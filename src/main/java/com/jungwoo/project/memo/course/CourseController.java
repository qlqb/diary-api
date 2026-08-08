package com.jungwoo.project.memo.course;

import com.jungwoo.project.memo.common.security.UserPrincipal;
import com.jungwoo.project.memo.course.dto.CourseCreateRequest;
import com.jungwoo.project.memo.course.dto.CourseResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 과목 컨트롤러.
 *
 * GET  /api/courses          목록
 * GET  /api/courses/{id}     상세
 * POST /api/courses          생성
 */
@Slf4j
@RestController
@RequestMapping("/api/courses")
@RequiredArgsConstructor
public class CourseController {

    private final CourseService courseService;

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

    @PostMapping
    public ResponseEntity<CourseResponse> create(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CourseCreateRequest request
    ) {
        log.info("POST /api/courses - userId={}, title={}", principal.getUserId(), request.getTitle());
        return ResponseEntity.status(HttpStatus.CREATED).body(courseService.create(principal.getUserId(), request));
    }
}
