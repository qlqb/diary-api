# Diary API Specification

Base URL: `http://localhost:8080`

Content-Type: `application/json`

대부분의 API는 JWT 인증이 필요합니다. 회원가입과 로그인으로 받은 토큰을 다음 헤더에 넣어 호출합니다.

```http
Authorization: Bearer {token}
```

인증 없이 호출 가능한 API는 `/api/auth/signup`, `/api/auth/login`입니다.

## 공통 오류 응답

```json
{
  "code": "E400_001",
  "message": "입력값이 올바르지 않습니다",
  "errors": [],
  "timestamp": "2026-06-28T12:34:56"
}
```

주요 상태 코드는 `400 Bad Request`, `401 Unauthorized`, `403 Forbidden`, `404 Not Found`, `409 Conflict`, `500 Internal Server Error`입니다.

## Auth

### 회원가입

`POST /api/auth/signup`

Request:

```json
{
  "email": "test@example.com",
  "password": "password123",
  "nickname": "tester"
}
```

Response `201 Created`:

```json
{
  "token": "jwt-token",
  "user": {
    "userId": 1,
    "email": "test@example.com",
    "nickname": "tester",
    "role": "USER"
  }
}
```

### 로그인

`POST /api/auth/login`

Request:

```json
{
  "email": "test@example.com",
  "password": "password123"
}
```

Response `200 OK`: 회원가입 응답과 동일한 `AuthResponse`를 반환합니다.

## Users

### 현재 사용자 기본 정보

`GET /api/users/me`

Response `200 OK`:

```json
{
  "userId": 1,
  "email": "test@example.com",
  "nickname": "tester",
  "role": "USER"
}
```

### 현재 사용자 상세 정보

`GET /api/users/me/detail`

Response `200 OK`:

```json
{
  "userId": 1,
  "email": "test@example.com",
  "nickname": "tester",
  "role": "USER",
  "status": "ACTIVE",
  "createdAt": "2026-06-28T10:00:00",
  "updatedAt": "2026-06-28T10:00:00"
}
```

## Diaries

### 일기 목록

`GET /api/diaries?page=1&size=10&mood=HAPPY&favorite=true&keyword=검색어`

Query:

- `page`: 기본값 `1`
- `size`: 기본값 `10`
- `mood`: 선택
- `favorite`: 선택
- `keyword`: 선택

Response `200 OK`:

```json
{
  "content": [
    {
      "diaryId": 10,
      "userId": 1,
      "writtenDate": "2026-06-28",
      "title": "오늘",
      "content": "내용",
      "mood": "HAPPY",
      "visibility": "PRIVATE",
      "weather": "SUNNY",
      "isFavorite": false,
      "createdAt": "2026-06-28T10:00:00",
      "updatedAt": "2026-06-28T10:00:00"
    }
  ],
  "page": 1,
  "size": 10,
  "totalElements": 1,
  "totalPages": 1,
  "first": true,
  "last": true
}
```

### 일기 검색

`GET /api/diaries/search?keyword=검색어&page=1&size=10`

Response `200 OK`: 일기 목록과 같은 `PageResponse<DiaryResponse>`를 반환합니다.

### 일기 상세

`GET /api/diaries/{diaryId}`

Response `200 OK`: `DiaryResponse`를 반환합니다.

### 일기 작성

`POST /api/diaries`

Request:

```json
{
  "writtenDate": "2026-06-28",
  "title": "오늘",
  "content": "내용",
  "mood": "HAPPY",
  "visibility": "PRIVATE",
  "weather": "SUNNY"
}
```

Response `201 Created`: 생성된 `DiaryResponse`를 반환합니다.

### 일기 수정

`PUT /api/diaries/{diaryId}`

Request:

```json
{
  "writtenDate": "2026-06-28",
  "title": "수정된 제목",
  "content": "수정된 내용",
  "mood": "NEUTRAL",
  "visibility": "PRIVATE",
  "weather": "CLOUDY",
  "isFavorite": true
}
```

Response `200 OK`: 수정된 `DiaryResponse`를 반환합니다.

### 일기 삭제

`DELETE /api/diaries/{diaryId}`

