# Diary API Specification (Template)

> 기준 코드: `DiaryController`, `UserController`, `GlobalExceptionHandler`  
> Base URL: `http://localhost:8080`  
> Content-Type: `application/json`

---

## 공통

### 성공 응답
- JSON Body가 있는 경우: `application/json`
- Body가 없는 경우: 상태코드만 내려줌

### 에러 응답 포맷
서버에서 예외 발생 시 아래 포맷으로 응답합니다.

```json
{
  "status": 404,
  "message": "Diary not found. diaryId=10",
  "timestamp": "2026-01-22T12:34:56.789"
}
```

### 에러 상태코드 매핑 (현재 코드 기준)
- `400 Bad Request`: `IllegalArgumentException`
- `401 Unauthorized`: `SecurityException` *(현재 기능상 거의 미사용)*
- `404 Not Found`: `NotFoundException`
- `500 Internal Server Error`: 그 외 예외 *(message는 항상 `"Internal server error"`)*

> 참고: Spring MVC 레벨에서 발생하는 일부 예외(필수 파라미터 누락 등)는 현재 `GlobalExceptionHandler`의 catch-all에 의해 `500`으로 포장될 수 있습니다.  
> (원래는 `400`이 더 정상. 이건 나중에 예외 핸들링 보강하면 해결됨.)

---

## Users

### 회원가입
`POST /api/users`

**Request Body**
```json
{
  "email": "test@test.com",
  "password": "1234",
  "nickname": "tester"
}
```

**Responses**
- `201 Created` (Body 없음)

**Error**
- `400 Bad Request`: 잘못된 입력(향후 Validation 도입 시), 중복 처리 로직 추가 시
- `500 Internal Server Error`: DB/서버 오류 등

---

### 회원 단건 조회 (테스트/디버그용)
`GET /api/users/{userId}`

**Path Params**
- `userId` (number)

**Response 200**
```json
{
  "userId": 1,
  "email": "test@test.com",
  "nickname": "tester",
  "role": "USER",
  "status": "ACTIVE",
  "createdAt": "2026-01-01T12:00:00",
  "updatedAt": "2026-01-01T12:00:00"
}
```

**Error**
- `404 Not Found`: `User not found. userId={userId}`
- `500 Internal Server Error`

---

### 로그인 (임시 버전)
`POST /api/users/login`

> 주의: 현재 세션/JWT 없이 **email+password 검증 후 UserResponse 반환**만 합니다.

**Request Body**
```json
{
  "email": "test@test.com",
  "password": "1234"
}
```

**Response 200**
```json
{
  "userId": 1,
  "email": "test@test.com",
  "nickname": "tester",
  "role": "USER",
  "status": "ACTIVE",
  "createdAt": "2026-01-01T12:00:00",
  "updatedAt": "2026-01-01T12:00:00"
}
```

**Error (현재 코드 기준)**
- `404 Not Found`: `User not found. userEmail={email}`
- `400 Bad Request`: `Invalid email or password`

> 추천(다음 스텝): 위 2개를 `401 Unauthorized` + 동일 메시지로 통일해서 이메일 존재 여부가 새지 않게 만드는 게 정석입니다.

---

## Diaries

### 일기 생성
`POST /api/diaries`

**Request Body**
```json
{
  "writtenDate": "2026-01-15",
  "title": "오늘",
  "content": "내용",
  "mood": "HAPPY",
  "visibility": "PRIVATE",
  "weather": "SUNNY"
}
```

**Responses**
- `201 Created` (Body 없음)

**Notes**
- 현재 구현은 `userId=1L`로 하드코딩되어 저장됩니다. (TODO: 로그인 연동)

**Error**
- `400 Bad Request`: 잘못된 입력(향후 Validation 도입 시)
- `500 Internal Server Error`

---

### 일기 단건 조회
`GET /api/diaries/{diaryId}`

**Path Params**
- `diaryId` (number)

**Response 200**
```json
{
  "diaryId": 10,
  "writtenDate": "2026-01-15",
  "title": "오늘",
  "content": "내용",
  "mood": "HAPPY",
  "visibility": "PRIVATE",
  "favorite": false,
  "createdAt": "2026-01-15T10:00:00",
  "updatedAt": "2026-01-15T10:00:00"
}
```

**Error**
- `404 Not Found`: `Diary not found. diaryId={diaryId}` *(삭제된 일기 포함)*
- `500 Internal Server Error`

---

### 사용자별 일기 목록 조회
`GET /api/diaries?userId={userId}`

**Query Params**
- `userId` (number, required)

**Response 200**
```json
[
  {
    "diaryId": 10,
    "writtenDate": "2026-01-15",
    "title": "오늘",
    "content": "내용",
    "mood": "HAPPY",
    "visibility": "PRIVATE",
    "favorite": false,
    "createdAt": "2026-01-15T10:00:00",
    "updatedAt": "2026-01-15T10:00:00"
  }
]
```

**Notes**
- 현재는 `userId`를 쿼리로 받습니다.
- 향후 로그인(세션/JWT) 도입 시 `userId` 파라미터 제거 후 인증정보 기반 조회로 변경 권장.

**Error**
- `500 Internal Server Error` *(현재는 userId 누락 같은 케이스도 500으로 포장될 수 있음)*

---

### 일기 수정
`PUT /api/diaries/{diaryId}`

**Path Params**
- `diaryId` (number)

**Request Body**
```json
{
  "title": "수정된 제목",
  "content": "수정된 내용",
  "mood": "NEUTRAL",
  "visibility": "PRIVATE",
  "weather": "CLOUDY"
}
```

**Responses**
- `200 OK` (Body 없음)

**Side Effect**
- 수정 전 상태를 `diary_revisions`에 저장합니다. (title/content/mood)

**Error**
- `404 Not Found`: `Diary not found. diaryId={diaryId}` *(삭제된 일기 포함)*
- `500 Internal Server Error`

---

### 일기 삭제 (Soft Delete)
`DELETE /api/diaries/{diaryId}`

**Path Params**
- `diaryId` (number)

**Responses**
- `204 No Content`

**Error**
- `404 Not Found`: `Diary not found. diaryId={diaryId}` *(삭제된 일기 포함)*
- `500 Internal Server Error`

---

## (미노출) Revision API 후보

> 현재 `DiaryRevisionMapper`는 존재하지만 Controller/API는 아직 없습니다.

### 특정 일기의 수정 이력 조회 (추가 예정)
`GET /api/diaries/{diaryId}/revisions`

**Response 200 (예시)**
```json
[
  {
    "revisionId": 1,
    "diaryId": 10,
    "title": "이전 제목",
    "content": "이전 내용",
    "mood": "HAPPY",
    "editedAt": "2026-01-15T11:00:00"
  }
]
```
