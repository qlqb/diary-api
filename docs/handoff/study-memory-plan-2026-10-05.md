# 학습 기억 · 목차 기반 목표 — 구현 계획 v3 (2026-10-05)

기준: diary-api `25ab409`, diary-ui `92c0d06`(dev-playground). 브랜치 `feat/study-memory`, 워크트리 `wt/api-memory`, `wt/ui-memory`.
스택 유지(Spring Boot + MyBatis XML + MariaDB / React + Vite). 마이그레이션은 docs/sql 수동 적용.

v1 → v2: Codex 계획 리뷰 12건 반영(§7). 판 이름 분리(v1 §2.4)는 이번 범위에서 뺀다.
v2 → v3: 재리뷰 5건 반영(§7.2, §8 — §8이 §2·§3의 해당 규칙보다 앞선다). 재리뷰 상한(1회)에 도달해 v3로 확정.

## 0. 성공 기준

> 사진을 올리고 같이 공부한 뒤, **새 대화**에서 계획을 요청해도 교재·진도를 다시 묻지 않고, 확인된 어려움과 해당 본문을 활용한다.

이번 PR = **1(목차 기반 목표·AI 연습) + A(학습 기억)**. 사진 없이 성립하는 부분("다시 묻지 않음", "확인된 어려움 반영")을 끝까지
동작시킨다. B(상담 사진)·C(사진 본문을 계획에)는 §6 메모로 다음 PR.

합격 판정(§5.3): 격리 DB 합성 사용자로 실제 모델 호출 — 새 대화 계획에서 (a) 교재·진도 질문 0개 (b) 막혔던 단원 topicId를 가진
항목 ≥1 (c) 목차만 아는 단원 항목의 목표에 "목차에서 추론" 표시 (d) AI_PRACTICE 항목이 교재 문제·쪽 내용을 주장하지 않음.

## 1. 지금 있는 것 (조사·리뷰로 확인)

| 영역 | 있는 것 | 빈틈 |
|---|---|---|
| 자동 기억 | 상담 턴 `consult.memory[]` → `UserContextService.autoSave`(CONSULT_AUTO). STATED·SELF_REPORT는 인용이 사용자 발화 안에 있어야, 아니면 INFERRED. 모델은 OBSERVED 불가. 실데이터 동작 중 | 종류·단원 없음 / 자료 원문 턴은 기억 전부 버림 / 같은 종류 새 사실이 옛 것을 대체 못 함 / 인용이 맞아도 text는 검증 안 됨 / 중복 검사가 근거 검증보다 먼저라 INFERRED→STATED 승격 안 됨 / 철회 비교는 최근 40건만 |
| 기억 주입 | 상담 `[장기 컨텍스트]`(배분 35%), 계획 `[사용자가 확인한 맥락]`(전역 20개), 자료 선택 단계엔 **내용만**(근거 종류·과목 없이) | 프로젝트 핵심 상태가 다른 과목과 섞여 잘림. 선택 재사용 지문에 기억 없음 |
| 교재 | 확정 교재를 다시 묻지 않는 규칙은 이미 `appendTextbookState`에 있음 | — |
| 실제 수업 진행 | `topic_class_progress`·범위 제외(계획만 읽음) | 상담에 없음 |
| 초안 최신성 | 인용한 기억이 ACTIVE/STALE이 아니면 "오래됨" | confirm(같은 행 갱신)·다른 대화의 새 막힘은 감지 못 함 |
| 계획 출처 | origin SOURCE_TASK/AI_PRACTICE/USER_REQUEST, SOURCE_TASK는 자료 구간 인용 필수 | 학습 목표 없음. AI_PRACTICE는 "원문 바탕"만. EXAM 규칙이 목차만 있는 과목을 "목차 훑기"로 유도 |
| 같은 제목 단원 | TocSkeleton·TocOps가 번호를 제목 앞에 붙임 | 번호 없는 줄은 같은 제목. 원본 목차 순번 없음(orderIndex는 트리 순서) |

결론: 새 기억 저장소를 만들지 않는다. `user_contexts`를 넓히고(종류·단원·대체), 공용 `ProjectStateService`가 만든 과목 상태를
상담·자료 선택·계획에 **같은 모양으로** 싣고, 프로젝트 화면에 사실 카드를 붙인다.

