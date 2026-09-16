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
| POST | `/api/plans/drafts/items/{proposalItemId}/detail` | 지금 근거판이 없을 때만 만든다(모델 1회). 이전 판이 오래됐으면(stale) 새 판을 만들고 `userText`를 옮긴다. 같은 판이 있으면 그대로 돌려준다. 항목·시간·마감은 그대로 |
| GET / POST | `/api/plans/items/{executionItemId}/detail` | 확정된 조각(만든 제안 항목 기준) |
| PATCH | `/api/plans/item-details/{detailId}` | `{userText}` |

응답 `{detailId, proposalItemId, evidenceVersion, steps[{text, refIds, sectionIds}], userText, stale, available, canGenerate,
sections[{sectionId, materialId, title, locator}]}`. 인용한 구간이 없으면 400 `E400_032`.

`POST /api/plans/draft`에 `excludeTopicIds`(이번 요청에서만 제외)가 추가됐고, 응답에 `pendingMaterials[{materialId, filename,
state, courseId}]`가 추가됐다.

## Plan Draft — AI 자료 선택과 같은 조건으로 다시 만들기 (2026-09-15)

설계는 `docs/product/15-material-auto-analysis.md` §7. 기본 AI 경로(`plan.draft.generator=AI`)에서 초안 1회는 모델 2~3회다
(선택 1, 후보가 커 접혔으면 펼친 묶음 선택 1, 계획 1).

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/api/plans/draft` | 요청에 `requestedMaterialIds?: number[]`, `requestedSectionIds?: number[]`(이번 요청의 지정 자료 — 영구 연결 아님)가 추가됐다 |
| POST | `/api/plans/proposals/{proposalId}/redraft` | `{excludeTopicIds?: number[], requestedMaterialIds?: number[]}`. 초안을 만든 요청(기간·강도·제목·지시·범위·지정 자료·대화)을 그대로 쓰고, 보낸 필드만 바꾼다(null이면 저장된 값 유지, 빈 배열이면 비움). 새 초안을 저장(상담 초안이면 같은 대화)하고 옛 초안을 DISMISSED로 바꾼다. 응답은 `PlanDraftResponse` |

응답 `PlanDraftResponse`에 추가된 필드:

```text
materialSelection: {
  status: SELECTED | EMPTY | NO_CANDIDATES, mode: NONE | FULL | FOLDED_GROUPS | FOLDED_COURSES,
  selectionCalls: 1..2, expanded, candidateTotal, candidateShown,
  sections[{sectionId, materialId, courseId, topicId, title, locator, filename, reason,
            outcome: FULL|PARTIAL|EXCERPT_ONLY|DROPPED_DELETED|DROPPED_CHANGED|DROPPED_SCOPE|NOT_RETRIEVED_BUDGET,
            retrievedRange, retrievedChars, refId}],
  topics[{topicId, courseId, title, reason}],
  unreviewed[{courseTitle, title, topics, sections, summarized}],   // 이번에 하나씩 보지 못한 범위
  insufficientEvidence, note, unknownIds, overLimit,
  requestedMaterials[{materialId, filename, courseId, source: EXPLICIT|INSTRUCTION}],
  ambiguities[{mention, candidates[{materialId, filename, courseId, source}]}],
  excludedTopics[{topicId, title, reason}],
  selectionInputTokens: number[], planInputTokens, perSectionChars
}
requestContext: { source: PLAN_SCREEN | CONVERSATION, courseIds, excludedTopics[{topicId, title}],
                  requestedMaterials[…], redraftable }
```

근거 스냅샷(`GET /api/plans/drafts/{id}/provenance`): 원문을 실은 구간은 `MATERIAL_SECTION`의 providedValue에 `retrievedRange`·
`retrievedChars`·`retrieval`·`selectionReason`이 붙고 `parentSourceId`는 학습 항목이다. 서버 계산에 `MATERIAL_SELECTION`이 추가됐다.

오류:

| 코드 | 상태 | 뜻 |
|---|---|---|
| `E400_033` | 400 | 지정 자료가 없거나 다른 사용자·요청 범위 밖 |
| `E400_034` | 400 | 판단 사실만으로 입력 상한을 넘음 — 기간·프로젝트 범위를 좁혀 달라 |
| `E409_019` | 409 | 요청이 저장되지 않은 옛 초안이라 같은 조건으로 다시 만들 수 없음 |
| `E409_020` | 409 | 이미 확정했거나 다른 초안으로 바뀐 초안 |
| `E503_003` | 503 | 자료 선택 호출 실패·시간 초과(기존 초안 유지, 서버 순위로 대체하지 않음) |
| `E503_004` | 503 | 자료 선택 응답을 읽지 못함(구조 없음, 보여 주지 않은 id뿐) |
| `E503_005` | 503 | 계획 호출은 성공했지만 쓸 항목이 없음(빈 초안 저장 없음) |
