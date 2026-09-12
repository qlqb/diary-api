package com.jungwoo.project.memo.material.analysis;

import com.jungwoo.project.memo.common.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 자동 분석 상태.
 *
 * GET  /api/materials/analysis/overview               내 자료 전체의 상태 요약 + 자료별 상태
 * POST /api/materials/analysis/pause                  자동 분석 일시중지(내 작업만)
 * POST /api/materials/analysis/resume
 * GET  /api/materials/{id}/analysis-status
 * POST /api/materials/{id}/analysis-status/retry      다시 시도(앞으로 당긴다)
 * GET  /api/materials/{id}/sections                    분석된 구간(원문 보기)
 * GET  /api/material-sections/{sectionId}
 */
@RestController
@RequiredArgsConstructor
public class MaterialAnalysisStatusController {

    private final MaterialAnalysisStatusService statusService;
    private final MaterialAnalysisJobService jobService;

    @GetMapping("/api/materials/analysis/overview")
    public ResponseEntity<MaterialAnalysisOverviewResponse> overview(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(statusService.overview(principal.getUserId()));
    }

    @PostMapping("/api/materials/analysis/pause")
    public ResponseEntity<MaterialAnalysisOverviewResponse> pause(@AuthenticationPrincipal UserPrincipal principal) {
        jobService.pause(principal.getUserId());
        return ResponseEntity.ok(statusService.overview(principal.getUserId()));
    }

    @PostMapping("/api/materials/analysis/resume")
    public ResponseEntity<MaterialAnalysisOverviewResponse> resume(@AuthenticationPrincipal UserPrincipal principal) {
        jobService.resume(principal.getUserId());
        return ResponseEntity.ok(statusService.overview(principal.getUserId()));
    }

    @GetMapping("/api/materials/{materialId}/analysis-status")
    public ResponseEntity<MaterialAnalysisStatusResponse> status(@AuthenticationPrincipal UserPrincipal principal,
                                                                 @PathVariable Long materialId) {
        return ResponseEntity.ok(statusService.forMaterial(principal.getUserId(), materialId));
    }

    @PostMapping("/api/materials/{materialId}/analysis-status/retry")
    public ResponseEntity<MaterialAnalysisStatusResponse> retry(@AuthenticationPrincipal UserPrincipal principal,
                                                                @PathVariable Long materialId) {
        return ResponseEntity.ok(statusService.retry(principal.getUserId(), materialId));
    }

    @GetMapping("/api/materials/{materialId}/sections")
    public ResponseEntity<List<MaterialSectionResponse>> sections(@AuthenticationPrincipal UserPrincipal principal,
                                                                  @PathVariable Long materialId) {
        return ResponseEntity.ok(statusService.sections(principal.getUserId(), materialId));
    }

    @GetMapping("/api/material-sections/{sectionId}")
    public ResponseEntity<MaterialSectionResponse> section(@AuthenticationPrincipal UserPrincipal principal,
                                                           @PathVariable Long sectionId) {
        return ResponseEntity.ok(statusService.section(principal.getUserId(), sectionId));
    }
}
