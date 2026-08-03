# 04. Requirements

이 문서는 2026-08-03 이후 구현 기준이 되는 요구사항을 관리한다. 현재 코드가 아직 레거시 모델을 사용하더라도 새 기능은 아래 목표 구조와 충돌하지 않아야 한다.

## 1. 공통 요구사항

```text
REQ-COMMON-001
백엔드는 Spring Boot + MyBatis Mapper/XML 패턴을 유지한다.

REQ-COMMON-002
모든 사용자 데이터 조회·수정·삭제 쿼리는 user_id 소유권 조건을 포함한다.

REQ-COMMON-003
삭제는 기본적으로 soft delete를 사용하며 삭제된 항목은 일반 조회에 노출하지 않는다.

REQ-COMMON-004
동일한 실행 상태와 수행 결과를 여러 테이블이 각각 소유하지 않는다.

REQ-COMMON-005
화면 이름과 내부 모델 이름은 분리할 수 있지만 문서·API·DB 사이의 의미는 일치해야 한다.
```

## 2. AI 상담과 제안

```text
REQ-AI-001
AI는 공식 PlanItem, ExecutionItem, ContextItem을 직접 생성·수정·삭제하지 않는다.

REQ-AI-002
AI 결과는 먼저 AIProposal과 AIProposalItem으로 저장한다.

REQ-AI-003
사용자는 제안 항목별로 제목, 날짜, 시간, 순서, 분량, 우선순위를 수정할 수 있다.

REQ-AI-004
사용자는 대화로도 제안을 다시 조정할 수 있다.

REQ-AI-005
사용자가 적용한 항목만 공식 테이블에 반영한다.

REQ-AI-006
제안 상태는 PROPOSED / APPLIED / MODIFIED_APPLIED / DISMISSED / EXPIRED를 사용한다.

REQ-AI-007
AI 제안은 originalPayload와 사용자가 고친 editedPayload를 구분해 보존한다.

REQ-AI-008
기존 항목 변경 제안은 생성 당시 baseVersion을 저장한다.

REQ-AI-009
적용 시 현재 version이 baseVersion과 다르면 409 Conflict로 거부한다.

REQ-AI-010
거절한 제안은 DISMISSED로 남겨 같은 제안을 반복 강요하지 않게 한다.

REQ-AI-011
사용자는 외부 ChatGPT 대화 텍스트를 붙여넣거나 파일로 가져와 계획·컨텍스트 후보를 만들 수 있다.

REQ-AI-012
외부 대화에서 추출한 항목도 AIProposal 검토와 사용자 적용을 거쳐야 한다.
```

## 3. 장기 컨텍스트

```text
REQ-CONTEXT-001
ContextItem은 GOAL / DECISION / CONSTRAINT / PREFERENCE / CONCERN / OBSERVATION / INSIGHT / UNKNOWN 유형을 가진다.

REQ-CONTEXT-002
ContextItem은 USER_INPUT / AI_CONVERSATION / EXECUTION_DERIVED 출처를 가진다.

REQ-CONTEXT-003
확인 상태는 UNCONFIRMED / AI_INFERRED / USER_CONFIRMED로 구분한다.

REQ-CONTEXT-004
AI_INFERRED 컨텍스트를 사용자 확정 사실처럼 취급하지 않는다.

REQ-CONTEXT-005
사용자는 컨텍스트 후보의 내용과 확인 상태를 수정하고 활성·보관 처리할 수 있다.

REQ-CONTEXT-006
다음 상담에는 현재 요청과 관련된 활성 컨텍스트만 출처·확인 상태와 함께 제공한다.

REQ-CONTEXT-007
PlanItem과 ExecutionItem은 생성 이유를 설명할 수 있도록 관련 ContextItem과 연결될 수 있다.

REQ-CONTEXT-008
ContextItem은 선택적 validFrom/validTo로 유효 기간을 표현할 수 있다.

REQ-CONTEXT-009
새 ContextItem이 과거 항목을 대체하면 supersedesContextItemId와 SUPERSEDED 수명주기를 남긴다.

REQ-CONTEXT-010
사용자가 컨텍스트를 철회하면 WITHDRAWN 상태와 withdrawnAt을 남기고 이후 계획 생성에서 제외한다.
```

