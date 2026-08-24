package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.common.security.UserPrincipal;
import com.jungwoo.project.memo.plan.domain.PlanVersion;
import com.jungwoo.project.memo.plan.dto.PlanConfirmRequest;
import com.jungwoo.project.memo.plan.dto.PlanDraftRequest;
import com.jungwoo.project.memo.plan.dto.PlanDraftResponse;
import com.jungwoo.project.memo.plan.dto.PlanPlacementRequest;
import com.jungwoo.project.memo.plan.dto.PlanPlacementResponse;
import com.jungwoo.project.memo.plan.dto.PlanResponse;
import com.jungwoo.project.memo.plan.dto.PlanReviewResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
 * GET  /api/plans/{planVersionId}/review        회고
 * POST /api/plans/{planVersionId}/place         롤링 배치 (다가온 창의 시각을 정한다)
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

    @PostMapping("/draft")
    public ResponseEntity<PlanDraftResponse> createDraft(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody PlanDraftRequest request
    ) {
        log.info("POST /api/plans/draft - userId={}, {}~{}, intensity={}",
                principal.getUserId(), request.getStartDate(), request.getEndDate(), request.getIntensity());

        return ResponseEntity.ok(planDraftService.createDraft(principal.getUserId(), request));
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
