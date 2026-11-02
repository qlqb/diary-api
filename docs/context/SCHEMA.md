# SCHEMA — 테이블 요약 (diary-api, MariaDB)

테이블 71개 = `docs/sql/*.sql`의 `CREATE TABLE` 63개 + 저장소에 DDL이 없는 핵심 8개(※ 표시).
전체 DDL은 싣지 않는다 — 필요하면 해당 마이그레이션 파일이나 `docs/product/05-database.md`를 첨부한다.

- 표기: `열(값|값)` = CHECK 또는 문서로 고정된 enum 값. `created_at`·`updated_at`·`user_id`는 생략(user_id 없는 표는 아래 목록). PK는 첫 열이며 다를 때만 `PK(..)`로 적는다. UK = 유일, IX = 인덱스.
- `user_id` 열이 없는 표: `diary_revisions`, `textbook_web_pages`, `textbook_web_revisions`, `learning_event_meta`, `learning_event_backfill`, `routine_weekdays`, `routine_exceptions`.
- **물리 FK 없음**(2026-08-16 이후 규칙). `참조`는 `*_id` 이름으로 읽은 논리 참조이고, 소유권은 모든 조회의 `user_id` 조건으로 지킨다.
- 이름이 겹쳐 논리 참조를 적지 않은 열: `assignment_id`, `event_id`, `item_id`, `job_id`, `link_id`, `proposal_id`, `revision_id`, `suggestion_id` — 대상은 열 이름 접두나 05-database.md로 판단.
- 마이그레이션 도구 없음 — `docs/sql/YYYY-MM-DD-*.sql`을 손으로 적용한다. 출처 열의 파일이 그 표를 만든 마이그레이션이다.
- 쓰지 않지만 DB에 남은 표: `daily_states`, `context_items`, `legacy_execution_item_map`. 지운 표: `todos`, `schedule_blocks`, `plan_items`, `plan_item_events`, `plan_item_context_links`, `daily_plans`(`2026-08-23-remove-legacy-tables.sql`).
- 대조: `scripts/check-context-layer.sh`가 이 파일의 `### 표` 집합과 docs/sql CREATE 집합을 비교한다.

## 사용자·일기

### users
계정(이메일·bcrypt 해시·닉네임·역할·상태) · `※ DDL 없음, UserMapper.xml`
- 열: user_id, email, password_hash, nickname, role, status, last_login_at

### diaries
일기 본문(기분·공개범위·날씨·즐겨찾기, soft delete) · `※ DDL 없음, DiaryMapper.xml`
- 열: diary_id, written_date, title, content, mood, visibility, weather, is_favorite, is_deleted

### diary_revisions
일기 수정 이력 스냅샷(복구용) · `※ DDL 없음, DiaryRevisionMapper.xml`
- 열: revision_id, diary_id, written_date, title, content, mood, visibility, weather, is_favorite, edited_at
- 참조: diary_id→diaries

## 실행

### execution_items
실행 항목 — Todo·ScheduleBlock 통합 원본. 배치 3종(UNSCHEDULED/DATE_ONLY/TIME_FIXED) · `※ DDL 없음, 05 §7`
- 열: execution_item_id, source_execution_item_id, title, description, placement_type(UNSCHEDULED|DATE_ONLY|TIME_FIXED), scheduled_date, scheduled_start_at, scheduled_end_at, expected_minutes, status(PLANNED|PARTIAL|HOLD|DONE|CANCELLED), priority(MUST|SHOULD|OPTIONAL), order_index, routine_id, origin_type, modified_after_creation, version, is_deleted, topic_id, course_id, plan_version_id, planning_start_date, planning_end_date, deadline_at
- 참조: routine_id→routines, topic_id→course_topics, course_id→courses, plan_version_id→plan_versions

### execution_records
실행 결과 기록(COMPLETED 100%/PARTIAL 1~99%+남은 조각/NOT_DONE 0%) · `※ DDL 없음, 05 §8`
- 열: execution_record_id, execution_item_id, outcome(COMPLETED|PARTIAL|NOT_DONE), started_at, ended_at, actual_minutes, completion_percent, note, remaining_execution_item_id, recorded_at, blocker_kind, support_level, stuck_step
- 참조: execution_item_id→execution_items

