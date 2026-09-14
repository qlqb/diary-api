package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.dto.AiProposalApplyRequest;
import com.jungwoo.project.memo.ai.dto.AiProposalResponse;
import com.jungwoo.project.memo.common.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * AI 제안(Proposal) 조회·적용 컨트롤러.
 *
 * 생성은 여기 없다 — Proposal은 AiConversationController의 대화 흐름에서 사용자가
 * 명시적으로 요청했거나 OFFER에 동의했을 때만 만들어진다 (POST /api/ai/proposals 같은
 * 강제 생성 엔드포인트를 두지 않는다).
 *
 * /api/** 는 SecurityConfig에서 기본적으로 인증이 필요하다 (permitAll로 열지 않는다).
 * 상담 원문, AI 원문 응답, JWT는 로그에 남기지 않는다.
 */
@Slf4j
@RestController
@RequestMapping("/api/ai/proposals")
@RequiredArgsConstructor
public class AiProposalController {

    private final AiProposalService aiProposalService;

    @GetMapping("/{proposalId}")
    public ResponseEntity<AiProposalResponse> get(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long proposalId
    ) {
        log.info("GET /api/ai/proposals/{} - userId={}", proposalId, principal.getUserId());

        return ResponseEntity.ok(aiProposalService.get(proposalId, principal.getUserId()));
    }

    @PostMapping("/{proposalId}/apply")
    public ResponseEntity<AiProposalResponse> apply(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long proposalId,
            @RequestBody(required = false) AiProposalApplyRequest request
    ) {
        log.info("POST /api/ai/proposals/{}/apply - userId={}", proposalId, principal.getUserId());

        AiProposalApplyRequest safeRequest = request != null ? request : new AiProposalApplyRequest();

        return ResponseEntity.ok(
                aiProposalService.apply(proposalId, principal.getUserId(), safeRequest));
    }
}
