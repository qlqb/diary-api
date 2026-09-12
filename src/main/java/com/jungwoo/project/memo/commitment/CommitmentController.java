package com.jungwoo.project.memo.commitment;

import com.jungwoo.project.memo.commitment.domain.CommitmentSourceType;
import com.jungwoo.project.memo.commitment.dto.CommitmentCreateRequest;
import com.jungwoo.project.memo.commitment.dto.CommitmentResponse;
import com.jungwoo.project.memo.commitment.dto.CommitmentUpdateRequest;
import com.jungwoo.project.memo.common.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 일회성 약속 컨트롤러.
 *
 * <pre>
 * GET    /api/commitments?from=&amp;to=       기간과 겹치는 약속
 * POST   /api/commitments                   만들기
 * PUT    /api/commitments/{id}              고치기 (전체 교체)
 * DELETE /api/commitments/{id}?version=N    소프트 삭제
 * </pre>
 *
 * <p>완료·일부 같은 엔드포인트가 없다. 약속은 수행 대상이 아니다.
 *
 * <p>생성 요청에 sourceType이 없는 것이 의도다 — 여기로 들어온 것은 전부 MANUAL이고,
 * AI 후보 승인은 이 컨트롤러가 아니라 승인 경로가 서비스를 직접 부른다.
 */
@Slf4j
@RestController
@RequestMapping("/api/commitments")
@RequiredArgsConstructor
public class CommitmentController {

    private final CommitmentService commitmentService;

    @GetMapping
    public ResponseEntity<List<CommitmentResponse>> list(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return ResponseEntity.ok(commitmentService.list(principal.getUserId(), from, to));
    }

    @PostMapping
    public ResponseEntity<CommitmentResponse> create(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CommitmentCreateRequest request
    ) {
        log.info("POST /api/commitments - userId={}, title={}", principal.getUserId(), request.getTitle());
        return ResponseEntity.status(HttpStatus.CREATED).body(
                commitmentService.create(principal.getUserId(), request, CommitmentSourceType.MANUAL));
    }

    @PutMapping("/{commitmentId}")
    public ResponseEntity<CommitmentResponse> update(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long commitmentId,
            @Valid @RequestBody CommitmentUpdateRequest request
    ) {
        return ResponseEntity.ok(
                commitmentService.update(principal.getUserId(), commitmentId, request));
    }

    @DeleteMapping("/{commitmentId}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long commitmentId,
            @RequestParam Long version
    ) {
        commitmentService.delete(principal.getUserId(), commitmentId, version);
        return ResponseEntity.noContent().build();
    }
}
