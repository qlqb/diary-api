package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.common.security.UserPrincipal;
import com.jungwoo.project.memo.plan.dto.PlanItemDetailResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 계획 항목의 「자세히」.
 *
 * GET   /api/plans/drafts/items/{proposalItemId}/detail     있으면 돌려주고 없으면 available=false
 * POST  /api/plans/drafts/items/{proposalItemId}/detail     없으면 만든다(모델 1회). 항목·시간·마감은 그대로다
 * GET   /api/plans/items/{executionItemId}/detail           확정된 조각(만든 제안 항목의 안내)
 * POST  /api/plans/items/{executionItemId}/detail
 * PATCH /api/plans/item-details/{detailId}                   body: {userText} — 사용자가 고친 안내
 */
@RestController
@RequestMapping("/api/plans")
@RequiredArgsConstructor
public class PlanItemDetailController {

    private final PlanItemDetailService detailService;

    @GetMapping("/drafts/items/{proposalItemId}/detail")
    public ResponseEntity<PlanItemDetailResponse> getDraftDetail(@AuthenticationPrincipal UserPrincipal principal,
                                                                 @PathVariable Long proposalItemId) {
        return ResponseEntity.ok(detailService.forProposalItem(principal.getUserId(), proposalItemId, false));
    }

    @PostMapping("/drafts/items/{proposalItemId}/detail")
    public ResponseEntity<PlanItemDetailResponse> createDraftDetail(@AuthenticationPrincipal UserPrincipal principal,
                                                                    @PathVariable Long proposalItemId) {
        return ResponseEntity.ok(detailService.forProposalItem(principal.getUserId(), proposalItemId, true));
    }

    @GetMapping("/items/{executionItemId}/detail")
    public ResponseEntity<PlanItemDetailResponse> getItemDetail(@AuthenticationPrincipal UserPrincipal principal,
                                                                @PathVariable Long executionItemId) {
        return ResponseEntity.ok(detailService.forExecutionItem(principal.getUserId(), executionItemId, false));
    }

    @PostMapping("/items/{executionItemId}/detail")
    public ResponseEntity<PlanItemDetailResponse> createItemDetail(@AuthenticationPrincipal UserPrincipal principal,
                                                                   @PathVariable Long executionItemId) {
        return ResponseEntity.ok(detailService.forExecutionItem(principal.getUserId(), executionItemId, true));
    }

    @PatchMapping("/item-details/{detailId}")
    public ResponseEntity<PlanItemDetailResponse> updateUserText(@AuthenticationPrincipal UserPrincipal principal,
                                                                 @PathVariable Long detailId,
                                                                 @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(detailService.updateUserText(principal.getUserId(), detailId, body.get("userText")));
    }
}
