# 프로젝트 기반 AI 상담·계획·실행 통합 — 작업 기록 (2026-09-19)

구현 지시: "Claude Code handoff — 프로젝트 기반 AI 상담·계획·실행 통합"(2026-09-19, v1).
이 문서는 그 지시의 baseline 기록과 구현·검증 결과, 명세와 다른 결정을 남긴다. 계약 본문은 제품 문서(11·13·15번,
05번, api-spec)에 있고 여기서는 가리키기만 한다.

## 0. Baseline (작업 시작 시점에 확인한 사실)

| 항목 | 값 |
|---|---|
| 저장소 | `qlqb/diary-api`, `qlqb/diary-ui` (origin = github) |
| 기준 브랜치 | 두 저장소 모두 **로컬** `dev-playground` — API `2e8f4a6`, UI `6531377`. origin/dev-playground보다 API 19커밋·UI 13커밋 앞(푸시 안 됨) |
| 기준 선택 이유 | 자료 선택(`feat/ai-material-selection`)·자료 형식/ZIP(`feat/zip-import-file-types`)·상담 연결(`feat/ai-plan-connection`)이 모두 합쳐진 유일한 위치. 원격의 같은 이름 브랜치는 옛 위치라 최신으로 가정하지 않았다 |
| 작업 브랜치 | `feat/project-consult-workspace` (두 저장소), worktree `wt/diary-api-consult`·`wt/diary-ui-consult` |
| 실행 중이던 서버 | 8080 = 사용자 IntelliJ(메인 작업트리 `diary-api`, devtools), 5173 = 사용자 vite. **건드리지 않았다** |
| 검증 서버 | API 8081(`.claude/launch.json`의 `diary-api-consult`), UI 5174(`diary-ui-consult`, `--mode claude`) |
| 검증 DB | **`memo_consult`** — `memo`의 스키마만 복제한 빈 DB(데이터 0행). `memo`(test@gmail.com 등 사용자의 수동 테스트 데이터가 있는 DB)에는 읽기·쓰기·마이그레이션을 하지 않았다. 업로드 디렉터리도 worktree 안 `./data/materials`라 분리된다 |
| 저장소 지침 | 두 저장소에 CLAUDE.md 없음. README·`docs/product/README.md`의 문서 규칙을 따랐다 |

격리 DB를 쓴 이유 두 가지: (1) 8080의 분석 worker가 같은 DB를 보면 검증용 업로드를 가져가 분석한다(2026-09-16에 실제로 겪음),
(2) 실사용 DB에 테스트를 돌리지 않는다는 지시.

### 과거 추적 단서의 취급

제안 4041/4122/4123·user 999000670·367구간/156구간은 `memo` DB에 있는 사용자의 수동 테스트 계정 데이터다. 이번 작업은
`memo`에 접속하지 않았으므로 **그 수치를 다시 확인하지 않았다.** 대신 코드에서 원인을 확정하고 합성 fixture로 재현했다.

