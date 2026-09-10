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
import com.jungwoo.project.memo.execution.ExecutionItemEventMapper;
import com.jungwoo.project.memo.execution.ExecutionItemMapper;
import com.jungwoo.project.memo.execution.domain.ExecutionEventActorType;
import com.jungwoo.project.memo.execution.domain.ExecutionItem;
import com.jungwoo.project.memo.execution.domain.ExecutionItemEvent;
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
    private final ExecutionItemEventMapper executionItemEventMapper;
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
     *
     * <p>★ created_item_id는 <b>조정 제안</b>(AI 이동·축소·보류)도 남긴다. 그쪽 행은 "이
     * 제안이 바꾼 대상"을 가리킬 뿐 이 조각을 만든 회차가 아니다. 그래서 최근 행이 아니라
     * 실제 CREATE 원본을 고른다 — 최근 행을 집으면 이동 뒤에 근거가 "기록 없음"으로 바뀐다.
     *
     * <p>적용된 뒤의 변경은 조각의 사건 기록에서 읽는다. 근거 JSON은 그대로 두고, 응답에
     * "적용한 뒤 무엇이 바뀌었는가"를 따로 붙인다.
     */
    @Transactional(readOnly = true)
    public PlanProvenanceResponse forExecutionItem(Long userId, Long executionItemId) {
        ExecutionItem item = executionItemMapper.findByIdAndUserId(executionItemId, userId);
        if (item == null) {
            throw new NotFoundException(ErrorCode.EXECUTION_ITEM_NOT_FOUND);
        }
        AiProposalItem origin = createOriginOf(userId, executionItemId);
        if (origin == null) {
            return PlanProvenanceResponse.notRecorded(null, item.getPlanVersionId(), List.of());
        }
        AiProposal proposal = aiProposalMapper.findByIdAndUserId(origin.getProposalId(), userId);
        if (proposal == null) {
            return PlanProvenanceResponse.notRecorded(null, item.getPlanVersionId(), List.of());
        }
        List<String> afterApply = changesAfterApply(userId, item);
        return build(userId, proposal, item.getPlanVersionId(), List.of(origin), item.getTitle(), afterApply);
    }

    /**
     * 이 조각을 <b>만든</b> 제안 항목. 같은 created_item_id를 가리키는 조정 행은 건너뛴다.
     *
     * <p>CREATE는 조각당 한 번뿐이라 여럿이 나올 수 없다. 만약 여럿이면 가장 이른 것이
     * 원본이다 — 나중 것은 데이터 이상이고, 그때도 최근 것을 고르지 않는다.
     */
    private AiProposalItem createOriginOf(Long userId, Long executionItemId) {
        List<AiProposalItem> rows = aiProposalItemMapper.findByCreatedItemIdAndUserId(executionItemId, userId);
        AiProposalItem origin = null;
        for (AiProposalItem row : rows) {
            if (!isCreate(row)) {
                continue;
            }
            if (origin == null || row.getProposalItemId() < origin.getProposalItemId()) {
                origin = row;
            }
        }
        return origin;
    }

    private boolean isCreate(AiProposalItem row) {
        if (row.getTargetItemId() != null) {
            return false;
        }
        String json = row.getOriginalPayload();
        if (json == null) {
            return false;
        }
        try {
            return !objectMapper.readValue(json, ProposalItemPayload.class).isAdjustment();
        } catch (Exception e) {
            log.debug("근거 조회: 제안 항목 payload를 읽지 못했다. proposalItemId={}", row.getProposalItemId());
            return false;
        }
    }

    /**
     * 적용된 뒤 이 조각에 일어난 변경. 사용자가 읽는 문장이다.
     *
     * <p>주체가 SYSTEM인 사건(롤링 배치 등)은 세지 않는다 — 그건 서버가 계획대로 시각을
     * 정한 것이지 내용이 바뀐 것이 아니다. 생성 사건도 세지 않는다.
     */
    private List<String> changesAfterApply(Long userId, ExecutionItem item) {
        List<String> changes = new ArrayList<>();
        for (ExecutionItemEvent event : executionItemEventMapper.findByExecutionItemIdAndUserId(
                item.getExecutionItemId(), userId)) {
            if (event.getActorType() == ExecutionEventActorType.SYSTEM
                    || event.getActorType() == ExecutionEventActorType.MIGRATION
                    || event.getEventType() == null) {
                continue;
            }
            String text = switch (event.getEventType()) {
                case MOVED -> "적용한 뒤 날짜·시각을 옮겼어요. 아래 배치 근거는 옮기기 전 상태예요.";
                case REDUCED -> "적용한 뒤 분량을 줄였어요. 아래는 줄이기 전 제안의 근거예요.";
                case SPLIT -> "적용한 뒤 여러 조각으로 나눴어요.";
                case PRIORITY_CHANGED -> "적용한 뒤 중요도를 바꿨어요.";
                case HOLD -> "적용한 뒤 보류했어요.";
                case CANCELLED -> "적용한 뒤 계획에서 뺐어요.";
                case DELETED -> "적용한 뒤 지웠어요.";
                case CREATED, RESUMED, REOPENED, RESTORED -> null;
            };
            if (text != null && !changes.contains(text)) {
                changes.add(text);
            }
        }
        return changes;
    }

    private PlanProvenanceResponse build(Long userId, AiProposal proposal, Long planVersionId,
                                         List<AiProposalItem> items) {
        return build(userId, proposal, planVersionId, items, null, List.of());
    }

    /**
     * @param currentTitle    적용된 조각의 지금 제목. null이면 제안 항목의 제목을 쓴다
     * @param afterApply      적용된 뒤 일어난 변경. 초안 조회는 빈 목록이다
     */
    private PlanProvenanceResponse build(Long userId, AiProposal proposal, Long planVersionId,
                                         List<AiProposalItem> items, String currentTitle,
                                         List<String> afterApply) {
        PlanProvenance provenance = codec.fromJson(proposal.getPlanProvenanceJson());
        String generationId = provenance != null ? provenance.generationId() : null;

        List<ItemView> itemViews = new ArrayList<>();
        for (AiProposalItem item : items) {
            itemViews.add(toItemView(item, generationId, currentTitle, afterApply));
        }

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

    /**
     * @param generationId 제안 행의 회차. 항목 근거가 다른 회차를 가리키면 그 refId는 이
     *                     스냅샷의 것이 아니므로 짝짓지 않는다 — 우연히 같은 번호("s1")가
     *                     있어도 다른 회차의 다른 줄이다
     */
    private ItemView toItemView(AiProposalItem item, String generationId, String currentTitle,
                                List<String> afterApply) {
        PlanItemEvidence evidence = codec.evidenceFromJson(item.getEvidenceJson());
        String title = currentTitle != null ? currentTitle : titleOf(item);
        if (evidence == null) {
            return new ItemView(item.getProposalItemId(), item.getCreatedItemId(), title, item.getStatus(),
                    false, null, List.of(), null, List.of(), List.of(), null, List.of(), 0, afterApply);
        }
        List<String> refIds = evidence.refIds();
        List<String> staleReasons = evidence.staleReasons();
        if (generationId != null && evidence.generationId() != null
                && !generationId.equals(evidence.generationId())) {
            log.warn("항목 근거의 회차가 제안의 회차와 다르다 — refId를 짝짓지 않는다. proposalItemId={}, "
                    + "item={}, proposal={}", item.getProposalItemId(), evidence.generationId(), generationId);
            refIds = List.of();
            staleReasons = new ArrayList<>(staleReasons);
            staleReasons.add("이 항목의 근거는 다른 생성 회차의 것이라 원본 정보와 짝지을 수 없어요.");
        }
        return new ItemView(item.getProposalItemId(), item.getCreatedItemId(), title, item.getStatus(),
                true, evidence.generationId(), refIds, evidence.reason(), evidence.aiEstimates(),
                evidence.serverCalculationIds(), evidence.status(), staleReasons,
                evidence.unknownRefCount(), afterApply);
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
