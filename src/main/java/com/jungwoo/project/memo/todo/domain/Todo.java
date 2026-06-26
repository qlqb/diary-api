package com.jungwoo.project.memo.todo.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Todo 엔티티
 *
 * source_type 값:
 *   - MANUAL       : 사용자가 직접 생성 (현재 단계에서 유일하게 사용)
 *   - AI_SUGGESTED : ai_suggestions 테이블에서 승인되어 생성 (추후 구현)
 *   - ROUTINE      : routines 테이블에서 자동 생성 (추후 구현)
 *
 * routine_id 는 nullable. Routine 기능 구현 이후 FK 제약 추가 예정.
 *
 * created_at / updated_at 은 DB DEFAULT 를 사용하지 않고
 * Service 에서 LocalDateTime.now() 로 직접 주입 (Diary 도메인과 동일 방식).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Todo {

    /** Todo 고유 ID (Primary Key) */
    private Long todoId;

    /** 작성자 사용자 ID (Foreign Key -> users.user_id) */
    private Long userId;

    /** Todo 날짜 (이 날 할 일) */
    private LocalDate todoDate;

    /** Todo 제목 (필수) */
    private String title;

    /** Todo 메모 (선택) */
    private String content;

    /**
     * 완료 상태
     * TODO : 미완료 (기본값)
     * DONE : 완료
     */
    private String status;

    /**
     * 우선순위
     * HIGH / MEDIUM / LOW
     * 기본값: MEDIUM
     */
    private String priority;

    /**
     * 생성 출처
     * MANUAL       : 사용자가 직접 생성
     * AI_SUGGESTED : AI 추천 승인 (추후 구현)
     * ROUTINE      : 루틴 자동 생성 (추후 구현)
     */
    private String sourceType;

    /**
     * 루틴 ID (nullable)
     * Routine 기능 구현 이후 routines.routine_id 를 참조하는 FK 추가 예정
     */
    private Long routineId;

    /** 완료 처리 시각 (status 가 DONE 으로 바뀔 때 기록) */
    private LocalDateTime completedAt;

    /** 소프트 삭제 여부 */
    private Boolean isDeleted;

    /** 생성 시각 (Service 에서 LocalDateTime.now() 로 주입) */
    private LocalDateTime createdAt;

    /** 마지막 수정 시각 (Service 에서 LocalDateTime.now() 로 주입) */
    private LocalDateTime updatedAt;
}