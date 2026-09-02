# diary-api

`diary-api`는 GPT와 나눈 고민·목표·제약을 편집 가능한 계획으로 바꾸고, 사용자가 승인한 내용만 실제 실행과 기록에 연결하는 개인 운영 웹 애플리케이션의 백엔드입니다.

이 저장소에는 현재 동작하는 일기·실행 조각·프로젝트·자료·AI 상담 API와 제품 문서가 함께 있습니다.

## 제품의 핵심 흐름

```text
자연어 상담
→ 목표·결정·제약·계획 후보 추출
→ 화면에 편집 가능한 초안 표시
→ 사용자가 직접 수정하고 선택 적용
→ 오늘 실행 조각과 시간표에 반영
→ 실제 수행 결과와 조정 사건 기록
→ 다음 상담과 계획에 재사용
```

최상위 사용자 화면은 `오늘 · 계획 · 실행 · 기록` 네 영역으로 구성합니다. AI 상담 패널은 모든 영역에서 현재 선택 항목과 관련 컨텍스트를 받아 제안을 만들지만, 공식 데이터는 사용자가 `적용`하기 전까지 바뀌지 않습니다.

대학생의 과목·학기·방학 계획은 첫 번째 주요 사용 사례이며, 제품 전체를 학습 플래너로 제한하지 않습니다.

## 현재 구조

```text
execution_items            실제로 배치·조정하는 실행 조각. 실행의 유일한 원본
execution_records          실제 수행 결과
execution_item_events      이동·축소·보류·분할 같은 조정 사건
courses / course_topics    프로젝트와 학습 목차
materials / material_links 자료 원본과 프로젝트 연결
context_items              다음 상담에 재사용할 장기 컨텍스트
ai_proposals/items         적용 전 AI 초안과 사용자 반응
```

`Todo`와 `ScheduleBlock`의 `execution_items` 통합은 완료되었습니다. 2026-08-03에 데이터를 이관하고(출처는 `legacy_execution_item_map`에 보존), 2026-08-23에 `todos` / `schedule_blocks` / `plan_items` 계열 / `daily_plans` 테이블과 코드를 제거했습니다. `docs/sql/2026-08-23-remove-legacy-tables.sql`과 백업 `docs/sql/backup/2026-08-23-legacy-tables.sql`을 참고하세요.

하루 단위 설정(화면 모드·강도·컨디션)은 현재 저장하지 않습니다. 필요해지면 `day_settings`로 새로 만듭니다.

## 기술 스택

- Java 17
- Spring Boot 4.0.1
- Spring Security, JWT
- MyBatis Mapper/XML
- MySQL 또는 MariaDB
- Gradle
- springdoc-openapi

