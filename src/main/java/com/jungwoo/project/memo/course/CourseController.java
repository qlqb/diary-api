package com.jungwoo.project.memo.course;

import com.jungwoo.project.memo.common.security.UserPrincipal;
import com.jungwoo.project.memo.course.domain.CourseStatus;
import com.jungwoo.project.memo.course.dto.CourseCreateRequest;
import com.jungwoo.project.memo.course.dto.CourseNoteResponse;
import com.jungwoo.project.memo.course.dto.CourseResponse;
import com.jungwoo.project.memo.course.dto.CourseUpdateRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 프로젝트 컨트롤러.
 *
 * GET    /api/courses               목록 (카드 요약 포함). ?status=ARCHIVED면 보관함
 * GET    /api/courses/{id}          상세
 * POST   /api/courses               생성 (제목만 있으면 된다)
 * PATCH  /api/courses/{id}          제목/분류 수정
 * DELETE /api/courses/{id}          보관(ARCHIVED). 실제 삭제하지 않는다
 * POST   /api/courses/{id}/restore  보관 해제(ACTIVE)
 * GET    /api/courses/{id}/notes    학습 topic이 아닌 과목 정보/평가 정보
 */
@Slf4j
@RestController
@RequestMapping("/api/courses")
@RequiredArgsConstructor
public class CourseController {

    private final CourseService courseService;
    private final CourseNoteService courseNoteService;

    /**
     * status를 주지 않으면 ACTIVE 목록이다. 보관함은 같은 경로에 status=ARCHIVED로 읽는다 —
     * 보관은 다른 종류의 데이터가 아니라 같은 프로젝트를 다른 상태로 보는 것뿐이다.
     */
    @GetMapping
    public ResponseEntity<List<CourseResponse>> list(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) CourseStatus status
    ) {
        return ResponseEntity.ok(courseService.list(principal.getUserId(),
                status != null ? status : CourseStatus.ACTIVE));
    }

    @GetMapping("/{courseId}")
    public ResponseEntity<CourseResponse> get(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long courseId
    ) {
        return ResponseEntity.ok(courseService.get(principal.getUserId(), courseId));
    }

    @GetMapping("/{courseId}/notes")
    public ResponseEntity<List<CourseNoteResponse>> notes(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long courseId
    ) {
        return ResponseEntity.ok(courseNoteService.getByCourse(principal.getUserId(), courseId));
    }

    @PostMapping
    public ResponseEntity<CourseResponse> create(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CourseCreateRequest request
    ) {
        log.info("POST /api/courses - userId={}, title={}", principal.getUserId(), request.getTitle());
        return ResponseEntity.status(HttpStatus.CREATED).body(courseService.create(principal.getUserId(), request));
    }

    @PatchMapping("/{courseId}")
    public ResponseEntity<CourseResponse> update(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long courseId,
            @Valid @RequestBody CourseUpdateRequest request
    ) {
        return ResponseEntity.ok(courseService.update(principal.getUserId(), courseId, request));
    }

    @DeleteMapping("/{courseId}")
    public ResponseEntity<Void> archive(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long courseId
    ) {
        courseService.archive(principal.getUserId(), courseId);
        return ResponseEntity.noContent().build();
    }

    /** 보관함에서 다시 꺼낸다. status만 ACTIVE로 되돌리면 자료 연결도 함께 다시 보인다. */
    @PostMapping("/{courseId}/restore")
    public ResponseEntity<Void> restore(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long courseId
    ) {
        courseService.restore(principal.getUserId(), courseId);
        return ResponseEntity.noContent().build();
    }
}
