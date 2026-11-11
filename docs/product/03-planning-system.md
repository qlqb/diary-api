# 03. 실행 조각 · 제안 적용 계약

> 현재 기준: 2026-10-25(축소 개정). 2026-08판에 있던 PlanItem·DailyState·ContextItem·레거시 전환 절은 지웠다 —
> PlanItem은 2026-08-23에 테이블째 지웠고 기간 계획은 `plan_versions`(11번)가 맡는다. DailyState(`daily_states`)는 만들지 않았다.
> 장기 기억은 `user_contexts`(13번 §12.1, 18번)다. 옛 판은 git 이력에 있다.

이 문서는 실행층의 계약 — 실행 조각의 배치·상태·기록·조정 사건과 AI 제안을 적용하는 규칙 — 의 기준이다.
기간 계획의 네 층(Project / PlanVersion / ExecutionItem / ExecutionRecord) 중 아래 두 층이 여기다.

## 1. 모델

| 모델 | 한 문장 정의 | 표 |
| --- | --- | --- |
| ExecutionItem | 실제로 날짜·시간에 배치하고 조정하는 실행 조각. 실행의 유일한 원본 | `execution_items` |
| ExecutionRecord | 실제로 수행한 결과 | `execution_records` |
| ExecutionItemEvent | 실행 조각을 이동·축소·보류·분할한 사건 | `execution_item_events` |
| AiProposal / Item | 적용 전 AI 변경안과 항목별 사용자 반응 | `ai_proposals`, `ai_proposal_items` |

계획 확정으로 생긴 조각은 그 계획 판(`plan_versions`)을 가리키고, 부분 수행으로 남은 조각은 `source_execution_item_id`로 원래 조각을 가리킨다(11번 §0).
계획 없이 만든 조각(임시 일정, 갑자기 생긴 일)도 있다.

## 2. 배치

```text
UNSCHEDULED   아직 날짜가 없는 실행 후보
DATE_ONLY     날짜만 정해진 실행 조각
TIME_FIXED    시작·종료 시각이 정해진 실행 조각
```

`TIME_FIXED`는 시작·종료 시각을 모두 가지며 종료가 시작보다 늦어야 한다. `scheduled_date`는 실제 달력 배치 날짜이며 `scheduled_start_at`의 날짜와
일치한다. 자정을 넘긴 "운영일"은 같은 필드에 섞지 않는다(07번 operationalDate, 미구현).

우선순위 `MUST / SHOULD / OPTIONAL`. 상태는 현재 상태만 표현한다: `PLANNED / PARTIAL / HOLD / DONE / CANCELLED`. 이동·축소·분할은 상태가 아니라 Event다.

## 3. 실제 수행 기록

```text
COMPLETED   completionPercent = 100
PARTIAL     completionPercent = 1~99
NOT_DONE    completionPercent = 0   (enum은 있지만 지금 이 결과를 남기는 API는 없다)
```

실제 시작·종료 시각이나 실제 분량을 모르면 `NULL`로 둔다. 없는 값을 추정해 채우지 않는다. 완료·부분 수행에 막힌 이유 `blockerKind`
(`TIME|CONCEPT|ENERGY|OTHER`)를 함께 남길 수 있다. 기록은 나중에 고칠 수 있다(`PATCH /records/{id}`).

**부분 수행**은 한 트랜잭션이다: ① 원래 조각에 `PARTIAL` 기록 ② 남은 분량을 새 조각으로 ③ 두 조각의 연결과 전후 상태를 `SPLIT` Event로.
원래 조각을 단순 DONE 처리하거나 제목만 바꿔 남은 분량을 잃으면 안 된다.

## 4. 조정 액션

모두 `/api/execution-items/{id}/…`, 요청에 `version`(낙관적 잠금)을 싣는다.

