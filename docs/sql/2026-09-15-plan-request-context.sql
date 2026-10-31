-- 2026-09-15 계획 초안의 요청 맥락 저장 — 같은 조건으로 다시 만들기(redraft)
--
-- 왜: 「이번만 빼기」·되돌리기·「이미 알아요」 뒤 재생성이 화면의 기본 날짜·빈 지시로 요청을 새로 조립하면
--     상담에서 만든 초안의 기간·범위·대화 지시·지정 자료가 사라진다. 초안을 만든 요청을 제안 행에 남기고
--     POST /api/plans/proposals/{id}/redraft가 그것을 쓴다.
--
-- 추가 전용이다. 기존 행은 NULL로 남고, 그 초안의 다시 만들기는 409(PLAN_REDRAFT_CONTEXT_MISSING)로 새로 만들기를 안내한다.
-- 재실행해도 된다(컬럼이 있으면 건너뛴다). JSON 타입 대신 LONGTEXT — 같은 테이블의 plan_strategy_json·plan_provenance_json과
-- 같은 형태이고(MariaDB에서 JSON은 LONGTEXT + json_valid 별칭), 서버가 만든 JSON만 쓴다.

SET @exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'ai_proposals'
       AND COLUMN_NAME = 'plan_request_json'
);
SET @ddl := IF(@exists = 0,
    'ALTER TABLE ai_proposals ADD COLUMN plan_request_json LONGTEXT NULL AFTER plan_provenance_json',
    'SELECT ''plan_request_json already exists''');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 확인:
--   SHOW COLUMNS FROM ai_proposals LIKE 'plan_request_json';
--
-- 롤백(되돌릴 때만):
--   ALTER TABLE ai_proposals DROP COLUMN plan_request_json;
