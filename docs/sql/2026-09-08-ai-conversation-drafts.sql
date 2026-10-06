-- 상담 대화의 "진행 중 요청 상태": ai_conversation_drafts.
--
-- 사용자가 "수업 전에 1시간 이동시간 블록 반복으로 만들어주고 근무 전에는 1회성으로"라고
-- 말하면, 확정된 값(1시간, 반복, 1회성)을 서버가 여기 들고 있는다. 이전에는 서버가 아무것도
-- 들고 있지 않아 매 턴 모델이 [최근 대화] 6개만 보고 처음부터 복원했고, 그 결과 20턴 중
-- 17턴이 되묻기였다(2026-09-08 00:54~01:06 로그).
--
-- 기존 docs/sql/*.sql 컨벤션(날짜 파일, 수동 적용)을 따른다. MariaDB 10.4 기준.
-- 신규 테이블은 FK를 걸지 않는다(2026-08-16-material-store.sql에서 정한 컨벤션).
--
-- ###############################################################
-- 이 테이블은 일정 저장소가 아니다 — 승인 전 AI 작업 상태다
--
-- 여기 있는 행은 "지금까지 확인된 요청 조각"일 뿐이고, 오늘/일정 화면도 가용시간 계산도
-- 이 테이블을 보지 않는다. 사용자에게 보이는 것은 draft가 아니라 그것으로 만든
-- proposal(ai_schedule_suggestions)이며, 원본(routines / one_off_commitments)에는 사용자가
-- 그 카드를 승인했을 때만 들어간다. draft → proposal → 원본, 이 순서를 건너뛰는 경로는 없다.
--
-- draft는 모델이 들고 있지 않다. 모델은 매 턴 서버가 준 현재 draft를 읽고 routing과
-- 수정(draftOps)을 제안할 뿐이고, 적용·저장·readiness 판정은 전부 서버가 한다.
-- ###############################################################
--
-- ###############################################################
-- version 컬럼을 두지 않는다
--
-- draft 갱신은 반드시 대화 잠금(ai_conversations.active_request_message_id) 안에서만
-- 일어난다. 대화 단위 동시 요청 차단과 ai_messages.idempotency_key가 이미 직렬화를
-- 보장하므로 행 단위 낙관적 락이 설 자리가 없다. 잠금 밖에서 draft를 쓰는 경로를 만들지
-- 않는 것이 이 표의 전제다.
-- ###############################################################
--
-- ###############################################################
-- 대화당 OPEN draft는 여러 개다
--
-- 한 발화가 draft를 여럿 만들 수 있고("수업 이동 반복 + 근무 이동 1회성"), 한 발화가 여럿을
-- 동시에 고칠 수 있다("둘 다 30분으로"). "활성 1개" 개념은 없다. 같은 최초 요청에서 나온
-- draft들은 draft_group_id로만 묶인다(별도 테이블 없음).
-- ###############################################################

CREATE TABLE ai_conversation_drafts (
    draft_id            BIGINT       NOT NULL AUTO_INCREMENT,
    user_id             BIGINT       NOT NULL,
    conversation_id     BIGINT       NOT NULL,

    -- 같은 최초 발화에서 함께 생긴 draft들의 묶음. 첫 draft의 draft_id를 그대로 쓴다(별도 시퀀스 없음).
    -- 생성 절차: 첫 draft INSERT → 생성된 draft_id 획득 → 그 행의 draft_group_id를 자기 id로 UPDATE
    -- → 나머지 draft는 그 id를 draft_group_id로 INSERT. 이 쓰기들은 한 트랜잭션(턴 마무리 트랜잭션).
    draft_group_id      BIGINT       NULL,

    draft_type          VARCHAR(30)  NOT NULL,

    -- 사람이 읽는 짧은 라벨. 모델이 대상 draft를 고를 때 프롬프트에 함께 보인다. 예: "수업 전 이동 루틴"
    label               VARCHAR(100) NOT NULL,

    status              VARCHAR(20)  NOT NULL DEFAULT 'OPEN',

    -- {"fieldName": {"value": <json>, "source": "USER|INFERRED|DB|SYSTEM|DEFAULT",
    --                "reason": "…", "confirmationRequired": true|false}, ...}
    -- source는 "어디서 왔는가", confirmationRequired는 "믿고 진행해도 되는가". 별개 축이다.
    fields              LONGTEXT     NOT NULL,

    -- ["fieldName", ...] 서버가 슬롯 맵(DraftSlotRegistry)으로 계산한 필수 누락. 모델이 쓰지 않는다.
    missing_required    LONGTEXT     NOT NULL,

    -- 이 draft를 두고 서버가 ASK를 낸 횟수. 그 턴에 실제로 질문 대상으로 선택된 draft에만 +1.
    ask_count           INT          NOT NULL DEFAULT 0,

    -- PROMOTED 시 만들어진 ai_schedule_suggestions.suggestion_id 목록(JSON 배열).
    -- CREATE_PERIOD_PLAN은 기존 기간 계획 OFFER로 넘어가므로 비어 있다.
    promoted_suggestion_ids LONGTEXT NULL,

    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (draft_id),

    -- 매 턴 "이 대화의 OPEN draft 전부"를 읽는 경로.
    INDEX idx_ai_drafts_conversation_status (conversation_id, status),

    CONSTRAINT chk_ai_drafts_type    CHECK (draft_type IN ('CREATE_ROUTINE', 'CREATE_SCHEDULE', 'CREATE_PERIOD_PLAN')),
    CONSTRAINT chk_ai_drafts_status  CHECK (status IN ('OPEN', 'PROMOTED', 'CANCELLED')),
    CONSTRAINT chk_ai_drafts_fields  CHECK (JSON_VALID(fields)),
    CONSTRAINT chk_ai_drafts_missing CHECK (JSON_VALID(missing_required))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ===============================================================
-- 적용 확인 (실행 안 함)
-- ===============================================================
--   SELECT TABLE_NAME FROM information_schema.TABLES
--    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_conversation_drafts';   -- 1행
--
--   SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS
--    WHERE TABLE_SCHEMA = DATABASE()
--      AND CONSTRAINT_NAME IN ('chk_ai_drafts_type', 'chk_ai_drafts_status',
--                              'chk_ai_drafts_fields', 'chk_ai_drafts_missing');  -- 4행
--
-- OPEN이 쌓이기만 하는지 볼 때(정상이면 대화가 이어지며 PROMOTED/CANCELLED로 끝난다.
-- 대화가 ARCHIVED되면 그 대화의 OPEN은 전부 CANCELLED가 된다):
--
--   SELECT status, COUNT(*) FROM ai_conversation_drafts GROUP BY status;
