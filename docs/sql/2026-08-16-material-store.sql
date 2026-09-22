-- 자료 소유 구조 전환: course 종속 → 전역 자료함 + N:M 연결.
--
-- docs/product/10-core-experience.md §3은 "파일의 절대 소유권까지 폴더 구조에 묶을 필요는
-- 없다. 하나의 자료를 여러 맥락에서 참조해야 하는 경우를 막지 않는다"를 확정 규칙으로 적어
-- 두었는데, course_materials.course_id는 NOT NULL이다. 이 마이그레이션은 새 기능 추가가
-- 아니라 그 역전을 바로잡는다.
--
--   materials          파일 원본 + 추출 텍스트 (맥락 없음, 사용자 소유)
--   material_links     자료 ↔ 프로젝트 연결. 이 맥락에서 어떤 성격의 자료인가
--   material_analyses  (자료 × 프로젝트) 쌍의 해석 결과. 이미 두 키를 다 갖고 있다
--   course_topics      사용자가 apply한 확정 상태. 프로젝트 소유. 변경 없음
--   course_notes       위와 동일. 변경 없음
--
-- 기존 docs/sql/*.sql 컨벤션(날짜 파일, 수동 적용)을 따른다. Flyway/Liquibase는 도입하지 않는다.
-- 엔진은 MariaDB 10.4 기준(FK 미사용, ENUM 대신 VARCHAR + CHECK). 소유권은 서비스 코드에서
-- user_id로 검증한다.
--
-- ###############################################################
-- 실행 순서 주의: 이 파일은 한 번에 다 돌리지 않는다.
--
--   PART 1 (섹션 1~4)  스키마 변경 + 백필
--   ---- 검증 정지점 ----  아래 "검증" 블록을 사람이 직접 확인
--   PART 2 (섹션 5)    storage_path를 NOT NULL로 확정
--
-- storage_path 백필이 디스크 실물과 하나라도 어긋나면 PART 2로 넘어가지 않는다.
-- 매칭 실패는 "파일이 이미 없다" 또는 "경로 규칙에 예외가 있다"는 뜻이고,
-- 어느 쪽이든 NOT NULL로 굳히기 전에 사람이 알아야 한다.
-- ###############################################################


-- ###############################################################
-- PART 1
-- ###############################################################

-- ===============================================================
-- 1. material_links: 자료 ↔ 프로젝트 연결.
--
-- material_type이 여기 있는 이유: 자료 자체의 성질이 아니라 "이 프로젝트가 이 자료를
-- 무엇으로 쓰는가"이기 때문이다. 같은 PDF가 A 프로젝트에서는 SYLLABUS, B에서는 OTHER일 수
-- 있다. 그래서 course_materials에서 이 컬럼을 떼어 여기로 옮긴다.
--
-- link_id는 PK로만 쓰고 다른 테이블에 전파하지 않는다. course_material_analyses는 이미
-- course_id + material_id를 갖고 있으므로 (material_id, course_id) UNIQUE만으로 "분석은 그
-- 쌍에 귀속된다"는 의미가 충분히 전달된다.
-- ===============================================================
CREATE TABLE material_links (
    link_id       BIGINT      NOT NULL AUTO_INCREMENT,
    user_id       BIGINT      NOT NULL,
    material_id   BIGINT      NOT NULL,
    course_id     BIGINT      NOT NULL,
    material_type VARCHAR(30) NOT NULL,
    linked_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (link_id),
    CONSTRAINT chk_material_links_type
        CHECK (material_type IN ('SYLLABUS', 'TEXTBOOK_TOC', 'PROFESSOR_SLIDE', 'OTHER')),
    UNIQUE KEY uq_material_links_material_course (material_id, course_id),
    INDEX idx_material_links_course (course_id),
    INDEX idx_material_links_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ===============================================================
-- 2. course_materials 컬럼 추가.
--
-- storage_path: 파일의 디스크 위치를 이 행 하나로 특정할 수 있게 한다.
--   지금까지 FileStorageService는 {uploadDir}/{userId}/{courseId}/{storedFilename}으로
--   경로를 "추론"했다. course_id가 NULL이 되는 순간 그 추론이 깨지고, 나중에 course_id를
--   DROP하면 기존 파일의 위치를 영영 찾을 수 없다. uploadDir 기준 상대 경로를 직접 저장해
--   그 의존을 끊는다. 기존 파일은 이동하지 않는다 — 백필로 현재 경로를 그대로 적어 둔다.
--   신규 업로드는 {userId}/{storedFilename}을 쓴다(프로젝트 없이 올릴 수 있어야 하므로).
--
-- file_hash: SHA-256. UNIQUE 제약을 걸지 않는다. 신규 업로드부터 계산해 저장하고 기존 행은
--   NULL로 둔다. 중복 감지·차단·병합은 지금 만들지 않는다 — 나중에 넣으려면 디스크의 모든
--   파일을 다시 읽어 백필해야 하므로 컬럼과 계산만 미리 확보한다. 해시로 아무것도 판단하지
--   않는다.
--
-- status: 자료 삭제를 soft delete로 처리한다. course_topics.source_material_id가 이 행을
--   참조하므로 행 자체를 지우면 "이 학습 항목이 어느 자료에서 왔는지"가 끊긴다. 원본 파일과
--   extracted_text는 실제로 지우되(삭제가 삭제여야 한다) 메타데이터는 남겨 provenance
--   표시("원본 삭제됨 · 강의계획서.pdf")에 쓴다.
-- ===============================================================
ALTER TABLE course_materials
    ADD COLUMN storage_path VARCHAR(500) NULL AFTER stored_filename,
    ADD COLUMN file_hash    CHAR(64)     NULL AFTER size_bytes,
    ADD COLUMN status       VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' AFTER extraction_error,
    ADD CONSTRAINT chk_course_materials_status CHECK (status IN ('ACTIVE', 'DELETED')),
    ADD INDEX idx_course_materials_hash (user_id, file_hash);


-- ===============================================================
-- 3. course_materials.course_id / material_type NOT NULL 해제.
--
-- ★ 반드시 두 컬럼 다 실행한다. course_id만 풀고 material_type을 놔두면 프로젝트 없는
--   업로드가 INSERT 단계에서 그대로 깨진다.
--
-- 기존 chk_course_materials_type CHECK 제약은 수정하지 않는다. NULL IN (...)은 UNKNOWN으로
-- 평가되고 CHECK는 UNKNOWN을 통과시키므로 NULL 삽입이 막히지 않는다. 문서상 동작과 실제
-- 스키마가 다를 수 있으니 아래 검증 블록에서 실제로 1건 INSERT해 확인한 뒤 롤백한다.
--
-- "컬럼을 유지한다"와 "제약을 유지한다"는 다른 얘기다. 두 컬럼은 남기되 NOT NULL은 반드시
-- 푼다. 컬럼을 남기는 이유는 오직 롤백 여지 확보이지, 기존 소유 관계를 계속 강제하기
-- 위함이 아니다.
--
-- DEPRECATED: material_links로 이관됨. 단계 6 검증 후 별도 마이그레이션에서 DROP.
--   이 시점부터 애플리케이션 코드는 이 두 컬럼을 읽지도 쓰지도 않는다.
--   (신규 INSERT에서도 채우지 않는다 — 이제 NULL이 정상값이다)
-- ===============================================================
ALTER TABLE course_materials
    MODIFY COLUMN course_id     BIGINT      NULL,
    MODIFY COLUMN material_type VARCHAR(30) NULL;


-- ===============================================================
-- 4. 백필.
--
-- 4-1. storage_path — 지금까지 FileStorageService가 쓰던 경로 규칙을 그대로 문자열로 고정.
--      파일을 이동하지 않으므로 이 값은 현재 디스크 상태와 정확히 일치해야 한다.
--      일치 여부는 아래 검증 블록에서 사람이 확인한다.
--
-- 4-2. material_links — 기존 자료마다 링크 정확히 1행.
-- ===============================================================
UPDATE course_materials
   SET storage_path = CONCAT(user_id, '/', course_id, '/', stored_filename)
 WHERE storage_path IS NULL
   AND course_id IS NOT NULL;

INSERT INTO material_links (user_id, material_id, course_id, material_type, linked_at)
SELECT user_id, material_id, course_id, material_type, created_at
  FROM course_materials
 WHERE course_id IS NOT NULL
   AND material_type IS NOT NULL;


-- ###############################################################
-- ---- 검증 정지점 ----
--
-- 아래를 전부 확인하고, 하나라도 어긋나면 PART 2로 넘어가지 않는다.
--
--   (1) 링크 건수 일치
--       SELECT (SELECT COUNT(*) FROM course_materials) AS materials,
--              (SELECT COUNT(*) FROM material_links)   AS links;
--       → 두 값이 같아야 한다 (현재 10 / 10)
--
--   (2) storage_path 누락 없음
--       SELECT COUNT(*) FROM course_materials WHERE storage_path IS NULL;
--       → 0
--
--   (3) storage_path 10건이 디스크 실물과 1:1 매칭  ★ 정지 조건
--       {storage.materials.upload-dir}/{storage_path} 가 실제로 존재하는지 전부 확인.
--       하나라도 없으면 여기서 멈추고 사람에게 보고한다.
--       자동으로 NULL로 두거나 건너뛰지 않는다 — 매칭 실패는 파일이 이미 없거나 경로
--       규칙에 예외가 있다는 뜻이고, 어느 쪽이든 NOT NULL로 굳히기 전에 알아야 한다.
--
--   (4) course_id / material_type 모두 NULL인 행이 INSERT되는지 (CHECK가 UNKNOWN을 통과)
--       START TRANSACTION;
--       INSERT INTO course_materials
--           (user_id, original_filename, stored_filename, storage_path, size_bytes)
--       VALUES (1, 'null-test.pdf', 'null-test.pdf', '1/null-test.pdf', 1);
--       ROLLBACK;
--       → 에러 없이 통과해야 한다
-- ###############################################################


-- ###############################################################
-- PART 2 — 위 검증을 모두 통과한 뒤에만 실행한다.
-- ###############################################################

-- ===============================================================
-- 5. storage_path 확정.
--
-- 백필이 끝나면 모든 행이 값을 갖는다. 앞으로 파일 없는 material 행은 존재할 수 없으므로
-- NOT NULL로 굳혀 "경로를 모르는 자료"라는 상태 자체를 없앤다.
--
-- soft delete된 자료(status='DELETED')도 이 값을 계속 갖는다. 파일 삭제가 실패했을 때
-- log.error에 남긴 경로를 나중에 DB에서 다시 찾아 고아 파일을 정리할 수 있어야 하기
-- 때문이다. 접근 자체는 서비스 계층의 getActiveOwned()가 막는다.
-- ===============================================================
ALTER TABLE course_materials
    MODIFY COLUMN storage_path VARCHAR(500) NOT NULL;
