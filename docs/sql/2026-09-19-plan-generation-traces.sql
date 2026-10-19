-- 2026-09-19 계획 생성의 실제 전달 기록(plan_generation_traces)
--
-- 왜: 2026-09-19 계획 품질 진단에서 "후보 367개 중 156개만 보였고 뒤쪽 프로젝트 셋은 목록에 아예 못 나왔다"는
--     **추정**이었다 — 선택 호출의 입력 전문과 실제로 줄로 실린 id를 어디에도 남기지 않았기 때문이다
--     (plan_provenance_json의 MATERIAL_SELECTION 계산은 lineage=PARTIAL, 고른 id·이유만 있었다).
--     "목록에 존재함 → 조회 성공 → 모델 입력에 전달됨"은 서로 다른 단계인데 세 번째를 검증할 방법이 없었다.
--
-- 무엇: 생성 회차(generation_id)의 모델 호출마다 한 행. 실제로 보낸 시스템·사용자 프롬프트 전문(불변 스냅샷)과
--       그 입력에 줄로 실린 구간·학습 항목 id, 프로젝트별 집계를 남긴다. 모델의 비공개 사고과정은 저장하지 않는다.
--
-- 소유권·보존:
--   - user_id 조건이 유일한 접근 경로다(FK 없음 — 2026-08-16 이후 신규 테이블 규칙). API는 소유자에게만,
--     기본은 id·집계만 돌려주고 전문은 includeText=true일 때만 준다.
--   - 프롬프트 전문에는 자료 원문이 들어 있다. 자료를 삭제하면 그 자료 id를 포함한 행의 전문을 지운다
--     (material_ids 컬럼으로 찾는다 — 사용자 데이터 삭제 정책과 같게). id·집계는 남는다.
--   - plan.trace.retention-days(기본 30)보다 오래된 행은 새 기록을 쓸 때 함께 지운다.
--
-- 추가 전용이며 재실행해도 된다.

CREATE TABLE IF NOT EXISTS plan_generation_traces (
    trace_id         BIGINT       NOT NULL AUTO_INCREMENT,
    user_id          BIGINT       NOT NULL,
    generation_id    VARCHAR(64)  NOT NULL,

    -- SELECTION / SELECTION_EXPAND / PLAN / PLAN_MORE_EVIDENCE / PLAN_RECOVERY
    call_kind        VARCHAR(32)  NOT NULL,
    call_order       INT          NOT NULL,

    -- 실행한 서버 빌드(git 커밋). 개발한 기능과 실제로 돈 서버를 구분한다. 모르면 NULL.
    api_commit       VARCHAR(64)  NULL,
    model_name       VARCHAR(100) NULL,
    estimated_tokens INT          NULL,

    -- 실제로 보낸 입력(불변 스냅샷). 자료가 삭제되면 NULL로 지워진다(text_purged_at에 시각).
    system_prompt    LONGTEXT     NULL,
    user_prompt      LONGTEXT     NULL,
    prompt_sha256    CHAR(64)     NULL,
    text_purged_at   DATETIME     NULL,

    -- 이 입력에 줄로 실린 id: {"sectionIds":[...], "topicIds":[...], "deliveredSectionIds":[...],
    --   "perCourse":[{"courseId":1,"candidates":21,"shown":21,"selected":3,"delivered":3}, ...]}
    shown_json       LONGTEXT     NOT NULL,

    -- ",12,15," 형태. 자료 삭제 시 LIKE '%,12,%'로 찾는다.
    material_ids     TEXT         NULL,

    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (trace_id),
    INDEX idx_plan_generation_traces_user_gen (user_id, generation_id, call_order),
    INDEX idx_plan_generation_traces_created (created_at),

    CONSTRAINT chk_plan_generation_traces_shown CHECK (JSON_VALID(shown_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 확인:
--   SHOW CREATE TABLE plan_generation_traces;
--
-- 롤백(되돌릴 때만):
--   DROP TABLE plan_generation_traces;
