# DOMAIN — 상태값과 전이 규칙 (diary-api)

코드(서비스 가드·매퍼 `WHERE status` 조건)에서 읽은 전이만 적는다. 경로는 `src/main/java/com/jungwoo/project/memo/` 기준.
문서와 코드가 다르면 **⚠문서차이**로 표시한다(코드가 현재 동작). 표·열은 [SCHEMA.md](SCHEMA.md).

## 범위 — 포함·제외 기준

- 실측 enum 145개(최상위 파일 94 + 클래스 안 중첩 51). 핸드오프의 "124"와 맞는 집계 기준은 찾지 못했다.
- **포함**: DB 열에 저장되고 행의 수명 동안 바뀌는 상태 enum, 그 전이를 정하는 분류 enum(배치 종류·결과·이벤트 종류).
- **제외**: 요청·응답 DTO 전용(`RequestedAction`, `PlanReviewCategory`, `PlanProvenanceResponse.*`), 한 요청 안의 계산·worker 결과(중첩 `Result`·`Mode`·`Kind`·`Stage`·`Call` 등), 계획 판단 JSON 안의 값(`plan/domain`의 `Treatment`·`ActionType`·`EvidenceType` 등), 파일 형식·매칭 휴리스틱, `ErrorCode`.
- 공통 문법: "A → B : 트리거". 별도 언급이 없으면 FROM 조건 불일치는 409, 낙관적 잠금은 `version`/`revision` 불일치 409.

## 옛 이름 → 현재 이름

| 설계 당시 이름 (99-changelog v2.1) | 현재 |
|---|---|
| `ScheduleStatus: PLANNED / DONE / HOLD / CANCELLED` (changelog 「v2.1에서 확정」) | **`ExecutionStatus`** (`PARTIAL` 추가). Todo·ScheduleBlock 모델은 2026-08-23 제거(`2026-08-23-remove-legacy-tables.sql`) |
| `PlanItemEventType` / `plan_item_events` | `ExecutionEventType` / `execution_item_events` |
| `ai_suggestions` (PROPOSED/APPLIED/MODIFIED_APPLIED/DISMISSED/EXPIRED, changelog 「AI 피드백 루프 명문화」) | **`ai_proposals` / `ai_proposal_items`** (같은 상태 집합·`created_item_type/id`). 명시적 개명 기록은 changelog에 없다. 일정·상황 후보는 따로 `ai_schedule_suggestions`, `ai_context_change_suggestions` |

## 실행 (execution)

**ExecutionStatus** `PLANNED | PARTIAL | HOLD | DONE | CANCELLED` — `execution/ExecutionItemService.java`

| 전이 | 트리거 |
|---|---|
| (생성) → PLANNED | create · 제안 적용 · 부분 수행의 남은 조각 |
| PLANNED → DONE | complete (+ COMPLETED 기록 100%, topic 진행 IN_PROGRESS) |
| PLANNED → DONE + 새 PLANNED 조각 | recordPartial (PARTIAL 기록 1~99% + `remaining_execution_item_id`, 이벤트 REDUCED) |
| DONE → PLANNED | reopen |
| PLANNED → HOLD / HOLD → PLANNED | hold / resume. HOLD는 resume 없이 완료 불가 |
| PLANNED·HOLD → (배치만 UNSCHEDULED) | unschedule |
| PLANNED에서만 | move · reduce |
| soft delete ↔ 복원 | delete(`is_deleted`, DELETED 이벤트) / restore(RESTORED) |

- FROM 불일치는 `INVALID_STATUS_TRANSITION`(409). 모든 쓰기 `version` CAS.
- `PARTIAL`·`CANCELLED`는 **읽기만 있고 현재 쓰는 경로가 없다**(평가·회고·가용시간이 제외 조건으로 읽음).
- ⚠문서차이: 05-database §12는 부분 수행 = "PARTIAL Record + 남은 Item + **SPLIT** Event"이나 코드는 원본 DONE + **REDUCED** 이벤트.

