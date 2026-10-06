package com.jungwoo.project.memo.learning.events.backfill;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** 백필 진행 위치와 대상 목록. 대상은 모두 전환(cutover) 전에 만든 원본이다. */
@Mapper
public interface BackfillMapper {

    int ensureRow(@Param("source") String source);

    Progress find(@Param("source") String source);

    /** lease를 잡는다(끝나지 않았고, 아무도 안 잡았거나 lease가 지났을 때만). 잡았으면 1. */
    int acquire(@Param("source") String source, @Param("owner") String owner, @Param("minutes") int minutes);

    /** 진행 위치·보고를 올리고 lease를 늘린다 — 내가 lease를 쥐고 있을 때만(아니면 0). */
    int advance(@Param("source") String source, @Param("owner") String owner, @Param("lastId") long lastId,
                @Param("report") String report, @Param("minutes") int minutes);

    int finish(@Param("source") String source, @Param("owner") String owner, @Param("report") String report);

    List<Unit> nextRecords(@Param("afterId") long afterId, @Param("before") LocalDateTime before, @Param("limit") int limit,
                           @Param("onlyUser") Long onlyUser);

    List<Unit> nextContextCourses(@Param("afterId") long afterId, @Param("before") LocalDateTime before,
                                  @Param("limit") int limit, @Param("onlyUser") Long onlyUser);

    List<Unit> nextCorrectionCourses(@Param("afterId") long afterId, @Param("before") LocalDateTime before,
                                     @Param("limit") int limit, @Param("onlyUser") Long onlyUser);

    List<Unit> nextTopics(@Param("afterId") long afterId, @Param("before") LocalDateTime before, @Param("limit") int limit,
                           @Param("onlyUser") Long onlyUser);

    List<Unit> nextLinks(@Param("afterId") long afterId, @Param("before") LocalDateTime before, @Param("limit") int limit,
                           @Param("onlyUser") Long onlyUser);

    /** 실시간 경로와 같은 순서로 원본 행을 먼저 잠근다(원본 → 교재 별칭 → origin). */
    Long lockTopic(@Param("userId") long userId, @Param("topicId") long topicId);

    Long lockTopicProgress(@Param("userId") long userId, @Param("topicId") long topicId);

    Long lockMaterialLink(@Param("userId") long userId, @Param("linkId") long linkId);

    /** 과목의 그 종류들 origin 판 합(동기화 전후 비교 — 바뀐 것이 있었나). */
    /** 과목의 전환 전 살아 있는 기억 행 중 이벤트를 하나도 쓰지 못한(판 0) 행 수. */
    long countUnwrittenContexts(@Param("userId") long userId, @Param("courseId") long courseId,
                                @Param("before") LocalDateTime before);

    /** 과목의 전환 전 범위 제외·수업 진도 행 중 판 0인 행 수. */
    long countUnwrittenCorrections(@Param("userId") long userId, @Param("courseId") long courseId,
                                   @Param("before") LocalDateTime before);

    long sumRevisions(@Param("userId") long userId, @Param("courseId") long courseId,
                      @Param("kinds") java.util.Collection<String> kinds);

    record Progress(String source, Long lastId, Boolean done, String report, String leaseOwner) {
    }

    /** 처리 단위 하나(id = 원본 id 또는 과목 id). */
    record Unit(Long id, Long userId) {
    }
}
