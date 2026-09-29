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
