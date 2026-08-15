# 99. Change Log

## 2026-08-15 — 계획이 이미 틀어진 하루를 다시 잡는 UX

오늘 화면은 계획을 잘 세우는 화면이 아니라 실행 작업면인데, 정작 계획이 무너진 순간
("늦게 일어나서 오전을 다 놓쳤고 지금은 오후")에 할 수 있는 것이 없었다. 예정 시간이 지난
항목이 세 개 있어도 "지금" 영역은 `지금 잡힌 것이 없어요`라는 사실과 다른 빈 상태를 보여줬고,
그 항목들은 앞으로 할 일과 같은 목록에 섞여 있었다. 개별 항목의 액션만 있고 하루 단위의
붕괴를 다루는 흐름이 없었다.

**"늦잠 기능"을 만들지 않았다.** 늦잠·지연·급한 일정·컨디션 저하는 모두 "기존 하루 계획의
전제가 바뀌었다"는 같은 상황이고, 화면은 원인이 아니라 그 결과(예정 시간이 지난 항목)만 본다.

- **지나간 항목 판별을 좁게 정의했다(REQ-TODAY-001/002).** 오늘 날짜 + PLANNED + TIME_FIXED +
  종료 시각이 지금을 지남. 시각을 정하지 않은 항목은 오후가 됐다는 이유만으로 밀린 것이 아니고,
  DONE/PARTIAL/HOLD/CANCELLED는 이미 결론이 난 상태라 다시 들이밀지 않는다. 새 status나 컬럼을
  만들지 않았다 — 저장되는 상태가 아니라 현재 시각으로 계산하는 화면상의 판단이다
  (`diary-ui/src/lib/today.js`).
- **"지금" 영역을 상태 인식형으로 바꿨다(REQ-TODAY-003).** 진행 중 일정 / 지나간 항목 있음 /
  시각 없는 항목 중 하나 / 다음 일정까지 남은 시간 / 오늘 아무것도 없음 다섯 갈래로 나뉜다.
  지나간 항목이 있으면 빈 상태 문구가 나오지 않는다. 밀렸다는 사실은 경고(빨강)가 아니라
  안내(노랑)로 표현하고 완료율·미달 같은 평가 표현을 쓰지 않는다.
- **"남은 오늘"에서 지나간 항목을 분리했다(REQ-TODAY-004).** 접을 수 있는 compact 묶음 +
  "한번에 정리" 버튼, 그 아래에 "앞으로 할 일". 1536x760에서 기본 오늘 화면은 세로 스크롤이
  사실상 없다(측정값 766 vs 760).
- **`미루기` -> `이동`으로 정리했다.** 이동은 "언제 할지 변경"이고 `오늘 뒤로 / 내일로 /
  날짜 선택` 세 갈래를 준다. 보류는 "당분간 실행 대상에서 제외"라는 다른 의미이므로 합치지
  않고 그대로 뒀다.
- **`ExecutionItemService.move()`가 같은 날 시각 이동을 처리한다(REQ-EXECUTION-012).**
  `ExecutionItemMoveRequest`에 선택 필드 `startTime/endTime`을 추가했다. 시각을 주지 않으면
  기존 동작 그대로(날짜 차이만큼 평행 이동)이고, 주면 그 시각으로 다시 배치한다. TIME_FIXED
  항목에만 허용하며(시각 없는 항목에 시각을 붙이는 것은 이동이 아니라 배치 형식 변경),
  날짜도 시각도 안 바뀌면 기존처럼 `MOVE_TARGET_DATE_INVALID`로 막는다. **새 액션도 새 이벤트
  타입도 만들지 않았다 — 둘 다 MOVED 이벤트 하나로 남는다.**
- **"남은 오늘 다시 잡기" 검토 영역을 오늘 화면 안에 펼친다(REQ-TODAY-005).** 새 페이지나
  전체 화면 모달을 만들지 않았다 — 계획이 틀어진 순간에 화면이 통째로 바뀌면 방금 보던 것을
  잃는다. 초안은 프론트 state에만 있고, 열어도 취소해도 저장된 계획은 그대로다.
- **추천은 규칙 기반이다(AI 호출 없음).** 지금부터 다음 고정 일정까지 실제로 남은 시간에
  들어가는 것만 오늘 뒤로, 절반이라도 들어가면 줄여서 오늘 안에, 그래도 안 되면 꼭 해야 하는
  것은 내일로 / 여유 있으면 하는 것은 보류. **전부 내일로 밀거나 전부 축소하는 일괄 처리는
  하지 않는다**(`diary-ui/src/lib/reschedule.js`).
