# 99. Change Log

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
