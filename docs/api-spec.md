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


## Materials (자료 형식·본문 재추출) — 2026-09-16

자료로 저장할 수 있는 형식은 PDF·PPTX·HWP(5.0)·HWPX·IPYNB(nbformat 4)다. 검증은 확장자 + 파일 앞머리 시그니처이고,
브라우저가 보낸 content type은 쓰지 않는다(형식마다 제각각이라 정상 파일이 막힌다). 저장되는 content type은 형식별 대표 값이다.
ZIP은 자료가 아니라 아래의 가져오기 경로로 간다.

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/api/materials/{id}/extraction/retry` | 저장된 원본으로 본문만 다시 읽는다. 응답은 자료 단건. 이미 읽은 자료면 409 `E409_023`. 분석 재시도(`/analysis-status/retry`)와 다른 일이다 |

자료 응답에 `extractionWarning`이 추가됐다 — 읽긴 했지만 상한에 걸려 일부를 못 읽은 경우의 안내이고, `extractionStatus`는
SUCCESS다. 실패(`FAILED`/`FAILED_NO_TEXT`)는 `extractionError`에 이유가 들어간다(암호·손상·미지원 판·시간 초과).

## Material ZIP Imports (압축 파일 가져오기) — 2026-09-16

압축 자체는 자료가 되지 않는다. 올리면 내부 목록이 오고, 사용자가 고른 파일만 각각 자료가 된다. 실제 생성은 서버가 뒤에서 하므로
화면을 닫아도 계속된다. 모든 경로가 소유권을 확인한다(남의 importId·entryId는 404).

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/api/materials/zip-imports` | multipart `file`(+ `courseId`, `materialType`). 201 + 가져오기 한 건. 자료는 아직 만들어지지 않는다 |
| GET | `/api/materials/zip-imports` | 최근 가져오기 10건(새로고침·재접속 복구용) |
| GET | `/api/materials/zip-imports/{id}` | 한 건의 상태와 항목별 결과 |
| POST | `/api/materials/zip-imports/{id}/confirm` | `{entryIds:[...]}`. 고른 항목을 대기열에 넣는다. 여러 번 눌러도 결과가 같다. 409 `E409_024`(상태), 400(선택 없음·상한 초과) |
| POST | `/api/materials/zip-imports/{id}/entries/{entryId}/retry` | 실패한 항목 하나만 다시. 409 `E409_025`(재시도 대상 아님), `E409_026`(보관 기한 지나 원본 없음) |
| DELETE | `/api/materials/zip-imports/{id}` | 아직 시작 안 한 것만 취소. 이미 만들어진 자료는 남는다 |

응답: `{importId, originalFilename, sizeBytes, courseId, materialType, status(PREPARING|READY|IMPORTING|COMPLETED|PARTIAL|FAILED|CANCELLED|EXPIRED),
entryCount, selectableCount, doneCount, failedCount, remainingCount, archiveAvailable, expiresAt, errorCode, message, createdAt,
entries[{entryId, entryPath, displayName, extension, sizeBytes, supported, skipReason, status(PENDING|QUEUED|IMPORTING|DONE|FAILED|UNSUPPORTED), materialId, errorMessage}]}`

## Material Analysis (자동 분석 상태) — 2026-09-13

