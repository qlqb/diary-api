# 99. Change Log

## 2026-08-05 (2차) — AI 상담 안전성 보강: 중복 호출 차단·동시 요청 직렬화·트랜잭션 경계

바로 아래 "AI 상담 구조 개편" 작업으로 CHAT/OFFER/PROPOSAL 대화 흐름 자체는 이미 있었다. 이번
작업은 그 흐름을 다시 설계하지 않고, 실제 운영에서 위험한 구멍들을 막았다.

- **사용자 메시지당 OpenAI 호출 최대 1회를 구조로 보장한다.** `spring.ai.retry.max-attempts=1`로
  Spring AI 자체 재시도(RetryTemplate)도 껐다 — 코드에서 Reactor `retry()`/`retryWhen()`,
  재귀 AI 호출, 파싱 실패 후 재생성, OFFER/PROPOSAL 판단용 추가 호출은 애초에 쓰지 않는다
  (구분자 기반 단일 호출 설계는 유지).
- **대화방 동시 요청을 DB로 직렬화한다.** `ai_conversations.active_request_message_id` /
  `active_request_started_at`을 추가하고, `AiTurnLifecycleService.prepareTurn()`이
  `SELECT ... FOR UPDATE`로 행을 잠근 뒤 원자적으로 진행 권한을 확인·획득한다. 이미 다른
  요청이 진행 중이면(그리고 오래되지 않았으면) OpenAI를 부르지 않고 `409 AI_CONVERSATION_BUSY`를
  반환한다 — 이 예외는 아직 SSE 스트림을 시작하기 전에 던져지므로 실제 HTTP 409로 응답한다.
  실제 로컬 DB에 두 스레드로 동시 요청을 보내 정확히 하나만 진행하고 나머지는 막히는 것을
  통합 테스트로 확인했다(`AiTurnLifecycleServiceConcurrencyTest`).
- **idempotency가 중복 INSERT 차단을 넘어 실제로 동작한다.** `ai_messages.status`를
  PROCESSING/COMPLETED/FAILED 3상태로 나눴다. 동일 idempotencyKey 재전송 시: COMPLETED면
  저장된 응답을 재생(AI 재호출 없음), PROCESSING이면 409로 막고(AI 재호출 없음), FAILED면
  자동 재시도하지 않고 막는다(사용자가 새 키로 다시 시도해야 한다).
- **서버 비정상 종료로 남는 오래된 PROCESSING을 회수한다.** 설정된 타임아웃(`ai.request.timeout-seconds`,
  기본 90초) + 여유 버퍼(`ai.conversation.stale-lock-buffer-seconds`, 기본 30초)를 넘긴
  요청만 명시적으로 FAILED 처리하고 잠금을 회수한다 — 아직 살아있을 가능성이 있는 요청을
  임의로 탈취하지 않는다.
- **턴에 서버 타임아웃을 건다.** `Flux.timeout(Duration.ofSeconds(ai.request.timeout-seconds))`로
  AI 스트리밍을 강제 종료한다. 타임아웃·오류·연결 종료 모두 재호출 없이 요청을 FAILED로
  종료하고 대화방 잠금을 해제한다.
- **SSE 취소를 실제로 처리한다.** 브라우저 연결이 끊기면(`SseEmitter.onTimeout/onError/onCompletion`)
  Reactor 구독의 `Disposable`을 dispose해 업스트림 스트림까지 취소를 전파하고, 진행 중이던
  요청을 FAILED로 정리한다. 완료 처리와 연결-종료 처리가 경합해도 원자적 플래그
  (`SseAiTurnEventSink.markTerminatedOnce()`)로 한쪽만 실행되게 막아 메시지·Proposal이
  중복 저장되지 않는다.
- **AI 스트리밍 중 DB 커넥션을 붙잡지 않는다.** `AiConversationService`에는 `@Transactional`을
  걸지 않는다. 실제 쓰기는 스트리밍 전/후의 짧은 트랜잭션(`AiTurnLifecycleService.prepareTurn`
  / `completeTurnSuccess` / `completeTurnFailure`)에만 있고, 그 사이 네트워크 I/O 구간은
  트랜잭션 밖이다.
