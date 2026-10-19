-- =====================================================================
-- 2026-10-06 이벤트 기반 학습 진도 1단계 A — 학습 이벤트 로그 · 이벤트 출처의 판 · 교재 식별
-- =====================================================================
--
-- 앞선 마이그레이션: docs/sql/2026-10-06-toc-subitems.sql. 이 파일은 그 뒤에 적용한다.
-- 설계: docs/product/20-learning-events.md, 계획: docs/handoff/learning-events-stage1-plan-2026-10-06.md(PR A).
--
-- 추가형이다(새 표만). 기존 표·행을 바꾸지 않는다.
--   - learning_events는 쌓기만 한다. 한 원본(origin)의 "살아 있는" 출력은 learning_event_origins.current_revision 판의 행이다.
--     판이 바뀌어도 옛 판 행은 지우지 않는다(과거 재생).
--   - textbook_refs·aliases는 서버가 시작할 때 한 번 채운다(TextbookRefSeeder, 멱등 — learning_event_meta 'textbook_refs_seeded').
--     MariaDB 10.4에서 BookKey(ISBN 정규화·제목 키)를 SQL로 만들기 어려워 이 파일은 표만 만든다.
-- 다시 돌려도 된다(IF NOT EXISTS, MariaDB 10.4).
--
-- 배포 순서: 이 마이그레이션 → 서버. 로컬 단일 서버이며 옛 서버와 함께 돌리지 않는다.
--
-- 적용 후 확인:
--   SHOW TABLES LIKE 'learning_event%';
--   SHOW TABLES LIKE 'textbook_ref%';
--   SELECT * FROM learning_event_meta;          -- 서버를 한 번 띄운 뒤 textbook_refs_seeded 행이 있어야 한다
--
-- 되돌리기(새 서버를 내린 뒤):
--   DROP TABLE IF EXISTS learning_events, learning_event_origins, learning_resolution_links, learning_event_meta,
--       textbook_ref_aliases, textbook_refs;
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. learning_event_origins — 원본(실행 기록·기억 행·범위 제외 …) 하나의 지금 판
-- ---------------------------------------------------------------------
--   course_id         처음 쓸 때 정하고 바꾸지 않는다
--   current_revision  살아 있는 판. 출력 0개도 판이다(지움·해제). 0 = 아직 쓴 적 없음(행만 있음 — 잠금 자리).
--                     없는 행을 잠금 읽기하면 간격 잠금이 남아 교착할 수 있어, 기록기는 행을 먼저 만들고(판 0) 잠근다.
--                     백필·동기화는 판 0인 origin을 "없음"으로 본다
CREATE TABLE IF NOT EXISTS learning_event_origins (
    user_id          BIGINT       NOT NULL,
    origin_kind      VARCHAR(24)  NOT NULL,
    origin_id        BIGINT       NOT NULL,
    course_id        BIGINT       NOT NULL,
    current_revision INT          NOT NULL DEFAULT 0,
    created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id, origin_kind, origin_id),
    INDEX idx_learning_event_origins_course (user_id, course_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------
-- 2. learning_events — 원천을 가리키는 학습 이벤트(쌓기만)
-- ---------------------------------------------------------------------
--   object_ref  s:{sectionId} · m:{materialId} · t:{bookRefId}:{keyHash}:{keyLine} · c:{sessionId} · '-'(COURSE)
--   topic_id    호환 앵커(표시·디버그용). 진도 계산은 원천만 본다
--   payload     {"v":1,...} verb별 구조. 원문(메모·막힌 단계 글·기억 본문)은 넣지 않는다
CREATE TABLE IF NOT EXISTS learning_events (
    event_id        BIGINT        NOT NULL AUTO_INCREMENT,
    user_id         BIGINT        NOT NULL,
    course_id       BIGINT        NOT NULL,
    origin_kind     VARCHAR(24)   NOT NULL,
    origin_id       BIGINT        NOT NULL,
    origin_revision INT           NOT NULL,
    output_no       SMALLINT      NOT NULL,
    actor           VARCHAR(8)    NOT NULL,
    verb            VARCHAR(24)   NOT NULL,
    object_kind     VARCHAR(12)   NOT NULL,
    object_ref      VARCHAR(200)  NOT NULL,
    object_from     INT           NULL,
    object_to       INT           NULL,
    topic_id        BIGINT        NULL,
    payload         LONGTEXT      NOT NULL,
    evidence        VARCHAR(10)   NOT NULL,
    confidence      DECIMAL(3,2)  NULL,
    claim_at        DATETIME(3)   NULL,
    claim_seq       BIGINT        NULL,
    occurred_at     DATETIME(3)   NULL,
    recorded_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (event_id),
    UNIQUE KEY uq_learning_events_output (user_id, origin_kind, origin_id, origin_revision, output_no),
    INDEX idx_learning_events_object (user_id, course_id, object_kind, object_ref),
    INDEX idx_learning_events_recorded (user_id, course_id, recorded_at),
    INDEX idx_learning_events_page (user_id, course_id, event_id),
    INDEX idx_learning_events_verb (user_id, course_id, verb),
    CONSTRAINT chk_learning_events_payload CHECK (JSON_VALID(payload)),
    CONSTRAINT chk_learning_events_actor CHECK (actor IN ('ME', 'CLASS', 'SYSTEM')),
    CONSTRAINT chk_learning_events_verb CHECK (verb IN (
        'RELEASED', 'COVERED_IN_CLASS', 'CANCELLED', 'SESSION_MOVED', 'SCOPE_ANNOUNCED', 'SCOPE_EXCLUDED', 'PLAN_CHANGED',
        'STUDIED', 'ATTEMPTED', 'STUCK', 'RESOLVED', 'ABSENT', 'SELF_ASSESSED', 'SUBMITTED',
        'STATED', 'RETRACTED', 'SUPERSEDES')),
    CONSTRAINT chk_learning_events_object CHECK (object_kind IN (
        'SECTION', 'ITEM', 'TOC_ENTRY', 'PAGE', 'WEEK', 'SESSION', 'MATERIAL', 'COURSE')),
    CONSTRAINT chk_learning_events_evidence CHECK (evidence IN (
        'STATED', 'INPUT', 'LOGGED', 'RULE', 'APPROX', 'INFERRED')),
    CONSTRAINT chk_learning_events_confidence CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 이 파일을 먼저 적용한 DB(초안)에도 인덱스를 맞춘다.
CREATE INDEX IF NOT EXISTS idx_learning_events_page ON learning_events (user_id, course_id, event_id);
CREATE INDEX IF NOT EXISTS idx_learning_events_verb ON learning_events (user_id, course_id, verb);

-- ---------------------------------------------------------------------
-- 3. textbook_refs / textbook_ref_aliases — 바뀌지 않는 교재 식별자와 그 책의 BookKey들
-- ---------------------------------------------------------------------
--   같은 책의 ISBN 키·제목 키가 같은 ref의 별칭이다. 이벤트가 가리킨 ref는 합치지 않는다(별칭만 더한다).
--   book_key_hash  SHA-256(book_key) — 긴 키를 UNIQUE로 묶으려고. 찾은 뒤 전체 키를 비교한다
CREATE TABLE IF NOT EXISTS textbook_refs (
    book_ref_id BIGINT      NOT NULL AUTO_INCREMENT,
    user_id     BIGINT      NOT NULL,
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (book_ref_id),
    INDEX idx_textbook_refs_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS textbook_ref_aliases (
    user_id       BIGINT        NOT NULL,
    book_key_hash CHAR(64)      NOT NULL,
    book_key      VARCHAR(400)  NOT NULL,
    book_ref_id   BIGINT        NOT NULL,
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, book_key_hash),
    INDEX idx_textbook_ref_aliases_ref (book_ref_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------
-- 4. learning_resolution_links — 어느 막힘(기억 행)을 어느 해결 기억 행이 닫았나 (PR B가 쓴다)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS learning_resolution_links (
    difficulty_context_id BIGINT    NOT NULL,
    resolver_context_id   BIGINT    NOT NULL,
    user_id               BIGINT    NOT NULL,
    created_at            DATETIME  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME  NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (difficulty_context_id),
    INDEX idx_learning_resolution_links_resolver (resolver_context_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------
-- 5. learning_event_meta — 일회 작업·전환 시각 기록
-- ---------------------------------------------------------------------
--   textbook_refs_seeded  교재 ref 시드를 마친 시각
--   cutover_at            실시간 이중 기록을 처음 켠 시각(PR B)
--   textbook_ref_conflicts  운영 중 같은 책 별칭이 이미 다른 ref에 있던 횟수
CREATE TABLE IF NOT EXISTS learning_event_meta (
    meta_key   VARCHAR(40)   NOT NULL,
    meta_value VARCHAR(100)  NOT NULL,
    updated_at DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (meta_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
