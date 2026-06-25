package com.jungwoo.project.memo.diary.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 일기 필터 요청 DTO
 *
 * 사용 예시:
 * GET /api/diaries?mood=HAPPY&favorite=true&keyword=공부
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiaryFilterRequest {

    /** 기분 필터 (HAPPY, SAD, NEUTRAL 등) */
    private String mood;

    /** 즐겨찾기 필터 (true/false) */
    private Boolean favorite;

    /** 검색 키워드 */
    private String keyword;
}