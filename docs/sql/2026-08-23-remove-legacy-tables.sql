-- 대체 완료된 레거시 실행 모델 제거: todos / schedule_blocks / plan_items 계열 / daily_plans.
--
-- 이 파일은 기능 변경이 아니라 이미 끝난 이관의 뒷정리다. 2026-08-03에 todos 3건과
-- schedule_blocks 16건이 전부 execution_items로 옮겨졌고, 그 출처는
-- legacy_execution_item_map(19행)에 남아 있다. 미이관 0건을 확인한 뒤 지운다.
--
--   SELECT COUNT(*) FROM schedule_blocks s WHERE NOT EXISTS (
--     SELECT 1 FROM legacy_execution_item_map m
--      WHERE m.source_type='SCHEDULE_BLOCK' AND m.source_id = s.schedule_block_id);  -- 0
--   SELECT COUNT(*) FROM todos t WHERE NOT EXISTS (
--     SELECT 1 FROM legacy_execution_item_map m
--      WHERE m.source_type='TODO' AND m.source_id = t.todo_id);                      -- 0
--
-- legacy_execution_item_map은 남긴다. 원본 테이블이 사라져도 "이 실행 조각이 어디서
-- 왔는가"는 정보다 — 참조 무결성을 위해서가 아니라 이력으로서 남긴다.
--
-- 되돌리려면 docs/sql/backup/2026-08-23-legacy-tables.sql을 적용한다. 6개 테이블의
-- 스키마·데이터·FK 정의가 전부 들어 있고, 별도 DB에 복원해 행 수 일치를 확인했다.
--
-- 기존 docs/sql/*.sql 컨벤션(날짜 파일, 수동 적용)을 따른다. MariaDB 10.4 기준.


-- ===============================================================
-- 1. execution_items.plan_item_id 제거
-- ===============================================================
-- plan_items를 가리키는 유일한 살아있는 참조다. 값은 전 행 NULL이었다
-- (SELECT COUNT(*) FROM execution_items WHERE plan_item_id IS NOT NULL; -- 0).
--
-- 기획서 v6.2 §9.4의 "계획 항목 → 실행 조각" 연결로 설계됐지만 한 번도 채워지지 않았고,
-- 그 역할은 이제 plan_versions.items_snapshot이 맡는다. 인덱스는 컬럼과 함께 사라진다.
ALTER TABLE execution_items DROP FOREIGN KEY fk_execution_items_plan_user;
ALTER TABLE execution_items DROP INDEX idx_execution_items_plan_user;
ALTER TABLE execution_items DROP COLUMN plan_item_id;


-- ===============================================================
-- 2. plan_items 계열 제거
-- ===============================================================
-- plan_item_context_links(0행)가 plan_items를 FK로 붙잡고 있어 먼저 지운다.
-- 두 테이블 모두 코드 참조가 없다 — 매퍼도 서비스도 컨트롤러도 없었다.
DROP TABLE plan_item_context_links;
DROP TABLE plan_items;

-- plan_item_events(21행)는 이름과 달리 plan_item_id 컬럼이 없다. todo_id/schedule_block_id를
-- 키로 하는 "스케줄 블록 변경 이력"이고, 마지막 쓰기가 2026-07-09다. 대상이 사라지므로
-- 함께 지운다. 같은 성격의 현행 이력은 execution_item_events가 이어받았다.
DROP TABLE plan_item_events;


-- ===============================================================
-- 3. schedule_blocks / todos 제거
-- ===============================================================
-- schedule_blocks가 todos를 FK로 참조하므로 순서를 지킨다.
-- schedule_blocks.daily_plan_id는 FK가 없어 따로 끊을 것이 없다.
DROP TABLE schedule_blocks;
DROP TABLE todos;


-- ===============================================================
-- 4. daily_plans 제거
-- ===============================================================
-- 인수인계 v3 §2는 이 테이블을 day_settings로 개명해 "하루 설정" 층으로 승격시키려 했다.
-- 그 계획을 접고 지운다 — 유일한 호출자였던 ScheduleBlockActionService가 3절에서 사라지면
-- 쓰는 코드가 하나도 남지 않기 때문이다. 1행(2026-07-08 생성, 이후 갱신 0회)짜리 빈
-- 테이블에 새 이름만 붙여 두면, 이름은 새것인데 아무도 안 쓰는 상태가 굳는다.
--
-- 하루 설정(view_mode / intensity / condition_note)이 실제로 필요해지면 그때 day_settings로
-- 새로 만든다. 지금 스키마를 그대로 살릴 이유가 없고, 컬럼 구성도 그때 다시 정하는 편이 낫다.
DROP TABLE daily_plans;


-- ===============================================================
-- 검증
-- ===============================================================
-- 아래가 전부 0이어야 한다.
--
--   SELECT COUNT(*) FROM information_schema.TABLES
--    WHERE TABLE_SCHEMA = DATABASE()
--      AND TABLE_NAME IN ('todos','schedule_blocks','plan_items',
--                         'plan_item_events','plan_item_context_links','daily_plans');
--
--   SELECT COUNT(*) FROM information_schema.COLUMNS
--    WHERE TABLE_SCHEMA = DATABASE()
--      AND TABLE_NAME = 'execution_items' AND COLUMN_NAME = 'plan_item_id';
--
-- 그리고 이관 이력은 그대로 남아 있어야 한다 (19행).
--
--   SELECT COUNT(*) FROM legacy_execution_item_map;
