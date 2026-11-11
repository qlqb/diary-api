-- 자료 형식 확대: IPYNB(셀 단위) · HWP/HWPX(구간 단위) · ZIP 가져오기.
--
-- 고치는 문제:
--   1) material_text_units.unit_type CHECK가 PDF_PAGE/PPTX_SLIDE/TEXT_BLOCK만 허용해 노트북 셀을
--      저장할 수 없었다. 셀 번호를 잃으면 구간이 "셀 8~12"라고 말할 수 없다.
--   2) 추출이 일부만 됐을 때(상한 초과 등) 그 사실을 남길 자리가 없었다. extraction_error는 실패용이라
--      성공 상태에 쓰면 실패처럼 보인다.
--   3) 압축 파일에서 꺼낸 자료의 출처(어느 zip의 어느 경로)가 남지 않았다.
--   4) ZIP 가져오기 자체의 진행 상태(선택 대기·가져오는 중·부분 실패·재시도)를 저장할 표가 없었다.
--
-- 전부 "추가"다. 기존 행·ID·컬럼 의미를 바꾸지 않는다. 재실행 가능하게 쓴다.
-- MariaDB 10.4 기준. 2026-08-16 이후 신규 테이블 규칙대로 FK 없음, ENUM 대신 VARCHAR + CHECK.
-- 소유권은 서비스가 user_id로 검증한다.
--
-- 적용 순서: 0 → 4. 롤백은 맨 아래 주석.

-- ===============================================================
-- 0. 재실행용 보조 프로시저 (컬럼이 없을 때만 추가)
-- ===============================================================
DROP PROCEDURE IF EXISTS add_column_if_missing;
DELIMITER //
CREATE PROCEDURE add_column_if_missing(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_ddl TEXT)
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND COLUMN_NAME = p_column) THEN
        SET @ddl = CONCAT('ALTER TABLE ', p_table, ' ADD COLUMN ', p_ddl);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END //
DELIMITER ;

-- ===============================================================
-- 1. material_text_units.unit_type 에 NOTEBOOK_CELL 추가
--
--    CHECK는 값 목록이라 바꾸려면 지우고 다시 만든다. 기존 행은 전부 옛 세 값 중 하나라
--    새 CHECK도 그대로 통과한다(추가만 하므로).
--    material_sections.unit_type에는 CHECK가 없다(2026-09-13 파일 참고) — 손댈 것이 없다.
-- ===============================================================
ALTER TABLE material_text_units DROP CONSTRAINT IF EXISTS chk_material_text_units_type;
ALTER TABLE material_text_units ADD CONSTRAINT chk_material_text_units_type
    CHECK (unit_type IN ('PDF_PAGE', 'PPTX_SLIDE', 'NOTEBOOK_CELL', 'TEXT_BLOCK'));

-- ===============================================================
-- 2. course_materials: 추출 경고와 압축 출처
--
--    extraction_warning은 성공(SUCCESS)에도 채워질 수 있다 — "읽었지만 뒷부분은 상한에 걸려 못 읽었다".
--    source_archive_name/source_entry_path는 표시용 출처다. 파일 저장 경로는 지금도 UUID이고,
--    여기 값으로 경로를 만들지 않는다.
-- ===============================================================
CALL add_column_if_missing('course_materials', 'extraction_warning',
    'extraction_warning VARCHAR(500) NULL AFTER extraction_error');
CALL add_column_if_missing('course_materials', 'source_archive_name',
    'source_archive_name VARCHAR(255) NULL AFTER extraction_warning');
CALL add_column_if_missing('course_materials', 'source_entry_path',
    'source_entry_path VARCHAR(1000) NULL AFTER source_archive_name');

