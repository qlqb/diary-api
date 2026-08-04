package com.jungwoo.project.memo.ai.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 상담 대화 하나. ai_conversations 테이블과 1:1 대응하는 MyBatis 엔티티.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiConversation {

    private Long conversationId;

    private Long userId;

    private AiProposalTargetScope scope;

    private ConversationStatus status;

    /** 오래된 메시지 요약. 이번 버전은 컬럼만 두고 요약 생성 로직은 만들지 않는다. */
    private String summary;

    /**
     * 대화방 동시 요청 차단용. 진행 중인 요청을 대표하는 ai_messages.message_id(PROCESSING
     * 상태인 USER 행). NULL이면 진행 중인 요청이 없다. 자바 메모리 변수가 아니라 이 컬럼으로
     * 판단해야 여러 서버 인스턴스나 재시작에도 안전하다.
     */
    private Long activeRequestMessageId;

    private LocalDateTime activeRequestStartedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
