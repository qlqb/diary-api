package com.jungwoo.project.memo.ai.draft;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.ai.ScheduleSuggestionService;
import com.jungwoo.project.memo.ai.dto.ScheduleSuggestion;
import com.jungwoo.project.memo.ai.dto.ScheduleSuggestionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 턴 마무리 트랜잭션 안에서 draft를 저장하고, PROPOSE 대상은 proposal로 승격한다.
 *
 * <p><b>이 클래스가 promotion 트랜잭션의 소유자다.</b> 그 턴에 PROPOSE된 draft 전부(타입 혼합 가능)를
 * 하나의 트랜잭션에서 처리한다: ROUTINE/SCHEDULE → {@link ScheduleSuggestionService#createFromDraft}
 * (기존 후보 저장 로직), PERIOD_PLAN → 기존 기간 계획 OFFER(버튼)로 넘기고 PROMOTED만 기록.
 * 성공한 draft → PROMOTED + promoted_suggestion_ids. 호출부(AiTurnLifecycleService.completeTurnSuccess)
 * 트랜잭션에 참여한다(REQUIRED) — 대화 잠금 소유권 재확인(PROCESSING 선점)이 이미 끝난 뒤에만 불린다.
 *
 * <p>여기 이외의 어떤 경로도 draft를 쓰지 않는다(대화 ARCHIVED 시 일괄 CANCELLED 제외).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DraftPromotionService {

    private final AiConversationDraftMapper draftMapper;
    private final ScheduleSuggestionService scheduleSuggestionService;
    private final ObjectMapper objectMapper;

    /** 이 턴에서 새로 만들어진 일정 후보. PERIOD_PLAN draft는 후보가 아니라 OFFER라 여기 없다. */
    public record PromotionResult(List<ScheduleSuggestionResponse> scheduleSuggestions) {
        public static final PromotionResult EMPTY = new PromotionResult(List.of());
    }

    @Transactional
    public PromotionResult applyTurn(Long userId, Long conversationId, Long assistantMessageId,
                                     DraftTurnResolver.Outcome outcome, DraftFacts facts) {
        if (outcome == null || !outcome.touchesDrafts()) {
            return PromotionResult.EMPTY;
        }
        DraftCodec codec = new DraftCodec(objectMapper);

        // 1. 새 draft INSERT (그룹 id 해석은 참조 순서대로)
        Map<String, Long> groupByRef = new HashMap<>();
        for (DraftState draft : outcome.drafts()) {
            if (!draft.isNew()) {
                groupByRef.put(draft.refId(), draft.getDraftGroupId() != null ? draft.getDraftGroupId() : draft.getDraftId());
            }
        }
        for (DraftState draft : outcome.drafts()) {
            if (!draft.isNew()) {
                continue;
            }
            String tempId = draft.getTempId();
            Long groupId = draft.getSameGroupAs() != null ? groupByRef.get(draft.getSameGroupAs().strip()) : null;
            draft.setDraftGroupId(groupId);
            AiConversationDraft entity = codec.toEntity(draft);
            draftMapper.insert(entity);
            draft.setDraftId(entity.getDraftId());
            if (groupId == null) {
                draft.setDraftGroupId(entity.getDraftId());
                draftMapper.updateGroupId(entity.getDraftId(), userId, entity.getDraftId());
            }
            groupByRef.put(tempId, draft.getDraftGroupId());
        }

        // 2. 승격
        List<ScheduleSuggestionResponse> created = new ArrayList<>();
        for (DraftState draft : outcome.proposeDrafts()) {
            if (draft.getType() == DraftType.CREATE_PERIOD_PLAN) {
                draft.setStatus(DraftStatus.PROMOTED);
                continue;
            }
            List<ScheduleSuggestion> suggestions = DraftProposalBuilder.build(draft, facts, objectMapper);
            List<ScheduleSuggestionResponse> responses = scheduleSuggestionService.createFromDraft(
                    userId, conversationId, assistantMessageId, suggestions);
            List<Long> ids = responses.stream().map(ScheduleSuggestionResponse::getSuggestionId).toList();
            draft.setPromotedSuggestionIds(ids);
            draft.setStatus(DraftStatus.PROMOTED);
            created.addAll(responses);
            log.info("draft 승격: draftId={}, type={}, suggestions={}", draft.getDraftId(), draft.getType(), ids.size());
        }

        // 3. 기존·새 draft 상태 저장(새 것도 승격/필드 갱신이 INSERT 뒤에 있었으므로 한 번 더 쓴다)
        for (DraftState draft : outcome.drafts()) {
            draftMapper.update(codec.toEntity(draft));
        }
        return new PromotionResult(created);
    }
}
