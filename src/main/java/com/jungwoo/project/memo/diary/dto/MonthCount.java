package com.jungwoo.project.memo.diary.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 월별 개수
 * MyBatis 결과 매핑용 DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MonthCount {

    private int month;
    private int count;
}