- **적용은 기존 도메인 액션만 쓴다(REQ-TODAY-006).** 항목마다 move/reduce/hold를 순서대로
  부른다. 새 일괄 적용 API를 만들지 않았다 — 항목 사이에 지켜야 할 불변식이 없고(A를 옮기는
  것이 B의 유효성을 바꾸지 않는다) 각 액션이 이미 원자적이며 낙관적 락과 이벤트 기록을 갖고
  있다. 하나가 실패하면 그 항목만 실패로 알리고 나머지는 반영된 상태로 둔다. 축소+이동이 함께
  필요하면 reduce -> move 순서로 부르고 reduce가 돌려준 version을 이어 쓴다.
- **현재 상황 태그는 새 DB 구조를 만들지 않았다(REQ-TODAY-008).** `DailyPlan.conditionTags`는
  아직 구현돼 있지 않다(1차-C 예정) — 중복 구조를 미리 만드는 대신 선택한 태그를 각 도메인
  액션의 `reason`에 실어 `execution_item_events`에 남긴다. "기상 늦음이던 날 무엇을 옮겼나"를
  되짚는 근거는 그 이벤트다. **DB 스키마 변경 없음.**
- **AI가 계획을 바로 바꾸지 않는 원칙은 그대로다.** CHAT -> OFFER -> (사용자 동의) ->
  PROPOSAL -> APPROVED 흐름과 "AUTO는 Proposal을 만들 수 없다"는 서버 방어선을 전혀 바꾸지
  않았다. 대신 판단 근거를 보강했다:
  - `AiWorkspaceContextBuilder`가 현재 시각과 `예정 시간 지남` 표시, 지난 항목 개수를 함께
    싣는다(`build`의 3번째 인자가 LocalDate -> LocalDateTime).
  - SYSTEM_PROMPT 원칙 18: 계획이 틀어진 상황은 실패가 아니라 조정할 거리다. 화면 상태로 이미
    아는 것(밀린 개수, 오늘 남은 고정 일정)은 다시 묻지 않고, 모르는 핵심 제약만 묻는다.
    범위는 오늘까지이며 주간/월간/새 목표로 넓히지 않는다.
  - AI 조정 후보 MOVE에 `startTime/endTime`을 추가해 "오전에 못 한 것을 오늘 16시로"를 표현할
    수 있게 했다. 적용은 위의 확장된 move 액션을 그대로 탄다.
- **OFFER의 reply를 서버 고정 문장에서 모델의 문장으로 바꿨다(동작 변경).** 기존에는
  decision=OFFER_PROPOSAL이면 항상 `말해준 내용을 바탕으로 계획 초안을 만들어볼까요?`로
  덮어썼다. 그러면 상황을 짚는 제안이 매번 같은 문장으로 뭉개지고, 스트리밍 중에 이미 보여준
  문장이 완료 시점에 다른 문장으로 바뀌어 보이는 문제도 있었다. 모델 문장이 비어 있을 때만
  고정 문장으로 대체한다. **버튼(OfferAction)은 여전히 서버가 만든다** — 모델이 화면 상태나
  버튼을 정하지 않는다는 원칙은 그대로다.
- 테스트: 백엔드 261건 전체 통과(신규 12건 — 같은 날 이동, 시각 범위 검증, 시각 없는 항목의
  시각 이동 거부, 기존 날짜 이동 회귀, 지난 항목 표시 컨텍스트, 프롬프트 계약, AI MOVE 시각
  전달). 프론트 69건 전체 통과(신규 33건 — 지난 항목 판별 경계, 재조정 초안 계산, 적용 시
  도메인 액션 호출/version 이어쓰기/부분 실패, 오늘 화면 상태 분기와 검토 영역). `npm run
  build`, `npm run lint` 통과.