| 보고서의 주장 | 코드에서 확인한 것 |
|---|---|
| "156구간만 보였고 뒤 프로젝트 셋은 목록에 못 나왔을 가능성(추정)" | **원인 확정.** `PlanMaterialSelector.expandRound`가 펼친 묶음을 모델이 적은 순서대로 앞에서부터 채우고 예산이 차면 `break outer`였다. 뒤 묶음은 0줄. 재현: 프로젝트 8개×구간 40개·선택 예산 5,000토큰의 합성 fixture를 기준 코드(`0005b98`, 선택기는 `2e8f4a6`과 같다)에서 돌리면 펼친 목록에 **프로젝트 1·2만** 실린다(`BASELINE_LISTED=[1, 2]`). 수정 뒤에는 정순·역순·셔플 모두 8개 전부(`ProjectCoverageFlowTest.T01`) |
| 그 수치를 검증할 기록이 없음 | **확정.** 선택 호출의 입력 전문·줄로 실린 id를 저장하지 않았다(`MATERIAL_SELECTION` 계산의 lineage=PARTIAL, 주석에 명시) |
| 추가 읽기 9건 전부 거절 | **원인 확정.** `plan.draft.max-normal-calls=3`인데 접힘 모드의 선택이 항상 2회를 써서 계획 1회 뒤 남는 호출이 0 |
| 강의계획서 일정이 계획 입력에 없음 | **확정.** `courseScheduleLines`가 옛 `course_material_analyses.keyDates`만 읽었다. 자동 분석의 날짜 후보(`material_sections.date_candidates_json`)는 아무도 읽지 않았다 |
| 일일 분석 상한이 CONTENT+LINK 합산 | **확정.** `MaterialAnalysisJobMapper.countStartedSince`에 `job_kind` 조건이 없다 |
| "학습 목표 705분" 안내 | **확정.** `AiConversationService.periodPlanReply`가 예산만 말하고 실제 항목 합·가정 여부·빠진 프로젝트를 말하지 않았다 |
| 일정 미리보기 솔버 2회 호출 | **확정.** `useProposalDraft.placeUnscheduled`와 `PlanDraftReview` mount effect가 각각 `schedulePreviewAPI`를 부른다 |
| "일정 조회 오류가 일정 없음으로 바뀐다" | **해당 없음.** `AvailabilityEstimateService.estimate`에 예외를 삼키는 곳이 없다 — 조회가 실패하면 생성이 실패한다. 회귀 테스트로 고정(T13) |
| 3주차/4주차, RDS '꼭', 웹서버 근거 없음, 파이썬 기초 분리 | 지시 §9에 따라 **버그로 단정하지 않았다.** 구현은 "자료 번호≠주차", "후보 일정≠확정 일정", "구조 변경은 미리보기 후 적용"의 일반 계약만 다룬다 |

### 이미 있어서 재사용한 것(중복 구현하지 않음)

provenance(`plan_provenance_json`·`evidence_json`), 요청 키·`PLAN_DRAFT_IN_PROGRESS`, 저장 초안 재조회(`GET /plans/proposals/{id}/draft`),
SSE 진행(`period_plan.progress`)·폴링(`/plans/draft/progress`), 상담 합의(`ai_plan_briefs`), 사용자 맥락(`user_contexts` +
변경 제안), 실행 근거(`ExecutionEvidenceService`), 검토 상태 낙관적 잠금(`review_state_json`), 늦은 결과 보호(`persistSuperseding`).

## 1. 구현한 것 — 지시 §14·§15 대응

