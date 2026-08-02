# diary-api

`diary-api`는 GPT와 나눈 고민·목표·제약을 편집 가능한 계획으로 바꾸고, 사용자가 승인한 내용만 실제 실행과 기록에 연결하는 개인 운영 웹 애플리케이션의 백엔드입니다.

이 저장소에는 현재 동작하는 일기·Todo·ScheduleBlock API와, 이를 최신 실행 모델로 전환하기 위한 제품 문서가 함께 있습니다.

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

## 현재 구현과 목표 구조

현재 코드에는 `todos`, `schedule_blocks`, `daily_plans`, `plan_item_events` 기반 기능이 남아 있습니다. 최신 제품 문서는 다음 목표 구조를 기준으로 합니다.

```text
plan_items                 앞으로 하려는 의도와 범위
execution_items            실제로 배치·조정하는 실행 조각
execution_records          실제 수행 결과
execution_item_events      이동·축소·보류·분할 같은 조정 사건
context_items              다음 상담에 재사용할 장기 컨텍스트
daily_states               날짜별 컨디션과 하루 운영 상태
ai_proposals/items         적용 전 AI 초안과 사용자 반응
```

`Todo`와 `ScheduleBlock`은 장기적으로 `execution_items`로 통합합니다. 두 레거시 구조에 동시에 쓰는 방식은 사용하지 않으며, 신규 구조로 데이터를 이전하고 MyBatis 조회·수정 코드를 전환한 뒤 레거시 쓰기를 중단합니다.

## 기술 스택

- Java 17
- Spring Boot 4.0.1
- Spring Security, JWT
- MyBatis Mapper/XML
- MySQL 또는 MariaDB
- Gradle
- springdoc-openapi

프런트엔드는 [diary-ui](https://github.com/qlqb/diary-ui)의 React + Vite + Tailwind CSS 구성을 사용합니다. JPA를 전제로 설계하지 않습니다.

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
