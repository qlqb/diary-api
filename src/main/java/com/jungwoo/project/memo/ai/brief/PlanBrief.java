package com.jungwoo.project.memo.ai.brief;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 대화 하나의 계획 합의(ai_plan_briefs). 대화당 한 행이고 {@code version}이 바뀔 때마다 오른다.
 *
 * <p>최근 대화 창(6/8개) 밖으로 밀려도 합의가 사라지지 않게 서버가 따로 들고 있는 것이다. 원문 조회 경로는
 * 각 항목의 sourceMessageId다.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanBrief {

    private Long briefId;
    private Long userId;
    private Long conversationId;
    private int version;
    /** OPEN / CLOSED */
    private String status;
    /** PlanBriefItem JSON 배열 */
    private String items;
    /** 이 합의로 가장 최근에 만든 초안(ai_proposals). 없으면 null */
    private Long lastProposalId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
