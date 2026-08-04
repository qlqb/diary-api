package com.jungwoo.project.memo.ai.dto;

import com.jungwoo.project.memo.ai.domain.AiProposalTargetScope;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * requestedAction=AUTO(기본)이면 message가 이번 사용자 턴의 원문이다.
 * requestedAction=CREATE_PROPOSAL이면 OFFER 카드의 버튼 클릭이므로 message는 비워도 되고,
 * 대신 sourceMessageId(그 OFFER를 만든 자신의 이전 USER 메시지)를 반드시 넣는다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiMessageRequest {

    private String message;

    private AiProposalTargetScope scope;

    private Long focusId;

    @Builder.Default
    private RequestedAction requestedAction = RequestedAction.AUTO;

    private Long sourceMessageId;

    @NotBlank(message = "idempotencyKey는 필수입니다")
    private String idempotencyKey;
}
