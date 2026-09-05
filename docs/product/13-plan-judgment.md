# 13. 기간 학습 계획 판단층

> 상태: 커밋 1 완료 (2026-09-06)
> 선행: 11-period-plan.md (기간형 계획), 03-planning-system.md
> 이 문서가 판단층의 기준이다. 대화로 오간 인수인계는 이 문서로 대체한다.

## 0. 무엇이 없었는가

`PeriodPlanDraftGenerator`는 `기간 + 과목 topic 목록 → AI items → Timefold`로 바로
점프한다. 그 사이에 있어야 할 "이번 기간을 왜 이렇게 운영하는가"가 없다 — 목표 재정의,
과목 간 우선순위, 선수지식 선택, 압축, 수업 전 마감.

```text
지금    기간 → 학습 항목 나열 → 모델이 조각을 만든다 → 배치
새것    기간 → 현실 수집 → 판단(전략) → 조각 → 배치
                            ↑ 저장된다. 다음 계획이 읽는다
```

핵심 가설은 **계획 품질의 병목이 모델이 아니라 입력**이라는 것이다. 그래서 LLM 없는 v0
생성기를 먼저 만들어 체인을 검증하고, 그 위에 판단과 조각 생성을 얹는다.

## 1. 확정된 결정

| # | 결정 | 기각한 대안 |
|---|------|------------|
| D1 | 판단(전략)과 조각을 **별도 서비스, 별도 AI 호출**로 분리. 최초 생성은 서버가 순차 호출, 중간 검토 화면 없음 | 단일 호출 — Replan이 "전략→조각" 경로를 어차피 필요로 해서 계약 두 벌 유지 비용이 더 크다 |
| D2 | 전략은 `plan_versions.strategy_json`에 저장 | 별도 `plan_strategies` 테이블 — 버전은 그대로인데 전략만 바뀌는 시나리오가 없다 |
| D3 | 전략 유효기간 = 원본 버전의 `end_date` | strategy 안의 `validUntil` — 두 값이 어긋날 여지를 만든다 |
| D4 | 한 요청에 목표 기간이 둘 이상이면 계획을 만들지 않고 **되묻는다** | `phases[]` 자동 분할 |
| D5 | **v0 결정적 생성기**(LLM 없음)를 먼저 만들어 마감→Timefold 체인 검증 | 판단부터 구현 |
| D6 | 모든 조각은 `자료 + 위치` 또는 `실습/회상 행동` 중 하나에 anchor | topic 제목만 있는 조각 |
| D7 | 우선순위는 **저장하지 않고 계획 시점에 계산** | `materials.priority` 컬럼, 가중합 점수 |
| D8 | 사용자 사실은 `course_topics.user_mark`로 **topic 단위** 저장 | 자료 단위 mark, 과목 레벨(초급/중급) |
| D9 | 근거 등급이 취급 상한을 정한다 (§4) | 확정된 맥락이면 무조건 SKIP |
| D10 | 예상시간 = 기본추정 × 사용자 속도계수. v1은 기본추정만 | 모델이 "사용자 수준 파악해서 시간 결정" |
| D11 | 사용 기록 기반 우선순위 자동 조정 **금지**. 관찰 → 사용자 확인 → 상태 갱신 | 미룸 횟수로 우선순위 하향 |
| D12 | 새 화면 0개. 입력 지점 4곳: 자료 분석 topic 트리, 초안의 이유 옆, AI 상담, 실행 후 관찰 | 온보딩 설문, 초안 직전 가정 확인 화면 |
| D13 | Supervisor/자율 오케스트레이터 없음. 서버 코드가 순서를 부른다 | Agent가 Agent를 호출 |

## 2. 착수 전에 코드로 확인한 것 (2026-09-06)

인수인계 문서와 실제 코드가 달랐던 항목이다. **여기 적힌 것이 코드에서 확인된 사실이다.**

- **생성 규칙은 `PlanDraftService`가 아니라 `PeriodPlanDraftGenerator`에 있다**(커밋
  `e229267`). 남은 `PlanDraftService`는 요청 변환과 저장뿐이다. 그 생성기는
  `AvailabilityEstimateService`를 직접 참조해 busy·가용 구간을 프롬프트에 이미 싣는다 —
  컨텍스트 갭은 문서가 말한 것보다 작고, 실제로 없던 것은 **진행 상태 · 다음 수업 시각 ·
  확정된 맥락 · 현재 시각** 넷이다.