- **ai_proposals ↔ ai_messages 연결 방향을 통일했다.** 기존에 `ai_messages.proposal_id`와
  `ai_proposals.source_message_id`가 같은 관계를 양쪽에서 들고 있었다. `ai_messages.proposal_id`를
  없애고 `ai_proposals.source_message_id`(이제 그 제안을 만든 ASSISTANT 메시지를 가리킨다,
  UNIQUE)만 남겼다. 어떤 USER 요청에 대한 응답인지는 새로 추가한
  `ai_messages.reply_to_message_id`(UNIQUE)로 추적한다. 이 SQL이 적용되지 않은 로컬 DB였음을
  먼저 확인했으므로 `docs/sql/2026-08-05-ai-consultation-conversations.sql` 자체를 최종
  구조로 수정했다(이미 적용된 환경이 있다면 이 파일을 다시 실행하지 말고 별도 ALTER를 새로
  작성해야 한다).
- `ai_usage_logs`에 `request_message_id`/`result_status`(SUCCESS/FAILED/CANCELLED/TIMEOUT)/
  `error_code`/`provider_request_id`를 추가하고 토큰 컬럼에 `>= 0` CHECK를 걸었다.
  `cached_tokens`는 Spring AI 표준 API로 얻을 수 없어 계속 NULL만 허용한다(추측해 채우지 않는다).
- **gpt-5 계열 출력 토큰 설정 오류를 고쳤다.** `spring.ai.openai.chat.options.max-tokens`는
  gpt-5류 reasoning 모델에 대해 OpenAI가 거부하는 필드다 — `max-completion-tokens`로 바꿨다.
  이 상태로는 실제 키가 있어도 매 호출이 실패했을 것이다.
- **실행 조각 상태 전이 구멍을 메웠다.** `ExecutionEventType.RESUMED`가 정의만 있고 이를 만드는
  경로가 없었고, `complete()`가 HOLD에서도 곧장 DONE으로 전환되는 것을 허용하고 있었다.
  `ExecutionItemService.resume()`(HOLD → PLANNED, `POST /api/execution-items/{id}/resume`)을
  추가하고 `complete()`는 PLANNED에서만 허용하도록 좁혔다 — 프론트에 hold 진입 UI가 아직 없어
  실사용 영향은 없다.
- **`isFixed` 오추론을 고쳤다.** `diary-ui`의 `toFrontendExecutionItem`이
  `isFixed: placementType === 'TIME_FIXED'`로 값을 만들고 있었다. `isFixed`(이동·축소·보류를
  막는 잠금 속성)와 `placementType`(TIME_FIXED=시작·종료 시각이 정해진 배치 형식)은 다른
  개념이고 백엔드에는 아직 잠금 속성 자체가 없다 — 항상 `false`로 바꿨다(TIME_FIXED 항목도
  완료·이동·축소가 막히지 않아야 한다).
- 프론트 `AiPanelShell`에 `AbortController`를 연결했다: 컴포넌트가 실제로 unmount되면 진행
  중인 스트림을 취소하고, 취소 후 자동으로 다시 연결하지 않는다. 스트리밍 중 전송 버튼/Enter
  중복 전송 차단은 이미 있던 `sending` 가드로 충분해 별도 변경 없이 테스트로 고정했다.
- 프론트에 처음으로 테스트 인프라(vitest + @testing-library/react)를 추가했다. 중복 전송
  차단, unmount 시 abort, delta 누적이 말풍선 하나에만 반영되는지, OFFER 카드 렌더링을
  검증한다.
- **알려진 사실**: `실행` 탭의 계획별 묶기(`ExecutionView`)는 아직 `mock/executionMock.js`의
  `MOCK_PLAN_ITEMS`/`MOCK_PLANS` 정적 데이터를 쓴다(`items` prop 자체는 실제 `execution_items`).
  이번 작업 범위가 아니라 손대지 않았다.
