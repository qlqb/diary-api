-- 실행 조각 삭제를 되돌릴 수 있게 한다 (Ctrl+Z).
--
-- execution_items.is_deleted는 이미 soft delete라 지운 뒤에도 행이 남아 있다. 되돌리기는
-- 그 플래그를 0으로 되돌리고 version을 올리는 것으로 끝난다 — 새 컬럼도, 새 테이블도
-- 필요 없다.
--
-- 바꿔야 하는 것은 이벤트 타입 CHECK 하나뿐이다. 삭제는 DELETED 이벤트를 남기는데 복구를
-- 남기지 못하면 이벤트 로그가 거짓말을 한다("지웠다"까지만 있고 "되살렸다"가 없다).
--
-- 되돌리기를 별도 테이블(삭제 이력)로 만들지 않는 이유: 지운 행이 그대로 있고 version으로
-- 낙관적 락까지 걸려 있어서, 복구에 필요한 정보가 이미 execution_items에 전부 있다.
--
-- 기존 docs/sql/*.sql 컨벤션(날짜 파일, 수동 적용)을 따른다.
-- MariaDB 10.2+의 ALTER TABLE ... DROP CONSTRAINT를 쓴다.

ALTER TABLE execution_item_events
    DROP CONSTRAINT chk_execution_events_type;

ALTER TABLE execution_item_events
    ADD CONSTRAINT chk_execution_events_type
        CHECK (event_type IN ('CREATED', 'MOVED', 'REDUCED', 'SPLIT', 'HOLD', 'RESUMED',
                              'REOPENED', 'CANCELLED', 'PRIORITY_CHANGED', 'DELETED', 'RESTORED'));

-- 검증
--   SHOW CREATE TABLE execution_item_events\G
--   → chk_execution_events_type에 RESTORED가 포함되어야 한다.
