package com.jungwoo.project.memo.learning;

import com.jungwoo.project.memo.common.security.UserPrincipal;
import com.jungwoo.project.memo.learning.dto.StudyRecommendationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * POST /api/courses/{courseId}/learning/recommendation      다음 학습 추천 받기
 * GET  /api/learning/recommendations/{id}                   추천 단건 조회
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class LearningRecommendationController {

    private final LearningRecommendationService learningRecommendationService;

    @PostMapping("/api/courses/{courseId}/learning/recommendation")
    public ResponseEntity<StudyRecommendationResponse> recommend(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long courseId
    ) {
        log.info("POST courses/{}/learning/recommendation - userId={}", courseId, principal.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(learningRecommendationService.recommend(principal.getUserId(), courseId));
    }

    @GetMapping("/api/learning/recommendations/{recommendationId}")
    public ResponseEntity<StudyRecommendationResponse> get(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long recommendationId
    ) {
        return ResponseEntity.ok(learningRecommendationService.get(principal.getUserId(), recommendationId));
    }
}