- **`AvailabilityEstimateResult`는 `(windows, busyWindows)` 두 필드뿐이다.** 요일별 slot이
  아니라 날짜를 모르는 연속 구간이고, 신뢰도는 결과가 아니라 `AvailabilityWindow`에
  구간별로 붙는다. 요일별 요약은 새로 접어야 한다(`AvailabilityDaySummary`).
- **`course_topics`에 주차 컬럼이 없다.** `source_locator`(VARCHAR)에 문자열로 들어 있고
  실제 값은 `"2주차"`, `"6 주차"`, `"9주차, 12주차"`, `"6주차; 9주차"`,
  `"7주차, (Ch03-Lesson 04)"`, `"과목 개요"`가 섞인다. 컬럼을 만들지 않고
  `TopicLocators`가 계획 시점에 읽는다.
- **자료 분량 정보가 어디에도 없다.** `MaterialAnalysisPayload.TopicNode`는
  `(title, sourceType, sourceLocator, children)`뿐이라 페이지 범위·슬라이드 수·유형(코드/수식)이
  없다. "슬라이드 2장/분" 같은 블록화는 입력 자체가 없다.
- **마감 체인은 확정에서 끊겨 있었다.** `deadlineDate`는 제안·미리보기까지만 살아 있고
  `execution_items`에 담을 컬럼이 없었으며 `PlanPlacementService`는 항상 `deadline=null`로
  돌았다. 즉 `deadline_at`과 `deadlineDate`는 **공존하지 않는다** — 수명이 다른 두 값이다.
- **`AI_INFERRED`라는 맥락 출처는 코드에 없다.** `ContextSourceType`은 `USER_CONFIRMED`와
  `AI_SUGGESTION_APPROVED` 둘뿐이고 둘 다 사용자가 승인 버튼을 눌러야 생긴다. 근거 등급을
  가르는 것은 출처가 아니라 **수명주기**(`UserContextStatus`)다.
- **`ai_proposals`에 범용 payload 컬럼이 없다.** 전략을 "기존 payload에 동봉"할 수 없어
  `plan_intensity`/`plan_target_minutes`와 같은 방식으로 `plan_strategy_json` 컬럼을 만든다.

실데이터 상태(로컬 `memo` DB, user 2):

- `course_topics` 271행(7과목) — 전부 `source_locator`가 채워져 있다
- **`topic_progress` 0행** — 진행 상태 기록이 하나도 없다. `LEARNED → SKIP` 규칙은 픽스처로만
  검증되고 실사용 eval에서는 이 경로가 보이지 않는다
- `user_contexts` 1행 — `"매주 토요일과 일요일은 휴무(주말)"`, ACTIVE
- 수업 루틴 7개(`routines.course_id`), 전부 `effective_from = 2026-08-25`. 이것이 개강일이고
  현재 주차의 근거다

## 3. DB

`docs/sql/2026-09-06-plan-judgment-v1.sql`

| 테이블 | 컬럼 | 뜻 |
|--------|------|-----|
| `execution_items` | `deadline_at DATETIME NULL` | 이 시각까지 끝나야 계획상 의미가 있다. Timefold HARD 제약 |
| `plan_versions` | `strategy_json JSON NULL` | 이 버전을 만든 판단. 불변 |
| `ai_proposals` | `plan_strategy_json JSON NULL` | 확정 전까지 전략을 들고 있는 자리 |
| `course_topics` | `user_mark VARCHAR(16) NULL` | `KNOWN`/`DEFER`. 사용자 결정, AI가 쓰지 않는다 |

인덱스는 없다 — 셋 다 조회 조건이 아니라 이미 고른 행의 속성이다. MariaDB의 `JSON` 타입은
인라인 `CHECK (json_valid(...))`를 자동으로 붙이므로 별도 제약을 걸지 않는다.

## 4. 근거 등급 → 취급 상한

모델이 상한을 넘기면 서버가 상한으로 내리고 `reason`에 `(서버 조정)`을 붙인다.

| 근거 | 상한 |
|------|------|
| `user_mark KNOWN` | SKIP |
| ACTIVE 맥락 + 범위 직접 일치 | SKIP |
| ACTIVE 맥락 + 넓거나 간접 | SKIM |
| STALE 맥락 | SKIM |
| PENDING 맥락 | 쓰지 않는다 |
| 근거 없음 | **FULL** |

