package com.jungwoo.project.memo.learning.events.session;

import com.jungwoo.project.memo.common.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 수업 확인: 끝난 수업 중 아직 확인하지 않은 회차와 기본값, 확인(수업 후·주간 공용), 과목별 끄기.
 */
@RestController
@RequiredArgsConstructor
public class ClassSessionController {

    private final ClassSessionService service;

    public record ConfirmRequest(Long courseId, List<ClassSessionService.ConfirmItem> items) {
    }

    public record PromptRequest(Boolean enabled) {
    }

    @GetMapping("/api/courses/{courseId}/class-sessions/pending")
    public ResponseEntity<ClassSessionService.PendingView> pending(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long courseId,
            @RequestParam(defaultValue = "14") int days
    ) {
        return ResponseEntity.ok(service.pending(principal.getUserId(), courseId, days));
    }

    @PutMapping("/api/class-sessions")
    public ResponseEntity<List<ClassSessionService.Confirmed>> confirm(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody ConfirmRequest request
    ) {
        if (request == null || request.courseId() == null) {
            throw new com.jungwoo.project.memo.common.exception.BadRequestException(
                    com.jungwoo.project.memo.common.exception.ErrorCode.INVALID_INPUT_VALUE);
        }
        return ResponseEntity.ok(service.confirm(principal.getUserId(), request.courseId(), request.items()));
    }

    @PatchMapping("/api/courses/{courseId}/class-prompt")
    public ResponseEntity<Void> prompt(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long courseId,
            @RequestBody PromptRequest request
    ) {
        if (request == null || request.enabled() == null) {
            throw new com.jungwoo.project.memo.common.exception.BadRequestException(
                    com.jungwoo.project.memo.common.exception.ErrorCode.INVALID_INPUT_VALUE);
        }
        service.setPromptEnabled(principal.getUserId(), courseId, request.enabled());
        return ResponseEntity.noContent().build();
    }
}
