package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.ai.AiConsultationClient;
import com.jungwoo.project.memo.ai.AiProposalMapper;
import com.jungwoo.project.memo.ai.AiProposalService;
import com.jungwoo.project.memo.ai.AiStreamParser;
import com.jungwoo.project.memo.ai.AiUsageLimitService;
import com.jungwoo.project.memo.ai.dto.AiProposalResponse;
import com.jungwoo.project.memo.ai.dto.ProposalItem;
import com.jungwoo.project.memo.course.CourseMapper;
import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.execution.ExecutionItemMapper;
import com.jungwoo.project.memo.plan.domain.PlanIntensity;
import com.jungwoo.project.memo.plan.dto.PlanDraftRequest;
import com.jungwoo.project.memo.plan.dto.PlanDraftResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import com.jungwoo.project.memo.learning.dto.TopicResponse;
import com.jungwoo.project.memo.material.domain.CourseMaterialAnalysis;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 초안 생성에서 증명하려는 것은 하나다: **프리셋은 기준선이고 AI가 조정한 값이 저장된다.**
 *
 * 프리셋 숫자에 실사용 근거가 없으므로 최종값으로 강제하면 근거 없는 값이 계획을 지배한다.
 * 반대로 모델 출력을 그대로 믿어도 안 되므로, 이상한 값이 오면 기준선으로 되돌린다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlanDraftServiceTest {

    private static final Long USER_ID = 1L;
    private static final LocalDate START = LocalDate.of(2026, 8, 24);
    private static final LocalDate END = LocalDate.of(2026, 8, 30);
    /** NORMAL × 7일 = 600분. 기준선. */
    private static final int BASELINE = 600;

    @Mock
    private AiConsultationClient aiConsultationClient;
    @Mock
    private AiProposalService aiProposalService;
    @Mock
    private AiProposalMapper aiProposalMapper;
    @Mock
    private AiUsageLimitService aiUsageLimitService;
    @Mock
    private PlanVersionService planVersionService;
    @Mock
    private PlanReviewService planReviewService;
    @Mock
    private CourseMapper courseMapper;
    @Mock
    private ExecutionItemMapper executionItemMapper;
    @Mock
    private com.jungwoo.project.memo.learning.TopicService topicService;
    @Mock
    private com.jungwoo.project.memo.course.CourseNoteMapper courseNoteMapper;
    @Mock
    private com.jungwoo.project.memo.material.CourseMaterialAnalysisMapper analysisMapper;

    private PlanDraftService service;

    @BeforeEach
    void setUp() {
        service = new PlanDraftService(aiConsultationClient, aiProposalService, aiProposalMapper,
                aiUsageLimitService, planVersionService, planReviewService, courseMapper,
                topicService, courseNoteMapper, analysisMapper,
                executionItemMapper, Clock.fixed(Instant.parse("2026-08-23T09:00:00Z"), ZoneId.of("UTC")));
        ReflectionTestUtils.setField(service, "maxCompletionTokens", 2000);
        ReflectionTestUtils.setField(service, "requestTimeoutSeconds", 90);
        ReflectionTestUtils.setField(service, "modelName", "test-model");
        ReflectionTestUtils.setField(service, "defaultTimeZoneId", "Asia/Seoul");

        when(aiConsultationClient.isConfigured()).thenReturn(true);
        when(planVersionService.resolveIntensity(anyLong(), any())).thenReturn(PlanIntensity.NORMAL);
        when(courseMapper.findByUserIdAndStatus(anyLong(), any()))
                .thenReturn(List.of(Course.builder().courseId(6L).title("자료구조").build(),
                        Course.builder().courseId(7L).title("빅데이터분석").build()));
        when(executionItemMapper.findByUserIdAndPlanningRange(anyLong(), any(), any())).thenReturn(List.of());
        when(planReviewService.summarizeLatestForPrompt(anyLong())).thenReturn(null);
        // 자료에서 뽑아 둔 것이 없는 프로젝트가 기본값이다 — 있는 경우는 개별 테스트에서 채운다.
        when(topicService.getTopicTree(anyLong(), anyLong())).thenReturn(List.of());
        when(courseNoteMapper.findByCourseIdAndUserId(anyLong(), anyLong())).thenReturn(List.of());
        when(analysisMapper.findAppliedByCourseIdAndUserId(anyLong(), anyLong())).thenReturn(List.of());
        when(aiProposalService.createFromItems(anyLong(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(AiProposalResponse.builder().proposalId(77L).items(List.of()).build());
    }

    @Test
    void aiLowersTheBaseline_thatAdjustedValueIsStored_withReason() {
        givenAiResponse(390, "알바 일정을 고려해 낮게 잡았어요");

        PlanDraftResponse draft = service.createDraft(USER_ID, request("이번 주 알바가 많아서 시간이 없어"));

        assertThat(draft.getBaselineMinutes()).isEqualTo(BASELINE);
        assertThat(draft.getTargetMinutes()).as("AI가 정한 값이 최종 목표다").isEqualTo(390);
        assertThat(draft.getTargetMinutes()).isLessThan(draft.getBaselineMinutes());
        assertThat(draft.getTargetMinutesReason()).isEqualTo("알바 일정을 고려해 낮게 잡았어요");

        // ★ 저장되는 값은 기준선이 아니라 조정된 값이다.
        verify(aiProposalMapper).updatePlanMetadata(
                eq(77L), eq(USER_ID), eq(START), eq(END), eq(PlanIntensity.NORMAL), eq(390));
    }

    @Test
    void aiRaisesTheBaseline_isAlsoAccepted() {
        // 조정은 양방향이다. 낮추는 것만 허용하면 "여유가 있다"를 표현할 수 없다.
        givenAiResponse(900, "이번 주는 일정이 비어 여유가 있어요");

        PlanDraftResponse draft = service.createDraft(USER_ID, request(null));

        assertThat(draft.getTargetMinutes()).isEqualTo(900).isGreaterThan(BASELINE);
        assertThat(draft.getTargetMinutesReason()).isNotBlank();
    }

    @Test
    void aiKeepsTheBaseline_hasNoReason() {
        givenAiResponse(BASELINE, "조정 안 함");

        PlanDraftResponse draft = service.createDraft(USER_ID, request(null));

        assertThat(draft.getTargetMinutes()).isEqualTo(BASELINE);
        assertThat(draft.getTargetMinutesReason())
                .as("조정이 없으면 이유 줄을 그리지 않아야 하므로 null이다").isNull();
    }

    @Test
    void aiOmitsOrCorruptsTargetMinutes_fallsBackToBaseline() {
        // 조정 권한을 주는 것과 출력을 그대로 믿는 것은 다르다.
        givenAiResponse(null, "이유만 있고 값이 없음");
        assertThat(service.createDraft(USER_ID, request(null)).getTargetMinutes()).isEqualTo(BASELINE);

        givenAiResponse(0, "0분");
        assertThat(service.createDraft(USER_ID, request(null)).getTargetMinutes()).isEqualTo(BASELINE);

        givenAiResponse(-100, "음수");
        assertThat(service.createDraft(USER_ID, request(null)).getTargetMinutes()).isEqualTo(BASELINE);
    }

    @Test
    void promptCarriesTheBaselineAsAReferencePoint_notAsAFixedTarget() {
        givenAiResponse(BASELINE, null);

        service.createDraft(USER_ID, request("시험 전까지 자료구조 위주로"));

        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(aiConsultationClient).streamTurn(any(), userPrompt.capture(), anyInt());
        assertThat(userPrompt.getValue())
                .contains("기준 학습 시간은 약 600분")
                .contains("조정이 필요하면 조정하고")
                .contains("억지로 채우지 말고 적게 제안하라")
                .contains("시험 전까지 자료구조 위주로");
    }

    /**
     * 자료에서 뽑아 둔 것이 계획 프롬프트까지 실제로 도달하는지.
     *
     * 이게 없던 동안 모델이 아는 것은 과목명과 교재명뿐이었고, 실측해 보니 교재 장 번호를
     * 지어내고 실제 진도와 무관한 계획을 냈다. 사용자는 자료를 올리고 분석까지 적용했는데
     * 그 결과가 계획에 한 글자도 반영되지 않고 있었다.
     */
    @Test
    void promptCarriesTopicsAndScheduleFromAppliedAnalyses() {
        givenAiResponse(BASELINE, null);
        when(topicService.getTopicTree(USER_ID, 6L)).thenReturn(List.of(
                TopicResponse.builder().title("파이썬 기초").sourceLocator("2주차")
                        .children(List.of(TopicResponse.builder()
                                .title("변수·연산자·제어문").sourceLocator("2주차").children(List.of()).build()))
                        .build(),
                TopicResponse.builder().title("NumPy").sourceLocator("3주차").children(List.of()).build()));
        when(analysisMapper.findAppliedByCourseIdAndUserId(6L, USER_ID)).thenReturn(List.of(
                CourseMaterialAnalysis.builder().analysisId(1L)
                        .analysisJson("{\"keyDates\":[{\"title\":\"개강일\",\"date\":null,"
                                + "\"description\":\"1주차: 개강일(8/25)\"}]}")
                        .build()));

        service.createDraft(USER_ID, request(null));

        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(aiConsultationClient).streamTurn(any(), userPrompt.capture(), anyInt());
        assertThat(userPrompt.getValue())
                // 항목 옆 괄호가 "몇 주차 내용"을 그대로 전달한다.
                .contains("- 파이썬 기초 (2주차)")
                .contains("  - 변수·연산자·제어문 (2주차)")
                .contains("- NumPy (3주차)")
                // 개강일이 있어야 모델이 지금 몇 주차인지 계산할 수 있다.
                .contains("개강일: 1주차: 개강일(8/25)")
                .contains("몇 주차인지 계산하고")
                .contains("당겨오지 마라");
    }

    @Test
    void topicLinesAreCappedPerCourse_soManyProjectsDoNotBlowUpThePrompt() {
        givenAiResponse(BASELINE, null);
        List<TopicResponse> many = new java.util.ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            many.add(TopicResponse.builder().title("항목 " + i).children(List.of()).build());
        }
        when(topicService.getTopicTree(USER_ID, 6L)).thenReturn(many);

        service.createDraft(USER_ID, request(null));

        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(aiConsultationClient).streamTurn(any(), userPrompt.capture(), anyInt());
        // 뒤쪽 주차는 어차피 지금 계획할 범위가 아니다 — 접고 개수만 알린다.
        assertThat(userPrompt.getValue())
                .contains("- 항목 30")
                .doesNotContain("- 항목 31")
                .contains("… 외 10개");
    }

    @Test
    void itemCapIsLooseForPlans_andPassedToTheProposalService() {
        givenAiResponse(BASELINE, null);

        service.createDraft(USER_ID, request(null));

        // 7일 계획 → 15개. 개수는 주 제약이 아니라 폭주 방지선이다.
        verify(aiProposalService).createFromItems(anyLong(), any(), any(), any(), any(), any(), any(), eq(15));
    }

    @Test
    void longPlanGetsTheHigherItemCap() {
        givenAiResponse(2057, null);
        when(planVersionService.resolveIntensity(anyLong(), any())).thenReturn(PlanIntensity.NORMAL);

        service.createDraft(USER_ID, PlanDraftRequest.builder()
                .startDate(START).endDate(START.plusDays(29)).build());

        verify(aiProposalService).createFromItems(anyLong(), any(), any(), any(), any(), any(), any(), eq(30));
    }

    @Test
    void periodLongerThanThirtyOneDays_isRejected() {
        assertThatThrownBy(() -> service.createDraft(USER_ID, PlanDraftRequest.builder()
                .startDate(START).endDate(START.plusDays(31)).build()))
                .isInstanceOf(com.jungwoo.project.memo.common.exception.BadRequestException.class);
    }

    @Test
    void itemWithoutDate_becomesUnscheduledWithThePlanPeriod() {
        givenAiResponse(BASELINE, null);

        service.createDraft(USER_ID, request(null));

        ArgumentCaptor<List<ProposalItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(aiProposalService).createFromItems(anyLong(), any(), any(), captor.capture(), any(), any(), any(), anyInt());
        List<ProposalItem> items = captor.getValue();
        assertThat(items).hasSize(2);
        // 날짜를 안 준 항목은 UNSCHEDULED로, 준 항목은 DATE_ONLY로. 확정 시점에 솔버를
        // 돌리지 않으므로 TIME_FIXED는 여기서 만들지 않는다.
        assertThat(items).extracting(ProposalItem::placementType)
                .containsExactly(
                        com.jungwoo.project.memo.execution.domain.PlacementType.UNSCHEDULED,
                        com.jungwoo.project.memo.execution.domain.PlacementType.DATE_ONLY);
        assertThat(items.get(0).courseId()).as("프로젝트별 그룹핑을 위해 항목이 courseId를 갖는다")
                .isEqualTo(6L);
    }

    @Test
    void courseIdNotAmongTargets_isDiscarded() {
        // 모델이 존재하지 않는 id를 만들어내면 그 항목이 남의 프로젝트에 붙는다.
        givenAiResponse(BASELINE, null, 999L);

        service.createDraft(USER_ID, request(null));

        ArgumentCaptor<List<ProposalItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(aiProposalService).createFromItems(anyLong(), any(), any(), captor.capture(), any(), any(), any(), anyInt());
        assertThat(captor.getValue()).extracting(ProposalItem::courseId).containsOnlyNulls();
    }

    @Test
    void singleTargetCourse_fillsInNullCourseId() {
        // 후보가 하나뿐이면 추측이 아니라 유일한 답이다. 비워두면 초안 화면이 전부 "기타"가 된다.
        when(courseMapper.findByUserIdAndStatus(anyLong(), any()))
                .thenReturn(List.of(Course.builder().courseId(6L).title("자료구조").build()));
        givenAiResponse(BASELINE, null, null);

        service.createDraft(USER_ID, request(null));

        ArgumentCaptor<List<ProposalItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(aiProposalService).createFromItems(anyLong(), any(), any(), captor.capture(), any(), any(), any(), anyInt());
        assertThat(captor.getValue()).extracting(ProposalItem::courseId).containsOnly(6L);
    }

    @Test
    void multipleTargetCourses_doesNotGuess() {
        givenAiResponse(BASELINE, null, null);

        service.createDraft(USER_ID, request(null));

        ArgumentCaptor<List<ProposalItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(aiProposalService).createFromItems(anyLong(), any(), any(), captor.capture(), any(), any(), any(), anyInt());
        assertThat(captor.getValue()).extracting(ProposalItem::courseId).containsOnlyNulls();
    }

    /*
     * 학습 항목 제목을 카드로 옮긴 수준("교재 진도 복습 및 실습")이 나왔다. 무엇을 하고 어디까지
     * 하면 끝인지가 없었고, 근거 파일 대신 "교재의 같은 출처" 같은 표현을 모델이 만들었다.
     * 규칙 조각은 PlanItemPromptRules 한 곳에 있고 대화 경로 테스트도 같은 목록을 본다 —
     * 두 경로가 같은 규칙을 공유해야 하기 때문이다. 모델 준수 여부는 여기서 단정하지 않는다.
     */
    @Test
    void systemPrompt_requiresConcreteActionsAndCompletionCriteria_andForbidsInventedSourcesAndHousekeeping() {
        givenAiResponse(BASELINE, null);

        service.createDraft(USER_ID, request(null));

        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        verify(aiConsultationClient).streamTurn(systemPrompt.capture(), any(), anyInt());
        com.jungwoo.project.memo.ai.PlanItemPromptRules.assertCarriesRules(systemPrompt.getValue());
        // 이 경로에서 출처로 삼을 수 있는 것은 [대상 프로젝트]에 실린 것뿐이다.
        assertThat(systemPrompt.getValue())
                .contains("출처는 [대상 프로젝트]에 실린 학습 항목 제목과 그 옆 괄호의 위치만 쓴다")
                .contains("\"description\": \"실제로 할 행동 1~3개 · 완료: 확인 가능한 완료 기준\"");
    }

    /*
     * 수동 실행에서 5개 항목이 전부 60분이었다(합 300분 = 목표). [시간] 블록의 "합이 목표 근처가
     * 되게 하라"와 "잘게 쪼개지 마라"가 합쳐진 결과다. 목표를 예산으로 읽게 하고 시스템
     * 프롬프트에 작업 성격별 범위와 15분 우선을 넣는다. 5~120분 검증은 AiProposalService가
     * 그대로 한다.
     */
    @Test
    void prompts_treatTheTargetAsABudget_andGiveTaskSizedDurations() {
        givenAiResponse(BASELINE, null);

        service.createDraft(USER_ID, request(null));

        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(aiConsultationClient).streamTurn(systemPrompt.capture(), userPrompt.capture(), anyInt());
        com.jungwoo.project.memo.ai.PlanItemPromptRules.assertCarriesDurationRules(systemPrompt.getValue());
        assertThat(userPrompt.getValue())
                .contains("이 목표는 계획 예산이지 소진할 할당량이 아니다")
                .contains("목표를 채우려고 항목 시간을 늘리지 마라")
                // 기존 규칙은 유지한다.
                .contains("항목을 잘게 쪼개 개수를 늘리지 마라")
                .doesNotContain("목표 근처가 되게");
        // 5~120분 범위 안내도 그대로다.
        assertThat(systemPrompt.getValue()).contains("expectedMinutes는 5~120 사이여야 한다");
    }

    /*
     * 프롬프트가 description에 "행동 · 완료: 기준"을 요구하는데 변환이 reason을 우선하면
     * 모델이 규칙을 지켜도 제안에는 "왜 지금 하는지"만 남는다. description이 있으면 그것을,
     * 없을 때만 reason을 쓴다.
     */
    @Test
    void descriptionCarriesActionsAndCompletion_reasonIsOnlyAFallback() {
        String json = """
                {
                  "title": "이번 주 계획", "goalSummary": null, "targetMinutes": 600, "targetMinutesReason": null,
                  "items": [
                    {"title":"자료구조 · 반복문 코드의 Big-O 판단",
                     "description":"단일·중첩 반복문 코드 5개의 시간복잡도 판단 · 완료: 5개 중 4개 이상 설명 가능",
                     "expectedMinutes":40,"priority":"MUST","courseId":6,"scheduledDate":null,
                     "reason":"2주차 진도라서"},
                    {"title":"과제 2번","description":null,
                     "expectedMinutes":15,"priority":"SHOULD","courseId":6,"scheduledDate":null,
                     "reason":"마감이 있어서"}
                  ]
                }
                """;
        when(aiConsultationClient.streamTurn(any(), any(), anyInt()))
                .thenReturn(Flux.just(chatResponse("초안을 만들었어요\n" + AiStreamParser.DELIMITER + "\n" + json)));

        service.createDraft(USER_ID, request(null));

        ArgumentCaptor<List<ProposalItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(aiProposalService).createFromItems(anyLong(), any(), any(), captor.capture(), any(), any(), any(), anyInt());
        assertThat(captor.getValue()).extracting(ProposalItem::description).containsExactly(
                "단일·중첩 반복문 코드 5개의 시간복잡도 판단 · 완료: 5개 중 4개 이상 설명 가능",
                "마감이 있어서");
        // 짧은 항목(15분)은 이 경로에서 손대지 않고 그대로 넘긴다 — 범위 검사는 AiProposalService가 한다.
        assertThat(captor.getValue()).extracting(ProposalItem::expectedMinutes).containsExactly(40, 15);
    }

    // ===== fixture =====

    private PlanDraftRequest request(String instruction) {
        return PlanDraftRequest.builder()
                .startDate(START).endDate(END).instruction(instruction).build();
    }

    private void givenAiResponse(Integer targetMinutes, String reason) {
        givenAiResponse(targetMinutes, reason, 6L);
    }

    private void givenAiResponse(Integer targetMinutes, String reason, Long courseId) {
        String json = """
                {
                  "title": "이번 주 계획",
                  "goalSummary": "3장까지 훑기",
                  "targetMinutes": %s,
                  "targetMinutesReason": %s,
                  "items": [
                    {"title":"연결 리스트 구현","expectedMinutes":40,"priority":"MUST","courseId":%s,
                     "scheduledDate":null,"reason":"포인터를 이미 아니까"},
                    {"title":"과제 2번","expectedMinutes":60,"priority":"SHOULD","courseId":%s,
                     "scheduledDate":"2026-08-26","reason":"마감이 있어서"}
                  ]
                }
                """.formatted(
                targetMinutes == null ? "null" : targetMinutes.toString(),
                reason == null ? "null" : "\"" + reason + "\"",
                courseId == null ? "null" : courseId.toString(),
                courseId == null ? "null" : courseId.toString());
        when(aiConsultationClient.streamTurn(any(), any(), anyInt()))
                .thenReturn(Flux.just(chatResponse("초안을 만들었어요\n" + AiStreamParser.DELIMITER + "\n" + json)));
    }

    private ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
