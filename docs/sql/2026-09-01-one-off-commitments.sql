-- 일회성 시간 점유(약속): one_off_commitments.
--
-- 의미는 딱 하나다: "특정 한 번의 시간 구간을 사용자가 다른 일에 쓸 수 없다."
-- 친구 약속, 병원, 면접, 외출, 행사 같은 것들이다.
--
-- 기존 docs/sql/*.sql 컨벤션(날짜 파일, 수동 적용)을 따른다. MariaDB 10.4 기준.
-- 신규 테이블은 FK를 걸지 않는다(2026-08-16-material-store.sql에서 정한 컨벤션).
-- 이 테이블에는 자식 테이블이 없어 고아 행이 생길 자리가 없고, user_id 소유권은 모든
-- 조회·변경 쿼리가 조건으로 들고 있다.
--
-- ###############################################################
-- 왜 새 테이블인가 — 세 가지 대안을 다 버린 이유
--
-- 1) execution_items에 "완료 대상 아님" 컬럼을 추가한다:
--    execution_items는 "사용자가 수행하는 행동"이고 완료/일부/축소/보류/ExecutionRecord가
--    전부 그 전제 위에 서 있다. 약속을 거기 넣으면 그 전제가 컬럼 값에 따라 참이 됐다
--    거짓이 됐다 하고, 이후 모든 실행 경로가 "이건 진짜 실행 조각인가"를 먼저 물어야 한다.
--
-- 2) 하루짜리 routine으로 저장한다:
--    routines는 요일 + 유효기간이라는 반복 규칙이 본체다. 일회성을 거기 넣으면 "반복
--    일정" 목록과 화면이 반복이 아닌 것으로 오염되고, 전개 규칙도 한 건을 위해 돈다.
--
-- 3) AI의 UnavailableWindowSpec을 영구 저장으로 승격한다:
--    그건 한 번의 제안 계산에만 쓰는 임시값이고(AI_INFERRED), 사용자가 확정한 사실이
--    아니다. 역할을 바꾸면 "추론값"과 "확정 사실"의 경계가 사라진다.
--
-- 그래서 세 원본(ExecutionItem / Routine / OneOffCommitment)을 분리한 채 두고, 합치는
-- 것은 화면과 가용시간 계산에서만 한다.
-- ###############################################################
--
-- ###############################################################
-- 반복 컬럼을 넣지 않는다
--
-- weekdays / repeat_type / rrule / effective_from / effective_until 어느 것도 없다.
-- 반복이면 routines다. 여기에 반복을 열어 두면 같은 개념이 두 테이블에 생기고, 화면과
-- 배치가 어느 쪽을 봐야 하는지 매번 정해야 한다.
-- ###############################################################
--
-- ###############################################################
-- 자정 넘김: DATETIME이라 규칙이 필요 없다
--
-- routines는 시각만 저장하므로 end_time <= start_time을 "다음 날"로 읽는 규칙이 있다.
-- 여기는 start_at/end_at이 DATETIME이라 22:00~다음날 02:00이 그대로 표현된다:
--   2026-09-04 22:00:00 ~ 2026-09-05 02:00:00
-- 그래서 chk_commitments_time을 start_at < end_at으로 단순하게 걸 수 있고, 같은 시각
-- (길이 0)도 이 하나로 막힌다.
-- ###############################################################

CREATE TABLE one_off_commitments (
    commitment_id BIGINT       NOT NULL AUTO_INCREMENT,
    user_id       BIGINT       NOT NULL,

    title         VARCHAR(200) NOT NULL,

    start_at      DATETIME     NOT NULL,
    end_at        DATETIME     NOT NULL,

    location_text VARCHAR(100) NULL,

    -- 클라이언트가 보내는 값이 아니다. 직접 생성이면 서버가 MANUAL을, AI 후보 승인이면
    -- 서버 내부에서 AI_SUGGESTION_APPROVED를 넣는다. payload로 출처를 위조할 자리를
    -- 만들지 않는다.
    source_type   VARCHAR(30)  NOT NULL,

    -- 낙관적 락. routines에는 없지만(화면 하나에서만 쓰므로) 약속은 직접 추가와 AI 후보
    -- 승인 두 경로에서 만들어지고 수정·삭제도 두 화면에서 가능하다.
    version       BIGINT       NOT NULL DEFAULT 0,

    is_deleted    TINYINT(1)   NOT NULL DEFAULT 0,

    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                               ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (commitment_id),

    -- 기간 조회는 "시작일이 범위 안"이 아니라 "구간이 범위와 겹침"이다. 전날 밤에 시작해
    -- 오늘 새벽에 끝나는 약속이 오늘 조회에서 빠지면 안 된다.
    INDEX idx_commitments_user_time (user_id, start_at, end_at, is_deleted),

    CONSTRAINT chk_commitments_time CHECK (start_at < end_at),
    CONSTRAINT chk_commitments_source CHECK (
        source_type IN ('MANUAL', 'AI_SUGGESTION_APPROVED')
    ),
    CONSTRAINT chk_commitments_deleted CHECK (is_deleted IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ===============================================================
-- 적용 확인 (실행 안 함)
-- ===============================================================
--   SELECT TABLE_NAME FROM information_schema.TABLES
--    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'one_off_commitments';   -- 1행
--
--   SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS
--    WHERE TABLE_SCHEMA = DATABASE()
--      AND CONSTRAINT_NAME IN ('chk_commitments_time', 'chk_commitments_source',
--                              'chk_commitments_deleted');                     -- 3행
--
--   SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE
--    WHERE TABLE_SCHEMA = DATABASE()
--      AND TABLE_NAME = 'one_off_commitments'
--      AND REFERENCED_TABLE_NAME IS NOT NULL;                                  -- 0
--
-- 길이 0/역전 구간이 실제로 막히는지 (둘 다 ERROR 4025여야 정상):
--
--   INSERT INTO one_off_commitments (user_id, title, start_at, end_at, source_type)
--   VALUES (0, 'chk', '2026-09-04 19:00:00', '2026-09-04 19:00:00', 'MANUAL');
--
--   INSERT INTO one_off_commitments (user_id, title, start_at, end_at, source_type)
--   VALUES (0, 'chk', '2026-09-04 21:00:00', '2026-09-04 19:00:00', 'MANUAL');
