# 04. Requirements

> 현재 기준: 2026-10-25(부분 개정). REQ 번호는 바꾸지 않는다. 2026-08 목표 모델(PlanItem·ContextItem·DailyState)을 전제로 한 절은
> 현재 구조(`plan_versions`·`user_contexts`, 하루 상태 없음)로 바꾸거나 "미구현/종료"로 표시했다. 2026-08-15 이후 기능은 §13~§16에 요약하고
> 상세 계약은 해당 설계 문서가 기준이다. 옛 판은 git 이력에 있다.

이 문서는 구현 기준이 되는 요구사항을 관리한다. 새 기능은 아래 요구사항과 충돌하지 않아야 한다.

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
AI는 공식 PlanVersion, ExecutionItem을 직접 생성·수정·삭제하지 않는다. 모델은 구조화된 출력을 내고 서버가 검증해 적용한다.
예외: 상담에서 말한 사실의 기억(user_contexts, CONSULT_AUTO)과 상담 합의(ai_plan_briefs)는 서버가 원문 대조를 거쳐 바로 저장한다(REQ-CONTEXT-*).

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

REQ-AI-011 (미구현)
사용자는 외부 ChatGPT 대화 텍스트를 붙여넣거나 파일로 가져와 계획·컨텍스트 후보를 만들 수 있다.

REQ-AI-012 (미구현)
외부 대화에서 추출한 항목도 AIProposal 검토와 사용자 적용을 거쳐야 한다.

REQ-AI-013 (응답 계약 세부는 이후 바뀌었다 — 상담 턴 구조화 출력은 13번 §12.2)
AI 패널의 기본 동작은 자유 대화다. 인사·잡담·감정 표현·정보가 부족한 입력을 실행 조각으로
바꾸지 않는다. 모든 AI 턴은 CHAT / OFFER / PROPOSAL 중 하나이며, 모든 턴에 자연스러운
reply가 있어야 한다.

REQ-AI-014
CHAT과 OFFER 턴에서는 proposalItems가 항상 빈 배열이고 ai_proposals/ai_proposal_items
행을 만들지 않는다. PROPOSAL에서만 1~5개 항목을 만든다.

REQ-AI-015
목표·기간·제약 등 계획을 짤 정보가 충분해도 사용자가 아직 요청하지 않았다면 OFFER로
응답하고 초안 생성 여부를 먼저 물어본다. 사용자가 명시적으로 요청하거나 OFFER에 동의했을
때만 PROPOSAL을 만든다.

REQ-AI-016
사용자 메시지는 AI 호출 전에 PROCESSING 상태로 먼저 저장한다(ai_messages). AI 생성이나
구조화 파싱이 실패해도 사용자가 입력한 원문은 사라지지 않고 FAILED로 남는다. Proposal은
자신을 만든 ASSISTANT 메시지(source_message_id)를 참조하고, 그 ASSISTANT가 어떤 사용자
요청에 대한 응답인지는 ai_messages.reply_to_message_id로 추적한다 — 같은 관계를 두 컬럼이
중복 보관하지 않는다.

REQ-AI-017
동일 idempotencyKey로 재전송된 메시지는 AI를 다시 호출하지 않고 저장된 응답을 그대로
재생한다.

REQ-AI-018 (2026-08-07)
사용자가 "계획 초안 만들기"로 명시적으로 요청한 턴(CREATE_PROPOSAL)은 결과 계약을 엄격하게
검사한다: reply가 비어 있지 않고, 구분자와 구조화 JSON이 존재하며 파싱에 성공하고,
responseType=PROPOSAL이며, proposalItems가 1개 이상이어야 한다. 하나라도 어기면 CHAT으로
조용히 대체하지 않고 AI 생성 실패(기존 FAILED lifecycle, 503 AI_GENERATION_FAILED)로
처리한다. 이 경우 빈 ASSISTANT 메시지·AIProposal·ExecutionItem을 만들지 않으며 자동으로
재호출하지 않는다.

REQ-AI-019 (2026-08-07)
requestedAction과 무관하게, 모델 응답이 reply와 구조화 데이터 모두 비어 있거나
finishReason=LENGTH(토큰 상한 종료)로 응답의 일부가 빈 채로 끝났으면 실패로 처리한다.
max_completion_tokens에는 사용자에게 보이는 텍스트뿐 아니라 reasoning 토큰도 포함되므로,
토큰 상한만 올리는 것으로는 이 문제를 해결하지 않는다 — reasoning-effort/verbosity를
낮춰 reasoning 토큰 사용을 줄이는 것과 함께 적용한다.