★ 출처(`USER_CONFIRMED` / `AI_SUGGESTION_APPROVED`)는 등급을 가르지 않는다. 둘 다 사용자가
승인해야 생기는 행이고, AI가 문장을 만들었다는 사실이 사용자의 승인을 약하게 만들지 않는다.
보수적 구분이 필요한 자리는 수명주기다.

★ "모델 추측"에 해당하는 등급을 만들지 않는다. 그런 등급이 있으면 판단 서비스가 프롬프트
안에서 스스로 추측한 것을 근거로 적어 SKIM을 정당화하는 통로가 된다. 근거가 없으면 FULL이다.
같은 이유로 `EvidenceType`은 닫힌 집합이고, 모르는 타입이 오면 그 근거를 버린다.

## 5. 커밋 1에서 만든 것

```text
PlanningContextBuilder     DB의 현실을 한 곳에서 모은다 → PlanningContext
  ├ TopicLocators          "9주차, 12주차" → 9 (가장 이른 주차)
  └ AvailabilityDaySummary 연속 구간 → 하루 한 줄 (자정 넘김을 날짜 경계에서 자른다)
PlanBlockGeneratorV0       모델 없이 초안을 만든다 (plan.draft.generator=V0)
PlanStrategy / Codec       판단의 계약. plan_versions.strategy_json
마감 체인                   ProposalItem → payload → 확정 → execution_items.deadline_at
                           → 스냅샷 / SchedulingTask.deadline → 기존 HARD 제약
```

### 컨텍스트 수집 규칙

- **현재 시각**은 날짜가 아니라 시각까지 담는다 — 21:37에 "오늘 오전"으로 시작하는 계획을
  만들지 않기 위해서다
- **학습 항목 창**은 현재 주차 −2 ~ +1. 주차를 모르는 항목은 창 뒤에 과목당 최대 3개
- **현재 주차**는 수업 루틴의 `effective_from`에서 계산한다. 강의계획서의 개강일을 파싱하지
  않는다 — 루틴은 사용자가 확인한 값이고 분석 JSON은 모델이 읽은 값이다
- **다음 수업**은 계획 종료일 + 14일까지 본다. 금요일에 끝나는 계획도 다음 화요일 수업을
  알아야 마감을 붙일 수 있다
- **맥락을 가용시간으로 바꾸지 않는다.** 추정의 유일한 원본은 `AvailabilityEstimateService`다

### v0 규칙

1. 과목 순서: 다음 수업이 빠른 순 (컨텍스트가 이미 정렬해 준다)
2. 취급: `user_mark KNOWN/DEFER → SKIP`, `LEARNED → SKIP`, 나머지(`NOT_STARTED`,
   `IN_PROGRESS`) `FULL`. 과목 안에서는 `IN_PROGRESS`를 앞에 두고 그 뒤는 주차 순 그대로다 —
   "LEARNED는 다시 시키지 않는다"의 짝이 "시작한 것은 이어간다"이고, 반쯤 열어 둔 항목이
   여럿 쌓이는 것이 새 항목 하나를 늦게 시작하는 것보다 나쁘다
3. 블록: **학습 항목 하나 = 블록 하나**, 길이는 설정값(`plan.v0.block-minutes`, 기본 45분).
   자료 분량을 모르므로 항목마다 다르게 줄 근거가 없고, 그 사실을 조각 설명에 적는다
4. 마감: 그 과목의 다음 수업 시작 시각. 수업이 없으면 마감도 없다
5. 상한을 넘겨 조각을 못 만들어도 **취급 판단은 전부 남긴다** — 잘린 항목이 "판단되지 않은
   항목"으로 보이면 안 된다

### 마감 규칙

- `deadlineAt`(시각)이 `deadlineDate`(날짜)를 이긴다
- `deadlineDate`만 있으면 확정 시 **다음날 00:00**으로 옮긴다. "9/9까지"는 9/9 안에 끝내면
  된다는 뜻이므로 경계는 9/10 00:00이고, 미리보기가 쓰던 변환과 같은 규칙이다.
  이 변환 덕분에 예전부터 있던 날짜 마감도 확정 이후까지 살아남는다
- 마감 전에 넣을 자리가 없으면 **미배치로 남긴다**. 넘겨 배치하면 "수업 전에 끝낸다"는
  전제가 조용히 깨지고 사용자는 그것을 배치 결과에서 알아차릴 방법이 없다
- 일부 수행 후 남은 분량 조각도 마감을 잇는다

