package com.jungwoo.project.memo.schedule;

import com.jungwoo.project.memo.common.security.UserPrincipal;
import com.jungwoo.project.memo.schedule.dto.ScheduleBlockCreateRequest;
import com.jungwoo.project.memo.schedule.dto.ScheduleBlockPatchRequest;
import com.jungwoo.project.memo.schedule.dto.ScheduleBlockResponse;
import com.jungwoo.project.memo.schedule.dto.ScheduleBlockUpdateRequest;
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
 * 시간 블록 컨트롤러
 *
 * PUT  /{id} : 전체 수정 (모든 필드 필수, null 허용 필드는 null로 덮어씀)
 * PATCH /{id} : 부분 수정 (보낸 필드만 수정, null 필드는 기존값 유지)
 */
@Slf4j
@RestController
@RequestMapping("/api/schedule-blocks")
@RequiredArgsConstructor
public class ScheduleBlockController {

    private final ScheduleBlockService scheduleBlockService;

    /**
     * 시간 블록 생성
     * POST /api/schedule-blocks
     */
    @PostMapping
    public ResponseEntity<ScheduleBlockResponse> createScheduleBlock(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody ScheduleBlockCreateRequest request
    ) {
        log.info("POST /api/schedule-blocks - userId={}, title={}",
                principal.getUserId(), request.getTitle());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(scheduleBlockService.createScheduleBlock(principal.getUserId(), request));
    }

    /**
     * 날짜별 시간 블록 목록 조회
     * GET /api/schedule-blocks?date=2026-07-01
     */
    @GetMapping
    public ResponseEntity<List<ScheduleBlockResponse>> getScheduleBlocksByDate(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        log.info("GET /api/schedule-blocks - userId={}, date={}", principal.getUserId(), date);

        return ResponseEntity.ok(
                scheduleBlockService.getScheduleBlocksByDate(principal.getUserId(), date));
    }

    /**
     * 시간 블록 단건 조회
     * GET /api/schedule-blocks/{scheduleBlockId}
     */
    @GetMapping("/{scheduleBlockId}")
    public ResponseEntity<ScheduleBlockResponse> getScheduleBlock(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long scheduleBlockId
    ) {
        log.info("GET /api/schedule-blocks/{} - userId={}", scheduleBlockId, principal.getUserId());

        return ResponseEntity.ok(
                scheduleBlockService.getScheduleBlock(scheduleBlockId, principal.getUserId()));
    }

    /**
     * 시간 블록 전체 수정
     * PUT /api/schedule-blocks/{scheduleBlockId}
     * 모든 필드 필수. null 허용 필드(todoId, memo)는 null로 보내면 null로 덮어써진다.
     */
    @PutMapping("/{scheduleBlockId}")
    public ResponseEntity<ScheduleBlockResponse> replaceScheduleBlock(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long scheduleBlockId,
            @Valid @RequestBody ScheduleBlockUpdateRequest request
    ) {
        log.info("PUT /api/schedule-blocks/{} - userId={}", scheduleBlockId, principal.getUserId());

        return ResponseEntity.ok(
                scheduleBlockService.replaceScheduleBlock(
                        scheduleBlockId, principal.getUserId(), request));
    }

    /**
     * 시간 블록 부분 수정
     * PATCH /api/schedule-blocks/{scheduleBlockId}
     * 보낸 필드만 수정. null 필드는 기존값 유지.
     */
    @PatchMapping("/{scheduleBlockId}")
    public ResponseEntity<ScheduleBlockResponse> updateScheduleBlock(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long scheduleBlockId,
            @RequestBody ScheduleBlockPatchRequest request
    ) {
        log.info("PATCH /api/schedule-blocks/{} - userId={}", scheduleBlockId, principal.getUserId());

        return ResponseEntity.ok(
                scheduleBlockService.updateScheduleBlock(
                        scheduleBlockId, principal.getUserId(), request));
    }

    /**
     * 시간 블록 삭제 (소프트 삭제)
     * DELETE /api/schedule-blocks/{scheduleBlockId}
     */
    @DeleteMapping("/{scheduleBlockId}")
    public ResponseEntity<Void> deleteScheduleBlock(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long scheduleBlockId
    ) {
        log.info("DELETE /api/schedule-blocks/{} - userId={}", scheduleBlockId, principal.getUserId());

        scheduleBlockService.deleteScheduleBlock(scheduleBlockId, principal.getUserId());

        return ResponseEntity.noContent().build();
    }
}