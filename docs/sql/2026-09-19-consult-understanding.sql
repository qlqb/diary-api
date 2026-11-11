-- 2026-09-19 상담이 이해한 사용자 상황(근거 유형·적용 범위·철회), 상담 턴의 질문/해석 기록, 실행 기록의 막힌 이유
--
-- 왜:
--  (1) user_contexts는 "문장 + 상태"뿐이었다. 사용자가 직접 말한 것·자기평가("애매해")·실행 기록에서 본 것·AI의 추정이
--      구분되지 않았고, "자료구조 한정"·"이번 주 한정" 같은 적용 범위도 없었다. 그래서 일시적인 말이 장기 성향처럼
--      재사용되거나, AI의 추정이 사실처럼 계획에 들어갈 수 있었다. 사용자가 지운 기억을 "다시 저장하지 않는다"는
--      상태도 없었다(ARCHIVED는 '더 안 씀'일 뿐 '철회'가 아니다).
--  (2) 상담 턴이 낸 질문 카드·해석·방향 변화는 SSE 한 번으로만 전달돼 새로고침하면 사라졌다.
--  (3) 부분 수행·못 함의 이유("시간이 없었다" vs "개념에서 막혔다")가 메모 자유 문장에만 있었다.
--
-- 전부 추가형이다. 기존 행은 기본값으로 읽힌다(evidence_type='STATED' — 기존 행은 모두 사용자가 확정/승인한 것이다).
-- 옛 서버 코드는 새 컬럼을 모른 채 그대로 동작한다(전부 NULL 허용이거나 기본값이 있다).
-- MariaDB 10.4 기준: ADD COLUMN IF NOT EXISTS, DROP CONSTRAINT IF EXISTS를 쓴다. 재실행해도 된다.
--
-- ===== 적용 전 dry-run(읽기 전용) =====
--   SELECT status, source_type, COUNT(*) FROM user_contexts GROUP BY status, source_type;
--     → 새 CHECK가 허용하지 않는 값이 없어야 한다(기존 값은 전부 새 목록에 포함된다).
--   SELECT COUNT(*) FROM ai_messages;  SELECT COUNT(*) FROM execution_records;
--     → 행 수는 바뀌지 않는다(컬럼 추가뿐, UPDATE 없음 — backfill 없음).

-- ---------- 1. user_contexts ----------
ALTER TABLE user_contexts
    -- STATED(사용자가 직접 말함·고침) / SELF_REPORT(자기평가: 알아·애매해·처음 봐, "혼자는 못 해")
    -- / OBSERVED(실행 기록에서 서버가 확인) / INFERRED(AI 추정 — 확인 전. 사실로 쓰지 않는다)
    ADD COLUMN IF NOT EXISTS evidence_type VARCHAR(20) NOT NULL DEFAULT 'STATED' AFTER source_type,
    -- 적용 범위. 전부 NULL이면 범위를 모르는(전반적인) 것이다.
    ADD COLUMN IF NOT EXISTS course_id     BIGINT      NULL AFTER evidence_type,
    ADD COLUMN IF NOT EXISTS topic_id      BIGINT      NULL AFTER course_id,
    ADD COLUMN IF NOT EXISTS section_id    BIGINT      NULL AFTER topic_id,
    ADD COLUMN IF NOT EXISTS scope_start   DATE        NULL AFTER section_id,
    ADD COLUMN IF NOT EXISTS scope_end     DATE        NULL AFTER scope_start,
    -- 점검 활동의 자기평가 값: KNOW / UNSURE / NEW. 숙달도가 아니다.
    ADD COLUMN IF NOT EXISTS self_level    VARCHAR(10) NULL AFTER scope_end,
    ADD COLUMN IF NOT EXISTS withdrawn_at  DATETIME    NULL AFTER confirmed_at;

ALTER TABLE user_contexts DROP CONSTRAINT IF EXISTS chk_user_contexts_status;
ALTER TABLE user_contexts ADD CONSTRAINT chk_user_contexts_status
    -- WITHDRAWN: 사용자가 지웠다. 상담·계획에 쓰지 않고, 같은 내용을 다시 저장하지도 않는다.
    CHECK (status IN ('ACTIVE', 'STALE', 'SUPERSEDED', 'ARCHIVED', 'WITHDRAWN'));

ALTER TABLE user_contexts DROP CONSTRAINT IF EXISTS chk_user_contexts_source_type;
ALTER TABLE user_contexts ADD CONSTRAINT chk_user_contexts_source_type
    -- CONSULT_AUTO: 상담 턴이 사용자 발화에서 바로 저장(승인 팝업 없음, 바로 고칠 수 있음)
    -- USER_EDITED: '조금 달라요'·기억 화면에서 사용자가 고침 / SELF_CHECK: 점검 활동
    CHECK (source_type IN ('USER_CONFIRMED', 'AI_SUGGESTION_APPROVED', 'CONSULT_AUTO', 'USER_EDITED', 'SELF_CHECK'));

