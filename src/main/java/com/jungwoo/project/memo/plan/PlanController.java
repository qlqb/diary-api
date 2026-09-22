package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.common.security.UserPrincipal;
import com.jungwoo.project.memo.execution.dto.ExecutionItemResponse;
import com.jungwoo.project.memo.plan.domain.PlanVersion;
import com.jungwoo.project.memo.plan.dto.PlanConfirmRequest;
import com.jungwoo.project.memo.plan.dto.PlanDraftRequest;
import com.jungwoo.project.memo.plan.dto.PlanDraftResponse;
import com.jungwoo.project.memo.plan.dto.PlanPlacementRequest;
import com.jungwoo.project.memo.plan.dto.PlanPlacementResponse;
import com.jungwoo.project.memo.plan.dto.PlanProvenanceResponse;
import com.jungwoo.project.memo.plan.dto.PlanResponse;
import com.jungwoo.project.memo.plan.dto.PlanReviewResponse;
import com.jungwoo.project.memo.plan.provenance.PlanProvenanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
 * 기간 계획 컨트롤러.
 *
 * POST /api/plans/draft                        초안 생성 (아직 아무것도 저장되지 않는다)
 * POST /api/plans/proposals/{proposalId}/confirm  확정 (execution_items + plan_versions)
 * GET  /api/plans?date=&courseId=              그 날짜를 포함하는 계획 목록
 * GET  /api/plans/{planVersionId}              계획 하나
 * GET  /api/plans/{planVersionId}/items         이 계획의 현재 항목 (planKey 기준)
 * GET  /api/plans/{planVersionId}/review        회고
 * POST /api/plans/{planVersionId}/place         롤링 배치 (다가온 창의 시각을 정한다)
 * GET  /api/plans/drafts/{proposalId}/provenance          초안의 생성 정보와 항목별 근거
 * GET  /api/plans/items/{executionItemId}/provenance      적용된 조각의 근거 (같은 응답 모양)
 *
 * ★ 목록은 단건을 반환하지 않는다. 같은 날짜에 8월 계획·이번 주 계획·오늘 계획이 동시에
 * 걸릴 수 있다.
 */
@Slf4j
@RestController
@RequestMapping("/api/plans")
@RequiredArgsConstructor
public class PlanController {

    private final PlanDraftService planDraftService;
    private final PlanConfirmService planConfirmService;
    private final PlanVersionService planVersionService;
    private final PlanReviewService planReviewService;
    private final PlanPlacementService planPlacementService;
    private final PlanProvenanceService planProvenanceService;

    @PostMapping("/draft")
    public ResponseEntity<PlanDraftResponse> createDraft(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody PlanDraftRequest request
    ) {
        log.info("POST /api/plans/draft - userId={}, {}~{}, intensity={}",
                principal.getUserId(), request.getStartDate(), request.getEndDate(), request.getIntensity());

        return ResponseEntity.ok(planDraftService.createDraft(principal.getUserId(), request));
    }

    /**
     * 판단은 그대로 두고 조각만 다시 만든다.
     *
     * <p>사용자가 초안에서 「이미 알아요」로 가정 하나를 고쳤을 때 쓴다. 판단까지 다시 하면
     * 목표와 과목 순서가 함께 흔들려, 무엇 때문에 계획이 바뀌었는지 알 수 없게 된다.
     *
     * <p>새 제안을 만들고 원본은 폐기한다 — 응답의 proposalId가 바뀌므로 화면은 그것을 쓴다.
     */
    @PostMapping("/drafts/{proposalId}/items:regenerate")
    public ResponseEntity<PlanDraftResponse> regenerateItems(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long proposalId
    ) {
        log.info("POST /api/plans/drafts/{}/items:regenerate - userId={}", proposalId, principal.getUserId());

        return ResponseEntity.ok(planDraftService.regenerateItems(principal.getUserId(), proposalId));
    }