REQ-AI-020 (2026-08-08)
사용자가 계획 생성을 원하더라도 계획 결과(범위·분량·순서·날짜·고정 시각·현실성)를 크게
바꾸는 핵심 정보가 부족하거나 현재 발언과 저장된 정보가 충돌하면 OFFER_PROPOSAL보다
ASK_CLARIFICATION을 우선한다. 핵심 정보는 계획 범위에 따라 달라지며 종료 시각·고정
일정·마감·사용 가능 시간 등일 수 있다. 모든 정보를 완벽하게 수집하는 설문이 아니다 —
영향이 작은 정보는 보수적으로 추정할 수 있고, 그 추정값을 사용자가 확정한 사실처럼
취급하지 않는다. 이미 최근 대화나 저장된 장기 컨텍스트에 있는 정보는 같은 것을 다시
묻지 않는다. 되물을 때는 핵심 질문 1~2개만 자연스러운 대화체로 묻고, 항목을 나열하는
설문 문구를 쓰지 않는다. 회귀 사례는
[09-ai-consultation-regression-cases.md](09-ai-consultation-regression-cases.md)에 기록한다.
```

## 3. 장기 컨텍스트 (2026-10-25 개정 — `user_contexts`, 13번 §12.1, 18번)

```text
REQ-CONTEXT-001
기억은 user_contexts 한 표에 둔다. 근거 유형은 STATED / SELF_REPORT / OBSERVED / INFERRED, 종류(fact_kind)는 진도·시험 범위·막힌 곳·해결 등이다.

REQ-CONTEXT-002
출처(source_type)는 USER_CONFIRMED / AI_SUGGESTION_APPROVED / CONSULT_AUTO / USER_EDITED / SELF_CHECK다.

REQ-CONTEXT-003
상담 턴은 기억을 승인 팝업 없이 저장할 수 있다(CONSULT_AUTO). STATED·SELF_REPORT는 quote가 이번 사용자 발화 안에 있어야 하고,
없으면 INFERRED로 낮춘다. OBSERVED는 서버만 만든다.

REQ-CONTEXT-004
INFERRED 기억을 사용자 확정 사실처럼 취급하지 않는다. 자기평가는 혼자 해낸 증거가 아니다.

REQ-CONTEXT-005
사용자는 기억을 고치고(옛 행 SUPERSEDED + 새 행 USER_EDITED) 지울 수 있다(WITHDRAWN — 같은 내용을 다시 저장하지 않는다).

REQ-CONTEXT-006
계획 생성에는 대상 과목(또는 과목 없음)·계획 기간과 겹치는 기억만 적용 범위와 함께 싣는다(findActiveAndStaleForPlan).

REQ-CONTEXT-007
계획 항목의 생성 이유는 회차 안의 인용 번호와 근거 스냅샷으로 추적한다(13번 §10). 별도 연결 표(plan_item_context_links)는 쓰지 않는다.

REQ-CONTEXT-008
기억은 course_id·topic_id·section_id·scope_start/end로 적용 범위를 가진다. 범위 밖으로 일반화하지 않는다.

REQ-CONTEXT-009
수명주기는 ACTIVE / STALE / SUPERSEDED / ARCHIVED / WITHDRAWN이다. 진도·시험 범위는 과목당 하나로 대체한다(18번).

REQ-CONTEXT-010
"이미 했어"라는 진술은 기억으로 판단에 쓰되 실행 항목의 완료 상태를 바꾸지 않는다.
```

## 4. 계획 요구사항 (2026-10-25 개정 — `plan_versions`, 11번)

```text
REQ-PLAN-001
기간 계획은 plan_versions 한 판이다(plan_key + version). 항목은 판 안의 스냅샷으로 보관하고 별도 PlanItem 표를 두지 않는다.

REQ-PLAN-002
초안은 ai_proposals(purpose=PERIOD_PLAN)로 만들고, 사용자가 확정하면 PlanVersion과 ExecutionItem이 생긴다. 재계획 확정은 같은 plan_key의 version+1이다.

REQ-PLAN-003
초안 목표 분량(target_minutes)은 서버가 소유한다(강도·가용시간·사용자가 말한 시간, 11번 §5-1·21번 §7).