- 실제 로컬 서버(별도 인스턴스 8082 + UI 5175)와 브라우저 1536x760에서 확인했다: 15:40 기준
  오전 3건 밀림 -> "예정 시간이 지난 일정이 3개" -> 다시 잡기 -> 기상 늦음 태그 -> 적용까지,
  DB에 `MOVED(내일로)` / `REDUCED + MOVED(같은 날 16:15)` / `HOLD`가 reason
  `다시 잡기: 기상 늦음`과 함께 저장되는 것을 확인했다. 실제 OpenAI로 "늦게 일어나서 오전
  계획을 다 못했어"를 보냈을 때 AUTO 턴이 `decision=OFFER_PROPOSAL`로 끝나고 제안이 0건인 것,
  그리고 이미 아는 17:00 고정 일정을 기준으로 삼아 되묻지 않는 것도 확인했다.

## 2026-08-09 (2차) — 학습 UX를 기능 데모에서 실제 학습 경험으로 재구성

바로 위 항목(멀티에이전트 vertical flow)에서 남긴 "알려진 한계" 두 가지를 이번 작업에서
정리했다. 새 Agent를 추가하거나 Material/Learning/Planning 백엔드 로직을 다시 만들지 않고,
frontend(diary-ui)만 재구성했다. 백엔드는 시간표 조회에 필요한 범위 쿼리 엔드포인트 하나만
추가했다.

- **학습을 최상위 진입점으로 분리**: 계획 탭 안의 "자료·과목" 서브탭을 없애고 사이드바에
  "학습"(오늘/학습/계획/실행/기록)을 추가했다.
- **학습 지도**: 평평한 topic 카드 목록을 계층 트리(LearningMap)로 바꿨다. 선택한 topic
  하나만 상세(TopicDetail)로 보여주고, 모든 행에 배지·버튼 4개를 반복하지 않는다.
- **개인과외 전용 화면(TutorView)**: topic 아래 인라인 채팅이던 것을 화면 전체를 쓰는
  전용 모드로 분리했다. 진입 시 course/topic context를 이미 갖고 있어 다시 묻지 않는다.
  "학습 완료"는 사용자의 명시적 액션일 때만 topic을 LEARNED로 바꾼다.
- **Material 분석 편집(MaterialReview)**: 기존 `PUT /api/material-analyses/{id}` 편집
  API가 frontend에 연결되지 않아 적용/폐기만 가능했던 것을 고쳤다. topic 제목 수정·제거·
  추가, 교재 필드 수정이 가능하고, 적용 시 항상 최신 편집 내용을 먼저 저장한다.
- **주간 시간표 실데이터 연결**: `GET /api/execution-items/range?startDate=&endDate=`를
  추가해(getByDate와 같은 원본, 같은 규칙) TimetableView가 자체 mock(EVENTS/TODAY_EVENTS)
  대신 실제 ExecutionItem을 보여주게 했다.
- **버그 수정**: 오늘 탭이 최초 1회만 조회하고 탭 재방문 시 다시 조회하지 않아, 학습 탭에서
  계획을 적용해도 오늘 화면에 반영되지 않던 문제를 브라우저 검증 중 발견해 고쳤다.
- 실 계정으로 강의계획서 업로드 → 분석 → 편집 → 적용 → 학습 지도 → 개인과외(실제 OpenAI
  응답 확인) → 학습 완료 → 다음 추천 → 계획 적용 → 오늘/시간표 반영까지 end-to-end로 검증했다.

## 2026-08-09 — 학습 자료 → Material/Learning/Planning 멀티에이전트 vertical flow

강의계획서/교재 목차/교수자료를 올리면 AI가 학습 구조로 분석하고(Material Agent), 그 구조 위에서
개인과외와 다음 학습 판단을 하고(Learning Agent), 그 판단을 기존 실행/스케줄링 파이프라인에
배치하는(Planning Agent) vertical flow를 처음부터 끝까지 연결했다. 기존 ExecutionItem/
scheduling/AiProposal 구조를 재사용했고, 동일한 의미의 실행 시스템을 병렬로 새로 만들지 않았다.

- **새 패키지**: `course`(과목), `material`(자료 업로드+텍스트 추출+Material Agent),
  `learning`(topic 트리/진행 상태+Learning Agent 개인과외/추천), `planning`(Planning Agent),
  `orchestration`(Learning→Planning 연쇄 호출을 결정론적으로 통제하는 얇은 오케스트레이터,
  워크플로당 최대 Agent 호출 횟수 제한).
