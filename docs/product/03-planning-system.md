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
| ScheduleBlock | 하루 안에 배치되는 행동 단위 |
| Todo | 완료 가능한 구체 행동 |

역할 구분은 다음과 같다.

```text
Todo = 무엇을 할지
ScheduleBlock = 언제 또는 어떤 순서로 할지
DailyPlan = 오늘을 어떤 방식과 강도로 운영할지
```

모든 Todo가 ScheduleBlock으로 배치될 필요는 없다.

모든 ScheduleBlock이 Todo에서 올 필요도 없다. 사용자는 임시 일정, 휴식, 메모성 블록을 직접 만들 수 있다.

## 3. DailyPlan

DailyPlan은 날짜별 하루 운영 단위다.

필드는 다음 개념을 가진다.

```text
date
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

`block_type`은 다음 두 값을 사용한다.

```text
TIME_FIXED
TASK
```

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

## 5. Todo

Todo는 완료 가능한 구체 행동이다.

Todo는 독립적으로 존재할 수 있고, 필요하면 ScheduleBlock에 연결될 수 있다.

예시는 다음과 같다.

```text
Todo: 자료구조 연결 리스트 문제 3개 풀기
ScheduleBlock: 20:00-21:00 자료구조 문제 풀기
```

Todo는 무엇을 할지에 집중하고, ScheduleBlock은 오늘의 시간 또는 순서 배치에 집중한다.

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
→ 기존 ScheduleBlock row의 date와 daily_plan_id 갱신
→ MOVED 이벤트 저장
```

## 8. MVP 적용 범위

MVP에서는 DailyPlan, ScheduleBlock, Todo, plan_item_events를 중심으로 구현한다.

월간 계획, 연간 계획, LifeGoal은 장기 확장 방향이다. MVP에서는 테이블도 화면도 만들지 않는다.

AI 주간 요약은 1.5차에 둔다. 먼저 이벤트 집계 기반 주간 회고 화면을 만든 뒤, 데이터가 쌓였을 때 사용자가 요청하면 AI가 참고용 요약 후보를 제공한다.
