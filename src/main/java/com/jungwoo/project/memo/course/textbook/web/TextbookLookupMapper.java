package com.jungwoo.project.memo.course.textbook.web;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface TextbookLookupMapper {

    int insert(TextbookLookup lookup);

    TextbookLookup findById(@Param("lookupId") Long lookupId);

    TextbookLookup findByIdAndUserId(@Param("lookupId") Long lookupId, @Param("userId") Long userId);

    /** 과목의 가장 최근 조회(상태 무관). 화면이 보이는 것은 이 행이다. */
    TextbookLookup findLatestByCourse(@Param("courseId") Long courseId, @Param("userId") Long userId);

    TextbookLookup findLatestByCourseForUpdate(@Param("courseId") Long courseId, @Param("userId") Long userId);

    /** 같은 질의의 재사용할 수 있는 완료 결과(가장 최근). */
    TextbookLookup findReusable(@Param("userId") Long userId, @Param("queryKey") String queryKey,
                                @Param("longSince") LocalDateTime longSince, @Param("shortSince") LocalDateTime shortSince);

    /** 열린 조회를 SUPERSEDED로 닫는다. 임대 토큰도 올린다 — 돌고 있는 worker가 뒤늦게 결과를 쓰지 못한다. */
    int supersedeOpen(@Param("courseId") Long courseId, @Param("userId") Long userId, @Param("reason") String reason);

    int markSuperseded(@Param("lookupId") Long lookupId);

    List<TextbookLookup> findClaimable(@Param("now") LocalDateTime now, @Param("limit") int limit);

    int claim(@Param("lookupId") Long lookupId, @Param("owner") String owner, @Param("now") LocalDateTime now,
              @Param("leaseUntil") LocalDateTime leaseUntil);

    int renewLease(@Param("lookupId") Long lookupId, @Param("leaseToken") int leaseToken,
                   @Param("leaseUntil") LocalDateTime leaseUntil);

    /** 임대가 그대로인지 확인하며 행을 잠근다. 결과 저장 직전. */
    Long lockIfLeased(@Param("lookupId") Long lookupId, @Param("leaseToken") int leaseToken);

    int finish(@Param("lookupId") Long lookupId, @Param("leaseToken") int leaseToken, @Param("status") String status,
               @Param("resultJson") String resultJson, @Param("queryJson") String queryJson,
               @Param("errorCode") String errorCode, @Param("errorMessage") String errorMessage,
               @Param("autoTidyState") String autoTidyState);

    int reschedule(@Param("lookupId") Long lookupId, @Param("leaseToken") int leaseToken,
                   @Param("nextRunAt") LocalDateTime nextRunAt, @Param("errorCode") String errorCode,
                   @Param("errorMessage") String errorMessage);

    int updateQueryJson(@Param("lookupId") Long lookupId, @Param("leaseToken") int leaseToken,
                        @Param("queryJson") String queryJson);

    int setChosen(@Param("lookupId") Long lookupId, @Param("revisionId") Long revisionId);

    List<TextbookLookup> findAutoTidyCandidates(@Param("now") LocalDateTime now, @Param("limit") int limit);

    int updateAutoTidy(@Param("lookupId") Long lookupId, @Param("from") String from, @Param("to") String to,
                       @Param("tidyJobId") Long tidyJobId, @Param("nextAt") LocalDateTime nextAt);

    int rebase(@Param("lookupId") Long lookupId, @Param("leaseToken") int leaseToken, @Param("basisKey") String basisKey,
               @Param("queryKey") String queryKey, @Param("queryJson") String queryJson,
               @Param("clueMaterialId") Long clueMaterialId, @Param("clueFileHash") String clueFileHash,
               @Param("clueExtractorVersion") Integer clueExtractorVersion,
               @Param("textbookVersion") Integer textbookVersion);

    // ===== 사용량 =====

    int ensureUsageRow(@Param("userId") Long userId, @Param("date") LocalDate date);

    /** calls + n이 상한 이하일 때만 올린다. 1이면 예약됨, 0이면 상한. */
    int reserveUsage(@Param("userId") Long userId, @Param("date") LocalDate date, @Param("n") int n,
                     @Param("limit") int limit);

    Integer findUsage(@Param("userId") Long userId, @Param("date") LocalDate date);
}
