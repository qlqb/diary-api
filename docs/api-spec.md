# Diary API Specification

Base URL: `http://localhost:8080`

Content-Type: `application/json`

대부분의 API는 JWT 인증이 필요합니다. 회원가입과 로그인으로 받은 토큰을 다음 헤더에 넣어 호출합니다.

```http
Authorization: Bearer {token}
```

인증 없이 호출 가능한 API는 `/api/auth/signup`, `/api/auth/login`입니다.

이 문서는 Auth / Users / Diaries만 다룹니다. 실행 조각(`/api/execution-items`), 프로젝트,
자료, AI 상담 엔드포인트는 `docs/openapi.yaml`을 기준으로 합니다.

2026-08-23에 `/api/todos`와 `/api/schedule-blocks` 절을 삭제했습니다. 해당 기능은
`execution_items`로 이관 완료 후 제거되었습니다 —
`docs/sql/2026-08-23-remove-legacy-tables.sql` 참고.

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


## Material Analysis (자동 분석 상태) — 2026-09-13

업로드된 자료는 서버가 뒤에서 자동으로 읽는다. 여기는 그 상태 조회와 제어다. 설계는 `docs/product/15-material-auto-analysis.md`.

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/materials/analysis/overview` | `{paused, serviceAvailable, queued, running, partial, done, failed, unavailable, materials[]}` |
| POST | `/api/materials/analysis/pause` · `/resume` | 내 자동 분석 일시중지/재개(QUEUED↔PAUSED). 응답은 overview |
| GET | `/api/materials/{id}/analysis-status` | `{materialId, state(NONE|QUEUED|RUNNING|PARTIAL|DONE|FAILED|UNAVAILABLE|PAUSED|NO_TEXT), totalChunks, completedChunks, errorCode, message, sectionCount, assignmentCandidateCount, pageCount, linkStates[{courseId,state,proposalId}]}` |
| POST | `/api/materials/{id}/analysis-status/retry` | 다시 시도(작업을 앞으로 당긴다) |
| GET | `/api/materials/{id}/sections` | 분석된 구간 `[{sectionId, locator, unitStart, unitEnd, unitType, printedPageStart, printedPageEnd, label, title, roles[], roleLabels[], taskText, excerpt, assignmentCue, assignmentQuote, dates[]}]` |
| GET | `/api/material-sections/{sectionId}` | 구간 단건 |

## Topic Change Proposals (자료 정리 변경안)

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/courses/{courseId}/topic-change-proposals?includeResolved=false` | 열린 변경안. `{proposalId, materialId, materialFilename, status, baseTreeVersion, currentTreeVersion, stale, summary{link,add,rename,move,merge,split,structural}, ops[], topicTitles{}, sections[]}` |
| GET | `/api/topic-change-proposals/{id}` | 단건 |
| POST | `/api/topic-change-proposals/{id}/apply` | `{selectedOpIndexes?: number[], titleOverrides?: {index: title}}`. 한 트랜잭션, 전부 아니면 전무. 409 `E409_017`(구조가 바뀜) · `E409_018`(이미 처리됨) · 400 `E400_030`(적용 불가) |
| POST | `/api/topic-change-proposals/{id}/dismiss` | 제외 |

ops: `LINK(topicId, sectionIds, role)` · `ADD(tempId, parentTopicId|parentTempId, title, sourceType, locator, sectionIds, children)` ·
`RENAME(topicId, title)` · `MOVE(topicId, parentTopicId|null)` · `MERGE(survivingTopicId, absorbedTopicIds)` · `SPLIT(topicId, children)`.

`GET /api/courses/{courseId}/topics` 응답의 각 항목에 `linkedMaterials[{linkId, materialId, filename, materialDeleted, sectionId,
sectionTitle, locator, role, roleLabel, taskText}]`와 `reviewNote`가 추가됐다.

## Assignments (과제)

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/courses/{courseId}/assignments` | 프로젝트의 과제 전부(후보·확정·완료). `{assignmentId, courseId, materialId, materialFilename, materialDeleted, sectionId, sectionLocator, topicId, title, sourceQuote, confirmStatus(CANDIDATE|CONFIRMED|NOT_ASSIGNMENT|LATER|DUPLICATE), dueKind(UNKNOWN|NONE|DATE|DATETIME), dueDate, dueAt, dueSource(SOURCE|ESTIMATED|USER), dueQuote, dueEstimates[], completedAt, completed, overdue, titleEdited, dueEdited, duplicateOfAssignmentId, duplicateOfTitle, version}` |
| GET | `/api/assignments` | 확정·미완료 과제 전부(마감 순). 오늘 화면용 |
| POST | `/api/assignments` | 직접 추가 `{courseId?, title, dueKind?, dueDate?, dueAt?}` |
| PATCH | `/api/assignments/{id}/answer` | `{answer, duplicateOfAssignmentId?, version}` |
| PATCH | `/api/assignments/{id}/due` | `{dueKind, dueDate?, dueAt?, version}`. 「이 날짜 맞아요」도 이 요청이다 |
| PATCH | `/api/assignments/{id}/title` | `{title, version}` |
| PATCH | `/api/assignments/{id}/completed` | `{completed, version}` |

모든 변경은 `version` 대조. 어긋나면 409 `E409_004`. 마감 입력 오류는 400 `E400_031`.

## Plan Item Detail (「자세히」)

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/plans/drafts/items/{proposalItemId}/detail` | 있으면 그대로. 없으면 `available=false, canGenerate` |
| POST | `/api/plans/drafts/items/{proposalItemId}/detail` | 없을 때만 만든다(모델 1회). 항목·시간·마감은 그대로 |
| GET / POST | `/api/plans/items/{executionItemId}/detail` | 확정된 조각(만든 제안 항목 기준) |
| PATCH | `/api/plans/item-details/{detailId}` | `{userText}` |

응답 `{detailId, proposalItemId, evidenceVersion, steps[{text, refIds, sectionIds}], userText, stale, available, canGenerate,
sections[{sectionId, materialId, title, locator}]}`. 인용한 구간이 없으면 400 `E400_032`.

`POST /api/plans/draft`에 `excludeTopicIds`(이번 요청에서만 제외)가 추가됐고, 응답에 `pendingMaterials[{materialId, filename,
state, courseId}]`가 추가됐다.