업로드된 자료는 서버가 뒤에서 자동으로 읽는다. 여기는 그 상태 조회와 제어다. 설계는 `docs/product/15-material-auto-analysis.md`.

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/materials/analysis/overview` | `{paused, serviceAvailable, queued, running, partial, done, failed, unavailable, materials[]}` |
| POST | `/api/materials/analysis/pause` · `/resume` | 내 자동 분석 일시중지/재개(QUEUED↔PAUSED). 응답은 overview |
| GET | `/api/materials/{id}/analysis-status` | `{materialId, state(NONE|QUEUED|RUNNING|PARTIAL|DONE|FAILED|UNAVAILABLE|PAUSED|NO_TEXT), totalChunks, completedChunks, errorCode, message, sectionCount, assignmentCandidateCount, pageCount, linkStates[{courseId,state,proposalId}]}` |
| POST | `/api/materials/{id}/analysis-status/retry` | 다시 시도(작업을 앞으로 당긴다) |
| GET | `/api/materials/{id}/sections` | 분석된 구간(`unitType`에 `NOTEBOOK_CELL` 추가 — 위치는 "셀 8~12") `[{sectionId, locator, unitStart, unitEnd, unitType, printedPageStart, printedPageEnd, label, title, roles[], roleLabels[], taskText, excerpt, assignmentCue, assignmentQuote, dates[]}]` |
| GET | `/api/material-sections/{sectionId}` | 구간 단건 |

## Topic Change Proposals (자료 정리 변경안) — **2026-09-21 이후 이력 전용**

> 자료 하나마다 만들던 변경안이다. **더 이상 새로 만들지 않는다** — 프로젝트에 연결된 자료를
> 함께 보고 하나로 만드는 [Project Tidy](#project-tidy-프로젝트-단위-자료-정리--2026-09-21)로 옮겼다.
> 아래 경로는 남아 있지만 열린(PROPOSED) 행이 없으므로 목록은 비어 있고, 지난 행은
> `SUPERSEDED`(원래 상태는 `superseded_from`)로 이력에 남는다.
> 되살리려면 `material.analysis.link-jobs.enabled=true`.

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

## Project Tidy (프로젝트 단위 자료 정리) — 2026-09-21

프로젝트에 연결된 **분석 완료 자료 전부**를 함께 보고 학습 구조 정리안 하나를 만든다. 자료별
변경안을 대신한다 — 같은 개념을 여러 자료가 다른 이름으로 다룰 때 자료별로는 각자 옳은 제안이
나오고 적용하면 중복만 남기 때문이다. 설계는 `docs/product/15-material-auto-analysis.md §9`.

**정리는 사용자가 누를 때만 시작된다.** 자료 분석이 끝나도 정리안이 생기지 않는다.

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/courses/{courseId}/tidy` | 지금 상태. 정리안이 없어도 이 모양으로 온다 |
| POST | `/api/courses/{courseId}/tidy?refresh=false` | [이 프로젝트 자료 정리]. 작업을 만들고 바로 돌아온다(생성은 비동기). **요청 시점의 입력을 스냅샷으로 고정한다**(아래). `refresh=true`면 새 자료까지 넣어 다시 만든다 — 성공할 때까지 기존 안과 편집은 그대로. 409 `E409_033`(쓸 수 있는 자료 없음) |
| POST | `/api/courses/{courseId}/tidy/retry` | *(2026-09-21 검토 후속)* 실패한 정리를 **요청 때의 입력 그대로** 다시 한다(실패한 작업의 스냅샷을 복사한 새 작업). 그 사이 끝난 자료는 섞지 않는다 — 그건 `refresh=true`. 실패한 작업이 없거나 `needsNewRequest` 실패면 409 `E409_035` |
| PUT | `/api/project-tidy/{proposalId}/edits` | 검토 중 고친 것 저장(자동 저장). `{editRevision, edits{changeId:{excluded,title}}, resolveCarried?{changeId: KEEP\|DROP}, revision?}`. **트리는 바뀌지 않는다.** `needsConfirm`·`carriedFrom`은 서버가 정한 값이라 요청으로 풀리지 않는다 — 풀려면 `resolveCarried`와 지금 정리안 `revision`을 함께 보낸다(판이 다르면 확인을 받지 않는다). `DROP`은 그 편집을 없앤다(새 제안 그대로). 409 `E409_029`(다른 곳에서 먼저 고침), `E409_028`(이미 끝난 정리안) |
| POST | `/api/project-tidy/{proposalId}/apply` | `{revision, editRevision, baseTreeVersion, selectedChangeIds[], titleOverrides{}}`. 한 트랜잭션, 전부 아니면 전무. 확인하지 않은 승계 편집이 하나라도 있으면 409 `E409_034`(어떤 항목인지 `details`에 이름으로) |
| POST | `/api/courses/{courseId}/tidy/dismiss` | [버리기]. 도는 작업도 무효화한다 — 되살아나지 않는다. **적용과 같은 전이 규칙**(행 잠금 + 상태 조건)이라 그 사이 적용이 끝났으면 덮지 않고 `alreadyResolved: true`로 돌려준다 |
| GET | `/api/courses/{courseId}/tidy/history` | 지난 정리안(적용·폐기·대체) |

