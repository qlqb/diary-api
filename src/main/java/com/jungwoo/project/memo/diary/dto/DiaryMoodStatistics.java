package com.jungwoo.project.memo.diary.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 기분별 통계 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiaryMoodStatistics {

    /** 기분별 개수 맵 */
    private Map<String, Integer> moodCounts;

    /** 전체 개수 */
    private int totalCount;

    public static DiaryMoodStatistics of(List<MoodCount> moodCounts) {
        Map<String, Integer> countsMap = moodCounts.stream()
                .collect(Collectors.toMap(
                        MoodCount::getMood,
                        MoodCount::getCount
                ));

        int total = moodCounts.stream()
                .mapToInt(MoodCount::getCount)
                .sum();

        return DiaryMoodStatistics.builder()
                .moodCounts(countsMap)
                .totalCount(total)
                .build();
    }
}