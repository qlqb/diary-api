# 03. Planning System

## 1. 계획 시스템의 목적

계획 시스템은 연간 목표에서 하루 계획으로 내려오는 하향식 구조가 아니다.

v2.1 기준 계획 시스템의 핵심은 DailyPlan, ScheduleBlock, Todo의 역할을 분리하고, 사용자가 오늘 실제로 할 수 있는 행동과 조정 이력을 남기는 것이다.

기본 흐름은 다음과 같다.

```text
오늘 기록
→ 반복 행동
→ 주간 패턴
→ 월간 집중 후보
→ 장기 방향 후보
```

월간/연간/LifeGoal은 장기 확장 방향으로만 남긴다. MVP에서는 테이블과 화면을 만들지 않는다.

## 2. 핵심 모델 역할

| 모델 | 역할 |
|---|---|
| DailyPlan | 오늘 하루의 보기 방식과 운영 상태 |
| ScheduleBlock | Today 화면에 올라온 오늘의 실행 카드 |
| Todo | 아직 날짜가 확정되지 않은 실행 후보 대기열 |

역할 구분은 다음과 같다.

```text
Todo = 나중에 해볼 실행 후보
ScheduleBlock = 오늘 해볼 실행 카드
DailyPlan = 오늘을 어떤 방식과 강도로 운영할지
```

1차-A 기준 사용자 화면의 "오늘 해볼 것"은 내부적으로 ScheduleBlock으로 저장한다. Todo는 아직 날짜가 확정되지 않은 실행 후보 대기열이며, 1차-A 사용자 화면에서는 노출하지 않는다.

모든 Todo가 ScheduleBlock으로 배치될 필요는 없다.

모든 ScheduleBlock이 Todo에서 올 필요도 없다. 사용자는 임시 일정, 휴식, 메모성 블록을 직접 만들 수 있다.

## 3. DailyPlan

DailyPlan은 날짜별 하루 운영 단위다.

필드는 다음 개념을 가진다.

```text
planDate
view_mode
view_mode_source
intensity
condition_note
main_goal
memo
conditionTags
```

`view_mode`는 다음 두 값을 사용한다.

```text
TIME_TABLE
CHECKLIST
```

`view_mode_source`는 다음 두 값을 사용한다.

```text
USER_DEFAULT
USER_SELECTED
```

`intensity`는 다음 세 값을 사용한다.

```text
LIGHT
NORMAL
FOCUSED
```

conditionTags는 자유 태그다. 사용자가 실제로 쓰는 표현을 수집하기 위한 값이므로 enum으로 미리 고정하지 않는다.

`viewMode`는 확정된 정답이 아니라 30일 자가사용으로 검증할 가설이다.

## 4. ScheduleBlock

ScheduleBlock은 하루 안에 배치되는 행동 단위다.

`blockDate`는 실제 날짜가 아니라 이 블록이 속한 하루를 의미한다. `startTime`/`endTime`은 실제 시각이다. 따라서 `blockDate`와 `startTime`/`endTime`의 날짜가 반드시 같을 필요는 없다.

예를 들어 `blockDate = 2026-07-08`, `startTime = 2026-07-09T01:30`, `endTime = 2026-07-09T02:00`인 블록은 실제 시각으로는 7월 9일 새벽에 실행되지만, 사용자의 하루 운영 기준으로는 7월 8일에 속할 수 있으므로 허용한다.

`block_type`은 다음 두 값을 사용한다.

```text
TIME_FIXED
TASK
```

`TIME_FIXED`는 `startTime`/`endTime`을 반드시 가진다. `TASK`는 시간 미지정 작업이므로 `startTime`/`endTime`을 가지지 않는다. `startTime`/`endTime` 중 하나만 있는 값은 허용하지 않으며, 둘 다 있는 경우 `endTime`은 `startTime`보다 이후여야 한다.

서비스와 DB에는 `DATE(start_time)=block_date` 같은 검증을 두지 않는다. `operationalDate` 계산은 추후 공통 유틸로 분리한다.

`priority`는 viewMode가 아니라 행동 속성이다.

```text
MUST
SHOULD
OPTIONAL
```

`orderIndex`는 CHECKLIST 모드에서 순서 중심 표시를 위해 사용한다.

`status`는 현재 상태만 표현한다.