### execution_item_events
실행 항목 상태 변화 이벤트(완료·부분·미수행은 record로 남김) · `※ DDL 없음, 05 §9`
- 열: execution_item_event_id, execution_item_id, related_execution_item_id, event_type, reason, before_state, after_state, before_version, after_version, actor_type(USER|AI|SYSTEM|MIGRATION|UNKNOWN), occurred_at
- 참조: execution_item_id→execution_items

## 기간 계획

### plan_versions
확정 계획 판. 항목 정의는 items_snapshot, (plan_key, version) 유일 · `2026-08-24-plan-versions`
- 열: plan_version_id, plan_key, version, start_date, end_date, title, goal_summary, items_snapshot, source_proposal_id, confirmed_at, intensity, target_minutes, strategy_json, provenance_json
- UK(plan_key,version) UK(source_proposal_id) IX(user_id,start_date,end_date)

### plan_item_details
항목 「자세히」 안내. evidence_version이 바뀌면 이전 안내는 STALE, user_text 보존 · `2026-09-13-material-auto-analysis`
- 열: detail_id, proposal_item_id, evidence_version, steps_json, user_text, status(CURRENT|STALE), model
- UK(proposal_item_id,evidence_version) IX(user_id) · 참조: proposal_item_id→ai_proposal_items

### plan_item_start_helps
항목 시작 도움 요청·응답 · `2026-09-29-learning-flow`
- 열: help_id, proposal_item_id, execution_item_id, evidence_version, request_kind(WHERE_TO_START|TOO_BIG|OTHER), request_text, help_json, model
- IX(user_id,proposal_item_id,created_at) IX(user_id,execution_item_id,created_at) · 참조: proposal_item_id→ai_proposal_items, execution_item_id→execution_items

### plan_generation_traces
초안 생성 회차의 모델 호출별 입력 전문·실린 id·서버 커밋(30일 보존) · `2026-09-19-plan-generation-traces`
- 열: trace_id, generation_id, call_kind, call_order, api_commit, model_name, estimated_tokens, system_prompt, user_prompt, prompt_sha256, text_purged_at, shown_json, material_ids
- IX(user_id,generation_id,call_order) IX(created_at)

## AI 상담·제안

### ai_conversations
상담 대화(scope·status, 프로젝트 연결) · `2026-08-05-ai-consultation-conversations`
- 열: conversation_id, scope(PLAN|TODAY|EXECUTION|CONTEXT|MIXED|MATERIAL|LEARNING|PLANNING), status(ACTIVE|ARCHIVED), summary, active_request_message_id, active_request_started_at, course_id
- IX(user_id,updated_at) · 참조: course_id→courses

### ai_messages
대화 메시지(USER/ASSISTANT, (user_id, idempotency_key) 유일로 중복 호출 차단) · `2026-08-05-ai-consultation-conversations`
- 열: message_id, conversation_id, role(USER|ASSISTANT), content, response_type, reply_to_message_id, idempotency_key, status(PROCESSING|COMPLETED|FAILED), consult_json
- UK(reply_to_message_id) UK(user_id,idempotency_key) IX(conversation_id,message_id) · 참조: conversation_id→ai_conversations

### ai_message_photos
메시지↔상담 사진 연결 · `2026-10-05-consult-photo`
- 열: message_id, material_id, conversation_id
- PK(message_id,material_id) IX(conversation_id,message_id) IX(material_id) · 참조: conversation_id→ai_conversations

### consult_photo_uploads
상담 사진 업로드(upload_key 멱등) · `2026-10-05-consult-photo`
- 열: upload_id, conversation_id, upload_key, status(PROCESSING|READ|UNREADABLE|FAILED), storage_path, material_id, result_json, file_removed_at
- UK(user_id,upload_key) IX(status,file_removed_at,updated_at) · 참조: conversation_id→ai_conversations, material_id→course_materials

### consult_turn_steps
상담 턴 단계 기록(모델·도구 호출·상한·오류). 진단용, 복원에 안 씀 · `2026-10-26-change-bundles`
- 열: step_id, conversation_id, request_message_id, seq, kind, name, args_json, result_code, result_text, latency_ms, input_tokens, output_tokens
- IX(request_message_id,seq) IX(conversation_id) · 참조: conversation_id→ai_conversations