## 2. 1번 — 목차 기반 학습 목표 · AI 연습

### 2.1 학습 목표(서버가 근거를 정한다)
- 모델 출력 item에 `goal`(60자)만 추가. **근거 종류는 모델이 내지 않는다** — 서버 계산(`PlanResultNormalizer.goalBasis`):
  - `USER`: 인용한 근거 중에 사용자가 **수락한** 합의 GOAL(brief item accepted, speaker USER 또는 accepted) 또는 STATED/USER_EDITED인 GOAL 기억이 있을 때만.
  - `MATERIAL_AI`("자료 기반 AI 목표"): 그 항목의 topicId와 같은 토픽(또는 같은 과목)의 자료 구간을 인용했을 때.
  - `TOC_AI`("목차에서 추론"): 그 밖에 목차 출처 토픽만 인용(토픽의 과목 = 항목 과목).
  - 근거가 없거나 인용 과목·토픽이 항목과 다르면 goal을 버린다.
- 경로: `PlanDraftAiItem.goal` → `PlanItemEvidence(goal, goalBasis)`(`withOrigin`·`withStatus`가 보존, JSON 왕복) → `PlanDraftService.withItemReasons` → `AiProposalItemResponse.learningGoal/learningGoalBasis`. 재조회 테스트.
- 화면: "목표: …" + 칩 `목차에서 추론`(툴팁 "단원 제목만 보고 추론했어요. 본문을 올리면 바로잡을 수 있어요") / `자료 기반 AI 목표` / `내가 정한 목표`.

### 2.2 AI가 만든 연습
- AI_PRACTICE 정의: "자료 원문 **또는 목차 제목**을 바탕으로 네가 만든 연습". 교재 대화문·문제 번호·정답·쪽 안 내용처럼 쓰지 않는다(유지).
- 정규화: 목차 토픽만 인용하고 자료 구간을 인용하지 않은 항목의 origin이 SOURCE_TASK면 AI_PRACTICE로(기존 강등과 동일), 비어 있고 actionType이 PRACTICE·RECALL이면 AI_PRACTICE로 채운다.
- 라벨 `AI_PRACTICE: 'AI가 만든 연습'` + 보조 "교재 문제가 아니에요". `PlanItemPromptRules.SHARED_PHRASES` 유지.

### 2.3 목차만 있는 과목의 계획 규칙
- `buildUserPrompt` L1685-1689: 목차 항목은 제목·쪽만 확인. 제목이 표현·문법을 드러내면 그 단원의 추론 목표·AI 연습 가능. 목차 전체를 진도·시험 범위·밀린 일로 보지 않는다(유지). 본문이 필요하면 그 단원 사진·본문을 제안(유지).
- `PlanPurpose` EXAM/REVIEW/SELF_STUDY 규칙의 "교재 전체 구조 훑기" 유도 문구 → "범위를 모르면 가정을 밝히고, 진도·막힌 단원 근처의 추론 목표와 AI 연습으로". `PlanPurposeTest` 갱신.
- 목차 출처 판정을 locator 문자열 대신 **명시 필드**로: `TopicLine.tocSeq`(아래) 또는 `source_web_revision_id`/locator 기존 규칙을 `TocTopics.isToc(topic)` 하나로 모아 상담·선택·계획 렌더러가 공유.

### 2.4 같은 제목 단원 — 원본 목차 순번
- `course_topics.source_toc_seq INT NULL`: 같은 교재 목차 안의 원본 순번(1부터). `TocSkeleton`과 `TocOps.checkAdd` **두 ADD 경로 모두** 채운다(정리안 ops에 tocLine이 있으니 그 값). locator는 그대로("교재 p.N"/"교재 목차").
- 렌더러(상담 토픽 줄·선택 후보·계획 토픽 줄): 목차 항목이면 `(교재 목차 n번째 — 제목만 확인)`. 규칙: "같은 교재 목차 안에서 제목이 같아도 번호·순번이 다르면 다른 단원이다 — 한 목표로 합치지 않고 기록을 옮기지 않는다".
- `ProjectTidyAnalyzer` L107: "같은 교재 목차 안에서 번호가 다르면 다른 항목이다. 표기만 다르고 번호가 같으면 새로 만들지 않는다. 다른 자료끼리 같은 내용을 잇는 LINK 원칙은 그대로"로 범위를 한정. `ProjectTidyTextbookPromptTest`.

