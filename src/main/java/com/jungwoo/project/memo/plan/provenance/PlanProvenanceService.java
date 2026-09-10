package com.jungwoo.project.memo.plan.provenance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.ai.AiProposalItemMapper;
import com.jungwoo.project.memo.ai.AiProposalMapper;
import com.jungwoo.project.memo.ai.UserContextMapper;
import com.jungwoo.project.memo.ai.domain.AiProposal;
import com.jungwoo.project.memo.ai.domain.AiProposalItem;
import com.jungwoo.project.memo.ai.dto.ProposalItemPayload;
import com.jungwoo.project.memo.commitment.CommitmentMapper;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.course.CourseMapper;
import com.jungwoo.project.memo.execution.ExecutionItemMapper;
import com.jungwoo.project.memo.execution.domain.ExecutionItem;
import com.jungwoo.project.memo.learning.CourseTopicMapper;
import com.jungwoo.project.memo.plan.dto.PlanProvenanceResponse;
import com.jungwoo.project.memo.plan.dto.PlanProvenanceResponse.ItemView;
import com.jungwoo.project.memo.plan.dto.PlanProvenanceResponse.LinkView;
import com.jungwoo.project.memo.plan.dto.PlanProvenanceResponse.SourceView;
import com.jungwoo.project.memo.routine.RoutineMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * "생성 시 참고한 정보"와 "이 항목의 근거"를 읽는 유일한 경로.
 *
 * <p>초안 화면(제안)과 적용된 항목 화면(계획·오늘)이 둘 다 여기를 지난다. 화면마다 자기
 * 방식으로 스냅샷을 해석하기 시작하면 같은 사실이 두 곳에서 다르게 보인다.
 *
 * <p>★ <b>당시 값과 지금 원본을 섞지 않는다.</b> providedValue는 그때 준 값 그대로이고,
 * link는 지금 열 수 있는지를 따로 확인한 결과다. 원본이 바뀌었다고 스냅샷을 갱신하지
 * 않는다 — 그러면 과거 제안이 현재 값으로 정당화된다(handoff §7).
 *
 * <p>★ 모든 조회가 userId로 좁혀진다. 남의 제안 id·실행 조각 id를 넣어도 404다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanProvenanceService {

    private final AiProposalMapper aiProposalMapper;
    private final AiProposalItemMapper aiProposalItemMapper;
    private final PlanProvenanceCodec codec;
    private final CourseMapper courseMapper;
    private final CourseTopicMapper courseTopicMapper;
    private final RoutineMapper routineMapper;
    private final CommitmentMapper commitmentMapper;
    private final ExecutionItemMapper executionItemMapper;
    private final UserContextMapper userContextMapper;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    /** 초안(제안) 하나의 생성 정보와 항목별 근거. */
    @Transactional(readOnly = true)
    public PlanProvenanceResponse forProposal(Long userId, Long proposalId) {
        AiProposal proposal = aiProposalMapper.findByIdAndUserId(proposalId, userId);
        if (proposal == null) {
            throw new NotFoundException(ErrorCode.AI_PROPOSAL_NOT_FOUND);
        }
        List<AiProposalItem> items = aiProposalItemMapper.findByProposalIdAndUserId(proposalId, userId);
        return build(userId, proposal, null, items);
    }

    /**
     * 적용된 실행 조각 하나의 근거. 계획 화면과 오늘 화면이 쓴다.
     *
     * <p>새 연결을 만들지 않고 ai_proposal_items.created_item_id를 되짚는다. 그 조각을
     * 만든 제안 항목이 없으면(직접 만든 조각, 출처 추적 이전의 조각) "기록 없음"이다 —
     * 지금 DB로 역추정해 채우지 않는다.
     */
    @Transactional(readOnly = true)
    public PlanProvenanceResponse forExecutionItem(Long userId, Long executionItemId) {
        ExecutionItem item = executionItemMapper.findByIdAndUserId(executionItemId, userId);
        if (item == null) {
            throw new NotFoundException(ErrorCode.EXECUTION_ITEM_NOT_FOUND);
        }
        List<AiProposalItem> origins = aiProposalItemMapper.findByCreatedItemIdAndUserId(executionItemId, userId);
        if (origins.isEmpty()) {
            return PlanProvenanceResponse.notRecorded(null, item.getPlanVersionId(), List.of());
        }
        AiProposalItem origin = origins.get(0);
        AiProposal proposal = aiProposalMapper.findByIdAndUserId(origin.getProposalId(), userId);
        if (proposal == null) {
            return PlanProvenanceResponse.notRecorded(null, item.getPlanVersionId(), List.of());
        }
        return build(userId, proposal, item.getPlanVersionId(), List.of(origin));
    }

    private PlanProvenanceResponse build(Long userId, AiProposal proposal, Long planVersionId,
                                         List<AiProposalItem> items) {
        List<ItemView> itemViews = new ArrayList<>();
        for (AiProposalItem item : items) {
            itemViews.add(toItemView(item));
        }

        PlanProvenance provenance = codec.fromJson(proposal.getPlanProvenanceJson());
        if (provenance == null) {
            // 이 기능이 생기기 전의 초안이거나 계획 경로가 아닌 제안이다.
            return PlanProvenanceResponse.notRecorded(proposal.getProposalId(), planVersionId, itemViews);
        }

        List<SourceView> sources = new ArrayList<>();
        for (ProvidedSource source : provenance.providedSources()) {
            sources.add(new SourceView(
                    source.refId(), source.sourceType(), source.sourceId(), source.sourceVersion(),
                    source.sourceUpdatedAt(), source.representation(), source.providedValue(),
                    source.promptLine(), resolveLink(userId, source)));
        }

        return new PlanProvenanceResponse(true, proposal.getProposalId(), planVersionId,
                provenance.generationId(), provenance.capturedAt(), provenance.timezone(),
                provenance.startDate(), provenance.endDate(), provenance.generator(),
                provenance.modelName(), sources, provenance.serverCalculations(), itemViews);
    }

    private ItemView toItemView(AiProposalItem item) {
        PlanItemEvidence evidence = codec.evidenceFromJson(item.getEvidenceJson());
        String title = titleOf(item);
        if (evidence == null) {
            return new ItemView(item.getProposalItemId(), item.getCreatedItemId(), title, item.getStatus(),
                    false, null, List.of(), null, List.of(), List.of(), null, List.of(), 0);
        }
        return new ItemView(item.getProposalItemId(), item.getCreatedItemId(), title, item.getStatus(),
                true, evidence.generationId(), evidence.refIds(), evidence.reason(), evidence.aiEstimates(),
                evidence.serverCalculationIds(), evidence.status(), evidence.staleReasons(),
                evidence.unknownRefCount());
    }

    /**
     * 화면에 붙일 항목 제목. 사용자가 고쳤으면 고친 제목이다 — 근거 옆에 옛 제목이 있으면
     * 어느 항목의 근거인지 알아보기 어렵다.
     */
    private String titleOf(AiProposalItem item) {
        String json = item.getEditedPayload() != null ? item.getEditedPayload() : item.getOriginalPayload();
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, ProposalItemPayload.class).title();
        } catch (Exception e) {
            log.debug("근거 조회: 제안 항목 payload를 읽지 못했다. proposalItemId={}", item.getProposalItemId());
            return null;
        }
    }

    /**
     * 지금 이 원본을 열 수 있는가.
     *
     * <p>깨진 링크를 만들지 않는 것이 요점이다. 지워졌거나 애초에 열 화면이 없는 종류는
     * 비활성으로 두고 이유를 말한다 — 당시 값은 그대로 보이므로 사용자가 잃는 것은 없다.
     */
    private LinkView resolveLink(Long userId, ProvidedSource source) {
        if (source.sourceId() == null) {
            return LinkView.none(switch (source.sourceType()) {
                case TURN_INPUT -> "이번 요청에서 직접 말한 내용이라 열 원본이 없어요";
                case PLAN_REVIEW -> "여러 기록을 서버가 요약한 문장이라 원본 한 곳이 없어요";
                default -> "가리킬 원본이 없어요";
            });
        }
        Long id = source.sourceId();
        return switch (source.sourceType()) {
            case COURSE -> courseMapper.findByIdAndUserId(id, userId) != null
                    ? LinkView.of("COURSE", id) : deleted();
            case TOPIC -> courseTopicMapper.findByIdAndUserId(id, userId) != null
                    ? LinkView.of("TOPIC", id) : deleted();
            case ROUTINE_OCCURRENCE -> routineMapper.findByIdAndUserId(id, userId) != null
                    ? LinkView.of("ROUTINE", id) : deleted();
            case COMMITMENT -> commitmentMapper.findByIdAndUserId(id, userId) != null
                    ? LinkView.of("COMMITMENT", id) : deleted();
            case EXECUTION_ITEM_FIXED, EXECUTION_ITEM_PLANNED ->
                    executionItemMapper.findByIdAndUserId(id, userId) != null
                            ? LinkView.of("EXECUTION_ITEM", id) : deleted();
            case USER_CONTEXT -> userContextMapper.findByIdAndUserId(id, userId) != null
                    ? LinkView.of("USER_CONTEXT", id) : deleted();
            // 자료 분석·과목 메모는 아직 단건으로 여는 화면이 없다. 없는 링크를 만들지 않는다.
            case COURSE_NOTE, MATERIAL_KEY_DATE -> LinkView.none("이 자료를 단건으로 여는 화면이 아직 없어요");
            case PLAN_REVIEW, TURN_INPUT -> LinkView.none("가리킬 원본이 없어요");
        };
    }

    private LinkView deleted() {
        return LinkView.none("원본이 지워졌거나 볼 수 없어요");
    }
}
