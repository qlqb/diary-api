-- 일정 후보 종류에 ROUTINE_LEAD를 더한다.
--
-- "수업 전엔 이동시간 1시간 채워줘"는 새 일정이 아니라 이미 있는 반복 일정에 붙는 값이다.
-- 승인하면 routines.lead_minutes를 바꾼다(2026-09-07-routine-lead-minutes.sql). 저장 형태는
-- 기존 후보와 같다 — kind 하나가 늘고 proposed_payload에 {routineIds, leadMinutes,
-- targetSummary}가 담긴다. 승인 전에는 아무것도 바뀌지 않는다는 규칙도 같다.
--
-- kind는 VARCHAR라 값 추가는 CHECK 제약만 바꾸면 된다. MariaDB 10.4 기준.

ALTER TABLE ai_schedule_suggestions DROP CONSTRAINT chk_ai_schedule_kind;
ALTER TABLE ai_schedule_suggestions
    ADD CONSTRAINT chk_ai_schedule_kind CHECK (kind IN ('COMMITMENT', 'ROUTINE', 'ROUTINE_LEAD'));


-- ===============================================================
-- 적용 확인 (실행 안 함)
-- ===============================================================
--   SELECT CHECK_CLAUSE FROM information_schema.CHECK_CONSTRAINTS
--    WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'chk_ai_schedule_kind';
--   -- `kind` in ('COMMITMENT','ROUTINE','ROUTINE_LEAD')
