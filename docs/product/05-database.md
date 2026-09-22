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

## 10.8 ai_plan_briefs (상담의 계획 합의) · plan_request_json 2판 (2026-09-17)

DDL은 `docs/sql/2026-09-17-plan-briefs.sql`(추가 전용·재실행 가능, FK 없음). 로컬 memo DB에 적용했고 배포 DB에는 없다.

```text
ai_plan_briefs
- brief_id, user_id
- conversation_id UNIQUE     대화당 한 행
- version                    항목이 바뀔 때마다 +1. 초안·요청은 (brief_id, version)으로 "그때 읽은 합의"를 가리킨다
- status                     OPEN / CLOSED
- items LONGTEXT JSON        [{id, kind, text, speaker USER|ASSISTANT, accepted, rejected, removed, scope THIS_DRAFT|PERIOD,
                               sourceMessageId, acceptedByMessageId, supersedes, topicId, courseId, executionItemId,
                               revision, history[], updatedAt}]
- last_proposal_id           이 합의로 가장 최근에 만든 초안
- created_at, updated_at
```

- 일정·계획 저장소가 아니다. 오늘/일정/계획 화면 어디도 이 표를 사실로 읽지 않는다. 사용자 승인으로 만들어지는 것은 여전히
  `ai_proposals → plan_versions / execution_items`뿐이다.
- 모델은 이 표를 쓰지 않는다. 턴의 구조화 응답(`planBrief` ops: ADD/ACCEPT/REJECT/UPDATE/REMOVE)을 서버가 assistant 메시지를
  저장한 뒤 `version` 대조(낙관적 잠금, 경합이면 다시 읽어 한 번 더)로 적용한다. 변경이 하나도 적용되지 않으면 쓰지 않는다.
- 지속 선호(다음 기간에도 유효)는 여기가 아니라 `user_contexts`다. 다른 대화로 이어지는 것은 DIFFICULTY·CAUSE와 PERIOD 항목뿐이다.

`ai_proposals.plan_request_json` 2판(같은 컬럼, 추가 DDL 없음. 1판은 그대로 읽힌다):

```text
version: 2, source, startDate, endDate, intensity, title, instruction, courseIds, excludeTopicIds, requestedMaterialIds,
requestedSectionIds, conversationId,
requestKey            화면이 만든 요청 키. 같은 키의 PROPOSED 초안이 있으면 모델을 부르지 않고 그것을 돌려준다(JSON_VALUE 조회)
briefId, briefVersion 그때 읽은 합의
previousProposalId    같은 조건으로 다시 만들었을 때의 옛 초안
evidence              근거 스냅샷: fingerprint · availabilityHash · materialsHash · assignmentsHash · progressHash · capturedAt ·
                      sections[{sectionId, materialId, fileHash, courseId, topicId, reason}] · topics[{topicId, courseId, reason}]
```

`evidence.fingerprint`가 다음 요청과 같으면 자료 선택 호출을 생략하고 `sections`를 다시 읽는다. 다르면 해시별로 무엇이
달라졌는지를 사람이 읽는 문장으로 초안에 남긴다(11번 §5-1-3). 조정 항목(기존 계획 항목의 REDUCE/MOVE/DROP)은 같은 제안의
`ai_proposal_items`에 `operation`·`target_item_id`로 들어간다 — 기존 조정 제안 적용 경로 그대로다.

### 10.8.1 합의 항목의 범위 필드 · 검토 상태 (2026-09-18 후속)

`ai_plan_briefs.items` 원소에 더한 것(같은 컬럼, DDL 없음. 옛 항목은 null):

```text
periodStart, periodEnd   PERIOD 합의의 실제 날짜. null이면 범위 미확인(과거 참고)
saidOn                   발언 날짜(사용자 시간대) — "이번 주"의 해석 기준
flowProposalId           THIS_DRAFT 합의가 묶인 초안 흐름(처음 초안 id). null이면 아직 초안을 만들지 않음
```

`ai_proposals.review_state_json`(DDL `docs/sql/2026-09-18-plan-review-state.sql`, 로컬 DB 적용):

```text
{version, title, excludedProposalItemIds[], editedItems[], answers{}, savedAt}
```

검토 상태다. 실행 데이터가 아니고 확정 요청이 같은 값을 싣는다. version은 저장마다 +1이고 늦은 저장은 0행(409). PROPOSED인
초안에만 쓴다. `plan_request_json`에는 `flowRootProposalId`(초안 흐름의 처음 초안 id)가 더해졌다.

