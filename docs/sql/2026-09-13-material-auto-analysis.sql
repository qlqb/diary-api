-- 자료 자동 분석 · 자료 구간 · 토픽-자료 다대다 · 과제 · 계획 상세 안내.
--
-- 고치는 문제: 업로드한 자료는 "구조 분석" 버튼을 눌러야만 읽혔고, 읽어도 topic 제목·위치만
-- 남아 계획이 실제 문제·예제 내용을 볼 수 없었다. 토픽은 자료 하나(source_material_id)만
-- 가리키고, 과제는 analysis_json 안의 keyDates로만 존재해 확인·완료를 저장할 곳이 없었다.
--
-- 이 파일은 전부 "추가"다. 기존 컬럼·행·ID를 바꾸거나 지우지 않는다. 재실행 가능하게 쓴다
-- (CREATE TABLE IF NOT EXISTS, 컬럼 추가는 information_schema 확인 뒤 실행).
--
-- 기존 docs/sql/*.sql 컨벤션(날짜 파일, 수동 적용)을 따른다. MariaDB 10.4 기준.
-- 2026-08-16 이후 신규 테이블 규칙대로 FK 없음, ENUM 대신 VARCHAR + CHECK. 소유권은 서비스가
-- user_id로 검증한다.
--
-- 적용 순서: 1 → 8 순서대로. 8(백필)은 3·5가 먼저 있어야 한다.
-- 롤백: 새 테이블 DROP + 새 컬럼 DROP (맨 아래 주석). 기존 표는 건드리지 않았으므로 롤백해도
--       자료·토픽·진도·계획은 그대로다. 작업 일시중지는 DROP 없이
--       application.properties의 material.analysis.worker.enabled=false 로 한다.

-- ===============================================================
-- 0. 재실행용 보조 프로시저 (컬럼이 없을 때만 추가)
-- ===============================================================
DROP PROCEDURE IF EXISTS add_column_if_missing;
DELIMITER //
CREATE PROCEDURE add_column_if_missing(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_ddl TEXT)
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND COLUMN_NAME = p_column) THEN
        SET @ddl = CONCAT('ALTER TABLE ', p_table, ' ADD COLUMN ', p_ddl);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END //
DELIMITER ;

-- ===============================================================
-- 1. course_materials.page_count — PDF 물리 페이지 수 / PPTX 슬라이드 수.
--    파일에서 계산한 값만 넣는다. 인쇄 쪽수가 아니다(그건 material_sections.printed_page_*).
-- ===============================================================
CALL add_column_if_missing('course_materials', 'page_count', 'page_count INT NULL AFTER size_bytes');

-- ===============================================================
-- 2. material_text_units — 추출 단위(PDF 페이지 / PPTX 슬라이드 / 텍스트 블록).
--
--    extracted_text는 그대로 둔다(기존 상담·링크 제안이 읽는다). 여기는 "몇 페이지에 무엇이
--    있었나"를 말하기 위한 표다. file_hash를 같이 두어 파일이 바뀌면(같은 material_id라도)
--    다른 행이 된다. 한 페이지가 너무 길면 같은 unit_no로 unit_index만 다른 행이 여러 개다.
-- ===============================================================
CREATE TABLE IF NOT EXISTS material_text_units (
    unit_id      BIGINT       NOT NULL AUTO_INCREMENT,
    user_id      BIGINT       NOT NULL,
    material_id  BIGINT       NOT NULL,
    file_hash    CHAR(64)     NOT NULL,
    unit_index   INT          NOT NULL,
    unit_type    VARCHAR(20)  NOT NULL,
    unit_no      INT          NOT NULL,
    char_count   INT          NOT NULL,
    text         MEDIUMTEXT   NOT NULL,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (unit_id),
    CONSTRAINT chk_material_text_units_type CHECK (unit_type IN ('PDF_PAGE', 'PPTX_SLIDE', 'TEXT_BLOCK')),
    UNIQUE KEY uq_material_text_units_position (material_id, file_hash, unit_index),
    INDEX idx_material_text_units_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ===============================================================
-- 3. material_analysis_jobs — 백그라운드 분석 작업.
--
--    kind CONTENT: 프로젝트와 무관하게 자료 원문에서 구간·문항·날짜 단서를 읽는다.
--    kind LINK   : (자료 × 프로젝트) 맥락에서 기존 토픽과의 연결·구조 변경안을 만든다.
--                  course_id=0 은 "프로젝트 없음"(CONTENT 작업). UNIQUE에 NULL을 쓰면 중복
--                  방지가 안 되므로 0을 쓴다.
--
--    선점(lease): status QUEUED이고 next_run_at이 지났거나, RUNNING인데 lease_until이 지난 행을
--    UPDATE ... WHERE 로 잡는다. 잡을 때마다 lease_token이 1 오르고, 결과 저장은 전부
--    "AND lease_token = ?" 로 대조한다 — 오래된 worker의 늦은 결과는 0행이 되어 버려진다.
-- ===============================================================
CREATE TABLE IF NOT EXISTS material_analysis_jobs (
    job_id           BIGINT       NOT NULL AUTO_INCREMENT,
    user_id          BIGINT       NOT NULL,
    material_id      BIGINT       NOT NULL,
    course_id        BIGINT       NOT NULL DEFAULT 0,
    job_kind         VARCHAR(10)  NOT NULL,
    file_hash        CHAR(64)     NOT NULL,
    analysis_version INT          NOT NULL,
    priority         INT          NOT NULL DEFAULT 10,
    status           VARCHAR(20)  NOT NULL DEFAULT 'QUEUED',
    attempt          INT          NOT NULL DEFAULT 0,
    max_attempts     INT          NOT NULL DEFAULT 3,
    next_run_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_owner      VARCHAR(64)  NULL,
    lease_until      DATETIME     NULL,
    lease_token      BIGINT       NOT NULL DEFAULT 0,
    total_chunks     INT          NULL,
    completed_chunks INT          NOT NULL DEFAULT 0,
    checkpoint_json  LONGTEXT     NULL,
    error_code       VARCHAR(40)  NULL,
    error_message    VARCHAR(500) NULL,
    result_ref_id    BIGINT       NULL,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    finished_at      DATETIME     NULL,
    PRIMARY KEY (job_id),
    CONSTRAINT chk_material_analysis_jobs_kind CHECK (job_kind IN ('CONTENT', 'LINK')),
    CONSTRAINT chk_material_analysis_jobs_status CHECK (status IN
        ('QUEUED', 'RUNNING', 'DONE', 'PARTIAL', 'FAILED', 'UNAVAILABLE', 'PAUSED', 'CANCELLED')),
    CONSTRAINT chk_material_analysis_jobs_checkpoint CHECK (checkpoint_json IS NULL OR JSON_VALID(checkpoint_json)),
    UNIQUE KEY uq_material_analysis_jobs_scope (material_id, course_id, job_kind, file_hash, analysis_version),
    INDEX idx_material_analysis_jobs_pick (status, next_run_at, priority, job_id),
    INDEX idx_material_analysis_jobs_user (user_id, status),
    INDEX idx_material_analysis_jobs_material (material_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ===============================================================
-- 4. material_sections — AI가 식별한 자료 구간. 자료 종류가 아니라 "구간의 역할"이다.
--
--    한 구간은 역할을 여러 개 가질 수 있다(roles_json: ["CONCEPT","EXERCISE"]).
--    unit_start/unit_end 는 물리 단위(PDF 페이지·슬라이드 번호). printed_page_* 는 원문에
--    실제로 찍힌 쪽수가 확인됐을 때만 채운다. 일괄 오프셋으로 계산하지 않는다.
--    dedupe_key 는 (정규화 제목 + 단위 범위) 해시 — 겹친 청크에서 같은 구간이 두 번 나와도
--    한 행이다. 같은 파일(해시)·같은 분석판 안에서만 유일하다.
-- ===============================================================
CREATE TABLE IF NOT EXISTS material_sections (
    section_id           BIGINT        NOT NULL AUTO_INCREMENT,
    user_id              BIGINT        NOT NULL,
    material_id          BIGINT        NOT NULL,
    file_hash            CHAR(64)      NOT NULL,
    analysis_version     INT           NOT NULL,
    chunk_index          INT           NOT NULL,
    unit_type            VARCHAR(20)   NOT NULL,
    unit_start           INT           NOT NULL,
    unit_end             INT           NOT NULL,
    printed_page_start   INT           NULL,
    printed_page_end     INT           NULL,
    section_label        VARCHAR(100)  NULL,
    display_title        VARCHAR(300)  NOT NULL,
    roles_json           LONGTEXT      NOT NULL,
    task_text            VARCHAR(1000) NULL,
    excerpt              TEXT          NULL,
    assignment_cue       TINYINT(1)    NOT NULL DEFAULT 0,
    assignment_quote     VARCHAR(500)  NULL,
    date_candidates_json LONGTEXT      NULL,
    dedupe_key           VARCHAR(160)  NOT NULL,
    status               VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    created_at           DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (section_id),
    CONSTRAINT chk_material_sections_status CHECK (status IN ('ACTIVE', 'SUPERSEDED')),
    CONSTRAINT chk_material_sections_roles CHECK (JSON_VALID(roles_json)),
    CONSTRAINT chk_material_sections_dates CHECK (date_candidates_json IS NULL OR JSON_VALID(date_candidates_json)),
    UNIQUE KEY uq_material_sections_dedupe (material_id, file_hash, analysis_version, dedupe_key),
    INDEX idx_material_sections_material (material_id, status, unit_start),
    INDEX idx_material_sections_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ===============================================================
-- 5. topic_material_links — 토픽 ↔ 자료 구간 다대다.
--
--    section_id = 0 은 "자료 전체"(구간 없이 자료만 가리키는 연결, 백필과 수동 연결).
--    course_topics.source_material_id 는 그대로 둔다 — 최초 출처·호환 정보다.
--    origin BACKFILL_SOURCE 는 기존 source_material_id 를 옮겨 적은 것이다. "모델이 그 본문을
--    새로 읽었다"는 기록이 아니다.
-- ===============================================================
CREATE TABLE IF NOT EXISTS topic_material_links (
    link_id      BIGINT       NOT NULL AUTO_INCREMENT,
    user_id      BIGINT       NOT NULL,
    course_id    BIGINT       NOT NULL,
    topic_id     BIGINT       NOT NULL,
    material_id  BIGINT       NOT NULL,
    section_id   BIGINT       NOT NULL DEFAULT 0,
    role         VARCHAR(30)  NOT NULL,
    locator      VARCHAR(200) NULL,
    origin       VARCHAR(30)  NOT NULL,
    status       VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (link_id),
    CONSTRAINT chk_topic_material_links_origin CHECK (origin IN ('BACKFILL_SOURCE', 'PROPOSAL_APPLIED', 'USER')),
    CONSTRAINT chk_topic_material_links_status CHECK (status IN ('ACTIVE', 'REMOVED')),
    UNIQUE KEY uq_topic_material_links_triple (topic_id, material_id, section_id),
    INDEX idx_topic_material_links_material (material_id, status),
    INDEX idx_topic_material_links_course (course_id, status),
    INDEX idx_topic_material_links_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ===============================================================
-- 6. 트리 버전과 병합/분할 흔적.
--
--    courses.topic_tree_version — 트리를 바꾸는 모든 쓰기(변경안 적용, 기존 apply)가 1씩
--    올린다. 변경안은 만들 때의 값을 base_tree_version 으로 들고 있다가 적용 시 대조한다.
--    course_topics.merged_into_topic_id — 병합으로 ARCHIVED 된 항목이 어디로 갔는가.
--    course_topics.review_note — 병합·분할에서 학습 기록 승계가 애매할 때 남기는 안내.
-- ===============================================================
CALL add_column_if_missing('courses', 'topic_tree_version',
    'topic_tree_version BIGINT NOT NULL DEFAULT 0 AFTER status');
CALL add_column_if_missing('course_topics', 'merged_into_topic_id',
    'merged_into_topic_id BIGINT NULL AFTER user_mark');
CALL add_column_if_missing('course_topics', 'review_note',
    'review_note VARCHAR(200) NULL AFTER merged_into_topic_id');

-- ===============================================================
-- 7. topic_change_proposals — LINK 작업의 결과(자료 정리 변경안).
--
--    ops_json 은 서버가 검증을 마친 작업 목록이다(LINK/ADD/RENAME/MOVE/MERGE/SPLIT).
--    summary_json 은 화면 요약용 개수. 같은 (자료, 프로젝트)에 열린(PROPOSED) 변경안은 하나다.
-- ===============================================================
CREATE TABLE IF NOT EXISTS topic_change_proposals (
    proposal_id        BIGINT       NOT NULL AUTO_INCREMENT,
    user_id            BIGINT       NOT NULL,
    course_id          BIGINT       NOT NULL,
    material_id        BIGINT       NOT NULL,
    job_id             BIGINT       NULL,
    file_hash          CHAR(64)     NOT NULL,
    base_tree_version  BIGINT       NOT NULL,
    status             VARCHAR(20)  NOT NULL DEFAULT 'PROPOSED',
    proposed_guard     TINYINT AS (CASE WHEN status = 'PROPOSED' THEN 1 ELSE NULL END) PERSISTENT,
    summary_json       LONGTEXT     NOT NULL,
    ops_json           LONGTEXT     NOT NULL,
    applied_ops_json   LONGTEXT     NULL,
    model              VARCHAR(60)  NULL,
    created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at        DATETIME     NULL,
    PRIMARY KEY (proposal_id),
    CONSTRAINT chk_topic_change_proposals_status CHECK (status IN
        ('PROPOSED', 'APPLIED', 'DISMISSED', 'CONFLICT', 'STALE', 'EMPTY')),
    CONSTRAINT chk_topic_change_proposals_ops CHECK (JSON_VALID(ops_json)),
    CONSTRAINT chk_topic_change_proposals_summary CHECK (JSON_VALID(summary_json)),
    UNIQUE KEY uq_topic_change_proposals_open (material_id, course_id, proposed_guard),
    INDEX idx_topic_change_proposals_course (course_id, status),
    INDEX idx_topic_change_proposals_user (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ===============================================================
-- 8. course_assignments — 과제. 제목·마감·완료 체크가 전부다.
--
--    confirm_status: CANDIDATE(과제인가요? 대기) / CONFIRMED / NOT_ASSIGNMENT(연습용) /
--                    LATER(나중에 — 과제 아님으로 저장하지 않는다) / DUPLICATE(기존 과제와 같음)
--    due_kind: UNKNOWN(미확인) / NONE(마감 없음) / DATE / DATETIME. 미확인과 없음은 다르다.
--    due_source: SOURCE(원문 명시) / ESTIMATED(상대 표현을 해석한 추정 — 확정 제약으로 쓰지
--                않는다) / USER(사용자 입력). 추정은 due_estimate_json 에 근거와 함께 두고
--                사용자가 [이 날짜 맞아요]를 누르기 전에는 due_date 에 넣지 않는다.
--    title_edited/due_edited: 사용자가 고친 값은 재분석이 덮지 않는다.
--    dedupe_key: 자료 구간에서 나온 과제는 "m{material}:{section dedupe}" — 재분석에서 같은
--                구간이 다시 나와도 같은 행이다. 제목만 같은 다른 자료의 과제는 별개 행이고,
--                모델이 같다고 본 경우 DUPLICATE + duplicate_of_assignment_id 로 표시만 한다.
--    자료를 지워도 이 행은 남는다(확정 과제는 사용자 데이터다).
-- ===============================================================
CREATE TABLE IF NOT EXISTS course_assignments (
    assignment_id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_id                    BIGINT       NOT NULL,
    course_id                  BIGINT       NULL,
    material_id                BIGINT       NULL,
    section_id                 BIGINT       NULL,
    topic_id                   BIGINT       NULL,
    title                      VARCHAR(300) NOT NULL,
    source_quote               VARCHAR(500) NULL,
    confirm_status             VARCHAR(20)  NOT NULL DEFAULT 'CANDIDATE',
    due_kind                   VARCHAR(10)  NOT NULL DEFAULT 'UNKNOWN',
    due_date                   DATE         NULL,
    due_at                     DATETIME     NULL,
    due_source                 VARCHAR(20)  NULL,
    due_quote                  VARCHAR(300) NULL,
    due_estimate_json          LONGTEXT     NULL,
    completed_at               DATETIME     NULL,
    title_edited               TINYINT(1)   NOT NULL DEFAULT 0,
    due_edited                 TINYINT(1)   NOT NULL DEFAULT 0,
    duplicate_of_assignment_id BIGINT       NULL,
    dedupe_key                 VARCHAR(160) NOT NULL,
    version                    BIGINT       NOT NULL DEFAULT 0,
    created_at                 DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                 DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (assignment_id),
    CONSTRAINT chk_course_assignments_confirm CHECK (confirm_status IN
        ('CANDIDATE', 'CONFIRMED', 'NOT_ASSIGNMENT', 'LATER', 'DUPLICATE')),
    CONSTRAINT chk_course_assignments_due_kind CHECK (due_kind IN ('UNKNOWN', 'NONE', 'DATE', 'DATETIME')),
    CONSTRAINT chk_course_assignments_due_source CHECK (due_source IS NULL OR due_source IN ('SOURCE', 'ESTIMATED', 'USER')),
    CONSTRAINT chk_course_assignments_due_shape CHECK (
        (due_kind = 'DATE' AND due_date IS NOT NULL AND due_at IS NULL)
        OR (due_kind = 'DATETIME' AND due_at IS NOT NULL)
        OR (due_kind IN ('UNKNOWN', 'NONE') AND due_date IS NULL AND due_at IS NULL)),
    CONSTRAINT chk_course_assignments_estimate CHECK (due_estimate_json IS NULL OR JSON_VALID(due_estimate_json)),
    UNIQUE KEY uq_course_assignments_dedupe (user_id, dedupe_key),
    INDEX idx_course_assignments_course (user_id, course_id, confirm_status),
    INDEX idx_course_assignments_due (user_id, confirm_status, due_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ===============================================================
-- 9. plan_item_details — 계획 항목의 「자세히」 안내.
--
--    evidence_version 은 (항목 제목·설명 + 인용한 자료 구간 id·해시)의 해시다. 원문이나 항목이
--    바뀌면 키가 달라져 이전 안내는 STALE 로 표시되고 새 안내를 만든다. 사용자가 고친
--    user_text 는 보존한다.
-- ===============================================================
CREATE TABLE IF NOT EXISTS plan_item_details (
    detail_id         BIGINT        NOT NULL AUTO_INCREMENT,
    user_id           BIGINT        NOT NULL,
    proposal_item_id  BIGINT        NOT NULL,
    evidence_version  VARCHAR(64)   NOT NULL,
    steps_json        LONGTEXT      NOT NULL,
    user_text         VARCHAR(2000) NULL,
    status            VARCHAR(20)   NOT NULL DEFAULT 'CURRENT',
    model             VARCHAR(60)   NULL,
    created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (detail_id),
    CONSTRAINT chk_plan_item_details_status CHECK (status IN ('CURRENT', 'STALE')),
    CONSTRAINT chk_plan_item_details_steps CHECK (JSON_VALID(steps_json)),
    UNIQUE KEY uq_plan_item_details_version (proposal_item_id, evidence_version),
    INDEX idx_plan_item_details_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ===============================================================
-- 10. material_analysis_controls — 사용자별 자동 분석 일시중지.
-- ===============================================================
CREATE TABLE IF NOT EXISTS material_analysis_controls (
    user_id     BIGINT     NOT NULL,
    paused      TINYINT(1) NOT NULL DEFAULT 0,
    paused_at   DATETIME   NULL,
    updated_at  DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ===============================================================
-- 11. 백필 — 기존 토픽의 최초 출처를 링크 1행으로. 중복 없이, 재실행 가능.
-- ===============================================================
INSERT INTO topic_material_links (user_id, course_id, topic_id, material_id, section_id, role, locator, origin, status)
SELECT t.user_id, t.course_id, t.topic_id, t.source_material_id, 0, 'SOURCE', t.source_locator, 'BACKFILL_SOURCE', 'ACTIVE'
  FROM course_topics t
 WHERE t.source_material_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM topic_material_links l
                    WHERE l.topic_id = t.topic_id AND l.material_id = t.source_material_id AND l.section_id = 0);

DROP PROCEDURE IF EXISTS add_column_if_missing;

-- ===============================================================
-- 적용 확인 (실행 안 함)
-- ===============================================================
-- 백필 행 수 = source_material_id 가 있는 토픽 수:
--   SELECT (SELECT COUNT(*) FROM course_topics WHERE source_material_id IS NOT NULL) AS topics_with_source,
--          (SELECT COUNT(*) FROM topic_material_links WHERE origin = 'BACKFILL_SOURCE') AS backfilled;
-- 기존 행 보존(적용 전후 같아야 한다):
--   SELECT COUNT(*) FROM course_topics; SELECT COUNT(*) FROM topic_progress; SELECT COUNT(*) FROM plan_versions;
--   SELECT COUNT(*) FROM course_material_analyses; SELECT COUNT(*) FROM material_links;
-- 작업 표는 비어 있어야 정상이다 — 서버가 뜬 뒤 폴러가 채운다:
--   SELECT status, COUNT(*) FROM material_analysis_jobs GROUP BY status;
--
-- 롤백 (데이터 손실 있음 — 새 표만):
--   DROP TABLE plan_item_details, course_assignments, topic_change_proposals, topic_material_links,
--              material_sections, material_analysis_jobs, material_text_units, material_analysis_controls;
--   ALTER TABLE course_topics DROP COLUMN review_note, DROP COLUMN merged_into_topic_id;
--   ALTER TABLE courses DROP COLUMN topic_tree_version;
--   ALTER TABLE course_materials DROP COLUMN page_count;
