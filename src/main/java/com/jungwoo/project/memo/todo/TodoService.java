package com.jungwoo.project.memo.todo;

import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.ForbiddenException;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.todo.domain.Todo;
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

/**
 * Todo 서비스
 *
 * 설계 원칙:
 * 1. userId 는 항상 JWT 인증 정보(@AuthenticationPrincipal)에서 넘어온다. 클라이언트 바디 금지.
 * 2. 조회 후 validateOwnership 으로 소유권 검증 → 위반 시 ForbiddenException.
 * 3. source_type 은 현재 단계에서 항상 MANUAL 로 고정.
 * 4. created_at / updated_at 은 LocalDateTime.now() 로 직접 주입 (Diary 도메인 동일 방식).
 * 5. 삭제는 is_deleted = true 소프트 삭제.
 * 6. 완료/미완료는 /complete, /uncomplete 로 분리하여 멱등성 보장.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TodoService {

    /** source_type 고정값 - 현재 단계에서는 항상 MANUAL */
    private static final String SOURCE_TYPE_MANUAL = "MANUAL";

    /** status 값 */
    private static final String STATUS_TODO = "TODO";
    private static final String STATUS_DONE = "DONE";

    private final TodoMapper todoMapper;

    // ===== Todo 생성 =====

    /**
     * Todo 생성
     * sourceType 은 항상 MANUAL 로 고정. 클라이언트 요청에서 받지 않는다.
     */
    @Transactional
    public TodoResponse createTodo(Long userId, TodoCreateRequest request) {
        log.info("Todo 생성 시작: userId={}, title={}", userId, request.getTitle());

        LocalDateTime now = LocalDateTime.now();

        Todo todo = Todo.builder()
                .userId(userId)
                .todoDate(request.getTodoDate())
                .title(request.getTitle())
                .content(request.getContent())
                .status(STATUS_TODO)
                .priority(request.getPriority() != null ? request.getPriority() : "MEDIUM")
                .sourceType(SOURCE_TYPE_MANUAL)     // 현재 단계 고정값
                .routineId(null)                     // Routine 기능 추후 구현 시 활용
                .completedAt(null)
                .isDeleted(false)
                .createdAt(now)
                .updatedAt(now)
                .build();

        todoMapper.insert(todo);

        log.info("Todo 생성 완료: todoId={}", todo.getTodoId());

        return TodoResponse.from(todo);
    }

    // ===== Todo 조회 =====

    /**
     * 날짜별 Todo 목록 조회
     * 우선순위 HIGH → MEDIUM → LOW 정렬은 SQL 에서 처리.
     */
    @Transactional(readOnly = true)
    public List<TodoResponse> getTodosByDate(Long userId, LocalDate todoDate) {
        log.info("날짜별 Todo 조회: userId={}, todoDate={}", userId, todoDate);

        List<Todo> todos = todoMapper.findByUserIdAndDate(userId, todoDate);

        return todos.stream()
                .map(TodoResponse::from)
                .toList();
    }

    /**
     * Todo 단건 조회
     */
    @Transactional(readOnly = true)
    public TodoResponse getTodo(Long todoId, Long userId) {
        log.info("Todo 단건 조회: todoId={}, userId={}", todoId, userId);

        Todo todo = findTodoById(todoId);
        validateOwnership(todo, userId);

        return TodoResponse.from(todo);
    }

    // ===== Todo 수정 =====

    /**
     * Todo 수정
     * title, content, todoDate, priority 만 변경 가능.
     * status, sourceType, routineId 는 이 메서드에서 변경하지 않는다.
     */
    @Transactional
    public TodoResponse updateTodo(Long todoId, Long userId, TodoUpdateRequest request) {
        log.info("Todo 수정 시작: todoId={}, userId={}", todoId, userId);

        Todo todo = findTodoById(todoId);
        validateOwnership(todo, userId);

        if (request.getTodoDate() != null) {
            todo.setTodoDate(request.getTodoDate());
        }

        if (request.getTitle() != null) {
            todo.setTitle(request.getTitle());
        }

        if (request.getContent() != null) {
            todo.setContent(request.getContent());
        }

        if (request.getPriority() != null) {
            todo.setPriority(request.getPriority());
        }

        todo.setUpdatedAt(LocalDateTime.now());

        todoMapper.update(todo);

        log.info("Todo 수정 완료: todoId={}", todoId);

        return TodoResponse.from(todo);
    }

    // ===== 완료 상태 변경 (complete / uncomplete 분리) =====

    /**
     * Todo 완료 처리 (TODO → DONE)
     * 이미 DONE 상태이면 변경하지 않고 그대로 반환. (멱등성)
     */
    @Transactional
    public TodoResponse completeTodo(Long todoId, Long userId) {
        log.info("Todo 완료 처리: todoId={}, userId={}", todoId, userId);

        Todo todo = findTodoById(todoId);
        validateOwnership(todo, userId);

        if (STATUS_DONE.equals(todo.getStatus())) {
            log.info("이미 완료 상태: todoId={}", todoId);
            return TodoResponse.from(todo);
        }

        todo.setStatus(STATUS_DONE);
        todo.setCompletedAt(LocalDateTime.now());
        todo.setUpdatedAt(LocalDateTime.now());

        todoMapper.update(todo);

        log.info("Todo 완료 처리 완료: todoId={}", todoId);

        return TodoResponse.from(todo);
    }

    /**
     * Todo 미완료 처리 (DONE → TODO)
     * 이미 TODO 상태이면 변경하지 않고 그대로 반환. (멱등성)
     */
    @Transactional
    public TodoResponse uncompleteTodo(Long todoId, Long userId) {
        log.info("Todo 미완료 처리: todoId={}, userId={}", todoId, userId);

        Todo todo = findTodoById(todoId);
        validateOwnership(todo, userId);

        if (STATUS_TODO.equals(todo.getStatus())) {
            log.info("이미 미완료 상태: todoId={}", todoId);
            return TodoResponse.from(todo);
        }

        todo.setStatus(STATUS_TODO);
        todo.setCompletedAt(null);
        todo.setUpdatedAt(LocalDateTime.now());

        todoMapper.update(todo);

        log.info("Todo 미완료 처리 완료: todoId={}", todoId);

        return TodoResponse.from(todo);
    }

    // ===== Todo 삭제 =====

    /**
     * Todo 삭제 (소프트 삭제)
     * is_deleted = true 로 변경. 실제 DELETE 는 사용하지 않는다.
     */
    @Transactional
    public void deleteTodo(Long todoId, Long userId) {
        log.info("Todo 삭제 시작: todoId={}, userId={}", todoId, userId);

        Todo todo = findTodoById(todoId);
        validateOwnership(todo, userId);

        todo.setIsDeleted(true);
        todo.setUpdatedAt(LocalDateTime.now());

        todoMapper.update(todo);

        log.info("Todo 삭제 완료: todoId={}", todoId);
    }

    // ===== 통계 =====

    /**
     * 특정 날짜 달성률 조회
     */
    @Transactional(readOnly = true)
    public TodoDailyStatisticsResponse getDailyStatistics(Long userId, LocalDate date) {
        log.info("Todo 달성률 조회: userId={}, date={}", userId, date);

        int totalCount = todoMapper.countByUserIdAndDate(userId, date);
        int doneCount  = todoMapper.countDoneByUserIdAndDate(userId, date);

        return TodoDailyStatisticsResponse.of(date, totalCount, doneCount);
    }

    // ===== private 헬퍼 메서드 (Diary 도메인과 동일 패턴) =====

    /**
     * todoId 로 Todo 조회. 없거나 소프트 삭제된 경우 NotFoundException.
     */
    private Todo findTodoById(Long todoId) {
        Todo todo = todoMapper.findById(todoId);

        if (todo == null || Boolean.TRUE.equals(todo.getIsDeleted())) {
            throw new NotFoundException(ErrorCode.TODO_NOT_FOUND);
        }

        return todo;
    }

    /**
     * 소유권 검증. userId 가 일치하지 않으면 ForbiddenException.
     * Diary 도메인의 validateOwnership 과 동일한 패턴.
     */
    private void validateOwnership(Todo todo, Long userId) {
        if (!todo.getUserId().equals(userId)) {
            throw new ForbiddenException(ErrorCode.NOT_RESOURCE_OWNER);
        }
    }
}