    /**
     * 같은 조건으로 초안 다시 만들기(「이번만 빼기」·되돌리기·「이미 알아요」 뒤). 계획 화면·상담 초안 공통.
     * 기간·강도·범위·지시·지정 자료는 서버에 남은 요청을 쓰고, 본문에는 바뀐 제외 목록·지정 자료만 온다.
     */
    @PostMapping("/proposals/{proposalId}/redraft")
    public ResponseEntity<PlanDraftResponse> redraft(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long proposalId,
            @RequestBody(required = false) com.jungwoo.project.memo.plan.dto.PlanRedraftRequest request) {
        return ResponseEntity.ok(planDraftService.redraft(principal.getUserId(), proposalId, request));
    }

    /**
     * 검토 상태 저장(제목·항목 포함/제외·편집값·답). 실행 데이터를 바꾸지 않는다. 열린 초안에만, version이 낡았으면 409.
     */
    @PutMapping("/proposals/{proposalId}/review-state")
    public ResponseEntity<com.jungwoo.project.memo.plan.dto.PlanReviewState> saveReviewState(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long proposalId,
            @RequestBody com.jungwoo.project.memo.plan.dto.PlanReviewState body) {
        return ResponseEntity.ok(planDraftService.saveReviewState(principal.getUserId(), proposalId, body));
    }

    /**
     * 진행 중인 초안 생성의 단계. 화면이 "자료 확인 중 / 계획 정리 중"을 서버가 실제로 밟은 단계로 보여 준다.
     * 모르는 키면 404가 아니라 known=false — 키는 생성이 끝나고 잠시 뒤 사라진다.
     */
    @GetMapping("/draft/progress")
    public ResponseEntity<java.util.Map<String, Object>> draftProgress(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam String requestKey
    ) {
        return ResponseEntity.ok(planDraftService.progressOf(principal.getUserId(), requestKey)
                .<java.util.Map<String, Object>>map(state -> {
                    java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
                    out.put("known", true);
                    out.put("stage", state.stage().name());
                    out.put("label", state.stageLabel());
                    out.put("proposalId", state.proposalId());
                    out.put("errorCode", state.errorCode());
                    return out;
                })
                .orElse(java.util.Map.of("known", false)));
    }

    /**
     * 저장된 초안을 다시 읽는다(새로고침·탭 이동 뒤 복구). 모델을 부르지 않는다.
     */
    @GetMapping("/proposals/{proposalId}/draft")
    public ResponseEntity<PlanDraftResponse> loadDraft(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long proposalId
    ) {
        return ResponseEntity.ok(planDraftService.loadDraft(principal.getUserId(), proposalId));
    }

    @PostMapping("/proposals/{proposalId}/confirm")
    public ResponseEntity<PlanResponse> confirm(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long proposalId,
            @RequestBody PlanConfirmRequest request
    ) {
        log.info("POST /api/plans/proposals/{}/confirm - userId={}", proposalId, principal.getUserId());

        PlanVersion plan = planConfirmService.confirm(principal.getUserId(), proposalId, request);
        return ResponseEntity.ok(PlanResponse.from(plan));
    }

    /**
     * 그 날짜를 포함하는 계획 목록. 기간이 짧은 순 → 최근 확정 순으로 정렬돼 있고,
     * 화면은 첫 번째를 대표로 쓴다(재정렬하지 않는다).
     *
     * courseId를 주면 그 프로젝트 항목을 담은 계획만 남는다. 안 주면 자료구조 화면에
     * 기간이 더 짧은 영어 계획이 대표로 뜬다.
     */
    @GetMapping
    public ResponseEntity<List<PlanResponse>> findCoveringDate(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Long courseId
    ) {
        return ResponseEntity.ok(
                planVersionService.findCoveringDate(principal.getUserId(), date, courseId).stream()
                        .map(PlanResponse::from)
                        .toList());
    }