- **DB 마이그레이션 적용 순서**: 로컬 개발 DB(`memo`, MariaDB 10.4)에 정보 스키마를 먼저
  조회해 미적용 상태(`ai_conversations` 등 부재, `ai_proposals.conversation_id`는
  VARCHAR(100)이고 전량 NULL)를 확인한 뒤 `docs/sql/2026-08-05-ai-consultation-conversations.sql`을
  그대로 적용했다. `DROP TABLE`/`TRUNCATE`/임의 데이터 삭제는 없다.

## 2026-08-05 — AI 상담 구조 개편: 강제 제안 생성 → 자유 대화 + CHAT/OFFER/PROPOSAL

이전 AI 패널은 이름만 상담이고 실제로는 자연어 입력 1회 → 실행 조각 1~5개 강제 생성이었다.
이번 변경으로 실제 다회차 상담으로 바꿨다.

- `ai_conversations`/`ai_messages` 테이블을 추가했다(`docs/sql/2026-08-05-ai-consultation-conversations.sql`,
  Flyway/Liquibase 미도입 — 기존 `docs/sql/*.sql` 날짜 파일 컨벤션대로 수동 적용).
  `ai_proposals`에 `source_message_id`를 추가하고 `conversation_id` 타입을 실제 대화 PK와
  맞춰 BIGINT로 바꿨다(그동안 항상 NULL로만 저장되던 컬럼이라 기존 데이터 영향 없음).
- AI 응답을 `AiResponseType`(CHAT/OFFER/PROPOSAL) 3상태 계약으로 바꿨다. 시스템 프롬프트에서
  "설명문·인사말 금지, 무조건 1~5개 생성" 규칙을 제거하고, 먼저 대화하고 정보가 충분해도
  사용자가 요청하기 전에는 초안을 만들지 않는 원칙으로 재작성했다. 전역 `MIN_ITEMS=1` 강제
  검증을 제거하고 PROPOSAL 응답에만 1~5개 검증을 건다.
- `POST /api/ai/proposals`(강제 생성) 엔드포인트를 없앴다. 대신
  `POST /api/ai/conversations`, `GET/POST /api/ai/conversations/{id}/messages`를 새로 만들었다.
  메시지 전송(POST)은 SSE로 스트리밍한다: `message.started/message.delta/offer.ready/
  proposal.ready/message.completed/message.error`. `GET /api/ai/proposals/{id}`,
  `POST /api/ai/proposals/{id}/apply`는 그대로 재사용한다.
- 사용자 메시지는 AI 호출 전에 먼저 저장해 AI 실패·파싱 실패에도 원문이 남는다. 완료된
  ASSISTANT 응답은 스트림이 끝까지 성공했을 때 한 번만 저장한다. `idempotencyKey`가 같은
  재전송은 AI를 다시 부르지 않고 저장된 응답을 재생한다.
- 스트리밍과 구조화 응답(CHAT/OFFER/PROPOSAL 판단 + PROPOSAL 항목)을 사용자 메시지당 모델
  호출 1회로 유지했다. Spring AI 2.0에서 `.stream()`과 `.entity()`(구조화 출력)는 같은 호출에서
  함께 못 쓴다(entity()는 완결된 응답이 있어야 스키마 파싱이 가능). 대신 시스템 프롬프트가
  "자연어 reply 먼저, 그 다음 줄에 고정 구분자, 그 아래에만 JSON"을 지키게 하고
  `AiStreamParser`가 그 경계를 스트리밍 중에 찾는다. reply는 토큰 단위로 즉시 보여주고,
  구분자 뒤 JSON은 스트림이 끝난 뒤에만 파싱해 OFFER/PROPOSAL을 확정한다. 별도의 분류용
  2차 AI 호출은 추가하지 않았다.
- AI가 만드는 실행 조각은 이제 DATE_ONLY와 TIME_FIXED를 모두 지원한다(그동안
  `createFromApprovedProposal`이 무조건 DATE_ONLY로 덮어썼다). `ExecutionItemService`의
  배치 무결성 검증에 DATE_ONLY인데 시각이 채워진 경우(`TASK_MUST_NOT_HAVE_TIME`) 케이스가
  빠져 있던 것도 이번에 같이 막았다.
