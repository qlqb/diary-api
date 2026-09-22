package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.dto.UserContextResponse;
import com.jungwoo.project.memo.common.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * "AI가 이해한 내 상황". 조회와, 사용자가 직접 하는 고치기·확인·지우기.
 *
 * <p>모든 변경 응답의 staleDraftIds는 그 내용을 근거로 든 <b>열린</b> 계획 초안이다 — 화면이 "갱신 필요"로 표시한다.
 * 이미 적용한 일정은 바뀌지 않는다.
 */
@Slf4j
@RestController
@RequestMapping("/api/contexts")
@RequiredArgsConstructor
public class UserContextController {

    private final UserContextService userContextService;

    public record EditRequest(String content) {
    }

    /** @param staleDraftIds 이 내용을 근거로 든 열린 계획 초안 */
    public record ChangeResponse(UserContextResponse context, List<Long> staleDraftIds) {
    }

    @GetMapping
    public ResponseEntity<List<UserContextResponse>> list(
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(userContextService.listActiveAndStale(principal.getUserId()));
    }

    @PatchMapping("/{contextId}")
    public ResponseEntity<ChangeResponse> edit(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long contextId,
            @RequestBody EditRequest request
    ) {
        log.info("PATCH /api/contexts/{} - userId={}", contextId, principal.getUserId());
        UserContextService.Change change = userContextService.edit(principal.getUserId(), contextId,
                request == null ? null : request.content());
        return ResponseEntity.ok(new ChangeResponse(change.context(), change.staleDraftIds()));
    }

    @PostMapping("/{contextId}/confirm")
    public ResponseEntity<ChangeResponse> confirm(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long contextId
    ) {
        UserContextService.Change change = userContextService.confirm(principal.getUserId(), contextId);
        return ResponseEntity.ok(new ChangeResponse(change.context(), change.staleDraftIds()));
    }

    @DeleteMapping("/{contextId}")
    public ResponseEntity<ChangeResponse> withdraw(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long contextId
    ) {
        log.info("DELETE /api/contexts/{} - userId={}", contextId, principal.getUserId());
        UserContextService.Change change = userContextService.withdraw(principal.getUserId(), contextId);
        return ResponseEntity.ok(new ChangeResponse(change.context(), change.staleDraftIds()));
    }
}