    @GetMapping("/{planVersionId}")
    public ResponseEntity<PlanResponse> get(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long planVersionId
    ) {
        return ResponseEntity.ok(
                PlanResponse.from(planVersionService.getOwned(principal.getUserId(), planVersionId)));
    }

    /**
     * 이 계획의 현재 항목들.
     *
     * /api/execution-items/range로 기간만 걸러 오면 같은 기간의 다른 계획 항목이 섞인다.
     * 계획 화면은 이 경로를 쓴다.
     */
    @GetMapping("/{planVersionId}/items")
    public ResponseEntity<List<ExecutionItemResponse>> items(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long planVersionId
    ) {
        return ResponseEntity.ok(
                planVersionService.findItems(principal.getUserId(), planVersionId).stream()
                        .map(ExecutionItemResponse::from)
                        .toList());
    }

    /**
     * 이 초안을 만들 때 모델에 무엇을 줬고, 각 항목이 무엇을 근거로 하는가.
     *
     * <p>기록이 없으면 404가 아니라 recorded=false다 — 출처가 없는 것은 오류가 아니라
     * 사실이고, 화면은 그대로 "생성 당시 출처 기록이 없습니다"라고 말한다.
     */
    @GetMapping("/drafts/{proposalId}/provenance")
    public ResponseEntity<PlanProvenanceResponse> draftProvenance(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long proposalId
    ) {
        return ResponseEntity.ok(planProvenanceService.forProposal(principal.getUserId(), proposalId));
    }

    /**
     * 이 초안을 만든 회차에 모델 호출마다 <b>실제로 보낸 것</b>: 줄로 실린 구간·학습 항목 id, 원문이 실린 구간 id,
     * 프로젝트별 집계, 실행 서버 커밋. 개발 검증용이다 — 소유자만 읽고, 기본은 id·집계·해시만 준다.
     * 프롬프트 전문(자료 원문 포함)은 {@code includeText=true}일 때만 준다. 자료를 지웠으면 전문은 이미 없다.
     */
    @GetMapping("/drafts/{proposalId}/trace")
    public ResponseEntity<com.jungwoo.project.memo.plan.trace.PlanGenerationTraceResponse> draftTrace(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long proposalId,
            @RequestParam(defaultValue = "false") boolean includeText
    ) {
        return ResponseEntity.ok(planProvenanceService.traceForProposal(principal.getUserId(), proposalId, includeText));
    }

    /**
     * 적용된 실행 조각의 근거. 계획 화면과 오늘 화면이 같은 응답을 쓴다.
     *
     * <p>같은 사실을 화면마다 다른 모양으로 복제하지 않으려고 초안 경로와 같은 DTO를 쓴다.
     */
    @GetMapping("/items/{executionItemId}/provenance")
    public ResponseEntity<PlanProvenanceResponse> itemProvenance(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long executionItemId
    ) {
        return ResponseEntity.ok(
                planProvenanceService.forExecutionItem(principal.getUserId(), executionItemId));
    }

    @GetMapping("/{planVersionId}/review")
    public ResponseEntity<PlanReviewResponse> review(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long planVersionId
    ) {
        return ResponseEntity.ok(planReviewService.review(principal.getUserId(), planVersionId));
    }

    /**
     * 롤링 배치. 다가온 창(최대 7일)의 미배치 항목에 시각을 정한다.
     *
     * 결과는 사용자 확인 없이 바로 적용된다 — 시각 부여는 되돌리기 싼 조작이고
     * unschedule로 즉시 복구할 수 있다.
     */
    @PostMapping("/{planVersionId}/place")
    public ResponseEntity<PlanPlacementResponse> place(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long planVersionId,
            @RequestBody(required = false) PlanPlacementRequest request
    ) {
        LocalDate windowStart = request != null ? request.getWindowStart() : null;
        log.info("POST /api/plans/{}/place - userId={}, windowStart={}",
                planVersionId, principal.getUserId(), windowStart);

        return ResponseEntity.ok(
                planPlacementService.place(principal.getUserId(), planVersionId, windowStart));
    }
}
