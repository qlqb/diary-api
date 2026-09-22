-- 범용 장기 컨텍스트 변경 후보 + 사용자 승인 흐름.
--
-- 목적: "이사", "알바 변경" 같은 개별 생활 사건을 백엔드 enum/if문으로 다루지 않는다.
-- AI가 사용자 발언과 기존 장기 컨텍스트를 비교해 범용 연산(ADD/SUPERSEDE/MARK_STALE/
-- ARCHIVE/CONFIRM) 후보만 만들고, 서버는 그 후보를 ai_context_change_suggestions에만
-- 저장한다. user_contexts(실제 장기 컨텍스트)는 사용자가 명시적으로 승인(apply)했을 때만
-- 바뀐다 — AI 판단만으로 즉시 UPDATE/DELETE하지 않는다.
--
-- 기존 docs/sql/*.sql 컨벤션(날짜 파일, 수동 적용)을 따른다. Flyway/Liquibase는 도입하지 않는다.
-- 엔진은 MariaDB 10.4 기준(FK 미사용, ENUM 대신 VARCHAR + CHECK).

-- user_contexts: 실제로 확정된 장기 사실. 물리 삭제하지 않는다 — SUPERSEDE/ARCHIVE는 상태만
-- 바꾸고 이력을 보존한다. category 같은 생활 사건 유형 컬럼을 두지 않는다 — content(사람이
-- 읽는 문장)가 유일한 핵심 데이터이고, 의미 판단은 전부 AI/사용자의 몫이다.
CREATE TABLE user_contexts (
    context_id             BIGINT       NOT NULL AUTO_INCREMENT,
    user_id                BIGINT       NOT NULL,
    content                TEXT         NOT NULL,
    -- ACTIVE: 지금 유효하다고 보는 사실. STALE: 재확인이 필요할 수 있음(삭제 아님).
    -- SUPERSEDED: 더 최신 사실로 대체됨(이력 보존). ARCHIVED: 더 이상 상담/계획에 쓰지 않음.
    status                  VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    -- USER_CONFIRMED: 사용자가 직접 확정. AI_SUGGESTION_APPROVED: AI 후보를 사용자가 승인.
    source_type             VARCHAR(30)  NOT NULL,
    -- 이 컨텍스트를 만든 계기가 된 사용자 발언(ai_messages.message_id). 없을 수 있다.
    source_message_id       BIGINT       NULL,
    -- SUPERSEDE로 이 행이 새로 생겼다면, 대체된 기존 context_id. 그 기존 행 자체는
    -- 삭제되지 않고 status=SUPERSEDED로만 남는다.
    supersedes_context_id   BIGINT       NULL,
    -- CONFIRM 적용 시(STALE -> ACTIVE) 갱신된다.
    confirmed_at            DATETIME     NULL,
    created_at              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (context_id),
    CONSTRAINT chk_user_contexts_status
        CHECK (status IN ('ACTIVE', 'STALE', 'SUPERSEDED', 'ARCHIVED')),
    CONSTRAINT chk_user_contexts_source_type
        CHECK (source_type IN ('USER_CONFIRMED', 'AI_SUGGESTION_APPROVED')),
    -- ContextSnapshotService가 상담마다 ACTIVE/STALE만 조회하는 실제 쿼리에 맞춘 인덱스.
    INDEX idx_user_contexts_user_status (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ai_context_change_suggestions: AI가 만든 변경 "후보"만 담는다. 실제 장기 컨텍스트와
-- 절대 같은 테이블에 섞지 않는다 — 사용자가 apply하기 전까지 user_contexts는 이 테이블의
-- 존재를 전혀 모른다.
CREATE TABLE ai_context_change_suggestions (
    suggestion_id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id                 BIGINT       NOT NULL,
    conversation_id         BIGINT       NOT NULL,
    -- 이 후보를 만든 ASSISTANT 메시지(ai_proposals.source_message_id와 같은 관례).
    source_message_id       BIGINT       NOT NULL,
    -- ADD / SUPERSEDE / MARK_STALE / ARCHIVE / CONFIRM. 백엔드는 이 다섯 개 범용 연산만 안다.
    operation                VARCHAR(20)  NOT NULL,
    -- ADD면 NULL, 그 외 연산은 필수(대상 user_contexts.context_id). FK는 프로젝트 방침에 따라
    -- 추가하지 않는다 — ContextChangeSuggestionService가 소유권과 존재 여부를 검증한다.
    target_context_id       BIGINT       NULL,
    -- ADD/SUPERSEDE만 값을 가진다. MARK_STALE/ARCHIVE/CONFIRM은 NULL이어야 한다.
    proposed_content         TEXT         NULL,
    -- 모든 연산에서 필수 — 왜 이 후보를 만들었는지 사용자에게 보여줄 근거.
    reason                   VARCHAR(500) NOT NULL,
    -- PROPOSED: 아직 승인/거절 전. APPLIED: 사용자가 승인해 user_contexts에 반영됨.
    -- DISMISSED: 사용자가 저장하지 않기로 함.
    status                   VARCHAR(20)  NOT NULL DEFAULT 'PROPOSED',
    -- apply가 만들거나 바꾼 user_contexts.context_id. 재적용 시 중복 생성을 막는
    -- idempotency 판단에도 쓰인다(APPLIED인데 이 값이 있으면 새로 만들지 않고 그대로 반환).
    resulting_context_id    BIGINT       NULL,
    created_at               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at              DATETIME     NULL,
    PRIMARY KEY (suggestion_id),
    CONSTRAINT chk_ai_ccs_operation
        CHECK (operation IN ('ADD', 'SUPERSEDE', 'MARK_STALE', 'ARCHIVE', 'CONFIRM')),
    CONSTRAINT chk_ai_ccs_status
        CHECK (status IN ('PROPOSED', 'APPLIED', 'DISMISSED')),
    -- 대화 재진입 시 "conversation별 pending suggestion" 복구 쿼리에 맞춘 인덱스.
    INDEX idx_ai_ccs_conversation_status (conversation_id, status),
    -- 턴 완료 직후 idempotency 재생(같은 요청 재전송)에서 source_message_id로 다시 찾는 쿼리용.
    INDEX idx_ai_ccs_source_message (source_message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