### 알려진 한계: IN_PROGRESS에는 "어디까지"가 없다

`topic_progress`는 상태만 갖고 위치를 갖지 않는다. 그래서 시작한 항목을 이어갈 때 조각
생성이 그 자료를 **처음부터** 다시 anchor할 수 있다.

**progress 모델에 위치를 추가하지 않는다.** "어디까지 봤는가"를 정확히 유지하려면 사용자가
매번 그것을 입력해야 하고, 그 입력을 강제하는 순간 이 앱은 진도 관리 도구가 된다. 한계로
적어 두고, 실제로 문제가 되면(같은 자료 앞부분을 반복해서 잡는 일이 눈에 띄면) 그때
관찰층에서 물어보는 쪽을 먼저 시도한다.

## 6. 다음 커밋

| 커밋 | 내용 | 검증 |
|------|------|------|
| 2 | `PlanJudgmentService` — 전략 생성, 근거 등급 강제, 되묻기(MULTI_PERIOD / UNKNOWN_FAMILIARITY), `user_mark` 읽기 | GD-*, ASK-*, ST-2(전략 재사용) |
| 3 | `PlanItemService` — 자료 anchor 조각, `doneCriteria`, `actionType`, `estimateConfidence`, `items:regenerate` 엔드포인트 | 전략-조각 모순 warn, 수동 eval |
| 4 | `PlanCreateView` — 판단 근거 표시, 「이미 알아요」/「이번엔 빼기」, topic 트리 mark | Vitest, 수동 |

### 되묻기 조건 (코드로 판정, 모델 재량 아님)

- **MULTI_PERIOD**: 모델이 목표 기간을 둘 이상 냈거나 지시에 "~까지 … 그 후" 패턴 → 계획을
  만들지 않고 ASK
- **UNKNOWN_FAMILIARITY**: 근거가 전혀 없는 topic이고 FULL vs SKIM 시간 차가 초안 총량의
  25% 이상일 때만. **초안당 최대 1회**

응답은 기존 상담의 `ASK_CLARIFICATION`과 같은 패턴을 쓴다. 새 상태 개념을 만들지 않는다.

### 이번 범위 밖 (계약만 맞춰 둘 것)

- **속도계수**: 과목별 `actualMinutes/expectedMinutes` 중앙값, 기록 3건 이상일 때만,
  시간 추정에만 사용(우선순위에는 쓰지 않는다)
- **관찰 프롬프트**: 미룸 3회 시 4선택지 — "이미 아는 내용이에요 / 생각보다 어려워요 /
  다른 일이 먼저였어요 / 이번엔 중요하지 않아요" → 각각 KNOWN / 더 작은 조각 후보 /
  무변경 / DEFER. 자동 상태 변경 없음
- **Replan v2**: REUSED 전략 + 실행 기록 → 조각만 재실행
- **REVIEW 취급 활성화**: 시험 범위에 마친 항목을 포함하라는 요구가 실제로 들어올 때
- **자료 분량 정보**: 분석 payload에 페이지 범위·유형을 넣으면 v0의 고정 45분과
  `estimateConfidence`가 비로소 의미를 갖는다

## 7. 카피 규칙

금지어: 실패 / 미완료 / 부족 / 이행률 / 수준 / 실력 / 초급 / 중급 / 고급.
"시간이 부족했어요"도 쓰지 않는다.

「이미 알아요」/「처음이에요」는 **익숙함의 진술**이지 능력 판정이 아니다.

- (O) "화요일 재귀 수업 전에 필요한 내용이고, 아직 학습 기록이 없어요"
- (X) "기반이 부족합니다"

## 8. 검증

### 8.1 결정적 (CI, 모델 없음)

| 대상 | 테스트 |
|------|--------|
| 주차 파싱(실데이터 변종 포함) | `TopicLocatorsTest` |
| 요일별 가용시간 접기, 자정 넘김 분리 | `AvailabilityDaySummaryTest` |
| 과목 순서·주차 창·맥락 정렬·현재 시각 | `PlanningContextBuilderTest` |
| v0 취급 규칙, 마감, 블록 길이, 상한 | `PlanBlockGeneratorV0Test` |
| 마감 체인(제안→확정→스냅샷→배치) | `PlanDeadlineChainIntegrationTest` (로컬 DB) |

**기존 테스트가 덮는 것.** 아래 둘은 이번에 새로 짜지 않았다. 리팩토링으로 이 테스트들이
사라지면 회귀가 조용히 빠지므로 이름을 적어 둔다.

