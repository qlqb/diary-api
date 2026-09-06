package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.ai.AiConsultationClient;
import com.jungwoo.project.memo.ai.AiStreamParser;
import com.jungwoo.project.memo.ai.AiUsageLimitService;
import com.jungwoo.project.memo.ai.dto.ProposalItem;
import com.jungwoo.project.memo.execution.domain.PlacementType;
import com.jungwoo.project.memo.learning.domain.TopicProgressStatus;
import com.jungwoo.project.memo.plan.PlanningContext.CourseContext;
import com.jungwoo.project.memo.plan.PlanningContext.TopicContext;
import com.jungwoo.project.memo.plan.domain.ActionType;
import com.jungwoo.project.memo.plan.domain.DoneCriteriaSource;
import com.jungwoo.project.memo.plan.domain.EstimateConfidence;
import com.jungwoo.project.memo.plan.domain.PlanIntensity;
import com.jungwoo.project.memo.plan.domain.PlanStrategy;
import com.jungwoo.project.memo.plan.domain.StrategySource;
import com.jungwoo.project.memo.plan.domain.Treatment;
import com.jungwoo.project.memo.plan.dto.PlanItemDraft;
import com.jungwoo.project.memo.scheduling.domain.AvailabilityConfidence;
import com.jungwoo.project.memo.scheduling.domain.AvailabilitySource;
import com.jungwoo.project.memo.scheduling.domain.AvailabilityWindow;
import com.jungwoo.project.memo.scheduling.service.AvailabilityEstimateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 조각 생성. 이 스위트가 지키는 것은 <b>사용자가 앉아서 바로 시작할 수 있는가</b>다 —
 * 무엇을 볼지와 무엇을 하면 끝인지가 조각마다 있는가, 그리고 판단이 정한 것을 조각이
 * 뒤집지 않는가.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlanItemServiceTest {

    private static final Long USER_ID = 7L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 6, 21, 37);
    private static final LocalDate START = LocalDate.of(2026, 9, 7);
    private static final LocalDate END = LocalDate.of(2026, 9, 13);
    private static final LocalDateTime NEXT_CLASS = LocalDateTime.of(2026, 9, 8, 14, 0);

    @Mock
    private AiConsultationClient aiConsultationClient;
    @Mock
    private AiUsageLimitService aiUsageLimitService;

    private PlanItemService service;

    @BeforeEach
    void setUp() {
        service = new PlanItemService(aiConsultationClient, aiUsageLimitService);
        when(aiConsultationClient.isConfigured()).thenReturn(true);
    }

    // ===== 무엇을 만들고 무엇을 만들지 않는가 =====

    @Test
    @DisplayName("SKIP으로 정해진 항목은 프롬프트에 실리지도 않는다")
    void skippedTopicsNeverReachTheModel() {
        givenAi(itemJson(217L, "자료구조 · ADT", "READ", "표를 보고 복잡도를 말할 수 있음", 45));

        service.generate(
                strategy(treatment(217L, Treatment.FULL), treatment(218L, Treatment.SKIP)),
                context(topic(217L, "ADT"), topic(218L, "재귀")), 30);

        assertThat(promptSentToModel())
                .contains("topicId=217")
                .as("보여주고 빼라고 하는 것보다 안 보여주는 편이 확실하다")
                .doesNotContain("topicId=218");
    }

    @Test
    @DisplayName("계획 대상이 아닌 topicId를 지어내면 그 조각을 버린다")
    void inventedTopicIdsAreDropped() {
        givenAi(itemJson(999L, "없는 항목", "READ", "무언가를 함", 45));

        assertThat(service.generate(strategy(treatment(217L, Treatment.FULL)),
                context(topic(217L, "ADT")), 30)).isEmpty();
    }

    @Test
    @DisplayName("한 항목에 조각은 하나다 — 두 번째는 버린다")
    void oneItemPerTopic() {
        givenAi(items(
                item(217L, "첫 번째", "READ", "표를 보고 복잡도를 말할 수 있음", 45),
                item(217L, "두 번째", "PRACTICE", "문제를 풀어 답을 확인함", 45)));

        assertThat(service.generate(strategy(treatment(217L, Treatment.FULL)),
                context(topic(217L, "ADT")), 30))
                .extracting(PlanItemDraft::title).containsExactly("첫 번째");
    }

    // ===== 조각의 조건 =====

    @Test
    @DisplayName("자료 위치와 마감은 서버가 붙인다 — 모델이 고르지 않는다")
    void anchorAndDeadlineComeFromTheServer() {
        givenAi(itemJson(217L, "자료구조 · ADT", "READ", "표를 보고 복잡도를 말할 수 있음", 45));

        PlanItemDraft draft = service.generate(strategy(treatment(217L, Treatment.FULL)),
                context(topic(217L, "ADT")), 30).get(0);

        assertThat(draft.materialId()).isEqualTo(901L);
        assertThat(draft.sourceLocator()).isEqualTo("2주차");
        assertThat(draft.deadlineAt()).isEqualTo(NEXT_CLASS);
        assertThat(draft.topicId()).isEqualTo(217L);
        assertThat(draft.estimateConfidence())
                .as("자료 분량을 모르는 동안에는 전부 LOW다")
                .isEqualTo(EstimateConfidence.LOW);
    }

    @Test
    @DisplayName("완료 기준이 '공부하기' 류면 서버가 행동에 맞는 문장으로 바꾼다")
    void vagueDoneCriteriaIsReplaced() {
        givenAi(itemJson(217L, "자료구조 · ADT", "PRACTICE", "공부하기", 45));

        PlanItemDraft draft = service.generate(strategy(treatment(217L, Treatment.FULL)),
                context(topic(217L, "ADT")), 30).get(0);

        assertThat(draft.doneCriteria())
                .as("조각을 버리면 그 학습 항목이 계획에서 통째로 사라진다")
                .isEqualTo("자료를 덮고 ADT의 핵심을 말로 설명한다");
        assertThat(draft.doneCriteriaSource())
                .as("서버가 채웠다는 사실을 숨기지 않는다 — 화면이 `기본` 라벨을 붙인다")
                .isEqualTo(DoneCriteriaSource.DEFAULT);
    }

    @Test
    @DisplayName("기본 완료 기준은 자료에 무엇이 있는지 전제하지 않는다")
    void defaultDoneCriteriaAssumesNothingAboutTheMaterial() {
        givenAi(itemJson(217L, "자료구조 · ADT", "LAB", "공부하기", 45));

        assertThat(service.generate(strategy(treatment(217L, Treatment.FULL)),
                context(topic(217L, "ADT")), 30).get(0).doneCriteria())
                .as("문제가 없는 자료일 때 '문제를 푼다'는 없는 것을 찾게 만든다")
                .isEqualTo("자료를 덮고 ADT의 핵심을 말로 설명한다");
    }

    @Test
    @DisplayName("모델이 제대로 쓴 완료 기준은 MODEL로 남는다")
    void modelWrittenDoneCriteriaKeepsItsSource() {
        givenAi(itemJson(217L, "자료구조 · ADT", "READ", "표를 보고 복잡도를 말할 수 있음", 45));

        assertThat(service.generate(strategy(treatment(217L, Treatment.FULL)),
                context(topic(217L, "ADT")), 30).get(0).doneCriteriaSource())
                .isEqualTo(DoneCriteriaSource.MODEL);
    }

    @Test
    @DisplayName("완료 기준이 비어도 조각은 남는다")
    void missingDoneCriteriaIsFilledIn() {
        givenAi(itemJson(217L, "자료구조 · ADT", "READ", null, 45));

        assertThat(service.generate(strategy(treatment(217L, Treatment.FULL)),
                context(topic(217L, "ADT")), 30).get(0).doneCriteria())
                .isEqualTo("자료를 덮고 ADT의 핵심을 말로 설명한다");
    }

    @Test
    @DisplayName("자료를 모르는 항목은 READ로 두지 않는다 — 무엇을 펴야 할지 모른 채 시간을 받는다")
    void readWithoutASourceBecomesRecall() {
        givenAi(itemJson(300L, "무언가", "READ", "내용을 설명할 수 있음", 45));

        PlanItemDraft draft = service.generate(strategy(treatment(300L, Treatment.FULL)),
                context(topicWithoutSource(300L, "자료 없는 항목")), 30).get(0);

        assertThat(draft.actionType()).isEqualTo(ActionType.RECALL);
        assertThat(draft.materialId()).isNull();
    }

    @Test
    @DisplayName("모르는 행동 종류는 READ로 읽는다")
    void unknownActionTypeFallsBackToRead() {
        givenAi(itemJson(217L, "자료구조 · ADT", "WATCH_VIDEO", "표를 보고 복잡도를 말할 수 있음", 45));

        assertThat(service.generate(strategy(treatment(217L, Treatment.FULL)),
                context(topic(217L, "ADT")), 30).get(0).actionType())
                .isEqualTo(ActionType.READ);
    }

    // ===== 판단을 조각이 뒤집지 않는다 =====

    @Test
    @DisplayName("훑기로 정해진 항목은 30분을 넘기지 못한다")
    void skimIsCappedEvenIfTheModelAsksForMore() {
        givenAi(itemJson(217L, "자료구조 · ADT", "READ", "무엇을 다루는지 확인함", 90));

        assertThat(service.generate(strategy(treatment(217L, Treatment.SKIM)),
                context(topic(217L, "ADT")), 30).get(0).expectedMinutes())
                .as("같은 시간을 주면 훑기 판단이 문구로만 남는다")
                .isEqualTo(30);
    }

    @Test
    @DisplayName("항목 상한을 넘는 조각은 만들지 않는다")
    void itemCapIsEnforced() {
        givenAi(items(
                item(216L, "하나", "READ", "설명할 수 있음", 45),
                item(217L, "둘", "READ", "설명할 수 있음", 45),
                item(218L, "셋", "READ", "설명할 수 있음", 45)));

        assertThat(service.generate(
                strategy(treatment(216L, Treatment.FULL), treatment(217L, Treatment.FULL),
                        treatment(218L, Treatment.FULL)),
                context(topic(216L, "가"), topic(217L, "나"), topic(218L, "다")), 2))
                .hasSize(2);
    }

    @Test
    @DisplayName("만들 항목이 하나도 없으면 모델을 부르지 않는다")
    void noTargetsMeansNoCall() {
        assertThat(service.generate(strategy(treatment(217L, Treatment.SKIP)),
                context(topic(217L, "ADT")), 30)).isEmpty();
        verify(aiConsultationClient, org.mockito.Mockito.never()).streamTurn(any(), any(), anyInt());
    }

    // ===== 제안 후보로 옮길 때 =====

    @Test
    @DisplayName("조각은 항상 미배치다 — 시각은 Timefold가 정한다")
    void draftsAreAlwaysUnscheduled() {
        givenAi(itemJson(217L, "자료구조 · ADT", "READ", "표를 보고 복잡도를 말할 수 있음", 45));

        ProposalItem item = service.generate(strategy(treatment(217L, Treatment.FULL)),
                context(topic(217L, "ADT")), 30).get(0).toProposalItem();

        assertThat(item.placementType()).isEqualTo(PlacementType.UNSCHEDULED);
        assertThat(item.fixedStartAt()).isNull();
        assertThat(item.topicId()).isEqualTo(217L);
        assertThat(item.deadlineAt()).isEqualTo(NEXT_CLASS);
        assertThat(item.description())
                .as("자료 위치와 완료 기준이 본문에 함께 남아 스냅샷까지 간다")
                .contains("자료: 2주차")
                .contains("완료 기준: 표를 보고 복잡도를 말할 수 있음");
    }

    // ===== fixture =====

    private String promptSentToModel() {
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(aiConsultationClient).streamTurn(any(), prompt.capture(), anyInt());
        return prompt.getValue();
    }

    private void givenAi(String structuredJson) {
        when(aiConsultationClient.streamTurn(any(), any(), anyInt()))
                .thenReturn(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(
                        "조각을 만들었어요\n" + AiStreamParser.DELIMITER + "\n" + structuredJson))))));
    }

    private static String itemJson(long topicId, String title, String action, String done, int minutes) {
        return items(item(topicId, title, action, done, minutes));
    }

    private static String items(String... entries) {
        return "{\"title\":\"이번 주 계획\",\"items\":[" + String.join(",", entries) + "]}";
    }

    private static String item(long topicId, String title, String action, String done, int minutes) {
        return ("{\"topicId\":%d,\"title\":\"%s\",\"description\":\"실제로 할 행동\","
                + "\"doneCriteria\":%s,\"actionType\":\"%s\",\"expectedMinutes\":%d,"
                + "\"priority\":\"SHOULD\",\"reason\":\"다음 수업에 필요해요\"}")
                .formatted(topicId, title, done == null ? "null" : "\"" + done + "\"", action, minutes);
    }

    private static PlanStrategy.TopicTreatment treatment(long topicId, Treatment treatment) {
        return new PlanStrategy.TopicTreatment(topicId, treatment, 1, "이유", List.of());
    }

    private static PlanStrategy strategy(PlanStrategy.TopicTreatment... topics) {
        return new PlanStrategy("다음 수업 따라가기", "필요한 것만", StrategySource.NEW, null, List.of(),
                List.of(new PlanStrategy.CourseStrategy(36L, 1, "화요일 전까지", "가장 빠른 수업")),
                List.of(topics), List.of("다음 수업 전 선수내용 완료"));
    }

    private static PlanningContext context(TopicContext... topics) {
        List<AvailabilityWindow> windows = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            LocalDate date = START.plusDays(i);
            windows.add(new AvailabilityWindow(date.atTime(19, 0), date.atTime(22, 0),
                    AvailabilitySource.DEFAULT_INFERENCE, AvailabilityConfidence.LOW, "기본 시간대"));
        }
        AvailabilityEstimateResult availability = new AvailabilityEstimateResult(windows, List.of());
        CourseContext course = new CourseContext(36L, "자료구조", null, NEXT_CLASS, 85L, 2, List.of(topics));
        return new PlanningContext(USER_ID, NOW, START, END, PlanIntensity.NORMAL,
                List.of(course), AvailabilityDaySummary.format(START, END, availability),
                availability, List.of(), null, null);
    }

    private static TopicContext topic(Long topicId, String title) {
        return new TopicContext(topicId, title, "2주차", 2, TopicProgressStatus.NOT_STARTED,
                null, null, 0, 901L, "자료구조 2주차.pdf");
    }

    private static TopicContext topicWithoutSource(Long topicId, String title) {
        return new TopicContext(topicId, title, null, null, TopicProgressStatus.NOT_STARTED,
                null, null, 0, null, null);
    }
}