## 3. A — 학습 기억

### 3.1 데이터 (`docs/sql/2026-10-05-study-memory.sql`, 추가형·재실행 가능, 머리에 dry-run·확인·롤백)
```sql
ALTER TABLE user_contexts
  ADD COLUMN IF NOT EXISTS fact_kind  VARCHAR(16)  NULL,  -- PROGRESS/EXAM_SCOPE/DIFFICULTY/RESOLVED/GOAL/PREFERENCE/CONSTRAINT/OTHER, NULL=예전 행
  ADD COLUMN IF NOT EXISTS fact_label VARCHAR(40)  NULL,  -- EXAM_SCOPE의 시험 이름(중간고사 등). 대체 키
  ADD COLUMN IF NOT EXISTS help_level VARCHAR(8)   NULL,  -- RESOLVED: SOLO/GUIDED (사용자 말로 확인된 것만)
  ADD COLUMN IF NOT EXISTS said_at    DATETIME     NULL,  -- 근거 발화 시각(사용자 메시지 created_at, 수동 수정은 수정 시각). 순서 판단
  ADD COLUMN IF NOT EXISTS content_key VARCHAR(191) NULL; -- 정규화 본문 키(중복·철회 판정)
CHECK·INDEX (user_id, course_id, fact_kind, status), (user_id, content_key, status)
ALTER TABLE course_topics ADD COLUMN IF NOT EXISTS source_toc_seq INT NULL;
```
- PROGRESS = **수업에서** 어디까지 했나(사실). DIFFICULTY/RESOLVED = **내가** 어디서 막혔고 어떻게 풀었나. 섞지 않는다.
- backfill: 없음. 예전 행은 종류 없이 "그 밖". content_key가 NULL인 예전 철회 행은 기존 최근 40건 비교로 계속 막는다.

### 3.2 저장 경로와 검증 (공용 정책 `StudyFactPolicy` — 자동 저장·수정·확인이 함께 씀)
**전달**: `MemoryOut(kind, topicId, label, help, resolves)` → `AutoSave` → `UserContext` → MyBatis insert/resultMap → `UserContextResponse`·`ConsultView.Understanding(kind, topicTitle)`.

**검증(서버)**:
- topicId: 그 courseId의 ACTIVE 토픽만. 그리고 STATED로 인정하려면 사용자 발화에 그 토픽의 번호(예 "Unit 3"/"3과") 또는 제목 핵심어가 있어야 한다 — 없으면 topicId를 버린다(잘못 연결된 막힘이 다른 단원을 대체하지 않게).
- **text 대조**: STATED·SELF_REPORT의 text에 있는 숫자 토큰(단원 번호·날짜·쪽)은 전부 사용자 발화에 있어야 한다. 아니면 INFERRED로 낮춘다. (인용 "Unit 3" + text "Unit 12까지" 공격 차단)
- courseId: 사용자 소유가 아니면 **저장하지 않는다**(전역으로 바꾸던 fallback 제거 — 과목 대화에서는 대화 과목으로).
- help: RESOLVED이면서 STATED일 때만, 사용자 발화에 도움/혼자를 드러내는 말이 있을 때(모델 판정 + 서버는 STATED 조건만 확인). 아니면 NULL.
- kind 모르는 값 → OTHER. 같은 턴에 PROGRESS가 여럿이면 마지막 하나만.

**자료 원문 턴(주입 방어)**: 원문을 실은 턴에서 모델이 낸 memory는 쓰지 않는다(기존 유지). 대신 그 턴에 사용자 발화가 있으면
**사용자 발화 전용 추출 호출**을 1회 한다 — 입력은 사용자 발화 + 과목 토픽 목록(번호·제목) + 현재 상태 블록뿐, 자료 원문·AI 답변 없음.
출력은 같은 MemoryOut 형식, 같은 검증을 탄다. 작은 모델(`ai.memory.extract-model`, 기본 대화 모델), 상한 400토큰, 실패해도 턴은
성공, 사용 기록 feature `MEMORY_EXTRACT`(상담 한도 미포함, 일일 상한 `ai.memory.daily-extract-limit=40`). 사용자 발화에 기억할
신호(숫자·단원·막힘·이해·시험 등 서버 규칙)가 없으면 호출하지 않는다.