### ai_conversation_drafts
진행 중 요청 상태(draft). 한 발화가 여럿 생성 가능, draft_group_id로 묶음 · `2026-09-08-ai-conversation-drafts`
- 열: draft_id, conversation_id, draft_group_id, draft_type(CREATE_ROUTINE|CREATE_SCHEDULE|CREATE_PERIOD_PLAN), label, status(OPEN|PROMOTED|CANCELLED), fields, missing_required, ask_count, promoted_suggestion_ids
- IX(conversation_id,status) · 참조: conversation_id→ai_conversations

### ai_plan_briefs
상담에서 합의한 계획 요청(대화당 1) · `2026-09-17-plan-briefs`
- 열: brief_id, conversation_id, version, status(OPEN|CLOSED), items, last_proposal_id
- UK(conversation_id) IX(user_id,updated_at) · 참조: conversation_id→ai_conversations

### ai_proposals
승인 전 AI 제안(초안). 계획 초안 상태·근거·검토 상태 열 포함 · `※ DDL 없음, 05 §10`
- 열: proposal_id, conversation_id, source_message_id, target_scope(PLAN|TODAY|EXECUTION|CONTEXT|MIXED), status(PROPOSED|APPLIED|MODIFIED_APPLIED|DISMISSED|EXPIRED), expires_at, responded_at, unavailable_windows, plan_intensity, plan_target_minutes, plan_start_date, plan_end_date, plan_strategy_json, plan_provenance_json, review_state_json
- UK(source_message_id) · 참조: conversation_id→ai_conversations

### ai_proposal_items
제안 항목(원본/편집 payload, 생성된 대상) · `※ DDL 없음, 05 §10`
- 열: proposal_item_id, proposal_id, item_type(PLAN_ITEM|EXECUTION_ITEM|CONTEXT_ITEM), original_payload, edited_payload, target_item_id, base_version, status(PROPOSED|APPLIED|MODIFIED_APPLIED|DISMISSED|EXPIRED), created_item_type, created_item_id, responded_at, evidence_json

### ai_proposal_schedule_previews
제안의 Timefold 배치 미리보기(제안당 1) · `2026-08-06-scheduling-preview`
- 열: schedule_preview_id, proposal_id, horizon_start, horizon_end, availability_windows, user_overrides, placed_items, unplaced_items, computed_at
- UK(proposal_id) IX(user_id)

### ai_schedule_suggestions
상담이 낸 일정 후보(약속·반복) · `2026-09-01-ai-schedule-suggestions`
- 열: suggestion_id, conversation_id, source_message_id, kind(COMMITMENT|ROUTINE), proposed_payload, status(PROPOSED|APPLIED|DISMISSED), resolved_at
- IX(conversation_id,status) IX(source_message_id) · 참조: conversation_id→ai_conversations

### ai_context_change_suggestions
상담이 낸 상황(user_contexts) 변경 후보 · `2026-08-07-context-change-suggestions`
- 열: suggestion_id, conversation_id, source_message_id, operation(ADD|SUPERSEDE|MARK_STALE|ARCHIVE|CONFIRM), target_context_id, proposed_content, reason, status(PROPOSED|APPLIED|DISMISSED), resulting_context_id, resolved_at
- IX(conversation_id,status) IX(source_message_id) · 참조: conversation_id→ai_conversations

### ai_change_bundles
상담 도구 단계가 모은 기존 데이터 변경 묶음(revision·멱등 적용 키·basis 지문) · `2026-10-26-change-bundles`
- 열: bundle_id, conversation_id, source_message_id, status(PROPOSED|APPLIED|DISMISSED), revision, range_start, range_end, basis_fingerprint, apply_idempotency_key, apply_request_hash, apply_result_json, resolved_at
- UK(user_id,apply_idempotency_key) IX(conversation_id,status) IX(source_message_id) · 참조: conversation_id→ai_conversations

### ai_change_bundle_items
변경 묶음의 op(seq 순서=적용 순서, target_key로 충돌 방지) · `2026-10-26-change-bundles`
- 열: item_id, bundle_id, seq, op_type, target_key, op_json, summary, depends_on, origin, selected, status(PROPOSED|APPLIED|DISMISSED), check_code, check_message, result_json
- UK(bundle_id,seq) IX(bundle_id) · 참조: bundle_id→ai_change_bundles

