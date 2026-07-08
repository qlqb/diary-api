# 05. Database

이 문서는 v2.1 기준 DB 설계 메모를 관리한다.

구현 기준은 Spring Boot + MyBatis + Mapper XML이다.

## 1. 핵심 테이블

```text
users
diaries
problems
todos
daily_plans
daily_plan_condition_tags
user_plan_preferences
schedule_blocks
plan_item_events
quick_logs
weekly_reviews
ai_suggestions
expenses
```

MonthlyPlan, YearlyPlan, LifeGoal, BehaviorPattern은 MVP에서 테이블도 만들지 않는다. 장기 확장 방향으로만 남긴다.

## 2. daily_plans

```text
daily_plan_id
user_id
plan_date
view_mode
view_mode_source
intensity
condition_note
main_goal
memo
created_at
updated_at

UNIQUE(user_id, plan_date)
```

DailyPlan은 날짜별 하루 운영 상태다. 대상 날짜 DailyPlan이 없을 때 이동 액션 등에서 기본값으로 생성될 수 있다.

## 3. daily_plan_condition_tags

```text
daily_plan_condition_tag_id
daily_plan_id
tag_text
```

conditionTags는 자유 태그다. enum으로 미리 고정하지 않는다.

## 4. user_plan_preferences

```text
user_plan_preference_id
user_id
default_view_mode
default_intensity
plan_depth
created_at
updated_at
```

## 5. schedule_blocks

```text
schedule_block_id
user_id
daily_plan_id
todo_id nullable
routine_id nullable
block_date
title
block_type
priority
start_time nullable
end_time nullable
order_index
status
memo
origin_type
modified_after_creation
is_deleted
created_at
updated_at
```

ScheduleStatus는 다음 네 값만 사용한다.

```text
PLANNED
DONE
HOLD
CANCELLED
```

MOVED, REDUCED는 status가 아니라 plan_item_events의 event_type으로 기록한다.

1차-A 기준 사용자 화면의 "오늘 해볼 것"은 내부적으로 `schedule_blocks`에 저장한다. 시간이 없는 실행 카드도 ScheduleBlock이며 `block_type = TASK`를 사용한다.

ScheduleBlock 시간 정책은 다음과 같다.

```text
TIME_FIXED:
- start_time NOT NULL
- end_time NOT NULL
- end_time > start_time

TASK:
- start_time NULL
- end_time NULL
```

`block_date`는 운영상 하루 기준 날짜이고, `start_time`/`end_time`은 실제 시각이다. 따라서 서로 날짜가 다를 수 있다.

DB와 서비스 모두 `DATE(start_time)=block_date` 제약을 두지 않는다.

허용 가능한 DB 권장 제약 예시는 다음과 같다. MySQL/MariaDB 버전과 기존 데이터 상태에 따라 CHECK 적용 가능 여부를 먼저 확인한다.

```sql
CHECK (
    (
        block_type = 'TIME_FIXED'
        AND start_time IS NOT NULL
        AND end_time IS NOT NULL
        AND end_time > start_time
    )
    OR
    (
        block_type = 'TASK'
        AND start_time IS NULL
        AND end_time IS NULL
    )
)
```

## 6. plan_item_events

```text
plan_item_event_id
user_id
todo_id nullable
schedule_block_id nullable
event_type
event_date
from_date nullable
to_date nullable
before_title nullable
after_title nullable
memo
created_at

CHECK(todo_id IS NOT NULL OR schedule_block_id IS NOT NULL)
INDEX(user_id, event_date)
INDEX(todo_id)
INDEX(schedule_block_id)
```

해석 정책은 다음과 같다.

```text
todo_id만 있음 = 미배치 Todo 이벤트
schedule_block_id만 있음 = 계획 항목 이벤트
둘 다 있음 = ScheduleBlock 기준 우선 해석
```

블록 이벤트의 todo_id는 클라이언트 입력을 받지 않는다. 서버가 ScheduleBlock.todo_id에서 복사해 무결성을 유지한다.

## 7. quick_logs

```text
quick_log_id
user_id
log_date
log_type
value_numeric
value_text nullable
created_at

UNIQUE(user_id, log_date, log_type)
```

값 정의:

```text
SLEEP: 1=6시간 미만, 2=6~7시간, 3=7시간 이상
EMOTION: 1=나쁨, 2=보통, 3=좋음
```

## 8. weekly_reviews

```text
weekly_review_id
user_id
week_start_date
done_summary
moved_summary
reduced_summary
hold_summary
next_week_note
ai_summary nullable
created_at
updated_at
```

주간 회고 집계 화면은 1차-B 범위다. AI 주간 요약은 1.5차 범위다.

## 9. ai_suggestions

ai_suggestions는 2차 구현이다. v2.1에서는 피드백 루프를 위해 설계만 확정한다.

```text
ai_suggestion_id
user_id
suggestion_type
content JSON
status
created_item_type nullable
created_item_id nullable
created_at
responded_at nullable

INDEX(user_id, status)
INDEX(user_id, created_at)
```

status는 다음 값을 사용한다.

```text
PROPOSED
APPLIED
MODIFIED_APPLIED
DISMISSED
EXPIRED
```

## 10. Todo 설계 메모

Todo는 기존 구현 흐름을 유지하되, 아직 날짜가 확정되지 않은 실행 후보 대기열로 본다. 나중에 Today로 가져오면 ScheduleBlock이 생성될 수 있다. 1차-A 사용자 화면에서는 Todo를 노출하지 않지만 기존 Todo 백엔드와 테이블은 삭제하지 않는다.

주요 조회 인덱스는 날짜별 조회와 상태별 조회를 우선한다.

```text
INDEX(user_id, is_deleted, todo_date)
INDEX(user_id, is_deleted, status)
INDEX(routine_id)
```

## 11. Enum

```text
DailyPlanViewMode
- TIME_TABLE
- CHECKLIST

ViewModeSource
- USER_DEFAULT
- USER_SELECTED

DailyPlanIntensity
- LIGHT
- NORMAL
- FOCUSED

ScheduleBlockType
- TIME_FIXED
- TASK

SchedulePriority
- MUST
- SHOULD
- OPTIONAL

ScheduleStatus
- PLANNED
- DONE
- HOLD
- CANCELLED

PlanItemEventType
- CREATED
- DONE
- MOVED
- REDUCED
- HOLD
- RESUMED
- DELETED

QuickLogType
- EMOTION
- SLEEP

OriginType
- MANUAL
- AI_GENERATED
- AI_SUGGESTED
- ROUTINE_GENERATED

SuggestionStatus
- PROPOSED
- APPLIED
- MODIFIED_APPLIED
- DISMISSED
- EXPIRED

PlanDepth
- TODAY_ONLY
- TODAY_AND_TOMORROW
- WEEKLY
- MONTHLY
- YEARLY
- LONG_TERM
```

## 12. 장기 확장 참고

구버전의 goals/plans 중심 설계는 장기 확장 참고 수준으로만 둔다.

MVP에서는 다음 테이블을 만들지 않는다.

```text
monthly_plans
yearly_plans
life_goals
behavior_patterns
```
