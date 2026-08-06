# 03. Planning System

## 1. 목적

계획 시스템의 목적은 거대한 계획표를 만드는 것이 아니라, 대화에서 나온 의도를 실제로 관리 가능한 실행 조각으로 바꾸고 계획과 실제의 차이를 다음 계획에 반영하는 것이다.

```text
자연어 상담
→ PlanItem 초안
→ ExecutionItem 후보
→ 사용자 수정·적용
→ 실제 수행 ExecutionRecord
→ 조정 ExecutionItemEvent
→ 다음 상담
```

## 2. 핵심 모델

| 모델 | 한 문장 정의 |
| --- | --- |
| PlanItem | 앞으로 하려는 의도·범위·기간 |
| ExecutionItem | 실제로 날짜·시간에 배치하고 조정하는 실행 조각 |
| ExecutionRecord | 실제로 수행한 결과 |
| ExecutionItemEvent | 실행 조각을 이동·축소·보류·분할한 사건 |
| DailyState | 특정 날짜의 컨디션과 운영 설정 |
| ContextItem | 다음 상담과 계획에 재사용할 장기 기억 |
| AIProposal / Item | 적용 전 AI 변경안과 항목별 사용자 반응 |

## 3. PlanItem에서 실행까지

PlanItem 하나는 여러 ExecutionItem으로 나뉠 수 있다.

```text
PlanItem: 포트폴리오 배포 준비
  → ExecutionItem: README 초안 작성
  → ExecutionItem: 환경변수 정리
  → ExecutionItem: 배포 오류 1개 수정
```

모든 실행 조각이 PlanItem을 가져야 하는 것은 아니다. 임시 일정, 휴식, 갑자기 생긴 할 일은 상위 계획 없이 만들 수 있다. 반대로 PlanItem은 실행 조각이 만들어지기 전 초안 상태로 존재할 수 있다.

## 4. ExecutionItem 배치

배치 유형은 다음 세 가지다.

```text
UNSCHEDULED   아직 날짜가 없는 실행 후보
DATE_ONLY     날짜만 정해진 실행 조각
TIME_FIXED    시작·종료 시각이 정해진 실행 조각
```

`TIME_FIXED`는 시작·종료 시각을 모두 가지며 종료가 시작보다 늦어야 한다. `scheduled_date`는 실제 달력 배치 날짜이며 `scheduled_start_at`의 날짜와 일치한다. 자정을 넘긴 “운영일”이 필요하면 별도 정책으로 다루고 같은 필드에 두 의미를 섞지 않는다.

우선순위는 다음 값을 사용한다.

```text
MUST / SHOULD / OPTIONAL
```

상태는 현재 상태만 표현한다.

```text
PLANNED / PARTIAL / HOLD / DONE / CANCELLED
```

이동·축소·분할은 상태가 아니라 Event다.

## 5. 실제 수행 기록

ExecutionRecord는 계획과 별도로 실제 사실을 저장한다.

```text
COMPLETED   completionPercent = 100
PARTIAL     completionPercent = 1~99
NOT_DONE    completionPercent = 0
```

실제 시작·종료 시각이나 실제 분량을 모르면 `NULL`로 둔다. 없는 값을 추정해 채우지 않는다.

### 부분 수행

부분 수행은 다음 세 작업을 하나의 트랜잭션으로 처리한다.

1. 원래 ExecutionItem에 `PARTIAL` ExecutionRecord를 만든다.
2. 남은 분량을 새 ExecutionItem으로 만든다.
3. 두 항목의 연결과 전후 상태를 `SPLIT` Event로 남긴다.

원래 항목을 단순 DONE 처리하거나 제목만 바꿔 남은 분량을 잃어버리면 안 된다.

## 6. 조정 액션

| 액션 | 공식 데이터 변화 |
| --- | --- |
| 이동 | 날짜·시간 변경 + `MOVED` Event |
| 축소 | 제목·분량·예상 시간 변경 + `REDUCED` Event |
| 보류 | 상태 `HOLD` + `HOLD` Event |
| 재개 | 상태 `PLANNED` + `RESUMED` Event |
| 취소 | 상태 `CANCELLED` + `CANCELLED` Event |
| 삭제 | soft delete + `DELETED` Event |
| 부분 수행 | `PARTIAL` Record + 남은 Item + `SPLIT` Event |
| 완료 | `COMPLETED` Record + 상태 `DONE` |

필드 수정과 Event/Record 저장은 같은 트랜잭션으로 처리한다. 중간 실패 시 일부만 남지 않아야 한다.

## 7. DailyState

기존 `daily_plans`의 실제 책임은 상위 계획이 아니라 하루 상태에 가깝다. 목표 구조에서는 `daily_states`로 분리한다.

```text
stateDate
viewMode        TIME_TABLE / CHECKLIST
viewModeSource  USER_DEFAULT / USER_SELECTED / SYSTEM_SUGGESTED / AI_RECOMMENDED
intensity       LIGHT / NORMAL / FOCUSED
conditionNote
focusNote
memo
```

ExecutionItem이 DailyState를 부모로 가지면 안 된다. 오늘 화면은 `state_date = 오늘` DailyState와 `scheduled_date = 오늘` ExecutionItem을 각각 조회해 합성한다.

## 8. 장기 컨텍스트

ContextItem 유형은 다음을 기본으로 한다.

```text
GOAL / DECISION / CONSTRAINT / PREFERENCE
CONCERN / OBSERVATION / INSIGHT / UNKNOWN
```