**PlacementType** `UNSCHEDULED | DATE_ONLY | TIME_FIXED` — 날짜·시각 유무로 정해진다(UNSCHEDULED = 둘 다 없음, DATE_ONLY = 날짜만, TIME_FIXED = 날짜+시작<끝, 같은 날). 롤링 배치(`plan/{id}/place`)가 시각을 부여, unschedule이 되돌림.
**ExecutionRecordOutcome** `COMPLETED(100%) | PARTIAL(1~99%, 남은 조각 필수) | NOT_DONE(0%)`.
**ExecutionEventType** `CREATED MOVED REDUCED SPLIT HOLD RESUMED REOPENED CANCELLED PRIORITY_CHANGED DELETED RESTORED`. 완료·부분·미수행은 이벤트가 아니라 record로 남긴다. **ExecutionEventActorType** `USER AI SYSTEM MIGRATION UNKNOWN`.
**ExecutionPriority** `MUST SHOULD OPTIONAL` · **ExecutionOriginType** `MANUAL AI_GENERATED ROUTINE_GENERATED IMPORTED`.

## AI 상담·제안

### draft ↔ proposal 구분

- **draft**(`ai_conversation_drafts`, `ai/draft`) = 상담 중 **아직 칸을 모으는 요청**. 검토할 변경을 담지 않는다. `DraftReadiness READY | NEEDS_INPUT | NEEDS_CONFIRM`(필수 칸 누락·미확인 칸으로 계산).
- **proposal**(`ai_proposals`) = 사용자가 **검토·적용하는 결과물**. 계획 초안(계획 화면 "초안")도 `ai_proposals` 행이다(`plan_start_date IS NOT NULL`로 구분, `PlanDraftService`가 `createFromItems` 후 `plan_*` 열 기록).
- draft는 proposal로 직접 승격하지 않는다: CREATE_ROUTINE·CREATE_SCHEDULE → `ai_schedule_suggestions` 생성, CREATE_PERIOD_PLAN → 행 없이 PROMOTED(계획 만들기 OFFER 버튼).

**DraftStatus** `OPEN | PROMOTED | CANCELLED` — `ai/draft/DraftPromotionService`, `DraftTurnResolver`
- (생성) → OPEN · OPEN → PROMOTED (resolver가 PROPOSE: READY, 사용자가 누르고 누락 없음, 또는 NEEDS_CONFIRM에서 ask_count ≥ 3)
- OPEN → CANCELLED: 모델 CANCEL op · 변경 묶음이 draft를 넘겨받음 · 대화 보관
**DraftType** `CREATE_ROUTINE | CREATE_SCHEDULE | CREATE_PERIOD_PLAN` · **FieldSource** `USER INFERRED DB SYSTEM DEFAULT`.

### proposal

**AiProposalStatus / AiProposalItemStatus** `PROPOSED | APPLIED | MODIFIED_APPLIED | DISMISSED | EXPIRED` — `ai/AiProposalService`
- (생성) → PROPOSED (헤더·항목)
- PROPOSED → APPLIED | MODIFIED_APPLIED : apply. 행 `FOR UPDATE`, 전체 한 트랜잭션(항목별 적용 경로 없음). 항목 하나라도 수정됐으면 MODIFIED_APPLIED. 제외한 항목 → DISMISSED. 조정 항목은 `base_version` 불일치 409. 전부 제외는 400.
- PROPOSED → DISMISSED : dismiss. 이미 적용이면 409, 이미 버림이면 그대로(멱등).
- 계획 초안 다시 만들기·재생성: 옛 초안 PROPOSED → DISMISSED, `loadDraft`가 대체 초안을 따라간다. 검토 상태 저장은 PROPOSED + version 조건.
- **EXPIRED는 어디서도 쓰지 않는다**(`expires_at`도 미설정). ⚠문서차이: 03·04·05번 문서는 살아 있는 상태로 적음. 03번의 `purpose`(PERIOD_PLAN/EXECUTION_CHANGE) 필드도 코드에서 확인 못 함(enum `ProposalPurpose`는 있음).
**AiProposalItemType** `PLAN_ITEM | EXECUTION_ITEM | CONTEXT_ITEM` · **AiProposalTargetScope** `PLAN TODAY EXECUTION CONTEXT MIXED MATERIAL LEARNING PLANNING` · **ProposalOperation** `CREATE REDUCE MOVE DROP`.