### ai_usage_logs
모델 호출 사용량·결과 로그 · `2026-08-05-ai-consultation-conversations`
- 열: usage_log_id, conversation_id, request_message_id, feature, model, input_tokens, cached_tokens, output_tokens, result_status(SUCCESS|FAILED|CANCELLED|TIMEOUT), error_code, provider_request_id, request_id, workflow_id, agent_run_id, latency_ms
- IX(user_id,created_at) · 참조: conversation_id→ai_conversations

## 기억

### user_contexts
AI가 이해한 내 상황(진도·시험 범위·도움 요청 등). 출처·근거 유형, 낡음(STALE) 관리 · `2026-08-07-context-change-suggestions`
- 열: context_id, content, status(ACTIVE|STALE|SUPERSEDED|ARCHIVED|WITHDRAWN), source_type(USER_CONFIRMED|AI_SUGGESTION_APPROVED|CONSULT_AUTO|USER_EDITED|SELF_CHECK), source_message_id, supersedes_context_id, confirmed_at, evidence_type(STATED|SELF_REPORT|OBSERVED|INFERRED), course_id, topic_id, section_id, scope_start, scope_end, self_level, withdrawn_at, topic_photo_id, fact_kind, fact_label, help_level, said_at, content_key
- IX(user_id,status) · 참조: course_id→courses, topic_id→course_topics, section_id→material_sections

## 프로젝트·학습

### courses
프로젝트(과목). 교재 칸·교재 판·웹 검색 허용·수업 확인 묻기 · `2026-08-09-learning-agents`
- 열: course_id, title, textbook_title, textbook_author, textbook_publisher, textbook_isbn, status(ACTIVE|ARCHIVED), group_label, textbook_edition, textbook_info_source, textbook_info_material_id, textbook_info_updated_at, textbook_version, textbook_web_revision_id, textbook_toc_material_id, textbook_toc_file_hash, textbook_toc_book_key, textbook_web_lookup_enabled, class_prompt_enabled
- IX(user_id)

### course_topics
학습 항목 트리(목차 출처·원본 순번·상태) · `2026-08-09-learning-agents`
- 열: topic_id, course_id, parent_topic_id, title, order_index, source_type(SOURCE|AI_DERIVED), source_material_id, source_locator, status(ACTIVE|ARCHIVED), user_mark, source_web_revision_id, source_textbook_key, source_toc_seq, toc_key_kind, toc_key_hash, toc_key_line, toc_key_state
- IX(course_id) IX(parent_topic_id) · 참조: course_id→courses

### course_notes
프로젝트 노트(수업 정보·평가 방식 — 학습 항목 트리와 분리) · `2026-08-09-course-notes`
- 열: note_id, course_id, category(COURSE_INFO|ASSESSMENT), label, detail, source_material_id
- IX(course_id) · 참조: course_id→courses

### course_assignments
과제(마감 종류·확인 상태, dedupe_key 유일) · `2026-09-13-material-auto-analysis`
- 열: assignment_id, course_id, material_id, section_id, topic_id, title, source_quote, confirm_status(CANDIDATE|CONFIRMED|NOT_ASSIGNMENT|LATER|DUPLICATE), due_kind(UNKNOWN|NONE|DATE|DATETIME), due_date, due_at, due_source, due_quote, due_estimate_json, completed_at, title_edited, due_edited, duplicate_of_assignment_id, dedupe_key, version
- UK(user_id,dedupe_key) IX(user_id,course_id,confirm_status) IX(user_id,confirm_status,due_date) · 참조: course_id→courses, material_id→course_materials, section_id→material_sections, topic_id→course_topics

### course_scope_exclusions
사용자가 정정한 범위 제외 · `2026-09-29-learning-flow`
- 열: exclusion_id, course_id, topic_id, label, status(ACTIVE|REMOVED), removed_at
- UK(course_id,topic_id,label) IX(user_id,course_id,status) · 참조: course_id→courses, topic_id→course_topics

### topic_progress
학습 항목별 진행 상태(user·topic 유일) · `2026-08-09-learning-agents`
- 열: topic_progress_id, topic_id, status(NOT_STARTED|IN_PROGRESS|LEARNED), last_studied_at, last_reviewed_at, review_count
- UK(user_id,topic_id) · 참조: topic_id→course_topics

