package com.jungwoo.project.memo.learning.events.session;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 수업 회차(class_sessions)와 수업 확인에 필요한 조회. */
@Mapper
public interface ClassSessionMapper {

    int insertIgnore(@Param("userId") long userId, @Param("courseId") long courseId, @Param("routineId") long routineId,
                     @Param("sourceDate") LocalDate sourceDate, @Param("startAt") LocalDateTime startAt,
                     @Param("endAt") LocalDateTime endAt);

    SessionRow find(@Param("userId") long userId, @Param("routineId") long routineId,
                    @Param("sourceDate") LocalDate sourceDate);

    /** 루틴들의 회차(과목과 무관 — 루틴의 과목을 바꿨어도 옛 회차를 찾는다). */
    List<SessionRow> findByRoutines(@Param("userId") long userId, @Param("routineIds") java.util.Collection<Long> routineIds,
                                    @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 이 과목에 연결된 수업자료(PROFESSOR_SLIDE·OTHER, 지운 자료·상담 사진 제외)의 id. */
    List<Long> findClassMaterialIds(@Param("userId") long userId, @Param("courseId") long courseId);

    List<SessionRow> findByCourse(@Param("userId") long userId, @Param("courseId") long courseId,
                                  @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 과목의 수업 확인 묻기(없는 과목이면 null). */
    Boolean findPromptEnabled(@Param("userId") long userId, @Param("courseId") long courseId);

    int updatePromptEnabled(@Param("userId") long userId, @Param("courseId") long courseId,
                            @Param("enabled") boolean enabled);

    /** 과목에 연결된 수업자료의 지금 구간(사진 제외), 고르는 목록용 — 제목·쪽만. */
    List<SectionOptionRow> findCourseSections(@Param("userId") long userId, @Param("courseId") long courseId);

    record SessionRow(Long sessionId, Long userId, Long courseId, Long routineId, LocalDate sourceDate,
                      LocalDateTime startAt, LocalDateTime endAt) {
    }

    /** minWeek = 사용자가 확인한 그 자료의 가장 이른 주차(없으면 null). */
    record SectionOptionRow(Long sectionId, Long materialId, String materialName, String materialType,
                            LocalDateTime linkedAt, Integer minWeek, String title, Integer pageFrom, Integer pageTo,
                            Integer chunkIndex) {
    }
}