`GET /api/courses/{id}/tidy` 응답:

```
{ courseId, proposalId, status(PROPOSED|APPLIED|DISMISSED|SUPERSEDED|EMPTY), revision,
  baseTreeVersion, currentTreeVersion, treeChanged,
  alreadyResolved,   // 폐기 응답에서만. 그 사이 이미 처리돼 있었다
  job{jobId, status(QUEUED|RUNNING|DONE|FAILED|UNAVAILABLE|CANCELLED), errorCode, message, retryable,
      needsNewRequest},  // STALE_INPUT·SNAPSHOT_INVALID·SNAPSHOT_OUTDATED — 같은 입력으로는 또 멈춘다
  summary{headline, link, add, rename, move, merge, split, total, structural,
          reviewedMaterialCount, excludedMaterialCount},
  groups[{key, kind(EXISTING|NEW), topicId, title, parentTitle, changeIds[]}],
  changes[{changeId, op, label, text, reason, titleEditable, title, structural,
           dependsOn[], caution, sections[{sectionId, materialId, materialFilename, locator,
           title, roles[], taskText, excerpt,
           availability(OK|OUTDATED|MATERIAL_DELETED|MISSING), page}]}],
  dependsOn{changeId: [changeId]},
  edits{changeId:{excluded, title, needsConfirm, carriedFrom{changeId, text, reason}}}, editRevision,
  scope{treeVersion, topicCount, treeLinesShown, reviewed[{materialId, filename, fileHash,
        analysisVersion, sectionCount, reviewedCount, listedCount}], excluded[{materialId, filename, reason,
        reasonLabel}], truncated, sectionsTotal, sectionsReviewed, sectionsListed, modelCalls},
  newMaterialCount, readyMaterialCount, analyzingMaterialCount, firstTime, legacyProposalCount }
```

- **변경은 영향을 받는 항목으로 묶인다**(`groups`). 파일별 카드가 아니다.
- `changeId`는 "무엇을 어떻게 바꾸는가"에서 나온 안정적인 이름이다. 제목을 고쳐도, 정리안이 새 판으로
  바뀌어도 같은 뜻이면 같다 — 사용자 편집을 새 판으로 옮길 수 있는 이유다. 배열 순번을 쓰지 않는다.
- `dependsOn`은 함께 골라야 하는 변경이다(새 항목 아래 새 항목). 빠뜨리면 적용이 409 `E409_030`이고
  무엇을 함께 골라야 하는지 `details`에 이름으로 온다.
- **목록으로 본 것과 자세히 읽은 것을 따로 센다**(2026-09-21 검토 후속). 모든 구간이 먼저 목록 수준
  (`sectionsListed`)으로 판단 호출에 실리고, 그중 모델이 고른 구간만 발췌까지 상세(`sectionsReviewed`)로 실린다.
  `modelCalls` = 고르기 호출 + 판단 호출 1(기본 최대 4). 자세히 읽은 것이 일부여도 전부 목록으로 봤으면
  부분 정리가 아니다.
- `scope.truncated`면 **목록 수준에서도 보지 못한 것**이 있는 부분 정리다(고르기 호출 한도 초과 → 그 자료는
  `excluded`에 `OVER_BUDGET`). 화면이 그 말을 해야 한다. `sectionsListed`가 0이면 목록/상세를 나눠 세기
  전(2026-09-21 판)의 정리안이다.
- `edits[].needsConfirm`: 판이 바뀌며 옮겨 온 편집 중 같은 변경이라고 단정할 수 없는 것. `carriedFrom`에
  예전 제안과 무엇이 달라졌는지(`reason`: "제안한 이름·근거 구간이(가) 달라졌어요" 등)가 있다.
  `changeId`가 같아도 내용(이름·근거·위치·분할 구성·합칠 대상)이 다르면 확인 필요로 옮긴다. 후보가 여럿이면
  옮기지 않는다.
- `sections[].availability`: `OK`면 지금 파일의 구간이고 PDF 쪽 단위면 `page`가 있다(원본을 그 쪽에서 연다).
  `OUTDATED`는 발췌가 예전 파일·예전 분석의 것 — 원본을 열면 지금 파일이 열리고 `page`는 없다.
  `MATERIAL_DELETED`·`MISSING`은 열 수 없다. 원본은 `GET /api/materials/{materialId}/file`(인증 필요).