## 4. 계획 요구사항

```text
REQ-PLAN-001
PlanItem은 제목, 설명, 선택적 시작일·종료일, 상태, 우선순위, version을 가진다.

REQ-PLAN-002
PlanItem 상태는 DRAFT / ACTIVE / HOLD / DONE / CANCELLED를 사용한다.

REQ-PLAN-003
사용자는 PlanItem을 날짜별·주차별·단계별·월별로 볼 수 있다.

REQ-PLAN-004
PlanItem 하나에서 0개 이상의 ExecutionItem을 만들 수 있다.

REQ-PLAN-005
PlanItem이 없어도 임시 ExecutionItem을 만들 수 있다.

REQ-PLAN-006
계획 적용 전 생성될 ExecutionItem과 기존 항목 변경 내용을 미리 보여준다.
```

## 5. 실행 조각 요구사항

```text
REQ-EXECUTION-001
ExecutionItem은 UNSCHEDULED / DATE_ONLY / TIME_FIXED placementType을 가진다.

REQ-EXECUTION-002
UNSCHEDULED는 날짜와 시작·종료 시각을 가지지 않는다.

REQ-EXECUTION-003
DATE_ONLY는 scheduledDate만 가지고 시작·종료 시각을 가지지 않는다.

REQ-EXECUTION-004
TIME_FIXED는 scheduledDate, scheduledStartAt, scheduledEndAt을 모두 가진다.

REQ-EXECUTION-005
TIME_FIXED의 종료 시각은 시작 시각보다 이후여야 한다.

REQ-EXECUTION-006
TIME_FIXED의 scheduledDate는 scheduledStartAt의 달력 날짜와 일치한다.

REQ-EXECUTION-007
ExecutionItem 상태는 PLANNED / PARTIAL / HOLD / DONE / CANCELLED를 사용한다.

REQ-EXECUTION-008
ExecutionItem 우선순위는 MUST / SHOULD / OPTIONAL을 사용한다.

REQ-EXECUTION-009
사용자는 제목·설명·날짜·시간·예상 분량·순서·우선순위를 직접 수정할 수 있다.

REQ-EXECUTION-010
Today와 Execution 화면은 동일한 ExecutionItem 원본을 조회한다.

REQ-EXECUTION-011
모든 공식 변경은 version을 1 증가시킨다.
```

## 6. 실제 수행 기록

```text
REQ-RECORD-001
ExecutionRecord outcome은 COMPLETED / PARTIAL / NOT_DONE을 사용한다.

REQ-RECORD-002
COMPLETED는 completionPercent=100이어야 한다.

REQ-RECORD-003
PARTIAL은 completionPercent=1~99이며 남은 ExecutionItem을 반드시 연결한다.

REQ-RECORD-004
NOT_DONE은 completionPercent=0이어야 한다.

REQ-RECORD-005
실제 시작·종료 시각과 actualMinutes를 모르면 NULL로 저장하며 추정하지 않는다.

REQ-RECORD-006
ExecutionItem을 DONE으로 만들 때 COMPLETED ExecutionRecord도 같은 트랜잭션에서 생성한다.

REQ-RECORD-007
유효한 COMPLETED ExecutionRecord 없이 ExecutionItem을 DONE으로 저장하지 않는다.

REQ-RECORD-008
부분 수행은 PARTIAL Record, 남은 ExecutionItem, SPLIT Event를 같은 트랜잭션에서 생성한다.
```

## 7. 조정 사건

