package com.jungwoo.project.memo.material;

import com.jungwoo.project.memo.common.security.UserPrincipal;
import com.jungwoo.project.memo.material.dto.MaterialAnalysisEditRequest;
import com.jungwoo.project.memo.material.dto.MaterialAnalysisResponse;
import com.jungwoo.project.memo.material.dto.MaterialAnalysisStartResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Material Agent 분석 draft/review/apply 컨트롤러.
 *
 * POST /api/courses/{courseId}/materials/{materialId}/analyses          분석 실행(draft 생성)
 * GET  /api/courses/{courseId}/materials/{materialId}/analyses          이 프로젝트 맥락의 분석 이력
 * GET  /api/material-analyses/{analysisId}                              단건 조회
 * PUT  /api/material-analyses/{analysisId}                              사용자 검토/수정 저장
 * POST /api/material-analyses/{analysisId}/apply                        확정 반영
 * POST /api/material-analyses/{analysisId}/dismiss                      폐기
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class MaterialAnalysisController {

    private final MaterialAnalysisService materialAnalysisService;

    @PostMapping("/api/courses/{courseId}/materials/{materialId}/analyses")
    public ResponseEntity<MaterialAnalysisResponse> analyze(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long courseId,
            @PathVariable Long materialId
    ) {
        log.info("POST materials/{}/analyses - userId={}, courseId={}", materialId, principal.getUserId(), courseId);
        /*
         * 새로 만들었으면 201, 검토 중이던 DRAFT를 그대로 돌려줬으면 200이다. 본문 형식은
         * 두 경우가 같다 — 화면이 하는 일(검토 폼을 연다)이 같기 때문이다.
         */
        MaterialAnalysisStartResult result =
                materialAnalysisService.analyze(principal.getUserId(), courseId, materialId);
        return ResponseEntity
                .status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.analysis());
    }

    /**
     * 이 프로젝트 맥락의 분석만 돌려준다. 같은 자료가 다른 프로젝트에도 걸려 있으면 그쪽
     * 해석은 여기 나오지 않는다 — 전체 이력은 전역 자료 상세(GET /api/materials/{id})가 맡는다.
     */
    @GetMapping("/api/courses/{courseId}/materials/{materialId}/analyses")
    public ResponseEntity<List<MaterialAnalysisResponse>> listByMaterial(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long courseId,
            @PathVariable Long materialId
    ) {
        return ResponseEntity.ok(
                materialAnalysisService.listByMaterialInCourse(principal.getUserId(), courseId, materialId));
    }

    @GetMapping("/api/material-analyses/{analysisId}")
    public ResponseEntity<MaterialAnalysisResponse> get(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long analysisId
    ) {
        return ResponseEntity.ok(materialAnalysisService.get(principal.getUserId(), analysisId));
    }

    @PutMapping("/api/material-analyses/{analysisId}")
    public ResponseEntity<MaterialAnalysisResponse> edit(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long analysisId,
            @RequestBody MaterialAnalysisEditRequest request
    ) {
        return ResponseEntity.ok(materialAnalysisService.editDraft(principal.getUserId(), analysisId, request.getPayload()));
    }

    @PostMapping("/api/material-analyses/{analysisId}/apply")
    public ResponseEntity<MaterialAnalysisResponse> apply(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long analysisId
    ) {
        log.info("POST material-analyses/{}/apply - userId={}", analysisId, principal.getUserId());
        return ResponseEntity.ok(materialAnalysisService.apply(principal.getUserId(), analysisId));
    }

    @PostMapping("/api/material-analyses/{analysisId}/dismiss")
    public ResponseEntity<MaterialAnalysisResponse> dismiss(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long analysisId
    ) {
        return ResponseEntity.ok(materialAnalysisService.dismiss(principal.getUserId(), analysisId));
    }
}
