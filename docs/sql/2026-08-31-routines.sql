-- 반복 일정(루틴): routines + routine_weekdays + routine_exceptions.
--
-- 매주 반복되는 고정 일정(수업·알바·운동)을 규칙으로 저장하고, 배치가 그 시간을 피하게 한다.
-- 지금은 수업 시간을 앱에 넣는 방법이 TIME_FIXED 실행 조각을 매주 손으로 만드는 것뿐이라
-- 주 5과목이면 매주 5~15개를 손으로 넣어야 하고, 안 넣으면 배치가 수업 시간에 학습을 앉힌다.
--
-- 기존 docs/sql/*.sql 컨벤션(날짜 파일, 수동 적용)을 따른다. MariaDB 10.4 기준.
-- 신규 테이블은 FK를 걸지 않는다(2026-08-16-material-store.sql에서 정한 컨벤션,
-- plan_versions도 같다). 소유권은 서비스 코드에서 user_id로 검증하고, 예외의 소유권은
-- routines를 JOIN해서 확인한다.
--
-- FK가 없어도 잃는 것이 거의 없다: 루틴 삭제는 is_deleted = 1 소프트 삭제라 CASCADE가
-- 실질적으로 도는 일이 없다. 요일·예외 행은 남고, 그게 맞다 — 복구할 수 있어야 한다.
--
-- ###############################################################
-- 주의: 이 파일은 execution_items.routine_id와 무관하다.
--
-- execution_items에 routine_id 컬럼과 idx_execution_items_routine, 그리고
-- ExecutionOriginType.ROUTINE_GENERATED가 이미 있다. 전부 레거시 todos 스키마의 잔재이고
-- (실 데이터 전부 NULL), 가리킬 테이블이 없었다. 이번에도 쓰지 않는다.
--
-- 반복 일정의 발생분은 행으로 만들지 않는다. 행을 미리 만들면 "몇 개를 미리 만들 것인가"에
-- 답이 없다 — 무기한 루틴은 끝이 없다. 발생분은 조회할 때마다 규칙에서 계산해 응답에만
-- 담는다(RoutineOccurrenceService.expand). v1에 필요한 것은 "피하기"와 "보이기"뿐이고
-- 둘 다 이걸로 된다.
--
-- 따라서 execution_items.routine_id는 이 테이블의 FK가 아니며, 채우지 않는다.
-- ###############################################################


-- ===============================================================
-- 1. routines: 반복 규칙 자체
-- ===============================================================
-- 종료는 effective_until 하나가 담당한다. status(ACTIVE/ARCHIVED)나 archived_on을 두지
-- 않는다 -- 루틴이 끝나는 경우는 둘뿐이고 둘 다 이 컬럼으로 표현된다. 수업은 만들 때
-- 종강일을 채우고, 기한 없이 시작한 알바·운동은 그만두는 날 이 값을 채운다.
--
-- 상태 컬럼을 두면 표시와 배치가 서로 다른 상태 집합을 읽게 되어, 화면에는 보이는데
-- 배치에서는 빠지는 어긋남이 가능해진다. 화면의 "종료됨" 뱃지는 저장하지 않고 계산한다.
--
-- course_id는 nullable 선택 참조다. 프로젝트에 묶이는 루틴(수업)은 채우고, 아닌 것
-- (알바·운동)은 null이다 -- 루틴은 수업 전용이 아니다.
--
-- 길이 컬럼(duration_minutes)을 두지 않는다. 시각이 유일한 진실이고, 길이가 필요하면
-- endAt - startAt으로 계산한다. 두 값을 따로 저장하면 어긋날 수 있고, 검증으로 막는 것보다
-- 어긋날 여지 자체를 없애는 쪽이 낫다(expected_minutes가 실제로 그렇게 어긋났다).
CREATE TABLE routines (
    routine_id       BIGINT       NOT NULL AUTO_INCREMENT,
    user_id          BIGINT       NOT NULL,
    course_id        BIGINT       NULL,
    title            VARCHAR(200) NOT NULL,
    location         VARCHAR(100) NULL,
    start_time       TIME         NOT NULL,
    end_time         TIME         NOT NULL,
    effective_from   DATE         NOT NULL,
    effective_until  DATE         NULL,
    is_deleted       TINYINT(1)   NOT NULL DEFAULT 0,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                                  ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (routine_id),
    CONSTRAINT chk_routines_range CHECK (effective_until IS NULL
                                         OR effective_until >= effective_from),
    -- 길이 0(또는 24시간)을 막는다. end_time < start_time은 막지 않는다 -- 자정 넘김이
    -- 정상값이다. 알바 근무표의 CL = 15~00이 실제 사례이고, 22:00~02:00도 같다.
    -- end_time <= start_time이면 다음 날로 넘어간다는 뜻으로 읽는다.
    CONSTRAINT chk_routines_span  CHECK (start_time <> end_time),
    CONSTRAINT chk_routines_flags CHECK (is_deleted IN (0, 1)),
    INDEX idx_routines_user (user_id, is_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ===============================================================
-- 2. routine_weekdays: 그 루틴이 도는 요일
-- ===============================================================
-- CSV 컬럼(MONDAY,THURSDAY)로 두지 않는다. VARCHAR(32)로는 월~금만 해도 부족하고
-- (전체 이름 기준 약 40자), 무엇보다 표기 규칙을 코드가 지켜야 한다 -- 정렬, 중복 제거,
-- 약어(MON) 대 전체 이름(MONDAY) 혼용을 전부 애플리케이션이 관리하게 된다. 이 명세의
-- 초안이 실제로 본문과 테스트에서 표기가 갈렸다.
--
-- 루틴당 최대 7행이라 성능 문제가 없고, CHECK가 값을 강제한다.
-- 갱신은 "전부 지우고 다시 넣기"다. 부분 갱신을 만들지 않는다.
CREATE TABLE routine_weekdays (
    routine_id  BIGINT     NOT NULL,
    day_of_week VARCHAR(9) NOT NULL,
    PRIMARY KEY (routine_id, day_of_week),
    CONSTRAINT chk_routine_weekday CHECK (
        day_of_week IN ('MONDAY','TUESDAY','WEDNESDAY','THURSDAY',
                        'FRIDAY','SATURDAY','SUNDAY')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ===============================================================
-- 3. routine_exceptions: 그 주만 쉬거나 옮기는 경우
-- ===============================================================
-- 강의계획서의 예외는 전부 "이동"이다(추석 보강 9/24 -> 10/1 등). EXDATE는 "그날 없음"만
-- 표현해 절반만 쓰게 되므로, type: SKIP | MOVED 한 행으로 둘 다 표현한다.
--
-- user_id를 두지 않는다. routine_id가 이미 소유자를 가리키는데 여기에도 두면
-- routine.user_id = 1인데 exception.user_id = 2인 상태가 만들어질 수 있다 -- 같은 사실을
-- 두 곳에 저장하면 어긋난다. 소유권은 routines를 JOIN해서 확인한다.
--
-- exception_date는 원래 발생했어야 할 날짜다. 이동한 날이 아니다.
-- moved_start_time/moved_end_time이 null이면 원래 시각을, moved_location이 null이면
-- 원래 장소를 쓴다. 보강은 강의실이 바뀌는 경우가 흔하지만 강의계획서에 안 적혀 있어
-- 대부분 null이 된다 -- 사용자가 나중에 채울 자리다.
--
-- 초·나노는 CHECK로 못 막는다. 서비스에서 MinutePrecision으로 검증한다.
CREATE TABLE routine_exceptions (
    routine_exception_id BIGINT       NOT NULL AUTO_INCREMENT,
    routine_id           BIGINT       NOT NULL,
    exception_date       DATE         NOT NULL,
    type                 VARCHAR(10)  NOT NULL,
    moved_date           DATE         NULL,
    moved_start_time     TIME         NULL,
    moved_end_time       TIME         NULL,
    moved_location       VARCHAR(100) NULL,
    note                 VARCHAR(200) NULL,
    created_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                                      ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (routine_exception_id),
    CONSTRAINT chk_routine_exceptions_type CHECK (type IN ('SKIP','MOVED')),
    -- 막는 것: SKIP인데 moved_location만 있는 상태, MOVED인데 시각이 한쪽만 있는 상태,
    -- 이동 시각의 길이가 0인 상태.
    CONSTRAINT chk_routine_exceptions_moved CHECK (
        (type = 'SKIP'
             AND moved_date       IS NULL
             AND moved_start_time IS NULL
             AND moved_end_time   IS NULL
             AND moved_location   IS NULL)
     OR (type = 'MOVED'
             AND moved_date IS NOT NULL
             AND ((moved_start_time IS NULL AND moved_end_time IS NULL)
                  OR (moved_start_time IS NOT NULL
                      AND moved_end_time IS NOT NULL
                      AND moved_start_time <> moved_end_time)))
    ),
    UNIQUE KEY uq_routine_exceptions (routine_id, exception_date),
    -- 이동 목적지로 조회하는 경로(전개 2단계)가 따로 있다. 원래 날이 조회 창 밖이고
    -- 목적지만 창 안인 경우가 흔하기 때문이다.
    INDEX idx_routine_exceptions_moved (routine_id, moved_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ===============================================================
-- 적용 확인
-- ===============================================================
--   SELECT TABLE_NAME FROM information_schema.TABLES
--    WHERE TABLE_SCHEMA = DATABASE()
--      AND TABLE_NAME IN ('routines','routine_weekdays','routine_exceptions');   -- 3행
--
--   SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS
--    WHERE TABLE_SCHEMA = DATABASE()
--      AND CONSTRAINT_NAME IN ('chk_routines_range','chk_routines_span',
--                              'chk_routines_flags','chk_routine_weekday',
--                              'chk_routine_exceptions_type',
--                              'chk_routine_exceptions_moved');                  -- 6행
--
--   SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE
--    WHERE TABLE_SCHEMA = DATABASE()
--      AND TABLE_NAME LIKE 'routine%' AND REFERENCED_TABLE_NAME IS NOT NULL;     -- 0
