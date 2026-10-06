package com.jungwoo.project.memo.learning.events;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

@Mapper
public interface LearningEventMapper {

    /** origin 행(잠금 읽기). 없으면 null. */
    OriginRow lockOrigin(@Param("userId") long userId, @Param("originKind") String originKind,
                         @Param("originId") long originId);

    /** 잠금 없이 origin 행. 백필이 "실시간이 이미 썼나"를 볼 때는 {@link #lockOrigin}을 쓴다. */
    OriginRow findOrigin(@Param("userId") long userId, @Param("originKind") String originKind,
                         @Param("originId") long originId);

    /** 없으면 만들고, 있으면 그 행에 배타 잠금을 건다(INSERT IGNORE의 공유 잠금 → 잠금 읽기 교착을 피한다). */
    int upsertOrigin(@Param("userId") long userId, @Param("originKind") String originKind,
                     @Param("originId") long originId, @Param("courseId") long courseId);

    int updateOriginRevision(@Param("userId") long userId, @Param("originKind") String originKind,
                             @Param("originId") long originId, @Param("revision") int revision);

    /** 한 판의 출력(잠금 읽기, output_no 순). */
    List<LearningEvent> lockOutputs(@Param("userId") long userId, @Param("originKind") String originKind,
                                    @Param("originId") long originId, @Param("revision") int revision);

    int insertEvents(@Param("events") List<LearningEvent> events);

    /** 과목의 살아 있는 이벤트(origin의 현재 판 − 살아 있는 RETRACTED·SUPERSEDES의 대상 − RETRACTED 자신). event_id 오름차순. */
    List<LearningEvent> findLive(@Param("userId") long userId, @Param("courseId") long courseId,
                                 @Param("afterId") long afterId, @Param("limit") int limit);

    /** id로 이벤트와 "살아 있나"(현재 판이고 철회·대체되지 않음 — 조회와 같은 정의). 다른 사용자의 것은 나오지 않는다. */
    List<EventRefRow> findEventRefs(@Param("userId") long userId, @Param("ids") Collection<Long> ids);

    /** 이벤트들의 대상(같은 사용자). */
    List<LearningEvent> findEventsByIds(@Param("userId") long userId, @Param("ids") Collection<Long> ids);

    // ===== 검증 =====

    int countOwnedCourse(@Param("userId") long userId, @Param("courseId") long courseId);

    /** 원본 행의 실제 사용자·과목. 원본이 없으면 null. */
    OriginOwner findOriginOwner(@Param("originKind") String originKind, @Param("originId") long originId);

    List<Long> findTopicIdsInCourse(@Param("userId") long userId, @Param("courseId") long courseId,
                                    @Param("ids") Collection<Long> ids);

    List<SectionRow> findSections(@Param("userId") long userId, @Param("courseId") long courseId,
                                  @Param("ids") Collection<Long> ids);

    List<MaterialRow> findMaterials(@Param("userId") long userId, @Param("courseId") long courseId,
                                    @Param("ids") Collection<Long> ids);

    /** 그 사용자·과목의 수업 회차. */
    List<Long> findSessionIds(@Param("userId") long userId, @Param("courseId") long courseId,
                              @Param("ids") Collection<Long> ids);

    List<Long> findOwnedBookRefs(@Param("userId") long userId, @Param("ids") Collection<Long> ids);

    /** 그 과목에 이 목차 열쇠(SET)를 가진 토픽이 있고, 토픽의 교재 키가 그 ref의 별칭인가. */
    int countTocEntryInCourse(@Param("userId") long userId, @Param("courseId") long courseId,
                              @Param("bookRefId") long bookRefId, @Param("keyHash") String keyHash,
                              @Param("keyLine") int keyLine);

    record OriginRow(Long courseId, Integer currentRevision) {
    }

    record OriginOwner(Long userId, Long courseId) {
    }

    record EventRefRow(Long eventId, Long courseId, String verb, Boolean live) {
    }

    /** linked = 지금 그 과목에 연결된 자료의 구간. */
    record SectionRow(Long sectionId, Integer printedPageStart, Integer printedPageEnd, Boolean linked) {
    }

    record MaterialRow(Long materialId, Boolean linked) {
    }
}
