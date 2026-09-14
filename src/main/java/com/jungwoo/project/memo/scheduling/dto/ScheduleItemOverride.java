package com.jungwoo.project.memo.scheduling.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 재배치 계산 전에 사용자가 고친 제안 항목 하나의 현재 편집값. null인 필드는 원본(또는 직전
 * 편집) 값을 그대로 쓴다. 이 편집은 AIProposalItem을 즉시 바꾸지 않는다 — 최종 승인(apply)
 * 시점에만 반영된다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScheduleItemOverride {
    private Long proposalItemId;
    private String title;
    private String description;
    private Integer expectedMinutes;
    private String priority;
    private LocalDate earliestStartDate;
    private LocalDate deadlineDate;
    /** 값을 주면 이 후보를 그 시각에 고정한다(Timefold가 움직이지 않는다). */
    private LocalDateTime fixedStartAt;
    private LocalDateTime fixedEndAt;
}
