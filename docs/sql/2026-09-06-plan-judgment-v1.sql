-- 기간 학습 계획 판단층 v1 — 마감 시각, 판단 산출물, 사용자 익숙함 표식.
--
-- 설계 근거는 docs/product/13-plan-judgment.md에 있다. 요지는 세 가지다.
--   1) 실행 조각에 "이 시각까지 끝나야 의미가 있다"를 담을 자리가 없었다. 제안 단계의
--      deadline_date는 확정에서 버려지고 롤링 배치는 항상 deadline=null로 돌았다.
--   2) "이번 기간을 왜 이렇게 운영하는가"라는 판단이 어디에도 저장되지 않았다.
--   3) "이건 이미 알아요"라는 사용자의 사실을 받을 자리가 없었다.
--
-- MariaDB 10.4 기준. 신규 컬럼이므로 FK 없이 VARCHAR/DATETIME + CHECK.
-- 인덱스는 만들지 않는다 — 세 컬럼 모두 조회 조건이 아니라 이미 고른 행의 속성이다.


-- ===============================================================
-- 1. execution_items — 이 시각까지 끝나야 계획상 의미가 있다
-- ===============================================================
-- DATE가 아니라 DATETIME이다. 근거가 "다음 수업 시작 시각"이라 날짜만으로는 표현되지
-- 않는다 — 수요일 10시 수업이면 화요일 자정이 아니라 수요일 10시가 마감이고, 그 사이
-- 수요일 새벽은 여전히 쓸 수 있는 시간이다. Timefold의 기존 HARD 제약
-- (SchedulingConstraintProvider.taskPastDeadline)이 이미 LocalDateTime을 받는다.
--
-- 기존 ai_proposal_items.original_payload의 deadlineDate(날짜)와 공존하지 않는다.
-- 그쪽은 제안·미리보기까지만 살아 있고 확정에서 끊겼다 — 이 컬럼이 그 끊긴 자리를
-- 잇는다. 확정 시 deadline_at이 없고 deadlineDate만 있으면 다음날 00:00으로 변환해
-- 여기에 넣는다(미리보기가 쓰던 변환과 같은 규칙).
--
-- 배치 무결성 CHECK(chk_execution_items_placement)에는 손대지 않는다. 마감은 배치가
-- 아니라 배치의 제약이고, 마감이 지난 조각이 존재하는 것 자체는 위반이 아니다
-- (계획대로 못 한 것은 회고가 다룰 사실이지 DB가 막을 일이 아니다).
ALTER TABLE execution_items
    ADD COLUMN deadline_at DATETIME NULL
        COMMENT '이 시각까지 끝나야 계획상 의미가 있음. 스케줄러 HARD 제약'
        AFTER scheduled_end_at;


-- ===============================================================
-- 2. plan_versions — 이 버전을 만든 판단
-- ===============================================================
-- items_snapshot과 같은 성격의 불변 역사다. "무엇을 하기로 했는가"(items_snapshot) 옆에
-- "왜 그렇게 하기로 했는가"(strategy_json)를 둔다. plan_versions에는 UPDATE 경로가 없어
-- 불변성은 매퍼가 강제한다.
--
-- 별도 plan_strategies 테이블로 빼지 않는다. 버전은 그대로인데 전략만 바뀌는 시나리오가
-- 없고, 그런 시나리오가 생긴다면 그것은 새 버전이다.
--
-- 전략의 유효기간은 이 행의 end_date다. strategy 안에 validUntil을 따로 두지 않는다 —
-- 두 값이 어긋날 여지를 만들지 않는다(routines가 duration_minutes를 두지 않은 것과 같은 이유).
--
-- 타입은 JSON이다. MariaDB에서 JSON은 LONGTEXT 별칭이지만 컬럼에 인라인
-- CHECK (json_valid(...))를 자동으로 붙여준다 — 그래서 JSON_VALID 제약을 따로 쓰지 않는다
-- (실제로 써 보고 중복인 것을 확인했다). items_snapshot이 chk_plan_versions_snapshot으로
-- 같은 것을 명시적으로 걸고 있는데, 그쪽은 LONGTEXT로 만들어졌기 때문이다.
ALTER TABLE plan_versions
    ADD COLUMN strategy_json JSON NULL
        COMMENT '이 버전을 만든 판단. 불변. strategySource로 NEW/REUSED 구분'
        AFTER items_snapshot;


