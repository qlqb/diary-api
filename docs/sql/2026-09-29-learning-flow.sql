-- =====================================================================
-- 2026-09-29 계획 이해·학습 실행·교재와 실제 수업 반영
-- =====================================================================
--
-- 앞선 마이그레이션: docs/sql/2026-09-22-material-week-assignments.sql. 이 파일은 그 뒤에 적용한다.
-- 설계: docs/product/16-learning-flow.md, DB: docs/product/05-database.md §22.
--
-- 전부 추가형이다(새 표, NULL 허용·기본값 열). 기존 행을 바꾸지 않는다. 다시 돌려도 된다(IF NOT EXISTS,
-- MariaDB 10.4에서 확인).
--
-- 배포 순서: 이 마이그레이션 → 서버 → 화면. 새 서버는 새 열을 읽으므로 적용하지 않은 DB에서는 실행 기록·교재·정리
-- 조회가 "Unknown column"으로 실패한다. 옛 서버는 새 열·표를 모르므로 적용 뒤에도 그대로 동작한다.
--
-- 되돌리기(새 서버를 내린 뒤):
--   DROP TABLE IF EXISTS plan_item_start_helps, material_textbook_extracts, topic_class_progress, course_scope_exclusions;
--   ALTER TABLE execution_records DROP CONSTRAINT IF EXISTS chk_execution_records_support_level,
--       DROP COLUMN IF EXISTS support_level, DROP COLUMN IF EXISTS stuck_step;
--   ALTER TABLE courses DROP COLUMN IF EXISTS textbook_edition, DROP COLUMN IF EXISTS textbook_info_source,
--       DROP COLUMN IF EXISTS textbook_info_material_id, DROP COLUMN IF EXISTS textbook_info_updated_at;
--   ALTER TABLE project_tidy_jobs DROP COLUMN IF EXISTS user_request_json;
--   ALTER TABLE project_tidy_proposals DROP COLUMN IF EXISTS origin, DROP COLUMN IF EXISTS user_request_json;
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 실행 기록: 어떻게 해냈는가(선택)
-- ---------------------------------------------------------------------
-- support_level  SOLO   = 혼자 수행했다(그 활동을 독립적으로 해낸 근거)
--                GUIDED = 설명·예제를 보고 수행했다(그 활동에 지원이 필요했다)
--                NULL   = 남기지 않았다. "모름"이지 "혼자 못 함"이 아니다
-- stuck_step     막힌 단계(사용자가 고르거나 적은 글). 활동 하나의 사실이지 과목 전체의 판정이 아니다
ALTER TABLE execution_records
    ADD COLUMN IF NOT EXISTS support_level VARCHAR(10) NULL AFTER blocker_kind;

ALTER TABLE execution_records
    ADD COLUMN IF NOT EXISTS stuck_step VARCHAR(300) NULL AFTER support_level;

ALTER TABLE execution_records
    ADD CONSTRAINT IF NOT EXISTS chk_execution_records_support_level
        CHECK (support_level IS NULL OR support_level IN ('SOLO', 'GUIDED'));

