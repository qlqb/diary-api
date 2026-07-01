# 05. Database

이 문서는 DB 설계 메모를 관리한다.

## 1. 핵심 테이블 후보

```text
users
diaries
problems
todos
goals
plans
schedule_blocks
expenses
```

## 2. Todo 테이블 설계 메모

```sql
CREATE TABLE todos (
    todo_id      BIGINT       NOT NULL AUTO_INCREMENT,
    user_id      BIGINT       NOT NULL,
    todo_date    DATE         NOT NULL,

    title        VARCHAR(255) NOT NULL,
    content      TEXT         NULL,

    status       VARCHAR(20)  NOT NULL DEFAULT 'TODO',
    priority     VARCHAR(20)  NOT NULL DEFAULT 'MEDIUM',

    origin_type  VARCHAR(30)  NOT NULL DEFAULT 'MANUAL',
    modified_after_creation TINYINT(1) NOT NULL DEFAULT 0,

    routine_id   BIGINT       NULL,
    completed_at DATETIME     NULL,

    is_deleted   TINYINT(1)   NOT NULL DEFAULT 0,

    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (todo_id),

    INDEX idx_todos_user_deleted_date   (user_id, is_deleted, todo_date),
    INDEX idx_todos_user_deleted_status (user_id, is_deleted, status),
    INDEX idx_todos_routine             (routine_id),

    CONSTRAINT fk_todos_user
        FOREIGN KEY (user_id) REFERENCES users(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

## 3. Todo enum 후보

```text
TodoStatus
- TODO
- DONE

TodoPriority
- HIGH
- MEDIUM
- LOW

TodoOriginType
- MANUAL
- AI_SUGGESTED
- ROUTINE_GENERATED
```

## 4. Plan 테이블 후보

월간 계획, 주간 계획, 하루 계획은 하나의 `plans` 테이블로 관리할 수 있다.

```text
plans

plan_id
user_id
plan_type
title
content
start_date
end_date
origin_type
modified_after_creation
is_deleted
created_at
updated_at
```

`plan_type` 후보:

```text
MONTHLY
WEEKLY
DAILY
```

연간 목표는 계획이라기보다 목표에 가까우므로 `goals` 테이블로 분리하는 것을 우선 고려한다.

## 5. Goal 테이블 후보

```text
goals

goal_id
user_id
title
description
goal_type
start_date
end_date
status
origin_type
modified_after_creation
is_deleted
created_at
updated_at
```

`goal_type` 후보:

```text
YEARLY
LONG_TERM
SHORT_TERM
```

## 6. ScheduleBlock 테이블 후보

하루 계획의 실제 시간 배치는 `schedule_blocks`에서 관리한다.

```text
schedule_blocks

schedule_block_id
user_id
plan_id
todo_id nullable
title
block_type
start_time
end_time
memo
origin_type
modified_after_creation
is_deleted
created_at
updated_at
```

역할 구분:

```text
Todo = 무엇을 할지
ScheduleBlock = 언제 할지
```

## 7. Origin 처리 원칙

직접 만든 데이터:

```text
origin_type = MANUAL
modified_after_creation = false
```

AI가 생성한 데이터를 그대로 적용:

```text
origin_type = AI_GENERATED 또는 AI_SUGGESTED
modified_after_creation = false
```

AI가 생성한 데이터를 사용자가 수정 후 적용:

```text
origin_type = AI_GENERATED 또는 AI_SUGGESTED
modified_after_creation = true
```

## 8. 인덱스 설계 원칙

Todo의 주요 조회 패턴은 다음과 같다.

```sql
SELECT *
FROM todos
WHERE user_id = ?
  AND is_deleted = 0
  AND todo_date = ?;
```

따라서 날짜별 조회용 인덱스를 둔다.

```sql
INDEX idx_todos_user_deleted_date (user_id, is_deleted, todo_date)
```

상태별 조회와 대시보드 필터링을 위해 다음 인덱스를 둔다.

```sql
INDEX idx_todos_user_deleted_status (user_id, is_deleted, status)
```

루틴에서 생성된 Todo 조회를 위해 다음 인덱스를 둔다.

```sql
INDEX idx_todos_routine (routine_id)
```
