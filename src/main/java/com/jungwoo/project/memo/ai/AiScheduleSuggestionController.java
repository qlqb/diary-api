package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.dto.ScheduleSuggestionApplyRequest;
import com.jungwoo.project.memo.ai.dto.ScheduleSuggestionResponse;
import com.jungwoo.project.memo.common.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    @PostMapping("/{suggestionId}/dismiss")
    public ResponseEntity<ScheduleSuggestionResponse> dismiss(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long suggestionId
    ) {
        return ResponseEntity.ok(scheduleSuggestionService.dismiss(suggestionId, principal.getUserId()));
    }
}
