-- AI 상담 구조 개편: 자유 대화(ai_conversations/ai_messages) 도입 + 사용량 로그 +
-- ai_proposals와 이를 만든 ASSISTANT 메시지 연결 + 대화방 동시 요청 차단.
--
-- 기존 docs/sql/*.sql 컨벤션(날짜 파일, 수동 적용)을 따른다. Flyway/Liquibase는 도입하지 않는다.
-- 엔진은 MariaDB 10.4 기준(FK 미사용, ENUM 대신 VARCHAR + CHECK, JSON은 자동으로
-- json_valid CHECK가 붙는 MariaDB 별칭 타입).
--
-- 2026-08-05 최초 작성 이후 로컬 DB에 아직 적용되지 않은 것을 확인하고(정보 스키마 조회로
-- ai_conversations/ai_messages/ai_usage_logs 부재, ai_proposals.conversation_id는
-- VARCHAR(100)이며 전량 NULL 확인) 이 파일 자체를 최종 구조로 수정했다. 이미 적용된 환경이
-- 있다면 이 파일을 다시 실행하지 말고 별도 ALTER 스크립트를 새로 작성해야 한다.

CREATE TABLE ai_conversations (
    conversation_id           BIGINT       NOT NULL AUTO_INCREMENT,
    user_id                   BIGINT       NOT NULL,
    scope                     VARCHAR(20)  NOT NULL,
    status                    VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    summary                   TEXT         NULL,
    -- 대화방 동시 요청 차단용. 진행 중인 요청의 ai_messages.message_id(그 요청을 대표하는
    -- USER 행)를 가리킨다. NULL이면 진행 중인 요청이 없다는 뜻이다. 자바 메모리 변수가 아니라
    -- DB에 원자적으로 기록해야 여러 서버 인스턴스나 재시작에도 안전하다.
    active_request_message_id BIGINT       NULL,
    active_request_started_at DATETIME     NULL,
    created_at                DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (conversation_id),
    CONSTRAINT chk_ai_conversations_scope
        CHECK (scope IN ('PLAN', 'TODAY', 'EXECUTION', 'CONTEXT', 'MIXED')),
    CONSTRAINT chk_ai_conversations_status
        CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    -- 사용자의 ACTIVE 대화 목록을 최근순으로 조회하는 실제 쿼리에 맞춘 인덱스.
    INDEX idx_ai_conversations_user_updated (user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_messages (
    message_id         BIGINT        NOT NULL AUTO_INCREMENT,
    conversation_id     BIGINT        NOT NULL,
    user_id             BIGINT        NOT NULL,
    role                VARCHAR(10)   NOT NULL,
    content             MEDIUMTEXT    NOT NULL,
    -- ASSISTANT 메시지만 값을 가진다.
    response_type       VARCHAR(10)   NULL,
    -- USER(요청 대표) 메시지가 응답을 기다리는 ASSISTANT 메시지가 어떤 요청에 대한 응답인지.
    -- ASSISTANT 행만 값을 가진다. 한 USER 메시지에 ASSISTANT 응답은 하나만 저장하므로 유일하다.
    reply_to_message_id BIGINT        NULL,
    idempotency_key     VARCHAR(100)  NULL,
    -- PROCESSING: 저장 직후, AI 호출 진행 중 (USER 메시지만 이 상태를 거친다)
    -- COMPLETED : USER는 응답까지 정상 종료, ASSISTANT는 저장된 그대로 최종
    -- FAILED    : 오류/타임아웃/연결 종료로 응답을 못 만듦 (USER 메시지만 이 상태가 될 수 있다.
    --             실패한 턴은 ASSISTANT 행 자체를 만들지 않는다)
    status              VARCHAR(20)   NOT NULL DEFAULT 'COMPLETED',
    created_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (message_id),
    CONSTRAINT chk_ai_messages_role CHECK (role IN ('USER', 'ASSISTANT')),
    CONSTRAINT chk_ai_messages_response_type
        CHECK (response_type IS NULL OR response_type IN ('CHAT', 'OFFER', 'PROPOSAL')),
    CONSTRAINT chk_ai_messages_status CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED')),
    -- 한 USER 메시지에 ASSISTANT 응답은 하나뿐이라는 전제를 DB로도 강제한다.
    UNIQUE KEY uq_ai_messages_reply_to (reply_to_message_id),
    -- 사용자별 idempotency_key 중복 전송 차단. NULL은 유니크 제약에서 서로 다른 값으로
    -- 취급되므로(MariaDB), idempotency_key가 없는 메시지끼리는 충돌하지 않는다.
    UNIQUE KEY uq_ai_messages_user_idem (user_id, idempotency_key),
    -- 대화의 최근 메시지를 message_id 기준으로 LIMIT 조회하는 실제 쿼리에 맞춘 인덱스.
    INDEX idx_ai_messages_conversation (conversation_id, message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_usage_logs (
    usage_log_id       BIGINT       NOT NULL AUTO_INCREMENT,
    user_id             BIGINT       NOT NULL,
    conversation_id     BIGINT       NULL,
    -- 이 사용량이 어떤 요청(ai_messages의 USER 행)에서 발생했는지.
    request_message_id  BIGINT       NULL,
    feature             VARCHAR(30)  NOT NULL DEFAULT 'AI_CONSULTATION',
    model                VARCHAR(50)  NOT NULL,
    input_tokens         INT          NULL,
    -- Spring AI 표준 API로 얻을 수 없는 값. 실제로 채울 수 있게 되기 전까지 항상 NULL이고,
    -- 다른 값에서 추측해 채우지 않는다.
    cached_tokens        INT          NULL,
    output_tokens         INT          NULL,
    -- SUCCESS / FAILED / CANCELLED / TIMEOUT
    result_status        VARCHAR(20)  NOT NULL DEFAULT 'SUCCESS',
    error_code            VARCHAR(20)  NULL,
    provider_request_id  VARCHAR(100) NULL,
    request_id            VARCHAR(100) NULL,
    created_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (usage_log_id),
    CONSTRAINT chk_ai_usage_logs_result_status
        CHECK (result_status IN ('SUCCESS', 'FAILED', 'CANCELLED', 'TIMEOUT')),
    CONSTRAINT chk_ai_usage_logs_input_tokens_nonneg CHECK (input_tokens IS NULL OR input_tokens >= 0),
    CONSTRAINT chk_ai_usage_logs_cached_tokens_nonneg CHECK (cached_tokens IS NULL OR cached_tokens >= 0),
    CONSTRAINT chk_ai_usage_logs_output_tokens_nonneg CHECK (output_tokens IS NULL OR output_tokens >= 0),
    -- 사용자별 기간 사용량 조회(일/월 한도 계산)에 맞춘 인덱스.
    INDEX idx_ai_usage_logs_user_created (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ai_proposals: 대화·원본 메시지 연결.
-- conversation_id는 그동안 항상 NULL로만 저장되던 컬럼이라(AiProposal.conversationId는 여태
-- 세팅된 적이 없다 — 적용 전 information_schema 조회로 VARCHAR(100)이고 전량 NULL임을
-- 확인했다) 타입을 BIGINT로 맞춰도 기존 데이터에 영향이 없다.
--
-- source_message_id는 이 Proposal을 만든 ASSISTANT 메시지를 가리킨다(사용자 메시지가 아니다
-- — ai_messages.reply_to_message_id로 그 ASSISTANT가 어떤 USER 요청에 대한 응답인지
-- 이미 추적되므로, Proposal 쪽에서 또 사용자 메시지를 직접 가리키면 같은 관계를 두 컬럼이
-- 중복 보관하게 된다). 메시지 하나는 Proposal을 하나만 만들 수 있다는 전제를 UNIQUE로 강제한다.
-- FK는 프로젝트 방침에 따라 추가하지 않는다 — 서비스에서 사용자 소유권, 대화 일치, 메시지
-- role=ASSISTANT 여부를 검증한다.
ALTER TABLE ai_proposals
    MODIFY COLUMN conversation_id BIGINT NULL,
    ADD COLUMN source_message_id BIGINT NULL AFTER conversation_id,
    ADD UNIQUE KEY uq_ai_proposals_source_message (source_message_id);
