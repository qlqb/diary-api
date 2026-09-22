package com.jungwoo.project.memo.learning.tidy;

import com.jungwoo.project.memo.common.security.UserPrincipal;
import com.jungwoo.project.memo.learning.tidy.dto.ProjectTidyRequests;
import com.jungwoo.project.memo.learning.tidy.dto.ProjectTidyResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 프로젝트 단위 자료 정리.
 *
 * <pre>
 * GET    /api/courses/{id}/tidy                   지금 상태(정리안·생성 중·쓸 수 있는 자료 수)
 * POST   /api/courses/{id}/tidy                   [이 프로젝트 자료 정리] — 작업을 만든다
 * POST   /api/courses/{id}/tidy?refresh=true      [새 자료 반영해 다시 정리]
 * PUT    /api/project-tidy/{proposalId}/edits     검토 중 고친 것 저장(자동 저장). 트리는 안 바뀐다
 * POST   /api/project-tidy/{proposalId}/apply     [선택한 변경 적용]
 * POST   /api/courses/{id}/tidy/dismiss           [버리기]
 * GET    /api/courses/{id}/tidy/history           지난 정리안
 * </pre>
 *
 * <p>[나중에]에 해당하는 경로는 없다 — 아무것도 하지 않고 화면을 옮기는 것이 곧 "나중에"이고,
 * 검토안은 서버에 그대로 남아 다시 들어오면 복원된다.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ProjectTidyController {

    private final ProjectTidyService tidyService;

    @GetMapping("/api/courses/{courseId}/tidy")
    public ResponseEntity<ProjectTidyResponse> view(@AuthenticationPrincipal UserPrincipal principal,
                                                    @PathVariable Long courseId) {
        return ResponseEntity.ok(tidyService.view(principal.getUserId(), courseId));
    }

    @PostMapping("/api/courses/{courseId}/tidy")
    public ResponseEntity<ProjectTidyResponse> request(@AuthenticationPrincipal UserPrincipal principal,
                                                       @PathVariable Long courseId,
                                                       @RequestParam(name = "refresh", defaultValue = "false")
                                                       boolean refresh) {
        log.info("POST /api/courses/{}/tidy - userId={}, refresh={}", courseId, principal.getUserId(), refresh);
        return ResponseEntity.ok(tidyService.request(principal.getUserId(), courseId, refresh));
    }

    @PutMapping("/api/project-tidy/{proposalId}/edits")
    public ResponseEntity<ProjectTidyResponse> saveEdits(@AuthenticationPrincipal UserPrincipal principal,
                                                         @PathVariable Long proposalId,
                                                         @Valid @RequestBody ProjectTidyRequests.SaveEdits request) {
        return ResponseEntity.ok(tidyService.saveEdits(principal.getUserId(), proposalId, request));
    }

    @PostMapping("/api/project-tidy/{proposalId}/apply")
    public ResponseEntity<ProjectTidyResponse> apply(@AuthenticationPrincipal UserPrincipal principal,
                                                     @PathVariable Long proposalId,
                                                     @Valid @RequestBody ProjectTidyRequests.Apply request) {
        log.info("POST /api/project-tidy/{}/apply - userId={}, 고른 변경={}",
                proposalId, principal.getUserId(),
                request.getSelectedChangeIds() == null ? 0 : request.getSelectedChangeIds().size());
        return ResponseEntity.ok(tidyService.apply(principal.getUserId(), proposalId, request));
    }

    /**
     * 실패한 정리를 요청 때의 입력 그대로 다시 한다. 새 자료를 반영하려면 POST /tidy?refresh=true.
     */
    @PostMapping("/api/courses/{courseId}/tidy/retry")
    public ResponseEntity<ProjectTidyResponse> retry(@AuthenticationPrincipal UserPrincipal principal,
                                                     @PathVariable Long courseId) {
        return ResponseEntity.ok(tidyService.retry(principal.getUserId(), courseId));
    }

    @PostMapping("/api/courses/{courseId}/tidy/dismiss")
    public ResponseEntity<ProjectTidyResponse> dismiss(@AuthenticationPrincipal UserPrincipal principal,
                                                       @PathVariable Long courseId) {
        log.info("POST /api/courses/{}/tidy/dismiss - userId={}", courseId, principal.getUserId());
        return ResponseEntity.ok(tidyService.dismiss(principal.getUserId(), courseId));
    }

    @GetMapping("/api/courses/{courseId}/tidy/history")
    public ResponseEntity<List<ProjectTidyResponse>> history(@AuthenticationPrincipal UserPrincipal principal,
                                                             @PathVariable Long courseId) {
        return ResponseEntity.ok(tidyService.history(principal.getUserId(), courseId));
    }
}
