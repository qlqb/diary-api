package com.jungwoo.project.memo.course;

import com.jungwoo.project.memo.common.security.UserPrincipal;
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
 * GET    /api/courses               목록 (카드 요약 포함)
 * GET    /api/courses/{id}          상세
 * POST   /api/courses               생성 (제목만 있으면 된다)
 * PATCH  /api/courses/{id}          제목/분류 수정
 * DELETE /api/courses/{id}          보관(ARCHIVED). 실제 삭제하지 않는다
 * GET    /api/courses/{id}/notes    학습 topic이 아닌 과목 정보/평가 정보
 */
@Slf4j
@RestController
@RequestMapping("/api/courses")
@RequiredArgsConstructor
public class CourseController {

    private final CourseService courseService;
    private final CourseNoteService courseNoteService;

    @GetMapping
    public ResponseEntity<List<CourseResponse>> list(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(courseService.list(principal.getUserId()));
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
}
