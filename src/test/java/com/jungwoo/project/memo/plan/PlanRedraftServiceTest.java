package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.ai.AiConsultationClient;
import com.jungwoo.project.memo.ai.AiProposalMapper;
import com.jungwoo.project.memo.ai.AiProposalService;
import com.jungwoo.project.memo.ai.ContextChangeSuggestionService;
import com.jungwoo.project.memo.ai.domain.AiProposal;
import com.jungwoo.project.memo.ai.domain.AiProposalStatus;
import com.jungwoo.project.memo.ai.dto.AiProposalResponse;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.ServiceUnavailableException;
import com.jungwoo.project.memo.plan.PeriodPlanDraftGenerator.Generated;
import com.jungwoo.project.memo.plan.PeriodPlanDraftGenerator.Spec;
import com.jungwoo.project.memo.plan.domain.PlanIntensity;
import com.jungwoo.project.memo.plan.dto.PlanDraftResponse;
import com.jungwoo.project.memo.plan.dto.PlanRedraftRequest;
import com.jungwoo.project.memo.plan.provenance.PlanProvenanceCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 같은 조건으로 초안 다시 만들기(POST /api/plans/proposals/{id}/redraft).
 *
 * <p>증명하려는 것: 기간·강도·범위·지시·지정 자료는 <b>초안을 만든 요청</b>(plan_request_json)에서 오고, 본문은 바뀐 제외
 * 목록·지정 자료만 바꾼다. 상담 초안이면 그 대화에 이어 붙는다. 실패하면 기존 초안은 그대로다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlanRedraftServiceTest {

    private static final long USER = 5L;
    private static final long OLD = 77L;

    @Mock
    private PeriodPlanDraftGenerator generator;
    @Mock
    private AiConsultationClient aiConsultationClient;
    @Mock
    private AiProposalService aiProposalService;
    @Mock
    private AiProposalMapper aiProposalMapper;
    @Mock
    private PlanVersionService planVersionService;
    @Mock
    private PlanBlockGeneratorV0 blockGeneratorV0;
    @Mock
    private PlanningContextBuilder planningContextBuilder;
    @Mock
    private PlanJudgmentService planJudgmentService;
    @Mock
    private ContextChangeSuggestionService contextChangeSuggestionService;
    @Mock
    private PlanItemService planItemService;
    @Mock
    private PlanMaterialContextService materialContextService;

    private PlanDraftService service;

    private static final String STORED = """
            {"version":1,"source":"CONVERSATION","startDate":"2026-09-14","endDate":"2026-09-20","intensity":"FOCUSED",
             "title":null,"instruction":"아래는 대화에서 사용자가 말한 것이다.\\n- 자료구조 3주차 슬라이드 중심으로",
             "courseIds":[6],"excludeTopicIds":[5],"requestedMaterialIds":[30],"requestedSectionIds":[],
             "familiarityAnswer":null,"familiarityTopicIds":null,"conversationId":12}
            """;

    @BeforeEach
    void setUp() {
        service = new PlanDraftService(generator, aiConsultationClient, aiProposalService, aiProposalMapper,
                planVersionService, new PlanStrategyCodec(), blockGeneratorV0, planningContextBuilder,
                planJudgmentService, contextChangeSuggestionService, planItemService, new PlanProvenanceCodec(),
                materialContextService, new PlanGenerationProgress(),
                org.mockito.Mockito.mock(com.jungwoo.project.memo.ai.brief.PlanBriefService.class));
        when(aiConsultationClient.isConfigured()).thenReturn(true);
        when(planVersionService.resolveIntensity(anyLong(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(generator.generate(any(), any())).thenAnswer(inv -> new Generated(inv.getArgument(0), 600, 600, null, false,
                "다시 만든 계획", null, List.of()));
        when(aiProposalService.createFromItems(anyLong(), any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(AiProposalResponse.builder().proposalId(78L).items(List.of()).build());
        when(materialContextService.pendingMaterials(anyLong(), any())).thenReturn(List.of());
    }

    private void givenProposal(AiProposalStatus status, String requestJson) {
        AiProposal proposal = AiProposal.builder().proposalId(OLD).userId(USER).conversationId(12L).status(status)
                .planStartDate(LocalDate.of(2026, 9, 14)).planEndDate(LocalDate.of(2026, 9, 20))
                .planRequestJson(requestJson).build();
        when(aiProposalMapper.findByIdAndUserId(OLD, USER)).thenReturn(proposal);
        when(aiProposalMapper.findByIdAndUserIdForUpdate(OLD, USER)).thenReturn(proposal);
    }

    @Test
    void 저장된_요청으로_다시_만들고_본문의_제외_목록만_바꾸며_기존_초안을_폐기한다() {
        givenProposal(AiProposalStatus.PROPOSED, STORED);

        PlanDraftResponse response = service.redraft(USER, OLD, PlanRedraftRequest.builder()
                .excludeTopicIds(List.of(5L, 101L)).build());

        ArgumentCaptor<Spec> spec = ArgumentCaptor.forClass(Spec.class);
        verify(generator).generate(spec.capture(), any());
        assertThat(spec.getValue().start()).isEqualTo(LocalDate.of(2026, 9, 14));
        assertThat(spec.getValue().end()).isEqualTo(LocalDate.of(2026, 9, 20));
        assertThat(spec.getValue().intensity()).isEqualTo(PlanIntensity.FOCUSED);
        assertThat(spec.getValue().instruction()).contains("자료구조 3주차 슬라이드 중심으로");
        assertThat(spec.getValue().courseIds()).containsExactly(6L);
        assertThat(spec.getValue().excludeTopicIds()).containsExactly(5L, 101L);
        assertThat(spec.getValue().requestedMaterialIds()).containsExactly(30L);

        // 상담 초안은 같은 대화에 이어 붙고, 기존 초안은 같은 트랜잭션에서 폐기된다.
        verify(aiProposalService).createFromItems(eq(USER), eq(12L), isNull(), any(), any(), any(), any(), anyInt(), any());
        verify(aiProposalMapper).updateStatusAndRespondedAt(eq(OLD), eq(USER), eq(AiProposalStatus.DISMISSED), any());
        verify(aiProposalMapper).updatePlanRequest(eq(78L), eq(USER), contains("\"excludeTopicIds\":[5,101]"));
        assertThat(response.getRequestContext().getSource()).isEqualTo("CONVERSATION");
        assertThat(response.getRequestContext().getExcludedTopics())
                .extracting(PlanDraftResponse.ExcludedTopic::getTopicId).containsExactly(5L, 101L);
    }

    @Test
    void 본문이_비어_있으면_저장된_제외_목록과_지정_자료를_그대로_쓴다() {
        givenProposal(AiProposalStatus.PROPOSED, STORED);

        service.redraft(USER, OLD, new PlanRedraftRequest());

        ArgumentCaptor<Spec> spec = ArgumentCaptor.forClass(Spec.class);
        verify(generator).generate(spec.capture(), any());
        assertThat(spec.getValue().excludeTopicIds()).containsExactly(5L);
        assertThat(spec.getValue().requestedMaterialIds()).containsExactly(30L);
    }

    @Test
    void 요청이_저장되지_않은_옛_초안은_다시_만들지_않고_새로_만들기를_안내한다() {
        givenProposal(AiProposalStatus.PROPOSED, null);

        assertThatThrownBy(() -> service.redraft(USER, OLD, new PlanRedraftRequest()))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).getErrorCode()).isEqualTo(ErrorCode.PLAN_REDRAFT_CONTEXT_MISSING);
        verify(generator, never()).generate(any(), any());
    }

    @Test
    void 이미_확정했거나_바뀐_초안은_다시_만들지_않는다() {
        givenProposal(AiProposalStatus.APPLIED, STORED);

        assertThatThrownBy(() -> service.redraft(USER, OLD, new PlanRedraftRequest()))
                .extracting(e -> ((ConflictException) e).getErrorCode()).isEqualTo(ErrorCode.PLAN_DRAFT_ALREADY_RESOLVED);
        verify(generator, never()).generate(any(), any());
    }

    @Test
    void 생성이_실패하면_기존_초안은_폐기되지_않는다() {
        givenProposal(AiProposalStatus.PROPOSED, STORED);
        when(generator.generate(any(), any())).thenThrow(new ServiceUnavailableException(ErrorCode.PLAN_MATERIAL_SELECTION_FAILED));

        assertThatThrownBy(() -> service.redraft(USER, OLD, new PlanRedraftRequest()))
                .isInstanceOf(ServiceUnavailableException.class);
        verify(aiProposalService, never()).createFromItems(anyLong(), any(), any(), any(), any(), any(), any(), anyInt(), any());
        verify(aiProposalMapper, never()).updateStatusAndRespondedAt(anyLong(), anyLong(), any(), any());
    }

    @Test
    void 생성하는_사이_다른_요청이_초안을_바꿨으면_저장하지_않는다() {
        givenProposal(AiProposalStatus.PROPOSED, STORED);
        when(aiProposalMapper.findByIdAndUserIdForUpdate(OLD, USER)).thenReturn(AiProposal.builder().proposalId(OLD)
                .userId(USER).status(AiProposalStatus.DISMISSED).build());

        assertThatThrownBy(() -> service.redraft(USER, OLD, new PlanRedraftRequest()))
                .extracting(e -> ((ConflictException) e).getErrorCode()).isEqualTo(ErrorCode.PLAN_DRAFT_ALREADY_RESOLVED);
        verify(aiProposalService, never()).createFromItems(anyLong(), any(), any(), any(), any(), any(), any(), anyInt(), any());
    }
}