REQ-PLAN-004
계획 항목 하나에서 0개 이상의 ExecutionItem을 만들 수 있다.

REQ-PLAN-005
계획 없이도 임시 ExecutionItem을 만들 수 있다.

REQ-PLAN-006
확정 전 생성될 ExecutionItem과 주간 배치를 미리 보여준다. 미리보기는 공식 데이터를 바꾸지 않는다.
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

REQ-EXECUTION-012 (2026-08-15)
이동(move)은 "언제 할지 변경"이다. 다른 날짜로 옮기는 것과, 같은 날 안에서 시각만
옮기는 것("오늘 뒤로")을 모두 포함하며 둘 다 MOVED 이벤트 하나로 기록한다.
같은 날 시각 이동은 TIME_FIXED 항목에만 허용한다 — 시각 없는 항목에 시각을 붙이는 것은
이동이 아니라 배치 형식 변경이다.
날짜도 시각도 달라지지 않는 이동 요청은 거부한다(MOVE_TARGET_DATE_INVALID).
보류(hold)는 이동과 다른 액션으로 유지한다 — 이동은 언제 할지 변경,
보류는 당분간 실행 대상에서 제외.
```

## 5.2 지나간 항목과 남은 오늘 다시 잡기 (2026-08-15)

```text
REQ-TODAY-001
"예정 시간이 지난 항목"은 오늘 날짜의 PLANNED이면서 TIME_FIXED이고 종료 시각이
현재 시각을 지난 항목이다. 저장되는 상태가 아니라 현재 시각으로 계산하는 판단이며,
이를 위해 새 status나 새 컬럼을 만들지 않는다.

REQ-TODAY-002
DONE / PARTIAL / HOLD / CANCELLED는 사용자가 이미 결론을 낸 상태이므로 대상이 아니다.
시각을 정하지 않은 항목(DATE_ONLY/UNSCHEDULED)은 현재 시각이 지났다는 이유만으로
지나간 항목으로 보지 않는다. 오늘이 아닌 날짜도 오늘 재조정 대상이 아니다.

REQ-TODAY-003
지나간 항목이 하나라도 있으면 오늘 화면의 "지금" 영역은 빈 상태 문구를 표시하지 않고
그 사실과 다시 잡기 진입점을 보여준다. 지나간 항목이 없으면 재조정 진입점을 띄우지 않는다.

REQ-TODAY-004
"남은 오늘"에서 지나간 항목과 앞으로 할 항목은 같은 목록에 섞지 않는다.
지나간 항목은 접을 수 있는 compact 묶음으로 표시한다.

REQ-TODAY-005
재조정은 항상 초안 -> 사용자 검토/수정 -> 명시적 적용 순서를 따른다. 진입 버튼을 누르는
것만으로는 어떤 데이터도 바뀌지 않고, 취소하면 저장된 계획에 아무 변화가 없다.

REQ-TODAY-006
재조정 적용은 기존 도메인 액션(move / reduce / hold / complete)만 사용한다.
status나 날짜를 직접 갱신해 execution_item_events 기록을 우회하지 않는다.
한 항목에 축소와 이동이 함께 필요하면 reduce -> move 순서로 호출하고 reduce가 돌려준
version을 이어 쓴다.

REQ-TODAY-007
재조정의 기본 범위는 오늘(TODAY)이다. 사용자가 다른 기간을 명시적으로 요청하지 않는 한
주간·월간 계획이나 새 목표로 확장하지 않는다.

REQ-TODAY-008
현재 상황 태그(기상 늦음 등)는 고정 enum이나 새 테이블로 만들지 않는다.
선택한 태그는 각 도메인 액션의 reason에 실려 execution_item_events에 남는다.
```

## 5.5 7일 범위 일정 후보 배치

```text
REQ-SCHEDULING-001
새 PROPOSAL 항목은 placementType=UNSCHEDULED로 만들 수 있다. 이 경우 실제 배치 날짜·시각은
모델이 정하지 않고 서버의 Timefold Solver가 계산한다.

REQ-SCHEDULING-002
가용시간은 기존 TIME_FIXED 실행 조각, 현재 대화에서 사용자가 명시한 사용 불가 시간, 사용자가
미리보기에서 고친 예외, Asia/Seoul 기준 기본 추정 시간대(근거 없을 때만, LOW 신뢰도)를 조합해
추정한다. 각 후보 시간은 source/confidence/reason을 가진다.

