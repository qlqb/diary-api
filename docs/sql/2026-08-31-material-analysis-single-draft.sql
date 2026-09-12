-- 구조 분석 DRAFT의 단일성: 같은 (user_id, course_id, material_id)에 열린 DRAFT는 하나뿐.
--
-- 고치는 문제: 프로젝트 화면이 새로고침 후 서버에 남은 DRAFT를 복원하지 않아 검토 폼이
-- 사라지고, 사용자가 "구조 분석"을 다시 누르면 같은 맥락에 DRAFT가 하나 더 쌓인다.
-- AI 호출 비용이 드는 작업인데 아무도 막지 않는다.
--
-- 프론트 복원만 고치면 더블클릭·다중 탭·요청 재시도로 중복이 다시 생긴다. 서버 검사만
-- 고치면 새로고침 후 기존 DRAFT가 화면에서 사라진다. 그래서 이 파일(DB)과 서비스 검사와
-- 화면 복원이 한 묶음이다.
--
-- 기존 docs/sql/*.sql 컨벤션(날짜 파일, 수동 적용)을 따른다. MariaDB 10.4 기준.
--
-- ###############################################################
-- 실행 순서 주의: 2번을 먼저 돌리고 3번을 돌린다.
--
-- 3번의 UNIQUE 인덱스는 이미 중복 DRAFT가 있는 상태에서는 만들어지지 않는다
-- (ERROR 1062). 2번이 그것을 먼저 하나로 줄인다. 순서를 바꾸면 3번이 실패한다.
-- ###############################################################


-- ===============================================================
-- 1. 적용 전 확인 (실행 안 함 — 무엇이 바뀔지 먼저 본다)
-- ===============================================================
-- 중복이 있는 맥락:
--
--   SELECT user_id, course_id, material_id, COUNT(*) AS draft_count
--     FROM course_material_analyses
--    WHERE status = 'DRAFT'
--    GROUP BY user_id, course_id, material_id
--   HAVING COUNT(*) > 1;
--
-- 그 맥락에서 어떤 행이 남고 어떤 행이 DISMISSED가 되는지:
--
--   SELECT a.analysis_id, a.user_id, a.course_id, a.material_id, a.created_at,
--          CASE WHEN a.analysis_id = (
--                 SELECT b.analysis_id FROM course_material_analyses b
--                  WHERE b.user_id = a.user_id AND b.course_id = a.course_id
--                    AND b.material_id = a.material_id AND b.status = 'DRAFT'
--                  ORDER BY b.created_at DESC, b.analysis_id DESC LIMIT 1)
--               THEN 'KEEP' ELSE 'DISMISS' END AS action
--     FROM course_material_analyses a
--    WHERE a.status = 'DRAFT'
--    ORDER BY a.user_id, a.course_id, a.material_id, a.created_at DESC;


-- ===============================================================
-- 2. 기존 중복 DRAFT 정리
-- ===============================================================
-- 같은 맥락에 DRAFT가 여럿이면 최신 하나만 남긴다. 기준은 created_at이 늦은 행이고,
-- 같으면 analysis_id가 큰 행이다.
--
-- 나머지는 삭제하지 않고 DISMISSED로 내린다. analysis_json/edited_json은 이력으로 그대로
-- 남는다 — 사용자가 검토하던 내용이라 조용히 없애지 않는다.
--
-- ★ "최신이 더 낫다"는 보장은 없다. 두 호출의 결과가 서로 다를 수 있고(temperature를
--   낮추지 않았다), 어느 쪽 문장이 나은지는 사람이 볼 문제다. 여기서는 "하나만 남긴다"는
--   불변조건을 세우는 것이 목적이고, 특정 행을 남기고 싶으면 이 UPDATE 전에 그 행의
--   created_at을 보고 직접 고르거나 원하는 쪽만 남기고 나머지를 먼저 DISMISSED로 내린다.
UPDATE course_material_analyses older
JOIN course_material_analyses newer
  ON newer.user_id = older.user_id
 AND newer.course_id = older.course_id
 AND newer.material_id = older.material_id
 AND newer.status = 'DRAFT'
 AND (
      newer.created_at > older.created_at
      OR (newer.created_at = older.created_at AND newer.analysis_id > older.analysis_id)
 )
SET older.status = 'DISMISSED',
    older.applied_at = NULL
WHERE older.status = 'DRAFT';


-- ===============================================================
-- 3. DRAFT에만 걸리는 조건부 유일성
-- ===============================================================
-- UNIQUE(user_id, course_id, material_id, status)는 쓸 수 없다. 그러면 APPLIED·DISMISSED
-- 이력까지 맥락당 하나로 제한되는데, 이력은 여러 건 남아야 한다.
--
-- 대신 status가 DRAFT일 때만 1이고 나머지는 NULL인 생성 컬럼을 두고 거기에 UNIQUE를 건다.
-- UNIQUE 인덱스는 NULL을 여러 개 허용하므로 DRAFT만 서로 충돌하고 나머지 상태는 자유롭다.
--
-- MariaDB 10.4.32에서 아래 문법이 실제로 도는지 임시 테이블로 확인했다(이 저장소에 생성
-- 컬럼 선례가 없어서다). 확인한 동작:
--   - PERSISTENT 생성 컬럼 + UNIQUE 인덱스 생성 성공 (SHOW CREATE에는 STORED로 표시된다)
--   - 같은 맥락 DRAFT 두 번째 INSERT -> ERROR 1062 Duplicate entry
--   - APPLIED 2건 + DISMISSED 1건 + FAILED 1건 동시 존재 성공 (draft_guard가 전부 NULL)
--   - DRAFT를 DISMISSED/APPLIED로 내린 뒤 새 DRAFT INSERT 성공
--
-- draft_guard는 DB 전용 파생 컬럼이다. Java 도메인 객체나 INSERT 컬럼 목록에 넣지 않는다.
-- 상태 전환(updateStatus)만으로 자동으로 NULL이 되어 다음 DRAFT 자리가 열린다.
ALTER TABLE course_material_analyses
    ADD COLUMN draft_guard TINYINT
        AS (CASE WHEN status = 'DRAFT' THEN 1 ELSE NULL END) PERSISTENT
        AFTER status,
    ADD UNIQUE KEY uq_course_material_analyses_single_draft
        (user_id, course_id, material_id, draft_guard);


-- ===============================================================
-- 적용 확인 (실행 안 함)
-- ===============================================================
-- 중복 DRAFT: 0행이 정상
--
--   SELECT user_id, course_id, material_id, COUNT(*) AS draft_count
--     FROM course_material_analyses
--    WHERE status = 'DRAFT'
--    GROUP BY user_id, course_id, material_id
--   HAVING COUNT(*) > 1;
--
-- 생성 컬럼 확인: draft_guard 1행 (EXTRA에 STORED GENERATED)
--
--   SELECT COLUMN_NAME, EXTRA
--     FROM information_schema.COLUMNS
--    WHERE TABLE_SCHEMA = DATABASE()
--      AND TABLE_NAME = 'course_material_analyses'
--      AND COLUMN_NAME = 'draft_guard';
--
-- 유일 인덱스 확인: 인덱스 구성 컬럼 4행, NON_UNIQUE = 0
--
--   SELECT INDEX_NAME, COLUMN_NAME, SEQ_IN_INDEX, NON_UNIQUE
--     FROM information_schema.STATISTICS
--    WHERE TABLE_SCHEMA = DATABASE()
--      AND TABLE_NAME = 'course_material_analyses'
--      AND INDEX_NAME = 'uq_course_material_analyses_single_draft'
--    ORDER BY SEQ_IN_INDEX;