| 지시 | 구현 | 위치 |
|---|---|---|
| §5.1·§14.3 전체 범위 탐색, 프로젝트별 결과 | 공정한 펼치기, `strategy.projects`(처리 결과 × 자료 상태), 서버 검증 | `PlanMaterialSelector.expandRound`·`exposure`, `ProjectOutcomeResolver`, `ProjectOutcome` |
| §5.2·§14.4 조회 예산 | 호출 수 + 입력 토큰 합 + 경과 시간, 선택적 호출은 최종 계획 호출을 남길 때만, 추가 읽기 실패는 첫 초안으로 | `GenerationBudget`, `PeriodPlanDraftGenerator.generate` |
| §6 실제 전달 기록 | 호출별 입력 전문·줄로 실린 id·원문 실린 id·프로젝트별 집계·서버 커밋, 자료 삭제 시 전문 삭제 | `plan/trace/*`, `BuildVersion`, `GET /plans/drafts/{id}/trace` |
| §4.1·§14.2 범위·깊이·시간 | 강도 필수 질문 제거, 합의 `DEPTH`·`TIME_BUDGET`, 말한 시간이 초안까지 유지 | `OpenAiConsultationClient` 원칙 21, `PlanBriefService.timeBudgetOf`, `fitToUserTime` |
| §4.2·§3.2 질문 실험 | `consult.question`(선택지는 제안, 다중 선택), 빠른 답은 저장된 선택지에서만, `PLAN_NOW`, 반복 확인 금지 | `ai/consult/*`, 원칙 26, `AiConversationService.normalizeConsultRequest` |
| §4.3·§3.7 사용자 상태의 근거 | 근거 유형·적용 범위·철회, 자동 저장(인용 확인), 고치기·확인·지우기, 점검 활동 | `UserContextService`, `/api/contexts`, `/courses/{id}/self-checks` |
| §4.4 운영 정보 구분 | 구간 역할 기반(ADMIN/SCHEDULE뿐이면 미룬 범위로), 혼합 구간은 통과, 원문은 입력에 남김 | `PlanResultNormalizer.citesOnlyOperationalInfo`, 계획 프롬프트 |
| §4.5·§14.5 일정·주차 | 자동 분석의 날짜 후보를 "일정 후보(미확정)"로, 자료 번호≠주차 | `sectionScheduleCandidates`, 계획 프롬프트 |
| §5.3·§14.5 승인 전 트리 | 읽기 전용 색인(옛 해시·삭제 자료 제외, 같은 자리 같은 제목만 합침), 쓰기 없음 | `ProposedTopicIndex`, `PlanMaterialContextService.proposedGroups` |
| §3.5 전체 학습 지도 | 주제가 뿌리, 제안 미리보기, 미연결 자료, 자료가 말한 주차, 다섯 상태 | `LearningMapService`, `GET /courses/{id}/learning-map` |
| §3.6·§14.5 분석 상태·`.sh` | 한도 분리, 대기열 앞막힘 수정, 대기 이유·재개 시각, `.sh` 텍스트 읽기 | `MaterialAnalysisJobService.claimNext`, `MaterialAnalysisStatusService`, `PlainTextExtractor` |
| §7 초안·적용·보존 | 최신성(서버 기록 비교), 다시 만들기의 편집 보존·충돌, 항목의 선정 이유·출처 유형 | `PlanDraftService.freshnessOf`·`carryReviewState`·`withItemReasons`, `ReviewStateCarryOver` |
| §3.7 실행 결과 재사용 | 막힌 이유를 다음 상담·계획 근거로, 적은 시간≠측정, 미기록은 미기록 | `execution_records.blocker_kind`, `ExecutionEvidenceService` |
| §3 화면 | diary-ui `docs/reviews/project-consult-workspace-completion.md` | — |

## 2. 고정 응답 회귀 — 지시 §16.1 대응

