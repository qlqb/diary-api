package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.dto.AiConversationCreateRequest;
import com.jungwoo.project.memo.ai.dto.AiConversationResponse;
import com.jungwoo.project.memo.ai.dto.AiMessageRequest;
import com.jungwoo.project.memo.ai.dto.AiMessageResponse;
import com.jungwoo.project.memo.common.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * AI 상담 대화 컨트롤러. 강제 생성 API(POST /api/ai/proposals) 대신 이 대화 흐름이
 * 진입점이다 — Proposal은 이 흐름 안에서 사용자가 요청하거나 OFFER에 동의했을 때만 생긴다.
 *
 * 상담 원문, AI 원문 응답, JWT는 로그에 남기지 않는다.
 */
@Slf4j
@RestController
@RequestMapping("/api/ai/conversations")
@RequiredArgsConstructor
public class AiConversationController {

    private final AiConversationService aiConversationService;

    @PostMapping
    public ResponseEntity<AiConversationResponse> create(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody(required = false) AiConversationCreateRequest request
    ) {
        log.info("POST /api/ai/conversations - userId={}", principal.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(aiConversationService.createConversation(principal.getUserId(), request));
    }

    @GetMapping("/{conversationId}/messages")
    public ResponseEntity<List<AiMessageResponse>> getMessages(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long conversationId
    ) {
        return ResponseEntity.ok(aiConversationService.getMessages(conversationId, principal.getUserId()));
    }

    @PostMapping(value = "/{conversationId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessage(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long conversationId,
            @Valid @RequestBody AiMessageRequest request
    ) {
        log.info("POST /api/ai/conversations/{}/messages - userId={}, requestedAction={}",
                conversationId, principal.getUserId(), request.getRequestedAction());

        // AI 스트리밍은 사용자에 따라 오래 걸릴 수 있어 서버가 스스로 완료/에러 처리한다 (타임아웃 없음).
        SseEmitter emitter = new SseEmitter(0L);
        AiTurnEventSink sink = new SseAiTurnEventSink(emitter);

        aiConversationService.handleMessage(conversationId, principal.getUserId(), request, sink);

        return emitter;
    }
}
