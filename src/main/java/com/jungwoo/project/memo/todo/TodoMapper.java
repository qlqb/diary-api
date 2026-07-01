package com.jungwoo.project.memo.todo;

import com.jungwoo.project.memo.todo.domain.Todo;
import com.jungwoo.project.memo.todo.domain.TodoStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface TodoMapper {

    // INSERT. created_at/updated_at은 DB DEFAULT로 자동처리.
    void insert(Todo todo);

    // todo_id + user_id + is_deleted=0 조건. 다른 사용자 접근 시 null 반환.
    Todo findByIdAndUserId(
            @Param("todoId") Long todoId,
            @Param("userId") Long userId
    );

    // WHERE에 todo_id + user_id + is_deleted=0 포함. updated_at은 DB ON UPDATE 자동처리.
    void update(Todo todo);

    // status가 null이면 전체, 값이 있으면 해당 상태만 조회.
    List<Todo> findByUserIdAndDate(
            @Param("userId") Long userId,
            @Param("todoDate") LocalDate todoDate,
            @Param("status") TodoStatus status
    );

    int countByUserIdAndDate(
            @Param("userId") Long userId,
            @Param("todoDate") LocalDate todoDate
    );

    int countDoneByUserIdAndDate(
            @Param("userId") Long userId,
            @Param("todoDate") LocalDate todoDate
    );
}
