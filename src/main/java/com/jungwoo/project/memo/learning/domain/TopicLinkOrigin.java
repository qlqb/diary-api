package com.jungwoo.project.memo.learning.domain;

/** topic_material_links.origin — 이 연결이 어떻게 생겼는가. */
public enum TopicLinkOrigin {
    /** 기존 course_topics.source_material_id를 옮겨 적은 것. 모델이 본문을 읽었다는 기록이 아니다. */
    BACKFILL_SOURCE,
    /** 자료 정리 변경안을 사용자가 적용해서 생겼다. */
    PROPOSAL_APPLIED,
    /** 사용자가 직접 연결했다. */
    USER
}
