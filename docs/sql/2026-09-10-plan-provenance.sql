-- 계획 생성 출처 추적 — 생성 회차 스냅샷과 항목별 근거.
--
-- 문제: 계획 초안이 나왔을 때 "AI에게 무엇을 줬는가"가 아무 데도 남지 않았다. 판단
-- (plan_strategy_json)은 결론이고, 그 결론 이전에 입력으로 준 사실들은 프롬프트 문자열로
-- 만들어져 호출과 함께 사라졌다. 그래서 사용자는 "왜 이 항목이 나왔나"를 물을 수 있어도
-- "무엇을 보고 그렇게 봤나"는 물을 수 없었다.
--
-- MariaDB 10.4 기준. 신규 컬럼이므로 FK 없이 JSON + 인라인 json_valid(JSON 타입이 자동으로
-- 붙여 준다 — 2026-09-06 마이그레이션에서 확인한 성질이다).
-- 인덱스를 만들지 않는다: 세 컬럼 모두 조회 조건이 아니라 이미 고른 행의 속성이다.


-- ===============================================================
-- 1. ai_proposals — 이 초안을 만든 회차에 모델에 무엇을 줬는가
-- ===============================================================
-- plan_strategy_json 옆에 둔다. 둘은 같은 성격의 "초안에 얹힌 불변 기록"이고, 확정 시
-- plan_versions로 함께 복사된다.
--
-- ★ 서버만 쓴다. 제안 수정·적용 API는 이 컬럼을 받지 않는다(요청 DTO에 필드가 없다).
-- 모델 응답에도 이 값은 없다 — 모델이 내는 것은 항목별 인용 번호와 추정뿐이다.
--
-- 값은 조회한 전체 행이 아니라 <b>최종 프롬프트에 실제로 들어간 줄</b>이다. 요약·길이
-- 제한이 이미 적용된 뒤의 값이라, 잘려서 안 들어간 것은 여기에도 없다.
--
-- 별도 테이블로 빼지 않는다. 제안 하나에 회차 하나이고, 회차만 따로 조회하거나 집계할
-- 일이 없다. 재생성은 새 제안 행을 만들므로 회차 보존도 이 구조로 성립한다
-- (plan_strategy_json이 별도 테이블로 가지 않은 것과 같은 이유).
ALTER TABLE ai_proposals
    ADD COLUMN plan_provenance_json JSON NULL
        COMMENT '생성 회차에 모델에 제공한 정보의 스냅샷. 서버 소유·불변'
        AFTER plan_strategy_json;


-- ===============================================================
-- 2. ai_proposal_items — 이 항목의 근거
-- ===============================================================
-- original_payload에 넣지 않는다. 그쪽은 사용자가 화면에서 고친 값이 edited_payload로
-- 다시 쓰이는 자리이고, 근거는 사용자가 쓰는 값이 아니다. 컬럼을 나눠 두면 "클라이언트가
-- 서버 소유 값을 덮어쓸 수 있는가"가 코드 검사가 아니라 구조로 답해진다.
--
-- 회차 스냅샷을 항목마다 복사하지 않는다. 여기에는 refId 목록과 모델의 추정만 담기고,
-- 그 refId가 무엇이었는지는 제안 행의 plan_provenance_json이 갖는다.
--
-- status는 이 JSON 안에 있다(CURRENT / EDITED_BY_USER / NEEDS_REVIEW). 컬럼으로 빼지
-- 않는 이유는 조회 조건이 아니기 때문이다 — 항목을 이미 고른 뒤에 함께 읽는 값이다.
ALTER TABLE ai_proposal_items
    ADD COLUMN evidence_json JSON NULL
        COMMENT '이 항목의 근거(회차 refId·서버 계산·AI 추정)와 그 상태. 서버 소유'
        AFTER original_payload;


-- ===============================================================
-- 3. plan_versions — 확정 이후에도 같은 근거를 볼 수 있게
-- ===============================================================
-- strategy_json과 같은 이유·같은 방식으로 복사한다. 확정 요청은 이 값을 받지 않는다.
--
-- 항목별 근거는 여기로 복사하지 않는다. 적용된 실행 조각은 ai_proposal_items.created_item_id
-- 로 원본 제안 항목에 이미 이어져 있어, 같은 근거를 두 벌 보관하면 어느 쪽이 원본인지가
-- 흐려진다(items_snapshot이 현재 상태를 동기화하지 않는 것과 같은 판단).
ALTER TABLE plan_versions
    ADD COLUMN provenance_json JSON NULL
        COMMENT '확정된 계획을 만든 회차의 제공 정보 스냅샷. 불변'
        AFTER strategy_json;


-- ===============================================================
-- 검증
-- ===============================================================
--   SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE FROM information_schema.COLUMNS
--    WHERE TABLE_SCHEMA = DATABASE()
--      AND ((TABLE_NAME = 'ai_proposals'      AND COLUMN_NAME = 'plan_provenance_json')
--        OR (TABLE_NAME = 'ai_proposal_items' AND COLUMN_NAME = 'evidence_json')
--        OR (TABLE_NAME = 'plan_versions'     AND COLUMN_NAME = 'provenance_json'));   -- 3행
--
-- 잘못된 값이 실제로 막히는지:
--   UPDATE ai_proposals SET plan_provenance_json = '{' ...  → JSON 타입의 인라인 json_valid 위반
--
-- 과거 데이터는 세 컬럼 모두 NULL이고 그대로 둔다. 지금 DB를 뒤져 그때 무엇을 줬는지
-- 역추정해 채우지 않는다 — 그건 스냅샷이 아니라 추측이고, 추측을 근거로 보여주는 것이
-- 이 기능이 막으려는 바로 그것이다. 화면은 NULL을 "이 초안에는 생성 당시 출처 기록이
-- 없습니다"로 말한다.
