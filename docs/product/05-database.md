# 05. Database

## 1. 문서 범위

이 문서는 목표 실행 모델과 레거시 전환 기준을 정의한다. 현재 코드에 존재하는 `todos`, `schedule_blocks`, `daily_plans`, `plan_item_events`를 그대로 최종 구조로 간주하지 않는다.

## 2. 목표 테이블

```text
users
daily_states
plan_items
context_items
plan_item_context_links
execution_items
execution_item_context_links
execution_records
execution_item_events
ai_proposals
ai_proposal_items
```

마이그레이션 중에는 다음 보조 테이블을 사용할 수 있다.

```text
legacy_execution_item_map
migration_data_adjustments
```

## 3. 핵심 관계

```text
PlanItem 1 ── N ExecutionItem
ExecutionItem 1 ── 0..N ExecutionRecord
ExecutionItem 1 ── N ExecutionItemEvent
PlanItem N ── M ContextItem
ExecutionItem N ── M ContextItem
AIProposal 1 ── N AIProposalItem
```

DailyState는 ExecutionItem의 부모가 아니다. 두 모델은 날짜로 조회해 화면에서 합성한다.

## 4. daily_states

하루의 컨디션과 운영 설정을 저장한다.

```text
daily_state_id
user_id
state_date
view_mode              TIME_TABLE / CHECKLIST
view_mode_source       USER_DEFAULT / USER_SELECTED / SYSTEM_SUGGESTED / AI_RECOMMENDED
intensity              LIGHT / NORMAL / FOCUSED
condition_note
focus_note
memo
created_at
updated_at
```

무결성:

- `UNIQUE(user_id, state_date)`
- ExecutionItem FK를 두지 않는다.

## 5. plan_items

상위 계획과 계획 항목을 별도 계층으로 과도하게 쪼개기 전에, 앞으로 하려는 의도·범위·기간을 한 단위로 저장한다.

```text
plan_item_id
user_id
title
description
start_date
end_date
status                  DRAFT / ACTIVE / HOLD / DONE / CANCELLED
priority                MUST / SHOULD / OPTIONAL
origin_type
modified_after_creation
version
is_deleted
created_at
updated_at
```

기간은 시작일과 종료일이 모두 있을 때 `start_date <= end_date`를 만족해야 한다.

## 6. context_items

```text
context_item_id
user_id
context_type            GOAL / DECISION / CONSTRAINT / PREFERENCE
                        CONCERN / OBSERVATION / INSIGHT / UNKNOWN
content
source_type             USER_INPUT / AI_CONVERSATION / EXECUTION_DERIVED
source_ref nullable
verification_status     UNCONFIRMED / AI_INFERRED / USER_CONFIRMED
lifecycle_status        PENDING / ACTIVE / SUPERSEDED / WITHDRAWN / ARCHIVED
valid_from nullable
valid_to nullable
supersedes_context_item_id nullable
withdrawn_at nullable
version
created_at
updated_at
```

`plan_item_context_links`와 `execution_item_context_links`는 다음 연결 유형을 사용한다.

```text
RATIONALE / CONSTRAINT / SOURCE / RELATED
```

이 연결이 있어야 “왜 이 실행 조각이 생겼는가”를 화면에서 추적할 수 있다.

`valid_from <= valid_to`를 보장한다. `supersedes_context_item_id`는 같은 사용자의 ContextItem만 가리킬 수 있고 자기 자신을 가리키면 안 된다. `WITHDRAWN` 항목은 `withdrawn_at`을 가져야 하며 다음 계획 생성에서 제외한다.

2026-08-03 마이그레이션 SQL 초안이 `DECISION/INSIGHT`, 유효 기간, 교체·철회 필드를 아직 포함하지 않는다면 Context API 구현 전에 DDL을 이 목표 계약으로 보완한다. 제품의 핵심인 결정·제약 기억을 `OBSERVATION` 하나로 뭉개지 않는다.

## 7. execution_items

Todo와 ScheduleBlock의 최종 통합 원본이다.

