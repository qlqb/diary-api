-- 업로드 묶음·예상 시간 표본·프로젝트 단위 자료 정리안.
--
-- 고치는 문제 셋:
--  (1) 자료를 여러 개 올릴 때 "얼마나 걸리는지"를 시작 전에 말할 근거가 없었다. 지난 실행의
--      소요를 남기지 않았기 때문이다. 진행 상황도 서버에 묶음이라는 단위가 없어 화면이
--      자료 목록을 훑어 짐작했고, 분석 중에 파일을 더 올리면 분모가 바뀌어 진행률이 뒤로 갔다.
--  (2) 트리 변경안이 자료 하나마다 따로 만들어졌다. 같은 프로젝트의 여러 자료가 같은 개념을
--      서로 다른 이름으로 건드리면 변경안끼리 충돌하고, 하나를 적용하는 순간 나머지가 STALE로
--      내려가 다시 분석됐다(무한에 가까운 되풀이). "합쳐서 순서대로 실행"으로는 풀리지 않는
--      의미 충돌이라, 프로젝트 안의 자료를 함께 보고 정리안 하나를 만드는 표가 필요하다.
--  (3) 검토 중인 정리안에 사용자가 한 편집(제목 고치기·제외)을 저장할 곳이 없어, 적용 요청에
--      실어 보내는 배열 순번이 전부였다. 새로고침하면 사라지고, 정리안이 바뀌면 순번이 어긋났다.
--
-- 전부 "추가"다. 기존 컬럼·행·ID를 바꾸거나 지우지 않는다. 한 곳만 예외인데(§6)
-- topic_change_proposals의 열린 행 상태를 PROPOSED → SUPERSEDED로 옮긴다 — 지우지 않고
-- superseded_from에 원래 상태를 적어 되돌릴 수 있게 한다.
--
-- 기존 docs/sql/*.sql 컨벤션(날짜 파일, 수동 적용)을 따른다. MariaDB 10.4 기준.
-- FK 없음, ENUM 대신 VARCHAR + CHECK. 소유권은 서비스가 user_id로 검증한다.
--
-- 적용 순서: 1 → 8 순서대로. 재실행 가능하다.
-- 롤백은 맨 아래 주석.

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

DROP PROCEDURE IF EXISTS replace_check_constraint;
DELIMITER //
CREATE PROCEDURE replace_check_constraint(IN p_table VARCHAR(64), IN p_name VARCHAR(64), IN p_expr TEXT)
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND CONSTRAINT_NAME = p_name) THEN
        SET @ddl = CONCAT('ALTER TABLE ', p_table, ' DROP CONSTRAINT ', p_name);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
    SET @ddl = CONCAT('ALTER TABLE ', p_table, ' ADD CONSTRAINT ', p_name, ' CHECK ', p_expr);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
END //
DELIMITER ;

-- ===============================================================
-- 1. material_analysis_batches — 한 번의 [분석 시작]이 맡는 자료 묶음.
--
--    구성원은 만들 때 정해지고 그 뒤로 바뀌지 않는다. 분석 중에 파일을 더 올리면 새 묶음이
--    생긴다 — 기존 묶음의 분모를 늘리면 80%가 30%로 떨어진다.
--
--    진행률의 원본은 이 표가 아니라 material_analysis_jobs다. 여기에는 "무엇이 이 묶음에
--    속하는가"와 업로드 단계의 결과만 둔다. 분석 진행은 조회할 때 작업 표와 조인해 센다 —
--    같은 사실을 두 곳에 두면 반드시 어긋난다.
--
--    course_id: 프로젝트 화면에서 시작한 묶음이면 그 프로젝트. 자료함에서 시작했으면 NULL.
-- ===============================================================
CREATE TABLE IF NOT EXISTS material_analysis_batches (
    batch_id        BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    course_id       BIGINT       NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'STAGED',
    item_count      INT          NOT NULL DEFAULT 0,
    est_min_seconds INT          NULL,
    est_max_seconds INT          NULL,
    est_basis       VARCHAR(20)  NULL,
    started_at      DATETIME     NULL,
    finished_at     DATETIME     NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (batch_id),
    CONSTRAINT chk_material_analysis_batches_status CHECK (status IN
        ('STAGED', 'UPLOADING', 'ANALYZING', 'FINISHED', 'ABANDONED')),
    CONSTRAINT chk_material_analysis_batches_basis CHECK (est_basis IS NULL OR est_basis IN
        ('HISTORY', 'PARTIAL_HISTORY', 'DEFAULT')),
    INDEX idx_material_analysis_batches_user (user_id, status, batch_id),
    INDEX idx_material_analysis_batches_course (course_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ===============================================================
-- 2. material_analysis_batch_items — 묶음의 고정된 구성원.
--
--    묶음을 만들 때는 아직 material_id가 없다(파일을 고르기만 한 상태). 자리(item)를 먼저
--    만들고 업로드가 그 자리에 material_id를 채운다. 업로드가 실패하거나 형식이 안 맞으면
--    자리는 남고 상태만 UPLOAD_FAILED/UNSUPPORTED가 된다 — 5개 골랐는데 3개만 보이면
--    무엇이 빠졌는지 알 수 없다.
--
--    upload_state는 이 표가 아는 전부다. 분석이 어디까지 갔는지는 작업 표가 안다.
-- ===============================================================
CREATE TABLE IF NOT EXISTS material_analysis_batch_items (
    item_id         BIGINT       NOT NULL AUTO_INCREMENT,
    batch_id        BIGINT       NOT NULL,
    user_id         BIGINT       NOT NULL,
    position        INT          NOT NULL,
    filename        VARCHAR(255) NOT NULL,
    size_bytes      BIGINT       NULL,
    extension       VARCHAR(20)  NULL,
    material_id     BIGINT       NULL,
    upload_state    VARCHAR(20)  NOT NULL DEFAULT 'STAGED',
    est_min_seconds INT          NULL,
    est_max_seconds INT          NULL,
    est_basis       VARCHAR(20)  NULL,
    message         VARCHAR(300) NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (item_id),
    CONSTRAINT chk_material_analysis_batch_items_state CHECK (upload_state IN
        ('STAGED', 'UPLOADING', 'UPLOADED', 'UPLOAD_FAILED', 'UNSUPPORTED', 'NO_TEXT', 'ABANDONED')),
    UNIQUE KEY uq_material_analysis_batch_items_pos (batch_id, position),
    UNIQUE KEY uq_material_analysis_batch_items_material (batch_id, material_id),
    INDEX idx_material_analysis_batch_items_user (user_id, batch_id),
    INDEX idx_material_analysis_batch_items_material (material_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ===============================================================
-- 3. material_analysis_timings — 지난 실행이 실제로 걸린 시간.
--
--    예상 시간은 여기서만 나온다. 표본이 모자라면 보수적인 기본값을 쓰고 화면이 "초기 추정"
--    이라고 말한다 — 근거 없는 초 단위 숫자를 만들지 않는다.
--
--    단계를 나눠 적는 이유: 큐에서 기다린 시간과 모델이 답한 시간을 합쳐 두면, 큐가 비었을
--    때의 예상이 과대해진다. 원문·파일명·토큰 본문은 남기지 않는다 — 진단에 필요한 것은
--    분량과 걸린 시간이다.
-- ===============================================================
CREATE TABLE IF NOT EXISTS material_analysis_timings (
    timing_id      BIGINT      NOT NULL AUTO_INCREMENT,
    user_id        BIGINT      NOT NULL,
    job_id         BIGINT      NOT NULL,
    job_kind       VARCHAR(10) NOT NULL,
    extension      VARCHAR(20) NULL,
    size_bytes     BIGINT      NULL,
    char_count     INT         NULL,
    unit_count     INT         NULL,
    chunk_count    INT         NULL,
    queue_wait_ms  BIGINT      NULL,
    extract_ms     BIGINT      NULL,
    model_ms       BIGINT      NULL,
    persist_ms     BIGINT      NULL,
    total_ms       BIGINT      NOT NULL,
    outcome        VARCHAR(20) NOT NULL,
    created_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (timing_id),
    CONSTRAINT chk_material_analysis_timings_outcome CHECK (outcome IN
        ('DONE', 'PARTIAL', 'FAILED', 'CANCELLED')),
    UNIQUE KEY uq_material_analysis_timings_job (job_id),
    INDEX idx_material_analysis_timings_pick (job_kind, outcome, created_at),
    INDEX idx_material_analysis_timings_user (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ===============================================================
-- 4. project_tidy_jobs — "이 프로젝트 자료 정리" 요청 하나.
--
--    사용자가 명시적으로 누른 것만 여기 들어온다. 자료 분석이 끝났다는 이유로 만들어지지
--    않는다.
--
--    generation: 같은 프로젝트에서 요청이 다시 들어오면 +1. 결과 저장은 최신 세대일 때만
--    받는다 — 버리기·다시 정리 뒤에 늦게 도착한 결과가 되살아나지 않는다.
--    lease_*: 자료 분석 작업 표와 같은 방식(선점마다 토큰 +1, 결과 저장은 토큰 대조).
--    input_snapshot_json: 요청 시점에 고정한 입력(자료 id·해시·분석판·트리 버전·제외 사유).
--
--    "지금 도는 것은 프로젝트당 하나"는 uq_project_tidy_jobs_open(부분 유일 인덱스 대용
--    생성 컬럼)로 DB가 지킨다. 중복 클릭·두 탭이 같은 작업을 두 번 만들지 못한다.
-- ===============================================================
CREATE TABLE IF NOT EXISTS project_tidy_jobs (
    job_id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_id             BIGINT       NOT NULL,
    course_id           BIGINT       NOT NULL,
    generation          BIGINT       NOT NULL DEFAULT 1,
    status              VARCHAR(20)  NOT NULL DEFAULT 'QUEUED',
    open_guard          TINYINT AS (CASE WHEN status IN ('QUEUED', 'RUNNING') THEN 1 ELSE NULL END) PERSISTENT,
    base_tree_version   BIGINT       NOT NULL,
    previous_proposal_id BIGINT      NULL,
    input_snapshot_json LONGTEXT     NULL,
    attempt             INT          NOT NULL DEFAULT 0,
    max_attempts        INT          NOT NULL DEFAULT 3,
    next_run_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_owner         VARCHAR(64)  NULL,
    lease_until         DATETIME     NULL,
    lease_token         BIGINT       NOT NULL DEFAULT 0,
    result_proposal_id  BIGINT       NULL,
    error_code          VARCHAR(40)  NULL,
    error_message       VARCHAR(500) NULL,
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    finished_at         DATETIME     NULL,
    PRIMARY KEY (job_id),
    CONSTRAINT chk_project_tidy_jobs_status CHECK (status IN
        ('QUEUED', 'RUNNING', 'DONE', 'FAILED', 'UNAVAILABLE', 'CANCELLED')),
    CONSTRAINT chk_project_tidy_jobs_snapshot CHECK (input_snapshot_json IS NULL OR JSON_VALID(input_snapshot_json)),
    UNIQUE KEY uq_project_tidy_jobs_open (course_id, open_guard),
    INDEX idx_project_tidy_jobs_pick (status, next_run_at, job_id),
    INDEX idx_project_tidy_jobs_course (course_id, status, job_id),
    INDEX idx_project_tidy_jobs_user (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ===============================================================
-- 5. project_tidy_proposals — 프로젝트 하나의 정리안.
--
--    ops_json의 각 작업에는 안정적인 change_id가 있다. 사용자 편집(제목·제외)은 배열 순번이
--    아니라 이 id로 저장된다 — 정리안이 새 버전으로 바뀌어도 같은 뜻의 변경을 찾을 수 있다.
--    scope_json: 실제로 검토한 자료·구간, 제외한 자료와 사유, 입력 한도로 못 본 범위.
--    revision: 이 정리안 자체의 판 번호(내용이 바뀌면 오른다). 적용 요청이 이 값을 싣는다.
--    superseded_by_proposal_id: [새 자료 반영해 다시 정리]로 대체된 경우 새 정리안.
--
--    검토 중인 정리안은 프로젝트당 하나(uq_project_tidy_proposals_open).
-- ===============================================================
CREATE TABLE IF NOT EXISTS project_tidy_proposals (
    proposal_id               BIGINT       NOT NULL AUTO_INCREMENT,
    user_id                   BIGINT       NOT NULL,
    course_id                 BIGINT       NOT NULL,
    job_id                    BIGINT       NULL,
    generation                BIGINT       NOT NULL DEFAULT 1,
    revision                  BIGINT       NOT NULL DEFAULT 1,
    base_tree_version         BIGINT       NOT NULL,
    status                    VARCHAR(20)  NOT NULL DEFAULT 'PROPOSED',
    open_guard                TINYINT AS (CASE WHEN status = 'PROPOSED' THEN 1 ELSE NULL END) PERSISTENT,
    summary_json              LONGTEXT     NOT NULL,
    ops_json                  LONGTEXT     NOT NULL,
    scope_json                LONGTEXT     NOT NULL,
    applied_result_json       LONGTEXT     NULL,
    previous_proposal_id      BIGINT       NULL,
    superseded_by_proposal_id BIGINT       NULL,
    model                     VARCHAR(60)  NULL,
    created_at                DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    resolved_at               DATETIME     NULL,
    PRIMARY KEY (proposal_id),
    CONSTRAINT chk_project_tidy_proposals_status CHECK (status IN
        ('PROPOSED', 'APPLIED', 'DISMISSED', 'SUPERSEDED', 'EMPTY')),
    CONSTRAINT chk_project_tidy_proposals_ops CHECK (JSON_VALID(ops_json)),
    CONSTRAINT chk_project_tidy_proposals_summary CHECK (JSON_VALID(summary_json)),
    CONSTRAINT chk_project_tidy_proposals_scope CHECK (JSON_VALID(scope_json)),
    UNIQUE KEY uq_project_tidy_proposals_open (course_id, open_guard),
    INDEX idx_project_tidy_proposals_course (course_id, status, proposal_id),
    INDEX idx_project_tidy_proposals_user (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ===============================================================
-- 6. project_tidy_proposal_materials — 이 정리안이 근거로 삼은 자료.
--
--    정리안 하나에 자료가 여럿이다(그게 이 작업의 요점이다). 적용 직전에 이 표의 해시·분석판을
--    지금 값과 대조한다 — 검토하는 동안 자료가 바뀌었으면 적용하지 않는다.
--    included=0 은 "이번 정리에서 뺐다"이고 exclude_reason이 왜인지를 말한다
--    (ANALYZING / FAILED / NO_TEXT / OVER_BUDGET / UNLINKED).
-- ===============================================================
CREATE TABLE IF NOT EXISTS project_tidy_proposal_materials (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    proposal_id      BIGINT       NOT NULL,
    user_id          BIGINT       NOT NULL,
    material_id      BIGINT       NOT NULL,
    file_hash        CHAR(64)     NULL,
    analysis_version INT          NULL,
    section_count    INT          NOT NULL DEFAULT 0,
    reviewed_count   INT          NOT NULL DEFAULT 0,
    included         TINYINT(1)   NOT NULL DEFAULT 1,
    exclude_reason   VARCHAR(20)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT chk_project_tidy_proposal_materials_reason CHECK (exclude_reason IS NULL OR exclude_reason IN
        ('ANALYZING', 'FAILED', 'NO_TEXT', 'OVER_BUDGET', 'UNLINKED', 'DELETED')),
    UNIQUE KEY uq_project_tidy_proposal_materials (proposal_id, material_id),
    INDEX idx_project_tidy_proposal_materials_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ===============================================================
-- 7. project_tidy_edits — 검토 중 사용자가 고친 것.
--
--    한 정리안에 한 행이다. edits_json은 {change_id: {excluded, title}} 모양이고
--    edit_revision은 저장할 때마다 오른다. 적용 요청이 이 값을 싣고, 어긋나면 적용하지 않는다
--    — 다른 탭에서 먼저 고친 것을 모르고 덮지 않기 위해서다.
--
--    이것은 검토 초안이지 트리 변경이 아니다. 여기에 저장돼도 트리는 그대로다.
-- ===============================================================
CREATE TABLE IF NOT EXISTS project_tidy_edits (
    proposal_id   BIGINT   NOT NULL,
    user_id       BIGINT   NOT NULL,
    edits_json    LONGTEXT NOT NULL,
    edit_revision BIGINT   NOT NULL DEFAULT 1,
    updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (proposal_id),
    CONSTRAINT chk_project_tidy_edits_json CHECK (JSON_VALID(edits_json)),
    INDEX idx_project_tidy_edits_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ===============================================================
-- 8. 레거시 전환 — 자료별 변경안과 LINK 작업.
--
--    지우지 않는다. 열린(PROPOSED) 자료별 변경안은 SUPERSEDED로 옮기고 원래 상태를
--    superseded_from에 적는다. ops_json·summary_json은 그대로라 이력에서 볼 수 있고,
--    되돌리려면 superseded_from을 status로 되쓰면 된다(맨 아래 롤백 참고).
--
--    아직 돌지 않은 LINK 작업은 CANCELLED로 내린다. 이미 RUNNING인 것은 여기서 건드리지
--    않는다 — lease_token을 올리면 그 worker가 결과를 쓰지 못하고 끝나지만, 실행 중인
--    트랜잭션과 겹치면 기다리게 된다. 대신 서버 쪽 생성 경로가 막혀 있어(§코드) 늦게
--    끝난 LINK도 변경안을 만들지 못한다.
-- ===============================================================
CALL add_column_if_missing('topic_change_proposals', 'superseded_from',
    'superseded_from VARCHAR(20) NULL AFTER status');

CALL replace_check_constraint('topic_change_proposals', 'chk_topic_change_proposals_status',
    "(status IN ('PROPOSED', 'APPLIED', 'DISMISSED', 'CONFLICT', 'STALE', 'EMPTY', 'SUPERSEDED'))");

UPDATE topic_change_proposals
   SET superseded_from = 'PROPOSED', status = 'SUPERSEDED', resolved_at = COALESCE(resolved_at, NOW())
 WHERE status = 'PROPOSED';

UPDATE material_analysis_jobs
   SET status = 'CANCELLED', finished_at = COALESCE(finished_at, NOW()),
       error_code = 'LEGACY_LINK', error_message = '자료별 연결 분석은 프로젝트 단위 정리로 바뀌었습니다'
 WHERE job_kind = 'LINK' AND status IN ('QUEUED', 'PAUSED');

DROP PROCEDURE IF EXISTS add_column_if_missing;
DROP PROCEDURE IF EXISTS replace_check_constraint;

-- ===============================================================
-- 롤백
-- ===============================================================
-- DROP TABLE IF EXISTS project_tidy_edits;
-- DROP TABLE IF EXISTS project_tidy_proposal_materials;
-- DROP TABLE IF EXISTS project_tidy_proposals;
-- DROP TABLE IF EXISTS project_tidy_jobs;
-- DROP TABLE IF EXISTS material_analysis_timings;
-- DROP TABLE IF EXISTS material_analysis_batch_items;
-- DROP TABLE IF EXISTS material_analysis_batches;
-- UPDATE topic_change_proposals SET status = superseded_from, superseded_from = NULL, resolved_at = NULL
--  WHERE status = 'SUPERSEDED' AND superseded_from IS NOT NULL;
-- ALTER TABLE topic_change_proposals DROP COLUMN superseded_from;
-- ALTER TABLE topic_change_proposals DROP CONSTRAINT chk_topic_change_proposals_status;
-- ALTER TABLE topic_change_proposals ADD CONSTRAINT chk_topic_change_proposals_status
--   CHECK (status IN ('PROPOSED','APPLIED','DISMISSED','CONFLICT','STALE','EMPTY'));
-- UPDATE material_analysis_jobs SET status = 'QUEUED', error_code = NULL, error_message = NULL, finished_at = NULL
--  WHERE job_kind = 'LINK' AND status = 'CANCELLED' AND error_code = 'LEGACY_LINK';
