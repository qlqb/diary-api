package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.dto.AiProposalResponse;
import com.jungwoo.project.memo.ai.dto.AiTurnCompletedPayload;
import com.jungwoo.project.memo.ai.dto.ContextSuggestionResponse;
import com.jungwoo.project.memo.ai.dto.OfferAction;
import com.jungwoo.project.memo.ai.dto.ScheduleSuggestionResponse;
import com.jungwoo.project.memo.common.exception.ErrorCode;

import java.util.List;

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

    /**
     * 장기 컨텍스트 변경 후보가 있으면 responseType(CHAT/OFFER/PROPOSAL)과 무관하게 함께
     * 알린다 — 이 후보는 sidecar이며 아직 user_contexts에는 반영되지 않은 PROPOSED 상태다.
     */
    void onContextSuggestionsReady(List<ContextSuggestionResponse> suggestions);

    /**
     * 약속·반복 일정 후보. proposal.ready와 섞지 않는다 — 저쪽은 수행할 항목의 배치안이고
     * 이쪽은 "그 시간은 못 쓴다"는 사실의 후보라, 화면이 그리는 카드도 누르는 버튼도 다르다.
     * 아직 원본에는 아무것도 저장되지 않은 PROPOSED 상태다.
     */
    void onScheduleSuggestionsReady(List<ScheduleSuggestionResponse> suggestions);

    void onCompleted(AiTurnCompletedPayload payload);

    void onError(ErrorCode errorCode);
}
