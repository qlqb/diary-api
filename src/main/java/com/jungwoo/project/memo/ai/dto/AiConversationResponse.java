package com.jungwoo.project.memo.ai.dto;

import com.jungwoo.project.memo.ai.domain.AiProposalTargetScope;
import com.jungwoo.project.memo.ai.domain.ConversationStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiConversationResponse {

    private Long conversationId;
    private AiProposalTargetScope scope;
    /** 프로젝트 대화면 그 프로젝트. 아니면 null. */
    private Long courseId;
    private ConversationStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * 대화 목록 전용 필드 — POST(생성) 응답에서는 항상 null/0이다.
     * title은 첫 사용자 메시지에서 서버가 계산한다(별도 저장 컬럼 없음, AI 추가 호출 없음).
     */
    private String title;
    private LocalDateTime lastMessageAt;
    private Integer pendingProposalCount;
}
