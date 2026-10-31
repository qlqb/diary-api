-- 7일 범위 일정 후보 배치(Timefold) 지원: AI가 대화에서 파악한 사용 불가 시간을 제안
-- 헤더에 보존하고, 마지막으로 계산한 배치 미리보기를 저장해 새로고침해도 복원되게 한다.
--
-- 기존 docs/sql/*.sql 컨벤션(날짜 파일, 수동 적용)을 따른다. Flyway/Liquibase는 도입하지 않는다.
-- 엔진은 MariaDB 10.4 기준(FK 미사용, ENUM 대신 VARCHAR + CHECK, JSON은 자동으로
-- json_valid CHECK가 붙는 MariaDB 별칭 타입).
--
-- 적용 전 information_schema로 ai_proposals.unavailable_windows와
-- ai_proposal_schedule_previews 부재를 확인한 뒤 적용한다. 기존 사용자 데이터를 삭제하는
-- 문장은 없다.

ALTER TABLE ai_proposals
    ADD COLUMN unavailable_windows JSON NULL AFTER target_scope;

CREATE TABLE ai_proposal_schedule_previews (
    schedule_preview_id  BIGINT   NOT NULL AUTO_INCREMENT,
    proposal_id          BIGINT   NOT NULL,
    user_id              BIGINT   NOT NULL,
    horizon_start        DATE     NOT NULL,
    horizon_end          DATE     NOT NULL,
    -- AvailabilityWindowDto 목록 — 계산 당시 화면에 보여준 가용시간 요약(출처·신뢰도·이유 포함).
    availability_windows JSON     NOT NULL,
    -- 사용자가 이 계산에 반영한 예외(AvailabilityOverrideRequest 목록). 다음 재계산에서도
    -- 그대로 다시 보내야 하므로 화면 복원용으로 별도 보존한다.
    user_overrides        JSON     NULL,
    -- PlacedItemDto / UnplacedItemDto 목록.
    placed_items          JSON     NOT NULL,
    unplaced_items         JSON     NOT NULL,
    computed_at            DATETIME NOT NULL,
    created_at             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (schedule_preview_id),
    -- Proposal 하나당 미리보기는 항상 최신 계산 결과 하나만 보존한다(재계산은 upsert).
    UNIQUE KEY uq_ai_proposal_schedule_previews_proposal (proposal_id),
    INDEX idx_ai_proposal_schedule_previews_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