재계획 확정은 `plan_versions`에 같은 `plan_key`의 다음 `version`을 넣는다(`uq_plan_versions_key_version`이 동시 확정을 막는다).
`execution_items.plan_version_id`는 여전히 "누가 만들었나"이고, 새 항목에만 새 판이 찍힌다. 계획 소속은 `plan_key` + 기간이다.

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
| `material_text_units` | 추출 단위. `unit_type` PDF_PAGE·PPTX_SLIDE·NOTEBOOK_CELL(노트북 셀, 1-based 원본 순서)·TEXT_BLOCK(쪽 구조가 없는 HWP·HWPX와 원본 없는 옛 자료). `unit_no`는 사람이 보는 번호 | UNIQUE (material_id, file_hash, unit_index) |
| `material_zip_imports` | 압축 가져오기 한 건. `status` PREPARING·READY·IMPORTING·COMPLETED·PARTIAL·FAILED·CANCELLED·EXPIRED. `storage_path`는 임시 보관한 원본(끝나거나 기한이 지나면 NULL) | 2026-09-16 |
| `material_zip_import_entries` | 압축 안 파일 하나. `entry_path`(표시용 원래 경로), `supported`/`skip_reason`, `status` PENDING·QUEUED·IMPORTING·DONE·FAILED·UNSUPPORTED, `material_id` | UNIQUE (import_id, entry_index), UNIQUE (material_id) — 한 항목이 자료를 둘 만들 수 없다 |
| `course_materials` 추가 열 | `extraction_warning`(읽었지만 일부를 못 읽음), `source_archive_name`·`source_entry_path`(압축에서 가져온 자료의 출처) | 2026-09-16 |
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

`ai_proposals.plan_request_json LONGTEXT NULL`(2026-09-15, `docs/sql/2026-09-15-plan-request-context.sql`, 추가 전용·재실행 가능):
기간 계획 초안을 만든 요청(version, source, startDate, endDate, intensity, title, instruction, courseIds, excludeTopicIds,
requestedMaterialIds, requestedSectionIds, conversationId). `POST /api/plans/proposals/{id}/redraft`가 읽는다. 이전 초안은 NULL이다.

동시성 규칙: 작업 결과 저장은 `WHERE job_id=? AND lease_token=? AND status='RUNNING'`. 변경안 적용은 프로젝트 행 FOR UPDATE →
`UPDATE courses SET topic_tree_version=+1 WHERE topic_tree_version=?`(0행이면 409) → 활성 항목 FOR UPDATE. 과제 갱신은 `version`
대조(0행이면 409 VERSION_CONFLICT).

## 18. 상담 이해·전달 기록·막힌 이유 (2026-09-19)

전부 추가형이고 재실행할 수 있다. **로컬 검증은 격리 DB `memo_consult`에만 적용했다 — `memo`와 배포 DB에는 적용하지 않았다.**
이 브랜치의 서버는 아래 두 파일이 적용된 DB에서만 뜬다(`user_contexts` INSERT가 새 컬럼을 쓴다).

| 파일 | 내용 |
|---|---|
| `docs/sql/2026-09-19-plan-generation-traces.sql` | `plan_generation_traces` 신설(호출별 입력 전문·줄로 실린 id·프로젝트별 집계·서버 커밋). FK 없음, 소유권은 `user_id` 조건. 자료 삭제 시 전문 NULL, 30일 보존 |
| `docs/sql/2026-09-19-consult-understanding.sql` | `user_contexts`에 `evidence_type`·`course_id`·`topic_id`·`section_id`·`scope_start/end`·`self_level`·`withdrawn_at`, status에 WITHDRAWN, source_type에 CONSULT_AUTO·USER_EDITED·SELF_CHECK. `ai_messages.consult_json`. `execution_records.blocker_kind` |

- backfill 없음: 기존 `user_contexts` 행은 기본값 STATED로 읽힌다(전부 사용자가 확정·승인한 것이다). 행 수는 바뀌지 않는다.
- 적용 전 dry-run 쿼리, 적용 후 확인 쿼리, 롤백 절차(새 값이 들어간 행이 있으면 먼저 확인 — 자동으로 지우지 않는다)는 각 SQL 파일 머리에 있다.
- 옛 서버 코드는 새 컬럼을 모른 채 동작한다(전부 NULL 허용이거나 기본값이 있다). 반대 방향(새 서버 + 옛 스키마)은 안 된다.

## 19. 업로드 묶음 · 소요 시간 표본 · 프로젝트 단위 정리안 (2026-09-21)

