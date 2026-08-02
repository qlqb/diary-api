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

## 10. ai_proposals / ai_proposal_items

`ai_proposals`는 한 번의 AI 변경안 묶음을, `ai_proposal_items`는 사용자가 개별 적용할 수 있는 항목을 저장한다.

```text
ai_proposals
- proposal_id, user_id, conversation_id
- target_scope           PLAN / TODAY / EXECUTION / CONTEXT / MIXED
- status
- created_at, expires_at, responded_at

ai_proposal_items
- proposal_item_id, proposal_id, user_id
- item_type              PLAN_ITEM / EXECUTION_ITEM / CONTEXT_ITEM
- original_payload JSON
- edited_payload JSON nullable
- target_item_id nullable
- base_version nullable
- status
- created_item_type / created_item_id nullable
- created_at, responded_at
```

상태:

```text
PROPOSED / APPLIED / MODIFIED_APPLIED / DISMISSED / EXPIRED
```

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

## 16. 보안

- 실제 이메일·일기·비밀번호 해시가 포함된 덤프를 Git에 올리지 않는다.
- 저장소에는 스키마와 가짜 테스트 데이터만 둔다.
- 평문 또는 BCrypt가 아닌 비밀번호 데이터는 삭제하거나 재설정한다.
- 모든 FK 연결과 조회는 같은 userId 범위인지 확인한다.
