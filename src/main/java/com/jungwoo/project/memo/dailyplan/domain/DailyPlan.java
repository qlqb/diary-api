package com.jungwoo.project.memo.dailyplan.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 날짜별 하루 운영 단위.
 *
 * 1차-A에서는 화면/API 없이 내부용으로만 쓰인다.
 * (move 액션의 get-or-create 대상. DailyPlan v2 화면과 conditionTags는 1차-C)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DailyPlan {

    private Long dailyPlanId;
    private Long userId;
    private LocalDate planDate;
    private DailyPlanViewMode viewMode;
    private ViewModeSource viewModeSource;
    private DailyPlanIntensity intensity;
    private String conditionNote;
    private String mainGoal;
    private String memo;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
