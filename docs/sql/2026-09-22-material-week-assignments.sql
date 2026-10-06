-- =====================================================================
-- 2026-09-22 자료 주차: 추천은 계산하고, 확정은 사용자가 한다
-- =====================================================================
--
-- 앞선 마이그레이션: docs/sql/2026-09-21-project-tidy-review.sql. 이 파일은 그 뒤에 적용한다.
--
-- 왜 필요한가.
--   학습 지도의 주차는 "학습 항목 → 연결된 구간·자료 → 구간 제목이나 분석 메타의 N주차"로
--   역추론한 값이었다. 강의계획서의 "1~3주차" 같은 예정 진도 구간이 그대로 실제 주차가 됐고
--   (정규식이 끝 숫자만 잡아 3주차가 됐다), 본문에 "3주차"라고 적지 않은 실제 강의 자료는
--   어느 주차에도 들어가지 못했다. 네트워크프로그래밍·센서활용프로그래밍 두 과목에서 확인했다.
--
--   이제 실제 주차의 기준은 <사용자가 확인한 자료 ↔ 주차 관계> 하나다. 규칙과 분석 결과는
--   주차를 추천만 하고, 추천은 저장하지 않고 요청마다 계산한다(근거 신호는 이미 자료·분석
--   결과에 있다). 사용자가 적용하거나 직접 옮긴 것만 이 표에 들어간다.
--
-- 무엇을 더하는가.
--   material_week_assignments (새 표)
--       프로젝트(course) 안에서 자료 하나가 어디에 놓였는가. 자료는 여러 프로젝트에 연결될 수
--       있으므로 (course_id, material_id)가 단위다. 한 자료가 여러 주차에 놓일 수 있다(행 여러 개).
--       placement:
--         WEEK        week_no 주차의 실제 수업 자료(1~30)
--         COURSE_WIDE 특정 주차가 아닌 전체 참고자료(강의계획서 등). week_no = 0
--         UNASSIGNED  사용자가 "주차 없음"으로 확인했다. week_no = 0. 같은 추천을 다시 묻지 않는다
--       source:
--         SUGGESTION  추천을 사용자가 적용했다(화면: "확인됨")
--         USER        사용자가 직접 골랐다(화면: "직접 지정")
--       재분석·정리·자동 분석은 이 표를 쓰지 않는다. 쓰는 길은 주차 확인 API 하나뿐이다.
--
--   course_materials.document_title / document_title_read (NULL 허용 열 + 기본값 0 열)
--       PDF·PPTX 파일 속성의 제목. 본문 추출과 따로 보존해 주차 추천과 자료 분석에 넘긴다.
--       document_title_read = 0은 "아직 안 읽었다"이고, 1이면 읽었다(제목이 없으면 NULL).
--       기존 자료는 0으로 남고, 주차 확인 화면을 처음 열 때 파일에서 한 번 읽어 채운다.
--
-- 기존 데이터: 옮기지 않는다. 옛 weekLabel(분석 메타)·구간 제목의 주차를 확정 관계로 바꾸지 않는다 —
--   바로 그 값이 틀렸던 것이다. 적용 뒤 학습 지도의 주차별 보기는 사용자가 확인한 자료가 생길 때까지
--   비어 있고, 대신 "자료 주차 확인 필요 N개"가 보인다.
--
-- 다시 돌려도 된다: IF NOT EXISTS(MariaDB 10.4에서 확인).
--
-- 배포 순서: 이 마이그레이션 → 서버 → 화면.
--   새 서버는 새 열·새 표를 읽고 쓴다. 적용하지 않은 DB에 새 서버를 띄우면 자료 조회·업로드가
--   "Unknown column document_title"로 실패한다. 반드시 먼저 적용한다.
--   옛 서버는 새 열·새 표를 모르므로 적용 뒤에도 그대로 동작한다.
--
-- 되돌리기(필요할 때만, 새 서버를 내린 뒤):
--   DROP TABLE IF EXISTS material_week_assignments;
--   ALTER TABLE course_materials DROP COLUMN document_title_read, DROP COLUMN document_title;
-- =====================================================================

CREATE TABLE IF NOT EXISTS material_week_assignments (
    assignment_id BIGINT      NOT NULL AUTO_INCREMENT,
    user_id       BIGINT      NOT NULL,
    course_id     BIGINT      NOT NULL,
    material_id   BIGINT      NOT NULL,
    placement     VARCHAR(20) NOT NULL,
    week_no       SMALLINT    NOT NULL DEFAULT 0,
    source        VARCHAR(20) NOT NULL,
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (assignment_id),
    -- 같은 자리에 두 번 놓이지 않는다. 동시에 두 탭이 같은 주차로 옮겨도 한 행이다.
    UNIQUE KEY uk_material_week_assignments_slot (course_id, material_id, placement, week_no),
    KEY idx_material_week_assignments_owner (user_id, course_id),
    CONSTRAINT chk_material_week_assignments_placement CHECK (
        (placement = 'WEEK' AND week_no BETWEEN 1 AND 30)
        OR (placement IN ('COURSE_WIDE', 'UNASSIGNED') AND week_no = 0)),
    CONSTRAINT chk_material_week_assignments_source CHECK (source IN ('SUGGESTION', 'USER'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;

ALTER TABLE course_materials
    ADD COLUMN IF NOT EXISTS document_title VARCHAR(300) NULL AFTER extraction_warning;

ALTER TABLE course_materials
    ADD COLUMN IF NOT EXISTS document_title_read TINYINT(1) NOT NULL DEFAULT 0 AFTER document_title;