프런트엔드는 [diary-ui](https://github.com/qlqb/diary-ui)의 React + Vite + Tailwind CSS 구성을 사용합니다. JPA를 전제로 설계하지 않습니다.

## 지금 구현된 것 (2026-08-04)

위 목표 구조 중 아래는 이미 코드로 구현되어 있습니다. 나머지(ContextItem, 외부 대화 가져오기, ghost/diff, 캘린더 등)는 여전히 목표 설계입니다.

- 회원가입/로그인: `/api/auth/signup`, `/api/auth/login`
- 현재 사용자 조회: `/api/users/me`, `/api/users/me/detail`
- 일기 CRUD, 검색, 즐겨찾기, 수정 이력, 통계
- ExecutionItem(실행 조각): Today/Execution 화면의 공식 실행 원본. 조회/생성/완료/재열기/이동/축소/보류/삭제
- AI 오늘 제안: Spring AI(OpenAI) 기반 자연어 상담 → 오늘 실행 후보 생성 → 편집 → 묶음 전체 적용 (`ai_proposals`/`ai_proposal_items`)
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

Windows PowerShell 예시:

```powershell
$env:JWT_SECRET="your-jwt-secret"
$env:DB_URL="jdbc:mysql://localhost:3306/memo?serverTimezone=Asia/Seoul&characterEncoding=UTF-8"
$env:DB_USERNAME="your-db-username"
$env:DB_PASSWORD="your-db-password"
```

로컬 MySQL(또는 MariaDB)에 `memo` 데이터베이스를 만든 뒤 `docs/sql/`의 마이그레이션을 날짜 순으로 수동 적용하고 실행하세요. Flyway/Liquibase는 도입하지 않습니다.

AI 오늘 제안 기능은 다음 환경변수를 선택적으로 사용합니다. 설정하지 않으면(`AI_PROVIDER` 기본값 `none`)
서버는 정상 부팅되고, 제안 생성 호출만 `503 AI_NOT_CONFIGURED`를 반환합니다. API 키는 코드나 Git에 커밋하지 마세요.

```powershell
$env:AI_PROVIDER="openai"
$env:OPENAI_API_KEY="your-openai-api-key"
$env:OPENAI_MODEL="gpt-5-mini"
```

## 실행과 테스트

```bash
./gradlew bootRun
./gradlew test
```

Windows에서는 `./gradlew` 대신 `.\gradlew.bat`을 사용할 수 있습니다.

## 인증 방식

회원가입 또는 로그인 응답의 `token`을 이후 요청에 Bearer 토큰으로 전달합니다.

```http
Authorization: Bearer {token}
```

## 문서 지도

| 문서 | 책임 |
| --- | --- |
| [01-product-plan.md](docs/product/01-product-plan.md) | 제품 문제, 핵심 경험, 성공 기준 |
| [02-feature-structure.md](docs/product/02-feature-structure.md) | 네 탭과 AI 패널의 책임, 화면 데이터 관계 |
| [03-planning-system.md](docs/product/03-planning-system.md) | 계획·실행·기록·이벤트·컨텍스트의 흐름 |
| [04-requirements.md](docs/product/04-requirements.md) | 최신 구현 요구사항과 상태 불변조건 |
| [05-database.md](docs/product/05-database.md) | 목표 테이블, 관계, 마이그레이션 기준 |
| [06-mvp-roadmap.md](docs/product/06-mvp-roadmap.md) | 현재 코드에서 목표 구조로 가는 구현 순서 |
| [07-ideas.md](docs/product/07-ideas.md) | 아직 확정되지 않은 아이디어 주차장 |
| [08-today-execution-loop.md](docs/product/08-today-execution-loop.md) | 오늘과 실행 화면의 경계 및 당일 조정 흐름 |
| [99-changelog.md](docs/product/99-changelog.md) | 확정 변경 이력과 현재 기준 |

`docs/product` Markdown이 제품 설계의 진실의 원천입니다. `자기관리앱_기획서_v2.1.docx`는 2026-07-05 시점의 과거 스냅샷이며, 충돌 시 2026-08-03 Markdown 기준을 따릅니다.

## 문서 수정 규칙

- 확정된 변경은 관련 문서와 `99-changelog.md`를 함께 수정합니다.
- 미확정 아이디어는 `07-ideas.md`에만 기록합니다.
- 화면 이름, 데이터 이름, 상태값을 문서마다 다르게 정의하지 않습니다.
- 현재 구현과 목표 구조를 섞어 이미 구현된 것처럼 쓰지 않습니다.
- 실제 사용자 데이터가 포함된 SQL 덤프는 저장소에 커밋하지 않습니다.

## 지금 구현된 API

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
| ExecutionItems | `GET /api/execution-items?date=YYYY-MM-DD` | 날짜별 실행 조각 조회 |
| ExecutionItems | `GET /api/execution-items/pending?beforeDate=YYYY-MM-DD` | pending 조회 |
| ExecutionItems | `POST /api/execution-items` | 실행 조각 생성 |
| ExecutionItems | `POST /api/execution-items/{id}/complete` | 완료 (execution_record 동시 생성) |
| ExecutionItems | `POST /api/execution-items/{id}/reopen` | 재열기 |
| ExecutionItems | `POST /api/execution-items/{id}/move` | 이동 |
| ExecutionItems | `POST /api/execution-items/{id}/reduce` | 축소 |
| ExecutionItems | `POST /api/execution-items/{id}/hold` | 보류 |
| ExecutionItems | `DELETE /api/execution-items/{id}?version=` | 삭제 (soft delete) |
| AI Proposals | `POST /api/ai/proposals` | 오늘 실행 제안 생성 |
| AI Proposals | `GET /api/ai/proposals/{id}` | 제안 조회 |
| AI Proposals | `POST /api/ai/proposals/{id}/apply` | 제안 묶음 전체 적용 |
