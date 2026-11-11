package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.dto.ScheduleSuggestionApplyBatchRequest;
import com.jungwoo.project.memo.ai.dto.ScheduleSuggestionApplyRequest;
import com.jungwoo.project.memo.ai.dto.ScheduleSuggestionResponse;
import com.jungwoo.project.memo.common.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 일정 후보(약속·반복 일정)의 적용·거절.
 *
 * <pre>
 * POST /api/ai/schedule-suggestions/{id}/apply     승인 -> 실제 원본 생성
 * POST /api/ai/schedule-suggestions/{id}/dismiss   적용하지 않음 (도메인 행 없음)
 * POST /api/ai/schedule-suggestions/apply-batch    여러 건을 한 번에 (전부 되거나 전부 안 됨)
 * </pre>
 *
 * <p>조회는 대화 단위라 AiConversationController에 있다(/conversations/{id}/schedule-suggestions).
 * 후보는 대화의 산물이고, 적용·거절은 후보 한 건의 상태 전이라 자리가 다르다 —
 * context-suggestions와 같은 구성이다.
 */
@Slf4j
@RestController
@RequestMapping("/api/ai/schedule-suggestions")
@RequiredArgsConstructor
public class AiScheduleSuggestionController {

    private final ScheduleSuggestionService scheduleSuggestionService;

    /**
     * body는 없어도 된다. editedPayload가 없으면 저장된 원본 후보를 그대로 쓴다 —
     * 사용자가 카드를 고치지 않고 그냥 [적용]을 누른 경우다.
     */
    @PostMapping("/{suggestionId}/apply")
    public ResponseEntity<ScheduleSuggestionResponse> apply(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long suggestionId,
            @RequestBody(required = false) ScheduleSuggestionApplyRequest request
    ) {
        log.info("POST /api/ai/schedule-suggestions/{}/apply - userId={}", suggestionId, principal.getUserId());
        return ResponseEntity.ok(scheduleSuggestionService.apply(
                suggestionId, principal.getUserId(),
                request != null ? request.getEditedPayload() : null));
    }

    /**
     * 여러 후보를 한 번에 적용한다. 근무표 한 장이 7개의 후보를 만들고, 사용자는 그것을
     * 하나씩 누르지 않는다.
     *
     * <p>중간에 실패하면 전부 롤백된다 — 3개만 들어간 상태로 끝나면 사용자는 카드만 보고
     * 어느 것이 들어갔는지 알 수 없다.
     */
    @PostMapping("/apply-batch")
    public ResponseEntity<List<ScheduleSuggestionResponse>> applyBatch(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody ScheduleSuggestionApplyBatchRequest request
    ) {
        log.info("POST /api/ai/schedule-suggestions/apply-batch - userId={}, count={}",
                principal.getUserId(), request.getSuggestionIds().size());
        return ResponseEntity.ok(
                scheduleSuggestionService.applyBatch(principal.getUserId(), request.getSuggestionIds()));
    }

    @PostMapping("/{suggestionId}/dismiss")
    public ResponseEntity<ScheduleSuggestionResponse> dismiss(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long suggestionId
    ) {
        return ResponseEntity.ok(scheduleSuggestionService.dismiss(suggestionId, principal.getUserId()));
    }
}