확인 상태는 다음 세 단계다.

```text
UNCONFIRMED / AI_INFERRED / USER_CONFIRMED
```

AI 추정은 사용자 확정과 동일하지 않다. 다음 계획에는 관련성이 높은 활성 컨텍스트만 넣고, 출처와 확인 상태를 함께 전달한다.

컨텍스트가 바뀌었을 때 과거 내용을 조용히 덮어쓰지 않는다. `validFrom/validTo`, `supersedesContextItemId`, `withdrawnAt`으로 유효 기간과 교체·철회를 표현한다. 수명주기는 `PENDING / ACTIVE / SUPERSEDED / WITHDRAWN / ARCHIVED`를 사용한다.

## 9. AI 제안 생애주기

AI 제안 상태는 다음과 같다.

```text
PROPOSED
APPLIED
MODIFIED_APPLIED
DISMISSED
EXPIRED
```

제안 항목은 원본 payload, 사용자가 수정한 payload, 대상 항목 ID, 제안 생성 당시 `baseVersion`, 적용 후 생성된 항목을 저장한다.

적용 절차:

1. AI가 변경안을 AIProposal/Item으로 만든다.
2. 프런트가 리스트 또는 시간표에 ghost/diff로 표시한다.
3. 사용자가 항목별로 수정·적용·무시한다.
4. 서버가 대상 ExecutionItem/PlanItem의 현재 version과 baseVersion을 비교한다.
5. 같을 때만 공식 데이터와 Event를 한 트랜잭션으로 갱신한다.
6. 다르면 `409 Conflict`로 적용을 거부하고 새 제안을 요청한다.

외부 ChatGPT 대화 텍스트나 파일을 가져오는 경우에도 같은 흐름을 사용한다. 가져온 원문을 공식 PlanItem/ContextItem으로 바로 저장하지 않고 AIProposal로 변환해 검토한다.

## 10. 실시간 화면 변경 원칙

대화 중 화면은 즉시 반응하되 두 상태를 구분한다.

```text
공식 상태     DB에 저장된 현재 계획과 실행
제안 상태     아직 적용되지 않은 신규/변경/삭제 미리보기
```

초기 구현 순서는 다음과 같다.

1. 리스트: TodayView와 ExecutionView에서 신규·변경·삭제 상태 표시
2. 시간표: 같은 diff 계약을 TimetableView 블록에 표시
3. 캘린더: 리스트와 시간표 검증 후 별도 구현

## 11. 오늘과 실행의 경계

- 오늘은 `scheduled_date = 오늘`인 ExecutionItem과 당일 조정에 집중한다.
- 실행은 여러 날짜의 ExecutionItem을 목록·주간 시간표로 운영한다.
- 같은 항목을 두 화면이 각각 소유하지 않는다.
- 오늘에서 바꾼 항목은 실행에도, 실행에서 바꾼 오늘 항목은 오늘에도 즉시 반영된다.

## 12. 7일 범위 일정 후보 배치 (2026-08-06)

사용자가 여러 날에 걸친 계획("이번 주 안에 강의 4개")을 요청하면, PROPOSAL 항목은 특정 날짜에
묶이지 않은 `placementType=UNSCHEDULED` 후보로 만들어진다. 실제 날짜와 시각은 GPT가 정하지
않는다 — 서버가 가용시간을 추정하고 Timefold Solver가 최대 7일 범위 안에서 계산한다.

```text
PROPOSAL(UNSCHEDULED 후보 1~5개 + 대화에서 파악한 사용 불가 시간)
→ AvailabilityEstimateService: 기존 TIME_FIXED 일정 + 사용 불가 시간 + 기본 추정 시간대
→ SchedulingSolverService(Timefold): 겹치지 않게, 마감 안 넘기게, 우선순위 순으로 배치
→ 배치 결과 미리보기(placedItems/unplacedItems) 저장
→ 사용자가 예외 수정 또는 항목 고정 → Timefold만 재계산(OpenAI 재호출 없음)
→ 사용자가 최종 확인한 날짜·시간으로 승인 → ExecutionItem 생성(TIME_FIXED/DATE_ONLY/UNSCHEDULED)
```

계산 모델(`scheduling.domain/solver/service` 패키지)은 ExecutionItem·AiProposalItem과 완전히
분리된 별도 모델이다. Timefold 어노테이션은 DB 엔티티에 붙지 않는다. Solver는 상시 실행되지
않고, 요청마다(주로 "다시 배치" 클릭) 짧게(기본 5초 상한) 한 번 돌고 버려진다.

미배치는 오류가 아니라 "배치 가능한 시간이 부족함" 같은 사유로 남는다. 사용자는 미배치 항목을
그대로 `UNSCHEDULED`로 남기거나, 날짜·시간을 직접 고정하거나, 제외할 수 있다. 이번 범위는 새
PROPOSAL 항목의 최초 배치만 다룬다 — 기존 ExecutionItem을 옮기거나 줄이는 PATCH 재계획은 다루지
않는다(`07-ideas.md`).

## 13. 레거시 전환

현재 구현의 `todos + schedule_blocks`는 목표 구조에서 `execution_items`로 통합한다. `plan_item_events`는 `execution_item_events`로, 완료 사실은 `execution_records`로 옮긴다. `daily_plans`는 `daily_states`로 책임을 바꾼다.

전환 중 두 구조에 동시에 저장하지 않는다. 신규 테이블 생성 → 데이터 복사·검증 → MyBatis 전환 → 레거시 쓰기 중단 순서를 따른다.