### topic_learning_events
topic_progress가 바뀐 이유의 이력(복습 추천 근거) · `2026-08-09-learning-agents`
- 열: event_id, topic_id, execution_item_id, event_type(STATUS_CHANGED|EXECUTION_COMPLETED|AI_SUGGESTED_CHANGE), from_status, to_status, note
- IX(topic_id,created_at) · 참조: topic_id→course_topics, execution_item_id→execution_items

### topic_class_progress
실제 수업 진행 정정(주차·순서). 쓰는 길은 적용된 정리안 하나뿐 · `2026-09-29-learning-flow`
- 열: progress_id, course_id, topic_id, class_seq, week_no, source(USER), note
- UK(course_id,topic_id) IX(user_id,course_id) · 참조: course_id→courses, topic_id→course_topics

### topic_material_links
학습 항목↔자료 구간 연결(origin·status) · `2026-09-13-material-auto-analysis`
- 열: link_id, course_id, topic_id, material_id, section_id, role, locator, origin(BACKFILL_SOURCE|PROPOSAL_APPLIED|USER|PHOTO_GUESS), status(ACTIVE|REMOVED)
- UK(topic_id,material_id,section_id) IX(material_id,status) IX(course_id,status) IX(user_id) · 참조: course_id→courses, topic_id→course_topics, material_id→course_materials, section_id→material_sections

### topic_change_proposals
자료 정리 변경안(2026-09-21 이후 이력 전용) · `2026-09-13-material-auto-analysis`
- 열: proposal_id, course_id, material_id, job_id, file_hash, base_tree_version, status(PROPOSED|APPLIED|DISMISSED|CONFLICT|STALE|EMPTY), proposed_guard, summary_json, ops_json, applied_ops_json, model, resolved_at
- UK(material_id,course_id,proposed_guard) IX(course_id,status) IX(user_id,status) · 참조: course_id→courses, material_id→course_materials

### study_recommendations
Learning Agent의 "다음 학습" 후보(WHAT, 시간 배치 없음) · `2026-08-09-learning-agents`
- 열: recommendation_id, course_id, topic_id, activity_type(NEW_LEARNING|REVIEW|CONTINUE|SKIP), recommended_minutes_min, recommended_minutes_ideal, priority(MUST|SHOULD|OPTIONAL), reason, status(SUGGESTED|SENT_TO_PLANNING|PLANNED|DISMISSED), source_conversation_id, source_message_id, proposal_id
- IX(user_id,created_at) · 참조: course_id→courses, topic_id→course_topics

## 자료

### course_materials
자료 원본(파일·추출 상태·형식). 프로젝트와는 material_links로 연결 · `2026-08-09-learning-agents`
- 열: material_id, course_id, material_type(SYLLABUS|TEXTBOOK_TOC|PROFESSOR_SLIDE|OTHER), original_filename, stored_filename, content_type, size_bytes, extraction_status(PENDING|SUCCESS|FAILED_NO_TEXT|FAILED), extracted_text, extraction_error, storage_path, file_hash, status(ACTIVE|DELETED), document_title, document_title_read, origin, source_conversation_id, original_expires_at, original_removed_at, original_removed_reason, original_purged_at
- IX(course_id) IX(user_id) IX(user_id,file_hash) · 참조: course_id→courses

### material_links
자료↔프로젝트 연결과 그 프로젝트에서의 자료 역할 · `2026-08-16-material-store`
- 열: link_id, material_id, course_id, material_type(SYLLABUS|TEXTBOOK_TOC|PROFESSOR_SLIDE|OTHER), linked_at
- UK(material_id,course_id) IX(course_id) IX(user_id) · 참조: material_id→course_materials, course_id→courses

### material_text_units
추출 본문의 물리 단위(페이지·슬라이드)별 텍스트, file_hash별 행 · `2026-09-13-material-auto-analysis`
- 열: unit_id, material_id, file_hash, unit_index, unit_type(PDF_PAGE|PPTX_SLIDE|NOTEBOOK_CELL|TEXT_BLOCK|IMAGE_PAGE), unit_no, char_count, text
- UK(material_id,file_hash,unit_index) IX(user_id) · 참조: material_id→course_materials

