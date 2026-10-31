# ARCHITECTURE — diary-api 구조 요약

코드에서 관찰한 사실만 적는다. 패키지 경로는 `src/main/java/com/jungwoo/project/memo/` 기준.
엔드포인트는 [API.md](API.md), 표는 [SCHEMA.md](SCHEMA.md), 상태값은 [DOMAIN.md](DOMAIN.md).

## 스택 (build.gradle 기준)

- Java 21 toolchain(Timefold 2.x 요구) · Spring Boot 4.0.1 · Spring Security + JWT(jjwt 0.12, stateless)
  - README는 "Java 17"이라고 적혀 있으나 빌드는 21이다.
- MyBatis(Mapper 인터페이스 + XML, `classpath:mapper/**/*.xml`). JPA 없음.
- MySQL/MariaDB(`mysql-connector-j`). 마이그레이션 도구 없음 — `docs/sql/*.sql` 수동 적용.
- Spring AI 2.0(OpenAI 스타터) · Timefold Solver 2.4(core만) · springdoc-openapi
- 자료 추출: PDFBox, POI(pptx), hwplib/hwpxlib, commons-compress(ZIP), jsoup + httpclient5(교재 웹)

## 레이어와 패키지 경계

기능(도메인) 단위 패키지이고, 패키지 안에서 레이어를 나눈다.

```text
<feature>/
  XxxController     @RestController. 경로·인증 주체(@AuthenticationPrincipal UserPrincipal)·DTO 변환만
  XxxService        업무 규칙·트랜잭션 경계(@Transactional)
  XxxMapper         MyBatis @Mapper 인터페이스 — 같은 패키지에 둔다
  domain/           엔티티·enum(상태값)
  dto/              요청·응답 DTO
  ai/               상담 도구가 이 기능을 조회·변경하는 모듈(ChangeModule, Find*Tool)
resources/mapper/<feature>/XxxMapper.xml
```

| 패키지 | 맡는 것 |
|---|---|
| `auth`, `user` | 회원가입·로그인(JWT 발급), 현재 사용자 |
| `diary` | 일기·수정 이력·통계 |
| `execution` | 실행 조각(ExecutionItem)·수행 기록·조정 이벤트. 실행의 유일한 원본 |
| `plan` | 기간 계획 초안 생성(범위 추출 `scope`, 자료 선택 `selection`, 판정, 근거 `provenance`·`trace`, 시작 도움 `help`) · 확정(PlanVersion) · 회고 · 롤링 배치 |
| `planning`, `orchestration` | planning agent 초안·적용, 추천→계획 묶음 실행 |
| `scheduling` | 가용 시간 추정·Timefold 배치 미리보기(`solver`, `scenario`) |
| `ai` | 상담 대화·턴(`turn`: 도구 루프), 제안(proposal)·draft·합의(`brief`)·변경 묶음(`change`)·사진(`photo`)·일정 이미지(`scheduleimport`)·근거 조회(`evidence`)·학습 기억(`state`, user_contexts) |
| `course` | 프로젝트(과목)·노트, 교재(`textbook`, 웹 목차 검색 `textbook/web`) |
| `learning` | 학습 항목 트리·진행·추천·학습 지도, 자료 정리(`tidy`)·구조 조정(`structure`)·주차(`week`)·정정(`correction`), 학습 이벤트(`events`, 백필·수업 회차) |
| `material` | 자료 저장소·연결·추출(`extract`)·자동 분석(`analysis`)·업로드 묶음(`batch`)·ZIP(`zip`)·연결 제안(`linkproposal`) |
| `assignment`, `commitment`, `routine` | 과제, 1회성 약속, 반복 일정(시간표) |
| `common` | `config`(Security·Jackson·Clock), `exception`(ErrorCode·GlobalExceptionHandler), `security`(JWT 필터), `response`(ErrorResponse) |

### 공통 규칙으로 보이는 것

