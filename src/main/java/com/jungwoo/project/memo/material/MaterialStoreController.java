package com.jungwoo.project.memo.material;

import com.jungwoo.project.memo.common.security.UserPrincipal;
import com.jungwoo.project.memo.material.dto.MaterialDetailResponse;
import com.jungwoo.project.memo.material.dto.MaterialLinkCreateRequest;
import com.jungwoo.project.memo.material.dto.MaterialLinkResponse;
import com.jungwoo.project.memo.material.dto.MaterialResponse;
import com.jungwoo.project.memo.material.dto.MaterialStoreItemResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 전역 자료함. 자료는 프로젝트가 아니라 사용자가 소유한다.
 *
 * GET    /api/materials                        내 전체 자료 + 각 자료에 연결된 프로젝트
 * POST   /api/materials                        프로젝트 없이 업로드 (materialType을 받지 않는다)
 * GET    /api/materials/{id}                   단건 + 연결 목록 + 분석 이력
 * DELETE /api/materials/{id}                   자료 삭제 (원본 파일까지)
 * POST   /api/materials/{id}/links             프로젝트에 연결. 이때 materialType이 정해진다
 * DELETE /api/materials/{id}/links/{courseId}  연결 해제만 (자료는 남는다)
 *
 * 프로젝트 화면의 업로드/목록은 기존 /api/courses/{courseId}/materials가 그대로 담당한다.
 */
@Slf4j
@RestController
@RequestMapping("/api/materials")
@RequiredArgsConstructor
public class MaterialStoreController {

    private final MaterialService materialService;
    private final MaterialAnalysisService materialAnalysisService;

    @GetMapping
    public ResponseEntity<List<MaterialStoreItemResponse>> list(
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(materialService.listAll(principal.getUserId()));
    }

    /**
     * 프로젝트 없이 업로드한다. materialType을 받지 않는 것이 의도다 — 자료의 성격은
     * "이 프로젝트가 이걸 무엇으로 쓰는가"라서 연결 시점에 정해진다. 그때까지 타입이
     * 없는 것이 정상이고, 그 상태를 결함처럼 표시하지 않는다.
     */
    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<MaterialResponse> upload(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam("file") MultipartFile file
    ) {
        log.info("POST /api/materials - userId={}, filename={}",
                principal.getUserId(), file.getOriginalFilename());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(materialService.upload(principal.getUserId(), null, null, file));
    }

    @GetMapping("/{materialId}")
    public ResponseEntity<MaterialDetailResponse> get(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long materialId
    ) {
        Long userId = principal.getUserId();
        return ResponseEntity.ok(MaterialDetailResponse.builder()
                .material(materialService.getStoreItem(userId, materialId))
                .analyses(materialAnalysisService.listByMaterial(userId, materialId))
                .build());
    }

    /** 원본 파일까지 지운다. 연결 해제와는 다른 액션이다. */
    @DeleteMapping("/{materialId}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long materialId
    ) {
        log.info("DELETE /api/materials/{} - userId={}", materialId, principal.getUserId());
        materialService.delete(principal.getUserId(), materialId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{materialId}/links")
    public ResponseEntity<MaterialLinkResponse> addLink(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long materialId,
            @Valid @RequestBody MaterialLinkCreateRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(materialService.addLink(
                principal.getUserId(), materialId, request.getCourseId(), request.getMaterialType()));
    }

    /** 연결만 끊는다. 자료도, 다른 프로젝트 연결도, 이미 적용한 학습 내용도 그대로 남는다. */
    @DeleteMapping("/{materialId}/links/{courseId}")
    public ResponseEntity<Void> removeLink(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long materialId,
            @PathVariable Long courseId
    ) {
        materialService.removeLink(principal.getUserId(), materialId, courseId);
        return ResponseEntity.noContent().build();
    }
}