- **DB**: `docs/sql/2026-08-09-learning-agents.sql` — courses/course_materials/
  course_material_analyses/course_topics/topic_progress/topic_learning_events/
  study_recommendations 신규, `execution_items.topic_id`(학습↔실행 연결),
  `study_recommendations.proposal_id`(추천↔계획 역참조), `ai_conversations.scope`에
  MATERIAL/LEARNING/PLANNING 추가, `ai_usage_logs`에 workflow_id/agent_run_id/latency_ms 추가.
- **AI 결과는 항상 승인 전 미반영이다.** Material 분석은 DRAFT로만 저장되고 apply() 전에는
  course_topics/courses 교재 필드에 전혀 영향을 주지 않는다. Planning 계획 초안은 기존
  AiProposal + SchedulePreviewService(Timefold)를 그대로 재사용하며 Apply 전에는 ExecutionItem이
  생기지 않는다.
- **source/AI_DERIVED provenance**를 모든 topic 노드에서 구분한다. 원문에 실제 있는 항목만
  SOURCE로 표시하고, AI가 학습 편의를 위해 세분화한 항목은 AI_DERIVED로 남긴다. 목차 근거가
  없으면 topics를 빈 배열로 두고 이유를 설명한다 — 목차를 지어내지 않는다. 학기 중 새 교수자료를
  올리는 경우, 이미 확정된 topic 트리를 프롬프트에 함께 줘서 중복 제안을 피하고 새 내용만
  보고하게 했다(제목에 상위 topic 맥락을 함께 적어 사용자가 review에서 관계를 알 수 있게 함 —
  단, 확정 구조에 자동으로 자식으로 붙이는 트리 병합까지는 이번 범위에 없다).
- **완료 = 완전 이해로 해석하지 않는다.** topic_progress는 NOT_STARTED/IN_PROGRESS/LEARNED
  세 단계만 두고, 사용자가 topic 화면에서 직접 누른 액션만 상태를 바꾼다. ExecutionItem 완료는
  `ExecutionItemCompletedEvent`를 발행해 `learning.ExecutionCompletionListener`가 받는 방식으로
  연결했다(execution 패키지가 learning을 몰라도 되도록 이벤트로 분리) — LEARNED로 자동 승격하지
  않고 IN_PROGRESS로 옮기거나(첫 학습) 복습 이력만 늘린다(이미 LEARNED).
- **Agent별 usage/토큰 예산 분리**: `ai_usage_logs.feature`에 MATERIAL_ANALYSIS/LEARNING_CHAT/
  LEARNING_RECOMMENDATION/PLANNING_CHAT 값을 남기고, `ai.material.*`/`ai.learning.*`/
  `ai.planning.*` 프로퍼티로 Agent별 max-input-tokens/max-completion-tokens을 독립 조절한다.
  `AiConsultationClient`에 `maxCompletionTokens` 오버로드를 추가했다(Today 상담은 기존 2-인자
  오버로드를 그대로 쓴다 — 동작 변경 없음).
- **버그 수정**: Spring Boot 4가 기본으로 Jackson 3(`tools.jackson`)를 쓰면서 이 프로젝트
  코드 전반이 의존하는 클래식 `com.fasterxml.jackson.databind.ObjectMapper` 빈이 더 이상
  자동 구성되지 않는 것을 발견했다(`spring.http.converters.preferred-json-mapper=jackson2`를
  명시해야 `Jackson2HttpMessageConvertersConfiguration`이 매치됨). `common/config/JacksonConfig`에
  `@ConditionalOnMissingBean`으로 명시적 빈을 추가해 해결했다 — 새 패키지가 없었어도 언젠가
  터졌을 잠재 버그였다.
- **문서화되지 않은 실제 제약**: PDF/PPTX 텍스트 기반 자료만 지원한다(PDFBox/POI 신규 의존성
  추가). 스캔 이미지 등 텍스트 레이어가 없는 PDF는 OCR 없이 FAILED_NO_TEXT로 명확히 실패
  처리한다. 파일은 로컬 디스크(`storage.materials.upload-dir`)에 저장하고 DB에는 메타데이터만
  남긴다.
- **알려진 한계**: `TimetableView.jsx`(주간 시간표)는 이미 이전부터 자체 mock 데이터를 쓰고
  있었고, 이번 작업에서도 실제 ExecutionItem 데이터로 바꾸지 않았다(하드코딩된 학기/날짜 라벨을
  포함한 더 큰 리팩터링이 필요해 범위 밖으로 남겼다) — Today 화면은 실제 데이터로 완전히
  연결되어 검증했다. Material Agent가 새 자료의 topic을 기존 확정 topic의 자식으로 물리적으로
  붙이는 트리 병합은 구현하지 않았다(프롬프트 컨텍스트로 중복 방지만 함).