파일 `docs/sql/2026-09-21-project-tidy.sql`. 전부 추가형이고 재실행할 수 있다.
**한 곳만 예외로 기존 행을 건드린다**(아래 전환 참고).

| 표 | 내용 |
|---|---|
| `material_analysis_batches` / `material_analysis_batch_items` | 한 번의 [분석 시작]이 맡는 자료 묶음과 <고정된> 구성원. 진행률의 원본이 아니다 — 분석 진행은 `material_analysis_jobs`가 안다. 자리에 `material_id`는 업로드가 채운다 |
| `material_analysis_timings` | 지난 분석이 실제로 걸린 시간(큐 대기·추출·모델·저장을 따로). 예상 시간의 유일한 근거다. 원문·파일명은 남기지 않는다 |
| `project_tidy_jobs` | "이 프로젝트 자료 정리" 요청 하나. 프로젝트당 열린 작업은 <하나>이고 그것을 DB가 지킨다(`open_guard` 생성 컬럼 + UNIQUE). 선점은 자료 분석과 같은 임대 방식 |
| `project_tidy_proposals` | 프로젝트 하나의 정리안. 검토 중인 것은 프로젝트당 하나(`open_guard`). `ops_json`의 각 작업에 안정적인 `changeId` |
| `project_tidy_proposal_materials` | 그 정리안이 근거로 삼은 자료(해시·분석판·검토한 구간 수·제외 사유). 적용 직전 대조에 쓴다 |
| `project_tidy_edits` | 검토 중 사용자가 고친 것(제목·제외). **트리 변경이 아니다.** `edit_revision`으로 다른 탭의 저장을 덮지 않는다 |

**왜 묶음이 서버에 있어야 하는가**: 진행 상태의 원본이 브라우저에 있으면 탭을 닫는 순간 사라진다.
그리고 "이번에 올린 5개"를 서버가 알아야 분석 중에 2개를 더 올려도 5개짜리 진행률이 그대로 있는다 —
분모를 늘리면 80%가 30%로 떨어진다. 추가 업로드는 **새 묶음**이 된다.

**왜 정리안이 프로젝트 단위인가**: 자료마다 변경안을 만들면 같은 개념을 강의 슬라이드·교재·실습
안내가 다른 이름으로 다룰 때 각자 옳은 제안 셋이 나오고, 적용하면 중복 항목 셋이 남는다. 그리고
하나를 적용하는 순간 나머지가 옛 트리 기준이 되어 다시 분석되는 되풀이가 생겼다.

동시성 규칙:

- 정리 요청: `uq_project_tidy_jobs_open (course_id, open_guard)` — 중복 클릭·두 탭이 같은 작업을 두 번 만들지 못한다.
- 결과 저장: 작업 행 FOR UPDATE + `lease_token` 대조를 **같은 트랜잭션에서**. 그 사이 사용자가 버렸으면
  토큰이 달라져 아무것도 쓰지 않는다 — "버린 안이 되살아나지 않는다"의 근거.
- 적용: `UPDATE project_tidy_proposals SET status='APPLIED' WHERE status='PROPOSED' AND revision=?`가
  **트리를 고치기 전에** 1행이어야 한다. 두 탭이 동시에 눌러도 한쪽만 통과한다.
- 검증과 쓰기 사이: 근거 자료(`course_materials`)와 연결(`material_links`)을 `FOR UPDATE`로 잠근 채
  확인한다. 같은 순간의 자료 삭제·연결 해제는 기다렸다가 일어나고, 먼저 일어났으면 적용이 거절된다.

전환(기존 행을 건드리는 유일한 곳):

- `topic_change_proposals.status`에 `SUPERSEDED` 추가, `superseded_from` 컬럼 신설.
  열린(`PROPOSED`) 자료별 변경안을 `SUPERSEDED`로 옮기고 원래 상태를 `superseded_from`에 적는다.
  **지우지 않는다** — `ops_json`이 그대로라 이력에서 볼 수 있고, `superseded_from`을 `status`로
  되쓰면 원상 복구된다(롤백 절차는 SQL 파일 맨 아래).
- 아직 돌지 않은 `LINK` 작업을 `CANCELLED`로. 실행 중이던 것은 건드리지 않되, 서버의 생성·저장
  경로가 막혀 있어 늦게 끝나도 변경안을 만들지 못한다.
