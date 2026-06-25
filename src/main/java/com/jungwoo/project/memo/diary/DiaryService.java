package com.jungwoo.project.memo.diary;

import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.ForbiddenException;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.diary.domain.Diary;
import com.jungwoo.project.memo.diary.domain.DiaryRevision;
import com.jungwoo.project.memo.diary.dto.DiaryCreateRequest;
import com.jungwoo.project.memo.diary.dto.DiaryFilterRequest;
import com.jungwoo.project.memo.diary.dto.DiaryMonthlyStatistics;
import com.jungwoo.project.memo.diary.dto.DiaryMoodStatistics;
import com.jungwoo.project.memo.diary.dto.DiaryResponse;
import com.jungwoo.project.memo.diary.dto.DiaryRevisionsResponse;
import com.jungwoo.project.memo.diary.dto.DiaryStatisticsSummary;
import com.jungwoo.project.memo.diary.dto.DiaryStreakStatistics;
import com.jungwoo.project.memo.diary.dto.DiaryUpdateRequest;
import com.jungwoo.project.memo.diary.dto.MonthCount;
import com.jungwoo.project.memo.diary.dto.MoodCount;
import com.jungwoo.project.memo.diary.dto.PageResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.List;

/**
 * 일기 서비스
 *
 * 주요 기능:
 * 1. CRUD
 * 2. 페이징, 필터링, 검색
 * 3. 즐겨찾기 관리
 * 4. 수정 이력 관리 및 복구
 * 5. 통계
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiaryService {

    private static final int MAX_PAGE_SIZE = 100;

    private final DiaryMapper diaryMapper;
    private final DiaryRevisionMapper diaryRevisionMapper;

    /**
     * 일기 목록 조회
     */
    @Transactional(readOnly = true)
    public PageResponse<DiaryResponse> getDiaries(
            Long userId,
            int page,
            int size,
            DiaryFilterRequest filter
    ) {
        int safePage = Math.max(page, 1);
        int safeSize = normalizeSize(size);
        int offset = (safePage - 1) * safeSize;

        int totalElements = diaryMapper.countByUserIdWithFilter(userId, filter);

        List<Diary> diaries = diaryMapper.findByUserIdWithFilter(
                userId,
                filter,
                safeSize,
                offset
        );

        List<DiaryResponse> content = diaries.stream()
                .map(DiaryResponse::from)
                .toList();

        return PageResponse.of(content, safePage, safeSize, totalElements);
    }

    /**
     * 일기 상세 조회
     */
    @Transactional(readOnly = true)
    public DiaryResponse getDiary(Long diaryId, Long userId) {
        Diary diary = findDiaryById(diaryId);
        validateOwnership(diary, userId);

        return DiaryResponse.from(diary);
    }

    /**
     * 일기 검색
     */
    @Transactional(readOnly = true)
    public PageResponse<DiaryResponse> searchDiaries(
            Long userId,
            String keyword,
            int page,
            int size
    ) {
        int safePage = Math.max(page, 1);
        int safeSize = normalizeSize(size);
        int offset = (safePage - 1) * safeSize;

        int totalElements = diaryMapper.countByKeyword(userId, keyword);

        List<Diary> diaries = diaryMapper.searchDiaries(
                userId,
                keyword,
                safeSize,
                offset
        );

        List<DiaryResponse> content = diaries.stream()
                .map(DiaryResponse::from)
                .toList();

        return PageResponse.of(content, safePage, safeSize, totalElements);
    }

    /**
     * 일기 작성
     */
    @Transactional
    public DiaryResponse createDiary(Long userId, DiaryCreateRequest request) {
        log.info("일기 작성 시작: userId={}, title={}", userId, request.getTitle());

        LocalDateTime now = LocalDateTime.now();

        Diary diary = Diary.builder()
                .userId(userId)
                .writtenDate(request.getWrittenDate())
                .title(request.getTitle())
                .content(request.getContent())
                .mood(request.getMood())
                .visibility(request.getVisibility() != null ? request.getVisibility() : "PRIVATE")
                .weather(request.getWeather())
                .isFavorite(false)
                .isDeleted(false)
                .createdAt(now)
                .updatedAt(now)
                .build();

        diaryMapper.insert(diary);

        log.info("일기 작성 완료: diaryId={}", diary.getDiaryId());

        return DiaryResponse.from(diary);
    }

    /**
     * 일기 수정
     *
     * 수정 전 내용을 revision 테이블에 저장한다.
     */
    @Transactional
    public DiaryResponse updateDiary(
            Long diaryId,
            Long userId,
            DiaryUpdateRequest request
    ) {
        log.info("일기 수정 시작: diaryId={}, userId={}", diaryId, userId);

        Diary diary = findDiaryById(diaryId);
        validateOwnership(diary, userId);

        saveRevision(diary);

        if (request.getWrittenDate() != null) {
            diary.setWrittenDate(request.getWrittenDate());
        }

        if (request.getTitle() != null) {
            diary.setTitle(request.getTitle());
        }

        if (request.getContent() != null) {
            diary.setContent(request.getContent());
        }

        if (request.getMood() != null) {
            diary.setMood(request.getMood());
        }

        if (request.getVisibility() != null) {
            diary.setVisibility(request.getVisibility());
        }

        if (request.getWeather() != null) {
            diary.setWeather(request.getWeather());
        }

        if (request.getIsFavorite() != null) {
            diary.setIsFavorite(request.getIsFavorite());
        }

        diary.setUpdatedAt(LocalDateTime.now());

        diaryMapper.update(diary);

        log.info("일기 수정 완료: diaryId={}", diaryId);

        return DiaryResponse.from(diary);
    }

    /**
     * 일기 삭제, 소프트 삭제
     */
    @Transactional
    public void deleteDiary(Long diaryId, Long userId) {
        log.info("일기 삭제 시작: diaryId={}, userId={}", diaryId, userId);

        Diary diary = findDiaryById(diaryId);
        validateOwnership(diary, userId);

        diary.setIsDeleted(true);
        diary.setUpdatedAt(LocalDateTime.now());

        diaryMapper.update(diary);

        log.info("일기 삭제 완료: diaryId={}", diaryId);
    }

    /**
     * 즐겨찾기 토글
     */
    @Transactional
    public DiaryResponse toggleFavorite(Long diaryId, Long userId) {
        log.info("즐겨찾기 토글: diaryId={}, userId={}", diaryId, userId);

        Diary diary = findDiaryById(diaryId);
        validateOwnership(diary, userId);

        Boolean currentFavorite = diary.getIsFavorite();
        diary.setIsFavorite(currentFavorite == null || !currentFavorite);
        diary.setUpdatedAt(LocalDateTime.now());

        diaryMapper.update(diary);

        log.info("즐겨찾기 토글 완료: diaryId={}, isFavorite={}",
                diaryId, diary.getIsFavorite());

        return DiaryResponse.from(diary);
    }

    /**
     * 수정 이력 목록 조회
     */
    @Transactional(readOnly = true)
    public DiaryRevisionsResponse getRevisions(Long diaryId, Long userId) {
        Diary diary = findDiaryById(diaryId);
        validateOwnership(diary, userId);

        List<DiaryRevision> revisions = diaryRevisionMapper.findByDiaryId(diaryId);

        return DiaryRevisionsResponse.of(diary, revisions);
    }

    /**
     * 특정 수정 이력으로 복구
     *
     * 복구 전 현재 내용도 revision에 저장한다.
     */
    @Transactional
    public DiaryResponse restoreRevision(Long diaryId, Long revisionId, Long userId) {
        log.info("수정 이력 복구 시작: diaryId={}, revisionId={}", diaryId, revisionId);

        Diary diary = findDiaryById(diaryId);
        validateOwnership(diary, userId);

        DiaryRevision revision = diaryRevisionMapper.findById(revisionId);

        if (revision == null || !revision.getDiaryId().equals(diaryId)) {
            throw new NotFoundException(ErrorCode.DIARY_REVISION_NOT_FOUND);
        }

        saveRevision(diary);

        diary.setWrittenDate(revision.getWrittenDate());
        diary.setTitle(revision.getTitle());
        diary.setContent(revision.getContent());
        diary.setMood(revision.getMood());
        diary.setVisibility(revision.getVisibility());
        diary.setWeather(revision.getWeather());
        diary.setIsFavorite(revision.getIsFavorite());
        diary.setUpdatedAt(LocalDateTime.now());

        diaryMapper.update(diary);

        log.info("수정 이력 복구 완료: diaryId={}, revisionId={}", diaryId, revisionId);

        return DiaryResponse.from(diary);
    }

    /**
     * 수정 이력 저장
     */
    private void saveRevision(Diary diary) {
        DiaryRevision revision = DiaryRevision.builder()
                .diaryId(diary.getDiaryId())
                .writtenDate(diary.getWrittenDate())
                .title(diary.getTitle())
                .content(diary.getContent())
                .mood(diary.getMood())
                .visibility(diary.getVisibility())
                .weather(diary.getWeather())
                .isFavorite(diary.getIsFavorite())
                .editedAt(LocalDateTime.now())
                .build();

        diaryRevisionMapper.insert(revision);

        log.debug("수정 이력 저장: diaryId={}, revisionId={}",
                diary.getDiaryId(), revision.getRevisionId());
    }

    /**
     * 통계 요약
     */
    @Transactional(readOnly = true)
    public DiaryStatisticsSummary getStatisticsSummary(Long userId) {
        int totalCount = diaryMapper.countByUserId(userId);

        int thisMonthCount = diaryMapper.countByUserIdAndMonth(
                userId,
                Year.now().getValue(),
                LocalDate.now().getMonthValue()
        );

        int favoriteCount = diaryMapper.countByUserIdAndFavorite(userId, true);

        return DiaryStatisticsSummary.of(totalCount, thisMonthCount, favoriteCount);
    }

    /**
     * 기분별 통계
     */
    @Transactional(readOnly = true)
    public DiaryMoodStatistics getMoodStatistics(Long userId) {
        List<MoodCount> moodCounts = diaryMapper.countByUserIdGroupByMood(userId);

        return DiaryMoodStatistics.of(moodCounts);
    }

    /**
     * 월별 통계
     */
    @Transactional(readOnly = true)
    public DiaryMonthlyStatistics getMonthlyStatistics(Long userId, Integer year) {
        int targetYear = year != null ? year : Year.now().getValue();

        List<MonthCount> monthlyCounts = diaryMapper.countByUserIdAndYearGroupByMonth(
                userId,
                targetYear
        );

        return DiaryMonthlyStatistics.of(targetYear, monthlyCounts);
    }

    /**
     * 연속 작성 일수 계산
     */
    @Transactional(readOnly = true)
    public DiaryStreakStatistics getStreakStatistics(Long userId) {
        List<LocalDate> writtenDates = diaryMapper.findDistinctWrittenDatesByUserId(userId);

        int currentStreak = calculateCurrentStreak(writtenDates);
        int longestStreak = calculateLongestStreak(writtenDates);

        return DiaryStreakStatistics.of(currentStreak, longestStreak);
    }

    /**
     * 일기 조회, 없으면 예외
     */
    private Diary findDiaryById(Long diaryId) {
        Diary diary = diaryMapper.findById(diaryId);

        if (diary == null || Boolean.TRUE.equals(diary.getIsDeleted())) {
            throw new NotFoundException(ErrorCode.DIARY_NOT_FOUND);
        }

        return diary;
    }

    /**
     * 작성자 확인
     */
    private void validateOwnership(Diary diary, Long userId) {
        if (!diary.getUserId().equals(userId)) {
            throw new ForbiddenException(ErrorCode.NOT_RESOURCE_OWNER);
        }
    }

    /**
     * size 값 보정
     */
    private int normalizeSize(int size) {
        if (size < 1) {
            return 10;
        }

        return Math.min(size, MAX_PAGE_SIZE);
    }

    /**
     * 현재 연속 작성 일수 계산
     */
    private int calculateCurrentStreak(List<LocalDate> dates) {
        if (dates == null || dates.isEmpty()) {
            return 0;
        }

        dates.sort((a, b) -> b.compareTo(a));

        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);

        if (!dates.contains(today) && !dates.contains(yesterday)) {
            return 0;
        }

        int streak = 0;
        LocalDate checkDate = dates.contains(today) ? today : yesterday;

        for (LocalDate date : dates) {
            if (date.equals(checkDate)) {
                streak++;
                checkDate = checkDate.minusDays(1);
            } else if (date.isBefore(checkDate)) {
                break;
            }
        }

        return streak;
    }

    /**
     * 최장 연속 작성 일수 계산
     */
    private int calculateLongestStreak(List<LocalDate> dates) {
        if (dates == null || dates.isEmpty()) {
            return 0;
        }

        dates.sort(LocalDate::compareTo);

        int longestStreak = 1;
        int currentStreak = 1;

        for (int i = 1; i < dates.size(); i++) {
            if (dates.get(i).equals(dates.get(i - 1).plusDays(1))) {
                currentStreak++;
                longestStreak = Math.max(longestStreak, currentStreak);
            } else {
                currentStreak = 1;
            }
        }

        return longestStreak;
    }
}