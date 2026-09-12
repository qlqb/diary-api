-- 학습 자료 → Material/Learning/Planning 멀티에이전트 vertical flow.
--
-- 기존 docs/sql/*.sql 컨벤션(날짜 파일, 수동 적용)을 따른다. Flyway/Liquibase는 도입하지 않는다.
-- 엔진은 MariaDB 10.4 기준(FK 미사용, ENUM 대신 VARCHAR + CHECK). 서비스 코드에서
-- user_id 소유권을 검증한다 — 이 마이그레이션의 모든 신규 테이블도 동일 원칙을 따른다.

-- ===============================================================
-- 1. courses: 사용자가 만드는 과목. Material Agent가 강의계획서에서 교재 정보를 채운다.
-- ===============================================================
CREATE TABLE courses (
    course_id         BIGINT       NOT NULL AUTO_INCREMENT,
    user_id           BIGINT       NOT NULL,
    title             VARCHAR(200) NOT NULL,
    -- 강의계획서 분석으로 채워지기 전에는 전부 NULL일 수 있다. AI가 목차 근거 없이
    -- 지어내지 않는다는 원칙을 지키기 위해 이 필드들도 "찾았다"가 확실할 때만 채운다.
    textbook_title    VARCHAR(300) NULL,
    textbook_author   VARCHAR(200) NULL,
    textbook_publisher VARCHAR(200) NULL,
    textbook_isbn     VARCHAR(50)  NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (course_id),
    CONSTRAINT chk_courses_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    INDEX idx_courses_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ===============================================================
-- 2. course_materials: 업로드한 원본 파일의 메타데이터. 파일 자체는 DB에 넣지 않고
--    로컬 디스크(개발 환경 기준 storage.upload-dir)에 저장하고 여기엔 경로만 남긴다.
-- ===============================================================
CREATE TABLE course_materials (
    material_id        BIGINT       NOT NULL AUTO_INCREMENT,
    user_id             BIGINT       NOT NULL,
    course_id           BIGINT       NOT NULL,
    material_type       VARCHAR(30)  NOT NULL,
    original_filename   VARCHAR(255) NOT NULL,
    -- 원본 파일명을 그대로 디스크 경로에 쓰지 않는다 — UUID 기반의 안전한 파일명.
    stored_filename      VARCHAR(255) NOT NULL,
    content_type          VARCHAR(100) NULL,
    size_bytes             BIGINT       NOT NULL,
    -- PENDING: 업로드 직후, 추출 전 / SUCCESS / FAILED_NO_TEXT(스캔 이미지 PDF 등, OCR 미지원)
    -- / FAILED(그 외 파싱 오류)
    extraction_status      VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    -- 추출된 원문 텍스트. Material Agent 분석 입력으로만 쓰고, 로그에는 남기지 않는다.
    extracted_text          LONGTEXT     NULL,
    extraction_error        VARCHAR(500) NULL,
    created_at               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (material_id),
    CONSTRAINT chk_course_materials_type
        CHECK (material_type IN ('SYLLABUS', 'TEXTBOOK_TOC', 'PROFESSOR_SLIDE', 'OTHER')),
    CONSTRAINT chk_course_materials_extraction_status
        CHECK (extraction_status IN ('PENDING', 'SUCCESS', 'FAILED_NO_TEXT', 'FAILED')),
    INDEX idx_course_materials_course (course_id),
    INDEX idx_course_materials_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ===============================================================
-- 3. course_material_analyses: Material Agent의 분석 결과 초안.
--    DRAFT 상태는 course_topics/courses에 전혀 영향을 주지 않는다 — apply()가 호출된 순간에만
--    확정 데이터가 생긴다.
-- ===============================================================
CREATE TABLE course_material_analyses (
    analysis_id      BIGINT       NOT NULL AUTO_INCREMENT,
    user_id           BIGINT       NOT NULL,
    course_id         BIGINT       NOT NULL,
    material_id        BIGINT       NOT NULL,
    status              VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    -- AI가 생성한 원본 구조 (course 필드 후보 + topic 트리 후보, 각 노드에 provenance 포함).
    analysis_json        LONGTEXT     NOT NULL,
    -- 사용자가 검토 중 수정한 내용. apply 시점엔 edited_json이 있으면 그것을, 없으면
    -- analysis_json을 그대로 확정한다.
    edited_json           LONGTEXT     NULL,
    model                  VARCHAR(60)  NULL,
    failure_reason          VARCHAR(500) NULL,
    created_at               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    applied_at                DATETIME     NULL,
    PRIMARY KEY (analysis_id),
    CONSTRAINT chk_course_material_analyses_status
        CHECK (status IN ('DRAFT', 'APPLIED', 'DISMISSED', 'FAILED')),
    INDEX idx_course_material_analyses_course (course_id),
    INDEX idx_course_material_analyses_material (material_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ===============================================================
-- 4. course_topics: 확정된(Apply된) 학습 구조 트리. source_type으로 원문 근거와 AI가
--    학습 편의를 위해 세분화한 항목을 구분한다 — 절대 섞어서 "원문에 있었다"고 저장하지 않는다.
-- ===============================================================
CREATE TABLE course_topics (
    topic_id            BIGINT       NOT NULL AUTO_INCREMENT,
    user_id               BIGINT       NOT NULL,
    course_id             BIGINT       NOT NULL,
    parent_topic_id        BIGINT       NULL,
    title                    VARCHAR(300) NOT NULL,
    order_index               INT          NOT NULL DEFAULT 0,
    -- SOURCE: 업로드한 자료 원문에 실제로 있던 항목 / AI_DERIVED: AI가 학습 편의를 위해
    -- 세분화해 추가한 항목 (원문에 없음).
    source_type                VARCHAR(20)  NOT NULL,
    source_material_id           BIGINT       NULL,
    -- 예: "3.2절", "p.12", "slide 5". 근거를 특정할 수 없으면 NULL.
    source_locator                 VARCHAR(200) NULL,
    status                          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at                       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (topic_id),
    CONSTRAINT chk_course_topics_source_type CHECK (source_type IN ('SOURCE', 'AI_DERIVED')),
    CONSTRAINT chk_course_topics_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    INDEX idx_course_topics_course (course_id),
    INDEX idx_course_topics_parent (parent_topic_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ===============================================================
-- 5. topic_progress: topic별 학습 진행 상태. "완료 체크 = 완전 이해"로 해석하지 않기 위해
--    상태를 NOT_STARTED/IN_PROGRESS/LEARNED 세 단계로만 단순하게 둔다.
-- ===============================================================
CREATE TABLE topic_progress (
    topic_progress_id BIGINT   NOT NULL AUTO_INCREMENT,
    user_id             BIGINT   NOT NULL,
    topic_id             BIGINT   NOT NULL,
    status                 VARCHAR(20) NOT NULL DEFAULT 'NOT_STARTED',
    last_studied_at         DATETIME NULL,
    last_reviewed_at         DATETIME NULL,
    review_count               INT      NOT NULL DEFAULT 0,
    created_at                   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (topic_progress_id),
    CONSTRAINT chk_topic_progress_status CHECK (status IN ('NOT_STARTED', 'IN_PROGRESS', 'LEARNED')),
    UNIQUE KEY uq_topic_progress_user_topic (user_id, topic_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ===============================================================
-- 6. topic_learning_events: topic_progress 상태가 왜 바뀌었는지의 이력. 복습 추천 판단과
--    "실행 완료가 다음 추천에 반영된다"는 요구를 만족시키는 근거 데이터.
-- ===============================================================
CREATE TABLE topic_learning_events (
    event_id           BIGINT      NOT NULL AUTO_INCREMENT,
    user_id              BIGINT      NOT NULL,
    topic_id              BIGINT      NOT NULL,
    -- 이 이벤트를 유발한 실행 조각(있다면). FK는 프로젝트 방침상 두지 않는다.
    execution_item_id      BIGINT      NULL,
    event_type                VARCHAR(30) NOT NULL,
    from_status                 VARCHAR(20) NULL,
    to_status                     VARCHAR(20) NULL,
    note                            VARCHAR(500) NULL,
    created_at                       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (event_id),
    CONSTRAINT chk_topic_learning_events_type
        CHECK (event_type IN ('STATUS_CHANGED', 'EXECUTION_COMPLETED', 'AI_SUGGESTED_CHANGE')),
    INDEX idx_topic_learning_events_topic (topic_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ===============================================================
-- 7. study_recommendations: Learning Agent가 만드는 구조화된 "다음 학습" 후보.
--    시간 배치는 하지 않는다 (WHAT이지 WHEN이 아니다) — Planning Agent가 이 행을 입력으로 받는다.
-- ===============================================================
CREATE TABLE study_recommendations (
    recommendation_id         BIGINT       NOT NULL AUTO_INCREMENT,
    user_id                     BIGINT       NOT NULL,
    course_id                     BIGINT       NOT NULL,
    topic_id                        BIGINT       NOT NULL,
    -- NEW_LEARNING / REVIEW / CONTINUE / SKIP. SKIP은 진행 상태가 아니라 "이번엔 안 해도
    -- 된다"는 추천 판단이다 (topic_progress.status와는 다른 축).
    activity_type                     VARCHAR(20)  NOT NULL,
    recommended_minutes_min             INT          NULL,
    recommended_minutes_ideal             INT          NULL,
    priority                                VARCHAR(10)  NOT NULL,
    reason                                    VARCHAR(1000) NOT NULL,
    -- SUGGESTED: 방금 생성 / SENT_TO_PLANNING: Planning Agent에 넘겨짐 /
    -- PLANNED: 실제 ExecutionItem으로 배치됨(Apply 완료) / DISMISSED: 사용자가 무시
    status                                      VARCHAR(20)  NOT NULL DEFAULT 'SUGGESTED',
    source_conversation_id                        BIGINT       NULL,
    source_message_id                               BIGINT       NULL,
    created_at                                        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (recommendation_id),
    CONSTRAINT chk_study_recommendations_activity_type
        CHECK (activity_type IN ('NEW_LEARNING', 'REVIEW', 'CONTINUE', 'SKIP')),
    CONSTRAINT chk_study_recommendations_priority
        CHECK (priority IN ('MUST', 'SHOULD', 'OPTIONAL')),
    CONSTRAINT chk_study_recommendations_status
        CHECK (status IN ('SUGGESTED', 'SENT_TO_PLANNING', 'PLANNED', 'DISMISSED')),
    INDEX idx_study_recommendations_user (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ===============================================================
-- 8. execution_items에 topic_id 연결 컬럼 추가. Planning Agent가 만든 제안을 Apply해서
--    생긴 ExecutionItem이 어떤 topic의 학습인지 알아야 완료 시 topic_progress를 갱신할 수 있다.
--    학습과 무관한 기존 ExecutionItem은 전부 NULL.
-- ===============================================================
ALTER TABLE execution_items
    ADD COLUMN topic_id BIGINT NULL AFTER plan_item_id;

-- ===============================================================
-- 9. ai_conversations.scope에 새 Agent 스코프 추가.
-- ===============================================================
ALTER TABLE ai_conversations
    DROP CONSTRAINT chk_ai_conversations_scope,
    ADD CONSTRAINT chk_ai_conversations_scope
        CHECK (scope IN ('PLAN', 'TODAY', 'EXECUTION', 'CONTEXT', 'MIXED', 'MATERIAL', 'LEARNING', 'PLANNING'));

-- ===============================================================
-- 10. ai_usage_logs: Agent 호출 추적 확장. feature 컬럼은 이미 자유 VARCHAR라 새 값
--     (MATERIAL_ANALYSIS/LEARNING_CHAT/LEARNING_RECOMMENDATION/PLANNING_CHAT/SCHEDULE_PROPOSAL)을
--     그대로 쓸 수 있다. workflow_id/agent_run_id/latency_ms만 추가한다.
-- ===============================================================
ALTER TABLE ai_usage_logs
    ADD COLUMN workflow_id  VARCHAR(64) NULL AFTER conversation_id,
    ADD COLUMN agent_run_id VARCHAR(64) NULL AFTER workflow_id,
    ADD COLUMN latency_ms   INT         NULL AFTER output_tokens;

-- ===============================================================
-- 11. study_recommendations에 Planning Agent가 이 추천으로 만든 ai_proposals 행을
--     역참조할 수 있게 한다. apply() 이후 생성된 execution_item에 topic_id를 연결하려면
--     "이 proposal이 어떤 topic 추천에서 나왔는지"를 알아야 한다.
-- ===============================================================
ALTER TABLE study_recommendations
    ADD COLUMN proposal_id BIGINT NULL AFTER topic_id;