| 액션 | 경로 | 공식 데이터 변화 |
| --- | --- | --- |
| 완료 | `POST /complete` | `COMPLETED` 기록 + 상태 `DONE` |
| 부분 수행 | `POST /partial` | `PARTIAL` 기록 + 남은 조각 + `SPLIT` |
| 재열기 | `POST /reopen` | 상태 `PLANNED` + `REOPENED`(완료 기록은 남는다) |
| 이동 | `POST /move` | 날짜·시간 변경 + `MOVED` |
| 미배정 | `PATCH /unschedule` | `UNSCHEDULED`로 + `MOVED` |
| 축소 | `POST /reduce` | 제목·분량·예상 시간 변경 + `REDUCED` |
| 보류 / 재개 | `POST /hold`, `POST /resume` | 상태 `HOLD` / `PLANNED` + `HOLD` / `RESUMED` |
| 삭제 / 복원 | `DELETE /{id}?version=`, `POST /restore` | soft delete + `DELETED` / `RESTORED` |

`CANCELLED`·`PRIORITY_CHANGED` Event 종류는 있지만 사용자가 직접 부르는 취소·우선순위 API는 없다.
필드 수정과 Event/Record 저장은 같은 트랜잭션이다. 중간 실패 시 일부만 남지 않는다. 완료·부분 수행은 학습 이벤트도 같은 트랜잭션에서 남긴다(20번 §7).

## 5. AI 제안 적용

상태: `PROPOSED / APPLIED / MODIFIED_APPLIED / DISMISSED / EXPIRED`. 목적(purpose)은 `PERIOD_PLAN`(기간 계획 초안)과 `EXECUTION_CHANGE`(기존 조각 변경).
항목 연산은 `CREATE / REDUCE / MOVE / DROP`이다.

제안 항목은 원본 payload, 사용자가 수정한 payload, 대상 항목 ID, 제안 당시 `baseVersion`, 적용 후 생성된 항목을 저장한다.

1. AI가 변경안을 AiProposal/Item으로 만든다.
2. 화면이 미리보기(목록·주간 배치)로 보인다.
3. 사용자가 항목별로 수정·적용·무시한다.
4. 서버가 대상 조각의 현재 version과 baseVersion을 비교해, 같을 때만 공식 데이터와 Event를 한 트랜잭션으로 갱신한다.
5. 다르면 `409 Conflict`로 거부하고 새 제안을 요청한다.

기간 계획 초안의 확정·재계획(같은 `plan_key`의 version+1)은 11번 §5-1-4가 기준이다.

## 6. 일정 후보 배치 (Timefold, 2026-08-06)

여러 날에 걸친 후보는 `UNSCHEDULED`로 만들고, 실제 날짜·시각은 언어모델이 정하지 않는다. 서버가 가용시간을 추정하고 Timefold가 배치한다.

```text
후보 + 대화에서 파악한 사용 불가 시간
→ AvailabilityEstimateService: 기존 TIME_FIXED 일정 + 루틴·약속 + 사용 불가 시간 + 하루 후보 창
  (scheduling.availability.day-start 09:00 ~ day-end 24:00, 요일 구분 없음 — 2026-10-18)
→ SchedulingSolverService(Timefold): 겹치지 않게, 마감 안 넘기게, 우선순위 순으로
→ 미리보기(placedItems/unplacedItems) → 예외 수정·고정 시 Timefold만 재계산(모델 재호출 없음)
→ 사용자가 확인한 날짜·시간으로 승인 → ExecutionItem 생성
```

계산 모델(`scheduling.domain/solver/service`)은 ExecutionItem·AiProposalItem과 분리된 별도 모델이다. Timefold 어노테이션은 DB 엔티티에 붙지 않는다.
Solver는 요청마다 짧게(기본 5초 상한) 한 번 돌고 버려진다. 미배치는 오류가 아니라 사유("배치 가능한 시간이 부족함")로 남는다.
기간 계획의 배치(말한 시간대 안에서만)는 11번 §5-1-5 4)와 21번 §7.

## 7. 오늘과 일정의 경계

- 오늘은 `scheduled_date = 오늘`인 조각과 당일 조정에 집중한다(08번).
- 일정은 여러 날짜의 조각을 주간 배치로 운영한다.
- 같은 항목을 두 화면이 각각 소유하지 않는다. 한쪽에서 바꾼 것은 다른 쪽에 즉시 반영된다.
