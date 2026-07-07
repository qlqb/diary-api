# 04. Requirements

이 문서는 실제 구현 기준이 되는 요구사항을 관리한다.

## 1. 공통 원칙

```text
REQ-COMMON-001
AI가 생성한 데이터는 자동 저장되지 않고 후보로 표시된다.

REQ-COMMON-002
사용자가 선택하거나 적용한 항목만 최종 저장된다.

REQ-COMMON-003
AI가 생성한 항목을 사용자가 수정 후 적용하면 origin_type은 AI_GENERATED 또는 AI_SUGGESTED 계열로 유지하고 modified_after_creation은 true로 저장한다.

REQ-COMMON-004
AI 분석은 사용자가 명시적으로 요청한 경우에만 실행한다.

REQ-COMMON-005
구현 기준은 Spring Boot + MyBatis + Mapper XML 패턴이다.
```

## 2. 회원 요구사항

```text
REQ-USER-001
사용자는 이메일, 비밀번호, 닉네임을 입력하여 회원가입할 수 있다.

REQ-USER-002
사용자는 이메일과 비밀번호로 로그인할 수 있다.

REQ-USER-003
이메일은 중복될 수 없다.
```

## 3. 일기 요구사항

```text
REQ-DIARY-001
로그인한 사용자는 제목, 내용, 감정 상태를 입력하여 일기를 작성할 수 있다.

REQ-DIARY-002
사용자는 자신의 일기 목록을 조회할 수 있다.

REQ-DIARY-003
사용자는 자신의 일기 상세 내용을 조회할 수 있다.

REQ-DIARY-004
사용자는 자신의 일기를 수정할 수 있다.

REQ-DIARY-005
사용자는 자신의 일기를 삭제할 수 있다.
```

## 4. 정리함 요구사항

```text
REQ-PROBLEM-001
사용자는 정리할 문제나 주제를 직접 등록할 수 있다.

REQ-PROBLEM-002
사용자는 정리함 목록을 조회할 수 있다.

REQ-PROBLEM-003
사용자는 정리함 항목을 수정할 수 있다.

REQ-PROBLEM-004
사용자는 정리함 항목을 삭제할 수 있다.

REQ-PROBLEM-005
사용자는 일기 내용을 바탕으로 AI 문제 후보를 생성할 수 있다.

REQ-PROBLEM-006
AI 문제 후보는 자동 저장되지 않고 후보로 표시된다.

REQ-PROBLEM-007
사용자가 선택한 문제 후보만 정리함에 저장된다.
```

## 5. Todo 요구사항

```text
REQ-TODO-001
사용자는 Todo를 등록할 수 있다.

REQ-TODO-002
사용자는 날짜별 Todo 목록을 조회할 수 있다.

REQ-TODO-003
사용자는 Todo 완료 상태를 변경할 수 있다.

REQ-TODO-004
Todo가 DONE 상태로 변경되면 completed_at을 기록한다.

REQ-TODO-005
사용자는 Todo를 수정할 수 있다.

REQ-TODO-006
사용자는 Todo를 삭제할 수 있다.

REQ-TODO-007
Todo는 origin_type을 가진다.

REQ-TODO-008
AI가 추천한 Todo를 사용자가 수정 후 저장하면 origin_type은 AI_SUGGESTED로 유지하고 modified_after_creation은 true로 저장한다.

REQ-TODO-009
Todo는 ScheduleBlock과 선택적으로 연결될 수 있다.
```

## 6. DailyPlan 요구사항

```text
REQ-DAILY-PLAN-001
사용자는 날짜별 DailyPlan을 가질 수 있다.

REQ-DAILY-PLAN-002
DailyPlan은 user_id + date 기준으로 유일하다.

REQ-DAILY-PLAN-003
DailyPlan은 viewMode, intensity, conditionTags 또는 condition_note를 가진다.

REQ-DAILY-PLAN-004
이동 액션 등에서 대상 날짜 DailyPlan이 없으면 기본값으로 생성될 수 있다.
```

## 7. ScheduleBlock 요구사항

