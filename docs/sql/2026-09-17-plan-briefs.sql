-- 2026-09-17 상담의 계획 합의(ai_plan_briefs)
--
-- 왜: 상담에서 "자료구조 복구 우선, 영어는 하루 15분, 금요일 밤은 비우기"에 사용자가 "좋아, 그대로 만들어줘"라고
--     했을 때, 계획 생성기는 최근 사용자 발언 8개만 받았다 — AI가 제안한 문장과 그것에 대한 동의는 어디에도 남지
--     않았고, 최근 대화 창(6개) 밖으로 밀리면 합의 자체가 사라졌다. 이 표가 발화자·출처·동의 상태를 가진 합의
--     항목을 대화 단위로 들고 있고, 계획 생성(상담 OFFER → CREATE_PERIOD_PLAN)과 다음 상담이 그것을 읽는다.
--
-- 일정·계획 저장소가 아니다. 오늘/일정/계획 화면 어디도 이 표를 사실로 읽지 않는다. 사용자 승인으로 만들어지는
-- 것은 여전히 ai_proposals → plan_versions/execution_items뿐이다. 지속 선호(다음 기간에도 유효한 것)는 여기가
-- 아니라 기존 user_contexts(사용자 확인 흐름)로 간다.
--
-- 추가 전용이며 재실행해도 된다. FK 없음(2026-08-16 이후 신규 테이블 규칙).

CREATE TABLE IF NOT EXISTS ai_plan_briefs (
    brief_id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id           BIGINT       NOT NULL,
    conversation_id   BIGINT       NOT NULL,

    -- 항목이 바뀔 때마다 +1. 생성 요청·초안은 (brief_id, version)으로 "그때 읽은 합의"를 가리킨다.
    version           INT          NOT NULL DEFAULT 1,

    status            VARCHAR(20)  NOT NULL DEFAULT 'OPEN',

    -- PlanBriefItem JSON 배열: [{"id":1,"kind":"PRIORITY","text":"...","speaker":"USER|ASSISTANT",
    --   "accepted":true,"rejected":false,"removed":false,"scope":"THIS_DRAFT|PERIOD",
    --   "sourceMessageId":210,"acceptedByMessageId":212,"supersedes":null,"topicId":null,"courseId":null,
    --   "executionItemId":null,"revision":1,"history":[],"updatedAt":"..."}, ...]
    items             LONGTEXT     NOT NULL,

    -- 이 합의로 가장 최근에 만든 초안(ai_proposals.proposal_id). 없으면 NULL.
    last_proposal_id  BIGINT       NULL,

    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (brief_id),
    UNIQUE KEY uk_ai_plan_briefs_conversation (conversation_id),
    INDEX idx_ai_plan_briefs_user_updated (user_id, updated_at),

    CONSTRAINT chk_ai_plan_briefs_status CHECK (status IN ('OPEN', 'CLOSED')),
    CONSTRAINT chk_ai_plan_briefs_items  CHECK (JSON_VALID(items))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 확인:
--   SHOW CREATE TABLE ai_plan_briefs;
--
-- 롤백(되돌릴 때만):
--   DROP TABLE ai_plan_briefs;
