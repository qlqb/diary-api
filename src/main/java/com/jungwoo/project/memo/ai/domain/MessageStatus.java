package com.jungwoo.project.memo.ai.domain;

/**
 * ai_messages.status 체크 제약과 1:1 대응한다.
 *
 * USER 메시지만 세 상태를 모두 거친다: PROCESSING(저장 직후, AI 호출 진행 중)
 * -> COMPLETED(정상 종료) 또는 FAILED(오류·타임아웃·연결 종료). ASSISTANT 메시지는
 * 스트림이 끝까지 성공했을 때만 만들어지므로 항상 COMPLETED로 저장된다.
 */
public enum MessageStatus {
    PROCESSING,
    COMPLETED,
    FAILED
}
