package com.jungwoo.project.memo.execution;

import com.jungwoo.project.memo.common.security.UserPrincipal;
import com.jungwoo.project.memo.execution.dto.ExecutionItemCompleteRequest;
import com.jungwoo.project.memo.execution.dto.ExecutionItemHoldRequest;
import com.jungwoo.project.memo.execution.dto.ExecutionItemMoveRequest;
import com.jungwoo.project.memo.execution.dto.ExecutionItemReduceRequest;
import com.jungwoo.project.memo.execution.dto.ExecutionItemReopenRequest;
import com.jungwoo.project.memo.execution.dto.ExecutionItemResponse;
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
}