Response `204 No Content`

### 즐겨찾기 토글

`PATCH /api/diaries/{diaryId}/favorite`

Response `200 OK`: 변경된 `DiaryResponse`를 반환합니다.

### 수정 이력 조회

`GET /api/diaries/{diaryId}/revisions`

Response `200 OK`:

```json
{
  "current": {
    "diaryId": 10,
    "userId": 1,
    "writtenDate": "2026-06-28",
    "title": "현재 제목",
    "content": "현재 내용",
    "mood": "HAPPY",
    "visibility": "PRIVATE",
    "weather": "SUNNY",
    "isFavorite": false,
    "createdAt": "2026-06-28T10:00:00",
    "updatedAt": "2026-06-28T11:00:00"
  },
  "revisions": [],
  "totalRevisions": 0
}
```

### 수정 이력 복원

`POST /api/diaries/{diaryId}/revisions/{revisionId}/restore`

Response `200 OK`: 복원된 `DiaryResponse`를 반환합니다.

### 일기 통계

- `GET /api/diaries/statistics/summary`
- `GET /api/diaries/statistics/mood`
- `GET /api/diaries/statistics/monthly?year=2026`
- `GET /api/diaries/statistics/streak`

Response examples:

```json
{
  "totalCount": 12,
  "thisMonthCount": 3,
  "favoriteCount": 2
}
```

```json
{
  "moodCounts": {
    "HAPPY": 5,
    "SAD": 2
  },
  "totalCount": 7
}
```

```json
{
  "year": 2026,
  "monthlyCounts": [0, 0, 0, 0, 0, 3, 0, 0, 0, 0, 0, 0],
  "totalCount": 3
}
```

```json
{
  "currentStreak": 4,
  "longestStreak": 12
}
```

## Todos

Todo API는 인증된 사용자의 Todo만 조회/수정/삭제합니다. 다른 사용자의 `todoId`로 접근하면 조회되지 않습니다.

Enum 값:

- `status`: `TODO`, `DONE`
- `priority`: `HIGH`, `MEDIUM`, `LOW`
- `originType`: `MANUAL`, `AI_SUGGESTED`, `ROUTINE_GENERATED`

### Todo 생성

`POST /api/todos`

Request:

```json
{
  "todoDate": "2026-06-28",
  "title": "운동하기",
  "content": "30분 걷기",
  "priority": "MEDIUM"
}
```

Request fields:

- `todoDate`: 필수
- `title`: 필수
- `content`: 선택
- `priority`: 선택, 생략 시 `MEDIUM`

Response `201 Created`:

```json
{
  "todoId": 1,
  "userId": 1,
  "todoDate": "2026-06-28",
  "title": "운동하기",
  "content": "30분 걷기",
  "status": "TODO",
  "priority": "MEDIUM",
  "originType": "MANUAL",
  "modifiedAfterCreation": false,
  "routineId": null,
  "completedAt": null,
  "createdAt": "2026-06-28T10:00:00",
  "updatedAt": "2026-06-28T10:00:00"
}
```

### 날짜별 Todo 목록

`GET /api/todos?date=2026-06-28`

`GET /api/todos?date=2026-06-28&status=TODO`

`GET /api/todos?date=2026-06-28&status=DONE`

Query:

- `date`: 필수
- `status`: 선택. 없으면 전체 조회, 있으면 해당 상태만 조회

Response `200 OK`: `TodoResponse` 배열을 반환합니다.

### Todo 상세

`GET /api/todos/{todoId}`

Response `200 OK`: `TodoResponse`를 반환합니다.

### Todo 수정

`PUT /api/todos/{todoId}`

Request:

```json
{
  "todoDate": "2026-06-28",
  "title": "수정된 제목",
  "content": "수정된 메모",
  "priority": "HIGH"
}
```

Response `200 OK`: 수정된 `TodoResponse`를 반환합니다.

### Todo 완료/미완료

- `PATCH /api/todos/{todoId}/done`: 완료 처리, `TODO` → `DONE`
- `PATCH /api/todos/{todoId}/todo`: 미완료 처리, `DONE` → `TODO`

Response `200 OK`: 변경된 `TodoResponse`를 반환합니다.

