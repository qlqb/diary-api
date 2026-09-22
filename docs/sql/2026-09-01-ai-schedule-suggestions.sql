-- AI가 대화에서 뽑은 "아직 승인되지 않은 일정 사실 후보": ai_schedule_suggestions.
--
-- 사용자가 "금요일 7시에 홍대에서 친구 만나"라고 말하면 AI가 구조화된 후보를 만들고,
-- 사용자가 검토·수정한 뒤 [적용]을 눌렀을 때만 실제 원본(one_off_commitments 또는
-- routines)에 저장된다.
--
-- 기존 docs/sql/*.sql 컨벤션(날짜 파일, 수동 적용)을 따른다. MariaDB 10.4 기준.
-- 신규 테이블은 FK를 걸지 않는다(2026-08-16-material-store.sql에서 정한 컨벤션).
--
-- ###############################################################
-- 이 테이블은 일정 저장소가 아니다
--
-- 여기 있는 행은 "AI가 이렇게 이해했다"는 후보일 뿐이고, 오늘/일정 화면도 가용시간
-- 계산도 이 테이블을 보지 않는다. 승인된 사실만 원본 테이블로 간다.
--
-- ai_context_change_suggestions와 같은 모양이다(대화에서 나온 후보를 PROPOSED로 쌓고
-- 사용자가 APPLIED/DISMISSED로 결론짓는다). 그 테이블과 합치지 않는 이유는 담는 것이
-- 다르기 때문이다 — 저쪽은 자연어 문장 하나이고 이쪽은 구조화된 일정 payload다.
-- ###############################################################
--
-- ###############################################################
-- source_message_id에 UNIQUE를 걸지 않는다
--
-- 한 발언에서 후보가 여럿 나올 수 있다:
--   "금요일엔 친구 만나고 토요일엔 병원 가."  -> COMMITMENT 후보 2개
-- UNIQUE를 걸면 둘째부터 저장에 실패하고, 사용자는 자기가 말한 절반만 보게 된다.
-- ###############################################################

CREATE TABLE ai_schedule_suggestions (
    suggestion_id     BIGINT      NOT NULL AUTO_INCREMENT,

    user_id           BIGINT      NOT NULL,
    conversation_id   BIGINT      NOT NULL,
    source_message_id BIGINT      NOT NULL,

    -- COMMITMENT면 one_off_commitments로, ROUTINE이면 routines로 간다.
    kind              VARCHAR(20) NOT NULL,

    -- 승인 시 그대로 도메인 요청으로 읽는 JSON. 필드 이름은 각각
    -- CommitmentCreateRequest / RoutineSaveRequest와 맞춘다 — AI 전용 이름을 따로 만들면
    -- 승인 경로에 변환 계층이 하나 더 생기고, 그 계층이 검증을 우회할 자리가 된다.
    proposed_payload  LONGTEXT    NOT NULL,

    status            VARCHAR(20) NOT NULL DEFAULT 'PROPOSED',

    created_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at       DATETIME    NULL,

    PRIMARY KEY (suggestion_id),

    -- 대화 재진입 시 미처리 후보를 다시 그리는 경로.
    INDEX idx_ai_schedule_conversation_status (conversation_id, status),
    -- idempotency 재생: 그 ASSISTANT 메시지가 만든 후보를 상태와 무관하게 전부.
    INDEX idx_ai_schedule_source_message (source_message_id),

    CONSTRAINT chk_ai_schedule_kind CHECK (kind IN ('COMMITMENT', 'ROUTINE')),
    CONSTRAINT chk_ai_schedule_status CHECK (status IN ('PROPOSED', 'APPLIED', 'DISMISSED')),
    CONSTRAINT chk_ai_schedule_payload CHECK (JSON_VALID(proposed_payload))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ===============================================================
-- 적용 확인 (실행 안 함)
-- ===============================================================
--   SELECT TABLE_NAME FROM information_schema.TABLES
--    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_schedule_suggestions';   -- 1행
--
--   SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS
--    WHERE TABLE_SCHEMA = DATABASE()
--      AND CONSTRAINT_NAME IN ('chk_ai_schedule_kind', 'chk_ai_schedule_status',
--                              'chk_ai_schedule_payload');                         -- 3행
--
-- 미처리 후보가 쌓이기만 하는지 확인할 때(정상이면 대부분 APPLIED/DISMISSED로 끝난다):
--
--   SELECT status, COUNT(*) FROM ai_schedule_suggestions GROUP BY status;