-- ===============================================================
-- 3. material_zip_imports — 압축 가져오기 한 건
--
--    status: PREPARING(목록 준비) → READY(선택 대기) → IMPORTING(자료 생성 중)
--            → COMPLETED / PARTIAL / FAILED / CANCELLED / EXPIRED
--    여기서 COMPLETED는 "자료가 만들어졌다"이지 "AI 분석이 끝났다"가 아니다. 분석 상태는
--    material_analysis_jobs가 따로 갖는다.
--
--    storage_path는 임시 보관한 원본 zip이다. 끝났거나 기한(expires_at)이 지나면 파일을 지우고
--    이 값을 NULL로 만든다 — 그 뒤에도 이미 만들어진 자료는 각자 파일을 갖고 있어 멀쩡하다.
-- ===============================================================
CREATE TABLE IF NOT EXISTS material_zip_imports (
    import_id         BIGINT       NOT NULL AUTO_INCREMENT,
    user_id           BIGINT       NOT NULL,
    course_id         BIGINT       NULL,
    material_type     VARCHAR(20)  NULL,
    original_filename VARCHAR(255) NOT NULL,
    storage_path      VARCHAR(500) NULL,
    size_bytes        BIGINT       NOT NULL,
    file_hash         CHAR(64)     NULL,
    status            VARCHAR(20)  NOT NULL,
    entry_count       INT          NOT NULL DEFAULT 0,
    selectable_count  INT          NOT NULL DEFAULT 0,
    error_code        VARCHAR(20)  NULL,
    error_message     VARCHAR(500) NULL,
    expires_at        DATETIME     NOT NULL,
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    finished_at       DATETIME     NULL,
    PRIMARY KEY (import_id),
    CONSTRAINT chk_material_zip_imports_status CHECK (status IN
        ('PREPARING', 'READY', 'IMPORTING', 'COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED', 'EXPIRED')),
    INDEX idx_material_zip_imports_user (user_id, import_id),
    INDEX idx_material_zip_imports_cleanup (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ===============================================================
-- 4. material_zip_import_entries — 압축 안 파일 하나
--
--    중복 생성 방지의 핵심이 두 제약이다.
--      uq_zip_import_entry_position : 같은 압축의 같은 순번은 한 행뿐이다(목록을 두 번 만들 수 없다).
--      uq_zip_import_entry_material : 한 자료는 한 항목에서만 나온다. 자료 INSERT와 이 행의
--                                     material_id 기록이 같은 트랜잭션이라, 커밋 뒤 응답이 유실되거나
--                                     서버가 내려가도 "자료는 있는데 항목은 미완료"가 남지 않는다.
--    (MariaDB는 UNIQUE 컬럼에 NULL을 여러 개 허용하므로 아직 자료가 없는 항목은 자유롭다.)
--
--    status: PENDING(선택 대기) · QUEUED(확정) · IMPORTING(작업자 선점) · DONE · FAILED · UNSUPPORTED
--    entry_path는 압축 안의 원래 경로다. 표시와 출처 기록에만 쓰고 파일시스템 경로로 쓰지 않는다.
-- ===============================================================
CREATE TABLE IF NOT EXISTS material_zip_import_entries (
    entry_id      BIGINT        NOT NULL AUTO_INCREMENT,
    import_id     BIGINT        NOT NULL,
    user_id       BIGINT        NOT NULL,
    entry_index   INT           NOT NULL,
    entry_path    VARCHAR(1000) NOT NULL,
    display_name  VARCHAR(255)  NOT NULL,
    extension     VARCHAR(20)   NULL,
    size_bytes    BIGINT        NOT NULL DEFAULT 0,
    supported     TINYINT(1)    NOT NULL DEFAULT 0,
    skip_reason   VARCHAR(200)  NULL,
    status        VARCHAR(20)   NOT NULL,
    material_id   BIGINT        NULL,
    error_code    VARCHAR(30)   NULL,
    error_message VARCHAR(500)  NULL,
    attempt       INT           NOT NULL DEFAULT 0,
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (entry_id),
    CONSTRAINT chk_material_zip_import_entries_status CHECK (status IN
        ('PENDING', 'QUEUED', 'IMPORTING', 'DONE', 'FAILED', 'UNSUPPORTED')),
    UNIQUE KEY uq_zip_import_entry_position (import_id, entry_index),
    UNIQUE KEY uq_zip_import_entry_material (material_id),
    INDEX idx_zip_import_entry_queue (status, entry_id),
    INDEX idx_zip_import_entry_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DROP PROCEDURE IF EXISTS add_column_if_missing;

-- ===============================================================
-- 롤백
-- ===============================================================
--   DROP TABLE material_zip_import_entries;
--   DROP TABLE material_zip_imports;
--   ALTER TABLE course_materials DROP COLUMN source_entry_path;
--   ALTER TABLE course_materials DROP COLUMN source_archive_name;
--   ALTER TABLE course_materials DROP COLUMN extraction_warning;
--   -- 노트북 단위가 이미 저장됐다면 CHECK를 되돌리기 전에 그 행부터 지워야 한다:
--   --   DELETE FROM material_text_units WHERE unit_type = 'NOTEBOOK_CELL';
--   ALTER TABLE material_text_units DROP CONSTRAINT IF EXISTS chk_material_text_units_type;
--   ALTER TABLE material_text_units ADD CONSTRAINT chk_material_text_units_type
--       CHECK (unit_type IN ('PDF_PAGE', 'PPTX_SLIDE', 'TEXT_BLOCK'));
