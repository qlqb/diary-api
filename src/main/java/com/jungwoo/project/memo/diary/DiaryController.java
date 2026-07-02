package com.jungwoo.project.memo.diary;

import com.jungwoo.project.memo.common.security.UserPrincipal;
import com.jungwoo.project.memo.diary.dto.DiaryCreateRequest;
import com.jungwoo.project.memo.diary.dto.DiaryFilterRequest;
import com.jungwoo.project.memo.diary.dto.DiaryMonthlyStatistics;
import com.jungwoo.project.memo.diary.dto.DiaryMoodStatistics;
import com.jungwoo.project.memo.diary.dto.DiaryResponse;
import com.jungwoo.project.memo.diary.dto.DiaryRevisionsResponse;
import com.jungwoo.project.memo.diary.dto.DiaryStatisticsSummary;
import com.jungwoo.project.memo.diary.dto.DiaryStreakStatistics;
import com.jungwoo.project.memo.diary.dto.DiaryUpdateRequest;
import com.jungwoo.project.memo.diary.dto.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;

/**
 * 일기 컨트롤러
 *
 * 주요 기능:
 * 1. @AuthenticationPrincipal 사용으로 JWT 직접 파싱 제거
 * 2. try-catch 제거, 예외는 GlobalExceptionHandler에서 처리
 * 3. MyBatis 기반 page/size 직접 페이징 처리
 * 4. CRUD, 검색, 수정 이력, 통계 API 제공
 */
@Slf4j
@RestController
@RequestMapping("/api/diaries")
@RequiredArgsConstructor
public class DiaryController {

    private final DiaryService diaryService;

    /**
     * 일기 목록 조회
     *
     * 예:
     * GET /api/diaries?page=1&size=10&mood=HAPPY&favorite=true
     */
    @GetMapping
    public ResponseEntity<PageResponse<DiaryResponse>> getDiaries(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @ModelAttribute DiaryFilterRequest filter
    ) {
        log.info("GET /api/diaries - userId={}, page={}, size={}, filter={}",
                principal.getUserId(), page, size, filter);

        PageResponse<DiaryResponse> response = diaryService.getDiaries(
                principal.getUserId(),
                page,
                size,
                filter
        );

        return ResponseEntity.ok(response);
    }

    /**
     * 일기 검색
     *
     * 예:
     * GET /api/diaries/search?keyword=공부&page=1&size=10
     */
    @GetMapping("/search")
    public ResponseEntity<PageResponse<DiaryResponse>> searchDiaries(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        log.info("GET /api/diaries/search - userId={}, keyword={}, page={}, size={}",
                principal.getUserId(), keyword, page, size);

        PageResponse<DiaryResponse> response = diaryService.searchDiaries(
                principal.getUserId(),
                keyword,
                page,
                size
        );

        return ResponseEntity.ok(response);
    }

    /**
     * 일기 상세 조회
     *
     * GET /api/diaries/{diaryId}
     */
    @GetMapping("/{diaryId}")
    public ResponseEntity<DiaryResponse> getDiary(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long diaryId
    ) {
        log.info("GET /api/diaries/{} - userId={}", diaryId, principal.getUserId());

        DiaryResponse response = diaryService.getDiary(diaryId, principal.getUserId());

        return ResponseEntity.ok(response);
    }

