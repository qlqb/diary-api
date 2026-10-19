package com.jungwoo.project.memo.plan.help;

import com.jungwoo.project.memo.common.security.UserPrincipal;
import com.jungwoo.project.memo.plan.PlanItemDetailService;
import com.jungwoo.project.memo.plan.dto.PlanItemDetailResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 학습 실행 작업 공간 · 시작 도움 · 메모.
 *
 * <p>작업 공간 조회는 모델을 부르지 않는다. 시작 도움 POST만 모델을 한 번 부르며, 항목의 범위·시간을 바꾸지 않는다.
 */
@RestController
@RequiredArgsConstructor
public class ExecutionWorkspaceController {

    private final ExecutionWorkspaceService workspaceService;
    private final StartHelpService startHelpService;
    private final PlanItemDetailService detailService;

    public record StartHelpRequest(String kind, String text) {
    }

    public record MemoRequest(String userText) {
    }

    @GetMapping("/api/execution-items/{executionItemId}/workspace")
    public ResponseEntity<ExecutionWorkspaceService.ExecutionWorkspaceResponse> workspace(
            @AuthenticationPrincipal UserPrincipal principal, @PathVariable Long executionItemId) {
        return ResponseEntity.ok(workspaceService.get(principal.getUserId(), executionItemId));
    }

    @PostMapping("/api/execution-items/{executionItemId}/start-help")
    public ResponseEntity<StartHelpResponse> startHelp(@AuthenticationPrincipal UserPrincipal principal,
                                                       @PathVariable Long executionItemId,
                                                       @RequestBody(required = false) StartHelpRequest request) {
        return ResponseEntity.ok(startHelpService.request(principal.getUserId(), executionItemId,
                request == null ? null : request.kind(), request == null ? null : request.text()));
    }

    @GetMapping("/api/plans/drafts/items/{proposalItemId}/start-help")
    public ResponseEntity<StartHelpResponse> draftStartHelp(@AuthenticationPrincipal UserPrincipal principal,
                                                            @PathVariable Long proposalItemId) {
        StartHelpResponse help = startHelpService.latestForProposalItem(principal.getUserId(), proposalItemId);
        return help == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(help);
    }

    @PostMapping("/api/plans/drafts/items/{proposalItemId}/start-help")
    public ResponseEntity<StartHelpResponse> requestDraftStartHelp(@AuthenticationPrincipal UserPrincipal principal,
                                                                   @PathVariable Long proposalItemId,
                                                                   @RequestBody(required = false) StartHelpRequest request) {
        return ResponseEntity.ok(startHelpService.requestForProposalItem(principal.getUserId(), proposalItemId,
                request == null ? null : request.kind(), request == null ? null : request.text()));
    }

    /** 확정 항목의 메모. 안내 단계가 없어도 남길 수 있다 — 초안에서 남긴 메모와 같은 자리다. */
    @PutMapping("/api/plans/items/{executionItemId}/memo")
    public ResponseEntity<PlanItemDetailResponse> itemMemo(@AuthenticationPrincipal UserPrincipal principal,
                                                           @PathVariable Long executionItemId,
                                                           @RequestBody MemoRequest request) {
        return ResponseEntity.ok(detailService.saveMemoForExecutionItem(principal.getUserId(), executionItemId,
                request == null ? null : request.userText()));
    }

    @PutMapping("/api/plans/drafts/items/{proposalItemId}/memo")
    public ResponseEntity<PlanItemDetailResponse> draftMemo(@AuthenticationPrincipal UserPrincipal principal,
                                                            @PathVariable Long proposalItemId,
                                                            @RequestBody MemoRequest request) {
        return ResponseEntity.ok(detailService.saveMemoForProposalItem(principal.getUserId(), proposalItemId,
                request == null ? null : request.userText()));
    }
}
