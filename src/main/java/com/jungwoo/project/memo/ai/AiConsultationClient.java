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
     */
    Flux<ChatResponse> streamTurn(String systemPrompt, String userPrompt);
}
