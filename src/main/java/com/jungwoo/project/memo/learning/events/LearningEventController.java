package com.jungwoo.project.memo.learning.events;

import com.jungwoo.project.memo.common.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 과목의 살아 있는 학습 이벤트(확인용). 본인 것만, 원천 참조·구조화된 의미만 — 제목·본문은 싣지 않는다.
 */
@RestController
@RequestMapping("/api/courses/{courseId}/learning-events")
@RequiredArgsConstructor
public class LearningEventController {

    private final LearningEventLog log;

    @GetMapping
    public ResponseEntity<List<LearningEventLog.EventView>> live(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long courseId,
            @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return ResponseEntity.ok(log.live(principal.getUserId(), courseId, after, limit));
    }
}
