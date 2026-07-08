# diary-api

Spring Boot 기반 개인 메모/일기 API입니다. JWT 인증을 사용하며, 일기와 Todo를 사용자별로 관리합니다.

## 기술 스택

- Java 17
- Spring Boot 4.0.1
- Spring Security, JWT
- MyBatis
- MySQL
- Gradle
- springdoc-openapi

## 주요 기능

- 회원가입/로그인: `/api/auth/signup`, `/api/auth/login`
- 현재 사용자 조회: `/api/users/me`, `/api/users/me/detail`
- 일기 CRUD, 검색, 즐겨찾기, 수정 이력, 통계
- Todo CRUD, 완료/미완료 처리, 일별 달성률 통계
- 정적 테스트 페이지: `/login.html`, `/signup.html`, `/diary.html`

## 실행 전 준비

`src/main/resources/application.properties`는 DB 접속 정보와 JWT 시크릿을 환경변수로 읽습니다.

```properties
jwt.secret=${JWT_SECRET}
jwt.expiration=${JWT_EXPIRATION:3600000}
spring.datasource.url=${DB_URL}
spring.datasource.username=${DB_USERNAME}
spring.datasource.password=${DB_PASSWORD}
server.port=8080
```

실행 전에 다음 환경변수를 로컬 환경에 맞게 설정하세요.

```powershell
$env:JWT_SECRET="your-jwt-secret"
$env:DB_URL="jdbc:mysql://localhost:3306/memo?serverTimezone=Asia/Seoul&characterEncoding=UTF-8"
$env:DB_USERNAME="your-db-username"
$env:DB_PASSWORD="your-db-password"
```

로컬 MySQL에 `memo` 데이터베이스와 필요한 테이블을 준비한 뒤 실행하세요. 현재 저장소에는 별도 SQL 마이그레이션 파일이 없습니다.

## 실행

```bash
./gradlew bootRun
```

Windows PowerShell에서는 다음 명령을 사용할 수 있습니다.

```powershell
.\gradlew.bat bootRun
```

## 테스트

```bash
./gradlew test
```

## 인증 방식

회원가입 또는 로그인 응답의 `token` 값을 이후 요청에 Bearer 토큰으로 전달합니다.

```http
Authorization: Bearer {token}
```

인증 없이 접근 가능한 경로는 `/api/auth/**`, `/docs/**`, 정적 HTML/CSS/JS/JSX 파일, `/error`입니다. 그 외 API는 JWT 인증이 필요합니다.

## API 문서

- 수동 API 명세: [docs/api-spec.md](docs/api-spec.md)
- OpenAPI 파일: [docs/openapi.yaml](docs/openapi.yaml)
- springdoc-openapi 의존성이 포함되어 있지만, 현재 Security 설정은 `/swagger-ui/**`와 `/v3/api-docs/**`를 공개 경로로 열어두지 않습니다.

## 제품 문서

| 문서 | 설명 |
| --- | --- |
| [01-product-plan.md](docs/product/01-product-plan.md) | 앱의 최종 방향, 1차 사용자, 성공 기준 |
| [02-feature-structure.md](docs/product/02-feature-structure.md) | 기록 / 정리 / 실행 / 분석 계층 구조 |
| [03-planning-system.md](docs/product/03-planning-system.md) | DailyPlan / ScheduleBlock / Todo 역할 분리 |
| [04-requirements.md](docs/product/04-requirements.md) | 상세 기능별 구현 요구사항 |
| [05-database.md](docs/product/05-database.md) | 테이블, enum, 인덱스 설계 메모 |
| [06-mvp-roadmap.md](docs/product/06-mvp-roadmap.md) | 1차-A부터 1.5차까지 MVP 릴리스 단위 |
| [07-ideas.md](docs/product/07-ideas.md) | 아이디어 주차장 / 미확정 아이디어 기록 |
| [08-today-execution-loop.md](docs/product/08-today-execution-loop.md) | Today 화면 중심 실행 루프, Todo/ScheduleBlock 역할, 1차-A 사용자 입력 범위 정리 |
| [99-changelog.md](docs/product/99-changelog.md) | 확정 변경 이력 |

문서 수정 기준:

- 미확정 아이디어는 `docs/product/07-ideas.md`에 기록합니다.
- 아이디어가 확정되면 해당 문서와 `docs/product/99-changelog.md`에 반영합니다.
- `docs/product/99-changelog.md`는 확정 변경 이력의 기준 문서입니다.

## 기본 API 요약

| 구분 | 메서드/경로 | 설명 |
| --- | --- | --- |
| Auth | `POST /api/auth/signup` | 회원가입 및 JWT 발급 |
| Auth | `POST /api/auth/login` | 로그인 및 JWT 발급 |
| Users | `GET /api/users/me` | 현재 사용자 기본 정보 |
| Users | `GET /api/users/me/detail` | 현재 사용자 상세 정보 |
| Diaries | `GET /api/diaries` | 일기 목록, 필터, 페이지 조회 |
| Diaries | `GET /api/diaries/search` | 일기 검색 |
| Diaries | `POST /api/diaries` | 일기 작성 |
| Diaries | `GET /api/diaries/{diaryId}` | 일기 상세 조회 |
| Diaries | `PUT /api/diaries/{diaryId}` | 일기 수정 |
| Diaries | `DELETE /api/diaries/{diaryId}` | 일기 삭제 |
| Diaries | `PATCH /api/diaries/{diaryId}/favorite` | 즐겨찾기 토글 |
| Diaries | `GET /api/diaries/{diaryId}/revisions` | 수정 이력 조회 |
| Diaries | `POST /api/diaries/{diaryId}/revisions/{revisionId}/restore` | 수정 이력 복원 |
| Diaries | `GET /api/diaries/statistics/summary` | 일기 요약 통계 |
| Diaries | `GET /api/diaries/statistics/mood` | 기분별 통계 |
| Diaries | `GET /api/diaries/statistics/monthly` | 월별 통계 |
| Diaries | `GET /api/diaries/statistics/streak` | 연속 작성 통계 |
| Todos | `POST /api/todos` | Todo 생성 |
| Todos | `GET /api/todos?date=YYYY-MM-DD` | 날짜별 Todo 목록 |
| Todos | `GET /api/todos/{todoId}` | Todo 상세 조회 |
| Todos | `PUT /api/todos/{todoId}` | Todo 수정 |
| Todos | `PATCH /api/todos/{todoId}/complete` | Todo 완료 처리 |
| Todos | `PATCH /api/todos/{todoId}/uncomplete` | Todo 미완료 처리 |
| Todos | `DELETE /api/todos/{todoId}` | Todo 삭제 |
| Todos | `GET /api/todos/statistics/daily?date=YYYY-MM-DD` | Todo 일별 통계 |