- **테스트**: 백엔드 신규 27건 모두 mock 기반(TextExtractionServiceTest만 실제 PDFBox/POI로
  진짜 PDF/PPTX를 만들어 추출 검증), 기존 포함 전체 `./gradlew test` 통과. 프론트 신규 10건
  포함 전체 `npm run test` 32건 통과. 로컬 dev 서버(실제 OpenAI 키)로 강의계획서 PDF 업로드→
  분석→적용→topic 트리→개인과외 대화→추천→계획 초안→Apply→오늘 화면 반영→완료→다음 추천
  반영→교수 PPT 추가까지 브라우저에서 전체 시나리오를 실행해 확인했다.

## 2026-08-08 — AI 상담의 성급한 계획 제안(OFFER) 판단 보완

실사용 입력("프로젝트 더 수정할 건데 계획 짜줘, 오늘은 조금 늦게 자도 괜찮을 것 같아")에서
AUTO 턴이 종료 시각·다음 날 고정 일정 같은 핵심 시간 정보 없이 바로 decision=OFFER_PROPOSAL을
반환해, 근거 없는 취침/기상 가정 위에서 계획 생성 버튼이 뜨는 것을 확인했다(CASE-001).
명시적 OFFER → 버튼 → CREATE_PROPOSAL 구조 자체나 AUTO가 Proposal을 직접 만들지 않는 안전
장치는 문제가 없었다 — OFFER 이전 판단 기준이 문제였다.

- **ASK 우선 규칙을 추가했다(REQ-AI-020).** 특정 문장 하드코딩이 아니라 "이 정보를 모르면
  계획의 범위·분량·순서·날짜·고정 시각·현실성이 크게 달라지는가"라는 일반 기준으로,
  핵심 정보가 부족하거나 현재 발언과 저장된 정보가 충돌하면 OFFER_PROPOSAL보다
  ASK_CLARIFICATION을 우선하도록 `OpenAiConsultationClient.SYSTEM_PROMPT`(원칙 14)와
  `AiConversationService.AUTO_MODE_BLOCK`을 보완했다. 영향이 작은 정보는 보수적으로 추정할
  수 있고(그 값을 확정 사실처럼 취급하지 않음), 이미 최근 대화나 장기 컨텍스트에 있는
  정보는 다시 묻지 않는다는 기준도 명시했다 — 정보 하나라도 없으면 무조건 ASK하는 설문이
  되지 않도록 반대 방향(불필요한 질문 남발)도 함께 경계했다.
- decision enum, structured JSON 계약, `resolveTurn`의 decision→responseType 변환,
  AUTO에서 PROPOSAL을 직접 만들지 않는 서버 방어선은 전혀 바꾸지 않았다 — 이번 수정은
  프롬프트의 판단 기준 텍스트만 보완했다.
- CASE-001을 [09-ai-consultation-regression-cases.md](09-ai-consultation-regression-cases.md)에
  기록했다. 정확한 문장을 고정하는 golden text 문서가 아니라 판단/행동 의미를 검증하는
  회귀 기준이며, 반대 방향 회귀(단순 인사, 정보가 이미 충분한 요청, 컨텍스트에 이미 있는
  정보 재질문 금지 등)도 함께 남겼다.
- 테스트: `OpenAiConsultationClientTest`(신규, 프롬프트에 판단 기준 문구가 실제로 포함됐는지
  계약 수준 확인 3건), `AiConversationServiceTest`에 CASE-001 서버 계약 회귀 테스트와
  AUTO_MODE_BLOCK 새 문구 포함 검증을 추가했다. 실제 모델이 이 입력에 항상
  ASK_CLARIFICATION을 고를 것이라는 것 자체는 Mockito로 증명할 수 없어, 모델이
  ASK_CLARIFICATION을 반환했을 때 서버가 정확히 CHAT 계약으로 처리하고 OFFER/PROPOSAL을
  만들지 않는다는 계약만 자동화했다. `./gradlew test`로 backend 전체 통과를 확인했다(207건).