REQ-SCHEDULING-003
Timefold 배치는 계획 범위(최대 7일), 현재 시각 이후, 기존 일정·신규 후보 간 미겹침, 명시된
마감일 준수를 강한 제약으로 지킨다. 우선순위(MUST/SHOULD/OPTIONAL), 신뢰도, 하루 과부하,
여유시간은 선호 제약이다.

REQ-SCHEDULING-004
시간이 부족하면 낮은 우선순위 항목을 미배치로 남긴다. 미배치는 오류가 아니며 사유와 함께
표시한다. Solver나 서버가 미배치 항목을 임의로 축소·보류하지 않는다.

REQ-SCHEDULING-005
사용자가 가용시간 예외를 고치거나 항목을 특정 시각에 고정하면, 그 재계산은 OpenAI를 다시
호출하지 않고 Timefold만 다시 돈다.

REQ-SCHEDULING-006
배치 미리보기(가용시간 요약, 배치/미배치 결과)는 승인 전까지 공식 execution_items를
변경하지 않으며, 새로고침해도 마지막 계산 결과가 복원된다.

REQ-SCHEDULING-007
사용자가 최종 승인한 날짜·시간만 ExecutionItem을 만든다. 배치된 항목은 TIME_FIXED, 날짜만
확정한 항목은 DATE_ONLY, 배치하지 못했지만 남기기로 한 항목은 UNSCHEDULED로 생성한다.

REQ-SCHEDULING-008
Timefold 계산 모델(SchedulingTask/SchedulingPlan 등)은 ExecutionItem/AiProposalItem과
분리된 별도 모델이며, DB 엔티티에 Timefold 어노테이션을 붙이지 않는다.

REQ-SCHEDULING-009
Solver는 상시 실행되지 않는다. 사용자 요청(재배치)마다 짧은 시간 제한을 두고 새로
실행하고 버린다.
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
NOT_DONE은 completionPercent=0이어야 한다. (이 결과를 남기는 API는 아직 없다)

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
ExecutionItemEvent는 CREATED / MOVED / REDUCED / SPLIT / HOLD / RESUMED / REOPENED / CANCELLED / PRIORITY_CHANGED / DELETED / RESTORED를 사용한다.

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

## 8. 하루 상태 (미구현)

`daily_states`는 만들지 않았다. 하루 단위 설정(화면 모드·강도·컨디션)이 필요해지면 `day_settings`로 새로 만들고, 그때도 ExecutionItem의
부모로 두지 않는다. 옛 REQ-DAILY-001~004는 폐기한다.

## 9. 화면 요구사항

```text
REQ-UI-001
최상위 탭은 오늘 / 자료 / 프로젝트 / 계획 / 일정 / 기록을 사용한다(2026-10-25 개정, 02번).

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

REQ-UI-007 (미구현)
캘린더는 목록과 시간표가 안정된 뒤 구현한다. 지금은 일정 탭의 주간 보기까지다.

REQ-UI-008
1536x760 CSS px에서 좌측 내비게이션, 핵심 본문, 우측 AI 패널, 주요 액션이 스크롤 없이 보여야 한다.

REQ-UI-009
기본 글자 크기는 14px 이상이고 본문 대비는 4.5:1 이상이어야 한다.
```

## 10. 상태 불변조건

- 삭제된 항목은 오늘·계획·일정 일반 목록에 노출하지 않는다.
- 시작과 종료 중 하나만 존재하는 TIME_FIXED 항목을 허용하지 않는다.
- 종료가 시작보다 빠른 시간 범위를 허용하지 않는다.
- 완료 기록과 DONE 상태가 불일치하지 않아야 한다.
- PARTIAL 기록에는 남은 실행 조각이 반드시 연결돼야 한다.
- CANCELLED/HOLD 상태를 우회해 완료하지 않는다.
- sourceExecutionItemId가 자기 자신을 가리키지 않는다.
- 다른 사용자의 프로젝트·자료·기억·계획·ExecutionItem을 연결하지 않는다(모델이 준 id도 서버가 소유권을 확인한다).
- 오래된 AI 제안으로 최신 사용자의 수정을 덮어쓰지 않는다.

## 11. 마이그레이션 요구사항

REQ-MIGRATION-001~005(todos·schedule_blocks·plan_item_events·daily_plans 전환)는 2026-08-03 이관, 2026-08-23 원본 삭제로 **종료**됐다
(`docs/sql/2026-08-23-remove-legacy-tables.sql`). 아래는 계속 유효하다.