```text
execution_item_id
user_id
plan_item_id nullable
source_execution_item_id nullable
title
description
placement_type          UNSCHEDULED / DATE_ONLY / TIME_FIXED
scheduled_date nullable
scheduled_start_at nullable
scheduled_end_at nullable
expected_minutes nullable
status                  PLANNED / PARTIAL / HOLD / DONE / CANCELLED
priority                MUST / SHOULD / OPTIONAL
order_index
routine_id nullable
origin_type
modified_after_creation
version
is_deleted
created_at
updated_at
```

배치 조건:

| placement_type | scheduled_date | start/end |
| --- | --- | --- |
| UNSCHEDULED | NULL | 둘 다 NULL |
| DATE_ONLY | NOT NULL | 둘 다 NULL |
| TIME_FIXED | NOT NULL | 둘 다 NOT NULL |

TIME_FIXED는 `scheduled_start_at < scheduled_end_at`이고 `DATE(scheduled_start_at) = scheduled_date`여야 한다.

`source_execution_item_id`는 부분 수행 후 남은 조각이나 분할된 항목의 출처를 추적한다. 자기 자신을 가리키는지는 MariaDB 10.4의 AUTO_INCREMENT/CHECK 제약 때문에 애플리케이션 검증과 사후 검증 쿼리로 보장한다.

## 8. execution_records

```text
execution_record_id
user_id
execution_item_id
outcome                 COMPLETED / PARTIAL / NOT_DONE
started_at nullable
ended_at nullable
actual_minutes nullable
completion_percent
note nullable
remaining_execution_item_id nullable
recorded_at
created_at
```

무결성:

- COMPLETED는 100%
- PARTIAL은 1~99%이고 remainingExecutionItemId 필수
- NOT_DONE은 0%
- actualMinutes를 모르면 NULL
- 시작·종료가 모두 있으면 시작 <= 종료

## 9. execution_item_events

```text
execution_item_event_id
user_id
execution_item_id
related_execution_item_id nullable
event_type
reason nullable
before_state JSON nullable
after_state JSON nullable
before_version nullable
after_version nullable
actor_type              USER / AI / SYSTEM / MIGRATION / UNKNOWN
occurred_at
created_at
```

Event 유형:

```text
CREATED / MOVED / REDUCED / SPLIT / HOLD / RESUMED
REOPENED / CANCELLED / PRIORITY_CHANGED / DELETED
```

완료·부분 수행·미수행은 Event만으로 표현하지 않고 ExecutionRecord로 남긴다.

## 10. ai_conversations / ai_messages / ai_proposals / ai_proposal_items

2026-08-05부터 AI 패널은 1회성 제안 생성기가 아니라 실제 다회차 상담이다. `ai_conversations`/`ai_messages`가
대화와 메시지를 저장하고, Proposal은 대화 중 사용자가 명시적으로 요청하거나 OFFER에 동의했을 때만 만들어지는
승인 전 초안으로 남는다. DDL은 `docs/sql/2026-08-05-ai-consultation-conversations.sql` 참고 (Flyway/Liquibase
미도입 — 기존 `docs/sql/*.sql` 날짜 파일 컨벤션을 따라 수동 적용한다).

```text
ai_conversations
- conversation_id, user_id
- scope                  PLAN / TODAY / EXECUTION / CONTEXT / MIXED
- status                 ACTIVE / ARCHIVED
- summary nullable       (오래된 메시지 요약. 이번 버전은 생성 로직 없이 컬럼만 둔다)
- created_at, updated_at

ai_messages
- message_id, conversation_id, user_id
- role                   USER / ASSISTANT
- content
- response_type nullable CHAT / OFFER / PROPOSAL (ASSISTANT만 값을 가진다)
- proposal_id nullable   (해당 턴이 PROPOSAL을 만들었으면 그 proposal_id)
- idempotency_key nullable  (user_id + idempotency_key UNIQUE — 동일 전송 중복 AI 호출 차단)
- status                 COMPLETED / FAILED
- created_at

ai_proposals
- proposal_id, user_id, conversation_id nullable, source_message_id nullable
- target_scope           PLAN / TODAY / EXECUTION / CONTEXT / MIXED
- status
- created_at, expires_at, responded_at

ai_proposal_items
- proposal_item_id, proposal_id, user_id
- item_type              PLAN_ITEM / EXECUTION_ITEM / CONTEXT_ITEM
- original_payload JSON  (title/description/expectedMinutes/priority/targetDate/
                          placementType/scheduledStartAt/scheduledEndAt)
- edited_payload JSON nullable
- target_item_id nullable
- base_version nullable
- status
- created_item_type / created_item_id nullable
- created_at, responded_at
```

