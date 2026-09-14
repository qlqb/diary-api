-- 계획 강도(intensity)와 시간 예산 보존.
--
-- 설계 근거는 docs/product/11-period-plan.md §5-1-1에 있다. 요지는 통제 대상을 항목
-- 개수에서 시간으로 옮긴다는 것이다 — 개수는 사용자가 판단할 수 있는 단위가 아니고,
-- AI가 한 항목을 셋으로 쪼개면 게이밍된다. 시간은 쪼개도 합이 같다.
--
-- 2026-08-24-plan-versions.sql이 이미 적용·커밋된 뒤에 정해진 변경이라 별도 파일로 낸다.
-- 적용이 끝난 마이그레이션 파일은 고치지 않는다 — 이미 적용한 환경과 파일이 어긋난다.
--
-- MariaDB 10.4 기준. 신규 컬럼이므로 FK 없이 VARCHAR + CHECK.


-- ===============================================================
-- 1. ai_proposals — 초안 생성 시점의 강도와 예산
-- ===============================================================
-- 기간 컬럼(plan_start_date/plan_end_date)과 같은 이유다. 확정 요청은 강도를 받지 않고
-- 여기서 읽으므로, 초안과 확정이 어긋날 경로 자체가 없다.
--
-- plan_target_minutes를 plan_intensity와 따로 저장한다. 프리셋 값(PlanIntensity enum)은
-- 실사용 후 조정할 예정이고, 그때 과거 계획의 예산이 소급 변조되면 안 된다. intensity에서
-- 매번 재계산하면 "그때 AI가 실제로 받은 예산"이 사라진다.
ALTER TABLE ai_proposals
    ADD COLUMN plan_intensity      VARCHAR(20) NULL,
    ADD COLUMN plan_target_minutes INT         NULL;

ALTER TABLE ai_proposals
    ADD CONSTRAINT chk_ai_proposals_plan_intensity CHECK (
        plan_intensity IS NULL
     OR plan_intensity IN ('LIGHT', 'NORMAL', 'FOCUSED')
    );

ALTER TABLE ai_proposals
    ADD CONSTRAINT chk_ai_proposals_plan_target_minutes CHECK (
        plan_target_minutes IS NULL OR plan_target_minutes > 0
    );


-- ===============================================================
-- 2. plan_versions — 확정 시점의 강도와 예산
-- ===============================================================
-- items_snapshot과 같은 성격의 역사 기록이다. 회고에서 "집중으로 잡았는데 절반만 했다"를
-- 판단하려면 그때 강도가 무엇이었는지 남아야 한다.
--
-- 강도 기본값은 이 컬럼에서 읽는다(11-period-plan.md §5-1-2) — 가장 최근 확정한
-- plan_version의 intensity를 이어받고, 없으면 NORMAL. 사용자 설정 테이블을 따로 만들지
-- 않는 이유이기도 하다.
--
-- 둘 다 NULL을 허용한다. 강도 없이 만든 계획이 있을 수 있다.
ALTER TABLE plan_versions
    ADD COLUMN intensity      VARCHAR(20) NULL AFTER goal_summary,
    ADD COLUMN target_minutes INT         NULL AFTER intensity;

ALTER TABLE plan_versions
    ADD CONSTRAINT chk_plan_versions_intensity CHECK (
        intensity IS NULL
     OR intensity IN ('LIGHT', 'NORMAL', 'FOCUSED')
    );

ALTER TABLE plan_versions
    ADD CONSTRAINT chk_plan_versions_target_minutes CHECK (
        target_minutes IS NULL OR target_minutes > 0
    );

-- 강도 기본값 승계용. confirmed_at DESC LIMIT 1을 사용자별로 찾는다.
CREATE INDEX idx_plan_versions_user_confirmed
    ON plan_versions (user_id, confirmed_at);


-- ===============================================================
-- 검증
-- ===============================================================
--   SELECT COUNT(*) FROM information_schema.COLUMNS
--    WHERE TABLE_SCHEMA = DATABASE()
--      AND ((TABLE_NAME = 'ai_proposals'
--            AND COLUMN_NAME IN ('plan_intensity','plan_target_minutes'))
--        OR (TABLE_NAME = 'plan_versions'
--            AND COLUMN_NAME IN ('intensity','target_minutes')));       -- 4
--
-- 잘못된 값이 실제로 막히는지:
--   INSERT ... plan_intensity = 'EXTREME'   → chk_ai_proposals_plan_intensity 위반
--   INSERT ... target_minutes = 0           → chk_plan_versions_target_minutes 위반
--
-- ★ 알려진 성질: 컬럼 collation이 utf8mb4_unicode_ci라 IN 비교가 대소문자를 가리지 않는다.
-- 즉 'normal'도 CHECK를 통과한다. 이 스키마의 기존 CHECK 전부가 같다
-- (chk_execution_items_status에 'planned', chk_ai_proposals_scope에 'today'도 통과).
-- 여기만 COLLATE utf8mb4_bin으로 특별 취급하지 않는다 — 실제 방어선은 양방향 모두 Java
-- enum이다. 쓸 때는 PlanIntensity.name()이 대문자를 보장하고, 읽을 때는 MyBatis의
-- enum 핸들러가 Enum.valueOf로 변환하므로 소문자가 들어와 있으면 조회에서 즉시 터진다.
