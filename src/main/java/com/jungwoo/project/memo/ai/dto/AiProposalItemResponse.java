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
    /**
     * 이 항목이 속한 프로젝트. 기간 계획처럼 한 제안에 여러 프로젝트가 섞이는 경로에서
     * 초안 검토 화면이 프로젝트별로 묶는 데 쓴다. 이 값이 없으면 그룹핑이 전부 한 덩어리가
     * 된다. 계획 경로가 아닌 제안은 null이다.
     */
    private Long courseId;
    private LocalDateTime scheduledStartAt;
    private LocalDateTime scheduledEndAt;
    /**
     * 근거 있는 마감 시각. 확정(PlanConfirmService)이 이 값을 execution_items.deadline_at에
     * 옮긴다 — 그래서 응답에 실린다. 초안 화면도 "수업 전"을 이 값으로 표시한다.
     */
    private LocalDateTime deadlineAt;
    /**
     * 날짜 단위 마감 힌트(모델이 낸 값). 확정은 deadlineAt이 없을 때만 이 값을 쓰고,
     * 그때 다음날 00:00으로 바꿔 deadline_at에 넣는다 — 미리보기가 쓰던 변환과 같은
     * 규칙이다("9/9까지"는 9/9 안에 끝내면 된다는 뜻이므로 경계는 9/10 00:00이다).
     */
    private LocalDate deadlineDate;
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