    /**
     * 일기 작성
     *
     * POST /api/diaries
     */
    @PostMapping
    public ResponseEntity<DiaryResponse> createDiary(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody DiaryCreateRequest request
    ) {
        log.info("POST /api/diaries - userId={}, title={}",
                principal.getUserId(), request.getTitle());

        DiaryResponse response = diaryService.createDiary(principal.getUserId(), request);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 일기 수정
     *
     * PUT /api/diaries/{diaryId}
     */
    @PutMapping("/{diaryId}")
    public ResponseEntity<DiaryResponse> updateDiary(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long diaryId,
            @Valid @RequestBody DiaryUpdateRequest request
    ) {
        log.info("PUT /api/diaries/{} - userId={}", diaryId, principal.getUserId());

        DiaryResponse response = diaryService.updateDiary(
                diaryId,
                principal.getUserId(),
                request
        );

        return ResponseEntity.ok(response);
    }

    /**
     * 일기 삭제
     *
     * DELETE /api/diaries/{diaryId}
     */
    @DeleteMapping("/{diaryId}")
    public ResponseEntity<Void> deleteDiary(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long diaryId
    ) {
        log.info("DELETE /api/diaries/{} - userId={}", diaryId, principal.getUserId());

        diaryService.deleteDiary(diaryId, principal.getUserId());

        return ResponseEntity.noContent().build();
    }

    /**
     * 즐겨찾기 토글
     *
     * PATCH /api/diaries/{diaryId}/favorite
     */
    @PatchMapping("/{diaryId}/favorite")
    public ResponseEntity<DiaryResponse> toggleFavorite(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long diaryId
    ) {
        log.info("PATCH /api/diaries/{}/favorite - userId={}", diaryId, principal.getUserId());

        DiaryResponse response = diaryService.toggleFavorite(diaryId, principal.getUserId());

        return ResponseEntity.ok(response);
    }

    /**
     * 일기 수정 이력 목록 조회
     *
     * GET /api/diaries/{diaryId}/revisions
     */
    @GetMapping("/{diaryId}/revisions")
    public ResponseEntity<DiaryRevisionsResponse> getRevisions(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long diaryId
    ) {
        log.info("GET /api/diaries/{}/revisions - userId={}", diaryId, principal.getUserId());

        DiaryRevisionsResponse response = diaryService.getRevisions(
                diaryId,
                principal.getUserId()
        );

        return ResponseEntity.ok(response);
    }

    /**
     * 특정 수정 이력으로 복구
     *
     * POST /api/diaries/{diaryId}/revisions/{revisionId}/restore
     */
    @PostMapping("/{diaryId}/revisions/{revisionId}/restore")
    public ResponseEntity<DiaryResponse> restoreRevision(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long diaryId,
            @PathVariable Long revisionId
    ) {
        log.info("POST /api/diaries/{}/revisions/{}/restore - userId={}",
                diaryId, revisionId, principal.getUserId());

        DiaryResponse response = diaryService.restoreRevision(
                diaryId,
                revisionId,
                principal.getUserId()
        );

        return ResponseEntity.ok(response);
    }

    /**
     * 일기 통계 요약
     *
     * GET /api/diaries/statistics/summary
     */
    @GetMapping("/statistics/summary")
    public ResponseEntity<DiaryStatisticsSummary> getStatisticsSummary(
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        log.info("GET /api/diaries/statistics/summary - userId={}", principal.getUserId());

        DiaryStatisticsSummary response = diaryService.getStatisticsSummary(
                principal.getUserId()
        );

        return ResponseEntity.ok(response);
    }

    /**
     * 기분별 통계
     *
     * GET /api/diaries/statistics/mood
     */
    @GetMapping("/statistics/mood")
    public ResponseEntity<DiaryMoodStatistics> getMoodStatistics(
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        log.info("GET /api/diaries/statistics/mood - userId={}", principal.getUserId());

        DiaryMoodStatistics response = diaryService.getMoodStatistics(
                principal.getUserId()
        );

        return ResponseEntity.ok(response);
    }

    /**
     * 월별 통계
     *
     * GET /api/diaries/statistics/monthly?year=2026
     */
    @GetMapping("/statistics/monthly")
    public ResponseEntity<DiaryMonthlyStatistics> getMonthlyStatistics(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) Integer year
    ) {
        log.info("GET /api/diaries/statistics/monthly - userId={}, year={}",
                principal.getUserId(), year);

        DiaryMonthlyStatistics response = diaryService.getMonthlyStatistics(
                principal.getUserId(),
                year
        );

        return ResponseEntity.ok(response);
    }

    /**
     * 연속 작성 일수
     *
     * GET /api/diaries/statistics/streak
     */
    @GetMapping("/statistics/streak")
    public ResponseEntity<DiaryStreakStatistics> getStreakStatistics(
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        log.info("GET /api/diaries/statistics/streak - userId={}", principal.getUserId());

        DiaryStreakStatistics response = diaryService.getStreakStatistics(
                principal.getUserId()
        );

        return ResponseEntity.ok(response);
    }
}