- Proposal 적용 시 사용자가 뺀 항목은 `DISMISSED`로 남기고 `execution_items`를 만들지
  않는다(`excludedItemIds`). 여러 항목의 `order_index`는 항상 그 날짜의 기존 최대값 다음부터
  이어 붙인다. 동시 apply 중복 생성 차단(`SELECT ... FOR UPDATE` + 상태 재확인)은 기존 구현이
  이미 만족하고 있어 그대로 유지했다.
- OpenAI 429(쿼터/결제)를 `AI_GENERATION_FAILED`와 분리해 `AI_QUOTA_EXCEEDED`(429)로 노출하고,
  프론트에 "AI 사용 한도 또는 결제 상태를 확인한 뒤 다시 시도해 주세요" 메시지를 보여준다.
  `spring.ai.retry.max-attempts=2`로 과도한 자동 재시도를 막았다. `ai_usage_logs`에
  모델/토큰 수/요청 ID 메타데이터만 남기고(전체 프롬프트·API 키 제외), 사용자별 일/월 호출
  한도를 `ai.usage.daily-limit`/`ai.usage.monthly-limit` 설정값으로 제어한다(Redis 등 외부
  시스템 도입 없이 기존 로그 테이블 집계만 사용).
- 컨텍스트는 매번 전체 대화를 보내지 않는다. 서버가 최근 메시지 최대 `ai.context.recent-message-limit`
  (기본 6)개만 모아 보낸다. 대화 요약(`ai_conversations.summary`) 컬럼은 미리 만들어 뒀지만
  이번 범위에서 요약을 생성하는 별도 AI 호출은 추가하지 않았다 — 사용자 메시지마다 두 번째
  AI 호출을 만들지 말라는 제약 때문에, 다음 버전 과제로 남긴다.
- 프론트 `AiPanelShell`을 1회성 생성 폼에서 스트리밍 대화 UI로 다시 만들었다: 말풍선,
  실시간 스트리밍 표시, OFFER 단일 액션 버튼, PROPOSAL 초안 카드(점선 테두리 + "AI 초안"
  배지, 카드별 제외 토글, 카드별 적용 버튼 없음), 하단 단일 "오늘에 적용" 버튼. `api.js`에
  POST 기반 SSE를 직접 파싱하는 스트리밍 클라이언트를 추가했다(네이티브 EventSource는
  POST와 Authorization 헤더를 못 보내서 fetch의 ReadableStream을 직접 읽는다).
- Today 화면은 이미 `execution_items` 하나만 읽고 쓰는 상태였다(2026-08-04에 완료). 이번
  작업에서 새로 발견된 문제는 없었고, AI 적용 결과가 그대로 반영되는 것만 재확인했다.
- 알려진 한계: 이 저장소의 로컬 개발 DB에는 아직 새 마이그레이션이 적용되지 않았다 —
  `docs/sql/2026-08-05-ai-consultation-conversations.sql`을 수동으로 실행해야
  `POST /api/ai/conversations`가 동작한다(적용 전에는 테이블 없음 오류). `OPENAI_API_KEY`가
  없는 로컬 환경에서는 실제 스트리밍 대화를 끝까지 확인할 수 없어, 대화 생성·라우팅·인증과
  CHAT/OFFER/PROPOSAL 단위 테스트(모델 응답을 mock 처리)로 검증을 마쳤다.

## 2026-08-04 — Today 화면 execution_items 전환 + AI 오늘 제안 1차 구현

