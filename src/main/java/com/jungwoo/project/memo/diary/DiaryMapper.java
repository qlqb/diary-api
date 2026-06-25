package com.jungwoo.project.memo.diary;

import com.jungwoo.project.memo.diary.domain.Diary;
import com.jungwoo.project.memo.diary.dto.DiaryFilterRequest;
import com.jungwoo.project.memo.diary.dto.MoodCount;
import com.jungwoo.project.memo.diary.dto.MonthCount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * Diary 테이블 MyBatis 매퍼
 *
 * XML과 연동하여 SQL 실행
 */
@Mapper
public interface DiaryMapper {

    // ===== 기본 CRUD =====

    /**
     * 일기 등록
     */
    void insert(Diary diary);

    /**
     * 일기 ID로 조회
     */
    Diary findById(@Param("diaryId") Long diaryId);

    /**
     * 일기 수정
     */
    void update(Diary diary);

    /**
     * 일기 삭제 (물리 삭제는 사용하지 않음)
     */
    void delete(@Param("diaryId") Long diaryId);

    // ===== 조회 (페이징, 필터링) =====

    /**
     * 사용자의 일기 목록 조회 (필터 적용)
     *
     * @param userId 사용자 ID
     * @param filter 필터 조건
     * @param limit 조회 개수
     * @param offset 시작 위치
     * @return 일기 목록
     */
    List<Diary> findByUserIdWithFilter(
            @Param("userId") Long userId,
            @Param("filter") DiaryFilterRequest filter,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    /**
     * 사용자의 일기 개수 (필터 적용)
     */
    int countByUserIdWithFilter(
            @Param("userId") Long userId,
            @Param("filter") DiaryFilterRequest filter
    );

    /**
     * 일기 검색 (제목, 내용)
     */
    List<Diary> searchDiaries(
            @Param("userId") Long userId,
            @Param("keyword") String keyword,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    /**
     * 검색 결과 개수
     */
    int countByKeyword(
            @Param("userId") Long userId,
            @Param("keyword") String keyword
    );

    // ===== 통계 쿼리 =====

    /**
     * 사용자의 전체 일기 개수
     */
    int countByUserId(@Param("userId") Long userId);

    /**
     * 특정 월의 일기 개수
     */
    int countByUserIdAndMonth(
            @Param("userId") Long userId,
            @Param("year") int year,
            @Param("month") int month
    );

    /**
     * 즐겨찾기 일기 개수
     */
    int countByUserIdAndFavorite(
            @Param("userId") Long userId,
            @Param("favorite") boolean favorite
    );

    /**
     * 기분별 일기 개수
     */
    List<MoodCount> countByUserIdGroupByMood(@Param("userId") Long userId);

    /**
     * 월별 일기 개수 (특정 연도)
     */
    List<MonthCount> countByUserIdAndYearGroupByMonth(
            @Param("userId") Long userId,
            @Param("year") int year
    );

    /**
     * 작성 날짜 목록 (중복 제거)
     * 연속 작성 일수 계산용
     */
    List<LocalDate> findDistinctWrittenDatesByUserId(@Param("userId") Long userId);
}