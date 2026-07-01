package com.jungwoo.project.memo.todo;

import com.jungwoo.project.memo.common.security.UserPrincipal;
import com.jungwoo.project.memo.todo.domain.TodoStatus;
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

@Slf4j
@RestController
@RequestMapping("/api/todos")
@RequiredArgsConstructor
public class TodoController {

    private final TodoService todoService;

    @PostMapping
    public ResponseEntity<TodoResponse> createTodo(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody TodoCreateRequest request
    ) {
        log.info("POST /api/todos - userId={}, title={}", principal.getUserId(), request.getTitle());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(todoService.createTodo(principal.getUserId(), request));
    }

    // GET /api/todos?date=2026-06-25
    // GET /api/todos?date=2026-06-25&status=TODO
    // GET /api/todos?date=2026-06-25&status=DONE
    @GetMapping
    public ResponseEntity<List<TodoResponse>> getTodosByDate(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) TodoStatus status
    ) {
        log.info("GET /api/todos - userId={}, date={}, status={}", principal.getUserId(), date, status);
        return ResponseEntity.ok(todoService.getTodosByDate(principal.getUserId(), date, status));
    }

    @GetMapping("/{todoId}")
    public ResponseEntity<TodoResponse> getTodo(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long todoId
    ) {
        log.info("GET /api/todos/{} - userId={}", todoId, principal.getUserId());
        return ResponseEntity.ok(todoService.getTodo(todoId, principal.getUserId()));
    }

    @PutMapping("/{todoId}")
    public ResponseEntity<TodoResponse> updateTodo(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long todoId,
            @Valid @RequestBody TodoUpdateRequest request
    ) {
        log.info("PUT /api/todos/{} - userId={}", todoId, principal.getUserId());
        return ResponseEntity.ok(todoService.updateTodo(todoId, principal.getUserId(), request));
    }

    // PATCH /api/todos/{todoId}/done  → 완료 처리 (TODO → DONE)
    @PatchMapping("/{todoId}/done")
    public ResponseEntity<TodoResponse> markAsDone(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long todoId
    ) {
        log.info("PATCH /api/todos/{}/done - userId={}", todoId, principal.getUserId());
        return ResponseEntity.ok(todoService.markAsDone(todoId, principal.getUserId()));
    }

    // PATCH /api/todos/{todoId}/todo  → 미완료 처리 (DONE → TODO)
    @PatchMapping("/{todoId}/todo")
    public ResponseEntity<TodoResponse> markAsTodo(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long todoId
    ) {
        log.info("PATCH /api/todos/{}/todo - userId={}", todoId, principal.getUserId());
        return ResponseEntity.ok(todoService.markAsTodo(todoId, principal.getUserId()));
    }

    @DeleteMapping("/{todoId}")
    public ResponseEntity<Void> deleteTodo(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long todoId
    ) {
        log.info("DELETE /api/todos/{} - userId={}", todoId, principal.getUserId());
        todoService.deleteTodo(todoId, principal.getUserId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/statistics/daily")
    public ResponseEntity<TodoDailyStatisticsResponse> getDailyStatistics(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        log.info("GET /api/todos/statistics/daily - userId={}, date={}", principal.getUserId(), date);
        return ResponseEntity.ok(todoService.getDailyStatistics(principal.getUserId(), date));
    }
}
