-- =====================================================================
-- 2026-10-06 이벤트 기반 학습 진도 1단계 C — 수업 회차 · 수업 후 확인 끄기
-- =====================================================================
--
-- 앞선 마이그레이션: docs/sql/2026-10-06-learning-events.sql. 이 파일은 그 뒤에 적용한다.
-- 설계: docs/product/20-learning-events.md §5.1·§8, 계획: docs/handoff/learning-events-stage1-plan-2026-10-06.md §4·§11.
--
-- 추가형이다(새 표·기본값 있는 열). 기존 행의 뜻을 바꾸지 않는다.
--   - class_sessions는 시간표(routines)를 펼친 수업 한 번. (루틴, 원래 날짜)가 열쇠라 시간표를 고쳐도 같은 회차다.
--     처음 필요할 때(수업 확인·일정 예외) 만들고, 만들 때의 시각을 스냅샷으로 남긴다.
--   - courses.class_prompt_enabled 기본 1(묻는다). 0이면 그 과목은 수업 후·주간 확인을 묻지 않는다.
-- 다시 돌려도 된다(IF NOT EXISTS, MariaDB 10.4).
--
-- 배포 순서: 이 마이그레이션 → 서버 → 화면.
--
-- 적용 후 확인:
--   SHOW CREATE TABLE class_sessions;
--   SHOW COLUMNS FROM courses LIKE 'class_prompt_enabled';
--
-- 되돌리기(새 서버를 내린 뒤):
--   DROP TABLE IF EXISTS class_sessions;
--   ALTER TABLE courses DROP COLUMN IF EXISTS class_prompt_enabled;
-- =====================================================================

CREATE TABLE IF NOT EXISTS class_sessions (
    session_id  BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    course_id   BIGINT       NOT NULL,
    routine_id  BIGINT       NOT NULL,
    source_date DATE         NOT NULL,
    start_at    DATETIME     NOT NULL,
    end_at      DATETIME     NOT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id),
    UNIQUE KEY uq_class_sessions_slot (routine_id, source_date),
    INDEX idx_class_sessions_course (user_id, course_id, source_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE courses ADD COLUMN IF NOT EXISTS class_prompt_enabled TINYINT(1) NOT NULL DEFAULT 1;