```text
REQ-EVENT-001
ExecutionItemEvent는 CREATED / MOVED / REDUCED / SPLIT / HOLD / RESUMED / REOPENED / CANCELLED / PRIORITY_CHANGED / DELETED를 사용한다.

REQ-EVENT-002
이동은 날짜·시간 변경과 MOVED Event 저장을 같은 트랜잭션에서 처리한다.

REQ-EVENT-003
축소는 변경 전후 상태와 version을 REDUCED Event에 보존한다.

REQ-EVENT-004
보류는 상태를 HOLD로 바꾸고 HOLD Event를 저장한다.

REQ-EVENT-005
HOLD 또는 CANCELLED 항목을 바로 DONE으로 변경하지 않고 먼저 재개 또는 재열기 처리한다.

REQ-EVENT-006
삭제는 soft delete와 DELETED Event를 같은 트랜잭션에서 처리한다.

REQ-EVENT-007
Event는 실제 수행 결과를 대신하지 않는다. 완료·부분·미수행 사실은 ExecutionRecord가 소유한다.
```

## 8. 하루 상태

```text
REQ-DAILY-001
DailyState는 userId + stateDate 기준으로 유일하다.

REQ-DAILY-002
DailyState는 viewMode, viewModeSource, intensity, conditionNote, focusNote, memo를 가진다.

REQ-DAILY-003
DailyState는 ExecutionItem의 부모가 아니다.

REQ-DAILY-004
오늘 화면은 오늘 DailyState와 오늘 ExecutionItem을 별도로 조회해 합성한다.
```

## 9. 화면 요구사항

```text
REQ-UI-001
최상위 탭은 오늘 / 계획 / 실행 / 기록을 사용한다.

REQ-UI-002
AI 상담 패널은 현재 화면과 선택 항목을 유지한 채 우측에서 열릴 수 있다.

REQ-UI-003
대화 중 변경안은 신규·변경·삭제 상태가 구분되는 ghost/diff로 표시한다.

REQ-UI-004
미리보기와 공식 상태는 시각적으로 구분한다.

REQ-UI-005
사용자는 변경안 전체 또는 일부만 적용할 수 있다.

REQ-UI-006
목록의 diff 표현을 먼저 검증하고 동일 계약을 시간표에 적용한다.

REQ-UI-007
캘린더는 목록과 시간표가 안정된 뒤 구현한다.

REQ-UI-008
1536x760 CSS px에서 좌측 내비게이션, 핵심 본문, 우측 AI 패널, 주요 액션이 스크롤 없이 보여야 한다.

REQ-UI-009
기본 글자 크기는 14px 이상이고 본문 대비는 4.5:1 이상이어야 한다.
```

## 10. 상태 불변조건

- 삭제된 항목은 오늘·계획·실행 일반 목록에 노출하지 않는다.
- 시작과 종료 중 하나만 존재하는 TIME_FIXED 항목을 허용하지 않는다.
- 종료가 시작보다 빠른 시간 범위를 허용하지 않는다.
- 완료 기록과 DONE 상태가 불일치하지 않아야 한다.
- PARTIAL 기록에는 남은 실행 조각이 반드시 연결돼야 한다.
- CANCELLED/HOLD 상태를 우회해 완료하지 않는다.
- sourceExecutionItemId가 자기 자신을 가리키지 않는다.
- 다른 사용자의 PlanItem, ContextItem, ExecutionItem을 연결하지 않는다.
- 오래된 AI 제안으로 최신 사용자의 수정을 덮어쓰지 않는다.

## 11. 마이그레이션 요구사항

```text
REQ-MIGRATION-001
기존 todos와 schedule_blocks는 execution_items로 통합한다.

REQ-MIGRATION-002
기존 plan_item_events는 execution_item_events와 execution_records로 역할을 분리한다.

REQ-MIGRATION-003
기존 daily_plans는 daily_states로 복사한다.

REQ-MIGRATION-004
레거시 원본은 전환 검증 전까지 삭제하지 않는다.

REQ-MIGRATION-005
신규 구조와 레거시 구조에 동시에 쓰지 않는다.

REQ-MIGRATION-006
없는 실제 수행 시간은 추정하지 않는다.

REQ-MIGRATION-007
이상 데이터를 보정하면 원본을 수정하지 않고 보정 내역을 별도 기록한다.

REQ-MIGRATION-008
실제 이메일, 일기 원문, 비밀번호 해시가 포함된 덤프를 Git에 커밋하지 않는다.
```