`source_message_id`는 이 Proposal을 만들게 한 사용자 메시지를 가리킨다. AI 생성이나 파싱이 실패해도
사용자 메시지(`ai_messages`)는 항상 먼저 저장되어 있으므로 원문이 사라지지 않는다.

상태:

```text
ai_proposals / ai_proposal_items: PROPOSED / APPLIED / MODIFIED_APPLIED / DISMISSED / EXPIRED
```

## 10.5 ai_proposals.unavailable_windows / ai_proposal_schedule_previews

2026-08-06부터 7일 범위 일정 후보 배치(Timefold)를 지원한다. DDL은
`docs/sql/2026-08-06-scheduling-preview.sql` 참고.

`ai_proposals`에 `unavailable_windows JSON NULL` 컬럼을 추가했다. 이 제안을 만든 대화에서
사용자가 명시한 사용 불가 시간(예: "화요일 저녁은 알바")을 원본 그대로 보존한다 —
AI_INFERRED 성격이며 별도 확정 저장소(ContextItem)가 아직 없어 이 제안 범위 안에서만
재사용한다.

```text
ai_proposal_schedule_previews
- schedule_preview_id, proposal_id(UNIQUE), user_id
- horizon_start, horizon_end
- availability_windows JSON   (계산 당시 화면에 보여준 가용시간 요약: 출처·신뢰도·이유)
- user_overrides JSON nullable (사용자가 이 계산에 반영한 예외)
- placed_items JSON            (배치된 제안 항목)
- unplaced_items JSON          (배치하지 못한 제안 항목과 사유)
- computed_at, created_at, updated_at
```

Proposal 하나당 미리보기는 최신 계산 결과 하나만 보존한다(재계산은 upsert) — 이 표는 승인
전까지 공식 `execution_items`가 아니며, 새로고침 후 미리보기를 복원하는 용도로만 쓴다.

## 10.7 계획 생성 출처 (plan_provenance_json / evidence_json / provenance_json)

2026-09-10부터 계획 초안은 "AI에게 무엇을 줬는가"를 함께 남긴다. DDL은
`docs/sql/2026-09-10-plan-provenance.sql`.

```text
ai_proposals.plan_provenance_json   JSON nullable  이 초안을 만든 회차의 제공 정보 스냅샷
ai_proposal_items.evidence_json     JSON nullable  이 항목의 근거(refId·서버 계산·AI 추정)와 상태
plan_versions.provenance_json       JSON nullable  확정 시 위 스냅샷을 그대로 복사
```

세 컬럼 모두 **서버만 쓴다.** 모델 응답에는 항목별 인용 번호(refId)와 추정만 있고, 초안
수정·확정 요청 DTO에는 이 필드가 없다. 그래서 "출처가 있다"가 "모델이 그렇게 주장했다"가
되지 않는다.

`plan_provenance_json`의 모양(schema_version 2, 2026-09-11부터. 1판은 아래 두 필드가 없다):

```text
generationId, capturedAt, timezone, startDate, endDate, generator, modelName
providedSources[]   refId · sourceType · sourceId · representation · providedValue · promptLine
                    · parentSourceId(2판) · material(2판: materialId · filename · contentType · fileHash · locator)
serverCalculations[] calculationId · kind · providedToModel · inputRefIds · inputLineage · result
```

- `parentSourceId`와 `material`은 **모델에 준 값이 아니다.** 서버가 "그때 이 파일이었다"를 말하려고
  옆에 붙이는 메타데이터이고, 프롬프트에는 파일명·해시가 나가지 않는다. 모델이 받은 것은
  `providedValue`(제목·위치 문자열)뿐이다.
- `material.locator`는 "2주차" 같은 문자열이다. PDF 페이지가 아니며, 페이지로 해석하지 않는다.
- `material.fileHash`는 당시 SHA-256이다. 지금 파일과 다르면 "원본이 변경됨"이지, 과거 파일을
  복원할 수 있다는 뜻은 아니다(파일 버전 보관은 없다).
