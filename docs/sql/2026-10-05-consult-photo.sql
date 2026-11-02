-- =====================================================================
-- 2026-10-05 상담 교재 사진(원본 30일 보관) · 사진 단원 추정 연결 · 메시지 첨부
-- =====================================================================
--
-- 앞선 마이그레이션: docs/sql/2026-10-05-study-memory.sql. 이 파일은 그 뒤에 적용한다.
-- 설계: docs/handoff/consult-photo-plan-2026-10-05.md, DB: docs/product/05-database.md §25.
--
-- 추가형이다(NULL 허용 열·새 표·CHECK 값 추가). 기존 행의 의미를 바꾸지 않는다 — origin이 NULL인 자료는 예전 업로드다.
-- 다시 돌려도 된다(IF NOT EXISTS, MariaDB 10.4).
--
-- 배포 순서: 이 마이그레이션 → 서버 → 화면. 새 서버는 새 열을 읽으므로 적용하지 않은 DB에서는 자료 조회가 실패한다.
-- 옛 서버는 새 열·값을 모른 채 동작한다(사진 자료가 있으면 옛 서버는 그것을 일반 자료로 보인다).
--
-- 적용 후 확인:
--   SHOW COLUMNS FROM course_materials WHERE Field IN ('origin','source_conversation_id',
--       'original_expires_at','original_removed_at','original_removed_reason','original_purged_at');
--   SHOW CREATE TABLE ai_message_photos;
--
-- 되돌리기(새 서버를 내린 뒤. 사진 자료가 있으면 먼저 확인한다 — 자동으로 지우지 않는다):
--   SELECT COUNT(*) FROM course_materials WHERE origin IS NOT NULL;
--   SELECT COUNT(*) FROM material_text_units WHERE unit_type = 'IMAGE_PAGE';
--   SELECT COUNT(*) FROM topic_material_links WHERE origin = 'PHOTO_GUESS';
--   DROP TABLE IF EXISTS ai_message_photos;  DROP TABLE IF EXISTS consult_photo_uploads;
--   ALTER TABLE user_contexts DROP COLUMN IF EXISTS topic_photo_id;
--   (값이 0이면) CHECK를 아래 옛 목록으로 되돌리고 새 열을 지운다.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. course_materials — 사진 출처와 원본 보관
-- ---------------------------------------------------------------------
--   origin                  CONSULT_PHOTO(상담에서 올린 교재 사진). NULL = 예전 업로드(자료함·과목·ZIP)
--   source_conversation_id  올린 상담 대화. 그 대화의 메시지만 이 사진을 붙일 수 있다
--   original_expires_at     원본 자동 삭제 시각(올린 시각 + 30일). 이 시각부터 원본 접근을 막는다(정리 작업 실행과 무관)
--   original_removed_at     원본 접근 차단 확정(만료 처리·사용자 삭제·자료 삭제)
--   original_removed_reason EXPIRED / USER
--   original_purged_at      디스크 파일 삭제 완료. NULL이고 차단된 행은 정리 작업이 계속 다시 지운다
-- storage_path는 NOT NULL 그대로 둔다 — 원본을 지운 뒤에도 경로 문자열은 재시도·고아 파일 단서다.
ALTER TABLE course_materials
    ADD COLUMN IF NOT EXISTS origin                  VARCHAR(20) NULL AFTER status,
    ADD COLUMN IF NOT EXISTS source_conversation_id  BIGINT      NULL AFTER origin,
    ADD COLUMN IF NOT EXISTS original_expires_at     DATETIME    NULL AFTER source_conversation_id,
    ADD COLUMN IF NOT EXISTS original_removed_at     DATETIME    NULL AFTER original_expires_at,
    ADD COLUMN IF NOT EXISTS original_removed_reason VARCHAR(10) NULL AFTER original_removed_at,
    ADD COLUMN IF NOT EXISTS original_purged_at      DATETIME    NULL AFTER original_removed_reason;

ALTER TABLE course_materials DROP CONSTRAINT IF EXISTS chk_course_materials_origin;
ALTER TABLE course_materials ADD CONSTRAINT chk_course_materials_origin
    CHECK (origin IS NULL OR origin IN ('CONSULT_PHOTO'));
ALTER TABLE course_materials DROP CONSTRAINT IF EXISTS chk_course_materials_original_reason;
ALTER TABLE course_materials ADD CONSTRAINT chk_course_materials_original_reason
    CHECK (original_removed_reason IS NULL OR original_removed_reason IN ('EXPIRED', 'USER'));

-- 정리 작업: 만료됐거나 차단됐는데 파일을 아직 못 지운 사진.
CREATE INDEX IF NOT EXISTS idx_course_materials_original ON course_materials (origin, original_purged_at, original_expires_at);
CREATE INDEX IF NOT EXISTS idx_course_materials_conversation ON course_materials (source_conversation_id);