## 12. 구현 상태 (2026-08-04)

이 절은 위 목표 요구사항 중 실제로 코드로 구현된 범위를 표시한다. 여기 없는 REQ는 아직 목표 설계 상태다.

```text
IMPL-001
REQ-EXECUTION-001~011, REQ-RECORD-006/007을 execution_items/execution_records/execution_item_events로
구현했다. Today 화면(TodayView)이 execution_items를 실제로 읽고 쓴다. schedule_blocks/todos에는
더 이상 쓰지 않는다(이중 저장 없음).

IMPL-002
API: GET/POST /api/execution-items, GET /api/execution-items/pending,
POST /api/execution-items/{id}/{complete|reopen|move|reduce|hold}, DELETE /api/execution-items/{id}.
모든 조회·수정에 user_id 소유권 조건을 걸고, 공식 변경마다 version을 1 증가시키며 요청 버전이
서버 값과 다르면 409 Conflict를 반환한다.

IMPL-003
REQ-AI-002/005/006/009(AIProposal 초안 저장, 상태값, 사용자 적용 전 미반영, baseVersion류 재검증 정신)를
ai_proposals/ai_proposal_items로 구현했다. 단 이번 범위는 REQ-AI-003(항목별 날짜·시간·순서·분량 수정)의
일부(제목/설명/예상 시간/우선순위 편집)만 지원하고, REQ-AI-004(대화로 재조정), REQ-AI-011/012(외부
ChatGPT 대화 가져오기)는 구현하지 않았다.

IMPL-004
API: POST/GET /api/ai/proposals, POST /api/ai/proposals/{id}/apply. LLM 호출(Spring AI ChatClient,
OpenAI 단일 구현체)은 DB 트랜잭션 밖에서 수행하고, 검증까지 마친 결과만 짧은 트랜잭션으로 저장한다.
구조화 출력 파싱·검증에 실패하면 ai_proposals/ai_proposal_items를 저장하지 않는다.

IMPL-005
적용(apply)은 proposal_id+user_id 행 잠금(SELECT ... FOR UPDATE) 하에 헤더 상태가 PROPOSED인지
재확인한 뒤, 모든 ProposalItem을 execution_items로 생성하고 각 CREATED 이벤트를 저장하고 헤더/항목
상태를 갱신하는 하나의 트랜잭션으로 처리한다. 같은 Proposal을 다시 적용하면 409 Conflict를 반환하고
중복 생성하지 않는다.

IMPL-006
REQ-UI-005(전체 또는 일부 적용) 중 이번 범위는 "전체 적용"만 구현했다. 사용자가 수정 없이 그대로
적용한 항목은 APPLIED, 값을 고쳐 적용한 항목은 MODIFIED_APPLIED다. 헤더는 하나라도 수정된 항목이
있으면 MODIFIED_APPLIED, 전부 그대로면 APPLIED다. PARTIALLY_APPLIED는 없다. 항목별 부분 적용은
후속 범위다.

IMPL-007
AI가 만드는 execution_item 제안은 항상 placement_type='DATE_ONLY'다. TIME_FIXED AI 추천은 후속 범위다.

IMPL-008
AI_PROVIDER=none이거나 API 키가 없어도 서버 부팅과 테스트는 정상 동작한다. 이 상태에서 제안 생성을
호출하면 503 AI_NOT_CONFIGURED를 반환한다.

IMPL-009
상담 원문, AI 원문 응답 전체, API 키, JWT는 로그에 남기지 않는다. /api/ai/**와 /api/execution-items/**는
permitAll로 열지 않는다.

IMPL-010
이번 범위에서 구현하지 않은 것: REQ-PLAN-*(PlanItem 전체 CRUD), REQ-CONTEXT-*(ContextItem),
REQ-DAILY-*(DailyState), REQ-RECORD-003/008 및 REQ-EVENT-*의 부분 수행(PARTIAL)·SPLIT 흐름,
REQ-UI-003(ghost/diff 미리보기), 외부 ChatGPT 대화 가져오기, 캘린더.
```
