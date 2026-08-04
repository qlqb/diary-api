package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.dto.AiProposalResponse;
import com.jungwoo.project.memo.ai.dto.AiTurnCompletedPayload;
import com.jungwoo.project.memo.ai.dto.OfferAction;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

/**
 * AiTurnEventSink를 실제 SseEmitter 전송으로 옮긴다. 구조화 JSON 원문은 절대 그대로
 * 내보내지 않는다 — 여기서 만드는 이벤트 payload는 전부 검증·파싱이 끝난 값이다.
 */
@Slf4j
class SseAiTurnEventSink implements AiTurnEventSink {

    private final SseEmitter emitter;

    SseAiTurnEventSink(SseEmitter emitter) {
        this.emitter = emitter;
    }

    @Override
    public void onStarted() {
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
    public void onCompleted(AiTurnCompletedPayload payload) {
        send("message.completed", payload);
        emitter.complete();
    }

    @Override
    public void onError(ErrorCode errorCode) {
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
