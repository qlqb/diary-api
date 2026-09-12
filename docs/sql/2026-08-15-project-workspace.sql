-- 프로젝트 중심 UX 재구성.
--
-- "학습(과목)"을 사용자 관점의 "프로젝트"(= AI와 계속 다루고 싶은 하나의 주제/맥락)로 확장한다.
-- 새 generic project 테이블을 만들지 않는다 — courses가 이미 "사용자 소유 + 제목 + 자료 +
-- topic + 상태"를 담고 있고, 이번에 바뀌는 것은 데이터 구조가 아니라 그 단위를 어떻게
-- 사용하는지(대화가 붙고, 실행이 붙는다)이기 때문이다. course_topics에 일반 Todo/개발 작업을
-- 넣지도 않는다 — 실행은 지금도 앞으로도 execution_items가 담당한다.
--
-- 컬럼 3개만 추가한다. rename/삭제/신규 테이블 없음. 전부 NULL 허용이라 기존 행은 그대로 유효하다.
--
-- 기존 docs/sql/*.sql 컨벤션(날짜 파일, 수동 적용)을 따른다. Flyway/Liquibase는 도입하지 않는다.
-- 엔진은 MariaDB 10.4 기준(FK 미사용, 소유권은 서비스 코드에서 user_id로 검증).

-- ===============================================================
-- 1. ai_conversations.course_id
--
-- 프로젝트를 다시 열었을 때 그 프로젝트에서 하던 대화를 이어갈 수 있어야 한다. 대화를
-- 프로젝트에 묶지 않으면 "이 주제를 AI와 계속 이어간다"는 프로젝트의 정의 자체가 성립하지
-- 않는다(매번 새 대화가 되거나, 전체 대화 목록에서 사용자가 직접 찾아야 한다).
--
-- NULL이면 프로젝트에 속하지 않은 대화다(오늘/일정/전체 상담) — 기존 대화는 전부 NULL이다.
-- ===============================================================
ALTER TABLE ai_conversations
    ADD COLUMN course_id BIGINT NULL AFTER scope;

CREATE INDEX idx_ai_conversations_user_course
    ON ai_conversations (user_id, course_id);

-- ===============================================================
-- 2. execution_items.course_id
--
-- 프로젝트 화면의 "관련 실행"과 오늘 화면의 프로젝트 표시에 쓴다.
--
-- 기존 topic_id로는 부족하다: topic은 자료를 업로드하고 구조 분석을 Apply해야만 생긴다.
-- "프로젝트 먼저 만들고 자료는 나중에" 흐름에서는 topic이 하나도 없는 상태로 실행 조각이
-- 만들어지므로, topic_id만으로는 그 조각이 어느 프로젝트 것인지 영원히 알 수 없다.
--
-- topic_id를 대체하지 않는다 — 둘은 다른 축이다(course_id = 어느 프로젝트, topic_id = 그 안의
-- 어느 학습 주제). topic_id가 있으면 course_id도 함께 채운다.
-- ===============================================================
ALTER TABLE execution_items
    ADD COLUMN course_id BIGINT NULL AFTER topic_id;

CREATE INDEX idx_execution_items_user_course
    ON execution_items (user_id, course_id);

-- ===============================================================
-- 3. courses.group_label
--
-- 사용자가 프로젝트를 스스로 묶는 자유 텍스트 라벨(예: 학교 / 자격증 / 영어 / 개인).
-- 별도 group 테이블을 만들지 않는다 — 이 값은 사용자가 목록을 정리해서 보기 위한 표시용
-- 분류일 뿐이고, 그룹 자체가 소유하는 데이터(자료·대화·실행)가 없기 때문이다. 나중에 그룹에
-- 고유한 상태가 생기면 그때 테이블로 승격한다.
--
-- NULL이면 "분류 없음"으로 묶인다.
-- ===============================================================
ALTER TABLE courses
    ADD COLUMN group_label VARCHAR(50) NULL AFTER title;
