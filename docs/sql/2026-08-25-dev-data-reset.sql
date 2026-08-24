-- 개발 데이터 리셋. 계정과 일기는 보존한다.
--
-- 스키마는 건드리지 않는다 — 행만 지운다. 마이그레이션이 아니라 운영 작업이지만,
-- 무엇을 지웠고 무엇을 남겼는지가 나중에 "이 데이터는 왜 없나"의 답이 되므로 파일로 남긴다.
--
-- 직전 스냅샷: docs/sql/backup/2026-08-25-pre-reset-full.sql (29개 테이블 전량)
--
-- ★ 남기는 것
--   users                        계정
--   diaries / diary_revisions    일기. UI 호출부가 0건이라 지금 앱에서 볼 수 없지만
--                                7행이 실제 사용자 기록이므로 지우지 않는다
--   ai_usage_logs                AI 사용량 원장. 프로젝트 데이터가 아니라 사용 이력이고
--                                일/월 한도 계산의 근거다. conversation_id가 끊긴 참조로
--                                남지만 FK가 없고 한도 계산은 user_id·날짜만 본다
--   migration_data_adjustments   2026-08-03 이관에서 사람이 내린 판단의 기록. 이력이다
--
-- ★ 지시서에 없었으나 함께 비운 것 (근거 포함)
--   topic_progress               course_topics를 비우면 전부 고아가 된다
--   topic_learning_events        같음
--   ai_context_change_suggestions ai_messages/conversations를 비우면 고아가 된다
--   daily_states                 매퍼 참조 0건인 사문 테이블의 2026-07-08 개발 흔적
--   user_contexts                1행뿐이고 source_message_id가 지워지는 ai_messages를
--                                가리킨다. 내용도 삭제되는 프로젝트의 목표라 함께 비운다


-- ===============================================================
-- 1. 실행 계열 — 자식부터
-- ===============================================================
-- execution_items에는 자기참조 FK(source_execution_item_id)와 자식 넷이 걸려 있다.
DELETE FROM execution_records;
DELETE FROM execution_item_events;
DELETE FROM execution_item_context_links;
DELETE FROM legacy_execution_item_map;

-- 자기참조를 먼저 끊는다. 그냥 DELETE하면 MariaDB가 행 단위로 지우면서
-- fk_execution_items_source_user에 걸린다(부분 완료로 갈라진 잔여 항목이 원본을 가리킨다).
-- SET FOREIGN_KEY_CHECKS=0으로 우회하지 않는다 — 제약을 끄고 지우는 습관이 남으면
-- 언젠가 진짜 참조 오류를 조용히 통과시킨다.
UPDATE execution_items SET source_execution_item_id = NULL WHERE source_execution_item_id IS NOT NULL;
DELETE FROM execution_items;

DELETE FROM plan_versions;


-- ===============================================================
-- 2. AI 계열
-- ===============================================================
DELETE FROM ai_proposal_schedule_previews;
DELETE FROM ai_proposal_items;
DELETE FROM ai_proposals;
DELETE FROM ai_context_change_suggestions;
DELETE FROM ai_messages;
DELETE FROM ai_conversations;


-- ===============================================================
-- 3. 학습·프로젝트 계열
-- ===============================================================
DELETE FROM study_recommendations;
DELETE FROM topic_learning_events;
DELETE FROM topic_progress;
DELETE FROM course_material_analyses;
DELETE FROM course_topics;
DELETE FROM course_notes;
DELETE FROM material_links;
DELETE FROM course_materials;
DELETE FROM courses;


-- ===============================================================
-- 4. 남은 개발 흔적
-- ===============================================================
DELETE FROM daily_states;
DELETE FROM user_contexts;
DELETE FROM context_items;


-- ===============================================================
-- 5. AUTO_INCREMENT 리셋
-- ===============================================================
-- 새 데이터가 1번부터 시작하면 나중에 "이건 리셋 후 것"이 id만으로 구분된다.
-- users/diaries는 데이터를 남기므로 건드리지 않는다.
ALTER TABLE execution_records              AUTO_INCREMENT = 1;
ALTER TABLE execution_item_events          AUTO_INCREMENT = 1;
ALTER TABLE execution_item_context_links   AUTO_INCREMENT = 1;
ALTER TABLE execution_items                AUTO_INCREMENT = 1;
ALTER TABLE plan_versions                  AUTO_INCREMENT = 1;
ALTER TABLE ai_proposal_schedule_previews  AUTO_INCREMENT = 1;
ALTER TABLE ai_proposal_items              AUTO_INCREMENT = 1;
ALTER TABLE ai_proposals                   AUTO_INCREMENT = 1;
ALTER TABLE ai_context_change_suggestions  AUTO_INCREMENT = 1;
ALTER TABLE ai_messages                    AUTO_INCREMENT = 1;
ALTER TABLE ai_conversations               AUTO_INCREMENT = 1;
ALTER TABLE study_recommendations          AUTO_INCREMENT = 1;
ALTER TABLE topic_learning_events          AUTO_INCREMENT = 1;
ALTER TABLE topic_progress                 AUTO_INCREMENT = 1;
ALTER TABLE course_material_analyses       AUTO_INCREMENT = 1;
ALTER TABLE course_topics                  AUTO_INCREMENT = 1;
ALTER TABLE course_notes                   AUTO_INCREMENT = 1;
ALTER TABLE material_links                 AUTO_INCREMENT = 1;
ALTER TABLE course_materials               AUTO_INCREMENT = 1;
ALTER TABLE courses                        AUTO_INCREMENT = 1;
ALTER TABLE daily_states                   AUTO_INCREMENT = 1;
ALTER TABLE user_contexts                  AUTO_INCREMENT = 1;
ALTER TABLE context_items                  AUTO_INCREMENT = 1;


-- ===============================================================
-- 검증
-- ===============================================================
--   users 18행, diaries 7행이 그대로인가
--   위에서 비운 테이블 전부 0행인가
--   data/materials/ 아래 파일 0개인가 (DB에서 자료를 비우면 전부 고아가 된다)
