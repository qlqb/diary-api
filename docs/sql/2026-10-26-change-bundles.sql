-- ===============================================================
-- 변경 묶음(상담이 제안한, 적용 전 변경)과 상담 턴 단계 기록 (2026-10-26)
-- ===============================================================
-- 새 테이블만 만든다. 기존 테이블·데이터는 건드리지 않는다.
--
-- ai_change_bundles / ai_change_bundle_items
--   상담 도구 단계가 모은 "기존 데이터를 이렇게 바꾸자"는 변경. 사용자가 검토·선택해 적용할 때까지 원본(일정·실행·과제)은
--   바뀌지 않는다. 묶음은 한 번 적용되면 끝난다(선택하지 않은 항목은 DISMISSED). 재접속 시 이 행으로 카드를 복원한다 —
--   도구 호출을 다시 실행해 복원하지 않는다.
--
--   revision: 선택·재확인으로 내용이 바뀔 때마다 올라간다. 적용·선택 요청은 기대 리비전을 보내고 서버가 잠금 아래 비교한다.
--   apply_idempotency_key / apply_request_hash / apply_result_json: 같은 키로 다시 오면 저장된 결과를 돌려주고, 같은 키에
--   다른 내용이 오면 거절한다(중복 클릭·응답 유실 후 재시도).
--   basis_fingerprint: 제안 때 영향 범위의 실제 일정(약속·시각 고정 실행·반복 회차) 지문. 적용 때 잠금 조회로 다시 만들어
--   다르면 STALE로 거절한다(미리보기 뒤 새 일정이 생긴 경우).
--
-- consult_turn_steps
--   상담 턴의 단계별 기록(모델 호출·도구 호출·상한·오류). 실패 위치를 찾는 데 쓰는 기록이며, 묶음 복원에는 쓰지 않는다.
--   인자·결과는 잘라서 남긴다.

CREATE TABLE IF NOT EXISTS ai_change_bundles (
    bundle_id             BIGINT       NOT NULL AUTO_INCREMENT,
    user_id               BIGINT       NOT NULL,
    conversation_id       BIGINT       NOT NULL,
    source_message_id     BIGINT       NOT NULL,           -- 이 묶음을 만든 ASSISTANT 메시지
    status                VARCHAR(20)  NOT NULL DEFAULT 'PROPOSED',
    revision              INT          NOT NULL DEFAULT 1,
    range_start           DATE         NULL,               -- 시간 효과가 닿는 날짜 범위(지문 범위)
    range_end             DATE         NULL,
    basis_fingerprint     VARCHAR(64)  NULL,
    apply_idempotency_key VARCHAR(100) NULL,
    apply_request_hash    VARCHAR(64)  NULL,
    apply_result_json     LONGTEXT     NULL,
    created_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    resolved_at           DATETIME     NULL,
    PRIMARY KEY (bundle_id),
    INDEX idx_change_bundles_conversation (conversation_id, status),
    INDEX idx_change_bundles_source_message (source_message_id),
    UNIQUE KEY uq_change_bundles_apply_key (user_id, apply_idempotency_key),
    CONSTRAINT chk_change_bundles_status CHECK (status IN ('PROPOSED', 'APPLIED', 'DISMISSED')),
    CONSTRAINT chk_change_bundles_revision CHECK (revision >= 1),
    CONSTRAINT chk_change_bundles_result CHECK (apply_result_json IS NULL OR JSON_VALID(apply_result_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_change_bundle_items (
    item_id        BIGINT       NOT NULL AUTO_INCREMENT,
    bundle_id      BIGINT       NOT NULL,
    user_id        BIGINT       NOT NULL,
    seq            INT          NOT NULL,                  -- 묶음 안의 순서(적용 순서이기도 하다)
    op_type        VARCHAR(40)  NOT NULL,
    target_key     VARCHAR(120) NOT NULL,                  -- 같은 대상의 충돌하는 op를 막는 키
    op_json        LONGTEXT     NOT NULL,                  -- 정규화한 op(검토 당시 대상 상태 포함)
    summary        VARCHAR(300) NOT NULL,
    depends_on     VARCHAR(200) NULL,                      -- 같은 묶음의 seq 목록(쉼표)
    origin         VARCHAR(20)  NOT NULL DEFAULT 'PROPOSED', -- PROPOSED(모델 제안) / INFERRED_LINK(추정 연결 — 기본 미선택)
    selected       TINYINT(1)   NOT NULL DEFAULT 1,
    status         VARCHAR(20)  NOT NULL DEFAULT 'PROPOSED',
    check_code     VARCHAR(40)  NULL,                      -- 마지막 확인 결과(OK가 아니면 적용 불가)
    check_message  VARCHAR(300) NULL,
    result_json    LONGTEXT     NULL,                      -- 적용 결과(만든 예외·기록 id 등)
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (item_id),
    UNIQUE KEY uq_change_bundle_items_seq (bundle_id, seq),
    INDEX idx_change_bundle_items_bundle (bundle_id),
    CONSTRAINT chk_change_bundle_items_status CHECK (status IN ('PROPOSED', 'APPLIED', 'DISMISSED')),
    CONSTRAINT chk_change_bundle_items_selected CHECK (selected IN (0, 1)),
    CONSTRAINT chk_change_bundle_items_op CHECK (JSON_VALID(op_json)),
    CONSTRAINT chk_change_bundle_items_result CHECK (result_json IS NULL OR JSON_VALID(result_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS consult_turn_steps (
    step_id            BIGINT       NOT NULL AUTO_INCREMENT,
    user_id            BIGINT       NOT NULL,
    conversation_id    BIGINT       NOT NULL,
    request_message_id BIGINT       NOT NULL,              -- 이 턴의 USER 메시지
    seq                INT          NOT NULL,
    kind               VARCHAR(20)  NOT NULL,              -- MODEL / TOOL / LIMIT / ERROR / ANSWER
    name               VARCHAR(80)  NULL,
    args_json          TEXT         NULL,
    result_code        VARCHAR(40)  NULL,
    result_text        TEXT         NULL,
    latency_ms         INT          NULL,
    input_tokens       INT          NULL,
    output_tokens      INT          NULL,
    created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (step_id),
    INDEX idx_consult_turn_steps_message (request_message_id, seq),
    INDEX idx_consult_turn_steps_conversation (conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 되돌리기(필요할 때만): DROP TABLE consult_turn_steps; DROP TABLE ai_change_bundle_items; DROP TABLE ai_change_bundles;