```text
REQ-MIGRATION-006
없는 실제 수행 시간은 추정하지 않는다.

REQ-MIGRATION-007
이상 데이터를 보정하면 원본을 수정하지 않고 보정 내역을 별도 기록한다.

REQ-MIGRATION-008
실제 이메일, 일기 원문, 비밀번호 해시가 포함된 덤프를 Git에 커밋하지 않는다.
```

## 12. 구현 상태

2026-08-04판의 IMPL-001~011은 그 시점 기록이라 지웠다(git 이력에 있다). 지금의 구현 범위는 diary-api README "구현 범위"와 99-changelog가 기준이다.
그때 "구현하지 않음"이었던 부분 수행(PARTIAL·SPLIT), 항목 제외(부분 적용), 기존 조각의 재계획(EXECUTION_CHANGE), 기억(user_contexts)은 이후 구현됐다.
외부 ChatGPT 대화 가져오기, 하루 상태, 캘린더, NOT_DONE 기록은 아직 없다.

## 13. 자료 (15번)

```text
REQ-MATERIAL-001
자료는 업로드 즉시 자동 분석한다(본문 추출 → 자료 구간 → 토픽 연결 변경안). 분석 결과가 확정 구조를 바로 바꾸지 않고, 정리안은 사용자가 적용한다.

REQ-MATERIAL-002
형식은 PDF·PPTX·HWP·HWPX·IPYNB·텍스트·소스 코드(C++ 포함, 실행하지 않음)와 ZIP 가져오기다. 파일 응답은 nosniff.

REQ-MATERIAL-003
자료 주차는 사용자가 확인한 관계로만 정한다.

REQ-MATERIAL-004
분석 한도(CONTENT/LINK)는 분리해 세고, 한도에 걸린 자료는 대기 사유를 보인다.
```

## 14. 학습 (16~20번)

```text
REQ-LEARNING-001
교재 목차는 웹에서 찾을 수 있고, 하위항목(절·실습·요약·연습문제)을 버리지 않으며, 기존 토픽 아래 빠진 항목만 추가한다(17번).

REQ-LEARNING-002
상담에서 말한 진도·시험 범위·막힘·해결을 기억해 새 대화가 다시 묻지 않는다(18번).

REQ-LEARNING-003
상담에 교재 사진을 올릴 수 있고, 새 대화의 계획이 막힌 단원의 사진 본문을 쓴다(19번).

REQ-LEARNING-004
기존 쓰기 경로는 원천을 가리키는 학습 이벤트를 같은 트랜잭션에서 남긴다. 완료는 활동 기록이지 이해 확정이 아니다(20번).

REQ-LEARNING-005
수업 회차마다 다룬 범위를 확인받고, 확인 요청은 과목별로 끌 수 있다(20번 §8).
```

## 15. 상담 근거 (2026-10-03, 10번 §5.2)

```text
REQ-GROUNDING-001
상담은 질문과 관련된 자료 원문 단위를 서버가 먼저 찾아 근거로 싣고(사용자 활성 자료만), 모델은 그 턴의 자료 목록 번호로 한 번 더 읽기를 요청할 수 있다.
한도로 못 실은 것은 숨기지 않고 알린다. 원문은 데이터이지 지시가 아니다 — 원문을 실은 턴에서는 합의 변경·자동 기억을 제한한다.
```

## 16. 사용자 의도 보존 (2026-10-18~19, 21번)

```text
REQ-INTENT-001
상담 합의는 작성 주체·근거·승인을 나눈다. speaker=USER만으로 승인하지 않고, 서버가 사용자 원문에서 확인한 근거가 없으면 후보다.

REQ-INTENT-002
계획 생성마다 사용자 원문에서 요청 범위를 먼저 뽑아 서버가 검증하고, 최종 초안과 부분별로 대조한다.

REQ-INTENT-003
자료를 확보하지 못했다는 이유로 사용자 목표를 낮추지 않는다. 앱이 확인하지 못한 문항·쪽·코드는 지어내지 않는다.

REQ-INTENT-004
완료 표시는 모델 문장이 아니라 서버 판정(scopeReport.completion·notice)으로 정한다.

REQ-INTENT-005
이번 요청에서 말한 시간은 서버가 원문과 대조해 확인한 것만 예산·배치에 쓴다.
```