### material_sections
자료 구간(단위 범위·인쇄 쪽수), 파일 해시·분석판 안에서 dedupe · `2026-09-13-material-auto-analysis`
- 열: section_id, material_id, file_hash, analysis_version, chunk_index, unit_type, unit_start, unit_end, printed_page_start, printed_page_end, section_label, display_title, roles_json, task_text, excerpt, assignment_cue, assignment_quote, date_candidates_json, dedupe_key, status(ACTIVE|SUPERSEDED)
- UK(material_id,file_hash,analysis_version,dedupe_key) IX(material_id,status,unit_start) IX(user_id) · 참조: material_id→course_materials

### material_textbook_extracts
원문에서 규칙으로 읽은 교재 단서(서지·목차) · `2026-09-29-learning-flow`
- 열: extract_id, material_id, file_hash, extractor_version, book_json, toc_json, toc_entry_count, clue_json
- UK(material_id,file_hash,extractor_version) IX(user_id,material_id) · 참조: material_id→course_materials

### course_material_analyses
프로젝트 맥락 자료 분석 초안·적용(열린 초안 1개 guard) · `2026-08-09-learning-agents`
- 열: analysis_id, course_id, material_id, status(DRAFT|APPLIED|DISMISSED|FAILED), analysis_json, edited_json, model, failure_reason, applied_at, draft_guard
- UK(user_id,course_id,material_id,draft_guard) IX(course_id) IX(material_id) · 참조: course_id→courses, material_id→course_materials

### material_analysis_jobs
자동 분석 작업 큐(종류·상태·시도) · `2026-09-13-material-auto-analysis`
- 열: job_id, material_id, course_id, job_kind(CONTENT|LINK), file_hash, analysis_version, priority, status(QUEUED|RUNNING|DONE|PARTIAL|FAILED|UNAVAILABLE|PAUSED|CANCELLED), attempt, max_attempts, next_run_at, lease_owner, lease_until, lease_token, total_chunks, completed_chunks, checkpoint_json, error_code, error_message, result_ref_id, finished_at
- UK(material_id,course_id,job_kind,file_hash,analysis_version) IX(status,next_run_at,priority,job_id) IX(user_id,status) IX(material_id,status) · 참조: material_id→course_materials, course_id→courses

### material_analysis_controls
사용자별 자동 분석 일시정지 · `2026-09-13-material-auto-analysis`
- 열: user_id, paused, paused_at

### material_analysis_timings
작업별 소요 시간 표본(추정용) · `2026-09-21-project-tidy`
- 열: timing_id, job_id, job_kind, extension, size_bytes, char_count, unit_count, chunk_count, queue_wait_ms, extract_ms, model_ms, persist_ms, total_ms, outcome(DONE|PARTIAL|FAILED|CANCELLED)
- UK(job_id) IX(job_kind,outcome,created_at) IX(user_id,created_at)

### material_analysis_batches
업로드·분석 묶음(ZIP 가져오기와 연결 가능) · `2026-09-21-project-tidy`
- 열: batch_id, course_id, status(STAGED|UPLOADING|ANALYZING|FINISHED|ABANDONED), item_count, est_min_seconds, est_max_seconds, est_basis, started_at, finished_at, zip_import_id
- IX(user_id,status,batch_id) IX(course_id,status) · 참조: course_id→courses

### material_analysis_batch_items
묶음의 자료별 항목(업로드 상태) · `2026-09-21-project-tidy`
- 열: item_id, batch_id, position, filename, size_bytes, extension, material_id, upload_state(STAGED|UPLOADING|UPLOADED|UPLOAD_FAILED|UNSUPPORTED|NO_TEXT|ABANDONED), est_min_seconds, est_max_seconds, est_basis, message, zip_entry_id, source_path
- UK(batch_id,position) UK(batch_id,material_id) IX(user_id,batch_id) IX(material_id) · 참조: batch_id→material_analysis_batches, material_id→course_materials

### material_zip_imports
ZIP 가져오기 · `2026-09-16-material-file-types`
- 열: import_id, course_id, material_type, original_filename, storage_path, size_bytes, file_hash, status(PREPARING|READY|IMPORTING|COMPLETED|PARTIAL|FAILED|CANCELLED|EXPIRED), entry_count, selectable_count, error_code, error_message, expires_at, finished_at
- IX(user_id,import_id) IX(expires_at) · 참조: course_id→courses

