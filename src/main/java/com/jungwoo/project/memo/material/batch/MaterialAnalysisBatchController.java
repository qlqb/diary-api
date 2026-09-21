package com.jungwoo.project.memo.material.batch;

import com.jungwoo.project.memo.common.security.UserPrincipal;
import com.jungwoo.project.memo.material.batch.dto.BatchEstimateResponse;
import com.jungwoo.project.memo.material.batch.dto.BatchRequests;
import com.jungwoo.project.memo.material.batch.dto.BatchResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 업로드·분석 묶음.
 *
 * <pre>
 * POST /api/materials/analysis/estimate            고른 파일의 예상 시간만(저장 없음)
 * POST /api/materials/analysis/batches             묶음 열기(구성원 고정) → 자리 목록
 * GET  /api/materials/analysis/batches/{id}        진행 상태
 * GET  /api/materials/analysis/batches?courseId=   아직 도는 묶음(화면 복원용)
 * </pre>
 *
 * 업로드는 기존 경로를 그대로 쓰고 {@code batchItemId}만 실어 보낸다.
 */
@RestController
@RequiredArgsConstructor
public class MaterialAnalysisBatchController {

    private final MaterialAnalysisBatchService batchService;

    @PostMapping("/api/materials/analysis/estimate")
    public ResponseEntity<BatchEstimateResponse> estimate(@AuthenticationPrincipal UserPrincipal principal,
                                                          @Valid @RequestBody BatchRequests.Estimate request) {
        return ResponseEntity.ok(batchService.estimate(request.getFiles()));
    }

    @PostMapping("/api/materials/analysis/batches")
    public ResponseEntity<BatchResponse> create(@AuthenticationPrincipal UserPrincipal principal,
                                                @Valid @RequestBody BatchRequests.Create request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(batchService.create(principal.getUserId(), request));
    }

    @GetMapping("/api/materials/analysis/batches/{batchId}")
    public ResponseEntity<BatchResponse> get(@AuthenticationPrincipal UserPrincipal principal,
                                             @PathVariable Long batchId) {
        return ResponseEntity.ok(batchService.get(principal.getUserId(), batchId));
    }

    @GetMapping("/api/materials/analysis/batches")
    public ResponseEntity<List<BatchResponse>> listOpen(@AuthenticationPrincipal UserPrincipal principal,
                                                        @RequestParam(name = "courseId", required = false)
                                                        Long courseId) {
        return ResponseEntity.ok(batchService.listOpen(principal.getUserId(), courseId));
    }
}