**계획 확정** `PlanConfirmService.confirm`: 계획 기간 필수 → 연결된 변경 묶음 먼저 적용 → 일정 충돌 확인 → proposal apply → `plan_versions` 삽입 → 새 실행 조각에 `plan_version_id`.
- `plan_versions`에는 상태 열이 없다(불변 스냅샷). `plan_key`는 기반 계획의 키로 version+1, 없으면 새 UUID·version 1. UNIQUE(plan_key,version)·UNIQUE(source_proposal_id).
- ⚠문서차이: SQL 주석과 11번 §119·123은 "version은 항상 1", 코드와 11번 §665는 재계획 시 +1.
**PlanIntensity** `LIGHT(40%) | NORMAL(65%, 기본) | FOCUSED(85%)` 채움률 · **PlanPurpose** `REVIEW PREVIEW EXAM SELF_STUDY UNSPECIFIED`(초안 입력, plan_versions에 저장 열 없음).

### 후보(suggestion)·변경 묶음

**ScheduleSuggestionStatus** `PROPOSED | APPLIED | DISMISSED` — `ai/ScheduleSuggestionService`. 생성원: 모델 출력·일정 이미지·draft 승격. apply·apply-batch는 잠금 후 도메인 행(약속·반복) 생성 → `resolveIfProposed`(1행 아니면 롤백), 배치는 한 트랜잭션. dismiss는 PROPOSED 아니면 409(**멱등 아님**). **ScheduleSuggestionKind** `COMMITMENT | ROUTINE`.

**ContextSuggestionStatus** `PROPOSED | APPLIED | DISMISSED` — `ai/ContextChangeSuggestionService`. apply: DISMISSED면 409, APPLIED면 기존 결과(멱등), 아니면 op 실행 후 `markApplied`(PROPOSED 조건). **ContextChangeOperation**의 user_contexts 효과:
`ADD` 새 ACTIVE · `SUPERSEDE` 대상 ACTIVE|STALE → SUPERSEDED + 새 ACTIVE(`supersedes_context_id`) · `MARK_STALE` ACTIVE → STALE · `ARCHIVE` ACTIVE|STALE → ARCHIVED · `CONFIRM` STALE → ACTIVE.

**변경 묶음** `ai_change_bundles` / `_items` status `PROPOSED | APPLIED | DISMISSED` (enum 아님, `ai/change/ChangeBundleRows`) — `ChangeBundleService`
- select·refresh: PROPOSED + 기대 revision 잠금 → revision+1 (refresh는 지문도 새로).
- apply(잠금): 이미 처리됐으면 같은 멱등 키·같은 해시는 저장 결과 반환, 해시가 다르면 409 `IDEMPOTENCY_REUSED`. revision 불일치 409, 선택 항목이 선택 안 된 항목에 기대면 409, 항목 `check_code`≠OK 409, `basis_fingerprint`가 현재 일정 지문과 다르면 409 **STALE**(상태가 아니라 오류 코드). 성공: 선택 항목 APPLIED, 나머지 DISMISSED, 묶음 APPLIED.
- dismiss: 항목 전부 + 묶음 DISMISSED.

### 대화·메시지·기억

