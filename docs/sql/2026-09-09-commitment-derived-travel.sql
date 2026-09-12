-- 파생 이동 블록의 원본 근무 참조: one_off_commitments에 두 컬럼 추가.
--
-- 기존 docs/sql/*.sql 컨벤션(날짜 파일, 수동 적용)을 따른다. MariaDB 10.4 기준.
--
-- ###############################################################
-- 왜 필요한가 — 제목만으로는 원본과 파생을 나눌 수 없다
--
-- UpcomingWorkShiftsResolver는 제목에 "근무|알바|출근"이 있으면 근무로 골랐다. 그런데
-- 이 기능이 만드는 블록의 제목이 "근무 전 이동", "근무 후 이동"이다 — 자기가 만든
-- 이동 블록이 다음 턴에 다시 원본 근무로 뽑히고, 그 앞뒤로 또 이동이 붙는다.
--
-- 제목 제외 규칙("이동|준비|휴식")만으로 막으면 사용자가 카드에서 제목을 "퇴근길"로
-- 고치는 순간 다시 근무가 된다. 제목은 사용자가 언제든 바꾸는 값이라 분류의 근거가
-- 될 수 없다. 그래서 "이 행은 무엇에서 파생됐는가"를 컬럼으로 남긴다.
--
-- source_type으로는 안 된다. 근무표 이미지 인식으로 등록한 진짜 근무도
-- AI_SUGGESTION_APPROVED다 — 생성 출처와 파생 관계는 다른 사실이다.
-- ###############################################################
--
-- ###############################################################
-- 중복 방지를 조회 후 검사에만 맡기지 않는다
--
-- 같은 근무에 "근무 후 이동"을 두 번 만들면 같은 시간 블록이 두 개 생긴다. 사용자가
-- [적용]을 두 번 누르거나 같은 요청을 다시 말하면 실제로 일어난다.
--
-- uk_commitments_derived가 그것을 DB에서 막는다. (user_id, 원본, 앞/뒤)가 같은 살아
-- 있는 행은 하나뿐이다. MariaDB의 UNIQUE는 NULL을 여러 번 허용하므로, 파생이 아닌
-- 일반 약속(derived_from_commitment_id IS NULL)은 제한을 받지 않는다.
--
-- 소프트 삭제는 derived_from_commitment_id를 NULL로 만든다(CommitmentMapper의
-- softDeleteWithVersion). 지운 이동 블록이 unique 키를 계속 점유하면 사용자가 지우고
-- 다시 만들 수 없기 때문이다. 지워진 행의 원본 참조는 보존 가치가 없다.
-- ###############################################################

ALTER TABLE one_off_commitments
    ADD COLUMN derived_from_commitment_id BIGINT      NULL AFTER source_type,
    ADD COLUMN derived_relation           VARCHAR(20) NULL AFTER derived_from_commitment_id;

-- 두 컬럼은 같이 있거나 같이 없다. 관계만 있고 원본이 없으면 무엇에서 파생됐는지 알 수 없고,
-- 원본만 있고 관계가 없으면 앞인지 뒤인지 알 수 없다.
ALTER TABLE one_off_commitments
    ADD CONSTRAINT chk_commitments_derived CHECK (
        (derived_from_commitment_id IS NULL AND derived_relation IS NULL)
        OR (derived_from_commitment_id IS NOT NULL
            AND derived_relation IN ('BEFORE_WORK', 'AFTER_WORK'))
    );

CREATE UNIQUE INDEX uk_commitments_derived
    ON one_off_commitments (user_id, derived_from_commitment_id, derived_relation);


-- ===============================================================
-- 하위 호환
-- ===============================================================
-- 기존 행은 두 컬럼이 NULL이다. NULL은 "파생이 아님"이고, 그것이 기존 행의 사실이다
-- (이 기능 이전에는 파생 이동을 만들 수 없었다). 백필하지 않는다.
--
-- 이미 제목으로 만들어진 레거시 이동 블록("근무 전 이동" 806 등)은 여전히 NULL이라
-- 원본 참조가 없다. 그것들은 UpcomingWorkShiftsResolver의 제목 제외 규칙이 근무 후보에서
-- 걸러낸다. 분류가 애매한 기존 데이터를 제목만 보고 UPDATE하거나 지우지 않는다 —
-- "근무 후 스터디"처럼 이동이 아닌 행을 잘못 건드릴 수 있다.


-- ===============================================================
-- 적용 확인 (실행 안 함)
-- ===============================================================
--   SELECT COLUMN_NAME, IS_NULLABLE, DATA_TYPE FROM information_schema.COLUMNS
--    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'one_off_commitments'
--      AND COLUMN_NAME IN ('derived_from_commitment_id', 'derived_relation');    -- 2행, YES
--
--   SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS
--    WHERE TABLE_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'chk_commitments_derived';  -- 1행
--
--   SHOW INDEX FROM one_off_commitments WHERE Key_name = 'uk_commitments_derived';     -- 3행
--
-- 같은 원본·같은 관계가 실제로 막히는지 (두 번째가 ERROR 1062여야 정상):
--
--   INSERT INTO one_off_commitments (user_id, title, start_at, end_at, source_type,
--                                    derived_from_commitment_id, derived_relation)
--   VALUES (0, 'dup', '2026-09-08 23:00:00', '2026-09-09 00:00:00',
--           'AI_SUGGESTION_APPROVED', 999999, 'AFTER_WORK');
--
--   INSERT INTO one_off_commitments (user_id, title, start_at, end_at, source_type,
--                                    derived_from_commitment_id, derived_relation)
--   VALUES (0, 'dup2', '2026-09-08 23:00:00', '2026-09-09 00:00:00',
--           'AI_SUGGESTION_APPROVED', 999999, 'AFTER_WORK');
--
-- 파생이 아닌 행은 제한 없음 (둘 다 성공해야 정상):
--
--   INSERT INTO one_off_commitments (user_id, title, start_at, end_at, source_type)
--   VALUES (0, 'plain', '2026-09-08 10:00:00', '2026-09-08 11:00:00', 'MANUAL');
--
--   INSERT INTO one_off_commitments (user_id, title, start_at, end_at, source_type)
--   VALUES (0, 'plain', '2026-09-08 10:00:00', '2026-09-08 11:00:00', 'MANUAL');
--
--   DELETE FROM one_off_commitments WHERE user_id = 0;
