package com.jungwoo.project.memo.ai.dto;

import com.jungwoo.project.memo.ai.domain.AiProposalItemStatus;
import com.jungwoo.project.memo.ai.domain.ProposalOperation;
import com.jungwoo.project.memo.execution.domain.PlacementType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 제안 카드 하나. edited_payload가 있으면 그 값을, 없으면 original_payload 값을 담는다.
 *
 * operation이 CREATE가 아니면 이 카드는 기존 실행 조각을 바꾸자는 제안이다 —
 * 화면은 targetExecutionItemId가 가리키는 실제 항목 위에 before/after를 겹쳐 보여준다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiProposalItemResponse {

    private Long proposalItemId;
    private AiProposalItemStatus status;
    private String title;
    private String description;
    private Integer expectedMinutes;
    private String priority;
    private LocalDate targetDate;
    private PlacementType placementType;
    private LocalDateTime scheduledStartAt;
    private LocalDateTime scheduledEndAt;
    private Boolean modified;
    private Long createdItemId;

    /** CREATE / REDUCE / MOVE / DROP. */
    private ProposalOperation operation;
    /** 조정 대상 실행 조각. CREATE면 null. */
    private Long targetExecutionItemId;
    private String beforeTitle;
    private Integer beforeExpectedMinutes;
    private LocalDate beforeScheduledDate;
    /** 왜 이렇게 바꾸자는지. 조정 카드에서만 채운다. */
    private String reason;
}
