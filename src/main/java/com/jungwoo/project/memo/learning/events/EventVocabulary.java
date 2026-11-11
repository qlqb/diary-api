package com.jungwoo.project.memo.learning.events;

/**
 * 학습 이벤트의 말(설계 20번 §6). 값은 DB CHECK 목록과 같다.
 */
public final class EventVocabulary {

    private EventVocabulary() {
    }

    /** 누가 한 일인가. CLASS = 교수·수업 쪽 사건(휴강·범위 공지), SYSTEM = 서버 관찰. */
    public enum Actor { ME, CLASS, SYSTEM }

    public enum Verb {
        // 수업
        RELEASED, COVERED_IN_CLASS, CANCELLED, SESSION_MOVED, SCOPE_ANNOUNCED, SCOPE_EXCLUDED, PLAN_CHANGED,
        // 나
        STUDIED, ATTEMPTED, STUCK, RESOLVED, ABSENT, SELF_ASSESSED, SUBMITTED,
        // 공통
        STATED, RETRACTED, SUPERSEDES
    }

    public enum ObjectKind { SECTION, ITEM, TOC_ENTRY, PAGE, WEEK, SESSION, MATERIAL, COURSE }

    /**
     * 근거의 종류. STATED = 사용자 말(18번 검증 통과), INPUT = 화면 입력, LOGGED = 실행 기록, RULE = 파일명·머리 표시,
     * APPROX = 근사·내용 대응, INFERRED = AI 추론.
     */
    public enum Evidence { STATED, INPUT, LOGGED, RULE, APPROX, INFERRED }

    /** 이벤트를 만든 원본. origin_id가 가리키는 표가 종류마다 다르다. */
    public enum OriginKind {
        /** execution_records.execution_record_id */
        EXECUTION_RECORD,
        /** topic_progress.topic_progress_id */
        TOPIC_PROGRESS,
        /** course_topics.topic_id (사용자 표식) */
        TOPIC_MARK,
        /** user_contexts.context_id */
        USER_CONTEXT,
        /** 해결된 막힘 user_contexts.context_id (learning_resolution_links) */
        RESOLUTION,
        /** course_scope_exclusions.exclusion_id */
        SCOPE_EXCLUSION,
        /** topic_class_progress.progress_id */
        CLASS_PROGRESS,
        /** ai_messages.message_id (사진이 붙은 사용자 메시지) */
        MESSAGE_PHOTO,
        /** material_links.link_id */
        MATERIAL_LINK,
        /** class_sessions.session_id (수업 확인 입력) */
        CLASS_INPUT,
        /** routine_exceptions.routine_exception_id */
        SCHEDULE_EXCEPTION,
        /** 테스트 전용 */
        TEST
    }
}
