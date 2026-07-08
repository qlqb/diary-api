package com.jungwoo.project.memo.schedule;

import com.jungwoo.project.memo.common.security.UserPrincipal;
import com.jungwoo.project.memo.schedule.dto.ScheduleBlockHoldRequest;
import com.jungwoo.project.memo.schedule.dto.ScheduleBlockMoveRequest;
import com.jungwoo.project.memo.schedule.dto.ScheduleBlockReduceRequest;
import com.jungwoo.project.memo.schedule.dto.ScheduleBlockResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * 시간 블록 도메인 액션 컨트롤러.
 *
 * move / reduce / hold / complete 는 부수효과를 포함한 "명령"이므로
 * PUT/PATCH(리소스 수정)가 아니라 POST 하위 액션으로 설계한다. (v2.1 확정)
 *
 * POST /api/schedule-blocks/{id}/move      body: { toDate, memo? }
 * POST /api/schedule-blocks/{id}/reduce    body: { afterTitle, memo? }
 * POST /api/schedule-blocks/{id}/hold      body: { memo? } (생략 가능)
 * POST /api/schedule-blocks/{id}/complete  body 없음
 * GET  /api/schedule-blocks/pending?date=  pending 조회
 */
@Slf4j
@RestController
@RequestMapping("/api/schedule-blocks")
@RequiredArgsConstructor
public class ScheduleBlockActionController {

    private final ScheduleBlockActionService actionService;

    /**
     * 이동 (내일로)
     */
    @PostMapping("/{scheduleBlockId}/move")
    public ResponseEntity<ScheduleBlockResponse> move(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long scheduleBlockId,
            @Valid @RequestBody ScheduleBlockMoveRequest request
    ) {
        log.info("POST /api/schedule-blocks/{}/move - userId={}, toDate={}",
                scheduleBlockId, principal.getUserId(), request.getToDate());

        return ResponseEntity.ok(
                actionService.move(scheduleBlockId, principal.getUserId(), request));
    }

    /**
     * 작게 줄이기
     */
    @PostMapping("/{scheduleBlockId}/reduce")
    public ResponseEntity<ScheduleBlockResponse> reduce(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long scheduleBlockId,
            @Valid @RequestBody ScheduleBlockReduceRequest request
    ) {
        log.info("POST /api/schedule-blocks/{}/reduce - userId={}",
                scheduleBlockId, principal.getUserId());

        return ResponseEntity.ok(
                actionService.reduce(scheduleBlockId, principal.getUserId(), request));
    }

    /**
     * 보류 (body 생략 가능)
     */
    @PostMapping("/{scheduleBlockId}/hold")
    public ResponseEntity<ScheduleBlockResponse> hold(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long scheduleBlockId,
            @RequestBody(required = false) ScheduleBlockHoldRequest request
    ) {
        log.info("POST /api/schedule-blocks/{}/hold - userId={}",
                scheduleBlockId, principal.getUserId());

        String memo = request == null ? null : request.getMemo();

        return ResponseEntity.ok(
                actionService.hold(scheduleBlockId, principal.getUserId(), memo));
    }

    /**
     * 완료
     */
    @PostMapping("/{scheduleBlockId}/complete")
    public ResponseEntity<ScheduleBlockResponse> complete(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long scheduleBlockId
    ) {
        log.info("POST /api/schedule-blocks/{}/complete - userId={}",
                scheduleBlockId, principal.getUserId());

        return ResponseEntity.ok(
                actionService.complete(scheduleBlockId, principal.getUserId()));
    }

    /**
     * pending 조회.
     * date는 pending 판단 기준 운영일(baseOperationalDate)이다. date 생략 시 현재는 오늘(LocalDate.now)을 사용한다.
     *
     * 1차-A 스코프: ScheduleBlock만 표시. (미배치 Todo는 1차-B 확장 — 의도된 범위)
     */
    @GetMapping("/pending")
    public ResponseEntity<List<ScheduleBlockResponse>> getPending(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        LocalDate baseDate = date == null ? LocalDate.now() : date;

        log.info("GET /api/schedule-blocks/pending - userId={}, baseDate={}",
                principal.getUserId(), baseDate);

        return ResponseEntity.ok(
                actionService.getPendingBlocks(principal.getUserId(), baseDate));
    }
}