### Todo 삭제

`DELETE /api/todos/{todoId}`

Response `204 No Content`

### Todo 일별 통계

`GET /api/todos/statistics/daily?date=2026-06-28`

Response `200 OK`:

```json
{
  "date": "2026-06-28",
  "totalCount": 5,
  "doneCount": 3,
  "achievementRate": 60.0
}
```

## ScheduleBlocks

ScheduleBlock API는 인증된 사용자의 블록만 조회/수정/삭제합니다.

Enum 값:

- `blockType`: `TIME_FIXED`, `TASK`

시간 정책:

- `TIME_FIXED`는 `startTime`/`endTime`을 반드시 가져야 합니다.
- `TASK`는 `startTime`/`endTime`을 가지면 안 됩니다.
- `startTime`/`endTime` 중 하나만 있으면 `PARTIAL_TIME_RANGE` 오류입니다.
- `startTime`/`endTime`이 모두 있으면 `endTime`은 `startTime`보다 이후여야 합니다.
- `blockDate`는 이 블록이 속한 하루이고, `startTime`/`endTime`은 실제 시각입니다. 두 날짜가 달라도 허용합니다.

### ScheduleBlock 생성 smoke 예시

`POST /api/schedule-blocks`

TASK + 시간 없음: 성공

```json
{
  "blockDate": "2026-07-08",
  "title": "연결 리스트 문제 풀기",
  "blockType": "TASK"
}
```

TASK + 시간 있음: `400 TASK_MUST_NOT_HAVE_TIME`

```json
{
  "blockDate": "2026-07-08",
  "title": "연결 리스트 문제 풀기",
  "blockType": "TASK",
  "startTime": "2026-07-08T20:00:00",
  "endTime": "2026-07-08T21:00:00"
}
```

TIME_FIXED + 시간 있음: 성공

```json
{
  "blockDate": "2026-07-08",
  "title": "스터디",
  "blockType": "TIME_FIXED",
  "startTime": "2026-07-08T20:00:00",
  "endTime": "2026-07-08T21:00:00"
}
```

TIME_FIXED + 시간 없음: `400 TIME_FIXED_REQUIRES_TIME`

```json
{
  "blockDate": "2026-07-08",
  "title": "스터디",
  "blockType": "TIME_FIXED"
}
```

startTime만 있음: `400 PARTIAL_TIME_RANGE`

```json
{
  "blockDate": "2026-07-08",
  "title": "스터디",
  "blockType": "TIME_FIXED",
  "startTime": "2026-07-08T20:00:00"
}
```

blockDate와 실제 시각 날짜가 다름: 성공

```json
{
  "blockDate": "2026-07-08",
  "title": "새벽 정리",
  "blockType": "TIME_FIXED",
  "startTime": "2026-07-09T01:30:00",
  "endTime": "2026-07-09T02:00:00"
}
```

### Pending 조회

`GET /api/schedule-blocks/pending?date=2026-07-09`

`date` 파라미터는 실제 오늘 날짜가 아니라 pending 판단 기준 운영일(`baseOperationalDate`)입니다. 사용자가 날짜를 보내면 그 날짜를 우선하고, 생략하면 현재 구현은 임시로 `LocalDate.now()`를 사용합니다. 새벽 4시 기준 `operationalDate` 계산은 추후 공통 유틸로 분리할 예정입니다.

pending은 `block_date < baseOperationalDate`, `status=PLANNED`, `is_deleted=false`인 ScheduleBlock입니다. 오늘 항목, 미래 항목, DONE/HOLD/CANCELLED/삭제 항목은 pending이 아닙니다. pending 판단에는 `startTime`/`endTime`의 실제 시각을 사용하지 않습니다.

HOLD는 사용자가 "지금은 하지 않겠다"고 결론 낸 상태이므로 pending 카드에 반복 노출하지 않습니다. 1차-A의 hold 액션은 상태를 HOLD로 바꾸고 HOLD 이벤트를 저장하는 것까지만 담당합니다. 보류함 화면, 보류 해제 API, 보류 재검토 알림, 보류 사유 입력은 이후 범위입니다.
