package com.jungwoo.project.memo.learning.tidy;

import com.jungwoo.project.memo.common.security.UserPrincipal;
import com.jungwoo.project.memo.learning.correction.CourseCorrectionService;
import com.jungwoo.project.memo.learning.structure.TopicChangeOp;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 학습 구조 조정. 직접 조작·말로 한 요청 모두 정리안(검토 → 선택 적용)으로 들어간다 — 여기서는 아무것도 바로 바뀌지 않는다.
 * 범위 제외 풀기만 즉시다(사용자가 자기 정정을 되돌리는 것).
 */
@RestController
@RequiredArgsConstructor
public class StructureController {

    private final StructureRequestService requestService;
    private final CourseCorrectionService correctionService;

    public record ManualRequest(List<TopicChangeOp> ops, String note) {
    }

    public record TextRequest(String text, List<Long> focusTopicIds) {
    }

    @PostMapping("/api/courses/{courseId}/structure/manual")
    public ResponseEntity<StructureRequestService.RequestResult> manual(@AuthenticationPrincipal UserPrincipal principal,
                                                                        @PathVariable Long courseId,
                                                                        @RequestBody ManualRequest request) {
        return ResponseEntity.ok(requestService.manual(principal.getUserId(), courseId,
                request == null ? null : request.ops(), request == null ? null : request.note()));
    }

    @PostMapping("/api/courses/{courseId}/structure/requests")
    public ResponseEntity<StructureRequestService.RequestResult> request(@AuthenticationPrincipal UserPrincipal principal,
                                                                         @PathVariable Long courseId,
                                                                         @RequestBody TextRequest request) {
        return ResponseEntity.ok(requestService.interpret(principal.getUserId(), courseId,
                request == null ? null : request.text(), request == null ? null : request.focusTopicIds()));
    }

    /** 사용자가 정정한 실제 수업 진행과 범위 제외. */
    @GetMapping("/api/courses/{courseId}/corrections")
    public ResponseEntity<CourseCorrectionService.CorrectionsView> corrections(
            @AuthenticationPrincipal UserPrincipal principal, @PathVariable Long courseId) {
        return ResponseEntity.ok(correctionService.view(principal.getUserId(), courseId));
    }

    @DeleteMapping("/api/courses/{courseId}/scope-exclusions/{exclusionId}")
    public ResponseEntity<CourseCorrectionService.CorrectionsView> removeExclusion(
            @AuthenticationPrincipal UserPrincipal principal, @PathVariable Long courseId, @PathVariable Long exclusionId) {
        return ResponseEntity.ok(correctionService.removeExclusion(principal.getUserId(), courseId, exclusionId));
    }
}
