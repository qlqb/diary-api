package com.jungwoo.project.memo.ai.change;

import com.jungwoo.project.memo.common.security.UserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 변경 묶음의 검토·적용.
 *
 * <pre>
 * GET  /api/ai/change-bundles/{id}                       묶음(카드 복원)
 * GET  /api/ai/conversations/{conversationId}/change-bundles   대화의 묶음 전부(재접속 복원)
 * PUT  /api/ai/change-bundles/{id}/selection             항목 선택 바꾸기(리비전 증가)
 * POST /api/ai/change-bundles/{id}/refresh               다시 검토(최신 상태로 다시 확인)
 * POST /api/ai/change-bundles/{id}/apply                 적용(기대 리비전·선택·멱등 키)
 * POST /api/ai/change-bundles/{id}/dismiss               적용하지 않고 닫기
 * </pre>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ChangeBundleController {

    private final ChangeBundleService service;

    public record SelectionRequest(@NotNull Integer expectedRevision, @NotNull List<Long> selectedItemIds) {
    }

    public record RevisionRequest(@NotNull Integer expectedRevision) {
    }

    public record ApplyRequest(@NotNull Integer expectedRevision, @NotNull List<Long> selectedItemIds,
                               @NotBlank @Size(max = 100) String idempotencyKey) {
    }

    @GetMapping("/api/ai/change-bundles/{bundleId}")
    public ResponseEntity<ChangeBundleView> get(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long bundleId) {
        return ResponseEntity.ok(service.view(principal.getUserId(), bundleId));
    }

    @GetMapping("/api/ai/conversations/{conversationId}/change-bundles")
    public ResponseEntity<List<ChangeBundleView>> byConversation(@AuthenticationPrincipal UserPrincipal principal,
                                                                 @PathVariable Long conversationId) {
        return ResponseEntity.ok(service.listByConversation(principal.getUserId(), conversationId));
    }

    @PutMapping("/api/ai/change-bundles/{bundleId}/selection")
    public ResponseEntity<ChangeBundleView> select(@AuthenticationPrincipal UserPrincipal principal,
                                                   @PathVariable Long bundleId,
                                                   @Valid @RequestBody SelectionRequest request) {
        return ResponseEntity.ok(service.select(principal.getUserId(), bundleId, request.expectedRevision(),
                request.selectedItemIds()));
    }

    @PostMapping("/api/ai/change-bundles/{bundleId}/refresh")
    public ResponseEntity<ChangeBundleView> refresh(@AuthenticationPrincipal UserPrincipal principal,
                                                    @PathVariable Long bundleId,
                                                    @Valid @RequestBody RevisionRequest request) {
        return ResponseEntity.ok(service.refresh(principal.getUserId(), bundleId, request.expectedRevision()));
    }

    @PostMapping("/api/ai/change-bundles/{bundleId}/apply")
    public ResponseEntity<ChangeBundleView> apply(@AuthenticationPrincipal UserPrincipal principal,
                                                  @PathVariable Long bundleId,
                                                  @Valid @RequestBody ApplyRequest request) {
        log.info("POST /api/ai/change-bundles/{}/apply - userId={}, revision={}, 선택={}개", bundleId, principal.getUserId(),
                request.expectedRevision(), request.selectedItemIds().size());
        return ResponseEntity.ok(service.apply(principal.getUserId(), bundleId, new ChangeBundleService.ApplyCommand(
                request.expectedRevision(), request.selectedItemIds(), request.idempotencyKey())));
    }

    @PostMapping("/api/ai/change-bundles/{bundleId}/dismiss")
    public ResponseEntity<ChangeBundleView> dismiss(@AuthenticationPrincipal UserPrincipal principal,
                                                    @PathVariable Long bundleId) {
        return ResponseEntity.ok(service.dismiss(principal.getUserId(), bundleId));
    }
}
