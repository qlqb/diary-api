# diary-api

`diary-api`는 자료·상담·실행 기록으로 사용자의 현실(시간·진도·막힘)을 모으고, 그 위에서 AI가 기간 학습 계획 초안을 만들며, 사용자가 확인한 것만 실제 실행과 기록에 연결하는 개인 운영 웹 애플리케이션의 백엔드입니다. 제품 가설은 [12-product-thesis.md](docs/product/12-product-thesis.md)를 봅니다.

이 저장소에는 현재 동작하는 일기·실행 조각·프로젝트·자료·AI 상담 API와 제품 문서가 함께 있습니다.

## 제품의 핵심 흐름

```text
자료 업로드·자동 분석 + 상담(사진 포함) + 학습 기억·학습 이벤트
→ 요청 범위·말한 시간을 원문과 대조해 확정
→ 기간 계획 초안(PlanVersion) 미리보기 — 말한 범위가 어떻게 담겼는지 서버 판정으로 표시
→ 사용자가 확정
→ 실행 조각(ExecutionItem)으로 오늘·일정에 배치
→ 수행 기록·조정 사건 기록
→ 다음 상담과 계획에 재사용
```

최상위 사용자 화면은 `오늘 · 자료 · 프로젝트 · 계획 · 일정 · 기록` 여섯 탭입니다(diary-ui `src/shell/MainShell.jsx`). AI 상담 패널은 모든 탭에서 열리지만, 계획·일정 같은 공식 데이터는 사용자가 확정·적용하기 전까지 바뀌지 않습니다.

관리 단위는 프로젝트입니다. 대학생의 과목·학기 계획이 첫 번째 사용 사례이고, 개인 개발·자격 준비도 같은 흐름을 씁니다.

## 현재 구조

```text
courses / course_topics          프로젝트와 학습 목차(교재 목차·하위항목 포함)
materials / material_links       자료 원본과 프로젝트 연결, 자동 분석·자료 구간
plan_versions                    기간 계획(초안·확정·회고). 항목은 스냅샷으로 보관
execution_items                  실제로 배치·조정하는 실행 조각. 실행의 유일한 원본
execution_records                실제 수행 결과
execution_item_events            이동·축소·보류·분할 같은 조정 사건
user_contexts                    상담에서 기억한 사실(진도·시험 범위·막힘·해결 등)
ai_plan_briefs                   상담 합의(작성 주체·근거·승인)
learning_events / class_sessions 원천을 가리키는 학습 이벤트와 수업 회차
routines / one_off_commitments   시간표와 일회성 약속
ai_proposals/items               적용 전 AI 제안과 사용자 반응
```

테이블 상세는 [05-database.md](docs/product/05-database.md), 기간 계획의 네 층은 [11-period-plan.md](docs/product/11-period-plan.md)를 봅니다.

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

## 구현 범위

기능별 현재 상태는 설계 문서와 [99-changelog.md](docs/product/99-changelog.md)가 기준입니다. 큰 덩어리만 적으면 다음과 같습니다.

- 인증·사용자·일기(수정 이력·통계)
- 실행 조각(완료·부분 수행·재열기·이동·미배정·축소·보류·재개·삭제·복원)과 수행 기록
- 프로젝트·자료(업로드·ZIP 가져오기·자동 분석·자료 구간·토픽 연결 변경안·프로젝트 정리·자료 주차)
- 교재 목차 웹 검색, 학습 지도, 학습 기억, 학습 이벤트·수업 확인
- AI 상담(자료 근거 조회·사진 첨부·일정 이미지 가져오기·합의 정리)
- 기간 계획 생성(요청 범위 추출·자료 선택·판정·배치)과 확정·회고, 근거 추적
- 시간표(루틴)·일회성 약속·과제

## 실행 전 준비

`src/main/resources/application.properties`는 DB 접속 정보와 JWT 시크릿을 환경변수로 읽습니다.

```properties
jwt.secret=${JWT_SECRET}
jwt.expiration=${JWT_EXPIRATION:3600000}
spring.datasource.url=${DB_URL}
spring.datasource.username=${DB_USERNAME}
spring.datasource.password=${DB_PASSWORD}
server.port=${SERVER_PORT:8081}
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

설계 문서 목록과 읽는 순서는 [docs/product/README.md](docs/product/README.md)가 기준입니다. API는 [docs/api-spec.md](docs/api-spec.md)(계약의 의미와 날짜별 추가 필드)와, 서버를 띄운 뒤 `/v3/api-docs.yaml`(springdoc 자동 생성, 전체 엔드포인트)을 봅니다.

`docs/product` Markdown이 제품 설계의 진실의 원천입니다. 완료된 작업 기록은 `docs/handoff/`에 있습니다.

## 문서 수정 규칙

- 확정된 변경은 관련 문서와 `99-changelog.md`를 함께 수정합니다.
- 미확정 아이디어는 `07-ideas.md`에만 기록합니다.
- 화면 이름, 데이터 이름, 상태값을 문서마다 다르게 정의하지 않습니다.
- 현재 구현과 목표 구조를 섞어 이미 구현된 것처럼 쓰지 않습니다.
- 실제 사용자 데이터가 포함된 SQL 덤프는 저장소에 커밋하지 않습니다.