**대체(현재 상태형)** — 과목 행 `SELECT … FOR UPDATE` 아래 조회·대체·삽입을 한 트랜잭션:
- 대상: 같은 사용자·과목·종류(EXAM_SCOPE는 + 같은 label, NULL은 NULL끼리)의 ACTIVE·STALE 행.
- 대체 조건: 새 행이 **STATED(검증 통과)** 이고 새 행의 said_at > 기존 행의 said_at. 기존이 USER_EDITED여도 더 늦은 사용자 발화면 대체(최신 사용자 말이 이긴다). PATCH 뒤에 늦게 끝난 옛 턴은 said_at이 더 이르므로 대체하지 못하고 **저장도 하지 않는다**.
- INFERRED·SELF_REPORT는 PROGRESS·EXAM_SCOPE를 대체하지 않는다. 같은 종류 ACTIVE STATED가 있으면 INFERRED는 저장하지 않는다.
- 대체된 행은 SUPERSEDED + `supersedes_context_id`.

**막힘 → 해결**: RESOLVED에 `resolves`(상태 블록에 보인 막힘 id)가 있고, 그 행이 같은 사용자·과목의 ACTIVE DIFFICULTY이고, 새
RESOLVED가 STATED일 때만 그 막힘을 SUPERSEDED로. 아니면 RESOLVED를 따로 저장하고 막힘은 그대로 둔다(같은 단원의 다른 막힘을 지우지 않는다).

**중복·승격·철회**:
- 중복 키 = (과목, 종류, 토픽, content_key). 같은 키의 INFERRED가 있고 새 것이 STATED면 **승격**(confirm과 같은 갱신, said_at 갱신).
- 철회 차단: (과목, 토픽, content_key)가 같은 WITHDRAWN 행이 하나라도 있으면 저장하지 않는다 — 인덱스 EXISTS로 전체 이력. content_key 없는 예전 철회는 기존 40건 비교 유지.

### 3.3 사용자 수정
- `PATCH /contexts/{id}`에 선택 필드 `kind`, `topicId`, `help`, `label` 추가. 생략은 보존(edit가 옛 값 복사), 지정하면 §3.2 검증(토픽 소유·과목 일치). 수정은 USER_EDITED·STATED, said_at = 지금. 수정도 과목 잠금 아래 같은 대체 정책(진도를 고치면 다른 ACTIVE 진도는 대체).
- `confirm`: INFERRED→STATED 갱신 시 `updated_at`·said_at 갱신(아래 최신성에 쓰임).

### 3.4 프로젝트 상태 (`ProjectStateService` — 화면·상담·선택·계획 공용)
반환: 구조화된 사실 목록 + **실제로 렌더링한 contextId 목록** + 상태 지문(행 id·updated_at·status·kind·topic·help의 해시).
렌더링 순서·상한(과목당 최대 14줄, 줄당 120자 — 자를 때 제외 조건 문구는 자르지 않고 줄 단위로 뺀다):
교재(확정) → 수업 진도 → 실제 수업 정정(최대 5) → 시험 범위·범위 제외 → 막힘(id 노출, 해결 지정용) → 해결(30일) → GOAL·CONSTRAINT·PREFERENCE(과목 한정) → 최근 실행 기록(14일, 최대 3) → AI 추정(확인 전).
넘치면 추정 → 실행 기록 → 해결 순으로 줄인다. 출처 라벨은 서버가 붙인다(사용자가 말함/사용자가 고침/자기 평가/실행 기록/AI 추정 — 확인 전).