- Today 화면(TodayView)이 더 이상 mock 데이터가 아니라 `execution_items`를 실제로 읽고 쓰도록 전환했다. `schedule_blocks`에는 더 이상 쓰지 않는다(이중 저장 없음).
- `GET/POST /api/execution-items`, `POST /api/execution-items/{id}/{complete|reopen|move|reduce|hold}`, `DELETE /api/execution-items/{id}`, `GET /api/execution-items/pending`을 구현했다. 모든 조회·수정에 `user_id` 소유권 검증을 걸고, 공식 변경마다 `version`을 증가시키며 요청 버전이 다르면 409를 반환한다.
- Spring AI(`ChatClient`, OpenAI 단일 구현체)로 오늘 실행 후보를 생성하는 `POST /api/ai/proposals`, `GET /api/ai/proposals/{id}`, `POST /api/ai/proposals/{id}/apply`를 구현했다.
- AI TODAY Proposal의 생성·조회·전체 적용을 구현했다. 이번 1차 UI는 Proposal 묶음 전체를 하나의 트랜잭션으로 원자 적용하는 것만 지원한다. 항목별 부분 적용은 후속 범위로 남겨둔다.
- AI가 만드는 제안 항목은 모두 `placement_type='DATE_ONLY'`다. `TIME_FIXED` AI 추천은 후속 범위다.
- `AI_PROVIDER=none`이거나 `OPENAI_API_KEY`가 없어도 서버 부팅과 테스트는 정상 동작하며, 이 상태에서 제안 생성을 호출하면 `503 AI_NOT_CONFIGURED`를 반환한다.
- 레거시 `schedule_blocks`/`todos` → `execution_items` 데이터 이관은 이번 작업 이전에 이미 완료되어 있었다(`legacy_execution_item_map`). 위 2026-08-03 문서 동기화가 정의한 목표 구조 중 실행 조각 조회·조정 액션과 AI TODAY 제안 묶음 적용 구간을 실제로 구현한 것이다.

## 2026-08-03 — 최신 제품 구조로 문서 기준 통합

- 제품의 중심 문제를 “AI와 만든 계획이 채팅에 묻히고 실행과 다음 계획으로 이어지지 않는 문제”로 재정의했다.
- 최상위 화면을 `오늘 / 계획 / 실행 / 기록` 네 영역으로 고정했다.
- AI 상담 패널은 전역 보조 도구, Quick은 축약 진입점으로 정의했다.
- 계획과 실행 데이터를 다음 역할로 분리했다.
  - `PlanItem`: 앞으로 하려는 의도와 범위
  - `ExecutionItem`: 실제로 배치·조정하는 실행 조각
  - `ExecutionRecord`: 실제 수행 결과
  - `ExecutionItemEvent`: 이동·축소·보류·분할 같은 조정 사건
- 기존 `todos + schedule_blocks`의 장기 원본을 `execution_items`로 통합하기로 확정했다.
- 기존 `daily_plans`는 상위 계획이 아니라 하루 상태이므로 `daily_states`로 책임을 변경했다.
- `ContextItem`을 1급 데이터로 두고 목표·결정·제약·선호·고민·관찰·인사이트를 구분한다.
- ContextItem은 출처, 미확인/AI 추정/사용자 확정 상태, 유효 기간, 교체·철회 수명주기를 관리한다.
- 외부 ChatGPT 대화 가져오기도 AIProposal 검토 흐름을 거치도록 요구사항에 포함했다.
- AI 결과는 `ai_proposals / ai_proposal_items`에 초안으로 보존하고, 사용자가 항목별로 수정·적용·무시한다.
- AI 수정 적용 시 `baseVersion`과 현재 version을 비교하고 오래된 제안은 `409 Conflict`로 거부한다.
- 부분 수행은 `PARTIAL Record + 남은 ExecutionItem + SPLIT Event`를 한 트랜잭션으로 처리한다.
- 완료는 `COMPLETED Record + DONE 상태`를 한 트랜잭션으로 처리하며, 완료 기록 없는 DONE을 금지한다.
- 대화 중 실시간 화면 변경은 공식 데이터 변경이 아니라 ghost/diff 미리보기로 정의했다.
- 구현 순서는 `리스트 → 주간 시간표 → 캘린더`로 고정했다.
- 마이그레이션은 신규 테이블 생성 → 데이터 복사·보정 기록 → MyBatis 전환 → 레거시 쓰기 중단 순서로 진행하며 이중 쓰기를 금지한다.
- 실제 이메일·일기·비밀번호가 포함된 DB 덤프는 Git에 올리지 않는다.
- `docs/product` Markdown을 진실의 원천으로 유지하고, v2.1 DOCX는 2026-07-05 과거 스냅샷으로 명시했다.
- 위 항목은 목표 설계이며, 현재 코드가 모두 전환됐다는 의미는 아니다.

