package com.jungwoo.project.memo.todo;

import com.jungwoo.project.memo.common.security.UserPrincipal;
import com.jungwoo.project.memo.todo.dto.TodoCreateRequest;
import com.jungwoo.project.memo.todo.dto.TodoDailyStatisticsResponse;
import com.jungwoo.project.memo.todo.dto.TodoResponse;
import com.jungwoo.project.memo.todo.dto.TodoUpdateRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Todo 컨트롤러
 *
 * 설계 원칙:
 * 1. userId 는 @AuthenticationPrincipal 에서만 가져온다. 클라이언트 바디에서 받지 않는다.
 * 2. 비즈니스 로직은 모두 TodoService 에 위임한다.
 * 3. try-catch 없음. 예외는 GlobalExceptionHandler 에서 처리.
 * 4. 완료/미완료는 toggle 이 아닌 /complete, /uncomplete 로 분리.
 *    (중복 요청 시 상태가 의도치 않게 뒤집히는 문제 방지)
 */
@Slf4j
@RestController
@RequestMapping("/api/todos")
@RequiredArgsConstructor
public class TodoController {

    private final TodoService todoService;

    /**
     * Todo 생성
     *
     * POST /api/todos
     */
    @PostMapping
    public ResponseEntity<TodoResponse> createTodo(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody TodoCreateRequest request
    ) {
        log.info("POST /api/todos - userId={}, title={}", principal.getUserId(), request.getTitle());

        TodoResponse response = todoService.createTodo(principal.getUserId(), request);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 날짜별 Todo 목록 조회
     *
     * GET /api/todos?date=2026-06-25
     */
    @GetMapping
    public ResponseEntity<List<TodoResponse>> getTodosByDate(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        log.info("GET /api/todos - userId={}, date={}", principal.getUserId(), date);

        List<TodoResponse> response = todoService.getTodosByDate(principal.getUserId(), date);

        return ResponseEntity.ok(response);
    }

    /**
     * Todo 단건 조회
     *
     * GET /api/todos/{todoId}
     */
    @GetMapping("/{todoId}")
    public ResponseEntity<TodoResponse> getTodo(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long todoId
    ) {
        log.info("GET /api/todos/{} - userId={}", todoId, principal.getUserId());

        TodoResponse response = todoService.getTodo(todoId, principal.getUserId());

        return ResponseEntity.ok(response);
    }

    /**
     * Todo 수정
     *
     * PUT /api/todos/{todoId}
     */
    @PutMapping("/{todoId}")
    public ResponseEntity<TodoResponse> updateTodo(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long todoId,
            @Valid @RequestBody TodoUpdateRequest request
    ) {
        log.info("PUT /api/todos/{} - userId={}", todoId, principal.getUserId());

        TodoResponse response = todoService.updateTodo(todoId, principal.getUserId(), request);

        return ResponseEntity.ok(response);
    }

    /**
     * Todo 완료 처리 (TODO → DONE)
     *
     * PATCH /api/todos/{todoId}/complete
     * 이미 DONE 이면 그대로 반환 (멱등성).
     */
    @PatchMapping("/{todoId}/complete")
    public ResponseEntity<TodoResponse> completeTodo(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long todoId
    ) {
        log.info("PATCH /api/todos/{}/complete - userId={}", todoId, principal.getUserId());

        TodoResponse response = todoService.completeTodo(todoId, principal.getUserId());

        return ResponseEntity.ok(response);
    }

    /**
     * Todo 미완료 처리 (DONE → TODO)
     *
     * PATCH /api/todos/{todoId}/uncomplete
     * 이미 TODO 이면 그대로 반환 (멱등성).
     */
    @PatchMapping("/{todoId}/uncomplete")
    public ResponseEntity<TodoResponse> uncompleteTodo(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long todoId
    ) {
        log.info("PATCH /api/todos/{}/uncomplete - userId={}", todoId, principal.getUserId());

        TodoResponse response = todoService.uncompleteTodo(todoId, principal.getUserId());

        return ResponseEntity.ok(response);
    }

    /**
     * Todo 삭제 (소프트 삭제)
     *
     * DELETE /api/todos/{todoId}
     */
    @DeleteMapping("/{todoId}")
    public ResponseEntity<Void> deleteTodo(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long todoId
    ) {
        log.info("DELETE /api/todos/{} - userId={}", todoId, principal.getUserId());

        todoService.deleteTodo(todoId, principal.getUserId());

        return ResponseEntity.noContent().build();
    }

    /**
     * 특정 날짜 달성률 조회
     *
     * GET /api/todos/statistics/daily?date=2026-06-25
     */
    @GetMapping("/statistics/daily")
    public ResponseEntity<TodoDailyStatisticsResponse> getDailyStatistics(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        log.info("GET /api/todos/statistics/daily - userId={}, date={}",
                principal.getUserId(), date);

        TodoDailyStatisticsResponse response =
                todoService.getDailyStatistics(principal.getUserId(), date);

        return ResponseEntity.ok(response);
    }
}