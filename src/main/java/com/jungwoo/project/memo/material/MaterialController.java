package com.jungwoo.project.memo.material;

import com.jungwoo.project.memo.common.security.UserPrincipal;
import com.jungwoo.project.memo.material.domain.MaterialType;
import com.jungwoo.project.memo.material.dto.MaterialResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 학습 자료 업로드/조회 컨트롤러.
 *
 * POST /api/courses/{courseId}/materials  업로드 (multipart, materialType 파라미터)
 * GET  /api/courses/{courseId}/materials  목록
 */
@Slf4j
@RestController
@RequestMapping("/api/courses/{courseId}/materials")
@RequiredArgsConstructor
public class MaterialController {

    private final MaterialService materialService;

    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<MaterialResponse> upload(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long courseId,
            @RequestParam MaterialType materialType,
            @RequestParam("file") MultipartFile file
    ) {
        log.info("POST /api/courses/{}/materials - userId={}, type={}, filename={}",
                courseId, principal.getUserId(), materialType, file.getOriginalFilename());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(materialService.upload(principal.getUserId(), courseId, materialType, file));
    }

    @GetMapping
    public ResponseEntity<List<MaterialResponse>> list(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long courseId
    ) {
        return ResponseEntity.ok(materialService.listByCourse(principal.getUserId(), courseId));
    }
}