- 1판 JSON은 그대로 읽히고(`@JsonIgnoreProperties`, 없는 필드는 null), 화면은 1판 스냅샷의 자료를
  "현재 연결된 자료"로만 말한다. 1판을 2판으로 다시 쓰지 않는다.
- 배포 순서: 2판은 같은 컬럼 안의 필드 추가라 **추가 DDL이 없다.** 2026-09-10 DDL이 적용된 DB에
  API를 먼저 올리고 UI를 올린다. 옛 UI는 늘어난 필드를 무시하고, 새 UI는 없는 필드를 null로 본다.

- `providedSources`는 조회한 행이 아니라 **최종 프롬프트에 실제로 들어간 줄**이다. 요약·길이
  제한이 이미 적용된 값이라, 잘려서 안 나간 것은 여기에도 없다. 스냅샷은 프롬프트를 만들면서
  같은 자리에서 모은다(`ProvenanceCollector`) — DB를 다시 조회해 만들면 그 사이 바뀐 값이
  "그때 준 값"으로 저장된다.
- `serverCalculations`는 출처가 아니다. 가용시간 추정과 학습 예산은 서버가 만든 값이고, 원본
  일정과 같은 목록에 두면 사용자가 추정을 확정된 사실로 읽는다. `inputLineage`는 그 계산의
  입력이 전부 남았는지(COMPLETE) 일부인지(PARTIAL)를 구분한다 — 하루 기본 창(09~23시)과 현재
  시각은 가리킬 원본 행이 없어 가용시간 추정은 항상 PARTIAL이다.
- `evidence_json`을 `original_payload`에 넣지 않는다. 그쪽은 사용자가 고친 값이
  `edited_payload`로 다시 쓰이는 자리다. 컬럼을 나눠 두면 "클라이언트가 서버 소유 값을
  덮어쓸 수 있는가"를 검사 코드가 아니라 구조가 답한다.
- 적용된 실행 조각에서 회차로 되짚는 경로는 기존 `ai_proposal_items.created_item_id`다. 새
  연결 컬럼을 만들지 않는다.
- 과거 데이터는 셋 다 NULL이고 그대로 둔다. 지금 DB로 역추정해 채우지 않는다 — 그건 스냅샷이
  아니라 추측이고, 추측을 근거로 보여주는 것이 이 기능이 막으려는 바로 그것이다.

## 10.6 ai_conversation_drafts (진행 중 요청 상태)

2026-09-08부터 상담 대화는 "아직 만들지 않은 일정 요청"의 확정된 조각을 서버가 들고 있는다.
DDL은 `docs/sql/2026-09-08-ai-conversation-drafts.sql`.

```text
ai_conversation_drafts
- draft_id, user_id, conversation_id
- draft_group_id nullable   (같은 최초 발화에서 함께 생긴 draft 묶음. 첫 draft의 draft_id를 그대로 쓴다)
- draft_type                CREATE_ROUTINE / CREATE_SCHEDULE / CREATE_PERIOD_PLAN
- label                     사람이 읽는 짧은 라벨(모델이 대상 draft를 고를 때 프롬프트에 보인다)
- status                    OPEN / PROMOTED / CANCELLED
- fields JSON               {"field": {"value", "source": USER|INFERRED|DB|SYSTEM|DEFAULT, "reason", "confirmationRequired"}}
- missing_required JSON     서버가 슬롯 맵(DraftSlotRegistry)으로 계산한 필수 누락. 모델이 쓰지 않는다
- ask_count                 이 draft를 두고 서버가 질문한 횟수(그 턴의 질문 대상에만 +1)
- promoted_suggestion_ids JSON nullable   PROMOTED 시 만들어진 ai_schedule_suggestions.suggestion_id 목록
- created_at, updated_at
```

**일정 저장소가 아니다.** 오늘/일정 화면도 가용시간 계산도 이 표를 보지 않는다. 사용자에게 보이는
것은 draft가 아니라 그것으로 만든 proposal(`ai_schedule_suggestions`)이고, 원본(`routines`,
`one_off_commitments`)에는 카드를 승인했을 때만 들어간다. draft → proposal → 원본 순서를 건너뛰는
경로는 없다. `CREATE_PERIOD_PLAN`은 후보 대신 기존 기간 계획 OFFER(버튼)로 넘어가므로
`promoted_suggestion_ids`가 비어 있다.

