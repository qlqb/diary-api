-- 2026-09-18 초안 검토 상태(ai_proposals.review_state_json)
--
-- 왜: 초안을 검토하며 고친 제목·항목 포함/제외·직접 편집값·미해결 질문의 답은 화면의 로컬 상태였다. 새로고침·탭 이동에서
--     초안 id만 세션에 남고 나머지는 초기화됐다. 검토 상태는 실행 데이터가 아니다 — 저장해도 일정은 바뀌지 않고, 확정할 때
--     화면의 포함/제외와 같은 값으로 적용된다.
--
-- 모양: {"version":n, "title":"…", "excludedProposalItemIds":[…], "editedItems":[{proposalItemId, …}], "answers":{…},
--        "savedAt":"…"}. version은 저장마다 +1이고 늦은 저장(옛 version)은 거절한다. PROPOSED인 초안에만 쓴다.
--
-- 추가 전용이며 재실행해도 된다.

ALTER TABLE ai_proposals
    ADD COLUMN IF NOT EXISTS review_state_json LONGTEXT NULL AFTER plan_request_json;

-- 확인:
--   SHOW COLUMNS FROM ai_proposals LIKE 'review_state_json';
--
-- 롤백(되돌릴 때만):
--   ALTER TABLE ai_proposals DROP COLUMN review_state_json;