- **상담**: 과목 대화는 `AiWorkspaceContextBuilder.build`에서 일반 상태 잘림(3,500자) **밖에** 별도로 붙인다(계획 상태·실행 기록 블록과 같은 방식, 예산 먼저 예약). 전역 대화(PLAN/PLANNING/MIXED/EXECUTION 또는 CREATE_PROPOSAL)는 최근 활동 과목 최대 3개에 과목당 6줄. `ContextSnapshotService`의 장기 컨텍스트에서는 **실제로 렌더링한 contextId만** 뺀다. 규칙: "확인된 상태에 있는 교재·진도·시험 범위는 다시 묻지 않는다. AI 추정은 쓰기 전 한 번 확인할 수 있다. 수업 진도와 내 이해를 섞지 않는다. 막힘을 해결했다고 말하면 resolves에 그 막힘 번호를 적는다."
- **자료 선택**: `PlanMaterialSelector.Request`에 과목별 상태 줄(근거 라벨 포함)을 넘기고, 헤더 "사용자가 확인한 맥락"을 "사용자 상태(라벨 참고 — AI 추정은 사실 아님)"로. 막힘·도움받아 해결 단원은 "먼저 볼 후보"로 표시하되 사용자 제외·범위 제외가 앞선다. **선택 재사용 지문(judgmentBasis)에 상태 지문 포함** — 진도·막힘을 고치면 다시 고른다.
- **계획**: `appendCourseContext`에 과목마다 같은 블록(USER_CONTEXT provenance로 행마다 표시). 기존 `[사용자가 확인한 맥락]`에서는 렌더링된 id만 빼고 나머지(과목 없는 것)는 유지. 규칙: "확인된 교재·진도·시험 범위는 questions에 넣지 않는다. 막힌 단원·도움받아 해결한 단원은 복습·AI 연습 후보로 먼저 본다(범위 제외 우선)."
- **초안 최신성**: provenance 값에 kind·evidenceType·help·updated_at 기록. `freshness`가 (1) 인용 행의 상태 변경 (2) updated_at 변경(confirm 포함) (3) 생성 시 저장한 과목 상태 지문 ≠ 현재 지문(다른 대화의 새 막힘 등)이면 STALE — 서버 계산이라 새로고침해도 유지.

### 3.5 프로젝트 사실 카드 (UI)
- API `GET /api/courses/{courseId}/study-state`(인증 사용자 소유 과목만, 모든 연결 행 user_id 조건) → `ProjectStateService` 결과(화면과 AI가 같은 것을 본다).
- 고치기(내용 + 종류·단원·도움 선택)·지우기·맞아요는 `/contexts/{id}` 재사용. 응답 후 카드 다시 조회, 상담 턴 완료·저장 이벤트 때 `ProjectWorkspace`의 `refreshToken`으로 갱신.
- `StudyStatePanel` "이 프로젝트에서 기억하는 것" — 작업 공간 탭 교재 구역 아래. 묶음: 진도 · 시험 범위 · 막힌 곳 · 해결한 것 · 그 밖. 줄: 내용, 단원 칩, 출처 칩, 날짜, [고치기][지우기], 추정이면 [맞아요]. 입력 폼 없음(빈 상태 문구 "상담에서 말한 진도·시험 범위·막힌 곳이 여기에 자동으로 모여요").
- 상담 `ConsultUnderstandingCard`에 종류·단원 표시(DTO·저장 JSON·화면).

## 4. 하지 않는 것 / 지키는 것
- 새 기억 테이블·벡터 검색·과거 대화 원문 검색 없음. 판 이름 분리는 다음에.
- 모델은 OBSERVED·근거 종류(goalBasis)를 정하지 못한다. AI 추정은 사실로 쓰지 않는다.
- 실 사용자 데이터로 테스트하지 않음. `memo` 적용 전 백업. push·PR·머지는 사용자 지시가 있을 때만.