**대화당 OPEN draft는 여러 개**이고 "활성 1개"는 없다. 매 턴 서버가 OPEN 전부를 프롬프트에 주고
모델이 대상을 고른다(routing). 그룹은 별도 테이블이 아니라 `draft_group_id` 하나다.

**version 컬럼을 두지 않는다.** draft 갱신은 대화 잠금(`ai_conversations.active_request_message_id`)
안에서만, 그것도 턴 마무리 트랜잭션(PROCESSING → COMPLETED 선점 뒤)에서만 일어난다. 대화 단위 동시
요청 차단과 `ai_messages.idempotency_key`가 이미 직렬화를 보장하므로 행 단위 낙관적 락이 설 자리가
없다. 세션 간 지속도 없다 — 대화가 ARCHIVED되면 OPEN은 전부 CANCELLED.

## 11. version과 동시 수정 방지

PlanItem, ContextItem, ExecutionItem은 version을 가진다. 기존 항목을 수정하는 AI 제안은 생성 당시 version을 baseVersion으로 저장한다.

```sql
UPDATE execution_items
SET title = ?,
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE execution_item_id = ?
  AND user_id = ?
  AND version = ?;
```

수정된 행이 0개면 오래된 제안이므로 `409 Conflict`로 처리한다.

## 12. 트랜잭션 경계

- 완료: COMPLETED Record 생성 + Item DONE
- 부분 수행: PARTIAL Record + 남은 Item + SPLIT Event
- 이동: Item 날짜·시간 변경 + MOVED Event
- 축소: Item 변경 + REDUCED Event
- 보류·재개·취소: 상태 변경 + 대응 Event
- 삭제: soft delete + DELETED Event
- AI 적용: version 확인 + 공식 데이터 변경 + 제안 상태 변경

각 묶음은 하나의 트랜잭션으로 처리한다.

## 13. 레거시 변환 규칙

| 기존 | 신규 |
| --- | --- |
| `todos.todo_date` | `execution_items.scheduled_date` |
| `schedule_blocks.block_date` | `scheduled_date` |
| Todo / TASK | `DATE_ONLY` |
| TIME_FIXED | `TIME_FIXED` |
| `start_time/end_time` | `scheduled_start_at/end_at` |
| Todo `HIGH/MEDIUM/LOW` | `MUST/SHOULD/OPTIONAL` |
| `plan_item_events` 조정 사건 | `execution_item_events` |
| DONE와 completed_at | `execution_records` 생성 근거 |
| `daily_plans` | `daily_states` |

기존 완료 데이터에 실제 시간이 없으면 다음처럼 남긴다.

```text
outcome = COMPLETED
actual_minutes = NULL
note = 기존 완료 데이터 이전: 실제 수행 시간 미확인
```

## 14. 이상 데이터 보정

마이그레이션은 레거시 원본을 수정하지 않고 신규 값만 보정하며 `migration_data_adjustments`에 근거를 남긴다.

- TIME_FIXED 날짜 불일치: start_time의 날짜를 scheduled_date로 사용
- 시간 누락·역전: DATE_ONLY로 낮추고 원래 block_date 유지
- 빈 제목: 원본 ID가 포함된 임시 제목
- 알 수 없는 상태·우선순위: PLANNED / SHOULD
- 대상이 완전히 사라진 이벤트: 건너뛰고 보정 내역 기록
- Todo와 ScheduleBlock이 겹침: 연결된 첫 블록에 합치고 추가 블록은 별도 실행 조각으로 보존

## 15. 안전한 전환 순서

1. 실제 DB 전체 백업
2. 애플리케이션 쓰기 중지
3. 신규 테이블 생성
4. 레거시 데이터 복사와 ID 매핑
5. 보정 내역과 건수·무결성 검증
6. MyBatis 조회·수정 코드를 신규 테이블 기준으로 변경
7. 레거시 쓰기 중단
8. 일정 기간 레거시 테이블 보관

신규 구조와 레거시 구조에 동시에 저장하지 않는다.