- 적용 시점 실측(2026-09-21, `memo`): 변경안 128행이 `PROPOSED` → `SUPERSEDED`, LINK 작업
  `QUEUED`/`PAUSED` 0행(이미 전부 끝나 있었다). 다른 표의 행 수는 바뀌지 않았다.

## 20. 프로젝트 정리 검토 후속 (2026-09-21)

파일 `docs/sql/2026-09-21-project-tidy-review.sql`. **NULL 허용 열 셋과 색인 하나만 더한다.** 기존 행은
바뀌지 않는다. `IF NOT EXISTS`라 다시 돌려도 된다(MariaDB 10.4에서 두 번 적용해 확인).

| 표.열 | 내용 |
|---|---|
| `material_analysis_batches.zip_import_id` | 압축 가져오기를 확정해 생긴 묶음이면 그 가져오기. 일반 업로드는 NULL |
| `material_analysis_batch_items.zip_entry_id` | 압축 안의 어느 항목인가. 가져오기 작업자가 이 값으로 자리를 찾는다 — 이름으로 찾으면 "과제1/run.sh"와 "과제2/run.sh"가 엇갈린다 |
| `material_analysis_batch_items.source_path` | 압축 안 경로. 화면 표시용 |
| `idx_material_analysis_batch_items_zip` | `(zip_entry_id)` |

**배포 순서**: 이 마이그레이션 → 서버 → 화면. 새 서버는 이 열을 읽고 쓰므로 적용하지 않은 DB에 새 서버를
띄우면 묶음 조회·생성이 `Unknown column`으로 실패한다(압축뿐 아니라 일반 업로드 묶음도). 옛 서버는 이 열을
모르므로 적용 뒤에도 그대로 돈다. **사용자 로컬 `memo`에는 적용하지 않았다** — 격리 테스트 DB에만 준비
스크립트(`scripts/test-db/prepare-memo-test.sh`)가 얹는다. 되돌리는 SQL은 파일 머리에 있다.

스키마 변경 없이 바뀐 저장 내용·규칙:

- `project_tidy_jobs.input_snapshot_json` **v2**: `{version:2, treeVersion, materialIds, materials[{materialId,
  filename, fileHash, analysisVersion, sectionIds[]}], excludedAtRequest[]}`. 실행할 때 지금 상태가 이것과 다르면
  모델을 부르지 않고 `error_code=STALE_INPUT`으로 끝낸다. v1(판 없음)은 `SNAPSHOT_OUTDATED`, 읽지 못하면
  `SNAPSHOT_INVALID`. 예전의 "읽지 못하면 최신 자료 전부" 대체 경로는 없앴다. 문법이 깨진 JSON은
  `chk_project_tidy_jobs_snapshot`(JSON_VALID)이 애초에 받지 않는다.
- `project_tidy_edits.edits_json` 값에 `carriedFrom{changeId, text, reason}`이 붙을 수 있다(확인 필요 승계).
  `needsConfirm`은 편집 저장으로 풀리지 않는다 — `resolveCarried` + 정리안 `revision`이 맞을 때만.
- `project_tidy_proposals.scope_json`에 `sectionsListed`, `modelCalls`, 자료별 `listedCount`. 옛 행은 0으로 읽힌다.
- 상태 전이(정리안): 적용·폐기·교체 모두 행 `FOR UPDATE` + `WHERE status='PROPOSED'`. 폐기는 예전에 잠금 없이
  읽고 조건 없이 썼다 — 적용이 끝난 행을 DISMISSED로 덮을 수 있었다. 교체는 `supersedeProposalIfOpen`.
- 상태 전이(묶음): `FINISHED ⇄ ANALYZING` 양쪽, 조건부(`transitionStatus`). 재시도·재추출 성공은
  `reopenFinishedContaining`으로 그 자료가 든 끝난 묶음을 연다. 올리다 만 자리는 30분 뒤 `ABANDONED`(압축 자리 제외).
- 잠금 순서: 정리 적용·폐기 = 정리안 → 작업 → 자료. 내용 분석의 마지막 단계(옛 구간 내리기) = 분석 작업 →
  자료(새로 추가). 적용은 분석 작업 행을 잡지 않으므로 순환하지 않는다.

## 16. 보안

- 실제 이메일·일기·비밀번호 해시가 포함된 덤프를 Git에 올리지 않는다.
- 저장소에는 스키마와 가짜 테스트 데이터만 둔다.
- 평문 또는 BCrypt가 아닌 비밀번호 데이터는 삭제하거나 재설정한다.
- 모든 FK 연결과 조회는 같은 userId 범위인지 확인한다.