- **입력 스냅샷**(요청 시점, `project_tidy_jobs.input_snapshot_json` v2): 트리 판, 대상 자료마다 파일 해시·분석
  판·근거 구간 id, 요청 때 제외와 사유. 실행할 때 지금 상태가 이것과 다르면 **모델을 부르지 않고** 작업을
  `FAILED`·`errorCode=STALE_INPUT`으로 끝내고 무엇이 바뀌었는지 `message`에 이름으로 적는다. 스냅샷을 읽지
  못하면 `SNAPSHOT_INVALID`, 판이 없는 옛 기록이면 `SNAPSHOT_OUTDATED`. 셋 다 `needsNewRequest=true`.
- `newMaterialCount > 0`이면 정리안을 만든 뒤 분석이 끝난 자료가 있다. **섞지 않는다** — 사용자가
  [새 자료 반영해 다시 정리]를 누를 때만 새 판을 만든다.
- 적용 오류: 409 `E409_017`(트리가 바뀜) · `E409_028`(이미 처리됨) · `E409_029`(판 불일치) ·
  `E409_030`(딸린 변경 누락) · `E409_031`(근거 자료가 바뀜 — 삭제·연결 해제·파일 해시·**분석 판**·인용 구간) ·
  `E409_034`(확인하지 않은 승계 편집). 404 `E404_032`.
- **상태 전이는 한 규칙**: 적용·폐기·교체 모두 정리안 행을 `FOR UPDATE`로 잡고 `status='PROPOSED'` 조건이 붙은
  UPDATE만 쓴다. 끝난 상태(APPLIED·DISMISSED·SUPERSEDED)를 다른 끝난 상태로 덮지 않는다. 잠금 순서는
  정리안 → 작업 → 자료.
- 구체적인 사유는 응답 `details`에 문장으로 온다("「교재.pdf」이(가) 이 프로젝트에서 연결이 끊겼어요").

## Material Analysis Batches (업로드·분석 묶음) — 2026-09-21

자료 여러 개를 올릴 때 **시작 전에 예상 시간**을 보고, 시작 뒤 진행률을 본다. 진행 상태의 원본은
서버다 — 탭을 옮기거나 새로고침해도 복원된다.

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/api/materials/analysis/estimate` | `{files:[{filename,sizeBytes}]}` → 예상만(저장 없음) |
| POST | `/api/materials/analysis/batches` | `{courseId?, files:[{filename,sizeBytes}]}` → 묶음 + 자리 목록. **여기서 구성원이 고정된다** |
| GET | `/api/materials/analysis/batches/{id}` | 진행 상태 |
| GET | `/api/materials/analysis/batches/open?courseId=&cursor=&limit=` | *(2026-09-21 검토 후속)* 아직 도는 묶음 한 쪽. `{batches[], nextCursor, totalOpen}`. **`nextCursor`가 null이 될 때까지 넘겨야 전부다.** `limit` 기본 20, 최대 50 |
| GET | `/api/materials/analysis/batches?courseId=` | **옛 경로 — 최대 5개에서 잘린다.** 옛 화면 호환용. 새 화면은 쓰지 않는다 |

업로드는 기존 경로에 `batchItemId`만 더 실어 보낸다:
`POST /api/materials?batchItemId=` · `POST /api/courses/{id}/materials?materialType=..&batchItemId=`.

묶음 응답:

```
{ batchId, courseId, zipImportId, sourceArchiveName, status(STAGED|UPLOADING|ANALYZING|FINISHED|ABANDONED), itemCount,
  processedPercent, doneCount, runningCount, waitingCount, failedCount, skippedCount,
  currentStage, remainingMinSeconds, remainingMaxSeconds, estimateBasis, uploadTimeExcluded,
  waitingReason(DAILY_LIMIT|PAUSED|SERVICE_UNAVAILABLE), resumesAt,
  finishedAt,
  items[{itemId, filename, sourcePath, sizeBytes, extension, materialId, uploadState, stage, stageLabel,
         totalChunks, completedChunks, estMinSeconds, estMaxSeconds, message, settled, retryable}] }