-- ---------------------------------------------------------------------
-- 2. 시작 도움("어디서 시작할지 모르겠어요")
-- ---------------------------------------------------------------------
-- 항목의 범위·시간·마감을 바꾸지 않는다. 그 항목의 첫 행동을 구체화한 안내만 남긴다.
-- 열쇠는 그 항목을 만든 제안 항목(proposal_item_id)이다 — 초안에서 받은 도움이 적용 뒤에도, 부분 수행으로 남은
-- 조각에도 이어진다. 직접 만든 항목(제안 원본이 없음)은 execution_item_id로 잇는다.
-- evidence_version은 도움을 만들 때의 항목 글·인용 구간 해시다. 바뀌면 화면이 "예전 안내"로 표시한다.
CREATE TABLE IF NOT EXISTS plan_item_start_helps (
    help_id           BIGINT        NOT NULL AUTO_INCREMENT,
    user_id           BIGINT        NOT NULL,
    proposal_item_id  BIGINT        NULL,
    execution_item_id BIGINT        NULL,
    evidence_version  VARCHAR(64)   NOT NULL,
    request_kind      VARCHAR(20)   NOT NULL,
    request_text      VARCHAR(500)  NULL,
    help_json         LONGTEXT      NOT NULL,
    model             VARCHAR(100)  NULL,
    created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (help_id),
    KEY idx_plan_item_start_helps_proposal (user_id, proposal_item_id, created_at),
    KEY idx_plan_item_start_helps_item (user_id, execution_item_id, created_at),
    CONSTRAINT chk_plan_item_start_helps_target CHECK (proposal_item_id IS NOT NULL OR execution_item_id IS NOT NULL),
    CONSTRAINT chk_plan_item_start_helps_kind CHECK (request_kind IN ('WHERE_TO_START', 'TOO_BIG', 'OTHER')),
    CONSTRAINT chk_plan_item_start_helps_json CHECK (JSON_VALID(help_json))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;

-- ---------------------------------------------------------------------
-- 3. 교재: 어느 책인가 / 그 책의 목차
-- ---------------------------------------------------------------------
-- courses의 교재 칸에 판 정보와 "누가 채웠나"를 더한다.
--   textbook_info_source  USER(사용자가 직접 적거나 고침) / MATERIAL(자료에서 찾은 값을 사용자가 적용) / NULL(모름·예전 값)
--   textbook_info_material_id  MATERIAL일 때 그 값을 찾은 자료
-- 자료에서 찾은 후보가 사용자 값과 다르면 조용히 덮지 않는다 — 화면이 차이를 보이고 사용자가 고른 칸만 바뀐다.
ALTER TABLE courses ADD COLUMN IF NOT EXISTS textbook_edition VARCHAR(100) NULL AFTER textbook_isbn;
ALTER TABLE courses ADD COLUMN IF NOT EXISTS textbook_info_source VARCHAR(10) NULL AFTER textbook_edition;
ALTER TABLE courses ADD COLUMN IF NOT EXISTS textbook_info_material_id BIGINT NULL AFTER textbook_info_source;
ALTER TABLE courses ADD COLUMN IF NOT EXISTS textbook_info_updated_at DATETIME NULL AFTER textbook_info_material_id;
ALTER TABLE courses ADD CONSTRAINT IF NOT EXISTS chk_courses_textbook_info_source
    CHECK (textbook_info_source IS NULL OR textbook_info_source IN ('USER', 'MATERIAL'));

-- 자료에서 읽어 낸 교재 단서(서지·목차). 모델이 아니라 규칙으로 원문 텍스트에서 읽는다 — 목차를 상상해 만들지 않는다.
-- (material_id, file_hash, extractor_version)마다 한 행: 재분석·재시도가 같은 파일을 다시 읽어도 행이 늘지 않는다.
-- 파일이 바뀌면(해시) 새 행을 읽고, 옛 행은 쓰지 않는다. 자료를 지우면 조회에서 빠진다.
--   book_json  {isbn, title, author, publisher, edition: {value, unit, quote}} — 원문 줄(quote)과 그 단위(쪽) 그대로
--   toc_json   {entries:[{level, number, title, page, unit}], fromUnit, toUnit}
CREATE TABLE IF NOT EXISTS material_textbook_extracts (
    extract_id        BIGINT       NOT NULL AUTO_INCREMENT,
    user_id           BIGINT       NOT NULL,
    material_id       BIGINT       NOT NULL,
    file_hash         VARCHAR(64)  NOT NULL,
    extractor_version INT          NOT NULL,
    book_json         LONGTEXT     NULL,
    toc_json          LONGTEXT     NULL,
    toc_entry_count   INT          NOT NULL DEFAULT 0,
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (extract_id),
    UNIQUE KEY uk_material_textbook_extracts (material_id, file_hash, extractor_version),
    KEY idx_material_textbook_extracts_user (user_id, material_id),
    CONSTRAINT chk_material_textbook_extracts_book CHECK (book_json IS NULL OR JSON_VALID(book_json)),
    CONSTRAINT chk_material_textbook_extracts_toc CHECK (toc_json IS NULL OR JSON_VALID(toc_json))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;

-- ---------------------------------------------------------------------
-- 4. 실제 수업 진행과 계획 범위 정정 (사용자가 확인한 것만)
-- ---------------------------------------------------------------------
-- 교재상의 위치(course_topics의 부모·순서)와 실제 수업 순서·주차는 다른 데이터다. 순서만 달랐다면 트리를 바꾸거나
-- 항목을 복제하지 않고 이 표만 바꾼다. 재분석·자동 분석·정리는 이 표를 쓰지 않는다 — 쓰는 길은 사용자가 적용한
-- 정리안(CLASS 작업) 하나뿐이다. 예정 진도(강의계획서)는 여기 넣지 않는다(자료 구간의 SCHEDULE로 따로 읽는다).
--   class_seq  실제로 다룬 순서(1부터). NULL이면 순서는 모르고 주차만 안다
--   week_no    실제로 다룬 주차(1~30). NULL이면 주차는 모른다
-- 항목이 병합으로 보관되면 그 행은 남지만 읽을 때 빠진다(기록을 옮겨 단정하지 않는다).
CREATE TABLE IF NOT EXISTS topic_class_progress (
    progress_id  BIGINT       NOT NULL AUTO_INCREMENT,
    user_id      BIGINT       NOT NULL,
    course_id    BIGINT       NOT NULL,
    topic_id     BIGINT       NOT NULL,
    class_seq    INT          NULL,
    week_no      SMALLINT     NULL,
    source       VARCHAR(10)  NOT NULL DEFAULT 'USER',
    note         VARCHAR(300) NULL,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (progress_id),
    UNIQUE KEY uk_topic_class_progress_topic (course_id, topic_id),
    KEY idx_topic_class_progress_owner (user_id, course_id),
    CONSTRAINT chk_topic_class_progress_week CHECK (week_no IS NULL OR week_no BETWEEN 1 AND 30),
    CONSTRAINT chk_topic_class_progress_source CHECK (source IN ('USER'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;

-- "이번 시험에는 이 단원이 빠져" — 계획 범위에서 제외. 학습 완료로 보지 않고, 트리에서 지우지도 않는다.
-- label은 어떤 시험·계획의 범위인지(예: 중간고사). 계획 만들기가 기본 제외로 싣고 화면에서 풀 수 있다.
CREATE TABLE IF NOT EXISTS course_scope_exclusions (
    exclusion_id BIGINT       NOT NULL AUTO_INCREMENT,
    user_id      BIGINT       NOT NULL,
    course_id    BIGINT       NOT NULL,
    topic_id     BIGINT       NOT NULL,
    label        VARCHAR(60)  NOT NULL DEFAULT '',
    status       VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    removed_at   DATETIME     NULL,
    PRIMARY KEY (exclusion_id),
    UNIQUE KEY uk_course_scope_exclusions (course_id, topic_id, label),
    KEY idx_course_scope_exclusions_owner (user_id, course_id, status),
    CONSTRAINT chk_course_scope_exclusions_status CHECK (status IN ('ACTIVE', 'REMOVED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;

-- ---------------------------------------------------------------------
-- 5. 정리 요청의 사용자 지시와 정리안의 출처
-- ---------------------------------------------------------------------
-- user_request_json  {text, focusTopicIds[]} — "이 항목은 너무 넓으니 나눠줘" 같은 요청. 모델에는 데이터로만 준다
-- origin             AI(자료 정리) / REQUEST(자연어 요청 해석) / USER(직접 조작으로 만든 변경안)
ALTER TABLE project_tidy_jobs ADD COLUMN IF NOT EXISTS user_request_json LONGTEXT NULL;
ALTER TABLE project_tidy_proposals ADD COLUMN IF NOT EXISTS origin VARCHAR(10) NOT NULL DEFAULT 'AI';
ALTER TABLE project_tidy_proposals ADD COLUMN IF NOT EXISTS user_request_json LONGTEXT NULL;
