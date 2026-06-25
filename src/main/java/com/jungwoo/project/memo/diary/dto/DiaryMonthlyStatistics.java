package com.jungwoo.project.memo.diary.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Arrays;
import java.util.List;

/**
 * 월별 통계 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiaryMonthlyStatistics {

    /** 연도 */
    private int year;

    /** 월별 개수 리스트, 1월~12월 */
    private List<Integer> monthlyCounts;

    /** 전체 개수 */
    private int totalCount;

    public static DiaryMonthlyStatistics of(int year, List<MonthCount> monthCounts) {
        Integer[] counts = new Integer[12];
        Arrays.fill(counts, 0);

        for (MonthCount monthCount : monthCounts) {
            int month = monthCount.getMonth();

            if (month >= 1 && month <= 12) {
                counts[month - 1] = monthCount.getCount();
            }
        }

        List<Integer> monthlyList = Arrays.asList(counts);

        int total = monthlyList.stream()
                .mapToInt(Integer::intValue)
                .sum();

        return DiaryMonthlyStatistics.builder()
                .year(year)
                .monthlyCounts(monthlyList)
                .totalCount(total)
                .build();
    }
}