ALTER TABLE user_contexts DROP CONSTRAINT IF EXISTS chk_user_contexts_evidence_type;
ALTER TABLE user_contexts ADD CONSTRAINT chk_user_contexts_evidence_type
    CHECK (evidence_type IN ('STATED', 'SELF_REPORT', 'OBSERVED', 'INFERRED'));

ALTER TABLE user_contexts DROP CONSTRAINT IF EXISTS chk_user_contexts_self_level;
ALTER TABLE user_contexts ADD CONSTRAINT chk_user_contexts_self_level
    CHECK (self_level IS NULL OR self_level IN ('KNOW', 'UNSURE', 'NEW'));

CREATE INDEX IF NOT EXISTS idx_user_contexts_user_course ON user_contexts (user_id, course_id, status);

-- ---------- 2. ai_messages ----------
-- ASSISTANT 메시지의 상담 부가 정보(질문 카드·이번 턴에 이해한 것·바뀐 방향·선택 활동). 새로고침·재접속 뒤 복구에 쓴다.
ALTER TABLE ai_messages
    ADD COLUMN IF NOT EXISTS consult_json LONGTEXT NULL;
ALTER TABLE ai_messages DROP CONSTRAINT IF EXISTS chk_ai_messages_consult_json;
ALTER TABLE ai_messages ADD CONSTRAINT chk_ai_messages_consult_json
    CHECK (consult_json IS NULL OR JSON_VALID(consult_json));

-- ---------- 3. execution_records ----------
-- 부분 수행·못 함의 이유(선택). TIME / CONCEPT / ENERGY / OTHER. 없으면 NULL — 묻지 않았거나 답하지 않은 것이다.
ALTER TABLE execution_records
    ADD COLUMN IF NOT EXISTS blocker_kind VARCHAR(12) NULL;
ALTER TABLE execution_records DROP CONSTRAINT IF EXISTS chk_execution_records_blocker_kind;
ALTER TABLE execution_records ADD CONSTRAINT chk_execution_records_blocker_kind
    CHECK (blocker_kind IS NULL OR blocker_kind IN ('TIME', 'CONCEPT', 'ENERGY', 'OTHER'));

-- ===== 적용 후 확인 =====
--   SHOW CREATE TABLE user_contexts;  SHOW CREATE TABLE ai_messages;  SHOW CREATE TABLE execution_records;
--   SELECT evidence_type, COUNT(*) FROM user_contexts GROUP BY evidence_type;  -- 기존 행은 전부 STATED
--
-- ===== 롤백(되돌릴 때만. 새 값이 들어간 행이 있으면 CHECK 복원이 실패하므로 먼저 확인한다) =====
--   SELECT COUNT(*) FROM user_contexts WHERE status = 'WITHDRAWN'
--       OR source_type IN ('CONSULT_AUTO', 'USER_EDITED', 'SELF_CHECK');
--   -- 0이 아니면: 그 행을 어떻게 할지(ARCHIVED로 바꿀지) 먼저 정한다. 자동으로 지우지 않는다.
--   ALTER TABLE user_contexts DROP CONSTRAINT chk_user_contexts_evidence_type, DROP CONSTRAINT chk_user_contexts_self_level;
--   ALTER TABLE user_contexts DROP INDEX idx_user_contexts_user_course,
--       DROP COLUMN evidence_type, DROP COLUMN course_id, DROP COLUMN topic_id, DROP COLUMN section_id,
--       DROP COLUMN scope_start, DROP COLUMN scope_end, DROP COLUMN self_level, DROP COLUMN withdrawn_at;
--   ALTER TABLE user_contexts DROP CONSTRAINT chk_user_contexts_status;
--   ALTER TABLE user_contexts ADD CONSTRAINT chk_user_contexts_status
--       CHECK (status IN ('ACTIVE', 'STALE', 'SUPERSEDED', 'ARCHIVED'));
--   ALTER TABLE user_contexts DROP CONSTRAINT chk_user_contexts_source_type;
--   ALTER TABLE user_contexts ADD CONSTRAINT chk_user_contexts_source_type
--       CHECK (source_type IN ('USER_CONFIRMED', 'AI_SUGGESTION_APPROVED'));
--   ALTER TABLE ai_messages DROP CONSTRAINT chk_ai_messages_consult_json, DROP COLUMN consult_json;
--   ALTER TABLE execution_records DROP CONSTRAINT chk_execution_records_blocker_kind, DROP COLUMN blocker_kind;
