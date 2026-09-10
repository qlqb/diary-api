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
import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.scheduling.domain.AvailabilityConfidence;
import com.jungwoo.project.memo.scheduling.domain.AvailabilitySource;
import com.jungwoo.project.memo.scheduling.domain.AvailabilityWindow;
import com.jungwoo.project.memo.scheduling.service.AvailabilityEstimateResult;
import com.jungwoo.project.memo.scheduling.service.AvailabilityEstimateService;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import com.jungwoo.project.memo.ai.domain.AiProposal;
import com.jungwoo.project.memo.ai.domain.AiProposalStatus;
import com.jungwoo.project.memo.plan.domain.PlanIntensity;
import com.jungwoo.project.memo.plan.domain.PlanStrategy;
import com.jungwoo.project.memo.plan.domain.StrategySource;
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
import com.jungwoo.project.memo.common.exception.ServiceUnavailableException;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
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
    /** 기본 stub 가용 925분 × NORMAL 65% = 601.25 → 15분 내림 600분. */
    private static final int BASELINE = 600;
    private static final int DEFAULT_AVAILABLE = 925;

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
    @Mock
    private com.jungwoo.project.memo.material.CourseMaterialMapper courseMaterialMapper;
    @Mock
    private AvailabilityEstimateService availabilityEstimateService;
    /**
     * 기본 경로(AI)만 검증하는 스위트다. v0는 PlanBlockGeneratorV0Test가, 판단은
     * PlanJudgmentServiceTest가 따로 본다 — 여기서는 그 경로들이 켜지지 않는다.
     */
    @Mock
    private PlanBlockGeneratorV0 blockGeneratorV0;
    @Mock
    private PlanningContextBuilder planningContextBuilder;
    @Mock
    private PlanJudgmentService planJudgmentService;
    @Mock
    private com.jungwoo.project.memo.ai.ContextChangeSuggestionService contextChangeSuggestionService;
    @Mock
    private PlanItemService planItemService;

    private PlanDraftService service;

    @BeforeEach
    void setUp() {
        // 생성 규칙은 generator에, 저장은 service에 있다. 두 진입점(계획 화면·대화)이 같은
        // generator를 지나므로 프롬프트 단언은 generator 쪽 mock(aiConsultationClient)에서 잡는다.
        PeriodPlanDraftGenerator generator = new PeriodPlanDraftGenerator(aiConsultationClient,
                aiUsageLimitService, planReviewService, courseMapper, topicService, courseNoteMapper,
                analysisMapper, courseMaterialMapper, executionItemMapper, availabilityEstimateService,
                Clock.fixed(Instant.parse("2026-08-23T09:00:00Z"), ZoneId.of("UTC")));
        ReflectionTestUtils.setField(generator, "maxCompletionTokens", 2000);
        ReflectionTestUtils.setField(generator, "requestTimeoutSeconds", 90);
        ReflectionTestUtils.setField(generator, "modelName", "test-model");
        ReflectionTestUtils.setField(generator, "defaultTimeZoneId", "Asia/Seoul");
        service = new PlanDraftService(generator, aiConsultationClient, aiProposalService, aiProposalMapper,
                planVersionService, new PlanStrategyCodec(), blockGeneratorV0,
                planningContextBuilder, planJudgmentService, contextChangeSuggestionService, planItemService,
                new com.jungwoo.project.memo.plan.provenance.PlanProvenanceCodec());

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
        when(aiProposalService.createFromItems(anyLong(), any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(AiProposalResponse.builder().proposalId(77L).items(List.of()).build());
        givenAvailableMinutes(DEFAULT_AVAILABLE);
    }

    /** 기간 안에 이만큼 남는다고 가용시간 서비스가 답한다. 0이면 구간이 없다. */
    private void givenAvailableMinutes(int minutes) {
        List<AvailabilityWindow> windows = minutes <= 0 ? List.of() : List.of(new AvailabilityWindow(
                START.atTime(9, 0), START.atTime(9, 0).plusMinutes(minutes),
                AvailabilitySource.DEFAULT_INFERENCE, AvailabilityConfidence.LOW, "기본 시간대"));
        when(availabilityEstimateService.estimate(anyLong(), any(), any(), any(), any()))
                .thenReturn(new AvailabilityEstimateResult(windows, List.of()));
    }

    // ===== 강도: 추정 가용시간의 비율 =====

    /*
     * 예전에는 고정 기준선을 모델에게 주고 조정하게 했다. 모델은 시간표를 못 보고, 사용자는 왜
     * 그 숫자인지 알 수 없었다. 이제 학습 예산은 서버가 추정 남는 시간 × 강도 비율로 정한다.
     * 모델이 targetMinutes를 적어 보내도 무시한다.
     */
    @Test
    void targetIsComputedFromAvailability_andTheModelCannotOverrideIt() {
        givenAvailableMinutes(600);
        givenAiResponse(555, "모델이 마음대로 정한 값");

        PlanDraftResponse draft = service.createDraft(USER_ID, request(null));

        assertThat(draft.getEstimatedAvailableMinutes()).isEqualTo(600);
        assertThat(draft.getTargetMinutes()).as("NORMAL 65% of 600").isEqualTo(390);
        assertThat(draft.getBaselineMinutes()).isEqualTo(390);
        assertThat(draft.getReservedBufferMinutes()).isEqualTo(210);
        assertThat(draft.getTargetMinutesReason()).isNull();
        assertThat(draft.getAvailabilityConfidenceSummary()).contains("기본 시간대");
        verify(aiProposalMapper).updatePlanMetadata(
                eq(77L), eq(USER_ID), eq(START), eq(END), eq(PlanIntensity.NORMAL), eq(390), isNull(), any());
    }

    @Test
    void intensityRatios_light40_normal65_focused85() {
        givenAvailableMinutes(600);
        givenAiResponse(null, null);

        when(planVersionService.resolveIntensity(anyLong(), any())).thenReturn(PlanIntensity.LIGHT);
        assertThat(service.createDraft(USER_ID, request(null)).getTargetMinutes()).isEqualTo(240);
        when(planVersionService.resolveIntensity(anyLong(), any())).thenReturn(PlanIntensity.NORMAL);
        assertThat(service.createDraft(USER_ID, request(null)).getTargetMinutes()).isEqualTo(390);
        when(planVersionService.resolveIntensity(anyLong(), any())).thenReturn(PlanIntensity.FOCUSED);
        assertThat(service.createDraft(USER_ID, request(null)).getTargetMinutes()).isEqualTo(510);
    }

    @Test
    void targetIsFlooredToFifteenMinutes_andShrinksWhenBusyWindowsGrow() {
        givenAiResponse(null, null);

        givenAvailableMinutes(925);
        assertThat(service.createDraft(USER_ID, request(null)).getTargetMinutes()).isEqualTo(600);
        // 수업·알바가 늘어 남는 시간이 줄면 예산도 준다 — 고정 기준선에는 없던 성질이다.
        givenAvailableMinutes(600);
        assertThat(service.createDraft(USER_ID, request(null)).getTargetMinutes()).isEqualTo(390);
    }

    @Test
    void zeroAvailability_doesNotCallTheModel_andReturnsGuidanceInsteadOfAnEmptyDraft() {
        givenAvailableMinutes(0);

        PlanDraftResponse draft = service.createDraft(USER_ID, request(null));

        assertThat(draft.isNoAvailableTime()).isTrue();
        assertThat(draft.getProposal()).isNull();
        assertThat(draft.getProposalId()).isNull();
        assertThat(draft.getTargetMinutes()).isZero();
        assertThat(draft.getAvailabilityConfidenceSummary()).isEqualTo("배치할 수 있는 시간이 없음");
        verify(aiConsultationClient, never()).streamTurn(any(), any(), anyInt());
        verify(aiProposalService, never()).createFromItems(anyLong(), any(), any(), any(), any(), any(), any(), anyInt(), any());
    }

    // ===== 항목 상한과 예산 =====

    /*
     * 7일 계획의 상한은 30개다. 15개였을 때는 강도 목표를 15로 나누면 항목당 평균 80~105분이
     * 되어 "짧은 회수 15~30분"을 요구하는 프롬프트 규칙과 상한이 서로를 무효화했다. 아래 값은
     * 기본 가용시간(평일 180분·주말 480분 → 7일 1,860분) 기준이다.
     */
    @Test
    void sevenDayPlan_capIsThirty_soEveryIntensityCanUseVariedLengths() {
        givenAiResponse(null, null);
        givenAvailableMinutes(1860);

        record Case(PlanIntensity intensity, int target, double averageAtCap) { }
        List<Case> cases = List.of(
                new Case(PlanIntensity.LIGHT, 735, 24.5),
                new Case(PlanIntensity.NORMAL, 1200, 40.0),
                new Case(PlanIntensity.FOCUSED, 1575, 52.5));

        for (Case c : cases) {
            when(planVersionService.resolveIntensity(anyLong(), any())).thenReturn(c.intensity());

            PlanDraftResponse draft = service.createDraft(USER_ID, request(null));

            assertThat(draft.getTargetMinutes()).as("%s 목표", c.intensity()).isEqualTo(c.target());
            assertThat(draft.isTargetCappedByItemLimit()).as("%s는 상한에 닿지 않는다", c.intensity()).isFalse();
            // 상한 30개를 다 쓰면 항목당 평균이 이 값이다 — 참고 범위(15~90분) 안이다.
            assertThat(c.target() / 30.0).as("%s 30개 평균", c.intensity())
                    .isEqualTo(c.averageAtCap()).isBetween(15.0, 90.0);
            // 최소 개수(항목 120분 상한 기준)도 30개 안이다 — 개수를 못 채워 실패하지 않는다.
            assertThat((int) Math.ceil(c.target() / 120.0)).as("%s 최소 개수", c.intensity())
                    .isLessThanOrEqualTo(30);
        }
        // 상한은 기간과 무관하게 하나다.
        verify(aiProposalService, times(3))
                .createFromItems(anyLong(), any(), any(), any(), any(), any(), any(), eq(30), any());
    }

    @Test
    void thirtyDayPlan_usesTheSameCap() {
        givenAiResponse(null, null);
        givenAvailableMinutes(1860);

        service.createDraft(USER_ID, PlanDraftRequest.builder()
                .startDate(START).endDate(START.plusDays(29)).build());

        verify(aiProposalService).createFromItems(anyLong(), any(), any(), any(), any(), any(), any(), eq(30), any());
    }

    /*
     * 예산이 "30개 × 120분 = 3,600분"을 넘으면 한 제안에 담기지 않는다. 거절하지 않고 예산을
     * 상한으로 깎은 뒤 그 사실을 응답과 프롬프트에 싣는다 — 31일 집중은 정상적인 조합이고,
     * 400을 내면 "긴 계획은 만들 수 없다"가 된다.
     */
    @Test
    void budgetAboveTheCap_isClampedAndReported_notRejected() {
        givenAiResponse(null, null);
        givenAvailableMinutes(7980);
        when(planVersionService.resolveIntensity(anyLong(), any())).thenReturn(PlanIntensity.FOCUSED);

        PlanDraftResponse draft = service.createDraft(USER_ID, PlanDraftRequest.builder()
                .startDate(START).endDate(START.plusDays(30)).build());

        // 7,980 × 85% = 6,783 → 15분 내림 6,780. 상한 3,600으로 깎인다.
        assertThat(draft.getTargetMinutes()).isEqualTo(3600);
        assertThat(draft.isTargetCappedByItemLimit()).isTrue();
        assertThat(draft.getUncoveredMinutes()).isEqualTo(6780 - 3600);
        assertThat(draft.getEstimatedAvailableMinutes()).isEqualTo(7980);
        // 저장되는 목표도 깎인 값이다 — 스냅샷과 화면이 어긋나지 않는다.
        verify(aiProposalMapper).updatePlanMetadata(eq(77L), eq(USER_ID), any(), any(),
                eq(PlanIntensity.FOCUSED), eq(3600), isNull(), any());

        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(aiConsultationClient).streamTurn(any(), userPrompt.capture(), anyInt());
        assertThat(userPrompt.getValue())
                .contains("[분량 안내]")
                .contains("기간 전체를 빈틈없이 채우려 하지 말고");
    }

    @Test
    void budgetWithinTheCap_reportsNoCapping() {
        givenAiResponse(null, null);
        givenAvailableMinutes(1860);
        when(planVersionService.resolveIntensity(anyLong(), any())).thenReturn(PlanIntensity.FOCUSED);

        PlanDraftResponse draft = service.createDraft(USER_ID, request(null));

        assertThat(draft.isTargetCappedByItemLimit()).isFalse();
        // 0분을 담지 못했다는 줄을 그리지 않도록 null이다.
        assertThat(draft.getUncoveredMinutes()).isNull();
        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(aiConsultationClient).streamTurn(any(), userPrompt.capture(), anyInt());
        assertThat(userPrompt.getValue()).doesNotContain("[분량 안내]");
    }

    @Test
    void prompt_saysTheCapIsAMaximum_notATarget() {
        givenAiResponse(null, null);

        service.createDraft(USER_ID, request(null));

        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(aiConsultationClient).streamTurn(any(), userPrompt.capture(), anyInt());
        assertThat(userPrompt.getValue())
                .contains("항목은 최대 30개다. 이것은 만들어야 할 개수가 아니라 넘으면 안 되는 최대치다")
                .contains("각 작업에 실제로 필요한 길이를 먼저 정한 다음 필요한 만큼만 만들어라")
                .contains("15~30분짜리 짧은 항목을 넣기 위해 다른 항목을 길게 부풀리지 마라");
    }

    @Test
    void promptCarriesTheAvailabilityAndTheBudget_asABudgetNotAQuota() {
        givenAiResponse(BASELINE, null);

        service.createDraft(USER_ID, request("시험 전까지 자료구조 위주로"));

        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(aiConsultationClient).streamTurn(any(), userPrompt.capture(), anyInt());
        assertThat(userPrompt.getValue())
                .contains("추정 남는 시간은 약 925분")
                .contains("강도 NORMAL(남는 시간의 65%) 기준 학습 예산은 600분")
                .contains("[이번 기간에 이미 등록된 일정]")
                .contains("[남는 시간(추정)]")
                .contains("합계 약 925분")
                .contains("억지로 채우지 말고 적게 제안하라")
                .contains("시험 전까지 자료구조 위주로")
                // 모델에게 숫자를 다시 정하라고 하지 않는다.
                .doesNotContain("조정이 필요하면 조정하고");
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

        // 기간과 무관하게 30개. 개수는 주 제약이 아니라 폭주 방지선이다.
        verify(aiProposalService).createFromItems(anyLong(), any(), any(), any(), any(), any(), any(), eq(30), any());
    }

    @Test
    void longPlanGetsTheHigherItemCap() {
        givenAiResponse(2057, null);
        when(planVersionService.resolveIntensity(anyLong(), any())).thenReturn(PlanIntensity.NORMAL);

        service.createDraft(USER_ID, PlanDraftRequest.builder()
                .startDate(START).endDate(START.plusDays(29)).build());

        verify(aiProposalService).createFromItems(anyLong(), any(), any(), any(), any(), any(), any(), eq(30), any());
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
        verify(aiProposalService).createFromItems(anyLong(), any(), any(), captor.capture(), any(), any(), any(), anyInt(), any());
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
        verify(aiProposalService).createFromItems(anyLong(), any(), any(), captor.capture(), any(), any(), any(), anyInt(), any());
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
        verify(aiProposalService).createFromItems(anyLong(), any(), any(), captor.capture(), any(), any(), any(), anyInt(), any());
        assertThat(captor.getValue()).extracting(ProposalItem::courseId).containsOnly(6L);
    }

    @Test
    void multipleTargetCourses_doesNotGuess() {
        givenAiResponse(BASELINE, null, null);

        service.createDraft(USER_ID, request(null));

        ArgumentCaptor<List<ProposalItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(aiProposalService).createFromItems(anyLong(), any(), any(), captor.capture(), any(), any(), any(), anyInt(), any());
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
        verify(aiProposalService).createFromItems(anyLong(), any(), any(), captor.capture(), any(), any(), any(), anyInt(), any());
        assertThat(captor.getValue()).extracting(ProposalItem::description).containsExactly(
                "단일·중첩 반복문 코드 5개의 시간복잡도 판단 · 완료: 5개 중 4개 이상 설명 가능",
                "마감이 있어서");
        // 짧은 항목(15분)은 이 경로에서 손대지 않고 그대로 넘긴다 — 범위 검사는 AiProposalService가 한다.
        assertThat(captor.getValue()).extracting(ProposalItem::expectedMinutes).containsExactly(40, 15);
    }

    /*
     * 대화 경로는 generate와 persist를 나눠 부른다. persist가 제안을 그 대화와 ASSISTANT 메시지에
     * 연결하고, 상한(15/30)과 계획 메타데이터는 계획 화면과 같은 값이어야 한다 — 어느 탭에서
     * 시작하든 같은 제안이다.
     */
    @Test
    void persist_fromAConversation_linksTheProposal_andKeepsThePlanCapAndMetadata() {
        givenAiResponse(BASELINE, null);

        PeriodPlanDraftGenerator.Generated generated = service.generate(USER_ID, request("집중으로"));
        service.persist(USER_ID, generated, 42L, 4201L);

        verify(aiProposalService).createFromItems(eq(USER_ID), eq(42L), eq(4201L), any(), any(), eq(START), any(), eq(30), any());
        verify(aiProposalMapper).updatePlanMetadata(eq(77L), eq(USER_ID), eq(START), eq(END),
                eq(PlanIntensity.NORMAL), eq(BASELINE), isNull(), any());
        // generate는 DB에 쓰지 않는다 — 저장은 persist 한 곳뿐이다.
        verify(aiProposalService, times(1)).createFromItems(anyLong(), any(), any(), any(), any(), any(), any(), anyInt(), any());
    }

    // ===== fixture =====

    private PlanDraftRequest request(String instruction) {
        return PlanDraftRequest.builder()
                .startDate(START).endDate(END).instruction(instruction).build();
    }

    /*
     * 2026-09-05: 항목 상한을 15 -> 30으로 올린 뒤 9일짜리 계획 요청이 items[5]에서 잘렸다.
     * 그때는 잘린 JSON이 그냥 Jackson 파싱 실패로만 보고돼, 실제 원인(토큰 예산)이 스택트레이스
     * 뒤에 숨었다. 이제는 finishReason=LENGTH를 먼저 보고 "잘렸다"고 말한다.
     */
    @Test
    void createDraft_whenModelOutputIsTruncatedByTokenLimit_failsWithoutPretendingItIsBadJson() {
        String truncated = """
                {
                  "title": "이번 주 계획",
                  "goalSummary": "3장까지 훑기",
                  "targetMinutes": 600,
                  "items": [
                    {"title":"연결 리스트 구현","expectedMinutes":40,"priority":"MUST","courseId":6,
                     "scheduledDate":null,"reason":"포인터를 이미 아니까"},
                    {"title":"과제 2번","expectedMinutes":60,"priority":"SHOULD","courseId":6,
                     "scheduledDate":"2026-08-26","reason":"마감이""";
        when(aiConsultationClient.streamTurn(any(), any(), anyInt())).thenReturn(Flux.just(
                truncatedChatResponse("초안을 만들었어요\n" + AiStreamParser.DELIMITER + "\n" + truncated)));

        assertThatThrownBy(() -> service.createDraft(USER_ID, request(null)))
                .isInstanceOf(ServiceUnavailableException.class);

        // 잘린 응답으로 반쪽짜리 계획을 저장하지 않는다.
        verify(aiProposalService, never()).createFromItems(
                anyLong(), any(), any(), any(), any(), any(), any(), anyInt(), any());
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

    /** 출력이 상한에서 끊긴 응답. OpenAI는 마지막 청크의 finishReason에만 LENGTH를 채운다. */
    private ChatResponse truncatedChatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text),
                ChatGenerationMetadata.builder().finishReason("LENGTH").build())));
    }

    // ===== 조각만 재생성 =====

    @Test
    void regenerateItems_doesNotRunTheJudgmentAgain() {
        AiProposal proposal = new AiProposal();
        proposal.setProposalId(77L);
        proposal.setStatus(AiProposalStatus.PROPOSED);
        proposal.setPlanStartDate(START);
        proposal.setPlanEndDate(END);
        proposal.setPlanIntensity(PlanIntensity.NORMAL);
        proposal.setPlanStrategyJson(new PlanStrategyCodec().toJson(
                new PlanStrategy("목표", "요약", StrategySource.NEW, null, List.of(), List.of(),
                        List.of(), List.of())));
        when(aiProposalMapper.findByIdAndUserId(77L, USER_ID)).thenReturn(proposal);
        when(planningContextBuilder.build(any())).thenReturn(emptyContext());
        when(planJudgmentService.applyUserMarks(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(planItemService.generate(any(), any(), anyInt())).thenReturn(List.of());

        service.regenerateItems(USER_ID, 77L);

        // 사용자가 가정 하나를 고쳤을 뿐인데 목표와 과목 순서까지 흔들리면, 무엇 때문에
        // 계획이 바뀌었는지 알 수 없게 된다.
        verify(planJudgmentService, never()).judge(any());
        verify(planJudgmentService, never()).judge(any(), anyBoolean());
        verify(planJudgmentService).applyUserMarks(any(), any());
        verify(planItemService).generate(any(), any(), anyInt());
    }

    @Test
    void regenerateItems_rejectsADraftThatHasNoJudgment() {
        AiProposal proposal = new AiProposal();
        proposal.setProposalId(77L);
        proposal.setStatus(AiProposalStatus.PROPOSED);
        proposal.setPlanStartDate(START);
        proposal.setPlanEndDate(END);
        when(aiProposalMapper.findByIdAndUserId(77L, USER_ID)).thenReturn(proposal);

        // 다시 만들 근거가 없다. 새 초안을 만들어야 한다.
        assertThatThrownBy(() -> service.regenerateItems(USER_ID, 77L))
                .isInstanceOf(BadRequestException.class);
    }

    private PlanningContext emptyContext() {
        return new PlanningContext(USER_ID, START.atStartOfDay(), START, END, PlanIntensity.NORMAL,
                List.of(), List.of(), new AvailabilityEstimateResult(List.of(), List.of()),
                List.of(), null, null);
    }
}