## 2026-07-09 — reduce 액션 선택적 시간 조정 및 이벤트 시간 전후 기록 추가

- ScheduleBlock reduce 요청 필드를 `reducedTitle` 중심으로 정리했다.
- 하위 호환을 위해 기존 `afterTitle` 요청은 임시 허용한다.
- reduce 액션에 `timeMode`를 추가했다.
  - `KEEP`: 기존 시간 유지
  - `SHRINK`: 시간 조정
  - `CLEAR`: 시간 해제
- 제목 축소, 시간 조정, `REDUCED` 이벤트 저장을 하나의 트랜잭션으로 처리한다.
- `plan_item_events`에 before/after blockType 및 start/end time 컬럼을 추가했다.
- rescale, scale-up, expand/extend 액션은 이번 범위에서 제외했다.

## 2026-07-09 — ScheduleBlock 완료취소 액션 추가

- `POST /api/schedule-blocks/{id}/uncomplete` 명시적 완료취소 액션을 추가했다.
- 완료취소는 DONE에서 PLANNED로 되돌리고, 실제 변경된 경우에만 `REOPENED` 이벤트를 저장한다.
- `RESUMED`는 HOLD 해제 의미로 남겨두고 완료취소와 분리했다.

## 2026-07-09 — ScheduleBlock 완료 액션 중복 호출 방어

- 완료/완료취소 액션 중복 호출 방어 원칙을 추가했다.
- 서버 idempotency를 우선 적용하고, 상태가 실제 변경된 경우에만 `plan_item_events`를 저장하도록 했다.
- Redis/AOP 기반 장기 전략은 `07-ideas.md`로 보류했다.

## 2026-07-08 — Today 실행 루프와 Todo/ScheduleBlock 역할 재정리

- Today 화면을 사용자 기본 입구로 확정했다.
- 1차-A의 핵심을 "오늘 할 일 생성 → 액션 → 이벤트 저장"으로 재정의했다.
- ScheduleBlock은 Today 화면의 실행 카드로 정의했다.
- Todo는 쓰레기통이 아니라 실행 후보 대기열로 정의했다.
- Todo UI는 1차-A에서 숨긴다.
- 월간/연간/장기목표는 실행 후보 보관함으로 쓰지 않는다.
- 사용자 입력 분류 병목을 피하기 위해 Today 입력은 전부 ScheduleBlock으로 저장한다.
- 이벤트는 사용자의 별도 기록이 아니라 액션 버튼의 부산물로 저장한다.
- AI 추천은 현재 제외하고 이벤트 데이터 수집을 우선한다.

## 2026-07-08 — ScheduleBlock 시간 정책과 pending 정책 정리

- `ScheduleBlockType`을 `TIME_FIXED / TASK` 기준으로 정리했다.
- `TIME_FIXED`는 `start_time/end_time`을 반드시 가진다.
- `TASK`는 `start_time/end_time`을 가지지 않는다.
- `start_time/end_time` 중 하나만 있는 값은 허용하지 않는다.
- `end_time`은 `start_time`보다 이후여야 한다.
- `block_date`는 실제 날짜가 아니라 이 블록이 속한 하루를 의미한다.
- `start_time/end_time`은 실제 시각이므로 `block_date`와 날짜가 달라도 허용한다.
- 따라서 서비스와 DB에 `DATE(start_time)=block_date` 검증을 두지 않는다.
- 새벽 4시 전 기록을 전날로 보는 operationalDate 정책은 이후 공통 유틸로 분리한다.
- pending은 기준 운영일보다 이전 `block_date`에 속했지만 아직 결론을 내리지 않은 `PLANNED` ScheduleBlock으로 정의한다.
- pending은 실패가 아니라 이전 운영일의 미정리 항목이다.
- `HOLD`는 사용자가 "지금은 하지 않겠다"고 결론 낸 상태이므로 pending이 아니다.
- `HOLD/DONE/CANCELLED/DELETED` 항목은 pending에서 제외하고, HOLD 항목은 pending 카드에 반복 노출하지 않는다.
- 1차-A의 hold 범위는 상태를 `HOLD`로 바꾸고 `HOLD` 이벤트를 저장하는 것까지다.
- HOLD 항목은 추후 별도 보류함 또는 다시 계획하기 흐름에서 다루며, 다시 계획하기는 `RESUMED` 이벤트로 확장할 수 있다.
- 보류함 화면, 보류 해제 API, 보류 재검토 알림, 보류 사유 입력은 1차-A 범위에서 제외한다.
- 1차-A pending 대상은 ScheduleBlock만이며, 미배치 Todo는 1차-B Todo 액션 확장에서 다룬다.
- pending 판단과 알림 정책은 분리한다.
- 기본 UX 방향은 다음 날 아침 pending 요약 정리다.
- 실제 알림 기능과 설정은 `07-ideas.md`에 보류하고, 푸시 알림 구현은 MVP 제외로 유지한다.