```

- **`processedPercent`는 처리 진행률이지 성공률이 아니다.** 실패·본문 없음으로 끝난 자리도 더 할 일이
  없으므로 센다. 100%는 "처리 종료"이고, 성공/실패/제외는 따로 센다.
- 산식: 자리 하나가 1, 분석 중인 자리는 청크 진행분만큼(최대 0.95). **분모는 묶음을 만들 때 고정된
  자리 수**라 분석 중에 다른 파일을 올려도 움직이지 않는다(그때는 새 묶음이 생긴다).
- 예상 시간은 보장이 아니라 범위다. `estimateBasis`가 `DEFAULT`·`PARTIAL_HISTORY`면 표본이 적다는
  뜻이고 화면이 "초기 추정"이라고 말한다. **업로드 전송 시간은 포함하지 않는다**(`uploadTimeExcluded`).
- 산식과 한계는 `AnalysisEstimator` 클래스 주석에 그대로 적혀 있다.
- **목록에 없다는 것은 끝났다는 증거가 아니다.** 화면은 열린 목록에서 빠진 묶음을 `GET /batches/{id}`로 하나씩
  물어 그 답을 쓴다.
- **상태는 양쪽으로 움직인다**(2026-09-21 검토 후속). 끝난 묶음의 실패 자료를 다시 돌리면(`POST /api/materials/{id}/analysis-status/retry`,
  본문 재추출 성공) 그 자료가 든 묶음이 `ANALYZING`으로 돌아가고 `finishedAt`이 비워진다. 다시 끝나면 새 종료 시각을 받는다.
- 올릴 때 본문을 못 읽은 자리(`NO_TEXT`)는 자료의 지금 상태를 따른다 — 재추출에 성공하면 더 이상 제외가 아니다.
- 파일을 고른 탭이 떠나 30분이 지나도 올라오지 않은 자리는 `ABANDONED`("다시 골라 올려야 해요")로 거둔다.
  서버가 이미 받은 자료의 분석은 이어진다. 압축 자리는 이 규칙을 받지 않는다(가져오기 작업자가 끝낸다).
- **압축 가져오기**: `POST /api/materials/zip-imports/{id}/confirm` 순간 확정한 항목으로 묶음이 열린다(`zipImportId`).
  탐색 중(`PREPARING`)에는 묶음이 없다. 자리는 압축 항목 id로 이어지고 `sourcePath`에 압축 안 경로가 있다 —
  이름이 같은 파일("과제1/run.sh", "과제2/run.sh")을 구분한다. 같은 압축에서 나중에 더 확정하면 새 묶음이다.
  항목 다시 가져오기는 자리를 `STAGED`로 되돌리고 묶음을 다시 연다. 가져오기 취소는 남은 자리를 `ABANDONED`로.

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

## Plan Draft — 상담·계획·실행 연결 (2026-09-17)

설계는 `docs/product/11-period-plan.md` §5-1-3, `13-plan-judgment.md` §10.6·§11, DB는 `05-database.md` §10.8. 초안 1회는 모델 최대
4회(선택 ≤2 · 판단 1 · 추가 읽기 뒤 판단 1, 복구 1회 포함 전체 4)이고 상한은 설정(`plan.draft.max-*`)이다.

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/api/plans/draft` | 요청에 `requestKey?: string`. 같은 키의 PROPOSED 초안이 있으면 모델 호출 없이 그것을 돌려준다. 같은 키가 진행 중이면 409 `E409_021` |
| POST | `/api/plans/proposals/{proposalId}/redraft` | 요청에 `requestKey?`. 이전 초안의 근거 스냅샷과 지금 값을 비교해 같으면 자료 선택을 생략(`generation.selectionReused`)하고, 다르면 `previousDraft.changes`에 달라진 점을 적는다 |
| GET | `/api/plans/draft/progress?requestKey=` | `{known, stage, label, proposalId?, errorCode?}`. stage: COLLECTING / SELECTING / RETRIEVING / PLANNING / READING_MORE / SAVING / DONE / FAILED. 모르는 키는 `{known:false}` |
| GET | `/api/plans/proposals/{proposalId}/draft` | 저장된 초안을 `PlanDraftResponse`로 다시 읽는다(새로고침·탭 이동 복구, 모델 호출 없음) |
| POST | `/api/ai/conversations/{id}/messages` | SSE에 `period_plan.progress {stage, label}`가 추가됐다(CREATE_PERIOD_PLAN 턴). `message.completed`의 `periodPlanDraft`는 아래 응답과 같다 |