| ID | 테스트 | 상태 |
|---|---|---|
| T01 | `ProjectCoverageFlowTest.T01_*`(정순·역순·셔플, 일부만 펼침) | 통과 |
| T02 | `ProjectCoverageFlowTest.T01_펼치기_요청이_일부뿐…`(모델이 3번 누락), `ProjectOutcomeResolverTest` | 통과 |
| T03 | `ProjectCoverageFlowTest.T03_*`, `ProjectOutcomeResolverTest.조회_실패와_분석_대기…` | 통과 |
| T04 | `ConsultConditionsFlowTest.T04_*` 2건(읽기 전용·옛 해시/삭제 제외·중복 합침·쓰기 없음) | 통과. LINK 대기 중 계획 생성은 "토픽 0 + 제안만" 상태의 실호출 s1로 확인 |
| T05 | `ProjectCoverageFlowTest.T05_*` | 통과 |
| T06 | `ProjectCoverageFlowTest.T06_*` 2건 + 기존 `PlanMaterialSelectionFlowTest`(없는·타 사용자 id) | 통과. 구버전 인용은 기존 해시 비교 경로 |
| T07 | `ConsultConditionsFlowTest.T07_*` 2건, `OpenAiConsultationClientTest`(프롬프트), `AiConversationServiceTest.auto_periodPlanOfferWithoutIntensity_*` | 통과. "목표 변경 뒤 모호한 조건만 확인"은 프롬프트 규칙이라 실호출로만 본다 |
| T08 | `UserContextServiceTest`(직접 진술/자기평가/추정/관찰 구분, 점검 활동), 실호출 s3(실행 항목 완료 쓰기 없음) | 통과 |
| T09 | `UserContextServiceTest.T09_*`·고치기·충돌 | 통과. 프롬프트의 `[사용자가 지운 기억]` 블록은 단위 테스트 없음 |
| T10 | `ReviewStateCarryOverTest`, `PlanDraftServiceTest.freshness_*`, 기존 `PlanRedraftServiceTest`(늦은 결과·중복 요청), UI `useConsultDraft.test.jsx` | 통과. 생성 **중 취소**는 새로 만들지 않았다(기존 진행 상태 STALE 처리만) |
| T11 | `withItemReasons`가 저장 직후·재조회 공통 경로. `strategy.projects`는 strategy JSON에 저장돼 세 응답 경로가 같은 값을 읽는다 | **전용 테스트 없음** — 실호출에서 재조회 응답의 `selectionReason`·`origin`·`projects`를 확인 |
| T12 | 기존 `PlanDeadlineChainIntegrationTest`·`PlanConfirmAndPlacementIntegrationTest`(DB). 일정 후보는 `deadlineRefId` 대상이 아님(프롬프트·`deadlineFacts` 미등록) | 기존 통과, 후보 자동 확정 금지는 구조로 보장 |
| T13 | `ConsultConditionsFlowTest.T13_*` | 통과. "과거 시간 배치 금지"는 기존 테스트 |
| T14 | — | **새 테스트 없음.** 기존 id·기록 보존은 기존 `TopicChangeProposalApplyDbTest`(적용 시 CAS)와 "전면 재생성 없음" 구조에 의존. 개정판·순서 뒤바뀜의 전용 회귀는 남은 일 |
| T15 | `PlainTextExtractorTest`, `ZipArchiveReaderTest`(.sh + 미지원 공존) | 통과 |
| T16 | `MaterialAnalysisDailyLimitTest` 3건 | 통과 |
| T17 | 기존 `ExecutionEvidenceServiceTest`(미기록≠예정) + 실호출 s6(프롬프트에 "실제 시간 미기록"·"개념에서 막혔다") | 통과 |
| T18 | 기존 `EvidenceFingerprintTest`·`PlanConnectionFlowTest.T27`(선택 재사용) | 기존 통과. 선택 캐시와 배치 캐시는 원래 분리돼 있다(배치는 매번 계산) |

전체 게이트 결과는 §4.1.

## 3. 명세와 다르게 한 것

1. **모델이 프로젝트 결과를 빠뜨렸을 때 수정 호출을 하지 않는다.** 지시는 "남은 예산 안에서 수정을 요청할 수 있다"였다. 수정 호출은
   계획 호출 전체(≈17k 토큰·25초)라, 그 예산을 추가 읽기에 남기고 서버가 NOT_REVIEWED로 명시하는 쪽을 택했다.
2. **TIME_BUDGET의 분을 별도 필드가 아니라 합의 문장의 꼬리표로 저장한다**(`(쓸 수 있는 시간 60분 · 계획 전체)`). `PlanBriefItem`
   레코드(생성자 호출부 22곳)를 건드리지 않고, 문장과 값이 어긋날 수 없게 하려는 선택이다. 사용자에게도 그대로 보인다.
3. **`confirmed` 주차·`DATE_CANDIDATE` 기반 주차는 만들지 않았다.** 주차는 자료가 스스로 말한 표기만이다(계약 문서의 `basis`에는 값이 하나뿐).
4. **OBSERVED 근거는 아직 아무도 만들지 않는다.** 근거 유형과 화면 묶음은 있지만, 실행 기록에서 "독립 수행 관찰"을 뽑아 저장하는 서버
   경로는 없다 — 실행 기록은 지금처럼 `[실행 기록]` 블록으로 매번 직접 읽힌다.
5. **"못 했음" 기록 경로는 추가하지 않았다**(기존에도 없음). 막힌 이유는 완료·부분 수행에만 붙는다.
6. **자료의 쪽·슬라이드·셀로 곧장 이동하지 않는다.** 위치 안내 + 파일 열기다(기존 제약 그대로).
7. **이미 생성된 운영 정보 학습 항목의 정리 제안은 만들지 않았다.** 새로 생기는 것을 막았을 뿐이다. 기존 노드는 그대로다(대량 삭제 금지).
8. **프로젝트 분리·통합 제안의 전후 미리보기는 새로 만들지 않았다.** 기존 `TopicChangeProposalCard`의 MERGE/SPLIT 표시와 학습 지도의
   읽기 전용 미리보기까지다.