-- ===============================================================
-- 3. ai_proposals — 확정 전까지 전략을 들고 있는 자리
-- ===============================================================
-- plan_intensity / plan_target_minutes와 같은 이유로 컬럼을 만든다. 확정 요청은 전략을
-- 받지 않고 여기서 읽으므로 초안과 확정이 어긋날 경로 자체가 없다. 클라이언트가 다시
-- 보내는 값으로 확정하면 사용자가 화면에서 본 판단과 저장된 판단이 달라질 수 있다.
--
-- 인수인계 문서는 "별도 컬럼 없이 기존 payload에 동봉"이라고 했지만 ai_proposals에는
-- 항목 단위가 아닌 제안 단위의 범용 payload 컬럼이 없다(unavailable_windows는 이름
-- 그대로 사용 불가 시간 전용이다). 계획 메타데이터가 이미 컬럼으로 붙어 있는 관례를 따른다.
ALTER TABLE ai_proposals
    ADD COLUMN plan_strategy_json JSON NULL
        COMMENT '초안 시점의 판단. 확정 시 plan_versions.strategy_json으로 복사된다'
        AFTER plan_target_minutes;


-- ===============================================================
-- 4. course_topics — 사용자가 말한 사실
-- ===============================================================
-- topic 단위다. 자료(파일) 단위가 아니다 — 사용자가 아는 것은 "이 내용"이지 "이 파일"이
-- 아니고, 같은 내용이 여러 자료에 걸쳐 있다.
--
-- AI는 이 컬럼에 쓰지 않는다. 판단층은 읽기만 하고, 값을 바꾸는 것은 사용자의 명시적
-- 행동(「이미 알아요」/「나중에」)뿐이다. 사용 기록으로 자동 조정하지 않는다 — 관찰은
-- 사용자에게 물어볼 근거이지 상태를 바꿀 근거가 아니다.
--
-- topic_progress.status와 다른 축이다. progress는 "이 앱에서 학습했는가"(앱이 관찰한
-- 사실)이고 user_mark는 "이미 알고 있는가"(사용자가 말한 사실)다. 앱을 쓰기 전부터 알던
-- 내용은 progress로 표현할 방법이 없다.
--
-- NULL이 기본이고 "모른다"는 뜻이다. "모른다"와 "모른다고 답했다"를 구분하지 않는다 —
-- 구분해봐야 판단이 같다(둘 다 근거 없음 → FULL).
ALTER TABLE course_topics
    ADD COLUMN user_mark VARCHAR(16) NULL
        COMMENT 'KNOWN | DEFER | NULL. 사용자 결정. AI가 쓰지 않음'
        AFTER status;

ALTER TABLE course_topics
    ADD CONSTRAINT chk_course_topics_user_mark CHECK (
        user_mark IS NULL OR user_mark IN ('KNOWN', 'DEFER')
    );


-- ===============================================================
-- 검증
-- ===============================================================
--   SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE FROM information_schema.COLUMNS
--    WHERE TABLE_SCHEMA = DATABASE()
--      AND ((TABLE_NAME = 'execution_items' AND COLUMN_NAME = 'deadline_at')
--        OR (TABLE_NAME = 'plan_versions'   AND COLUMN_NAME = 'strategy_json')
--        OR (TABLE_NAME = 'ai_proposals'    AND COLUMN_NAME = 'plan_strategy_json')
--        OR (TABLE_NAME = 'course_topics'   AND COLUMN_NAME = 'user_mark'));   -- 4행
--
-- 잘못된 값이 실제로 막히는지:
--   UPDATE course_topics SET user_mark = 'MAYBE' ...    → chk_course_topics_user_mark 위반
--   UPDATE plan_versions SET strategy_json = '{'  ...   → JSON 타입의 인라인 json_valid 위반
--                                                          (오류 메시지에 'plan_versions.strategy_json')
--
-- ★ 알려진 성질: user_mark의 IN 비교는 컬럼 collation(utf8mb4_unicode_ci)이라 대소문자를
-- 가리지 않는다. 'known'도 CHECK를 통과한다. 이 스키마의 기존 CHECK 전부가 같고
-- (chk_plan_versions_intensity 주석 참고) 실제 방어선은 양방향 모두 Java enum이다.