`PlanDraftResponse`에 추가된 필드:

```text
strategy: {                      // 새 초안에서 null이 아니다
  goal, reach, strategySummary, keptDecisions[], courses[{courseId, rank, focus, reason}],
  topics[{topicId, topicTitle, treatment, reason}], deferred[{title, reason, topicId}], assumptions[],
  openQuestions[] (최대 1), unreadNotes[], changes[{what, why}],
  existingDecisions[{executionItemId, title, action KEEP|REDUCE|MOVE|DROP, reason, expectedMinutes?, toDate?}]
}
proposal.items[]: + deadlineSource: CLASS | ASSIGNMENT | AI_PROPOSED | null, doneCriteria, actionType, topicId,
                  조정 항목은 operation REDUCE|MOVE|DROP + targetExecutionItemId + beforeTitle/beforeExpectedMinutes/beforeScheduledDate
generation: { normalCalls, recoveryCalls, maxNormalCalls, maxTotalCalls, retrievalRounds, maxRetrievalRounds,
              inputTokens, outputTokens, elapsedMs, selectionReused, calls[] }
previousDraft: { proposalId, changes[] } | null
briefId, briefVersion            // 그때 읽은 상담 합의
```

근거 스냅샷(`GET /api/plans/drafts/{id}/provenance`)의 sourceType에 `CONVERSATION_MESSAGE`·`PLAN_BRIEF`·`EXECUTION_HISTORY`·
`NEXT_CLASS`, 서버 계산에 `GENERATION_CALLS`가 추가됐다.

계획 회고(`GET /api/plans/{planVersionId}/review`): `measuredMinutes`(실측 합) · `estimatedMinutes`(시간 미기록 완료 항목의 예정 합) ·
`unmeasuredDoneCount`, 항목마다 `actualMinutesSource: MEASURED | ESTIMATED | NONE`. `completedMinutes`는 둘의 합이다.

오류:

| 코드 | 상태 | 뜻 |
|---|---|---|
| `E409_021` | 409 | 같은 요청 키의 초안 생성이 진행 중 — 진행 상태를 조회해 기다린다 |
| `E503_003` | 503 | 계획 호출이 실패했거나, 읽을 수 없는 응답이 복구 호출(1회) 뒤에도 이어짐. 이전 초안 유지 |

## Plan Draft — 후속 수정 (2026-09-18)

