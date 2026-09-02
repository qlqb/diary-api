package com.jungwoo.project.memo.scheduling;

import com.jungwoo.project.memo.common.security.UserPrincipal;
import com.jungwoo.project.memo.scheduling.dto.SchedulePreviewRequest;
import com.jungwoo.project.memo.scheduling.dto.SchedulePreviewResponse;
import com.jungwoo.project.memo.scheduling.service.SchedulePreviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 7일 범위 일정 후보 배치 미리보기. 이 컨트롤러의 어떤 엔드포인트도 OpenAI를 호출하지 않는다
 * — Timefold 재계산만 수행한다(REQ: AI 재호출 없이 재계산).
 */
@RestController
@RequiredArgsConstructor
public class SchedulePreviewController {

    private final SchedulePreviewService schedulePreviewService;

    /** 새로고침 복원용. 아직 한 번도 계산하지 않았으면 본문 없이 204를 반환한다. */
    @GetMapping("/api/ai/proposals/{proposalId}/schedule-preview")
    public ResponseEntity<SchedulePreviewResponse> get(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long proposalId
    ) {
        SchedulePreviewResponse stored = schedulePreviewService.getStoredPreview(principal.getUserId(), proposalId);
        return stored != null ? ResponseEntity.ok(stored) : ResponseEntity.noContent().build();
    }

    @PostMapping("/api/ai/proposals/{proposalId}/schedule-preview")
    @ResponseStatus(HttpStatus.OK)
    public SchedulePreviewResponse recompute(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long proposalId,
            @RequestBody(required = false) SchedulePreviewRequest request
    ) {
        SchedulePreviewRequest effectiveRequest = request != null ? request : SchedulePreviewRequest.builder().build();
        return schedulePreviewService.computePreview(principal.getUserId(), proposalId, effectiveRequest);
    }
}
