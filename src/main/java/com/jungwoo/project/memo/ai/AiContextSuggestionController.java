package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.dto.ContextSuggestionResponse;
import com.jungwoo.project.memo.common.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 장기 컨텍스트 변경 후보(AiContextChangeSuggestion) 승인/거절 컨트롤러.
 *
 * 생성은 여기 없다 — 후보는 AiConversationController의 대화 흐름에서 AI가 만들고
 * ai_context_change_suggestions에만 저장된다. apply를 눌러야만 실제 user_contexts가 바뀐다.
 *
 * /api/** 는 SecurityConfig에서 기본적으로 인증이 필요하다. apply/dismiss 둘 다 로그인
 * userId 소유 후보만 허용한다(ContextChangeSuggestionService가 조회 시점에 강제).
 */
@Slf4j
@RestController
@RequestMapping("/api/ai/context-suggestions")
@RequiredArgsConstructor
public class AiContextSuggestionController {

    private final ContextChangeSuggestionService contextChangeSuggestionService;

    @PostMapping("/{suggestionId}/apply")
    public ResponseEntity<ContextSuggestionResponse> apply(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long suggestionId
    ) {
        log.info("POST /api/ai/context-suggestions/{}/apply - userId={}", suggestionId, principal.getUserId());
        return ResponseEntity.ok(contextChangeSuggestionService.apply(suggestionId, principal.getUserId()));
    }

    @PostMapping("/{suggestionId}/dismiss")
    public ResponseEntity<ContextSuggestionResponse> dismiss(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long suggestionId
    ) {
        log.info("POST /api/ai/context-suggestions/{}/dismiss - userId={}", suggestionId, principal.getUserId());
        return ResponseEntity.ok(contextChangeSuggestionService.dismiss(suggestionId, principal.getUserId()));
    }
}
