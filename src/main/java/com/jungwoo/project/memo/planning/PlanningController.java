package com.jungwoo.project.memo.planning;

import com.jungwoo.project.memo.ai.dto.AiProposalApplyRequest;
import com.jungwoo.project.memo.ai.dto.AiProposalResponse;
import com.jungwoo.project.memo.common.security.UserPrincipal;
import com.jungwoo.project.memo.planning.dto.PlanningDraftRequest;
import com.jungwoo.project.memo.planning.dto.PlanningDraftResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Planning Agent 컨트롤러.
 *
 * POST /api/learning/recommendations/{recommendationId}/planning-draft   계획 초안 생성
 *      (기존 AiProposal + 7일 범위 스케줄 프리뷰를 재사용해 만든다. Apply 전에는
 *      ExecutionItem이 전혀 생기지 않는다 — draft 자체는 ai_proposals/미리보기 저장일 뿐)
 * POST /api/planning/proposals/{proposalId}/apply                        계획 확정 적용
 *      (기존 AiProposalService.apply()를 그대로 쓰고, 생성된 실행 조각에 topic만 연결한다)
 *
 * 계획 초안을 검토/수정하거나 재배치하는 동안에는 기존 proposalAPI(GET)/schedulePreviewAPI
 * (GET/POST) 엔드포인트를 그대로 쓴다 — 여기서 새로 만들지 않는다.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class PlanningController {

    private final PlanningAgentService planningAgentService;

    @PostMapping("/api/learning/recommendations/{recommendationId}/planning-draft")
    public ResponseEntity<PlanningDraftResponse> createDraft(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long recommendationId,
            @RequestBody(required = false) PlanningDraftRequest request
    ) {
        String instruction = request != null ? request.getInstruction() : null;
        log.info("POST learning/recommendations/{}/planning-draft - userId={}", recommendationId, principal.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(planningAgentService.createDraft(principal.getUserId(), recommendationId, instruction));
    }

    @PostMapping("/api/planning/proposals/{proposalId}/apply")
    public ResponseEntity<AiProposalResponse> apply(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long proposalId,
            @RequestBody AiProposalApplyRequest request
    ) {
        log.info("POST planning/proposals/{}/apply - userId={}", proposalId, principal.getUserId());
        return ResponseEntity.ok(planningAgentService.apply(principal.getUserId(), proposalId, request));
    }
}