## 4. 검증 결과

### 4.1 게이트

| 명령 | 결과 |
|---|---|
| `./gradlew test` (DB 테스트 포함, 격리 DB `memo_consult`) | **1,170개 · 실패 0 · 건너뜀 2** (API `da991f4`) |
| `./gradlew test -PexcludeDbTests` | 974개 · 실패 0 |
| diary-ui `npm test` / `npm run lint` / `npm run build` | 570개 통과 / 0 / 성공 (UI `bc5078a`) |

### 4.2 실제 모델 평가 (2026-09-19, 상담 `gpt-5.6-terra`, 선택·계획 `gpt-5.6-luna`, 서버 8081 + `memo_consult`)

스크립트 `scripts/ai-baseline/verify-project-consult-2026-09-19.py`. 자료는 전부 스크립트가 만든 합성 노트북(.ipynb)·스크립트(.sh)다 —
**사용자의 실제 자료로는 평가하지 않았다.** 시작 전에 정한 상한: 계획 생성 14회·상담 턴 45회. 실제 사용: **계획 생성 14회(상한 소진)**,
상담 턴 33회. 계획 생성 13회분의 서버 계측 합: 입력 139,411 · 출력 30,462 토큰, 회차당 9.3~26.8초(평균 16.8초). 첫 s4 1회
(입력 8,258)를 더하면 입력 ≈147.7k. **상담 턴과 자료 분석(CONTENT 26건·LINK 26건 안팎) 호출의 토큰은 집계하지 않았다**(API로 노출되지
않고 DB에 직접 붙지 않았다). 금액은 확인할 수 없어 적지 않는다.

| 시나리오 | 실행 | 결과 | 관찰 |
|---|---|---|---|
| s1 신규 사용자·5개 프로젝트·학습 항목 0·제안 승인 전·전체 훑기 | 2회 | PASS ×2 | 5개 프로젝트 모두 INCLUDED·TEXT_DELIVERED(후보 7~10개 전부 목록 전달, 원문 3~6구간). 운영 안내 항목 0(강의계획서 포함). 추가 질문 없이 `PLAN_NOW`로 진행. **항목당 55~70분·합계 315~360분으로 "훑어보기"치고 무겁다** → 프롬프트에 깊이-분량 규칙을 넣었으나 상한 소진으로 **재확인하지 못했다** |
| s2 특정 프로젝트 실습 | 1회 | PASS | 대상 1개만, 항목 4개·140분, 다른 프로젝트를 끌어들이지 않음 |
| s3 초안 뒤 "이미 했어" | 2회 | 동작 확인(자동 판정은 검사 쪽 결함 2건) | 자기평가로 기억(파이썬 기초 한정), 방향 변화(초안 영향=true), 다시 만들기에서 파이썬 항목 2→1·자료 선택 재사용(호출 1회), 새 대화에서 "이미 해본 변수·자료형…"을 재사용. 실행 항목 완료 쓰기 없음. 1회차에서 **서버 최신성이 CURRENT로 남는 결함**을 찾아 고쳤고 2회차에서 STALE 확인. 자동 판정 실패 2건은 검사가 저장 문장의 단어("이미")를 찾던 것 — 근거 유형·범위로 보도록 고침(재실행은 상한 때문에 하지 않음) |
| s4 "혼자 못 해" → "한 시간만" | 3회 | PASS ×2 + 검사 결함 1 | 질문 카드(막히는 지점, 선택지 4개)·빠른 답이 같은 경로로 기록, SELF_REPORT 저장, `TIME_BUDGET` 60분 → **예산 60·합계 60분(3회 모두)**, 강도 질문 없음. 1회차에서 direction이 안 나와 프롬프트를 고친 뒤 2·3회차는 매 답변에 방향 변화 |
| s5 제출 요구·마감 미확인 | 1회 | PASS | "과제 1: 스택 클래스 구현 제출(마감 없음)" 자료. 과제 수행·제출 항목 없음, 실제 마감 생성 없음, 기간 모호성("이번 주 3일")만 한 번 확인. 추가 읽기가 실제로 일어남(호출 3회). **일부 원문 조회 실패 조건은 실호출로 만들지 못했다** — 고정 응답 T03으로만 검증 |
| s6 부분 수행(시간 미기록·이유=개념) 뒤 재계획 | 2회 | 1회차 **500**(기존 결함 발견) → 수정 뒤 PASS | 날짜 미정 항목의 부분 수행이 배치 제약으로 실패하던 것을 고침. 재계획은 "남은 분량 15분"으로 이어지고 계획 입력에 "실제 시간 미기록"·"(이유: 개념에서 막혔다)"가 실림 |

