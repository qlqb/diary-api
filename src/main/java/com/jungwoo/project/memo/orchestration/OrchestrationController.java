package com.jungwoo.project.memo.orchestration;

import com.jungwoo.project.memo.common.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * "다음 학습 추천 + 그 학습을 바로 계획까지" 같은 복합 요청 전용 진입점.
 * 개별 단계(추천만, 계획만)는 learning/planning 각자의 엔드포인트를 그대로 쓴다 —
 * 이 컨트롤러는 그 둘을 정해진 순서로 묶어 부르는 편의 진입점일 뿐이다.
 */
@Slf4j
@RestController
@RequestMapping("/api/courses/{courseId}/learning")
@RequiredArgsConstructor
public class OrchestrationController {

    private final LearningWorkflowOrchestrator orchestrator;

    @PostMapping("/recommend-and-plan")
    public ResponseEntity<LearningWorkflowOrchestrator.RecommendAndPlanResult> recommendAndPlan(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long courseId,
            @RequestBody(required = false) RecommendAndPlanRequest request
    ) {
        String instruction = request != null ? request.getPlanningInstruction() : null;
        log.info("POST courses/{}/learning/recommend-and-plan - userId={}", courseId, principal.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(orchestrator.recommendAndPlan(principal.getUserId(), courseId, instruction));
    }
}
