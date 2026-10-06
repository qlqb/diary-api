package com.jungwoo.project.memo.learning.events;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/** 이벤트를 만들 때 원본·원천을 읽는 조회(쓰기 없음, 해결 연결만 쓴다). */
@Mapper
public interface EventSourceMapper {

    List<TopicRow> findTopics(@Param("userId") long userId, @Param("courseId") long courseId,
                              @Param("ids") Collection<Long> ids);

    /** 토픽에 연결된 구간(살아 있는 연결, 자료 전체 제외). */
    List<TopicSection> findTopicSections(@Param("userId") long userId, @Param("courseId") long courseId,
                                         @Param("topicIds") Collection<Long> topicIds);

    /** 사진 자료의 구간(사진 한 장 = 구간 하나). 없으면 null. */
    Long findPhotoSection(@Param("userId") long userId, @Param("materialId") long materialId);

    /** origin의 지금 판 출력(잠금 없이). */
    List<LearningEvent> findCurrentOutputs(@Param("userId") long userId, @Param("originKind") String originKind,
                                           @Param("originId") long originId);

    /**
     * 과목의 살아 있는 출력 전체(종류 하나). 동기화는 과목 잠금 뒤 READ_COMMITTED 트랜잭션에서 읽는다(최신 커밋을 본다 — 잠금 읽기의
     * 간격 잠금이 다른 과목의 origin 삽입과 교착하지 않게).
     */
    List<LearningEvent> findCurrentOutputsOfCourse(@Param("userId") long userId, @Param("courseId") long courseId,
                                                   @Param("originKind") String originKind);

    /** 과목의 그 종류 origin과 지금 판(판 0 포함). */
    List<OriginRevision> findOriginRevisions(@Param("userId") long userId, @Param("courseId") long courseId,
                                             @Param("originKind") String originKind);

    record OriginRevision(Long originId, Integer currentRevision) {
    }

    List<Long> findOriginIdsOfCourse(@Param("userId") long userId, @Param("courseId") long courseId,
                                     @Param("originKind") String originKind);

    ExecutionFacts findExecutionFacts(@Param("userId") long userId, @Param("recordId") long recordId);

    /** 과목의 기억 행 중 이벤트가 될 수 있는 것(사실 종류가 있거나 자기 점검) — 상태와 무관하게. */
    List<ContextRow> findEventContexts(@Param("userId") long userId, @Param("courseId") long courseId);

    List<ResolutionLink> findResolutionLinks(@Param("userId") long userId, @Param("courseId") long courseId);

    int upsertResolutionLink(@Param("userId") long userId, @Param("difficultyId") long difficultyId,
                             @Param("resolverId") long resolverId);

    int moveResolutionLinks(@Param("userId") long userId, @Param("fromResolverId") long fromResolverId,
                            @Param("toResolverId") long toResolverId);

    List<ExclusionRow> findExclusions(@Param("userId") long userId, @Param("courseId") long courseId);

    List<ClassRow> findClassRows(@Param("userId") long userId, @Param("courseId") long courseId);

    record ExclusionRow(Long exclusionId, Long topicId, String label, String status, LocalDateTime createdAt) {
    }

    record ClassRow(Long progressId, Long topicId, Integer classSeq, Integer weekNo, LocalDateTime createdAt) {
    }

    /** 사용자 메시지가 속한 대화의 과목. 과목 상담이 아니면 null. */
    Long findCourseOfMessage(@Param("userId") long userId, @Param("messageId") long messageId);

    /** 이 메시지에 붙은 사진 자료. */
    List<Long> findPhotosOfMessage(@Param("userId") long userId, @Param("messageId") long messageId);

    /** 자료 연결 행(과목·종류·연결 시각). 없으면 null. */
    MaterialLinkRow findMaterialLink(@Param("userId") long userId, @Param("linkId") long linkId);

    /** (자료, 과목)의 연결 행 id. 없으면 null. */
    Long findMaterialLinkId(@Param("userId") long userId, @Param("materialId") long materialId,
                            @Param("courseId") long courseId);

    record MaterialLinkRow(Long linkId, Long materialId, Long courseId, String materialType, LocalDateTime linkedAt,
                           String origin) {
    }

    TopicState findTopicState(@Param("userId") long userId, @Param("topicId") long topicId);

    /** 토픽의 과목·표식·진도(행이 없으면 진도 칸은 null). */
    record TopicState(Long topicId, Long courseId, String userMark, Long progressId, String progressStatus) {
    }

    record TopicRow(Long topicId, String tocKeyState, String tocKeyHash, Integer tocKeyLine, String sourceTextbookKey) {
    }

    record TopicSection(Long topicId, Long sectionId) {
    }

    /** 실행 기록과 그 항목. */
    record ExecutionFacts(Long recordId, Long executionItemId, Long courseId, Long topicId, String outcome,
                          Integer completionPercent, String supportLevel, String blockerKind, String stuckStep,
                          LocalDateTime endedAt, LocalDateTime recordedAt, LocalDateTime createdAt) {
    }

    record ContextRow(Long contextId, Long courseId, String status, String sourceType, String evidenceType,
                      String factKind, String factLabel, String helpLevel, String selfLevel, Long topicId,
                      Long topicPhotoId, Long sectionId, LocalDateTime saidAt, LocalDateTime confirmedAt,
                      LocalDateTime createdAt, Long sourceMessageId) {
    }

    record ResolutionLink(Long difficultyId, Long resolverId) {
    }
}