- **소유권**: 모든 조회·수정이 `user_id` 조건을 단다. 물리 FK는 쓰지 않는다(SCHEMA.md 머리말).
- **시각**: `ClockConfig`가 UTC `Clock` 하나를 빈으로 둔다. `now()` 정적 호출 대신 주입받고, 사용자 시간대(`Asia/Seoul` 기본)는 쓰는 곳에서 변환.
- **오류 응답**: `ErrorResponse{timestamp, code, message, errors?, details?}`. 코드 형식 `E{HTTP}_{번호}`(예 `E409_040`). 예외 계층 `BusinessException` → `BadRequest/NotFound/Conflict/Forbidden/TooManyRequests/ServiceUnavailable…`.
- **동시 수정**: `version` 열 + `WHERE version = #{version}` 갱신, 0행이면 409(`VERSION_CONFLICT`). 상태 전이는 `WHERE status = …` 조건부 갱신이나 `FOR UPDATE` 잠금 뒤 확인.

## 트랜잭션 경계

- 서비스 메서드 단위. `@Transactional` 186 · `readOnly = true` 132 · `propagation = MANDATORY` 24(호출부 트랜잭션 안에서만 도는 하위 쓰기) · `isolation = READ_COMMITTED` 13 · `REQUIRES_NEW` 2.
- `TransactionTemplate`(직접 경계 지정): `PlanDraftService`, `TextbookLookupService`, `TextbookRefSeeder`, `LearningEventBackfill`. 예: `PlanDraftService`는 저장 구간(제안·계획 메타·요청 맥락·옛 초안 폐기)만 템플릿으로 묶고 모델 호출·자료 선택은 그 밖에 둔다.
- 실행 조각 묶음(05-database.md §12): 완료 = 기록 + 상태, 부분 수행 = 기록 + 남은 조각 + 이벤트, 이동·축소·보류·재개·삭제 = 변경 + 이벤트, AI 적용 = version 확인 + 공식 데이터 변경 + 제안 상태. 각각 한 트랜잭션.
- 학습 이벤트 기록기(`learning/events/*Recorder`, `EventSync`)는 `MANDATORY` — 기존 쓰기 경로의 트랜잭션 안에서만 함께 기록된다.

## 요청 흐름

```text
HTTP ─ JwtAuthenticationFilter ─ Controller ─ Service(@Transactional) ─ Mapper(XML) ─ MariaDB
                                                    └─ AI 클라이언트 / Timefold / 웹 수집 (트랜잭션 밖이 원칙)
```

- 상담 턴 `POST /api/ai/conversations/{id}/messages`는 **SSE**(`SseEmitter`, `text/event-stream`). 같은 멱등 키 재전송은 저장된 턴을 재생한다.
- 계획 초안 생성은 동기 요청이고 진행 단계는 `GET /api/plans/draft/progress`로 읽는다.

## 백그라운드 작업 (`@Scheduled` 폴러)

| 스케줄러 | 대상 표 | 끄는 설정 |
|---|---|---|
| `material/analysis/MaterialAnalysisScheduler` | `material_analysis_jobs` (임대 lease, 동시성 2, 일일 한도) | `material.analysis.worker.enabled` |
| `learning/tidy/ProjectTidyScheduler` | `project_tidy_jobs` | `project.tidy.worker.enabled` |
| `course/textbook/web/TextbookLookupScheduler` | `textbook_lookups` (일일 호출 한도) | `textbook.lookup.worker.enabled` |
| `material/zip/MaterialZipImportScheduler` | `material_zip_imports` | `material.zip-import.worker.enabled` |
| `ai/photo/PhotoOriginalCleanupScheduler` | 상담 사진 원본 만료 삭제(1시간 주기) | `consult-photo.cleanup.enabled` |

테스트(`build.gradle` test task)는 위 worker를 전부 끈다. 작업은 DB 행(QUEUED → 임대 → 결과)으로 주고받고, 여러 서버가 같은 DB를 봐도 lease로 한 곳만 집는다.