셔플 비교(같은 내용·순서만 다름)는 실제 모델로 하지 않았다 — 고정 응답 T01로만 했다. s1의 두 회차는 프로젝트 생성 순서가 같다.
반복은 제한적 점검이며 통계적 품질 입증이 아니다.

### 4.3 실호출·화면에서 찾아 고친 결함

1. 방금 만든 초안이 "갱신 필요"로 보임(합의 판 번호만 비교) — `5acf4ab`.
2. 답변이 기억으로만 저장되면 새로고침 뒤 "갱신 필요"가 사라짐 — `da991f4`.
3. 초안 뒤에 한마디만 더 해도 새로고침에 초안이 복구되지 않음(UI) — `bc5078a`.
4. "꼭" 항목의 이유가 화면에 없음(근거 기록에만 있었음) — `5acf4ab` + UI.
5. 날짜 미정 항목의 부분 수행 기록 500(**기존 결함**) — `da991f4`.
6. 추가 읽기 회차에서 프로젝트별 목록 전달 수가 null — `da991f4`.
7. devtools 재시작 중 컨트롤러 매핑이 빠진 채 뜨는 현상은 개발 환경 문제라 고치지 않고 기록만 했다(서버를 다시 띄우면 된다).

## 5. 데이터·환경

- 마이그레이션 둘은 **`memo_consult`에만** 적용했다. `memo`·배포 DB는 그대로다. 이 브랜치를 `memo`에 붙여 띄우려면 먼저
  `docs/sql/2026-09-19-plan-generation-traces.sql` → `2026-09-19-consult-understanding.sql` 순으로 적용한다(둘 다 추가형·재실행 가능).
- 기존 사용자 데이터 변경: **없음**(`memo`에 접속하지 않았다).
- 만든 것(정리하지 않았다 — 사용자 판단):
  - DB `memo_consult` 전체(합성 계정 `consult-smoke-*`, `project-consult-s{1..6}-*@example.com` 12개, 비밀번호 `consult-1234`, DB 테스트가 남긴 행).
    통째로 지우려면 `DROP DATABASE memo_consult;`
  - 업로드 파일 `wt/diary-api-consult/data/materials/`(합성 노트북·스크립트), 평가 기록 `build/synthetic/project-consult-*.json`·`eval-run*.log`(git 무시).
  - `.claude/launch.json`에 `diary-api-consult`·`diary-ui-consult` 항목, worktree 둘, 각 worktree의 `application-local.properties`(gitignore)·`.env.claude`.
- 푸시·PR·배포: **하지 않았다**(권한 없음). 두 저장소 모두 로컬 브랜치 `feat/project-consult-workspace`의 커밋 상태다.

## 6. 재현 명령

```bash
# 서버(격리 DB) — .claude/launch.json의 diary-api-consult / diary-ui-consult
cd wt/diary-api-consult && ./gradlew.bat test                      # 전체 게이트(1,170)
cd wt/diary-api-consult && ./gradlew.bat test -PexcludeDbTests --tests "*ProjectCoverageFlowTest" --tests "*ConsultConditionsFlowTest"
cd wt/diary-ui-consult  && npm test && npm run lint && npm run build
# 실제 모델(서버가 8081에 떠 있고 memo_consult를 볼 때만. 도는 동안 gradle을 돌리지 않는다)
cd wt/diary-api-consult && python scripts/ai-baseline/verify-project-consult-2026-09-19.py s4
```
