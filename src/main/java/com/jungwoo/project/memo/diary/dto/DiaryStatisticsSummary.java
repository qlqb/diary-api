package com.jungwoo.project.memo.diary.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 일기 통계 요약 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiaryStatisticsSummary {

    /** 전체 일기 수 */
    private int totalCount;

    /** 이번 달 작성 수 */
    private int thisMonthCount;

    /** 즐겨찾기 일기 수 */
    private int favoriteCount;

    public static DiaryStatisticsSummary of(int totalCount, int thisMonthCount, int favoriteCount) {
        return DiaryStatisticsSummary.builder()
                .totalCount(totalCount)
                .thisMonthCount(thisMonthCount)
                .favoriteCount(favoriteCount)
                .build();
    }
}