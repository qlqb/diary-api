-- 반복 일정 앞 이동시간: routines.lead_minutes.
--
-- "수업 전엔 이동시간 1시간 채워줘"를 메모가 아니라 일정층이 읽는 값으로 저장한다.
-- 가용시간 계산과 일정 화면이 같은 전개(RoutineOccurrenceService.expand)에서 이 값을 읽어
-- 발생분 앞에 lead 발생분을 만든다. 행을 만들지 않는 것은 발생분과 같다.
--
-- 기존 docs/sql/*.sql 컨벤션(날짜 파일, 수동 적용)을 따른다. MariaDB 10.4 기준.
--
-- ###############################################################
-- 세 상태를 구조로 구분한다
--
--   NULL  = 아직 모름. 계획 초안을 만들기 전에 사용자에게 묻는다.
--   0     = 없음. 묻지 않는다.
--   n > 0 = 발생분 시작 n분 전부터 시작까지를 hardBusy로 뺀다(0 < n <= 480).
--
-- 그래서 기본값이 NULL이고 백필하지 않는다. 기존 행을 0으로 채우면 "없음"과 "안 물어봄"이
-- 같아져 다시는 묻지 못하게 된다.
-- ###############################################################

ALTER TABLE routines ADD COLUMN lead_minutes INT NULL AFTER end_time;

-- 범위는 서비스가 검증한다(0~480). DB CHECK를 더 걸지 않는 이유는 NULL을 허용하는 CHECK가
-- MariaDB에서 NULL을 통과시키긴 하지만, 그 사실을 아는 사람만 읽을 수 있어서다.


-- ===============================================================
-- 적용 확인 (실행 안 함)
-- ===============================================================
--   SELECT COLUMN_NAME, IS_NULLABLE, COLUMN_DEFAULT
--     FROM information_schema.COLUMNS
--    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'routines'
--      AND COLUMN_NAME = 'lead_minutes';                         -- 1행, YES, NULL
--
--   SELECT COUNT(*) FROM routines WHERE lead_minutes IS NOT NULL;  -- 적용 직후 0
