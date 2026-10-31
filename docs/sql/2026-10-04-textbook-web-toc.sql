-- =====================================================================
-- 2026-10-04 교재 목차 자동 검색(웹) → 학습 구조 변경안 → 상담·계획
-- =====================================================================
--
-- 앞선 마이그레이션: docs/sql/2026-09-29-learning-flow.sql. 이 파일은 그 뒤에 적용한다.
-- 설계: docs/product/17-textbook-web-toc.md, DB: docs/product/05-database.md §23.
--
-- 추가형이다(새 표, NULL 허용·기본값 열). 기존 행의 의미를 바꾸지 않는다 — courses.textbook_version은 0에서
-- 시작하고, 교재 출처 CHECK는 값 하나(WEB)를 더할 뿐이다. 다시 돌려도 된다(IF NOT EXISTS, MariaDB 10.4).
--
-- 배포 순서: 이 마이그레이션 → 서버 → 화면. 새 서버는 새 열을 읽으므로 적용하지 않은 DB에서는 교재·정리·학습 지도
-- 조회가 "Unknown column"으로 실패한다. 옛 서버는 새 열·표를 모르므로 적용 뒤에도 그대로 동작한다.
--
-- 되돌리기(새 서버를 내린 뒤):
--   DROP TABLE IF EXISTS textbook_lookup_usage, textbook_lookups, textbook_web_revisions, textbook_web_pages;
--   ALTER TABLE courses DROP COLUMN IF EXISTS textbook_version, DROP COLUMN IF EXISTS textbook_web_revision_id,
--       DROP COLUMN IF EXISTS textbook_toc_material_id, DROP COLUMN IF EXISTS textbook_toc_file_hash,
--       DROP COLUMN IF EXISTS textbook_toc_book_key, DROP COLUMN IF EXISTS textbook_web_lookup_enabled;
--   (WEB 출처 행이 있으면 먼저 UPDATE courses SET textbook_info_source='USER' WHERE textbook_info_source='WEB')
--   ALTER TABLE courses DROP CONSTRAINT IF EXISTS chk_courses_textbook_info_source;
--   ALTER TABLE courses ADD CONSTRAINT chk_courses_textbook_info_source
--       CHECK (textbook_info_source IS NULL OR textbook_info_source IN ('USER', 'MATERIAL'));
--   ALTER TABLE course_topics DROP COLUMN IF EXISTS source_web_revision_id, DROP COLUMN IF EXISTS source_textbook_key;
--   ALTER TABLE project_tidy_proposals DROP COLUMN IF EXISTS toc_basis_json;
--   ALTER TABLE material_textbook_extracts DROP COLUMN IF EXISTS clue_json;
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 웹 근거: 페이지(캐시 자리)와 리비전(불변 원문)
-- ---------------------------------------------------------------------
-- 페이지는 "이 URL을 마지막으로 언제 받았나"를 가리키는 자리일 뿐이다. 실제 근거는 리비전이다 — 같은 URL의 내용이
-- 나중에 바뀌어도 이미 적용한 토픽·정리안은 그때의 리비전을 가리켜 과거 근거를 그대로 다시 볼 수 있다.
--   cache_scope_key  SHARED      지원 서점의 정규화된 상품 페이지(공개 도서 정보). 사용자와 무관하게 재사용한다
--                    USER:{id}   사용자가 직접 준 링크나 지원 목록 밖의 페이지. 그 사용자만 읽는다
--   url              저장용 정규화 URL(쿼리는 상품 식별에 필요한 것만 남긴다)
CREATE TABLE IF NOT EXISTS textbook_web_pages (
    page_id            BIGINT        NOT NULL AUTO_INCREMENT,
    cache_scope_key    VARCHAR(40)   NOT NULL,
    url_hash           CHAR(64)      NOT NULL,
    url                VARCHAR(1000) NOT NULL,
    site               VARCHAR(40)   NOT NULL,
    latest_revision_id BIGINT        NULL,
    last_fetched_at    DATETIME      NULL,
    last_fetch_status  VARCHAR(20)   NULL,
    created_at         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (page_id),
    UNIQUE KEY uk_textbook_web_pages (cache_scope_key, url_hash),
    CONSTRAINT chk_textbook_web_pages_scope CHECK (cache_scope_key = 'SHARED' OR cache_scope_key LIKE 'USER:%')
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;

-- 한 번 쓰면 바꾸지 않는다. (페이지, 원문 해시, 파서 판)이 같으면 새 행을 만들지 않는다.
--   toc_raw       페이지에서 잘라 낸 목차 원문 그대로(구조화 전)
--   toc_json      {entries:[{level, number, title, page, line}]} — 제목은 원문 줄에서 서버가 잘라 낸 것뿐이다
--   toc_coverage  PAGE_FULL  이 페이지에 실린 목차를 잘림 없이 읽었다(책 전체 목차라는 증명은 아니다)
--                 PARTIAL    일부만 읽었거나 페이지가 잘린 목차를 실었다
--                 UNKNOWN    완전한지 판단할 수 없다
--                 NONE       페이지에 목차가 없다
CREATE TABLE IF NOT EXISTS textbook_web_revisions (
    revision_id     BIGINT        NOT NULL AUTO_INCREMENT,
    page_id         BIGINT        NOT NULL,
    content_hash    CHAR(64)      NOT NULL,
    parser_version  INT           NOT NULL,
    fetched_at      DATETIME      NOT NULL,
    http_status     INT           NOT NULL,
    isbn13          VARCHAR(13)   NULL,
    title           VARCHAR(300)  NULL,
    authors         VARCHAR(300)  NULL,
    author_notes    VARCHAR(300)  NULL,
    publisher       VARCHAR(200)  NULL,
    published_date  VARCHAR(20)   NULL,
    edition         VARCHAR(100)  NULL,
    toc_raw         MEDIUMTEXT    NULL,
    toc_json        LONGTEXT      NULL,
    toc_entry_count INT           NOT NULL DEFAULT 0,
    toc_coverage    VARCHAR(10)   NOT NULL DEFAULT 'NONE',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (revision_id),
    UNIQUE KEY uk_textbook_web_revisions (page_id, content_hash, parser_version),
    KEY idx_textbook_web_revisions_isbn (isbn13),
    CONSTRAINT chk_textbook_web_revisions_coverage CHECK (toc_coverage IN ('PAGE_FULL', 'PARTIAL', 'UNKNOWN', 'NONE')),
    CONSTRAINT chk_textbook_web_revisions_toc CHECK (toc_json IS NULL OR JSON_VALID(toc_json))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;

-- ---------------------------------------------------------------------
-- 2. 교재 조회 작업 (과목마다 열린 작업 하나)
-- ---------------------------------------------------------------------
-- 화면이 아니라 이 표가 상태의 원본이다 — 화면을 닫거나 새로고침해도 진행·결과를 다시 본다.
--   basis_key   이 조회가 무엇을 근거로 시작됐나(과목·교재 판·단서 종류·단서 입력). 결과를 저장하는 순간 다시 계산해
--               다르면 SUPERSEDED — 늦게 끝난 옛 교재 검색이 사용자가 고친 교재를 덮지 않는다
--   query_key   종류별 입력만(과목·판 제외). 같은 질의의 완료 결과를 검색 없이 새 basis 행으로 복사할 때 쓴다
--   query_json  외부로 보낸 단서 그대로(제목·저자·출판사·판·ISBN). 다른 원문은 보내지 않는다
--   result_json 후보 리비전·일치 판정·이유·판본 묶음
--   auto_tidy_state  목차를 확보한 뒤 정리안을 자동으로 만들었는가
CREATE TABLE IF NOT EXISTS textbook_lookups (
    lookup_id              BIGINT        NOT NULL AUTO_INCREMENT,
    user_id                BIGINT        NOT NULL,
    course_id              BIGINT        NOT NULL,
    basis_key              CHAR(64)      NOT NULL,
    query_key              CHAR(64)      NOT NULL,
    query_json             LONGTEXT      NOT NULL,
    clue_origin            VARCHAR(20)   NOT NULL,
    clue_material_id       BIGINT        NULL,
    clue_file_hash         VARCHAR(64)   NULL,
    clue_extractor_version INT           NULL,
    textbook_version       INT           NOT NULL,
    force_refresh          TINYINT(1)    NOT NULL DEFAULT 0,
    status                 VARCHAR(20)   NOT NULL,
    open_guard             TINYINT       AS (CASE WHEN status IN ('QUEUED', 'RUNNING') THEN 1 ELSE NULL END) PERSISTENT,
    attempt                INT           NOT NULL DEFAULT 0,
    max_attempts           INT           NOT NULL DEFAULT 3,
    next_run_at            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_owner            VARCHAR(64)   NULL,
    lease_until            DATETIME      NULL,
    lease_token            INT           NOT NULL DEFAULT 0,
    result_json            LONGTEXT      NULL,
    chosen_revision_id     BIGINT        NULL,
    reused_from_lookup_id  BIGINT        NULL,
    auto_tidy_state        VARCHAR(30)   NOT NULL DEFAULT 'NONE',
    auto_tidy_next_at      DATETIME      NULL,
    tidy_job_id            BIGINT        NULL,
    error_code             VARCHAR(40)   NULL,
    error_message          VARCHAR(500)  NULL,
    created_at             DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    finished_at            DATETIME      NULL,
    PRIMARY KEY (lookup_id),
    UNIQUE KEY uk_textbook_lookups_open (course_id, open_guard),
    KEY idx_textbook_lookups_course (user_id, course_id, lookup_id),
    KEY idx_textbook_lookups_query (user_id, query_key, status),
    KEY idx_textbook_lookups_claim (status, next_run_at),
    KEY idx_textbook_lookups_auto_tidy (auto_tidy_state, auto_tidy_next_at),
    CONSTRAINT chk_textbook_lookups_status CHECK (status IN ('QUEUED', 'RUNNING', 'FOUND', 'NEEDS_CHOICE', 'BOOK_NO_TOC',
        'NOT_FOUND', 'ACCESS_FAILED', 'FAILED', 'CLUE_CONFLICT', 'SUPERSEDED', 'CANCELLED', 'DISABLED')),
    CONSTRAINT chk_textbook_lookups_origin CHECK (clue_origin IN ('CURRENT_TEXTBOOK', 'SYLLABUS', 'ISBN', 'USER_LINK')),
    CONSTRAINT chk_textbook_lookups_auto_tidy CHECK (auto_tidy_state IN ('NONE', 'PENDING', 'ENQUEUED',
        'WAITING_OPEN_PROPOSAL', 'DONE')),
    CONSTRAINT chk_textbook_lookups_query CHECK (JSON_VALID(query_json)),
    CONSTRAINT chk_textbook_lookups_result CHECK (result_json IS NULL OR JSON_VALID(result_json))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;

-- (같은 날 개정) 이미 만든 표에 자동 정리 다음 평가 시각을 더한다 — 다시 돌려도 된다.
ALTER TABLE textbook_lookups ADD COLUMN IF NOT EXISTS auto_tidy_next_at DATETIME NULL AFTER auto_tidy_state;
ALTER TABLE textbook_lookups DROP INDEX IF EXISTS idx_textbook_lookups_auto_tidy;
ALTER TABLE textbook_lookups ADD INDEX idx_textbook_lookups_auto_tidy (auto_tidy_state, auto_tidy_next_at);

-- 사용자별 하루 외부 조회 비용 예약(모델·검색 호출 수). 동시에 여러 작업이 돌아도 상한을 넘지 않게
-- UPDATE ... WHERE calls + n <= limit 한 줄로 예약한다.
CREATE TABLE IF NOT EXISTS textbook_lookup_usage (
    user_id    BIGINT   NOT NULL,
    usage_date DATE     NOT NULL,
    calls      INT      NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, usage_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;

-- ---------------------------------------------------------------------
-- 3. 과목의 현재 교재
-- ---------------------------------------------------------------------
--   textbook_info_source       USER / MATERIAL / WEB(웹에서 찾은 판을 사용자가 정했거나 검토한 목차 변경과 함께 적용)
--   textbook_version           교재 칸이 바뀔 때마다 +1. 화면은 본 판을 함께 보내고 다르면 409(조용히 덮지 않는다)
--   textbook_web_revision_id   WEB일 때 그 판의 리비전
--   textbook_toc_*             사용자가 "이 교재의 목차"로 이은 업로드 자료와 그때의 파일 해시·교재 식별 키.
--                              교재 식별이 바뀌면 서버가 연결을 푼다(다른 책의 목차를 새 교재에 쓰지 않게)
--   textbook_web_lookup_enabled 0이면 교재 단서를 외부로 보내지 않는다
ALTER TABLE courses ADD COLUMN IF NOT EXISTS textbook_version INT NOT NULL DEFAULT 0 AFTER textbook_info_updated_at;
ALTER TABLE courses ADD COLUMN IF NOT EXISTS textbook_web_revision_id BIGINT NULL AFTER textbook_version;
ALTER TABLE courses ADD COLUMN IF NOT EXISTS textbook_toc_material_id BIGINT NULL AFTER textbook_web_revision_id;
ALTER TABLE courses ADD COLUMN IF NOT EXISTS textbook_toc_file_hash VARCHAR(64) NULL AFTER textbook_toc_material_id;
ALTER TABLE courses ADD COLUMN IF NOT EXISTS textbook_toc_book_key VARCHAR(400) NULL AFTER textbook_toc_file_hash;
ALTER TABLE courses ADD COLUMN IF NOT EXISTS textbook_web_lookup_enabled TINYINT(1) NOT NULL DEFAULT 1
    AFTER textbook_toc_book_key;
ALTER TABLE courses DROP CONSTRAINT IF EXISTS chk_courses_textbook_info_source;
ALTER TABLE courses ADD CONSTRAINT IF NOT EXISTS chk_courses_textbook_info_source
    CHECK (textbook_info_source IS NULL OR textbook_info_source IN ('USER', 'MATERIAL', 'WEB'));

-- ---------------------------------------------------------------------
-- 4. 학습 항목의 웹 목차 출처
-- ---------------------------------------------------------------------
-- 웹 목차에서 온 항목은 source_material_id를 쓰지 않는다(웹 근거를 업로드 파일 출처처럼 저장하지 않는다).
--   source_web_revision_id  그 항목을 만든 웹 목차 리비전
--   source_textbook_key     목차에서 온 항목이 어느 책의 것인가(ISBN 또는 정규화 제목). 교재가 바뀐 뒤 이전 교재의
--                           항목을 새 교재의 범위로 세지 않기 위해 남긴다
ALTER TABLE course_topics ADD COLUMN IF NOT EXISTS source_web_revision_id BIGINT NULL AFTER source_locator;
ALTER TABLE course_topics ADD COLUMN IF NOT EXISTS source_textbook_key VARCHAR(400) NULL AFTER source_web_revision_id;

-- ---------------------------------------------------------------------
-- 5. 정리안의 목차 근거, 자료 교재 단서의 모델 보조 결과
-- ---------------------------------------------------------------------
-- toc_basis_json  {kind: MATERIAL|WEB, materialId, fileHash, revisionId, textbookVersion, bookKey}
--                 적용할 때 지금 목차 근거와 다르면 목차에서 온 변경만 막는다
-- clue_json       규칙이 못 읽은 강의계획서 표에서 모델이 줄 번호로 짚고 서버가 원문에서 잘라 낸 교재 단서
ALTER TABLE project_tidy_proposals ADD COLUMN IF NOT EXISTS toc_basis_json LONGTEXT NULL;
ALTER TABLE material_textbook_extracts ADD COLUMN IF NOT EXISTS clue_json LONGTEXT NULL;