```text
PLANNED
DONE
HOLD
CANCELLED
```

MOVED, REDUCED는 status가 아니다. 사용자가 조정한 사건이므로 plan_item_events에 event로 기록한다.

`HOLD`는 사용자가 "지금은 하지 않겠다"고 결론 낸 상태다. 따라서 아직 결론을 내리지 않은 상태인 pending과 구분한다.

1차-A에서는 hold 액션으로 ScheduleBlock의 상태를 `HOLD`로 바꾸고 `HOLD` 이벤트를 저장하는 것까지만 범위로 둔다. HOLD 항목은 나중에 별도 보류함 또는 다시 계획하기 흐름에서 다룬다. HOLD를 다시 계획하는 흐름은 추후 `RESUMED` 이벤트로 확장할 수 있다.

보류함 화면, 보류 해제 API, 보류 재검토 알림, 보류 사유 입력은 1차-A에서 구현하지 않는다.

### Pending

pending은 기준 운영일보다 이전 날짜에 속했지만, 아직 사용자가 결론을 내리지 않은 `PLANNED` ScheduleBlock이다. pending은 실패가 아니라 아직 정리되지 않은 이전 계획 항목이다.

`HOLD`, `DONE`, `CANCELLED`, 삭제된 항목은 pending이 아니다. 특히 HOLD 항목은 이미 보류로 결론을 낸 항목이므로 pending 카드에 반복 노출하지 않는다.

1차-A에서는 ScheduleBlock만 pending 대상으로 삼는다. 미배치 Todo의 pending 처리는 1차-B Todo 액션 확장에서 다룬다.

pending 판단은 `block_date` 기준으로 한다. `start_time`/`end_time`의 실제 시각 기준으로 pending을 판단하지 않고, `DATE(start_time)=block_date` 같은 조건도 사용하지 않는다.

pending 판단과 알림 정책은 분리한다. 기본 UX 방향은 사용자를 즉시 압박하지 않고 다음 날 아침에 이전 운영일의 pending을 정리하게 돕는 것이다. 실제 알림 기능과 설정은 `07-ideas.md`에 아이디어로 두며, 푸시 알림 구현은 MVP 제외다.

## 5. Todo

Todo는 아직 날짜가 확정되지 않은 실행 후보 대기열이다.

Todo는 독립적으로 존재할 수 있고, 필요하면 ScheduleBlock에 연결될 수 있다.

예시는 다음과 같다.

```text
Todo: 자료구조 연결 리스트 문제 3개 풀기
ScheduleBlock: 20:00-21:00 자료구조 문제 풀기
```

Todo는 후보이고, ScheduleBlock은 오늘의 실행 카드다.

## 6. v2 개념 정리

v2에서 사용하던 일부 계획 모드는 v2.1에서 다음처럼 정리한다.

| 기존 개념 | v2.1 정리 |
|---|---|
| RECOVERY | 별도 mode가 아니라 `intensity + conditionTags`로 흡수 |
| FLOW | 순서 있는 체크리스트 표시를 위한 `orderIndex`로 흡수 |
| PRIORITY | viewMode가 아니라 ScheduleBlock의 행동 속성으로 정리 |

## 7. 조정 중심 흐름

계획 시스템은 계획 생성보다 조정 이력을 중요하게 본다.

사용자는 ScheduleBlock에 대해 다음 액션을 수행할 수 있다.

- 완료
- 이동
- 축소
- 보류

이 액션은 단순 필드 변경으로 끝나지 않고 plan_item_events에 기록된다.

예시:

```text
오늘 못 한 블록을 내일로 이동
→ 기존 ScheduleBlock row의 blockDate와 dailyPlanId 갱신
→ MOVED 이벤트 저장
```

## 8. MVP 적용 범위

MVP에서는 DailyPlan, ScheduleBlock, Todo, plan_item_events를 중심으로 구현한다.

월간 계획, 연간 계획, LifeGoal은 장기 확장 방향이다. MVP에서는 테이블도 화면도 만들지 않는다.

AI 주간 요약은 1.5차에 둔다. 먼저 이벤트 집계 기반 주간 회고 화면을 만든 뒤, 데이터가 쌓였을 때 사용자가 요청하면 AI가 참고용 요약 후보를 제공한다.
