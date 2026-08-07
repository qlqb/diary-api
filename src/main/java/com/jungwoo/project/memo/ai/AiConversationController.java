package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.dto.AiConversationCreateRequest;
import com.jungwoo.project.memo.ai.dto.AiConversationResponse;
import com.jungwoo.project.memo.ai.dto.AiMessageRequest;
import com.jungwoo.project.memo.ai.dto.AiMessageResponse;
import com.jungwoo.project.memo.ai.dto.ContextSuggestionResponse;
import com.jungwoo.project.memo.common.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

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

    @Value("${ai.request.timeout-seconds:90}")
    private int requestTimeoutSeconds;

    /** Flux 타임아웃이 항상 먼저 걸리도록 SseEmitter 자체 타임아웃(백스톱)에 여유를 더 둔다. */
    @Value("${ai.sse.timeout-buffer-seconds:30}")
    private int sseTimeoutBufferSeconds;

    @PostMapping
    public ResponseEntity<AiConversationResponse> create(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody(required = false) AiConversationCreateRequest request
    ) {
        log.info("POST /api/ai/conversations - userId={}", principal.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(aiConversationService.createConversation(principal.getUserId(), request));
    }

    /** 로그인한 사용자의 대화 목록. 마지막 메시지 시각 내림차순, 본인 것만. */
    @GetMapping
    public ResponseEntity<List<AiConversationResponse>> list(
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(aiConversationService.listConversations(principal.getUserId()));
    }

    @GetMapping("/{conversationId}/messages")
    public ResponseEntity<List<AiMessageResponse>> getMessages(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long conversationId
    ) {
        return ResponseEntity.ok(aiConversationService.getMessages(conversationId, principal.getUserId()));
    }

    /** 아직 승인/거절하지 않은 Context 변경 후보. 새로고침 후 카드 복원용. */
    @GetMapping("/{conversationId}/context-suggestions")
    public ResponseEntity<List<ContextSuggestionResponse>> getContextSuggestions(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long conversationId
    ) {
        return ResponseEntity.ok(aiConversationService.getPendingContextSuggestions(conversationId, principal.getUserId()));
    }

    @PostMapping(value = "/{conversationId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessage(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long conversationId,
            @Valid @RequestBody AiMessageRequest request
    ) {
        Long userId = principal.getUserId();
        log.info("POST /api/ai/conversations/{}/messages - userId={}, requestedAction={}",
                conversationId, userId, request.getRequestedAction());

        // 소유권/idempotency/대화방 잠금/AI 설정/사용량 한도 확인. 여기서 예외가 나면 아직 SSE를
        // 시작하지 않았으므로(emitter를 만들기 전) GlobalExceptionHandler가 실제 HTTP 409/
        // 503/429로 응답한다 — OpenAI는 이 시점까지 한 번도 호출되지 않는다.
        AiTurnLifecycleService.PreparedTurn prepared = aiConversationService.prepareTurn(conversationId, userId, request);

        long sseTimeoutMillis = (requestTimeoutSeconds + sseTimeoutBufferSeconds) * 1000L;
        SseEmitter emitter = new SseEmitter(sseTimeoutMillis);
        SseAiTurnEventSink sink = new SseAiTurnEventSink(emitter);

        if (prepared.replay()) {
            aiConversationService.replayStoredTurn(prepared, sink);
            return emitter;
        }

        AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();
        Runnable onDisconnect = () -> {
            if (sink.markTerminatedOnce()) {
                Disposable subscription = subscriptionRef.get();
                if (subscription != null) {
                    subscription.dispose();
                }
                aiConversationService.abortTurn(conversationId, userId, sink.getRequestMessageId());
            }
        };
        emitter.onTimeout(onDisconnect::run);
        emitter.onError(ex -> onDisconnect.run());
        emitter.onCompletion(onDisconnect::run);

        Disposable subscription = aiConversationService.streamAndComplete(prepared, request, sink);
        subscriptionRef.set(subscription);

        return emitter;
    }
}