**ConversationStatus** `ACTIVE → ARCHIVED`(대화 삭제, 멱등). 되돌리는 경로 없음, 보관 대화에 전송하면 404. draft OPEN은 함께 CANCELLED.
**MessageStatus** `PROCESSING → COMPLETED | FAILED` (USER 메시지, `WHERE status = fromStatus`). 늦게 온 결과는 버린다. 오래된 잠금 회수 시 FAILED. ASSISTANT 메시지는 COMPLETED로 삽입. **MessageRole** `USER ASSISTANT` · **AiResponseType** `CHAT OFFER PROPOSAL` · **AiModelDecision** `CHAT ASK_CLARIFICATION OFFER_PROPOSAL PROPOSAL_READY`.

**UserContextStatus** `ACTIVE | STALE | SUPERSEDED | ARCHIVED | WITHDRAWN` — `ai/UserContextService`
- edit: 옛 행 ACTIVE|STALE → SUPERSEDED + 새 ACTIVE(USER_EDITED·STATED). 한 값만 살아야 하는 사실 종류는 같은 종류 다른 행도 SUPERSEDED.
- confirm: ACTIVE|STALE → ACTIVE, 근거 STATED · withdraw: ACTIVE|STALE → WITHDRAWN(행 유지)
- STALE을 만드는 곳은 MARK_STALE 하나. ARCHIVED·WITHDRAWN은 끝 상태.
**ContextSourceType** `USER_CONFIRMED AI_SUGGESTION_APPROVED CONSULT_AUTO USER_EDITED SELF_CHECK` · **ContextEvidenceType** `STATED SELF_REPORT OBSERVED INFERRED` · **FactKind** `PROGRESS EXAM_SCOPE DIFFICULTY RESOLVED GOAL PREFERENCE CONSTRAINT OTHER`.

## 자료 (material)

**MaterialStatus** `ACTIVE → DELETED`(한 트랜잭션: 열린 작업 취소·연결 삭제·본문 단위 삭제, 열린 변경안 STALE). 되돌리기 없음.
**ExtractionStatus** `PENDING(DB 기본값, 코드는 안 씀) | SUCCESS | FAILED_NO_TEXT | FAILED`. 재추출은 FAILED*에서만(SUCCESS면 409, 상담 사진은 불가), 성공 시 CONTENT 작업 등록·끝난 묶음 다시 열기.
**MaterialType** `SYLLABUS TEXTBOOK_TOC PROFESSOR_SLIDE OTHER` — 연결(`material_links`)마다의 역할.

**AnalysisJobStatus** `QUEUED RUNNING DONE PARTIAL FAILED UNAVAILABLE PAUSED CANCELLED` · **AnalysisJobKind** `CONTENT | LINK` — `material/analysis/MaterialAnalysisJobService`, `…JobRunner`
- (생성) → QUEUED (`INSERT IGNORE`, 자료·과목·종류·해시·분석판 유일, max_attempts 3)
- QUEUED(시각 도래)·RUNNING(임대 만료) → RUNNING : claim(lease_token·attempt +1, 일시정지 사용자 제외, 일일 한도 CONTENT 60 / LINK 120)
- RUNNING → DONE | PARTIAL(청크 예산 초과) | CANCELLED(자료 삭제·해시 변경) | FAILED(본문 없음)
- RUNNING → UNAVAILABLE(인증 실패 + 전역 쿨다운) · → QUEUED(rate limit 지수 백오프 / 그 밖 30s·120s·480s), attempt ≥ max면 FAILED
- QUEUED → PAUSED / PAUSED → QUEUED (pause·resume, RUNNING은 안 멈춤) · 끝난 상태 → QUEUED(재시도, attempt 0)
- 결과 쓰기는 모두 `lease_token AND status='RUNNING'` + `FOR UPDATE`.
- LINK 작업 생성은 기본 꺼짐(`link-jobs.enabled=false`). ⚠문서차이: 15번은 백로그가 LINK도 만든다고 적음, "3회 초과 실패"라 적었지만 코드는 3번째 실패에서 FAILED.