설계는 `docs/product/11-period-plan.md` §5-1-4, `13-plan-judgment.md` §11.1, DB는 `05-database.md` §10.8.1.

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/api/plans/proposals/{proposalId}/confirm` | 초안에 기존 항목 결정(REDUCE/MOVE/DROP, 전략의 KEEP)이 있으면 같은 `planKey`의 다음 `version`을 만든다. 기존 항목의 생성 출처는 그대로, 새 항목만 새 판. 조정 전용 초안도 확정된다. 응답 `PlanResponse.planKey`/`version` |
| PUT | `/api/plans/proposals/{proposalId}/review-state` | 검토 상태 저장 `{version, title?, excludedProposalItemIds[], editedItems[], answers{}}`. 응답은 저장된 상태(version+1). 열린 초안만, version이 낡았으면 409 `E409_022`. 실행 데이터는 바뀌지 않는다 |
| GET | `/api/plans/proposals/{proposalId}/draft` | 옛 id가 대체됐으면(다시 만들기·「이미 알아요」) 지금 열린 최신 초안을 돌려준다. `reviewState`를 함께 준다 |
| POST | `/api/plans/drafts/{proposalId}/items:regenerate` | 요청이 저장된 초안(`requestContext.redraftable`)이면 같은 조건으로 다시 만들기로 간다(응답에 `previousDraft`) |
| POST | `/api/plans/{planVersionId}/place` | 날짜가 정해진(DATE_ONLY) 항목도 그 날 안에서 시각을 정한다. `unplaced[]`에 `scheduledDate`·`reason`("9/18에 남는 시간이 없어요") |
| GET | `/api/plans?date=` | 같은 `planKey`의 판이 여럿이면 최신 판만 |

`PlanDraftResponse.reviewState`: 위 저장 상태. `previousDraft.changes`의 일정 변경 문구는 "일정(수업·약속·시각이 정해진 항목)이
달라져 남는 시간이 바뀌었다" / "날짜가 …에서 …로 바뀌어 오늘 이후의 일정·마감을 다시 확인했다"다.

상담 SSE의 `planBrief` ops에 `periodStart`/`periodEnd`(PERIOD 합의의 실제 날짜)와 kind `FREQUENCY`가 더해졌다.

| 코드 | 상태 | 뜻 |
|---|---|---|
| `E409_022` | 409 | 검토 상태가 다른 곳에서 먼저 저장됨 — 최신 상태를 다시 읽는다 |

## 프로젝트 상담·계획·실행 통합 (2026-09-19)

설계는 11번 §5-1-5, 13번 §12, 15번 §4-3·§7.2·§7.3, DB는 05번 §18. 모든 추가 필드는 예전 초안·예전 대화에서 null/빈 목록이다.

### 계획 초안 `PlanDraftResponse` 추가 필드

| 필드 | 뜻 |
|---|---|
| `proposedMinutes` | 빼지 않은 항목의 예상 시간 합. 첫 화면의 분량 |
| `availabilityBasis` | `ALL_ASSUMED` / `PARTLY_ASSUMED` / `CONFIRMED` / `NONE` |
| `strategy.projects[]` | `{courseId, courseTitle, disposition, reason, decidedBy(MODEL|SERVER), materialState, candidates, shown(null=선택 재사용), selected, delivered, itemCount, itemMinutes, sectionIds[], nextAction}` — 대상 프로젝트가 모두 정확히 한 번 |
| `proposal.items[].selectionReason` / `.origin` | 선정 이유(모델의 한 문장) / `SOURCE_TASK`·`AI_PRACTICE`·`USER_REQUEST`·null. 근거 기록에서 서버가 읽어 붙인다 |
| `freshness` | `{state: CURRENT|STALE, reasons[]}` — 재조회(`GET /plans/proposals/{id}/draft`)에서 계산 |
| `carriedEdits[]`, `editConflicts[]` | 다시 만들기 응답에만. `{title, fields[]}`, `{title, field, yours, suggested}` |
| `generation.maxInputTokens`·`maxElapsedMs`·`refusals[]` | 한 회차 예산과, 선택적 호출을 하지 않은 이유 |

`disposition`: `INCLUDED` / `EXCLUDED_BY_CHOICE` / `UNDECIDED` / `NOT_REVIEWED`.
`materialState`: `NO_MATERIAL` / `ANALYSIS_PENDING` / `NO_RELEVANT_CONTENT` / `NOT_LISTED` / `OUTLINE_ONLY` / `RETRIEVAL_FAILED` / `TEXT_DELIVERED`.
`nextAction`: `ANSWER_QUESTION` / `UPLOAD_MATERIAL` / `WAIT_ANALYSIS` / `RETRY_ANALYSIS` / `NARROW_SCOPE` / `REVIEW_LATER` / null.

### `GET /api/plans/drafts/{proposalId}/trace?includeText=false`

이 초안을 만든 회차에 모델 호출마다 실제로 보낸 것(개발 검증용, 소유자만).

```json
{ "recorded": true, "generationId": "gen-…",
  "calls": [ { "callKind": "SELECTION|SELECTION_EXPAND|PLAN|PLAN_MORE_EVIDENCE|PLAN_RECOVERY", "callOrder": 1,
               "apiCommit": "5acf4ab…", "modelName": "…", "estimatedTokens": 3333, "promptSha256": "…",
               "shown": { "sectionIds": [], "topicIds": [], "deliveredSectionIds": [],
                          "perCourse": [ { "courseId": 1, "courseTitle": "…", "candidates": 21, "shown": 21, "selected": 3, "delivered": 3 } ] },
               "textPurged": false, "createdAt": "…", "systemPrompt": null, "userPrompt": null } ] }
