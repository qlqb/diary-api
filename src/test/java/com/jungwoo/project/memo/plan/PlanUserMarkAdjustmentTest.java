package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.ai.AiConsultationClient;
import com.jungwoo.project.memo.ai.AiUsageLimitService;
import com.jungwoo.project.memo.learning.domain.TopicProgressStatus;
import com.jungwoo.project.memo.learning.domain.TopicUserMark;
import com.jungwoo.project.memo.plan.PlanningContext.ContextLine;
import com.jungwoo.project.memo.plan.PlanningContext.CourseContext;
import com.jungwoo.project.memo.plan.PlanningContext.TopicContext;
import com.jungwoo.project.memo.plan.domain.AdjustedBy;
import com.jungwoo.project.memo.plan.domain.EvidenceType;
import com.jungwoo.project.memo.plan.domain.PlanIntensity;
import com.jungwoo.project.memo.plan.domain.PlanStrategy;
import com.jungwoo.project.memo.plan.domain.StrategySource;
import com.jungwoo.project.memo.plan.domain.Treatment;
import com.jungwoo.project.memo.scheduling.service.AvailabilityEstimateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「이미 알아요」를 누른 뒤 조각만 다시 만드는 경로.
 *
 * <p>여기서 지키는 것은 <b>판단이 흔들리지 않는가</b>다. 사용자가 가정 하나를 고쳤는데
 * 목표와 과목 순서까지 바뀌면, 무엇 때문에 계획이 달라졌는지 알 수 없게 된다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlanUserMarkAdjustmentTest {

    private static final Long USER_ID = 7L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 6, 21, 37);
    private static final LocalDate START = LocalDate.of(2026, 9, 7);
    private static final LocalDate END = LocalDate.of(2026, 9, 13);
    private static final LocalDateTime NEXT_CLASS = LocalDateTime.of(2026, 9, 8, 14, 0);

    @Mock
    private AiConsultationClient aiConsultationClient;
    @Mock
    private AiUsageLimitService aiUsageLimitService;

    private PlanJudgmentService service;

    @BeforeEach
    void setUp() {
        service = new PlanJudgmentService(aiConsultationClient, aiUsageLimitService);
        when(aiConsultationClient.isConfigured()).thenReturn(true);
    }

    @Test
    @DisplayName("「이미 알아요」한 항목은 취급이 SKIP으로 내려가고 근거가 남는다")
    void knownTopicIsAdjustedToSkip() {
        PlanStrategy adjusted = service.applyUserMarks(
                strategy(treatment(217L, Treatment.FULL), treatment(218L, Treatment.FULL)),
                context(topic(217L, "ADT", TopicUserMark.KNOWN), topic(218L, "재귀", null)));

        assertThat(treatmentOf(adjusted, 217L).treatment()).isEqualTo(Treatment.SKIP);
        assertThat(treatmentOf(adjusted, 217L).reason()).isEqualTo("이미 알고 있다고 표시했어요");
        assertThat(treatmentOf(adjusted, 217L).adjustedBy())
                .as("모델이 그렇게 본 것이 아니라 표시에 따라 조정된 것이다")
                .isEqualTo(AdjustedBy.SERVER);
        assertThat(treatmentOf(adjusted, 217L).evidence())
                .anySatisfy(e -> {
                    assertThat(e.type()).isEqualTo(EvidenceType.USER_MARK);
                    assertThat(e.value()).isEqualTo("KNOWN");
                    assertThat(e.refId()).isEqualTo(217L);
                });

        assertThat(treatmentOf(adjusted, 218L).treatment())
                .as("표식이 없는 항목은 건드리지 않는다")
                .isEqualTo(Treatment.FULL);
        assertThat(treatmentOf(adjusted, 218L).adjustedBy()).isNull();
    }

    @Test
    @DisplayName("「나중에」도 SKIP이지만 이유가 다르다 — 안다는 뜻이 아니다")
    void deferredTopicIsSkippedForADifferentReason() {
        PlanStrategy adjusted = service.applyUserMarks(
                strategy(treatment(217L, Treatment.FULL)),
                context(topic(217L, "ADT", TopicUserMark.DEFER)));

        assertThat(treatmentOf(adjusted, 217L).treatment()).isEqualTo(Treatment.SKIP);
        assertThat(treatmentOf(adjusted, 217L).reason()).isEqualTo("이번에는 빼기로 표시했어요");
        assertThat(treatmentOf(adjusted, 217L).evidence())
                .anySatisfy(e -> assertThat(e.value()).isEqualTo("DEFER"));
    }

    @Test
    @DisplayName("판단은 다시 하지 않는다 — 모델을 부르지 않는다")
    void theModelIsNotCalled() {
        service.applyUserMarks(strategy(treatment(217L, Treatment.FULL)),
                context(topic(217L, "ADT", TopicUserMark.KNOWN)));

        verify(aiConsultationClient, never()).streamTurn(any(), any(), anyInt());
    }

    @Test
    @DisplayName("목표·과목 순서·규칙은 그대로다 — 고친 것과 무관한 변화가 함께 오면 안 된다")
    void theRestOfTheJudgmentIsUntouched() {
        PlanStrategy original = strategy(treatment(217L, Treatment.FULL));

        PlanStrategy adjusted = service.applyUserMarks(original,
                context(topic(217L, "ADT", TopicUserMark.KNOWN)));

        assertThat(adjusted.goal()).isEqualTo(original.goal());
        assertThat(adjusted.strategySummary()).isEqualTo(original.strategySummary());
        assertThat(adjusted.courses()).isEqualTo(original.courses());
        assertThat(adjusted.planningRules()).isEqualTo(original.planningRules());
        assertThat(adjusted.strategySource()).isEqualTo(original.strategySource());
    }

    @Test
    @DisplayName("바꿀 것이 없으면 받은 전략을 그대로 돌려준다")
    void nothingToAdjustReturnsTheSameInstance() {
        PlanStrategy original = strategy(treatment(217L, Treatment.FULL));

        assertThat(service.applyUserMarks(original, context(topic(217L, "ADT", null))))
                .isSameAs(original);
    }

    @Test
    @DisplayName("내리기만 한다 — 표식이 없다고 모델의 SKIP을 되돌리지 않는다")
    void adjustmentOnlyGoesDown() {
        PlanStrategy adjusted = service.applyUserMarks(
                strategy(treatment(217L, Treatment.SKIP)),
                context(topic(217L, "ADT", null)));

        assertThat(treatmentOf(adjusted, 217L).treatment())
                .as("그 SKIP에는 다른 근거가 있었을 수 있고 여기서 판단할 방법이 없다")
                .isEqualTo(Treatment.SKIP);
    }

    // ===== fixture =====

    private static PlanStrategy.TopicTreatment treatmentOf(PlanStrategy strategy, Long topicId) {
        return strategy.topics().stream()
                .filter(t -> t.topicId().equals(topicId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("전략에 topicId=" + topicId + " 판단이 없다"));
    }

    private static PlanStrategy.TopicTreatment treatment(long topicId, Treatment treatment) {
        return new PlanStrategy.TopicTreatment(topicId, treatment, 1, "모델이 본 이유", List.of());
    }

    private static PlanStrategy strategy(PlanStrategy.TopicTreatment... topics) {
        return new PlanStrategy("다음 수업 따라가기", "필요한 것만", StrategySource.NEW, null, List.of(1L),
                List.of(new PlanStrategy.CourseStrategy(36L, 1, "화요일 전까지", "가장 빠른 수업")),
                List.of(topics), List.of("다음 수업 전 선수내용 완료"));
    }

    private static PlanningContext context(TopicContext... topics) {
        CourseContext course = new CourseContext(36L, "자료구조", null, NEXT_CLASS, 85L, 2, List.of(topics));
        return new PlanningContext(USER_ID, NOW, START, END, PlanIntensity.NORMAL,
                List.of(course), List.of(), new AvailabilityEstimateResult(List.of(), List.of()),
                List.<ContextLine>of(), null, null);
    }

    private static TopicContext topic(Long topicId, String title, TopicUserMark mark) {
        return new TopicContext(topicId, title, "2주차", 2, TopicProgressStatus.NOT_STARTED,
                null, mark, 0, 901L, "자료구조 2주차.pdf");
    }
}
