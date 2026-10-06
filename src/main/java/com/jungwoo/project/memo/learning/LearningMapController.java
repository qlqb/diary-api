package com.jungwoo.project.memo.learning;

import com.jungwoo.project.memo.ai.UserContextService;
import com.jungwoo.project.memo.common.security.UserPrincipal;
import com.jungwoo.project.memo.learning.dto.LearningMapResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 프로젝트 전체 학습 지도와, 지도 안의 선택 활동(점검).
 *
 * <p>둘 다 계획·일정을 바꾸지 않는다. 지도는 읽기 전용이고, 점검은 자기평가를 "AI가 이해한 내 상황"에 남길 뿐이다 —
 * 학습 완료나 숙달로 기록하지 않는다.
 */
@Slf4j
@RestController
@RequestMapping("/api/courses/{courseId}")
@RequiredArgsConstructor
public class LearningMapController {

    private final LearningMapService learningMapService;
    private final UserContextService userContextService;

    /** @param level KNOW(알아) / UNSURE(애매해) / NEW(처음 봐) */
    public record SelfCheckItem(String key, String label, Long topicId, Long sectionId, String level, String note) {
    }

    public record SelfCheckRequest(List<SelfCheckItem> items) {
    }

    @GetMapping("/learning-map")
    public ResponseEntity<LearningMapResponse> learningMap(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long courseId
    ) {
        return ResponseEntity.ok(learningMapService.map(principal.getUserId(), courseId));
    }

    /**
     * 점검 활동의 결과. 선택이다 — 하지 않아도 상담과 계획은 된다. 저장된 자기평가는 다음 상담·계획 입력에 들어간다.
     */
    @PostMapping("/self-checks")
    public ResponseEntity<Void> selfChecks(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long courseId,
            @RequestBody SelfCheckRequest request
    ) {
        List<UserContextService.SelfCheck> items = request == null || request.items() == null ? List.of()
                : request.items().stream().map(i -> new UserContextService.SelfCheck(i.label(), i.topicId(),
                i.sectionId(), i.level(), i.note())).toList();
        int saved = userContextService.saveSelfChecks(principal.getUserId(), courseId, items);
        log.info("POST /api/courses/{}/self-checks - userId={}, 저장={}건", courseId, principal.getUserId(), saved);
        return ResponseEntity.noContent().build();
    }
}
