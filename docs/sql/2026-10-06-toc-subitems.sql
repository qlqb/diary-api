-- =====================================================================
-- 2026-10-06 교재 목차 하위항목 보존 · 목차 항목 열쇠(원문 해시 + 원문 줄)
-- =====================================================================
--
-- 앞선 마이그레이션: docs/sql/2026-10-05-consult-photo.sql. 이 파일은 그 뒤에 적용한다.
-- 설계: docs/handoff/toc-subitems-plan-2026-10-06.md.
--
-- 추가형이다(NULL 허용 열·기본값 있는 열). 기존 행의 뜻을 바꾸지 않는다.
--   - textbook_web_revisions.toc_version 기본값 1 = 옛 구조화 규칙으로 읽은 리비전. 새 서버는 저장된 원문으로 다시 구조화한 새 리비전을 만든다
--     (옛 리비전은 지우지 않는다 — 옛 토픽·정리안의 근거다).
--   - course_topics.toc_key_* 는 서버가 시작할 때 한 번 채운다(TocKeyBackfill, 멱등). 이 파일은 열만 만든다 —
--     MariaDB 10.4에는 JSON_TABLE이 없어 목차 항목을 SQL로 훑기 어렵다.
--   - project_tidy_jobs/proposals.toc_key_version NULL = 옛 정리안(tocLine = 구조화 목록 순번). 새 서버는 목차 작업이 든 옛 정리안을
--     "목차가 바뀜"으로 보고 다시 정리하게 한다.
-- 다시 돌려도 된다(IF NOT EXISTS, MariaDB 10.4).
--
-- 배포 순서: 이 마이그레이션 → 서버 → 화면. 로컬 단일 서버이며 옛 서버와 함께 돌리지 않는다.
--
-- 적용 후 확인:
--   SHOW COLUMNS FROM textbook_web_revisions WHERE Field IN ('toc_version','toc_raw_hash','toc_raw_truncated');
--   SHOW COLUMNS FROM course_topics LIKE 'toc_key_%';
--   SELECT COUNT(*) FROM textbook_web_revisions WHERE toc_raw IS NOT NULL AND toc_raw_hash IS NULL;   -- 0이어야 한다
--
-- 되돌리기(새 서버를 내린 뒤):
--   ALTER TABLE course_topics DROP COLUMN IF EXISTS toc_key_state, DROP COLUMN IF EXISTS toc_key_line,
--       DROP COLUMN IF EXISTS toc_key_hash, DROP COLUMN IF EXISTS toc_key_kind;
--   ALTER TABLE project_tidy_proposals DROP COLUMN IF EXISTS toc_key_version;
--   (새 구조 리비전을 쓰는 교재가 있으면 옛 서버는 그 리비전을 옛 규칙의 것으로 읽는다 — 항목이 더 많을 뿐 뜻은 같다)
--   ALTER TABLE textbook_web_revisions DROP INDEX IF EXISTS idx_textbook_web_revisions_toc,
--       DROP COLUMN IF EXISTS toc_raw_truncated, DROP COLUMN IF EXISTS toc_raw_hash, DROP COLUMN IF EXISTS toc_version;
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. textbook_web_revisions — 구조화 판·원문 해시·잘림
-- ---------------------------------------------------------------------
--   toc_version        목차 구조화 규칙의 판(WebTocStructurer.TOC_VERSION). 1 = 2026-10-06 전 규칙
--   toc_raw_hash       toc_raw(UTF-8)의 SHA-256. 목차 항목 열쇠(원문 줄 번호)가 기대는 원문
--   toc_raw_truncated  받은 목차 원문이 잘렸나(서점이 접어 실음·저장 상한 2만 자). NULL = 기록 전 리비전
--                      (서버는 원문 길이가 저장 상한에 닿았으면 잘림으로 본다. 옛 PARTIAL은 잘림으로 보지 않는다)
ALTER TABLE textbook_web_revisions
    ADD COLUMN IF NOT EXISTS toc_version       INT        NOT NULL DEFAULT 1 AFTER toc_coverage,
    ADD COLUMN IF NOT EXISTS toc_raw_hash      CHAR(64)   NULL AFTER toc_version,
    ADD COLUMN IF NOT EXISTS toc_raw_truncated TINYINT(1) NULL AFTER toc_raw_hash;

ALTER TABLE textbook_web_revisions
    ADD INDEX IF NOT EXISTS idx_textbook_web_revisions_toc (page_id, toc_raw_hash, toc_version);

UPDATE textbook_web_revisions
   SET toc_raw_hash = SHA2(toc_raw, 256)
 WHERE toc_raw IS NOT NULL AND toc_raw_hash IS NULL;

-- ---------------------------------------------------------------------
-- 2. course_topics — 목차 항목 열쇠(만들 때 한 번 정하고 바꾸지 않는다)
-- ---------------------------------------------------------------------
--   toc_key_kind   WEB(웹 목차: hash = 목차 원문 해시, line = 원문 줄 번호) · MATERIAL(업로드 목차: hash = 파일 해시, line = 추출 순번)
--   toc_key_hash   위 원문 해시
--   toc_key_line   위 줄 번호·순번(1부터)
--   toc_key_state  SET(열쇠 있음) · MISSING_SOURCE(목차에서 왔지만 열쇠를 확정 못 함 — 정리에서 "검토 필요") · NULL(목차 토픽 아님)
-- source_toc_seq는 화면 표시용 순번("목차 N번째")으로 남긴다. 짝 찾기에는 쓰지 않는다.
ALTER TABLE course_topics
    ADD COLUMN IF NOT EXISTS toc_key_kind  VARCHAR(10) NULL AFTER source_toc_seq,
    ADD COLUMN IF NOT EXISTS toc_key_hash  CHAR(64)    NULL AFTER toc_key_kind,
    ADD COLUMN IF NOT EXISTS toc_key_line  INT         NULL AFTER toc_key_hash,
    ADD COLUMN IF NOT EXISTS toc_key_state VARCHAR(16) NULL AFTER toc_key_line;

-- ---------------------------------------------------------------------
-- 3. 정리안 — 목차 항목 열쇠의 판(작업은 실행할 때 목차를 다시 읽으므로 옛 번호를 들고 있지 않다)
-- ---------------------------------------------------------------------
--   toc_key_version  2 = tocLine이 목차 항목 열쇠(웹 원문 줄·업로드 순번). NULL = 옛 정리안(구조화 목록 순번)
ALTER TABLE project_tidy_proposals
    ADD COLUMN IF NOT EXISTS toc_key_version INT NULL;
