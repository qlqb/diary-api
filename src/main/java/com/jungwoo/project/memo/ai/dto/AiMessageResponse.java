package com.jungwoo.project.memo.ai.dto;

import com.jungwoo.project.memo.ai.domain.AiResponseType;
import com.jungwoo.project.memo.ai.domain.MessageRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** GET .../messages 이력 조회, 새로고침 후 대화 복원용. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiMessageResponse {

    private Long messageId;
    private MessageRole role;
    private String content;
    private AiResponseType responseType;
    private Long proposalId;
    private LocalDateTime createdAt;

    /** 이 ASSISTANT 메시지의 질문 카드·이해한 내용·바뀐 방향. 새로고침 뒤 같은 카드를 복구한다. 없으면 null. */
    private com.jungwoo.project.memo.ai.consult.ConsultView consult;

    /** 이 USER 메시지에 붙인 상담 사진(자료 id). 사진 내용은 GET .../photos가 준다. 없으면 빈 목록. */
    @Builder.Default
    private java.util.List<Long> photoIds = java.util.List.of();
}