**MaterialAnalysisStatus** `DRAFT → APPLIED | DISMISSED`, 실패는 FAILED로 삽입. 열린 DRAFT 하나(`draft_guard` 생성 열 + UNIQUE). 상태 UPDATE에 조건이 없어 동시 apply·dismiss는 DB가 직렬화하지 않는다(코드 관찰).
**TopicChangeProposalStatus** (2026-09-21 이후 이력 전용) `PROPOSED → APPLIED | DISMISSED | STALE`, 빈 op는 EMPTY, 마이그레이션이 SUPERSEDED. `CONFLICT`는 쓰는 코드 없음.
**BatchStatus** `STAGED → UPLOADING → … → FINISHED ⇄ ANALYZING`, STAGED 24h 방치 → ABANDONED. ANALYZING은 응답 계산값이고 저장은 FINISHED → ANALYZING(재시도·재추출)뿐. **BatchItemUploadState** `STAGED|UPLOADING → UPLOADED | NO_TEXT | UPLOAD_FAILED | ABANDONED(30분 고아·ZIP 취소)`, ZIP 재시도 시 → STAGED.
**ZipImportStatus** `PREPARING → READY | FAILED`, confirm → IMPORTING → `COMPLETED | PARTIAL | FAILED`, cancel → CANCELLED, 기한 지난 비종결(IMPORTING 제외) → EXPIRED. **ZipEntryStatus** `PENDING|FAILED → QUEUED → IMPORTING → DONE | FAILED`, 재시도는 FAILED에서만.

## 프로젝트·학습

**CourseStatus** `ACTIVE ⇄ ARCHIVED`(archive = DELETE 경로, 열린 정리 작업 취소, 데이터 유지). ARCHIVED는 업로드·ZIP·반복 일정 연결·교재 조회·정리 worker·변경안을 막는다. 교재 칸 변경은 `textbook_version` CAS(불일치 `E409_040`).
**TopicStatus** `ACTIVE → ARCHIVED`(병합으로만, `merged_into_topic_id`). **TopicSourceType** `SOURCE | AI_DERIVED`.
**TopicProgressStatus** `NOT_STARTED | IN_PROGRESS | LEARNED` — 행이 없으면 NOT_STARTED. 수동 PATCH는 아무 값 → 아무 값(이력 `topic_learning_events` STATUS_CHANGED). 실행 완료는 LEARNED가 아니면 IN_PROGRESS로, LEARNED면 review_count+1(LEARNED로는 올리지 않음).
**TopicUserMark** `KNOWN | DEFER | null` — 진행 상태와 별개 축, 아무 값 → 아무 값.
**RecommendationStatus** `SUGGESTED → PLANNED`(planning agent 적용). `SENT_TO_PLANNING` 쓰는 경로는 호출자 없음, `DISMISSED`는 쓰는 코드 없음. **ActivityType** `NEW_LEARNING REVIEW CONTINUE SKIP`.
**AssignmentConfirmStatus** `CANDIDATE`(자료 분석이 만듦) / `CONFIRMED`(직접 생성·AI 모듈) → answer로 `CONFIRMED | NOT_ASSIGNMENT | LATER | DUPLICATE`(FROM 조건 없음, version CAS, DUPLICATE는 다른 과제 id 필요). **DueKind** `UNKNOWN NONE DATE DATETIME` · **DueSource** `SOURCE | USER`(`ESTIMATED`는 쓰는 코드 없음 — ⚠05·15번은 있다고 적음).

**교재 조회** `textbook_lookups.status`(CHECK, enum 아님): QUEUED → RUNNING(claim) → `FOUND | BOOK_NO_TOC | NEEDS_CHOICE | NOT_FOUND | ACCESS_FAILED | FAILED`, 단서 경로는 `NOT_FOUND | CLUE_CONFLICT`. 근거(basis)가 바뀌거나 과목 보관·재시도·판 선택이면 열린 조회 → SUPERSEDED. `CANCELLED`·`DISABLED`는 쓰는 코드 없음.

