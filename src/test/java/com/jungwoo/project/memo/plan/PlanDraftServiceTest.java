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

    private PlanDraftService service;

    @BeforeEach
    void setUp() {
        service = new PlanDraftService(aiConsultationClient, aiProposalService, aiProposalMapper,
                aiUsageLimitService, planVersionService, planReviewService, courseMapper,
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
