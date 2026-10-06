package com.jungwoo.project.memo.learning.events;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** learning_event_meta — 일회 작업·전환 시각·충돌 카운터. */
@Mapper
public interface LearningEventMetaMapper {

    String TEXTBOOK_REFS_SEEDED = "textbook_refs_seeded";
    String TEXTBOOK_REF_CONFLICTS = "textbook_ref_conflicts";
    String CUTOVER_AT = "cutover_at";

    String find(@Param("key") String key);

    /** 없을 때만 넣는다. 넣었으면 1. */
    int insertIgnore(@Param("key") String key, @Param("value") String value);

    /** 정수 카운터를 1 올린다(없으면 1로 만든다). */
    int increment(@Param("key") String key);
}
