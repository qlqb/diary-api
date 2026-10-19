package com.jungwoo.project.memo.ai;

import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

/**
 * 상담 한 턴을 스트리밍으로 처리하는 목적의 작은 인터페이스.
 *
 * 테스트에서 목(mock) 처리하기 위해 존재한다. 멀티 제공자 구조는 만들지 않는다 —
 * 구현체는 OpenAiConsultationClient 하나뿐이다.
 */
public interface AiConsultationClient {

    /** AI가 설정되어 있는지 (AI_PROVIDER != none, API 키 존재). */
    boolean isConfigured();

    /**
     * 시스템 프롬프트와 사용자 프롬프트로 모델을 한 번 호출해 응답 스트림을 받는다.
     * ChatResponse를 그대로 돌려주는 이유는 텍스트 델타뿐 아니라 사용량(Usage) 메타데이터도
     * 마지막 청크에서 함께 와야 ai_usage_logs에 남길 수 있기 때문이다.
     * 응답 텍스트는 "자연어 reply" + 구분자(AiStreamParser.DELIMITER) + "구조화 JSON" 형식이다.
     * DB 트랜잭션 밖에서 구독해야 한다.
     *
     * 전역 기본(spring.ai.openai.chat.max-completion-tokens)을 그대로 쓴다. Today 상담
     * 흐름(AiConversationService)은 이 오버로드만 쓴다 — 기존 동작을 그대로 유지한다.
     */
    default Flux<ChatResponse> streamTurn(String systemPrompt, String userPrompt) {
        return streamTurn(systemPrompt, userPrompt, null);
    }

    /**
     * Material/Learning/Planning Agent처럼 호출별로 다른 출력 토큰 예산이 필요한 경우용
     * 오버로드. maxCompletionTokens가 null이면 전역 기본값을 쓴다(위 오버로드와 동일 동작).
     */
    default Flux<ChatResponse> streamTurn(String systemPrompt, String userPrompt, Integer maxCompletionTokens) {
        return streamTurn(systemPrompt, userPrompt, maxCompletionTokens, null);
    }

    /**
     * 호출별로 모델까지 고르는 오버로드. model이 null이면 전역 기본 모델
     * (spring.ai.openai.chat.model)을 그대로 쓴다 — 위 두 오버로드와 동일 동작.
     *
     * 상담 경로(AiConversationService)만 이것을 쓴다(ai.consultation.model). 어떤 모델을 실제로
     * 불렀는지는 호출부가 ai_usage_logs에 기록해야 하므로, 모델 문자열은 클라이언트가 숨기지
     * 않고 호출부가 들고 온다.
     */
    Flux<ChatResponse> streamTurn(String systemPrompt, String userPrompt, Integer maxCompletionTokens, String model);
}