## 5. 테스트·검증
### 5.1 단위
- `StudyFactPolicyTest`: 숫자 대조 강등, 토픽 언급 없으면 토픽 버림, 소유 아닌 과목 거부, PROGRESS 대체(STATED만·said_at 순서·USER_EDITED 보호·늦은 옛 턴 무시), EXAM_SCOPE label별, 같은 턴 복수 PROGRESS, resolves 검증(부분 해결 시 다른 막힘 유지), INFERRED→STATED 승격, 철회 키.
- `ProjectStateBlockTest`: 순서·상한·줄 단위 축약·라벨·렌더링 id·지문.
- `PlanResultNormalizerTest`: goalBasis 3종·과목/토픽 불일치 버림·AI_PRACTICE 강제. `PlanPurposeTest`, `ProjectTidyTextbookPromptTest`, `TocOps`/`TocSkeleton` seq.
- 주입: 원문 턴에서 모델 memory 버림 + 추출 호출 입력에 원문·AI 답변이 없음 + "유효 인용 + 거짓 숫자/단원" 공격이 INFERRED·토픽 없음으로.
### 5.2 DB 통합 (`StudyMemoryFlowDbTest`, 로컬 DB·합성 행·정리, CI에서는 기존 관행대로 `excludeDbTests` 목록에 추가)
합성 과목(교재 확정, Unit 1~12, Unit 1·10 같은 제목) → 턴1 진도·막힘 저장 → 턴2 해결(resolves) → INFERRED 진도가 덮지 못함 →
동시 두 턴 저장 시 ACTIVE 진도 1개 → PATCH 뒤 늦은 옛 턴 무시 → 다른 미해결 막힘 철회 후 같은 내용 재저장 거부 → 새 대화의 상담
프롬프트·선택 요청·계획 프롬프트에 같은 상태 → 가짜 모델 출력으로 정규화 결과(topicId·origin·goalBasis) → confirm 후 새로고침해도 초안 STALE.
### 5.3 실제 모델(격리 DB `memo_memory`, 합성 사용자)
§0 합격 판정 (a)~(d)를 시나리오 3회 실행에서 모두 만족. 결과를 handoff에 기록.
### 5.4 UI
`StudyStatePanel.test.jsx`(묶음·라벨·고치기 필드·지우기·확인·빈 상태·갱신), `PlanDraftReview` 목표 칩, `planLabels`. `npm test`, `npm run build`.
Codex 코드 리뷰 1회(+재리뷰 최대 1회).

## 6. 다음 PR 메모 (B·C)
- **B 상담 사진**: JPG/PNG/WebP, 장당 8MB·턴당 4장 → 과목 자료(origin CONSULT_PHOTO) → 비전 글자 추출(자료 분석 상한) → 쪽·단원 제목으로 목차 토픽 연결(애매하면 질문 1개, 확정 전 "추정 연결") → `material_text_units` → 다음 턴부터 근거 조회. 업로드 ≠ 학습 완료. 원본 30일 뒤 삭제(즉시 삭제 가능), 추출 텍스트는 자료 삭제 시 삭제. 사진 속 글은 원문 턴과 같은 주입 방어. 합성 이미지로 테스트.
- **C**: 선택기가 막힘 단원과 연결된 사진 구간을 우선 후보로. 사진 포함 성공 시나리오.

## 7. Codex 계획 리뷰 1회차 분류
| # | 지적 | 분류 | 반영 |
|---|---|---|---|
| 1 | 인용 검사로 원문 턴 주입 방어 안 됨 | 반영 | 원문 턴은 모델 memory 버리고 사용자 발화 전용 추출, 숫자·토픽 대조 |
| 2 | 같은 단원 막힘 전부 해결 처리 | 반영 | `resolves` 명시·검증, 불명확하면 막힘 유지, help는 STATED만 |
| 3 | 대체 순위·동시성·STALE·복수·시험별 | 반영 | said_at 순서, 과목 행 잠금, ACTIVE·STALE, 마지막 하나, fact_label |
| 4 | PATCH가 종류·단원 못 고침, 승격 없음 | 반영 | 선택 필드·보존·검증, INFERRED→STATED 승격 |
| 5 | 자료 선택 단계 누락·재사용 지문 | 반영 | 선택 요청에 상태·라벨, 지문에 상태 지문 |
| 6 | 잘림·중복 제거·전역 상담 | 반영 | 잘림 밖 블록, 렌더링 id만 제외, 전역 상담 과목 3개 |
| 7 | goalBasis 과대 확정 | 반영 | 서버 계산, USER는 수락된 GOAL만, MATERIAL_AI, 과목·토픽 일치 |
| 8 | 최신성(confirm·새 막힘) | 반영 | updated_at·상태 지문 비교 |
| 9 | 목차 순번·두 ADD 경로·locator 검사 | 반영 | source_toc_seq, isToc 공용, 같은 목차 한정 |
| 10 | 철회 40건 한계·키 범위 | 반영 | content_key 인덱스 EXISTS, 키 범위 분리 |
| 11 | 파서 VERSION이 완료 조회를 갱신 못 함 | 반영(범위 제외) | 판 이름 분리를 이번 범위에서 뺌 |
| 12 | 검증 부족·CI 스키마 | 반영 / CI 부분 기각 | 출력·합격 기준·경합 테스트 추가. CI 스키마 bootstrap은 기각 — 저장소에 전체 스키마가 없어 DB 테스트 42개를 이미 `excludeDbTests`로 로컬 실행하는 관행을 따른다 |