```text
REQ-SCHEDULE-BLOCK-001
ScheduleBlock은 하루 안에 배치되는 행동 단위다.

REQ-SCHEDULE-BLOCK-002
ScheduleBlock은 Todo와 선택적으로 연결될 수 있다.

REQ-SCHEDULE-BLOCK-003
ScheduleStatus는 PLANNED / DONE / HOLD / CANCELLED만 사용한다.

REQ-SCHEDULE-BLOCK-004
MOVED, REDUCED는 status가 아니라 event로 기록한다.

REQ-SCHEDULE-BLOCK-005
ScheduleBlock은 TIME_FIXED 또는 TASK block_type을 가진다.

REQ-SCHEDULE-BLOCK-006
ScheduleBlock은 MUST / SHOULD / OPTIONAL priority를 가진다.

REQ-SCHEDULE-BLOCK-007
CHECKLIST 표시 순서는 order_index로 관리한다.
```

## 8. plan_item_events 요구사항

```text
REQ-PLAN-EVENT-001
사용자가 완료/이동/축소/보류/삭제 같은 조정 행위를 하면 이벤트를 저장한다.

REQ-PLAN-EVENT-002
eventType은 CREATED / DONE / MOVED / REDUCED / HOLD / RESUMED / DELETED를 사용한다.

REQ-PLAN-EVENT-003
todo_id 또는 schedule_block_id 중 하나는 반드시 존재해야 한다.

REQ-PLAN-EVENT-004
ScheduleBlock 이벤트의 todo_id는 클라이언트에게 받지 않고 서버가 ScheduleBlock.todo_id에서 복사한다.
```

## 9. 도메인 액션 API 요구사항

move / reduce / hold / complete는 PATCH가 아니라 POST 하위 액션이다.

```text
REQ-SCHEDULE-ACTION-001
POST /api/schedule-blocks/{id}/move

REQ-SCHEDULE-ACTION-002
POST /api/schedule-blocks/{id}/reduce

REQ-SCHEDULE-ACTION-003
POST /api/schedule-blocks/{id}/hold

REQ-SCHEDULE-ACTION-004
POST /api/schedule-blocks/{id}/complete
```

## 10. move 액션 요구사항

```text
REQ-MOVE-001
move 액션은 기존 ScheduleBlock row를 복제하지 않는다.

REQ-MOVE-002
기존 row의 date와 daily_plan_id를 이동 대상 날짜로 갱신한다.

REQ-MOVE-003
대상 날짜 DailyPlan이 없으면 get-or-create 한다.

REQ-MOVE-004
ScheduleBlock 조회, DailyPlan get-or-create, block 갱신, MOVED 이벤트 저장은 하나의 트랜잭션이다.
```

## 11. quick_logs 요구사항

quick_logs는 1차-B 범위다.

```text
REQ-QUICK-LOG-001
SLEEP value_numeric은 1=6시간 미만, 2=6~7시간, 3=7시간 이상으로 저장한다.

REQ-QUICK-LOG-002
EMOTION value_numeric은 1=나쁨, 2=보통, 3=좋음으로 저장한다.

REQ-QUICK-LOG-003
value_numeric은 분석용 값이고 value_text는 표시용 값이다.
```

## 12. WeeklyReview / AI 요약 요구사항

```text
REQ-WEEKLY-REVIEW-001
주간 회고 집계 화면은 1차-B 범위다.

REQ-WEEKLY-REVIEW-002
AI 주간 요약은 1.5차 범위다.

REQ-WEEKLY-REVIEW-003
AI 요약은 사용자가 명시적으로 요청한 경우에만 실행한다.

REQ-WEEKLY-REVIEW-004
일기 원문 전체를 기본 전송하지 않는다.

REQ-WEEKLY-REVIEW-005
AI 결과는 참고용 후보이며 자동 저장하지 않는다.
```

## 13. 지출 기록 요구사항

```text
REQ-EXPENSE-001
사용자는 금액, 카테고리, 한 줄 메모, 감정 태그를 입력하여 지출을 기록할 수 있다.

REQ-EXPENSE-002
사용자는 일기 내용에서 AI가 추출한 지출 후보를 확인할 수 있다.

REQ-EXPENSE-003
AI가 추출한 지출 후보는 자동 저장되지 않는다.

REQ-EXPENSE-004
사용자가 저장한 지출 후보만 지출 기록으로 저장된다.
```
