package com.jungwoo.project.memo.execution;

import com.jungwoo.project.memo.common.security.UserPrincipal;
import com.jungwoo.project.memo.execution.dto.ExecutionItemCreateRequest;
import com.jungwoo.project.memo.execution.dto.ExecutionItemResponse;
import com.jungwoo.project.memo.execution.dto.ExecutionRecordResponse;
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
 * 실행 조각 컨트롤러.
 *
 * GET    /api/execution-items?date=            날짜별 조회
 * GET    /api/execution-items/range?startDate=&endDate=[&includeUnscheduled=] 날짜 범위 조회
 *        includeUnscheduled=false(기본): 배치된 항목만 — 주간 시간표
 *        includeUnscheduled=true : 계획 기간이 겹치는 미배치 항목도 포함 — 계획 화면
 * GET    /api/execution-items/pending?beforeDate= pending 조회
 * GET    /api/execution-items/by-course/{courseId} 프로젝트의 관련 실행
 * GET    /api/execution-items/records?startDate=&endDate= 실제로 일어난 결과(기록 화면)
 * POST   /api/execution-items                   생성
 * DELETE /api/execution-items/{id}?version=      삭제 (soft delete)
 */
@Slf4j
@RestController
@RequestMapping("/api/execution-items")
@RequiredArgsConstructor
public class ExecutionItemController {

    private final ExecutionItemService executionItemService;

    @GetMapping
    public ResponseEntity<List<ExecutionItemResponse>> getByDate(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        log.info("GET /api/execution-items - userId={}, date={}", principal.getUserId(), date);

        return ResponseEntity.ok(executionItemService.getByDate(principal.getUserId(), date));
    }

    @GetMapping("/range")
    public ResponseEntity<List<ExecutionItemResponse>> getByDateRange(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "false") boolean includeUnscheduled
    ) {
        log.info("GET /api/execution-items/range - userId={}, startDate={}, endDate={}, includeUnscheduled={}",
                principal.getUserId(), startDate, endDate, includeUnscheduled);

        return ResponseEntity.ok(executionItemService.getByDateRange(
                principal.getUserId(), startDate, endDate, includeUnscheduled));
    }

    @GetMapping("/pending")
    public ResponseEntity<List<ExecutionItemResponse>> getPending(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beforeDate
    ) {
        log.info("GET /api/execution-items/pending - userId={}, beforeDate={}",
                principal.getUserId(), beforeDate);

        return ResponseEntity.ok(executionItemService.getPending(principal.getUserId(), beforeDate));
    }

    @GetMapping("/by-course/{courseId}")
    public ResponseEntity<List<ExecutionItemResponse>> getByCourse(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long courseId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate today
    ) {
        LocalDate base = today != null ? today : LocalDate.now();
        return ResponseEntity.ok(executionItemService.getByCourse(principal.getUserId(), courseId, base));
    }

    @GetMapping("/records")
    public ResponseEntity<List<ExecutionRecordResponse>> getRecords(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate
    ) {
        return ResponseEntity.ok(executionItemService.getRecords(principal.getUserId(), startDate, endDate));
    }

    @PostMapping
    public ResponseEntity<ExecutionItemResponse> create(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody ExecutionItemCreateRequest request
    ) {
        log.info("POST /api/execution-items - userId={}, title={}", principal.getUserId(), request.getTitle());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(executionItemService.create(principal.getUserId(), request));
    }

    @DeleteMapping("/{executionItemId}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long executionItemId,
            @RequestParam Long version
    ) {
        log.info("DELETE /api/execution-items/{} - userId={}", executionItemId, principal.getUserId());

        executionItemService.delete(executionItemId, principal.getUserId(), version);

        return ResponseEntity.noContent().build();
    }
}
