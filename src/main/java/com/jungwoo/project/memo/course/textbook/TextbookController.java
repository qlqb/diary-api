package com.jungwoo.project.memo.course.textbook;

import com.jungwoo.project.memo.common.security.UserPrincipal;
import com.jungwoo.project.memo.course.textbook.web.TextbookLookupService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 프로젝트 교재 확인·적용·웹 조회. 조회(GET)는 모델·외부 호출을 하지 않는다 — 필요하면 조회 작업을 등록만 하고, 서버
 * worker가 찾는다. 화면은 이 GET을 다시 불러 진행·결과를 본다.
 */
@RestController
@RequestMapping("/api/courses/{courseId}/textbook")
@RequiredArgsConstructor
public class TextbookController {

    private final TextbookService textbookService;
    private final TextbookLookupService lookupService;

    /**
     * @param values          고른 칸의 후보 값(화면에 보인 그대로) {title, author, publisher, isbn, edition 중}
     * @param expected        화면이 본 지금 교재 칸 값(고른 칸만). 그 사이 바뀌었으면 409
     * @param expectedVersion 화면이 본 교재 판
     */
    public record ApplyRequest(Long materialId, Map<String, String> values, Map<String, String> expected,
                               Integer expectedVersion) {
    }

    public record ChooseRequest(Long lookupId, Long revisionId, Integer expectedVersion) {
    }

    public record LinkRequest(String url) {
    }

    public record ClueRequest(Long materialId, String title, Integer expectedVersion) {
    }

    public record TocLinkRequest(Long materialId, Integer expectedVersion) {
    }

    public record EnabledRequest(boolean enabled) {
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
                request.values(), request.expected(), request.expectedVersion()));
    }

    /** 강의계획서 단서 하나를 지금 교재로(주교재 단서가 여럿일 때 고르기). */
    @PostMapping("/clue/apply")
    public ResponseEntity<TextbookReview> applyClue(@AuthenticationPrincipal UserPrincipal principal,
                                                    @PathVariable Long courseId, @RequestBody ClueRequest request) {
        return ResponseEntity.ok(textbookService.applyClue(principal.getUserId(), courseId, request.materialId(),
                request.title(), request.expectedVersion()));
    }

    /** 웹에서 찾은 판 중 하나를 지금 교재로. */
    @PostMapping("/web/choose")
    public ResponseEntity<TextbookReview> choose(@AuthenticationPrincipal UserPrincipal principal,
                                                 @PathVariable Long courseId, @RequestBody ChooseRequest request) {
        lookupService.choose(principal.getUserId(), courseId, request.lookupId(), request.revisionId(),
                request.expectedVersion());
        return ResponseEntity.ok(textbookService.view(principal.getUserId(), courseId));
    }

    /** 지금 근거로 다시 찾기(재사용·캐시를 건너뛴다). */
    @PostMapping("/web/retry")
    public ResponseEntity<TextbookReview> retry(@AuthenticationPrincipal UserPrincipal principal,
                                                @PathVariable Long courseId) {
        lookupService.retry(principal.getUserId(), courseId);
        return ResponseEntity.ok(textbookService.view(principal.getUserId(), courseId));
    }

    /** 사용자가 준 도서 상세 페이지 링크로 찾기. */
    @PostMapping("/web/link")
    public ResponseEntity<TextbookReview> link(@AuthenticationPrincipal UserPrincipal principal,
                                               @PathVariable Long courseId, @RequestBody LinkRequest request) {
        lookupService.link(principal.getUserId(), courseId, request.url());
        return ResponseEntity.ok(textbookService.view(principal.getUserId(), courseId));
    }

    /** 교재 단서를 외부로 보내 찾을지. */
    @PutMapping("/web/enabled")
    public ResponseEntity<TextbookReview> enabled(@AuthenticationPrincipal UserPrincipal principal,
                                                  @PathVariable Long courseId, @RequestBody EnabledRequest request) {
        lookupService.setEnabled(principal.getUserId(), courseId, request.enabled());
        return ResponseEntity.ok(textbookService.view(principal.getUserId(), courseId));
    }

    /** 식별 없는 업로드 목차를 지금 교재의 목차로 잇는다. */
    @PostMapping("/toc-link")
    public ResponseEntity<TextbookReview> tocLink(@AuthenticationPrincipal UserPrincipal principal,
                                                  @PathVariable Long courseId, @RequestBody TocLinkRequest request) {
        return ResponseEntity.ok(textbookService.linkToc(principal.getUserId(), courseId, request.materialId(),
                request.expectedVersion()));
    }
}
