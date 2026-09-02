package com.jungwoo.project.memo.learning;

import com.jungwoo.project.memo.common.security.UserPrincipal;
import com.jungwoo.project.memo.learning.domain.TopicProgress;
import com.jungwoo.project.memo.learning.dto.TopicProgressUpdateRequest;
import com.jungwoo.project.memo.learning.dto.TopicResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * GET   /api/courses/{courseId}/topics       확정된 topic 트리 (진행 상태 포함)
 * PATCH /api/topics/{topicId}/progress       사용자가 직접 학습 상태를 바꾸는 액션
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class TopicController {

    private final TopicService topicService;

    @GetMapping("/api/courses/{courseId}/topics")
    public ResponseEntity<List<TopicResponse>> getTree(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long courseId
    ) {
        return ResponseEntity.ok(topicService.getTopicTree(principal.getUserId(), courseId));
    }

    @PatchMapping("/api/topics/{topicId}/progress")
    public ResponseEntity<TopicProgress> updateProgress(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long topicId,
            @Valid @RequestBody TopicProgressUpdateRequest request
    ) {
        log.info("PATCH /api/topics/{}/progress - userId={}, status={}",
                topicId, principal.getUserId(), request.getStatus());
        return ResponseEntity.ok(topicService.updateProgressStatus(principal.getUserId(), topicId, request.getStatus()));
    }
}