| 보장 | 덮는 테스트 |
|------|------------|
| 알바·수업 등 기존 고정 일정과 겹치지 않는다 | `PlanConfirmAndPlacementIntegrationTest.place_doesNotOverlapExistingTimeFixedItems` |
| 같은 과목의 `orderIndex` 순서를 배치가 뒤집지 않는다 | `SchedulingConstraintProviderTest.preferOrderIndexSequence_*` (4건) |
| 마감을 넘겨 끝나면 HARD 위반 | `SchedulingConstraintProviderTest.taskPastDeadline_*` (2건) |
| 배치 길이 불변식 | `PlacementDuration` 스위트 |
| 기간 초과 사유(anti-inflation guard) | `periodViolationReason` 테스트 — **건드리지 않는다** |

### 8.2 v0 baseline과 판단층 비교 지표

커밋 1의 v0를 실데이터에 돌린 결과가 기준선이다 (user 2, 7과목, 2026-09-07 ~ 09-13,
가용 1,710분 / 예산 1,110분):

```text
후보 46  →  생성 30  →  배치 11  ·  미배치 19
```

30개 전부 마감이 `execution_items.deadline_at`과 스냅샷까지 도달했다. 미배치 19개가 이
커밋의 증거다 — 마감을 넘겨 억지로 넣지 않았다는 뜻이고, 센서활용프로그래밍(마감 9/9 14:00)은
그 전 남는 시간이 이미 자료구조·웹서버로 차서 통째로 미배치가 됐다.

판단층(커밋 2)은 **같은 가용시간에서** 아래 셋이 v0보다 올라야 한다.

| 지표 | 뜻 |
|------|-----|
| 선수 topic 배치 커버율 | 다음 수업을 이해하는 데 필요한 topic 중 마감 전에 실제로 배치된 비율 |
| 근거 있는 SKIM/SKIP 수 | `evidence`가 붙은 압축·제외의 개수. 근거 없는 것은 세지 않는다 |
| MUST 배치율 | MUST로 낸 조각 중 배치된 비율 |

셋이 오르지 않으면 커밋 2는 "말을 그럴싸하게 하는 기능"이고 되돌린다. 이 기준이 없으면
판단 결과를 보고 "괜찮아 보이네"로 넘어가게 된다.

### 8.3 수동 eval (모델, 회귀 아님)

같은 픽스처 + 지시 "1~2주차를 제대로 못 했어. 다음 주 수업을 알아들을 정도로 따라잡는 계획을
짜줘."

- 목표가 "전부 정독"이 아니라 "다음 수업 복귀"로 잡히는가
- 자료구조가 rank 1인가 · Big-O가 ADT보다 앞서는가 (선수지식 선택)
- `USER_CONFIRMED` "Python 기본 문법 익숙" 맥락을 주입하면 Python topic이 SKIM인가 (압축)
- 조각에 READ 외 PRACTICE/RECALL이 있는가 (행동 다양화)
- 전략-조각 모순 warn이 0인가

**`LEARNED` 경로는 eval 항목에서 뺀다** — `topic_progress`가 0행이라 실사용에서는 보이지
않는다. 픽스처(`PlanBlockGeneratorV0Test`)로만 검증한다. "Python 기본 문법 익숙" 맥락은
eval 전에 `user_contexts`에 직접 넣고 끝나면 지운다.

## 9. 완료 기준

JSON 필드가 늘어난 것은 완료가 아니다. "1~2주차를 제대로 못 했어. 다음 주 수업을 알아들을
정도로 따라잡는 계획을 짜줘"를 넣었을 때:

- 목표가 재정의되고 (`goal`)
- 과목 순서가 다음 수업·선수지식·진행 상태로 설명되고 (`courses[].reason`)
- 압축·제외에 근거 출처가 붙고 (`topics[].evidence`)
- 조각마다 자료 위치와 완료 기준이 있고
- 수업 전 필요한 조각의 마감이 Timefold까지 살아서 배치 결과가 수업 전이고
- 알바·수업과 겹치지 않고
- 사용자가 초안에서 「이미 알아요」 한 번으로 가정을 고치고 재생성할 수 있으면

완료다. v0 대비 판단층이 **추가로** 내야 하는 것은 선수지식 선택 · 압축 · 행동 다양화
셋이고, 그것이 나오지 않으면 판단층은 값을 하지 못한 것이다.
