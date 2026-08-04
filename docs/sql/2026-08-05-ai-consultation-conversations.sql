-- AI 상담 구조 개편: 자유 대화(ai_conversations/ai_messages) 도입 + 사용량 로그 +
-- ai_proposals와 대화/원본 메시지 연결.
--
-- 기존 docs/sql/*.sql 컨벤션(날짜 파일, 수동 적용)을 따른다. Flyway/Liquibase는 도입하지 않는다.
-- 엔진은 MariaDB 10.4 기준(FK 미사용, ENUM 대신 VARCHAR + CHECK, JSON은 자동으로
-- json_valid CHECK가 붙는 MariaDB 별칭 타입).

CREATE TABLE ai_conversations (
    conversation_id BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    scope           VARCHAR(20)  NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    summary         TEXT         NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (conversation_id),
    CONSTRAINT chk_ai_conversations_scope
        CHECK (scope IN ('PLAN', 'TODAY', 'EXECUTION', 'CONTEXT', 'MIXED')),
    CONSTRAINT chk_ai_conversations_status
        CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    INDEX idx_ai_conversations_user (user_id, updated_at)
);

CREATE TABLE ai_messages (
    message_id       BIGINT        NOT NULL AUTO_INCREMENT,
    conversation_id  BIGINT        NOT NULL,
    user_id          BIGINT        NOT NULL,
    role             VARCHAR(10)   NOT NULL,
    content          MEDIUMTEXT    NOT NULL,
    response_type    VARCHAR(10)   NULL,
    proposal_id      BIGINT        NULL,
    idempotency_key  VARCHAR(100)  NULL,
    status           VARCHAR(20)   NOT NULL DEFAULT 'COMPLETED',
    created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (message_id),
    CONSTRAINT chk_ai_messages_role CHECK (role IN ('USER', 'ASSISTANT')),
    CONSTRAINT chk_ai_messages_response_type
        CHECK (response_type IS NULL OR response_type IN ('CHAT', 'OFFER', 'PROPOSAL')),
    CONSTRAINT chk_ai_messages_status CHECK (status IN ('COMPLETED', 'FAILED')),
    -- 사용자별 idempotency_key 중복 전송 차단. NULL은 유니크 제약에서 서로 다른 값으로
    -- 취급되므로(MariaDB), idempotency_key가 없는 메시지끼리는 충돌하지 않는다.
    UNIQUE KEY uq_ai_messages_user_idem (user_id, idempotency_key),
    INDEX idx_ai_messages_conversation (conversation_id, message_id)
);

CREATE TABLE ai_usage_logs (
    usage_log_id    BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    conversation_id BIGINT       NULL,
    feature         VARCHAR(30)  NOT NULL DEFAULT 'AI_CONSULTATION',
    model           VARCHAR(50)  NOT NULL,
    input_tokens    INT          NULL,
    cached_tokens   INT          NULL,
    output_tokens   INT          NULL,
    request_id      VARCHAR(100) NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (usage_log_id),
    INDEX idx_ai_usage_logs_user_created (user_id, created_at)
);

-- ai_proposals: 대화·원본 메시지 연결.
-- conversation_id는 그동안 항상 NULL로만 저장되던 컬럼이라(AiProposal.conversationId는 여태
-- 세팅된 적이 없다) 타입을 BIGINT로 맞춰도 기존 데이터에 영향이 없다.
ALTER TABLE ai_proposals
    MODIFY COLUMN conversation_id BIGINT NULL,
    ADD COLUMN source_message_id BIGINT NULL AFTER conversation_id;