- 실제 OpenAI 환경에서의 수동 확인은 이번에는 진행하지 않았다 — 이 세션이 시작하지 않은
  프로세스가 이미 로컬 8080 포트를 점유하고 있어(코드 버전을 확신할 수 없는 상태), 그
  프로세스를 재시작하거나 새 서버를 올려 실제 비용이 드는 OpenAI 호출을 시도하지 않았다.

## 2026-08-07 — 계획 초안(CREATE_PROPOSAL) 빈 CHAT 오처리 수정

장애 로그에서 CREATE_PROPOSAL 요청이 OpenAI 호출까지는 성공(input=1772, output=2400,
output이 설정 상한과 정확히 일치)했지만 최종 reply/구조화 JSON이 완전히 빈 채로
`responseType=CHAT`으로 잘못 대체되어 "성공"으로 저장되는 것을 확인했다. gpt-5-mini 같은
reasoning 모델은 `max_completion_tokens`를 내부 추론(reasoning) 토큰까지 포함해 쓰므로,
추론이 상한을 전부 써버리면 사용자에게 보일 텍스트가 하나도 남지 않을 수 있다.

- **CREATE_PROPOSAL 응답 계약을 엄격화했다(REQ-AI-018).** reply 비어있지 않음, 구분자·구조화
  JSON 존재, JSON 파싱 성공, responseType=PROPOSAL, proposalItems 1개 이상을 전부 만족해야
  성공으로 본다. 하나라도 어기면 `AiConversationService.enforceTurnContract()`가
  `ServiceUnavailableException(AI_GENERATION_FAILED)`를 던지고, 스트림 완료 콜백의 기존
  catch 블록이 그대로 받아 `completeTurnFailure` + `sink.onError(503)`로 처리한다 — 새
  실패 상태·테이블을 만들지 않고 기존 FAILED lifecycle을 재사용했다. 빈 ASSISTANT
  메시지·AIProposal·ExecutionItem을 만들지 않으며, idempotency 재생 경로도 그대로라 자동
  재호출은 생기지 않는다.
- **requestedAction과 무관한 일반 판정도 추가했다(REQ-AI-019).** reply·구조화 데이터가
  모두 비어 있거나, `finishReason=LENGTH`(토큰 상한 종료)로 응답 일부가 빈 채로 끝났으면
  action에 상관없이 실패로 처리한다 — "빈 CHAT을 성공으로 저장"하는 이번 장애의 근본
  패턴을 일반적으로 막는다. finishReason은 Spring AI의 공개 인터페이스
  (`ChatResponse.getResult().getMetadata().getFinishReason()`)로만 얻고 내부 클래스를
  캐스팅하지 않았다. 스트리밍 중 finishReason은 마지막 청크에만 채워지므로 `AtomicReference`로
  마지막 값을 누적한다(기존 `lastUsage` 캡처와 같은 패턴). finishReason을 못 얻는 극단적인
  경우에만 `outputTokens >= max_completion_tokens`를 보조 판정으로 쓴다(정상 종료인데
  우연히 상한과 같은 토큰 수를 쓴 경우까지 실패로 몰지 않도록, finishReason이 있으면
  보조 판정은 쓰지 않는다).
- **OpenAI 요청 옵션을 공식(비-deprecated) 프로퍼티로 정리하고 강화했다.**
  `spring.ai.openai.chat.options.max-completion-tokens`(deprecated alias, 계속 동작은 함)
  대신 `spring.ai.openai.chat.max-completion-tokens`를 쓰고, `reasoning-effort=low`,
  `verbosity=low`를 추가해 상한을 6000으로 올렸다(Spring AI 2.0
  `spring-configuration-metadata.json`에서 세 키 모두 공식 키의 deprecation replacement로
  확인). `minimal`은 계획 항목 분해에 약할 수 있어 `low`를 썼다. `ChatModel` 기본 옵션에
  자동 바인딩되므로 `OpenAiConsultationClient`의 호출 코드는 바꾸지 않았다(별도 옵션 오버라이드
  없이 기존과 동일하게 `.stream()`만 호출).
- CREATE_PROPOSAL 사용자 프롬프트와 시스템 프롬프트에 "reply는 짧게, proposalItems 내용을
  reply에서 다시 설명하지 말라"는 간결성 지시를 추가해 출력량 자체도 줄였다 — 기존
  JSON 스키마·CHAT/OFFER/PROPOSAL 계약·구분자 파싱 방식은 바꾸지 않았다.
