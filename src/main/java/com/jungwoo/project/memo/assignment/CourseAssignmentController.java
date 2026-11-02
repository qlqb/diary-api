package com.jungwoo.project.memo.assignment;

import com.jungwoo.project.memo.assignment.dto.AssignmentRequests;
import com.jungwoo.project.memo.assignment.dto.AssignmentResponse;
import com.jungwoo.project.memo.common.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 과제.
 *
 * GET    /api/courses/{courseId}/assignments        프로젝트의 과제(후보·확정·완료 전부)
 * GET    /api/assignments?open=true                  확정·미완료 과제 전부(오늘 화면)
 * POST   /api/assignments                            직접 추가
 * PATCH  /api/assignments/{id}/answer                과제인가요? 답
 * PATCH  /api/assignments/{id}/due                   마감(이 날짜 맞아요 포함)
 * PATCH  /api/assignments/{id}/title
 * PATCH  /api/assignments/{id}/completed             완료 체크/해제
 */
@RestController
@RequiredArgsConstructor
public class CourseAssignmentController {

    private final CourseAssignmentService assignmentService;

    @GetMapping("/api/courses/{courseId}/assignments")
    public ResponseEntity<List<AssignmentResponse>> listByCourse(@AuthenticationPrincipal UserPrincipal principal,
                                                                 @PathVariable Long courseId) {
        return ResponseEntity.ok(assignmentService.listByCourse(principal.getUserId(), courseId));
    }

    @GetMapping("/api/assignments")
    public ResponseEntity<List<AssignmentResponse>> listOpen(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(assignmentService.listOpen(principal.getUserId()));
    }

    @PostMapping("/api/assignments")
    public ResponseEntity<AssignmentResponse> create(@AuthenticationPrincipal UserPrincipal principal,
                                                     @Valid @RequestBody AssignmentRequests.Create request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(assignmentService.create(principal.getUserId(), request));
    }

    @PatchMapping("/api/assignments/{assignmentId}/answer")
    public ResponseEntity<AssignmentResponse> answer(@AuthenticationPrincipal UserPrincipal principal,
                                                     @PathVariable Long assignmentId,
                                                     @Valid @RequestBody AssignmentRequests.Answer request) {
        return ResponseEntity.ok(assignmentService.answer(principal.getUserId(), assignmentId, request));
    }

    @PatchMapping("/api/assignments/{assignmentId}/due")
    public ResponseEntity<AssignmentResponse> due(@AuthenticationPrincipal UserPrincipal principal,
                                                  @PathVariable Long assignmentId,
                                                  @Valid @RequestBody AssignmentRequests.Due request) {
        return ResponseEntity.ok(assignmentService.setDue(principal.getUserId(), assignmentId, request));
    }

    @PatchMapping("/api/assignments/{assignmentId}/title")
    public ResponseEntity<AssignmentResponse> rename(@AuthenticationPrincipal UserPrincipal principal,
                                                     @PathVariable Long assignmentId,
                                                     @Valid @RequestBody AssignmentRequests.Rename request) {
        return ResponseEntity.ok(assignmentService.rename(principal.getUserId(), assignmentId, request));
    }

    @PatchMapping("/api/assignments/{assignmentId}/completed")
    public ResponseEntity<AssignmentResponse> completed(@AuthenticationPrincipal UserPrincipal principal,
                                                        @PathVariable Long assignmentId,
                                                        @Valid @RequestBody AssignmentRequests.Complete request) {
        return ResponseEntity.ok(assignmentService.setCompleted(principal.getUserId(), assignmentId, request));
    }
}