### 7.2 재리뷰(2회차) 분류 — 전부 반영
| # | 지적 | 반영 |
|---|---|---|
| 1 | 같은 숫자의 부정·질문·가정문이 진도를 대체 | §8.1 |
| 2 | resolves가 다른 단원 막힘을 닫음 / 짧은 해결 답변의 단원 유실 / SELF_REPORT 해결 | §8.2 |
| 3 | 초 단위 시각 동률 | §8.3 |
| 4 | 키 길이 191 / 예전 철회 40건 한계 | §8.4 |
| 5 | 수락된 GOAL 인용 + 다른 목표 출력 = "내가 정한 목표" | §8.5 |

## 8. v3 규칙 (§2·§3보다 앞선다)
### 8.1 현재 상태 자동 대체의 조건
진도·시험 범위의 자동 대체는 STATED이면서 **인용(quote) 자체가** 그 주장을 담을 때만: text의 숫자가 전부 인용 안에 있고(발화 전체가 아니라),
인용에 부정·미완·질문·가정 표지(`못`, `안 `, `않`, `아직`, `?`, `까`, `면`, `라면`, `거나`, `지도`)가 없어야 한다. 하나라도 어기면 INFERRED
후보로만 저장(대체 없음 — 카드에서 [맞아요]로 확정). 추출 프롬프트에서 토픽 제목·현재 상태는 "번호 참고용 데이터"로 따로 감싸고, 사실의
근거는 사용자 발화뿐이라고 명시. 회귀 테스트: "Unit 3은 아직 못 끝냈어"·"Unit 5까지 나가면 좋겠다"·"Unit 4까지 했나?"·악성 토픽 제목.

### 8.2 막힘 해결
- resolves 대상은 같은 사용자·과목의 ACTIVE·STALE DIFFICULTY. 새 해결의 topicId는 **대상에서 승계**한다. 모델이 다른 topicId(검증된)를 냈으면 대체를 거부하고 해결을 따로 저장.
- 해결을 인정하는 근거: STATED 또는 SELF_REPORT("설명 보고 이제 만들 수 있어"는 자기평가). 둘 다 인용 검증을 통과해야 한다. 숫자 대조는 발화 + 대상 단원 제목을 함께 본다(대상 단원 번호를 문장에 보충해도 강등되지 않게).
- 단원 언급이 없는 해결 답변: resolves가 있으면 그 대상으로 연결한다. resolves가 없으면 막힘을 닫지 않는다(해결만 따로, 단원 없음).
- help_level은 STATED·SELF_REPORT 해결에서만.
- 실제 모델 합격 시나리오에 "Unit 3 have to 문장이 막혀" → "설명 보고 이제 만들 수 있어"(단원 언급 없음)를 넣는다.

### 8.3 순서
비교 키 = (said_at, 출처 순번). 메시지 출처끼리 시각이 같으면 source_message_id가 큰 쪽이 늦다. 사용자 수정(USER_EDITED)과 메시지가 같은
초면 **수정이 이긴다**(명시적 정정). 즉 새 메시지 사실은 `said > 기존` 이거나 `said == 기존 && 기존이 메시지 출처 && 새 message_id > 기존 message_id`일 때만 대체.
테스트: 같은 초 두 메시지 역순 완료, 같은 초 PATCH 뒤 늦은 메시지.

### 8.4 본문 키·철회
content_key = SHA-256(정규화 본문) 16진수 64자(`CHAR(64)`). 예전 철회 행(content_key NULL)은 그 사용자의 **전부**를 읽어 Java 정규화로 비교한다
(행 수가 작다 — 40건 상한 제거). 테스트: 300자 본문 저장, 41번째 이전의 예전 철회 행.

### 8.5 USER 목표
USER는 goal 본문이 인용한 확인된 GOAL(수락된 합의·STATED/USER_EDITED GOAL 기억)의 본문과 정규화 비교로 같거나 그 본문을 포함할 때만.
아니면 USER 근거를 빼고 나머지 근거로 판정(자료 → MATERIAL_AI, 목차 → TOC_AI, 없으면 goal 버림). 테스트: 수락된 "과거시제 질문하기" 인용 + 출력 "현재진행형으로 설명하기".