-- ---------------------------------------------------------------------
-- 2. material_text_units.unit_type — 사진 한 장(IMAGE_PAGE)
--    CHECK는 값 목록이라 지우고 다시 만든다. 기존 행은 옛 값 중 하나라 그대로 통과한다.
--    material_sections.unit_type에는 CHECK가 없다.
-- ---------------------------------------------------------------------
ALTER TABLE material_text_units DROP CONSTRAINT IF EXISTS chk_material_text_units_type;
ALTER TABLE material_text_units ADD CONSTRAINT chk_material_text_units_type
    CHECK (unit_type IN ('PDF_PAGE', 'PPTX_SLIDE', 'NOTEBOOK_CELL', 'TEXT_BLOCK', 'IMAGE_PAGE'));

-- ---------------------------------------------------------------------
-- 3. topic_material_links.origin — 서버가 쪽·단원 제목으로 추정한 사진 연결(PHOTO_GUESS)
--    사용자가 [맞아요]·[바꾸기]로 정하면 USER가 된다.
-- ---------------------------------------------------------------------
ALTER TABLE topic_material_links DROP CONSTRAINT IF EXISTS chk_topic_material_links_origin;
ALTER TABLE topic_material_links ADD CONSTRAINT chk_topic_material_links_origin
    CHECK (origin IN ('BACKFILL_SOURCE', 'PROPOSAL_APPLIED', 'USER', 'PHOTO_GUESS'));

-- ---------------------------------------------------------------------
-- 4. ai_message_photos — 어느 사용자 메시지에 어떤 사진을 붙였나
--    기록 복원(말풍선 아래 사진 칩)과 이어지는 턴의 근거 고정, 계획의 "그 어려움을 말할 때 보던 사진"에 쓴다.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_message_photos (
    message_id      BIGINT   NOT NULL,
    material_id     BIGINT   NOT NULL,
    user_id         BIGINT   NOT NULL,
    conversation_id BIGINT   NOT NULL,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (message_id, material_id),
    INDEX idx_ai_message_photos_conversation (conversation_id, message_id),
    INDEX idx_ai_message_photos_material (material_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------
-- 5. consult_photo_uploads — 사진 한 장 업로드의 선점·결과(재전송 복구, 저장 전 파일 정리)
--    사용량을 예약하기 전에 (user_id, upload_key)로 먼저 INSERT한다 — 같은 키의 동시 요청은 하나만 비전을 부른다.
--    status   PROCESSING(처리 중) / READ(자료 저장됨, material_id) / UNREADABLE(글자 없음·교재 아님) / FAILED(읽기·저장 실패)
--    storage_path  파일을 디스크에 쓴 직후 기록. 자료가 되지 못한 업로드(UNREADABLE·FAILED·오래된 PROCESSING)의 파일은
--                  정리 작업이 지우고 file_removed_at을 남긴다. READ면 파일은 자료 행(course_materials)이 맡는다.
--    result_json   끝난 결과의 화면 응답 요약(같은 키 재전송에 그대로 돌려준다). 본문 글은 담지 않는다 — 글은 자료에 있다.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS consult_photo_uploads (
    upload_id       BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    conversation_id BIGINT       NOT NULL,
    upload_key      VARCHAR(64)  NOT NULL,
    status          VARCHAR(12)  NOT NULL DEFAULT 'PROCESSING',
    storage_path    VARCHAR(500) NULL,
    material_id     BIGINT       NULL,
    result_json     TEXT         NULL,
    file_removed_at DATETIME     NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (upload_id),
    CONSTRAINT chk_consult_photo_uploads_status CHECK (status IN ('PROCESSING', 'READ', 'UNREADABLE', 'FAILED')),
    CONSTRAINT chk_consult_photo_uploads_result CHECK (result_json IS NULL OR JSON_VALID(result_json)),
    UNIQUE KEY uq_consult_photo_uploads_key (user_id, upload_key),
    INDEX idx_consult_photo_uploads_cleanup (status, file_removed_at, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------
-- 6. user_contexts.topic_photo_id — 기억의 단원을 사진 문맥("이 문제 모르겠어")으로 채웠으면 그 사진(자료 id)
--    사진의 단원 연결을 확인·변경·해제하면 이 값으로 그 사진에서 유도한 기억의 단원도 따라 바꾼다.
--    사용자가 기억의 단원을 직접 고치면 NULL로 끊는다(사용자 수정이 우선).
-- ---------------------------------------------------------------------
ALTER TABLE user_contexts ADD COLUMN IF NOT EXISTS topic_photo_id BIGINT NULL AFTER topic_id;
CREATE INDEX IF NOT EXISTS idx_user_contexts_topic_photo ON user_contexts (topic_photo_id);
