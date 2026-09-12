package com.jungwoo.project.memo.plan.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 계획 항목의 「자세히」 안내. plan_item_details.
 *
 * <p>evidenceVersion은 (항목 제목·설명 + 인용 구간 id·해시)의 해시다. 항목이나 원문이 바뀌면
 * 키가 달라지고, 이전 행은 STALE로 남는다. 사용자가 고친 userText는 지우지 않는다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlanItemDetail {
    private Long detailId;
    private Long userId;
    private Long proposalItemId;
    private String evidenceVersion;
    private String stepsJson;
    private String userText;
    private String status;
    private String model;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