```

기록이 없으면(이전 초안·보존 기간 경과) `recorded=false`다 — 오류가 아니다. 전문은 `includeText=true`일 때만, 자료를 지웠으면 없다.

### 상담 턴 `POST /api/ai/conversations/{id}/messages`

- 요청: `requestedAction`에 `PLAN_NOW` 추가. `answer: {questionId, choiceIds[], skipped}` — `message`가 비면 서버가 **저장된 질문의
  선택지 라벨**로 사용자 발화를 만든다(그 질문의 선택지가 아니면 400). `skipped=true`면 "이 질문은 건너뛸게요."
- 응답(`message.completed` payload, 그리고 `GET …/messages`의 ASSISTANT 메시지): `consult`
  ```json
  { "question": { "id": "q-<assistantMessageId>", "text": "…", "why": null,
                  "topic": "SUPPORT_LEVEL|BLOCKER|TIME|SCOPE|DEPTH|SUBMISSION|OTHER",
                  "choices": [ { "id": "c1", "label": "…" } ], "multiSelect": false },
    "understanding": [ { "id": "91", "source": "MEMORY|BRIEF", "text": "…",
                         "evidenceType": "STATED|SELF_REPORT|OBSERVED|INFERRED", "scopeLabel": "파이썬 기초 · 9/19", "isNew": true } ],
    "direction": { "before": "…", "after": "…", "reason": "…", "affectsDraft": true },
    "activity": { "kind": "SELF_CHECK", "courseId": 1, "title": "…", "items": [ { "key": "a1", "label": "…", "topicId": null, "sectionId": null } ] } }
  ```
- 기간 계획 OFFER는 강도 없이도 나온다(`intensity`=NORMAL 가정, 답변 끝에 가정이라고 말한다). 강도 되묻기 고정 문구는 더 나오지 않는다.

### `POST /api/ai/proposals/{proposalId}/dismiss`

이 제안을 버린다 → 204. 상태를 `DISMISSED`로만 바꾸고 내용은 지우지 않는다(다음 상담의 근거다).
이미 버렸으면 그대로 204(멱등), 이미 적용했으면 409 `E409_005`, 남의 것·없는 것이면 404 `E404_009`.

화면의 [초안 버리기]는 이 호출 없이 끝내지 않는다 — 열린 초안이 있는지는 서버가 들고 있어서, 화면에서만 지우면
대화를 다시 읽을 때(탭 이동·새로고침) 되살아난다. 되살리기는 `PROPOSED`만 되살린다.

### AI가 이해한 내 상황 `/api/contexts`

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/contexts` | `[{contextId, content, status, sourceType, evidenceType, courseId, courseTitle, topicId, scopeStart, scopeEnd, selfLevel, sourceMessageId, confirmedAt, updatedAt}]` |
| PATCH | `/api/contexts/{id}` `{content}` | 고치기. 옛 행 SUPERSEDED, 새 행 STATED/USER_EDITED |
| POST | `/api/contexts/{id}/confirm` | AI 추정을 확인(STATED로) |
| DELETE | `/api/contexts/{id}` | 철회(WITHDRAWN). 같은 내용은 다시 저장되지 않는다 |

변경 셋의 응답은 `{context, staleDraftIds[]}`. 이미 고쳤거나 지운 것이면 409 `E409_027`, 없으면 404 `E404_030`.

### 학습 지도·점검

- `GET /api/courses/{courseId}/learning-map` → `{courseId, title, treeVersion, state{materials, analysisPending, analysisFailed, linkWaiting, openProposals, topics, hasRecords}, topics[], proposed[], unlinked[], weeks[]}` (15번 §7.3).
- `POST /api/courses/{courseId}/self-checks` `{items:[{key,label,topicId,sectionId,level(KNOW|UNSURE|NEW),note}]}` → 204. 자기평가로만 저장.

### 자료 분석·실행 기록

- `GET /api/materials/analysis/overview`: `limit{contentUsed, contentLimit, linkUsed, linkLimit, reached, resumesAt}`, 자료마다 `waitingReason`·`linkState`.
- 업로드·ZIP 허용 확장자에 `sh`(text/plain으로 저장, 실행하지 않음). 파일 응답에 `X-Content-Type-Options: nosniff`.
- `POST /api/execution-items/{id}/complete|partial`: 선택 필드 `blockerKind`(`TIME|CONCEPT|ENERGY|OTHER`, 모르는 값은 버린다).
  `GET /api/execution-records`의 각 행에 `blockerKind`.
