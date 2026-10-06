-- =====================================================================
-- 2026-10-05 학습 기억(종류·단원·대체) · 목차 원본 순번
-- =====================================================================
--
-- 앞선 마이그레이션: docs/sql/2026-10-04-textbook-web-toc.sql. 이 파일은 그 뒤에 적용한다.
-- 설계: docs/handoff/study-memory-plan-2026-10-05.md, DB: docs/product/05-database.md §24.
--
-- 추가형이다(NULL 허용 열·인덱스·CHECK). 기존 행의 의미를 바꾸지 않는다 — 예전 기억은 fact_kind가 NULL("종류 모름")로
-- 읽히고, said_at·content_key가 NULL인 행은 예전 방식(본문 정규화 비교, updated_at 순서)으로 다룬다. backfill 없음.
-- 다시 돌려도 된다(IF NOT EXISTS, MariaDB 10.4).
--
-- 배포 순서: 이 마이그레이션 → 서버 → 화면. 새 서버는 새 열을 읽고 쓰므로 적용하지 않은 DB에서는 기억 저장·조회가
-- "Unknown column"으로 실패한다. 옛 서버는 새 열을 모른 채 그대로 동작한다(전부 NULL 허용).
--
-- 적용 전 확인(dry-run): 아래가 0이어야 CHECK 추가가 실패하지 않는다(새 열이므로 항상 0).
--   SELECT COUNT(*) FROM user_contexts WHERE fact_kind IS NOT NULL;
-- 적용 후 확인:
--   SHOW COLUMNS FROM user_contexts LIKE 'fact_%';  SHOW COLUMNS FROM course_topics LIKE 'source_toc_seq';
--
-- 되돌리기(새 서버를 내린 뒤. 새 값이 들어간 행이 있으면 먼저 확인한다 — 자동으로 지우지 않는다):
--   SELECT COUNT(*) FROM user_contexts WHERE fact_kind IS NOT NULL;
--   ALTER TABLE user_contexts DROP CONSTRAINT IF EXISTS chk_user_contexts_fact_kind;
--   ALTER TABLE user_contexts DROP CONSTRAINT IF EXISTS chk_user_contexts_help_level;
--   DROP INDEX IF EXISTS idx_user_contexts_course_kind ON user_contexts;
--   DROP INDEX IF EXISTS idx_user_contexts_content_key ON user_contexts;
--   ALTER TABLE user_contexts DROP COLUMN IF EXISTS fact_kind, DROP COLUMN IF EXISTS fact_label,
--       DROP COLUMN IF EXISTS help_level, DROP COLUMN IF EXISTS said_at, DROP COLUMN IF EXISTS content_key;
--   ALTER TABLE course_topics DROP COLUMN IF EXISTS source_toc_seq;
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 기억의 종류·단원·순서
-- ---------------------------------------------------------------------
--   fact_kind    PROGRESS(수업에서 어디까지 했나) / EXAM_SCOPE(시험 범위) / DIFFICULTY(내가 막힌 곳) /
--                RESOLVED(막혔다가 풀었다) / GOAL / PREFERENCE / CONSTRAINT / OTHER. NULL = 예전 행(종류 모름)
--                PROGRESS는 수업 진도이고 DIFFICULTY·RESOLVED는 내 이해다 — 섞지 않는다.
--   fact_label   EXAM_SCOPE의 시험 이름(중간고사·기말고사 등). 같은 과목·종류·이름끼리만 대체한다
--   help_level   RESOLVED일 때 SOLO(혼자) / GUIDED(설명·도움을 받아). 사용자가 말한 것만
--   said_at      근거가 된 사용자 발화 시각(수동 수정은 수정 시각). "더 늦게 말한 것이 이긴다"의 기준 —
--                저장이 늦게 끝난 옛 턴이 사용자의 정정을 되돌리지 못하게 한다
--   content_key  정규화한 본문의 SHA-256(16진수 64자, 중복·철회 판정). 지운 것과 같은 내용은 이력 전체에서 막는다
--                (예전 철회 행은 NULL — 서버가 그 사용자의 예전 철회 행 전부를 읽어 같은 정규화로 비교한다)
ALTER TABLE user_contexts
    ADD COLUMN IF NOT EXISTS fact_kind   VARCHAR(16)  NULL AFTER evidence_type,
    ADD COLUMN IF NOT EXISTS fact_label  VARCHAR(40)  NULL AFTER fact_kind,
    ADD COLUMN IF NOT EXISTS help_level  VARCHAR(8)   NULL AFTER self_level,
    ADD COLUMN IF NOT EXISTS said_at     DATETIME     NULL AFTER confirmed_at,
    ADD COLUMN IF NOT EXISTS content_key CHAR(64)     NULL AFTER content;
-- 초안(VARCHAR)으로 먼저 만든 DB도 같은 모양이 되게 한다. 새 열이라 값이 있으면 64자 해시뿐이다.
ALTER TABLE user_contexts MODIFY COLUMN content_key CHAR(64) NULL;

ALTER TABLE user_contexts DROP CONSTRAINT IF EXISTS chk_user_contexts_fact_kind;
ALTER TABLE user_contexts ADD CONSTRAINT chk_user_contexts_fact_kind
    CHECK (fact_kind IS NULL OR fact_kind IN ('PROGRESS', 'EXAM_SCOPE', 'DIFFICULTY', 'RESOLVED', 'GOAL',
                                              'PREFERENCE', 'CONSTRAINT', 'OTHER'));

ALTER TABLE user_contexts DROP CONSTRAINT IF EXISTS chk_user_contexts_help_level;
ALTER TABLE user_contexts ADD CONSTRAINT chk_user_contexts_help_level
    CHECK (help_level IS NULL OR help_level IN ('SOLO', 'GUIDED'));

CREATE INDEX IF NOT EXISTS idx_user_contexts_course_kind ON user_contexts (user_id, course_id, fact_kind, status);
CREATE INDEX IF NOT EXISTS idx_user_contexts_content_key ON user_contexts (user_id, content_key, status);

-- ---------------------------------------------------------------------
-- 2. 목차 원본 순번
-- ---------------------------------------------------------------------
-- 같은 교재 목차 안의 원본 순번(1부터). order_index는 트리 안의 순서라 사용자가 옮기면 바뀐다 — 이 값은 바뀌지 않는다.
-- 제목이 같은 단원(예: Unit 1과 Unit 10이 둘 다 "What's your name?")을 구분하는 근거다. 목차에서 오지 않은 항목은 NULL.
ALTER TABLE course_topics
    ADD COLUMN IF NOT EXISTS source_toc_seq INT NULL AFTER source_textbook_key;
