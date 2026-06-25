package com.jungwoo.project.memo.diary.domain;

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 일기 엔티티
 * Diary 도메인의 핵심 엔티티
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Diary {

    /** 일기 고유 ID (Primary Key) */
    private Long diaryId;

    /** 작성자 사용자 ID (Foreign Key -> users.user_id) */
    private Long userId;

    /** 일기 작성 날짜 (실제 일기가 기록하는 날짜) */
    private LocalDate writtenDate;

    /** 일기 제목 */
    private String title;

    /** 일기 내용 */
    private String content;

    /** 기분 상태 (GOOD, BAD, NEUTRAL 등) */
    private String mood;

    /** 공개 범위 (PUBLIC, PRIVATE 등) */
    private String visibility;

    /** 날씨 정보 (맑음, 흐림, 비 등) */
    private String weather;

    /** 즐겨찾기 여부 */
    private Boolean isFavorite;

    /** 삭제 여부 (소프트 삭제용) */
    private Boolean isDeleted;

    /** 일기 최초 생성 시간 */
    private LocalDateTime createdAt;

    /** 일기 마지막 수정 시간 */
    private LocalDateTime updatedAt;
}