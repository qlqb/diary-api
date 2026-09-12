-- 기간형 계획: plan_versions + execution_items의 계획 기간 컬럼.
--
-- 설계 근거는 docs/product/11-period-plan.md에 있다. 여기에는 스키마 결정의 이유만 적는다.
--
-- 기존 docs/sql/*.sql 컨벤션(날짜 파일, 수동 적용)을 따른다. MariaDB 10.4 기준.
-- 신규 테이블은 FK를 걸지 않는다(2026-08-16-material-store.sql에서 정한 컨벤션).
-- 소유권은 서비스 코드에서 user_id로 검증한다.


-- ===============================================================
-- 1. plan_versions
-- ===============================================================
-- 특정 시점에 확정한 계획의 불변 스냅샷.
--
-- 이 테이블은 UPDATE하지 않는다. 현재 상태는 execution_items가 유일하게 소유하고,
-- 여기에는 "그때 무엇을 하기로 했는가"만 남는다. 따라서 items_snapshot의 값 복사는
-- 이중 원본이 아니라 의도적인 역사 보존이다.
--
-- plan_key/version은 지금 항상 (새 UUID, 1)이다. 재계획은 1차 범위 밖이다
-- (11-period-plan.md §1-5). 컬럼을 미리 두는 이유는 나중에 재계획을 넣을 때 스키마를
-- 바꾸지 않기 위해서다. version이 항상 1이라 MAX(version)+1 경합은 발생하지 않으므로
-- 재시도 로직도 만들지 않는다.
CREATE TABLE plan_versions (
    plan_version_id    BIGINT        NOT NULL AUTO_INCREMENT,
    user_id            BIGINT        NOT NULL,
    plan_key           CHAR(36)      NOT NULL,
    version            INT           NOT NULL DEFAULT 1,
    start_date         DATE          NOT NULL,
    end_date           DATE          NOT NULL,
    title              VARCHAR(200)  NOT NULL,
    goal_summary       VARCHAR(1000) NULL,
    items_snapshot     LONGTEXT      NOT NULL,
    source_proposal_id BIGINT        NULL,
    confirmed_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (plan_version_id),
    UNIQUE KEY uq_plan_versions_key_version (plan_key, version),
    -- 같은 제안을 두 번 확정하는 것을 DB에서도 막는다.
    -- UNIQUE는 NULL을 여러 번 허용한다(MariaDB 표준 동작) — 계획 경로가 아닌 제안은 NULL이다.
    UNIQUE KEY uq_plan_versions_proposal (source_proposal_id),
    INDEX idx_plan_versions_user_period (user_id, start_date, end_date),
    CONSTRAINT chk_plan_versions_period CHECK (start_date <= end_date),
    CONSTRAINT chk_plan_versions_snapshot CHECK (JSON_VALID(items_snapshot))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ===============================================================
-- 2. execution_items 컬럼 추가
-- ===============================================================
-- plan_version_id: 이 조각을 "처음 만들어낸" 계획 확정. 생성 출처이며 이후 바뀌지 않는다.
--   NULL이 정상값이다 — 직접 추가한 조각, AI 단건 추천을 승인해 만든 조각은 전부 NULL이다.
--   "현재 어느 계획에 속하는가"는 이 컬럼이 답하지 않는다. 그건 스냅샷이 답한다.
--
-- planning_start_date / planning_end_date: 날짜를 아직 안 정한 조각의 목표 기간.
--   scheduled_date에서 파생된 중복이 아니라, scheduled_date가 없는 조각의 독립 정보다.
--   이게 없으면 8일 이상 계획을 확정한 직후 range 조회에서 전부 사라진다.
ALTER TABLE execution_items
    ADD COLUMN plan_version_id     BIGINT NULL AFTER course_id,
    ADD COLUMN planning_start_date DATE   NULL AFTER scheduled_date,
    ADD COLUMN planning_end_date   DATE   NULL AFTER planning_start_date;

CREATE INDEX idx_execution_items_plan_version
    ON execution_items (user_id, plan_version_id);
CREATE INDEX idx_execution_items_planning_range
    ON execution_items (user_id, planning_start_date, planning_end_date);


-- ===============================================================
-- 3. planning_* 정합성 CHECK
-- ===============================================================
-- chk_execution_items_placement는 이미 존재하므로 건드리지 않는다.
-- 기존 제약은 placement_type과 세 날짜 필드의 관계에 더해
-- cast(scheduled_start_at as date) = scheduled_date 와 scheduled_start_at < scheduled_end_at
-- 까지 강제한다. 위반 행은 0건이고, 다시 만들면 오히려 보장이 약해진다.
--
-- 아래는 새로 추가하는 planning_* 전용 제약이다.
--
-- planning_* 는 UNSCHEDULED 전용이고, 둘 다 있거나 둘 다 없다.
--
-- 이유는 조회 중복이 아니다(SQL의 OR은 행을 중복 반환하지 않는다). scheduled_date와
-- planning_* 가 동시에 채워지면 "이 항목의 기간은 언제인가"에 답이 둘이 되고, 둘이
-- 어긋났을 때 어느 쪽이 진실인지 알 방법이 없다. 현재 기간의 이중 원본을 막는 제약이다.
ALTER TABLE execution_items
    ADD CONSTRAINT chk_execution_items_planning_range CHECK (
        (placement_type = 'UNSCHEDULED'
         OR (planning_start_date IS NULL AND planning_end_date IS NULL))
        AND
        ((planning_start_date IS NULL AND planning_end_date IS NULL)
         OR (planning_start_date IS NOT NULL AND planning_end_date IS NOT NULL
             AND planning_start_date <= planning_end_date))
    );


-- ===============================================================
-- 4. ai_proposals에 계획 기간 보존
-- ===============================================================
-- 초안 생성 시점의 기간을 서버가 보존한다.
--
-- 확정 요청은 기간을 받지 않는다. 클라이언트가 다시 보내게 하면 초안과 다른 기간으로
-- 확정될 수 있고, 그러면 스냅샷의 기간과 항목의 planning_* 가 어긋난다.
-- 계획 경로가 아닌 제안(단건 추천 등)은 두 값이 NULL이고, 그런 제안에 confirm을 호출하면
-- 서비스가 400으로 거절한다.
ALTER TABLE ai_proposals
    ADD COLUMN plan_start_date DATE NULL,
    ADD COLUMN plan_end_date   DATE NULL;

ALTER TABLE ai_proposals
    ADD CONSTRAINT chk_ai_proposals_plan_period CHECK (
        (plan_start_date IS NULL AND plan_end_date IS NULL)
     OR (plan_start_date IS NOT NULL AND plan_end_date IS NOT NULL
         AND plan_start_date <= plan_end_date)
    );


-- ===============================================================
-- 검증
-- ===============================================================
--   SELECT COUNT(*) FROM information_schema.TABLES
--    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'plan_versions';          -- 1
--
--   SELECT COLUMN_NAME FROM information_schema.COLUMNS
--    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'execution_items'
--      AND COLUMN_NAME IN ('plan_version_id','planning_start_date','planning_end_date');
--                                                                               -- 3행
--
--   SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
--    WHERE TABLE_SCHEMA = DATABASE()
--      AND CONSTRAINT_NAME IN ('chk_execution_items_placement',
--                              'chk_execution_items_planning_range');           -- 2
--
-- 기존 행은 전부 새 컬럼이 NULL이므로 chk_execution_items_planning_range를 통과한다.