- 일반 SEND_MESSAGE(AUTO)의 CHAT/OFFER/PROPOSAL 흐름과 malformed-JSON일 때의 CHAT 폴백은
  그대로 유지된다 — 이번 강화는 CREATE_PROPOSAL 전용 계약과, 응답이 진짜로 텅 비었거나
  토큰 상한에 걸린 경우에만 적용된다.
- 테스트: `AiConversationServiceTest`에 CREATE_PROPOSAL의 빈 응답/구분자 없음/JSON 없음/
  JSON 파싱 실패/CHAT 반환/OFFER 반환/빈 proposalItems/정상 성공 케이스와, action과 무관한
  토큰 상한 실패·정상 종료(STOP) 시 우연한 토큰 수 일치 오탐 방지 테스트를 추가했다(10개).
  Java 21 환경에서 `./gradlew test`, `./gradlew build` 모두 통과를 확인했다.

## 2026-08-06 — Timefold Solver 기반 7일 범위 일정 후보 배치

사용자가 여러 날에 걸친 계획("이번 주 안에 강의 4개 들어야 해, 화·목 저녁은 알바")을 말하면,
그동안은 AI가 만든 PROPOSAL 항목이 항상 오늘 하루(DATE_ONLY/TIME_FIXED)에만 묶였다. 이번
작업으로 실제 날짜·시각 배치를 서버의 Timefold Solver에 맡기는 한 바퀴를 완성했다.

- **Java 17 → 21, Timefold Solver 2.4.0 도입.** `timefold-solver-core`만 쓴다(Spring Boot 4용
  공식 스타터가 아직 없다). `ConstraintVerifier`가 core 안으로 들어와 있어(2.x부터) 별도
  `timefold-solver-test` 아티팩트는 필요 없다(1.x까지만 배포됨).
- **계산 모델을 DB 엔티티와 분리했다.** `scheduling.domain`(SchedulingTask/BusyWindow/
  AvailabilityWindow/TimeSlotOption/SchedulingPlan/SchedulingContext), `scheduling.solver`
  (SchedulingConstraintProvider, SchedulingSolverService), `scheduling.service`
  (AvailabilityEstimateService, SchedulePreviewService). `ExecutionItem`/`AiProposalItem`에는
  Timefold 어노테이션을 붙이지 않았다.
- **제약**: HardMediumSoftScore를 썼다 — HARD 5개(신규 후보끼리 안 겹침, 기존 고정 일정과
  안 겹침, 계획 범위 안, 과거 아님, 마감 안 넘김), MEDIUM 1개(우선순위 순 배치 선호), SOFT 3개
  (신뢰도 높은 시간 우선, 하루 과부하 방지, 여유시간 선호). 우선순위 선호를 SOFT가 아니라
  MEDIUM에 둔 이유: 같은 레벨에 두면 "미배치 페널티"와 "낮은 신뢰도 배치 페널티"가 우연히
  같아질 때 시간이 남는데도 솔버가 임의로 미배치를 선택하는 것을 실제로 확인했다(구성
  휴리스틱 로그로 재현). Solver는 상시 실행하지 않는다 — 요청마다(기본 5초 상한) 새로
  만들고 버린다.
- **AI 응답 계약 확장(하위 호환)**: PROPOSAL 항목 `placementType`에 `UNSCHEDULED`를
  추가하고, `earliestStartDate/deadlineDate`(선택적 힌트)와 `fixedStartAt/fixedEndAt`(사용자가
  명확한 날짜+시각을 말했을 때만, Timefold가 움직이지 않는 고정값)을 추가했다. 구조화 JSON에
  대화 차원 `unavailableWindows`(사용자가 명시한 사용 불가 시간, day-of-week 또는 특정 날짜
  반복)도 추가했다. 기존 TODAY 흐름(DATE_ONLY/TIME_FIXED, 오늘 하루)은 그대로 동작한다 —
  CHAT/OFFER/PROPOSAL 최상위 계약, 메시지당 OpenAI 호출 1회, 구분자 기반 스트리밍 파싱은
  전혀 바꾸지 않았다.
- **`POST/GET /api/ai/proposals/{proposalId}/schedule-preview` 추가.** 둘 다 OpenAI를
  호출하지 않는다. POST는 가용시간을 다시 추정하고 Timefold를 재계산해 결과를
  `ai_proposal_schedule_previews`(신규 테이블, `docs/sql/2026-08-06-scheduling-preview.sql`)에
  upsert한다. GET은 새로고침 후 마지막 계산 결과를 복원한다(없으면 204).