## 2026-07-05 — 기획 v2.1 확정 (기획 마감, 구현 단계 전환)

기존 워드 기획서를 v2 → v2.1로 개정하고 이 버전으로 기획을 마감했다.
스냅샷: `자기관리앱_기획서_v2.1.docx` (26장). 이후 변경은 이 changelog와 각 문서 패치로만 관리한다.

### v2에서 확정 (유지)

- 핵심 개정 축: 도메인 구조보다 기록 밀도, 이벤트 이력, 수집 조기화, MVP 재조정, 성공 기준
- RECOVERY를 mode에서 제거 → `intensity(LIGHT/NORMAL/FOCUSED) + conditionTags`로 흡수
- FLOW → 순서 있는 체크리스트(orderIndex), PRIORITY → 행동 속성(MUST/SHOULD/OPTIONAL)
- `DailyPlan.viewMode`는 TIME_TABLE / CHECKLIST 2종. 가설로 취급, 30일 자가사용으로 검증
- 성공 기준: 개발자 본인 30일 연속 사용 (daily-driver 검증)
- 초저마찰 기록 스펙(마찰 예산), 오늘 화면 4카드, 모바일 반응형 + PWA는 1차 요건
- 원칙: 수집은 일찍 가볍게, 분석은 늦게 깊게
- 월간/연간/LifeGoal: MVP에서 테이블도 만들지 않음. 소셜: 별도 프로젝트 취급

### v2.1에서 확정 (신규)

- **status/event 역할 분리**: status = 현재 상태, event = 상태 전이 또는 사건
  - `ScheduleStatus: PLANNED / DONE / HOLD / CANCELLED` (MOVED, REDUCED 제거)
  - `PlanItemEventType: CREATED / DONE / MOVED / REDUCED / HOLD / RESUMED / DELETED`
  - CANCELLED = 안 하기로 결정한 상태, DELETED = soft delete + 이벤트
- **plan_item_events** (구 todo_events 개명)
  - `todo_id(N)`, `schedule_block_id(N)`, `CHECK (둘 중 하나는 NOT NULL)`
  - 해석 정책: todo만 = 미배치 Todo 이벤트 / block만 = 계획 항목 이벤트 / 둘 다 = block 기준 우선 해석
  - 무결성: 블록 이벤트의 todo_id는 클라이언트에게 받지 않고 서버가 block.todo_id에서 복사
- **이동(내일로) 정책**: 블록 복제 없이 기존 레코드 이동. 단일 트랜잭션으로
  1. 블록 조회(소유 검증) → 2. 대상일 DailyPlan 조회 → 3. 없으면 기본값으로 생성(get-or-create)
     → 4. date + daily_plan_id 갱신 → 5. MOVED 이벤트 저장 → 6. 커밋(실패 시 전체 롤백)
- **도메인 액션 API**: move/reduce/hold/complete는 PATCH가 아니라 POST 하위 액션
  - `POST /api/schedule-blocks/{id}/move | /reduce | /hold | /complete`
