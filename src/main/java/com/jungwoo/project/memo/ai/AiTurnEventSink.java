package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.dto.AiProposalResponse;
import com.jungwoo.project.memo.ai.dto.AiTurnCompletedPayload;
import com.jungwoo.project.memo.ai.dto.OfferAction;
import com.jungwoo.project.memo.common.exception.ErrorCode;

/**
 * AiConversationService가 SSE 이벤트를 내보낼 때 쓰는 콜백. 컨트롤러가 SseEmitter에
 * 연결한다 — 서비스 계층은 서블릿/SSE API를 몰라도 된다.
 */
public interface AiTurnEventSink {

    /** requestMessageId는 이번 턴을 대표하는 USER 메시지 id — 연결 종료 시 잠금 해제에 쓴다. */
    void onStarted(Long requestMessageId);

    /** reply 텍스트 조각. 구분자 뒤 구조화 JSON 구간은 절대 여기로 오지 않는다. */
    void onDelta(String text);

    void onOfferReady(OfferAction offerAction);

    void onProposalReady(AiProposalResponse proposal);

    void onCompleted(AiTurnCompletedPayload payload);

    void onError(ErrorCode errorCode);
}
