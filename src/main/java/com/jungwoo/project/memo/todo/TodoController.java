package com.jungwoo.project.memo.todo;

import com.jungwoo.project.memo.common.security.UserPrincipal;
import com.jungwoo.project.memo.todo.domain.TodoStatus;
import com.jungwoo.project.memo.todo.dto.TodoCreateRequest;
import com.jungwoo.project.memo.todo.dto.TodoDailyStatisticsResponse;
import com.jungwoo.project.memo.todo.dto.TodoPatchRequest;
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
 * PUT  /{todoId}       : 전체 수정 (모든 필드 필수, null 허용 필드는 null로 덮어씀)
 * PATCH /{todoId}      : 부분 수정 (보낸 필드만 수정, null 필드는 기존값 유지)
 * PATCH /{todoId}/done : 완료 처리
 * PATCH /{todoId}/todo : 미완료 처리
 */
@Slf4j
@RestController
@RequestMapping("/api/todos")
@RequiredArgsConstructor
public class TodoController {

    private final TodoService todoService;

    /**
     * Todo 생성
     * POST /api/todos
     */
    @PostMapping
    public ResponseEntity<TodoResponse> createTodo(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody TodoCreateRequest request
    ) {
        log.info("POST /api/todos - userId={}, title={}", principal.getUserId(), request.getTitle());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(todoService.createTodo(principal.getUserId(), request));
    }

    /**
     * 날짜별 Todo 목록 조회
     * GET /api/todos?date=2026-07-01
     * GET /api/todos?date=2026-07-01&status=TODO
     */
    @GetMapping
    public ResponseEntity<List<TodoResponse>> getTodosByDate(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) TodoStatus status
    ) {
        log.info("GET /api/todos - userId={}, date={}, status={}", principal.getUserId(), date, status);
        return ResponseEntity.ok(todoService.getTodosByDate(principal.getUserId(), date, status));
    }

    /**
     * Todo 단건 조회
     * GET /api/todos/{todoId}
     */
    @GetMapping("/{todoId}")
    public ResponseEntity<TodoResponse> getTodo(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long todoId
    ) {
        log.info("GET /api/todos/{} - userId={}", todoId, principal.getUserId());
        return ResponseEntity.ok(todoService.getTodo(todoId, principal.getUserId()));
    }

    /**
     * Todo 전체 수정
     * PUT /api/todos/{todoId}
     * 모든 필드 필수. null 허용 필드(content)는 null로 덮어써진다.
     */
    @PutMapping("/{todoId}")
    public ResponseEntity<TodoResponse> replaceTodo(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long todoId,
            @Valid @RequestBody TodoUpdateRequest request
    ) {
        log.info("PUT /api/todos/{} - userId={}", todoId, principal.getUserId());
        return ResponseEntity.ok(todoService.replaceTodo(todoId, principal.getUserId(), request));
    }

    /**
     * Todo 부분 수정
     * PATCH /api/todos/{todoId}
     * 보낸 필드만 수정. null 필드는 기존값 유지.
     */
    @PatchMapping("/{todoId}")
    public ResponseEntity<TodoResponse> updateTodo(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long todoId,
            @RequestBody TodoPatchRequest request
    ) {
        log.info("PATCH /api/todos/{} - userId={}", todoId, principal.getUserId());
        return ResponseEntity.ok(todoService.updateTodo(todoId, principal.getUserId(), request));
    }

    /**
     * Todo 완료 처리 (TODO → DONE)
     * PATCH /api/todos/{todoId}/done
     * 이미 DONE이면 그대로 반환 (멱등성).
     */
    @PatchMapping("/{todoId}/done")
    public ResponseEntity<TodoResponse> markAsDone(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long todoId
    ) {
        log.info("PATCH /api/todos/{}/done - userId={}", todoId, principal.getUserId());
        return ResponseEntity.ok(todoService.markAsDone(todoId, principal.getUserId()));
    }

    /**
     * Todo 미완료 처리 (DONE → TODO)
     * PATCH /api/todos/{todoId}/todo
     * 이미 TODO이면 그대로 반환 (멱등성).
     */
    @PatchMapping("/{todoId}/todo")
    public ResponseEntity<TodoResponse> markAsTodo(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long todoId
    ) {
        log.info("PATCH /api/todos/{}/todo - userId={}", todoId, principal.getUserId());
        return ResponseEntity.ok(todoService.markAsTodo(todoId, principal.getUserId()));
    }

    /**
     * Todo 삭제 (소프트 삭제)
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
     * GET /api/todos/statistics/daily?date=2026-07-01
     */
    @GetMapping("/statistics/daily")
    public ResponseEntity<TodoDailyStatisticsResponse> getDailyStatistics(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        log.info("GET /api/todos/statistics/daily - userId={}, date={}", principal.getUserId(), date);
        return ResponseEntity.ok(todoService.getDailyStatistics(principal.getUserId(), date));
    }
}