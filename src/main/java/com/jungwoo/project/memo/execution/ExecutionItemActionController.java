package com.jungwoo.project.memo.execution;

import com.jungwoo.project.memo.common.security.UserPrincipal;
import com.jungwoo.project.memo.execution.dto.ExecutionItemCompleteRequest;
import com.jungwoo.project.memo.execution.dto.ExecutionItemHoldRequest;
import com.jungwoo.project.memo.execution.dto.ExecutionItemMoveRequest;
import com.jungwoo.project.memo.execution.dto.ExecutionItemUnscheduleRequest;
import com.jungwoo.project.memo.execution.dto.ExecutionItemPartialRequest;
import com.jungwoo.project.memo.execution.dto.ExecutionItemReduceRequest;
import com.jungwoo.project.memo.execution.dto.ExecutionItemReopenRequest;
import com.jungwoo.project.memo.execution.dto.ExecutionItemResponse;
import com.jungwoo.project.memo.execution.dto.ExecutionItemResumeRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * 실행 조각 도메인 액션 컨트롤러.
 *
 * complete/reopen/move/reduce/hold는 부수효과(Event/Record 기록)를 포함한 명령이므로
 * PUT/PATCH가 아니라 POST 하위 액션으로 분리한다.
 *
 * 모든 액션은 요청 본문에 현재 알고 있는 version을 담아 보내야 하며,
 * 서버의 실제 version과 다르면 409 Conflict를 반환한다.
 */
@Slf4j
@RestController
@RequestMapping("/api/execution-items")
@RequiredArgsConstructor
public class ExecutionItemActionController {

    private final ExecutionItemService executionItemService;

    @PostMapping("/{executionItemId}/complete")
    public ResponseEntity<ExecutionItemResponse> complete(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long executionItemId,
            @Valid @RequestBody ExecutionItemCompleteRequest request
    ) {
        log.info("POST /api/execution-items/{}/complete - userId={}", executionItemId, principal.getUserId());

        return ResponseEntity.ok(
                executionItemService.complete(executionItemId, principal.getUserId(), request));
    }

    @PostMapping("/{executionItemId}/reopen")
    public ResponseEntity<ExecutionItemResponse> reopen(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long executionItemId,
            @Valid @RequestBody ExecutionItemReopenRequest request
    ) {
        log.info("POST /api/execution-items/{}/reopen - userId={}", executionItemId, principal.getUserId());

        return ResponseEntity.ok(
                executionItemService.reopen(executionItemId, principal.getUserId(), request));
    }

    @PostMapping("/{executionItemId}/move")
    public ResponseEntity<ExecutionItemResponse> move(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long executionItemId,
            @Valid @RequestBody ExecutionItemMoveRequest request
    ) {
        log.info("POST /api/execution-items/{}/move - userId={}, toDate={}",
                executionItemId, principal.getUserId(), request.getToDate());

        return ResponseEntity.ok(
                executionItemService.move(executionItemId, principal.getUserId(), request));
    }

    /**
     * 배치 해제. 롤링 배치 결과를 되돌리는 수단이다.
     *
     * PATCH를 쓴다 — 배치 상태 필드만 바꾸는 부분 수정이고, 같은 요청을 두 번 보내면
     * 두 번째는 이미 UNSCHEDULED라 400으로 거절된다(멱등하지 않으므로 PUT은 아니다).
     *
     * body의 planningStartDate/EndDate는 서버가 추론하지 않는다 — 같은 날짜에 계획이
     * 여럿 걸릴 수 있어 "그 계획의 기간"을 서버가 고를 수 없다. 두 값이 null이면
     * 미분류로 나간다.
     */
    @PatchMapping("/{executionItemId}/unschedule")
    public ResponseEntity<ExecutionItemResponse> unschedule(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long executionItemId,
            @Valid @RequestBody ExecutionItemUnscheduleRequest request
    ) {
        log.info("PATCH /api/execution-items/{}/unschedule - userId={}, planningRange={}~{}",
                executionItemId, principal.getUserId(),
                request.getPlanningStartDate(), request.getPlanningEndDate());

        return ResponseEntity.ok(
                executionItemService.unschedule(executionItemId, principal.getUserId(), request));
    }

    @PostMapping("/{executionItemId}/reduce")
    public ResponseEntity<ExecutionItemResponse> reduce(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long executionItemId,
            @Valid @RequestBody ExecutionItemReduceRequest request
    ) {
        log.info("POST /api/execution-items/{}/reduce - userId={}", executionItemId, principal.getUserId());

        return ResponseEntity.ok(
                executionItemService.reduce(executionItemId, principal.getUserId(), request));
    }

    @PostMapping("/{executionItemId}/partial")
    public ResponseEntity<ExecutionItemResponse> partial(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long executionItemId,
            @Valid @RequestBody ExecutionItemPartialRequest request
    ) {
        log.info("POST /api/execution-items/{}/partial - userId={}", executionItemId, principal.getUserId());

        return ResponseEntity.ok(
                executionItemService.recordPartial(executionItemId, principal.getUserId(), request));
    }

    @PostMapping("/{executionItemId}/hold")
    public ResponseEntity<ExecutionItemResponse> hold(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long executionItemId,
            @Valid @RequestBody ExecutionItemHoldRequest request
    ) {
        log.info("POST /api/execution-items/{}/hold - userId={}", executionItemId, principal.getUserId());

        return ResponseEntity.ok(
                executionItemService.hold(executionItemId, principal.getUserId(), request));
    }

    @PostMapping("/{executionItemId}/resume")
    public ResponseEntity<ExecutionItemResponse> resume(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long executionItemId,
            @Valid @RequestBody ExecutionItemResumeRequest request
    ) {
        log.info("POST /api/execution-items/{}/resume - userId={}", executionItemId, principal.getUserId());

        return ResponseEntity.ok(
                executionItemService.resume(executionItemId, principal.getUserId(), request));
    }
}