## 외부 연동 지점

### AI provider (OpenAI, Spring AI)

- 설정: `spring.ai.model.chat=${AI_PROVIDER:none}`. `none`이면 부팅은 되고 AI 경로만 `503 AI_NOT_CONFIGURED`.
- **`ai/AiConsultationClient`**(인터페이스) → `OpenAiConsultationClient`가 유일한 텍스트 모델 경계. `streamTurn(system, user, maxTokens, model)`이 `Flux<ChatResponse>`를 돌려준다. 이 인터페이스를 주입받는 곳: 상담(`AiConversationService`, `AiTurnLifecycleService`, `UserMemoryExtractor`), 계획(`PlanDraftService`, `PeriodPlanDraftGenerator`, `PlanJudgmentService`, `PlanScopeExtractor`, `PlanMaterialSelector`, `PlanItemService`, `PlanItemDetailService`, `StartHelpService`), 자료(`MaterialAnalysisService`, `MaterialContentAnalyzer`, `TopicLinkAnalyzer`, `MaterialLinkProposalService`), 학습(`LearningConversationService`, `LearningRecommendationService`, `ProjectTidyAnalyzer`, `StructureRequestService`), 교재(`TextbookModelAssist`), `PlanningAgentService`.
- `ChatClient`를 직접 쓰는 곳: `ai/turn/ConsultToolLoop`(상담 도구 호출 루프), `ai/photo/TextbookPhotoReader`·`ai/scheduleimport/ScheduleImageVisionClient`(이미지 입력).
- `course/textbook/web/BookWebSearchClient`: OpenAI Responses API `web_search` 도구로 서점 페이지 **주소 후보만** 얻는다. 교재 단서만 보내고, 책 일치·목차는 서버가 `SafePageFetcher`(공인 IP만 허용)로 받아 직접 판정.
- 사용량: `ai_usage_logs` 기록, `ai.usage.daily-limit`/`monthly-limit`. 모델명·토큰 상한은 `application.properties`의 `ai.*`.
- 상담 도구 단계(`ai/turn`): `ConsultTool` 구현(`ProposeChangeTool`, `PreviewAvailabilityTool`, `SetStudyTimeTool`, `WithdrawChangeTool`, 기능별 `Find*Tool`)이 조회와 **적용 전 변경 묶음**(`ai_change_bundles`)만 만든다. 공식 데이터는 사용자가 묶음을 적용할 때 `ChangeModule`(기능별 `*/ai/*ChangeModule`)이 바꾼다. 단계는 `consult_turn_steps`에 남는다.

### Timefold Solver

- `scheduling/solver/SchedulingSolverService` + `SchedulingConstraintProvider`. 상시 `SolverManager` 없이 **요청 시 동기로 짧게**(`scheduling.solver.timeout-seconds` 기본 5초) 푼다.
- 입력: 가용 시간(반복 일정·약속·고정 실행·고정 제안 항목을 바쁜 칸으로), 지평 최대 7일, 15분 칸, 하루 09:00~24:00 기본.
- 결과는 `ai_proposal_schedule_previews`에 저장(제안당 1, 새로고침 복원). 롤링 배치(`POST /api/plans/{id}/place`)는 확인 없이 적용, `unschedule`로 되돌린다.

### 파일 저장

- 자료 원본은 로컬 디스크 `storage.materials.upload-dir`(기본 `./data/materials`), 최대 20MB. 본문은 `material_text_units`·`material_sections`로 DB에 남는다.

## 더 볼 곳

- 계획 생성 단계별 설계: `docs/product/11-period-plan.md`, `13-plan-judgment.md`, `21-plan-intent-preservation.md`
- 자료 분석 파이프라인: `docs/product/15-material-auto-analysis.md`
- 학습 이벤트: `docs/product/20-learning-events.md`
- 상담 도구·변경 묶음: `docs/handoff/consult-tools-2026-10-26.md`(product 기준 문서 아직 없음)
