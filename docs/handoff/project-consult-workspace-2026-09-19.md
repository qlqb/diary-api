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