- **가용시간 추정(AvailabilityEstimateService)**: 기존 TIME_FIXED 실행 조각(제외 불가) +
  대화에서 파악한 사용 불가 시간(HIGH, AI_INFERRED) + 사용자가 미리보기에서 고친 예외
  (USER_OVERRIDE, HIGH) + 근거 없을 때 Asia/Seoul 기준 보수적 기본 활동 시간대
  (DEFAULT_INFERENCE, LOW)를 조합한다. 현재 시각 이전과 계획 범위(최대 7일, 설정 가능) 밖은
  항상 제외한다. ContextItem/실행 패턴 집계는 아직 없어 그 출처(USER_CONFIRMED_CONTEXT/
  EXECUTION_PATTERN)는 계약만 남기고 이번 구현에서 채우지 않는다.
- **`AiProposalService.apply()`를 다중 날짜 승인이 가능하도록 확장했다.** 기존에는 제안
  묶음 전체가 생성 당시의 단일 `targetDate`를 공유해, 항목마다 다른 날로 확정하는 이번
  기능을 적용할 방법이 없었다. `EditedProposalItem`에 `scheduledDate`를 추가하고, 지정하지
  않으면 TIME_FIXED 시각의 날짜 또는 기존 targetDate로 자동 대체해 **TODAY 흐름은 동작이
  그대로다**. `order_index` 시드도 프로포절 전체 단일값에서 날짜별 값으로 바꿨다(여러 날짜에
  항목이 흩어지므로). `UNSCHEDULED`로 확정되는 항목은 scheduledDate/시각을 서버가 강제로
  비운다(REQ-EXECUTION-002).
- **실측(gpt-5-mini, 실제 OpenAI 키)으로 확인**: "이번 주 안에 강의 4개, 화·목 저녁 알바"
  시나리오를 실제로 끝까지 실행했다 — PROPOSAL이 UNSCHEDULED 후보 4개 + unavailableWindows를
  만들고, Timefold가 화요일 저녁을 피해 겹치지 않게 4개를 배치했다(0hard/0medium 달성, 전부
  배치). 이 과정에서 `spring.ai.openai.chat.options.max-completion-tokens=1200`으로는 항목당
  필드가 늘어난 새 스키마가 reasoning 토큰과 합쳐 종종 잘려 `responseType=CHAT`+빈 reply로
  폴백하는 것을 실제로 관측해 2400으로 올렸다. 프론트에서 "이 시간은 안 돼요"로 예외를
  추가하자 OpenAI 재호출 없이 Timefold만 재계산해 4개를 다른 시간대로 재배치했고, 최종
  "오늘에 적용"이 재계산된 시각 그대로 4개의 `execution_items`(TIME_FIXED)를 만드는 것도
  확인했다.
- 프론트 `AiPanelShell`: 카드에 `placementType==='UNSCHEDULED'` 후보가 있을 때만(기존
  DATE_ONLY/TIME_FIXED 전용 TODAY 흐름은 전혀 건드리지 않는다) 가용시간 요약, 신뢰도 배지,
  "이 시간은 안 돼요" 예외 폼, 미배치 목록, 항목별 "날짜·시간 직접 수정", "이 기준으로 다시
  배치" 버튼을 추가로 보여준다. 새 Proposal이 도착하거나(SSE) 적용 전 제안이 있는 대화를
  다시 열면(새로고침 포함) 저장된 미리보기를 복원하거나 없으면 자동으로 첫 계산을 요청한다.
  최종 "오늘에 적용"은 배치 결과(placed/unplaced)를 반영한 `editedItems`를 기존
  `proposalAPI.apply`에 그대로 보낸다 — 적용 API 자체나 카드/OFFER/제외 토글 구조는 바꾸지
  않았다. `api.js`에 `schedulePreviewAPI`(get/recompute)를 추가했다.
- 알려진 한계: 기존 ExecutionItem을 옮기거나 줄이는 PATCH 재계획, ContextItem 기반 장기
  자동 학습, 상시 자동 재계획(SolverManager), 실시간 ProblemChange는 이번 범위가 아니다
  (`07-ideas.md` 참고).

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
