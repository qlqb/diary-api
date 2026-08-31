package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.dto.AiProposalResponse;
import com.jungwoo.project.memo.ai.dto.AiTurnCompletedPayload;
import com.jungwoo.project.memo.ai.dto.ContextSuggestionResponse;
import com.jungwoo.project.memo.ai.dto.OfferAction;
import com.jungwoo.project.memo.ai.dto.ScheduleSuggestionResponse;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AiTurnEventSink를 실제 SseEmitter 전송으로 옮긴다. 구조화 JSON 원문은 절대 그대로
 * 내보내지 않는다 — 여기서 만드는 이벤트 payload는 전부 검증·파싱이 끝난 값이다.
 *
 * terminated 플래그는 "정상 완료/오류"와 "브라우저 연결 종료"가 경합해도 정리 로직이 한
 * 번만 실행되게 막는다 — 컨트롤러의 연결 종료 콜백도 같은 markTerminatedOnce()를 공유한다.
 */
@Slf4j
class SseAiTurnEventSink implements AiTurnEventSink {

    private final SseEmitter emitter;
    private final AtomicBoolean terminated = new AtomicBoolean(false);
    private volatile Long requestMessageId;

    SseAiTurnEventSink(SseEmitter emitter) {
        this.emitter = emitter;
    }

    /** 먼저 도착한 쪽만 true를 받는다(정상 완료 vs 연결 종료 경합 방지). */
    boolean markTerminatedOnce() {
        return terminated.compareAndSet(false, true);
    }

    /**
     * 컨트롤러가 streamAndComplete() 구독 Disposable을 subscriptionRef에 등록한 직후 이 값을
     * 확인한다 — 등록 사이의 아주 짧은 순간에 연결 종료 콜백이 먼저 실행돼 markTerminatedOnce()
     * 가 이미 true가 됐지만 그때는 subscriptionRef가 비어 있어 dispose()하지 못했을 수 있다.
     * 그 경우 컨트롤러가 이 메서드로 뒤늦게라도 dispose해야 업스트림 OpenAI 스트림이 계속
     * 살아 있다가 이미 종료 처리된 요청에 뒤늦은 성공 결과를 만드는 것을 막는다.
     */
    boolean isTerminated() {
        return terminated.get();
    }

    Long getRequestMessageId() {
        return requestMessageId;
    }

    @Override
    public void onStarted(Long requestMessageId) {
        this.requestMessageId = requestMessageId;
        send("message.started", Map.of());
    }

    @Override
    public void onDelta(String text) {
        send("message.delta", Map.of("text", text));
    }

    @Override
    public void onOfferReady(OfferAction offerAction) {
        send("offer.ready", Map.of("offerAction", offerAction));
    }

    @Override
    public void onProposalReady(AiProposalResponse proposal) {
        send("proposal.ready", Map.of("proposalId", proposal.getProposalId(), "items", proposal.getItems()));
    }

    @Override
    public void onContextSuggestionsReady(List<ContextSuggestionResponse> suggestions) {
        send("context.suggestions.ready", Map.of("suggestions", suggestions));
    }

    @Override
    public void onScheduleSuggestionsReady(List<ScheduleSuggestionResponse> suggestions) {
        send("schedule.suggestions.ready", Map.of("suggestions", suggestions));
    }

    @Override
    public void onCompleted(AiTurnCompletedPayload payload) {
        if (!markTerminatedOnce()) {
            return;
        }
        send("message.completed", payload);
        emitter.complete();
    }

    @Override
    public void onError(ErrorCode errorCode) {
        if (!markTerminatedOnce()) {
            return;
        }
        send("message.error", Map.of("code", errorCode.getCode(), "message", errorCode.getMessage()));
        emitter.complete();
    }

    private void send(String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE 전송 실패(클라이언트 연결 종료 가능): event={}", eventName);
        }
    }
}