### material_zip_import_entries
ZIP 안 항목(확정·재시도 단위) · `2026-09-16-material-file-types`
- 열: entry_id, import_id, entry_index, entry_path, display_name, extension, size_bytes, supported, skip_reason, status(PENDING|QUEUED|IMPORTING|DONE|FAILED|UNSUPPORTED), material_id, error_code, error_message, attempt
- UK(import_id,entry_index) UK(material_id) IX(status,entry_id) IX(user_id) · 참조: import_id→material_zip_imports, material_id→course_materials

### material_week_assignments
자료↔주차 확정 배치(source: 추천/사용자) · `2026-09-22-material-week-assignments`
- 열: assignment_id, course_id, material_id, placement, week_no, source(SUGGESTION|USER)
- UK(course_id,material_id,placement,week_no) IX(user_id,course_id) · 참조: course_id→courses, material_id→course_materials

## 프로젝트 정리

### project_tidy_jobs
프로젝트 단위 정리 작업(열린 작업 1개 guard) · `2026-09-21-project-tidy`
- 열: job_id, course_id, generation, status(QUEUED|RUNNING|DONE|FAILED|UNAVAILABLE|CANCELLED), open_guard, base_tree_version, previous_proposal_id, input_snapshot_json, attempt, max_attempts, next_run_at, lease_owner, lease_until, lease_token, result_proposal_id, error_code, error_message, finished_at, user_request_json
- UK(course_id,open_guard) IX(status,next_run_at,job_id) IX(course_id,status,job_id) IX(user_id,status) · 참조: course_id→courses

### project_tidy_proposals
정리안(트리 변경 제안, 열린 정리안 1개 guard) · `2026-09-21-project-tidy`
- 열: proposal_id, course_id, job_id, generation, revision, base_tree_version, status(PROPOSED|APPLIED|DISMISSED|SUPERSEDED|EMPTY), open_guard, summary_json, ops_json, scope_json, applied_result_json, previous_proposal_id, superseded_by_proposal_id, model, resolved_at, origin, user_request_json, toc_basis_json, toc_key_version
- UK(course_id,open_guard) IX(course_id,status,proposal_id) IX(user_id,status) · 참조: course_id→courses

### project_tidy_proposal_materials
정리안이 근거로 쓴 자료 · `2026-09-21-project-tidy`
- 열: id, proposal_id, material_id, file_hash, analysis_version, section_count, reviewed_count, included, exclude_reason
- UK(proposal_id,material_id) IX(user_id) · 참조: material_id→course_materials

### project_tidy_edits
정리안 검토 편집 초안(edit_revision 대조, 트리는 안 바뀜) · `2026-09-21-project-tidy`
- 열: proposal_id, edits_json, edit_revision
- IX(user_id)

## 교재

### textbook_lookups
교재 웹 검색 요청·결과(basis 다르면 SUPERSEDED) · `2026-10-04-textbook-web-toc`
- 열: lookup_id, course_id, basis_key, query_key, query_json, clue_origin(CURRENT_TEXTBOOK|SYLLABUS|ISBN|USER_LINK), clue_material_id, clue_file_hash, clue_extractor_version, textbook_version, force_refresh, status, open_guard, attempt, max_attempts, next_run_at, lease_owner, lease_until, lease_token, result_json, chosen_revision_id, reused_from_lookup_id, auto_tidy_state(NONE|PENDING|ENQUEUED|WAITING_OPEN_PROPOSAL|DONE), auto_tidy_next_at, tidy_job_id, error_code, error_message, finished_at
- UK(course_id,open_guard) IX(user_id,course_id,lookup_id) IX(user_id,query_key,status) IX(status,next_run_at) IX(auto_tidy_state,auto_tidy_next_at) IX(auto_tidy_state,auto_tidy_next_at) · 참조: course_id→courses

### textbook_lookup_usage
사용자별 일일 교재 검색 사용량 · `2026-10-04-textbook-web-toc`
- 열: usage_date, calls
- PK(user_id,usage_date)

### textbook_web_pages
검색한 웹 페이지(캐시 범위·URL 해시 유일) · `2026-10-04-textbook-web-toc`
- 열: page_id, cache_scope_key, url_hash, url, site, latest_revision_id, last_fetched_at, last_fetch_status
- UK(cache_scope_key,url_hash)