## 17. 자료 자동 분석 · 자료 구간 · 토픽 연결 · 과제 · 계획 상세 (2026-09-13)

`docs/sql/2026-09-13-material-auto-analysis.sql`. 전부 추가이며 재실행 가능하다. FK 없음(2026-08-16 이후 규칙), VARCHAR + CHECK.
설계 근거는 15-material-auto-analysis.md.

| 테이블 / 컬럼 | 뜻 | 유일성·상태 |
|---|---|---|
| `material_text_units` | 추출 단위(PDF 페이지·PPTX 슬라이드·텍스트 블록). `unit_no`는 사람이 보는 번호 | UNIQUE (material_id, file_hash, unit_index) |
| `material_analysis_jobs` | 백그라운드 작업. kind CONTENT(course_id=0) / LINK. `lease_owner/until/token`, `checkpoint_json`, `attempt/max_attempts/next_run_at` | UNIQUE (material_id, course_id, job_kind, file_hash, analysis_version). status QUEUED·RUNNING·DONE·PARTIAL·FAILED·UNAVAILABLE·PAUSED·CANCELLED |
| `material_sections` | 구간. `unit_start/end`(물리), `printed_page_*`(확인된 인쇄 쪽수만), `roles_json`, `task_text`, `excerpt`, `assignment_cue/quote`, `date_candidates_json` | UNIQUE (material_id, file_hash, analysis_version, dedupe_key). status ACTIVE·SUPERSEDED |
| `topic_material_links` | 토픽↔구간 N:M. `section_id`=0은 자료 전체. `origin` BACKFILL_SOURCE·PROPOSAL_APPLIED·USER | UNIQUE (topic_id, material_id, section_id). status ACTIVE·REMOVED |
| `topic_change_proposals` | 변경안. `base_tree_version`, `ops_json`, `summary_json`, `applied_ops_json` | 열린(PROPOSED) 변경안은 (material_id, course_id)당 하나(생성 컬럼 UNIQUE). PROPOSED·APPLIED·DISMISSED·CONFLICT·STALE·EMPTY |
| `course_assignments` | 과제. `confirm_status`, `due_kind`(UNKNOWN·NONE·DATE·DATETIME) + 모양 CHECK, `due_source`(SOURCE·ESTIMATED·USER), `due_estimate_json`, `completed_at`, `title_edited/due_edited`, `duplicate_of_assignment_id`, `version` | UNIQUE (user_id, dedupe_key) |
| `plan_item_details` | 「자세히」. `steps_json`, `user_text`, status CURRENT·STALE | UNIQUE (proposal_item_id, evidence_version) |
| `material_analysis_controls` | 사용자별 일시중지 | PK user_id |
| `courses.topic_tree_version` | 학습 구조 쓰기마다 +1. 변경안 적용의 낙관적 잠금 | |
| `course_topics.merged_into_topic_id`, `review_note` | 병합 행선지, 승계가 애매할 때의 안내 | |
| `course_materials.page_count` | 파일에서 센 물리 페이지/슬라이드 수 | |

백필: `topic_material_links`에 `course_topics.source_material_id`를 (topic, material, 0)으로 1행씩 넣는다(NOT EXISTS). 작업 표는
SQL로 채우지 않고 서버 폴러가 idempotent하게 등록한다. `course_materials.file_hash`가 NULL이던 옛 자료는 서버가 파일(없으면 원문
텍스트)의 SHA-256으로 채운다.

동시성 규칙: 작업 결과 저장은 `WHERE job_id=? AND lease_token=? AND status='RUNNING'`. 변경안 적용은 프로젝트 행 FOR UPDATE →
`UPDATE courses SET topic_tree_version=+1 WHERE topic_tree_version=?`(0행이면 409) → 활성 항목 FOR UPDATE. 과제 갱신은 `version`
대조(0행이면 409 VERSION_CONFLICT).

## 16. 보안

- 실제 이메일·일기·비밀번호 해시가 포함된 덤프를 Git에 올리지 않는다.
- 저장소에는 스키마와 가짜 테스트 데이터만 둔다.
- 평문 또는 BCrypt가 아닌 비밀번호 데이터는 삭제하거나 재설정한다.
- 모든 FK 연결과 조회는 같은 userId 범위인지 확인한다.
