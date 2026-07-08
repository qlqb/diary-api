# 08. Today Execution Loop

## 1. 이 문서의 목적

- Today 화면 중심의 실행 루프를 명확히 정리한다.
- Todo와 ScheduleBlock의 역할 혼동을 줄인다.
- 사용자 화면 언어와 내부 도메인 용어를 분리한다.
- 1차-A에서 구현할 것과 나중으로 미룰 것을 고정한다.

## 2. 최종 결론

사용자에게 Today 화면은 앱의 기본 입구다. 사용자는 Today 화면에서 "오늘 해볼 것"을 만든다.

이 항목은 내부적으로 `schedule_blocks`에 저장한다. 사용자는 각 항목에 대해 완료, 이동, 줄이기, 보류 액션을 수행할 수 있다.

각 액션은 `plan_item_events`에 저장한다. AI 추천은 지금 구현하지 않는다. 지금은 추천에 사용할 행동 이벤트 데이터를 쌓는 단계다.

## 3. 사용자 언어와 내부 도메인 분리

| 사용자 화면 용어 | 내부 도메인 | 설명 |
|---|---|---|
| 오늘 해볼 것 / 오늘 할 일 | ScheduleBlock | Today 화면에 올라온 실행 카드 |
| 나중에 해볼 것 | Todo | 아직 날짜가 확정되지 않은 실행 후보 |
| 이번 달 집중 | MonthlyPlan | 월 단위 방향과 집중 영역 |
| 올해 방향 | YearlyGoal | 연 단위 큰 방향 |
| 행동 기록 | plan_item_events | 완료/이동/축소/보류 같은 사건 기록 |
| 추천 | ai_suggestions | 2차 이후 AI 후보와 사용자 반응 기록 |

사용자 화면에는 `ScheduleBlock`, `Todo`, `plan_item_events` 같은 내부 용어를 직접 노출하지 않는다.

## 4. Todo와 ScheduleBlock의 역할

### ScheduleBlock

- Today 화면에 올라온 실행 카드다.
- 시간이 없어도 ScheduleBlock이다.
- 시간이 없으면 `block_type = TASK`다.
- 시작/종료 시간이 있으면 `block_type = TIME_FIXED`다.
- 완료/이동/줄이기/보류 액션의 직접 대상이다.
- 1차-A의 중심 도메인이다.

### Todo

- "쓰레기통"이 아니라 "실행 후보 대기열"이다.
- 아직 오늘 할지 확정되지 않은 행동 후보를 담는다.
- 월간계획/연간목표/장기목표에 넣기엔 너무 구체적인 행동들을 받아준다.
- 나중에 Today로 가져오면 ScheduleBlock이 생성된다.
- 1차-A에서는 사용자 화면에 노출하지 않는다.
- 기존 Todo 백엔드 코드는 삭제하지 않는다.

Todo는 후보이고, ScheduleBlock은 오늘의 실행 카드다.

## 5. 사용자 관점의 설계 병목

### 1. 입력 분류 병목

사용자가 할 일을 입력할 때 Todo인지 ScheduleBlock인지 월간계획인지 고르게 만들면 안 된다.

1차-A에서는 Today에 입력한 것은 전부 오늘 해볼 것으로 보고 ScheduleBlock으로 저장한다.

### 2. 날짜 미정 항목 병목

"언젠가 할 행동"은 월간계획/연간목표가 아니다.

나중에 Todo, 즉 "나중에 해볼 것"으로 받아야 한다. 단, 1차-A에서는 이 UI를 만들지 않는다.

### 3. 상위 계획 오염 병목

월간계획/연간목표/장기목표를 잡일 보관함으로 쓰면 안 된다.

상위 계획은 방향과 집중 영역을 담는다. 행동 단위의 후보는 Todo가 담당한다.

### 4. 이벤트 수집 체감 병목

이벤트는 사용자가 따로 기록하는 것이 아니다.

사용자가 버튼을 누르면 시스템이 뒤에서 `plan_item_events`에 저장한다. "왜 못 했나요?" 같은 추가 입력을 강제하지 않는다.

## 6. 1차-A 범위

포함:

- Today 화면
- 오늘의 기록 영역
- 오늘 해볼 것 입력
- 오늘 해볼 것 목록
- 지난 계획 정리 pending 카드
- ScheduleBlock 생성
- ScheduleBlock 조회
- complete / move / reduce / hold 액션
- plan_item_events 저장
- 액션 성공 후 pending 목록과 오늘 목록 재조회

제외:

- Todo 사용자 화면
- 나중에 해볼 것 보관함
- 보류함 화면
- 보류 해제
- 월간계획 화면
- 연간목표 화면
- 장기목표 화면
- AI 추천
- ai_suggestions 구현
- WeeklyReview
- quick_logs
- viewMode/intensity/conditionTags UI 고도화
- priority/intensity 연동 UI
- 자동 scale-down
- 알림/푸시/PWA

## 7. Today 입력 규칙

- Today 화면 입력창 문구는 "오늘 해볼 것을 적어보세요"처럼 오늘 기준으로 제한한다.
- 제목만 입력하면 ScheduleBlock TASK로 저장한다.
- 시간 추가를 선택한 경우 ScheduleBlock TIME_FIXED로 저장한다.
- 처음부터 날짜, 카테고리, 중요도, 반복, 메모를 요구하지 않는다.
- 사용자가 Todo/ScheduleBlock을 선택하게 만들지 않는다.

## 8. 이벤트와 추천의 관계

```text
ScheduleBlock 생성
→ 사용자가 완료/이동/줄이기/보류 수행
→ plan_item_events 저장
→ 이벤트 집계
→ 나중에 AI 추천의 근거로 사용
```

AI 추천은 1차-A 범위가 아니다.

1차-A는 추천의 결과물이 아니라 추천에 필요한 행동 데이터를 수집하는 단계다. `ai_suggestions`는 2차 이후 구현 대상으로 유지한다.

## 9. 나중 확장 방향

나중에는 "나중에 해볼 것" UI를 만들 수 있다. 이 UI는 내부적으로 Todo를 사용한다.

Todo를 오늘로 가져오면 ScheduleBlock을 생성한다. MonthlyPlan / YearlyGoal / LifeGoal은 행동 보관함이 아니라 방향과 집중 영역을 담는다.

예시:

```text
Todo 후보: README 정리하기
→ 오늘로 가져오기
→ ScheduleBlock 생성
→ 완료/이동/줄이기/보류
→ plan_item_events 저장
```

## 10. 구현자 주의사항

- 사용자 화면에 ScheduleBlock이라는 단어를 노출하지 않는다.
- 사용자 화면에 Todo와 ScheduleBlock을 구분해서 입력시키지 않는다.
- 1차-A에서는 Todo UI를 만들지 않는다.
- 기존 Todo 코드는 삭제하지 않는다.
- Today 화면의 중심은 Todo 관리가 아니라 기록 기반 자기관리다.
- Today 화면의 첫 섹션은 "오늘의 기록"이어야 한다.
- "미완료", "실패", "밀린 일" 같은 표현은 피한다.
- 대신 "정리할 항목", "다시 배치", "줄이기", "보류" 같은 표현을 사용한다.
