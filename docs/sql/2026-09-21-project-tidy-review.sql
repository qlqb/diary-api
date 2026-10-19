-- =====================================================================
-- 2026-09-21 프로젝트 정리 검토 후속: 압축 가져오기를 분석 묶음에 잇는다
-- =====================================================================
--
-- 앞선 마이그레이션: docs/sql/2026-09-21-project-tidy.sql (묶음·정리안 표를 만든 것).
-- 이 파일은 그 뒤에 적용한다.
--
-- 무엇을 더하는가 — 전부 NULL 허용 열과 색인. 기존 행은 바뀌지 않는다(값이 NULL로 남는다).
--   material_analysis_batches.zip_import_id
--       이 묶음이 어느 압축 가져오기에서 확정된 것인가. 일반 업로드 묶음은 NULL.
--   material_analysis_batch_items.zip_entry_id
--       이 자리가 압축 안의 어느 항목인가. 파일 이름으로 잇지 않는다 — 압축 안에는
--       "과제1/main.py"와 "과제2/main.py"처럼 이름이 같은 파일이 흔하다.
--   material_analysis_batch_items.source_path
--       압축 안의 경로. 화면이 같은 이름의 파일을 구분해 보여 주는 데 쓴다.
--
-- 다시 돌려도 된다: IF NOT EXISTS(MariaDB 10.4에서 확인).
--
-- 배포 순서: 이 마이그레이션 → 서버 → 화면.
--   새 서버는 이 열을 읽고 쓴다. 적용하지 않은 DB에 새 서버를 띄우면 묶음 조회·생성이
--   "Unknown column" 오류로 실패한다(압축뿐 아니라 일반 업로드 묶음도). 반드시 먼저 적용한다.
--   옛 서버는 이 열을 모르므로 적용 뒤에도 그대로 동작한다.
--
-- 되돌리기(필요할 때만, 새 서버를 내린 뒤):
--   ALTER TABLE material_analysis_batch_items DROP INDEX idx_material_analysis_batch_items_zip;
--   ALTER TABLE material_analysis_batch_items DROP COLUMN source_path, DROP COLUMN zip_entry_id;
--   ALTER TABLE material_analysis_batches DROP COLUMN zip_import_id;
-- =====================================================================

ALTER TABLE material_analysis_batches
    ADD COLUMN IF NOT EXISTS zip_import_id BIGINT NULL AFTER course_id;

ALTER TABLE material_analysis_batch_items
    ADD COLUMN IF NOT EXISTS zip_entry_id BIGINT NULL AFTER material_id;

ALTER TABLE material_analysis_batch_items
    ADD COLUMN IF NOT EXISTS source_path VARCHAR(1024) NULL AFTER filename;

-- 가져오기 작업자가 "이 항목의 자리"를 찾는 길. 항목 하나는 묶음 하나에만 들어간다
-- (확정은 PENDING 항목만 대기열에 올리므로 같은 항목이 두 번 확정되지 않는다).
CREATE INDEX IF NOT EXISTS idx_material_analysis_batch_items_zip
    ON material_analysis_batch_items (zip_entry_id);
