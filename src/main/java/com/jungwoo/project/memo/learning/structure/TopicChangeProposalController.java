package com.jungwoo.project.memo.learning.structure;

import com.jungwoo.project.memo.common.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 자료 정리 변경안.
 *
 * GET  /api/courses/{courseId}/topic-change-proposals?includeResolved=false
 * GET  /api/topic-change-proposals/{id}
 * POST /api/topic-change-proposals/{id}/apply     body: {selectedOpIndexes?, titleOverrides?}
 * POST /api/topic-change-proposals/{id}/dismiss
 */
@RestController
@RequiredArgsConstructor
public class TopicChangeProposalController {

    private final TopicChangeProposalService proposalService;

    @GetMapping("/api/courses/{courseId}/topic-change-proposals")
    public ResponseEntity<List<TopicChangeProposalResponse>> list(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long courseId,
            @RequestParam(name = "includeResolved", defaultValue = "false") boolean includeResolved) {
        return ResponseEntity.ok(proposalService.listByCourse(principal.getUserId(), courseId, includeResolved));
    }

    @GetMapping("/api/topic-change-proposals/{proposalId}")
    public ResponseEntity<TopicChangeProposalResponse> get(@AuthenticationPrincipal UserPrincipal principal,
                                                           @PathVariable Long proposalId) {
        return ResponseEntity.ok(proposalService.get(principal.getUserId(), proposalId));
    }

    @PostMapping("/api/topic-change-proposals/{proposalId}/apply")
    public ResponseEntity<TopicChangeProposalResponse> apply(@AuthenticationPrincipal UserPrincipal principal,
                                                             @PathVariable Long proposalId,
                                                             @RequestBody(required = false) TopicChangeApplyRequest request) {
        return ResponseEntity.ok(proposalService.apply(principal.getUserId(), proposalId, request));
    }

    @PostMapping("/api/topic-change-proposals/{proposalId}/dismiss")
    public ResponseEntity<TopicChangeProposalResponse> dismiss(@AuthenticationPrincipal UserPrincipal principal,
                                                               @PathVariable Long proposalId) {
        return ResponseEntity.ok(proposalService.dismiss(principal.getUserId(), proposalId));
    }
}
