package com.jungwoo.project.memo.ai;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.Disposables;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AiConversationController.sendMessage()가 구독 Disposable을 subscriptionRef에 등록하는
 * 순서와 SseAiTurnEventSink.isTerminated()를 조합해, "연결 종료가 subscriptionRef.set()보다
 * 먼저/나중에 일어나는" 두 타이밍 모두에서 업스트림 OpenAI 스트림 구독이 반드시 dispose되는지
 * 검증한다.
 *
 * 이 프로젝트에는 AiConversationController에 대한 기존 MockMvc 테스트 선례가 없어 새 HTTP
 * 테스트 하네스를 새로 만들지 않는다 — 대신 컨트롤러가 실제로 쓰는 것과 동일한 조합
 * (AtomicReference&lt;Disposable&gt; + sink.markTerminatedOnce()/isTerminated())을 그대로
 * 재현해 그 순서 보장 자체를 검증한다.
 */
class SseAiTurnEventSinkTest {

    @Test
    void isTerminated_reflectsMarkTerminatedOnce() {
        SseAiTurnEventSink sink = new SseAiTurnEventSink(new SseEmitter());

        assertThat(sink.isTerminated()).isFalse();

        boolean firstCall = sink.markTerminatedOnce();

        assertThat(firstCall).isTrue();
        assertThat(sink.isTerminated()).isTrue();
    }

    @Test
    void markTerminatedOnce_onlyFirstCallerWins() {
        SseAiTurnEventSink sink = new SseAiTurnEventSink(new SseEmitter());

        assertThat(sink.markTerminatedOnce()).isTrue();
        assertThat(sink.markTerminatedOnce()).isFalse();
    }

    /**
     * 연결 종료가 subscriptionRef.set()보다 먼저 발생한 상황(컨트롤러 코드 그대로 재현):
     * onDisconnect가 sink를 terminated로 만들지만 그 시점엔 subscriptionRef가 비어 있어
     * dispose하지 못한다. 나중에 subscriptionRef.set() 직후의 isTerminated() 확인 코드가
     * 뒤늦게라도 dispose해야 한다.
     */
    @Test
    void disconnectBeforeSubscriptionRegistered_stillDisposesOnceRegistered() {
        SseAiTurnEventSink sink = new SseAiTurnEventSink(new SseEmitter());
        AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();

        // 연결 종료 콜백이 구독 등록보다 먼저 실행됨 — 컨트롤러의 onDisconnect와 동일한 로직.
        if (sink.markTerminatedOnce()) {
            Disposable early = subscriptionRef.get();
            if (early != null) {
                early.dispose();
            }
        }

        // 그 뒤에야 실제 구독이 등록된다(컨트롤러의 streamAndComplete 반환 이후) — 컨트롤러의
        // subscriptionRef.set(subscription) + isTerminated() 확인과 동일한 로직.
        Disposable subscription = Disposables.single();
        subscriptionRef.set(subscription);
        if (sink.isTerminated()) {
            subscription.dispose();
        }

        assertThat(subscription.isDisposed()).isTrue();
    }

    /**
     * 연결 종료가 subscriptionRef.set() 이후에 발생하는 일반적인 경우: 기존 onDisconnect
     * 콜백이 이미 채워진 ref를 보고 그대로 dispose한다.
     */
    @Test
    void disconnectAfterSubscriptionRegistered_disposesViaExistingCallback() {
        SseAiTurnEventSink sink = new SseAiTurnEventSink(new SseEmitter());
        AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();

        Disposable subscription = Disposables.single();
        subscriptionRef.set(subscription);
        if (sink.isTerminated()) {
            subscription.dispose();
        }
        assertThat(subscription.isDisposed()).isFalse(); // 아직 연결은 살아있다 — 오탐 dispose 없음.

        // 이후 연결 종료 콜백이 실행됨.
        if (sink.markTerminatedOnce()) {
            Disposable current = subscriptionRef.get();
            if (current != null) {
                current.dispose();
            }
        }

        assertThat(subscription.isDisposed()).isTrue();
    }
}