**프로젝트 정리** — `learning/tidy`
- **TidyJobStatus** `QUEUED → RUNNING → DONE | FAILED | CANCELLED`, 인증 실패 UNAVAILABLE, rate limit·일시 오류는 QUEUED 재예약(20s·90s·300s 후 FAILED). 과목당 열린 작업 하나(`open_guard`). 재시도는 FAILED·UNAVAILABLE에서만. dismiss·과목 보관 → CANCELLED.
- **TidyProposalStatus** `PROPOSED → APPLIED | DISMISSED | SUPERSEDED`, 빈 op는 EMPTY. 열린 정리안 하나. apply는 `FOR UPDATE` + status·revision·edit_revision·트리 버전 모두 대조(불일치 409, `TOPIC_TREE_CONFLICT`), 마지막 갱신 0행이면 전부 롤백. 새 정리안이 나오면 옛 PROPOSED → SUPERSEDED. 편집 저장은 `edit_revision` CAS.

## 학습 이벤트·일정

**학습 이벤트** (`learning/events/LearningEventWriter`) — 상태 전이가 아니라 **판(revision) 모델**.
- origin = 원천 한 행(`OriginKind` EXECUTION_RECORD·TOPIC_PROGRESS·TOPIC_MARK·USER_CONTEXT·CLASS_INPUT·SCHEDULE_EXCEPTION 등). 쓰기마다 origin을 잠그고, 출력이 지금과 같으면 UNCHANGED, 다르면 `current_revision+1`로 output_no 1..n 행을 새로 넣는다. 출력 0개도 판이다(철회). 행은 추가 전용, 살아 있는 것은 `origin_revision = current_revision`.
- **Actor** `ME CLASS SYSTEM` · **Verb** 17개(`RELEASED COVERED_IN_CLASS CANCELLED SESSION_MOVED SCOPE_ANNOUNCED SCOPE_EXCLUDED PLAN_CHANGED STUDIED ATTEMPTED STUCK RESOLVED ABSENT SELF_ASSESSED SUBMITTED STATED RETRACTED SUPERSEDES`) · **ObjectKind** `SECTION ITEM TOC_ENTRY PAGE WEEK SESSION MATERIAL COURSE` · **Evidence** `STATED INPUT LOGGED RULE APPROX INFERRED`.
- 수업 확인(`ClassSessionService.confirm`, 회차 = (routine_id, source_date)): COVERED → CLASS COVERED_IN_CLASS(출처 필수) · UNKNOWN_CONTENT → 같은 verb·출처 없음 · CANCELLED → CLASS CANCELLED · ABSENT → ME ABSENT. 기대 revision 불일치 409.

**RoutineExceptionType** `SKIP | MOVED` — MOVED는 옮길 날짜 필수, SKIP은 이동 칸 금지. 날짜당 예외 하나. 날짜는 요일·유효 기간 검사(**RoutineExceptionConflictReason** `DAY_OF_WEEK_MISMATCH | OUTSIDE_EFFECTIVE_RANGE`), 반복 일정 수정 시 기존 예외를 다시 검사해 어긋나면 409(자동 삭제 안 함). 반복 일정 삭제는 soft, 예외 삭제는 hard(빈 이벤트 판을 먼저 씀).
**약속** `one_off_commitments`: version 0 삽입, 수정·soft delete는 `version` CAS + `is_deleted=0`. **CommitmentSourceType** `MANUAL | AI_SUGGESTION_APPROVED` · **DerivedTravelRelation** `BEFORE_WORK | AFTER_WORK` — 서버만 만들고, 원 약속의 시각이 바뀌거나 없어지면 409. (원 약속, 관계)당 살아 있는 파생 하나.
