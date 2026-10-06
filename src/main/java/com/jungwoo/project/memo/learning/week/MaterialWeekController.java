package com.jungwoo.project.memo.learning.week;

import com.jungwoo.project.memo.common.security.UserPrincipal;
import com.jungwoo.project.memo.learning.week.dto.MaterialWeekRequests;
import com.jungwoo.project.memo.learning.week.dto.MaterialWeekReviewResponse;
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

/**
 * 프로젝트의 자료 주차 확인.
 *
 * <p>GET은 추천을 보여 줄 뿐이고, 자리를 정하는 것은 PUT(한 자료)과 POST apply-suggestions(화면에 보인 추천 일괄)
 * 둘뿐이다. 둘 다 사용자 조작으로만 불린다 — 분석이 끝났다고 서버가 스스로 부르지 않는다.
 */
@RestController
@RequestMapping("/api/courses/{courseId}/material-weeks")
@RequiredArgsConstructor
public class MaterialWeekController {

    private final MaterialWeekService materialWeekService;

    @GetMapping
    public ResponseEntity<MaterialWeekReviewResponse> review(@AuthenticationPrincipal UserPrincipal principal,
                                                             @PathVariable Long courseId) {
        return ResponseEntity.ok(materialWeekService.review(principal.getUserId(), courseId));
    }

    @PutMapping("/{materialId}")
    public ResponseEntity<MaterialWeekReviewResponse> place(@AuthenticationPrincipal UserPrincipal principal,
                                                            @PathVariable Long courseId,
                                                            @PathVariable Long materialId,
                                                            @RequestBody MaterialWeekRequests.Place request) {
        return ResponseEntity.ok(materialWeekService.place(principal.getUserId(), courseId, materialId, request));
    }

    @PostMapping("/apply-suggestions")
    public ResponseEntity<MaterialWeekRequests.ApplyResult> applySuggestions(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long courseId,
            @RequestBody MaterialWeekRequests.ApplySuggestions request) {
        return ResponseEntity.ok(materialWeekService.applySuggestions(principal.getUserId(), courseId, request));
    }
}
