package com.jungwoo.project.memo.todo;

import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.todo.domain.Todo;
import com.jungwoo.project.memo.todo.domain.TodoOriginType;
import com.jungwoo.project.memo.todo.domain.TodoPriority;
import com.jungwoo.project.memo.todo.domain.TodoStatus;
import com.jungwoo.project.memo.todo.dto.TodoCreateRequest;
import com.jungwoo.project.memo.todo.dto.TodoDailyStatisticsResponse;
import com.jungwoo.project.memo.todo.dto.TodoResponse;
import com.jungwoo.project.memo.todo.dto.TodoUpdateRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TodoService {

    private final TodoMapper todoMapper;

    @Transactional
    public TodoResponse createTodo(Long userId, TodoCreateRequest request) {
        log.info("Todo 생성 시작: userId={}, title={}", userId, request.getTitle());

        Todo todo = Todo.builder()
                .userId(userId)
                .todoDate(request.getTodoDate())
                .title(request.getTitle())
                .content(request.getContent())
                .status(TodoStatus.TODO)
                .priority(request.getPriority() != null ? request.getPriority() : TodoPriority.MEDIUM)
                .originType(TodoOriginType.MANUAL)
                .modifiedAfterCreation(false)
                .routineId(null)
                .completedAt(null)
                .isDeleted(false)
                .build();

        todoMapper.insert(todo);

        // INSERT 후 DB의 createdAt/updatedAt을 포함해서 반환하기 위해 재조회
        Todo saved = todoMapper.findByIdAndUserId(todo.getTodoId(), userId);

        log.info("Todo 생성 완료: todoId={}", todo.getTodoId());

        return TodoResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<TodoResponse> getTodosByDate(Long userId, LocalDate todoDate, TodoStatus status) {
        log.info("날짜별 Todo 조회: userId={}, todoDate={}, status={}", userId, todoDate, status);

        return todoMapper.findByUserIdAndDate(userId, todoDate, status)
                .stream()
                .map(TodoResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public TodoResponse getTodo(Long todoId, Long userId) {
        log.info("Todo 단건 조회: todoId={}, userId={}", todoId, userId);

        return TodoResponse.from(findTodoByIdAndUserId(todoId, userId));
    }

    @Transactional
    public TodoResponse updateTodo(Long todoId, Long userId, TodoUpdateRequest request) {
        log.info("Todo 수정 시작: todoId={}, userId={}", todoId, userId);

        Todo todo = findTodoByIdAndUserId(todoId, userId);

        if (request.getTodoDate() != null) todo.setTodoDate(request.getTodoDate());
        if (request.getTitle()    != null) todo.setTitle(request.getTitle());
        if (request.getContent()  != null) todo.setContent(request.getContent());
        if (request.getPriority() != null) todo.setPriority(request.getPriority());

        // AI/루틴 Todo를 수정하면 modifiedAfterCreation = true
        if (TodoOriginType.AI_SUGGESTED.equals(todo.getOriginType())
                || TodoOriginType.ROUTINE_GENERATED.equals(todo.getOriginType())) {
            todo.setModifiedAfterCreation(true);
        }

        todoMapper.update(todo);

        log.info("Todo 수정 완료: todoId={}", todoId);

        return TodoResponse.from(todoMapper.findByIdAndUserId(todoId, userId));
    }

    @Transactional
    public TodoResponse markAsDone(Long todoId, Long userId) {
        log.info("Todo 완료 처리: todoId={}, userId={}", todoId, userId);

        Todo todo = findTodoByIdAndUserId(todoId, userId);

        if (TodoStatus.DONE.equals(todo.getStatus())) {
            log.info("이미 완료 상태: todoId={}", todoId);
            return TodoResponse.from(todo);
        }

        todo.setStatus(TodoStatus.DONE);
        todo.setCompletedAt(LocalDateTime.now());
        todoMapper.update(todo);

        return TodoResponse.from(todoMapper.findByIdAndUserId(todoId, userId));
    }

    @Transactional
    public TodoResponse markAsTodo(Long todoId, Long userId) {
        log.info("Todo 미완료 처리: todoId={}, userId={}", todoId, userId);

        Todo todo = findTodoByIdAndUserId(todoId, userId);

        if (TodoStatus.TODO.equals(todo.getStatus())) {
            log.info("이미 미완료 상태: todoId={}", todoId);
            return TodoResponse.from(todo);
        }

        todo.setStatus(TodoStatus.TODO);
        todo.setCompletedAt(null);
        todoMapper.update(todo);

        return TodoResponse.from(todoMapper.findByIdAndUserId(todoId, userId));
    }

    @Transactional
    public void deleteTodo(Long todoId, Long userId) {
        log.info("Todo 삭제 시작: todoId={}, userId={}", todoId, userId);

        Todo todo = findTodoByIdAndUserId(todoId, userId);
        todo.setIsDeleted(true);
        todoMapper.update(todo);

        log.info("Todo 삭제 완료: todoId={}", todoId);
    }

    @Transactional(readOnly = true)
    public TodoDailyStatisticsResponse getDailyStatistics(Long userId, LocalDate date) {
        int totalCount = todoMapper.countByUserIdAndDate(userId, date);
        int doneCount  = todoMapper.countDoneByUserIdAndDate(userId, date);
        return TodoDailyStatisticsResponse.of(date, totalCount, doneCount);
    }

    private Todo findTodoByIdAndUserId(Long todoId, Long userId) {
        Todo todo = todoMapper.findByIdAndUserId(todoId, userId);
        if (todo == null) {
            throw new NotFoundException(ErrorCode.TODO_NOT_FOUND);
        }
        return todo;
    }
}