### textbook_web_revisions
웹 페이지 내용 판(목차 파싱 결과·커버리지) · `2026-10-04-textbook-web-toc`
- 열: revision_id, page_id, content_hash, parser_version, fetched_at, http_status, isbn13, title, authors, author_notes, publisher, published_date, edition, toc_raw, toc_json, toc_entry_count, toc_coverage(PAGE_FULL|PARTIAL|UNKNOWN|NONE), toc_version, toc_raw_hash, toc_raw_truncated
- UK(page_id,content_hash,parser_version) IX(isbn13) · 참조: page_id→textbook_web_pages

### textbook_refs
바뀌지 않는 교재 식별자 · `2026-10-06-learning-events`
- 열: book_ref_id
- IX(user_id)

### textbook_ref_aliases
교재 ref의 BookKey 별칭(ISBN 키·제목 키) · `2026-10-06-learning-events`
- 열: book_key_hash, book_key, book_ref_id
- PK(user_id,book_key_hash) IX(book_ref_id) · 참조: book_ref_id→textbook_refs

## 학습 이벤트

### learning_events
학습 이벤트 로그(actor·verb·object·evidence). origin 판마다 출력 번호로 유일 · `2026-10-06-learning-events`
- 열: event_id, course_id, origin_kind, origin_id, origin_revision, output_no, actor(ME|CLASS|SYSTEM), verb, object_kind(SECTION|ITEM|TOC_ENTRY|PAGE|WEEK|SESSION|MATERIAL|COURSE), object_ref, object_from, object_to, topic_id, payload, evidence(STATED|INPUT|LOGGED|RULE|APPROX|INFERRED), confidence, claim_at, claim_seq, occurred_at, recorded_at
- UK(user_id,origin_kind,origin_id,origin_revision,output_no) IX(user_id,course_id,object_kind,object_ref) IX(user_id,course_id,recorded_at) IX(user_id,course_id,event_id) IX(user_id,course_id,verb) · 참조: course_id→courses, topic_id→course_topics

### learning_event_origins
이벤트 원천 행(origin)과 살아 있는 판(current_revision) · `2026-10-06-learning-events`
- 열: origin_kind, origin_id, course_id, current_revision
- PK(user_id,origin_kind,origin_id) IX(user_id,course_id) · 참조: course_id→courses

### learning_event_meta
일회 작업·전환 시각 기록(key-value) · `2026-10-06-learning-events`
- 열: meta_key, meta_value

### learning_resolution_links
막힘(기억 행)을 닫은 해결 기억 행 · `2026-10-06-learning-events`
- 열: difficulty_context_id, resolver_context_id
- IX(resolver_context_id)

### learning_event_backfill
원본 종류별 백필 진행(last_id·lease) · `2026-10-06-learning-event-backfill`
- 열: source, last_id, done, report, lease_owner, lease_until

### class_sessions
반복 일정(수업)의 회차별 진행 확인 · `2026-10-06-class-sessions`
- 열: session_id, course_id, routine_id, source_date, start_at, end_at
- UK(routine_id,source_date) IX(user_id,course_id,source_date) · 참조: course_id→courses, routine_id→routines

## 일정

### routines
반복 일정(soft delete) · `2026-08-31-routines`
- 열: routine_id, course_id, title, location, start_time, end_time, effective_from, effective_until, is_deleted
- IX(user_id,is_deleted) · 참조: course_id→courses

### routine_weekdays
반복 요일 · `2026-08-31-routines`
- 열: routine_id, day_of_week(MONDAY|TUESDAY|WEDNESDAY|THURSDAY|FRIDAY|SATURDAY|SUNDAY)
- PK(routine_id,day_of_week)

### routine_exceptions
반복 일정의 날짜별 예외(SKIP 건너뛰기·MOVED 옮기기) · `2026-08-31-routines`
- 열: routine_exception_id, routine_id, exception_date, type(SKIP|MOVED), moved_date, moved_start_time, moved_end_time, moved_location, note
- UK(routine_id,exception_date) IX(routine_id,moved_date) · 참조: routine_id→routines

### one_off_commitments
1회성 약속(직접 입력·AI 후보 승인), 파생 이동시간 행 · `2026-09-01-one-off-commitments`
- 열: commitment_id, title, start_at, end_at, location_text, source_type(MANUAL|AI_SUGGESTION_APPROVED), version, is_deleted, derived_from_commitment_id, derived_relation
- IX(user_id,start_at,end_at,is_deleted)
