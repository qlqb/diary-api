package com.jungwoo.project.memo.todo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 특정 날짜의 Todo 달성률 응답 DTO
 *
 * 응답 예시:
 * {
 *   "date": "2026-06-25",
 *   "totalCount": 5,
 *   "doneCount": 3,
 *   "achievementRate": 60.0
 * }
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TodoDailyStatisticsResponse {

    /** 조회 날짜 */
    private LocalDate date;

    /** 해당 날짜의 전체 Todo 수 (is_deleted = false) */
    private int totalCount;

    /** 해당 날짜의 완료(DONE) Todo 수 */
    private int doneCount;

    /**
     * 달성률 (%)
     * totalCount 가 0 이면 0.0 반환
     */
    private double achievementRate;

    /**
     * 달성률 계산 포함 정적 팩토리 메서드
     *
     * @param date       조회 날짜
     * @param totalCount 전체 Todo 수
     * @param doneCount  완료 Todo 수
     * @return TodoDailyStatisticsResponse
     */
    public static TodoDailyStatisticsResponse of(LocalDate date, int totalCount, int doneCount) {
        double rate = (totalCount == 0) ? 0.0
                : Math.round((doneCount * 100.0 / totalCount) * 10) / 10.0;

        return TodoDailyStatisticsResponse.builder()
                .date(date)
                .totalCount(totalCount)
                .doneCount(doneCount)
                .achievementRate(rate)
                .build();
    }
}