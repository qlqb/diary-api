package com.jungwoo.project.memo.todo;

import com.jungwoo.project.memo.todo.domain.Todo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * Todo 테이블 MyBatis 매퍼
 *
 * 모든 조회/수정/삭제 SQL 에는 user_id 조건과 is_deleted = false 조건이 포함된다.
 * 다른 사용자의 todo_id 로 접근해도 처리되지 않도록 SQL 레벨에서 차단한다.
 */
@Mapper
public interface TodoMapper {

    // ===== 기본 CRUD =====

    /**
     * Todo 등록
     * useGeneratedKeys 로 생성된 PK 를 todo.todoId 에 주입
     */
    void insert(Todo todo);

    /**
     * Todo 단건 조회 (소유권 검증 전용)
     * user_id 조건 없이 todo_id 만으로 조회. Service 에서 validateOwnership 호출.
     * is_deleted = false 조건은 포함.
     */
    Todo findById(@Param("todoId") Long todoId);

    /**
     * Todo 수정
     * WHERE 조건에 user_id, is_deleted = false 포함.
     * 다른 사용자의 todo_id 로 호출해도 UPDATE 행이 0 이 되어 처리 안 됨.
     */
    void update(Todo todo);

    // ===== 날짜별 목록 조회 =====

    /**
     * 특정 날짜의 Todo 목록 조회
     * WHERE user_id, todo_date, is_deleted = false
     * ORDER BY priority (HIGH→MEDIUM→LOW), todo_id ASC
     */
    List<Todo> findByUserIdAndDate(
            @Param("userId") Long userId,
            @Param("todoDate") LocalDate todoDate
    );

    // ===== 통계 쿼리 =====

    /**
     * 특정 날짜의 전체 Todo 수 (is_deleted = false)
     */
    int countByUserIdAndDate(
            @Param("userId") Long userId,
            @Param("todoDate") LocalDate todoDate
    );

    /**
     * 특정 날짜의 완료(DONE) Todo 수 (is_deleted = false)
     */
    int countDoneByUserIdAndDate(
            @Param("userId") Long userId,
            @Param("todoDate") LocalDate todoDate
    );
}