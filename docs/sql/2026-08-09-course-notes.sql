-- Material Agent가 강의계획서에서 찾아낸 내용 중 "실제 학습 내용이 아닌" 과목 운영/평가 정보를
-- 담는 테이블. 기존에는 이런 항목이 학습 topic과 구분 없이 course_topics에 함께 섞여 들어가
-- LearningMap을 오염시켰다 — 이제 Material Agent 분석 단계에서부터 topics(학습 내용)와
-- courseNotes(과목 정보/평가 정보)를 분리해서 만들고, apply() 시점에 각자 다른 곳에 확정한다.
--
-- course_topics 테이블 자체는 건드리지 않는다 — 분류가 "생성 이전" 단계에서 끝나므로, 새로
-- 만들어지는 topic은 이제 전부 실제 학습 내용뿐이다. 기존에 이미 저장된(오염된) topic은
-- 이 마이그레이션으로 임의 추측 변환하지 않는다(잘못 지울 위험이 확실성보다 크다) — 새 자료
-- 분석부터 올바르게 쌓이고, 기존 과목은 사용자가 Material Review에서 자료를 다시 분석해
-- 정리할 수 있다.

CREATE TABLE course_notes (
    note_id             BIGINT       NOT NULL AUTO_INCREMENT,
    user_id             BIGINT       NOT NULL,
    course_id           BIGINT       NOT NULL,
    -- COURSE_INFO: 과목 설명/담당교수/연락처/수업 운영 정보/사용 도구/수업 지원 안내 등.
    -- ASSESSMENT: 중간/기말고사, 과제, 평가 비율, 주차별 일정처럼 평가·운영 일정에 관한 사실.
    -- 날짜가 명확한 항목은 course_material_analyses의 keyDates가 이미 담당하므로 여기 중복하지 않는다.
    category            VARCHAR(20)  NOT NULL,
    label               VARCHAR(200) NOT NULL,
    detail              VARCHAR(1000) NOT NULL,
    source_material_id  BIGINT       NULL,
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (note_id),
    CONSTRAINT chk_course_notes_category CHECK (category IN ('COURSE_INFO', 'ASSESSMENT')),
    INDEX idx_course_notes_course (course_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