- **quick_logs 값 정의**: SLEEP 1=6h미만/2=6~7h/3=7h이상, EMOTION 1=나쁨/2=보통/3=좋음
  (value_numeric으로 분석, value_text로 표시)
- **AI 주간 요약을 1.5차로 이동**: 집계 기반 수동 주간 회고 화면(1차-B)이 먼저.
  근거: "수집은 일찍, 분석은 늦게" 원칙과의 모순 해소 + 초기 데이터 빈약 시 헛요약 방지
- **AI 개인정보 처리 원칙**: 명시 요청 시만 실행 / 최소 데이터(집계·태그) 전달 /
  일기 원문 기본 전송 금지 / 결과 자동 저장 금지
- **AI 피드백 루프 명문화**: 루프 = 결과의 절반(1차, plan_item_events) + 제안의 절반(2차, ai_suggestions)
  - `ai_suggestions`: PROPOSED / APPLIED / MODIFIED_APPLIED / DISMISSED / EXPIRED,
    적용 항목 역참조(created_item_type/id). 2차 구현, 설계는 v2.1에서 확정
  - 다음 생성 컨텍스트 스펙: 유형별 수락률, 수정 패턴, AI 생성 항목의 실행/이월 통계(집계만 전달)
  - "무시해도 다시 강요하지 않는다" 원칙은 무시 기록(DISMISSED)이 있어야 구현 가능
- **MVP 릴리스 단위 분할**: 1차-A(이벤트+액션+아직못한것카드, ScheduleBlock 우선) →
  1차-B(퀵로그+하루마무리+주간회고집계+Todo 액션 확장) → 1차-C(DailyPlan v2+오늘상태카드) →
  1차-D(4카드 통합+반응형+PWA) → 1.5차(AI 주간 요약) → 30일 자가사용 검증
- **스코프 명시**: 1차-A의 "아직 못 한 것" 카드는 ScheduleBlock만 표시(의도된 범위, 버그 아님).
  스키마는 처음부터 Todo를 지원하므로 확장 시 마이그레이션 부채 없음
- **구현 기준**: JPA가 아니라 현재의 MyBatis Mapper/XML 패턴 기준. 모바일 검증은
  와이어프레임 문서가 아니라 실제 목업/구현으로 수행

### 반영 필요한 문서 (동기화 대상)

- `03-planning-system.md`: 하향식 구조 → viewMode/intensity/conditionTags + 상향식 발견으로 재작성
- `04-requirements.md`: DailyPlan v2, 도메인 액션, 퀵로그, 이벤트 REQ 추가
- `05-database.md`: daily_plans / user_plan_preferences / plan_item_events / quick_logs 반영,
  schedule_blocks 갱신(status 4종, priority, order_index 등)
- `06-mvp-roadmap.md`: 릴리스 단위(1차-A~D, 1.5차)로 재작성
- `02-feature-structure.md`: 용어 정리(문제함 → 정리함 우선)

## 2026-07-01

- 기존 워드 기획서를 Git 이력관리용 Markdown 구조로 전환.
- 문서 구조를 다음 기준으로 재정리.
  - `01-product-plan.md`
  - `02-feature-structure.md`
  - `03-planning-system.md`
  - `04-requirements.md`
  - `05-database.md`
  - `06-mvp-roadmap.md`
  - `99-changelog.md`
- planning은 앱 전체 구조를 좌우하는 상위 개념이므로 별도 파일로 분리.
- 계획 구조를 다음 계층으로 정의.
  - 연간 목표
  - 월간 계획
  - 주간 계획
  - 하루 계획
  - Todo
  - 시간 블록
- AI 생성 데이터는 자동 저장하지 않고 후보로 표시하는 원칙 유지.
- AI가 생성한 데이터를 사용자가 수정 후 적용하는 경우 `origin_type`은 AI 계열로 유지하고 `modified_after_creation = true`로 저장하는 원칙 추가.
- Todo 인덱스 구조를 다음 형태로 정리.
  - `idx_todos_user_deleted_date (user_id, is_deleted, todo_date)`
  - `idx_todos_user_deleted_status (user_id, is_deleted, status)`
  - `idx_todos_routine (routine_id)`
