package com.jungwoo.project.memo.course.textbook;

import com.jungwoo.project.memo.common.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 프로젝트 교재 확인·적용. 조회는 모델을 부르지 않는다(규칙 추출). */
@RestController
@RequestMapping("/api/courses/{courseId}/textbook")
@RequiredArgsConstructor
public class TextbookController {

    private final TextbookService textbookService;

    /**
     * @param values   고른 칸의 후보 값(화면에 보인 그대로) {title, author, publisher, isbn, edition 중}
     * @param expected 화면이 본 지금 교재 칸 값(고른 칸만). 그 사이 바뀌었으면 409
     */
    public record ApplyRequest(Long materialId, Map<String, String> values, Map<String, String> expected) {
    }

    @GetMapping
    public ResponseEntity<TextbookReview> review(@AuthenticationPrincipal UserPrincipal principal,
                                                 @PathVariable Long courseId) {
        return ResponseEntity.ok(textbookService.review(principal.getUserId(), courseId));
    }

    @PostMapping("/apply")
    public ResponseEntity<TextbookReview> apply(@AuthenticationPrincipal UserPrincipal principal,
                                                @PathVariable Long courseId, @RequestBody ApplyRequest request) {
        return ResponseEntity.ok(textbookService.apply(principal.getUserId(), courseId, request.materialId(),
                request.values(), request.expected()));
    }
}
