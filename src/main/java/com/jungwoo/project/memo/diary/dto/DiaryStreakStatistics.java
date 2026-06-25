package com.jungwoo.project.memo.diary.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 연속 작성 통계 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiaryStreakStatistics {

    /** 현재 연속 작성 일수 */
    private int currentStreak;

    /** 최장 연속 작성 일수 */
    private int longestStreak;

    public static DiaryStreakStatistics of(int currentStreak, int longestStreak) {
        return DiaryStreakStatistics.builder()
                .currentStreak(currentStreak)
                .longestStreak(longestStreak)
                .build();
    }
}