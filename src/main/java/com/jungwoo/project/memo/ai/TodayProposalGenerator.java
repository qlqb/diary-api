package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.dto.TodayProposal;

import java.time.LocalDate;

/**
 * 오늘 실행 제안 생성 목적의 작은 인터페이스.
 *
 * 테스트에서 목(mock) 처리하기 위해 존재한다. 멀티 제공자 구조는 만들지 않는다 —
 * 구현체는 OpenAiTodayProposalGenerator 하나뿐이다.
 */
public interface TodayProposalGenerator {

    /** AI가 설정되어 있는지 (AI_PROVIDER != none, API 키 존재). */
    boolean isConfigured();

    /**
     * 자연어 상담 원문으로 오늘 실행 후보를 만든다.
     * DB 트랜잭션 밖에서 호출해야 한다 — LLM 호출 자체는 트랜잭션을 걸지 않는다.
     */
    TodayProposal generate(String sourceText, LocalDate targetDate);
}
