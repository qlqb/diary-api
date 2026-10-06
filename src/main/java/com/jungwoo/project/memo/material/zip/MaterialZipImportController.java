package com.jungwoo.project.memo.material.zip;

import com.jungwoo.project.memo.common.security.UserPrincipal;
import com.jungwoo.project.memo.material.domain.MaterialType;
import com.jungwoo.project.memo.material.zip.dto.ZipImportConfirmRequest;
import com.jungwoo.project.memo.material.zip.dto.ZipImportResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 압축 파일 가져오기.
 *
 * POST   /api/materials/zip-imports                         zip 올리기 → 내부 목록(자료는 아직 없다)
 * GET    /api/materials/zip-imports                         내 최근 가져오기(새로고침·재접속 복구용)
 * GET    /api/materials/zip-imports/{id}                    한 건의 상태와 항목별 결과
 * POST   /api/materials/zip-imports/{id}/confirm            고른 항목을 대기열에 넣는다(여러 번 눌러도 같다)
 * POST   /api/materials/zip-imports/{id}/entries/{eid}/retry 실패한 항목 하나만 다시
 * DELETE /api/materials/zip-imports/{id}                    아직 시작 안 한 것만 취소(만든 자료는 남는다)
 *
 * <p>모든 경로가 사용자 소유를 확인한다 — 남의 importId·entryId로는 조회도 조작도 되지 않는다.
 * courseId를 주면 그 프로젝트에서 시작한 가져오기라 만들어지는 자료가 그 프로젝트에 연결된다.
 */
@Slf4j
@RestController
@RequestMapping("/api/materials/zip-imports")
@RequiredArgsConstructor
public class MaterialZipImportController {

    private static final int RECENT_LIMIT = 10;

    private final MaterialZipImportService zipImportService;

    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<ZipImportResponse> create(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "courseId", required = false) Long courseId,
            @RequestParam(value = "materialType", required = false) MaterialType materialType
    ) {
        log.info("POST /api/materials/zip-imports - userId={}, filename={}, courseId={}",
                principal.getUserId(), file.getOriginalFilename(), courseId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(zipImportService.create(principal.getUserId(), courseId, materialType, file));
    }

    @GetMapping
    public ResponseEntity<List<ZipImportResponse>> listRecent(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(zipImportService.listRecent(principal.getUserId(), RECENT_LIMIT));
    }

    @GetMapping("/{importId}")
    public ResponseEntity<ZipImportResponse> get(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long importId
    ) {
        return ResponseEntity.ok(zipImportService.get(principal.getUserId(), importId));
    }

    @PostMapping("/{importId}/confirm")
    public ResponseEntity<ZipImportResponse> confirm(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long importId,
            @Valid @RequestBody ZipImportConfirmRequest request
    ) {
        log.info("POST /api/materials/zip-imports/{}/confirm - userId={}, entries={}",
                importId, principal.getUserId(), request.getEntryIds().size());
        return ResponseEntity.ok(zipImportService.confirm(principal.getUserId(), importId, request.getEntryIds()));
    }

    @PostMapping("/{importId}/entries/{entryId}/retry")
    public ResponseEntity<ZipImportResponse> retryEntry(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long importId,
            @PathVariable Long entryId
    ) {
        return ResponseEntity.ok(zipImportService.retryEntry(principal.getUserId(), importId, entryId));
    }

    @DeleteMapping("/{importId}")
    public ResponseEntity<ZipImportResponse> cancel(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long importId
    ) {
        return ResponseEntity.ok(zipImportService.cancel(principal.getUserId(), importId));
    }
}
