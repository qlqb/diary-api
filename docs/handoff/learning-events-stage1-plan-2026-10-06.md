# 이벤트 기반 학습 진도 — 1단계 구현 계획 (2026-10-06, v2.1)

설계 `docs/product/20-learning-events.md`(v2.1, Codex 재리뷰 APPROVED). 이 계획은 설계 §14의 1단계(+1b 백필)를 구현 단위로 쪼갠다.
기존 동작은 바꾸지 않는다 — 원본 표와 토픽 경로는 그대로 쓰고, 이벤트가 그 옆에 같은 트랜잭션으로 쌓인다.
v2: Codex 계획 리뷰 1회차 REVISE 17건 반영(§10). v2.1: 재리뷰 9건 판단·반영(§11 — 본문과 다르면 §11이 우선).

## 0. 범위와 순서

| PR | 저장소 | 내용 |
|---|---|---|
| A | diary-api | 마이그레이션, `learning_events`·`learning_event_origins`·`textbook_refs`, `LearningEventWriter`(검증·판), `LearningEventLog`, 디버그 조회 API, 교재 ref 연결 |
| B | diary-api | 쓰기 경로 연결(§3), 해결 연결 표, 자료 삭제 시 구간 제목 정리 |
| C | diary-api | `class_sessions` + 수업 확인 API + 일정 예외 → 회차 이벤트 + 과목별 끄기 |
| D | diary-ui | 수업 확인 카드(오늘·과목 화면), 과목 설정 토글 |
| E | diary-api | 백필(1b) |

A → B → C → D 순서로 머지, E는 B가 운영에 반영되고 cutover가 기록된 뒤. 2단계 뷰는 이번 범위가 아니다 — 1단계는 이벤트가 **정확히 쌓이는지**만
책임진다.

설계에서 계획 단계로 정한 것(B와 함께 설계 문서도 고친다):
- **판 저장 위치:** 원본 표마다 열을 더하지 않고 `learning_event_origins`(origin별 현재 판) 한 표에 둔다(설계 §6.3 문장 수정).
- **현재 값 대체는 출력 0 판으로:** 진도·시험 범위·자기 점검이 새 값으로 대체되면 옛 origin을 출력 0 판으로 내린다. 옛 값은 이전 판에 남는다.
  그래서 최신 값을 철회해도 옛 값이 되살아나지 않는다(원본의 SUPERSEDED와 같은 결과). 1단계에는 SUPERSEDES 이벤트를 만드는 경로가 없다
  (CHECK·접기는 미리 둔다).
- **호환 앵커 `topic_id`:** 원천을 확실히 못 찾은 실시간 기록도 활동은 남기되(`object_kind=COURSE`, evidence APPROX), 2단계 내 학습 진도는 원천
  대상만 센다. 토픽은 표시·디버그용.
- **CLASS 작업(정리안)은 항상 주차 진술:** 토픽별 행이라 회차 집합으로 합치지 않는다. 회차 집합(COVERED_IN_CLASS)은 수업 확인 API만 쓴다.
- **시험 범위(기억)는 1단계에서 항상 AMBIGUOUS:** 기억에는 토픽 하나와 기간뿐이라 범위 양끝을 알 수 없다. 앵커만 남기고 2단계에서 해석한다
  (설계 §6.2에 `coordinate:"UNKNOWN"` 추가).
- **배치 묶음(batchId)은 이벤트에 넣지 않는다.** 뷰가 `material_analysis_batch_items`를 조인.

## 1. 스키마 (`docs/sql/2026-10-06-learning-events.sql`, 추가형·재실행 가능)

```sql
learning_event_origins
  user_id BIGINT, origin_kind VARCHAR(24), origin_id BIGINT,
  course_id BIGINT NOT NULL,               -- 처음 쓸 때 정하고 바꾸지 않는다(다르면 Writer 예외)
  current_revision INT NOT NULL, updated_at DATETIME(3)
  PRIMARY KEY (user_id, origin_kind, origin_id)

learning_events
  event_id BIGINT AUTO_INCREMENT PK
  user_id, course_id BIGINT NOT NULL
  origin_kind VARCHAR(24), origin_id BIGINT, origin_revision INT, output_no SMALLINT   -- 모두 NOT NULL
  actor VARCHAR(8), verb VARCHAR(24), object_kind VARCHAR(12), object_ref VARCHAR(200)  -- NOT NULL, CHECK 목록
  object_from INT NULL, object_to INT NULL, topic_id BIGINT NULL
  payload LONGTEXT NOT NULL CHECK (JSON_VALID(payload))   -- {"v":1,...}
  evidence VARCHAR(10) NOT NULL, confidence DECIMAL(3,2) NULL
  claim_at DATETIME(3) NULL, claim_seq BIGINT NULL, occurred_at DATETIME(3) NULL
  recorded_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
  UNIQUE (user_id, origin_kind, origin_id, origin_revision, output_no)
  INDEX (user_id, course_id, object_kind, object_ref), INDEX (user_id, course_id, recorded_at)

textbook_refs         book_ref_id PK, user_id, created_at
textbook_ref_aliases  user_id, book_key VARCHAR(400), book_key_hash CHAR(64), book_ref_id, created_at
                      UNIQUE (user_id, book_key_hash)  -- 조회 후 전체 키 비교

learning_resolution_links   difficulty_context_id BIGINT PK, resolver_context_id BIGINT NOT NULL, user_id, created_at, updated_at
learning_event_meta         meta_key VARCHAR(40) PK, meta_value VARCHAR(100), updated_at   -- 'cutover_at'

-- C
class_sessions        session_id PK, user_id, course_id, routine_id, source_date DATE,
                      start_at, end_at DATETIME(당시 스냅샷), created_at — UNIQUE (routine_id, source_date)
courses.class_prompt_enabled TINYINT(1) NOT NULL DEFAULT 1
-- E
learning_event_backfill     source VARCHAR(30) PK, last_id BIGINT, done TINYINT, report LONGTEXT,
                            lease_owner VARCHAR(64), lease_until DATETIME
```

- 모든 표 `ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARSET=utf8mb4 COLLATE utf8mb4_unicode_ci`(기존 표와 같은 collation 확인 후 맞춘다).
  긴 키는 해시 UNIQUE로 피한다.
- FK는 두지 않는다(방침). 사용자 삭제 경로가 있으면 새 표를 넣는다.
- **교재 ref 시드:** 기존 `courses.textbook_toc_book_key`, `course_topics.source_textbook_key`의 사용자별 서로 다른 값마다 ref 하나 + 별칭 하나.
  같은 책의 키 변경은 이미 `CourseTextbookWriter`가 토픽 키를 새 키로 옮겨 두었으므로(같은 책인데 키가 다른 값은 남아 있지 않다) 1:1 시드로
  충분하다. 합치기 러너는 두지 않는다.

### 1.1 object_ref 형식

| kind | ref | 비고 |
|---|---|---|
| SECTION | `s:{sectionId}` | `section_id=0`(자료 전체)은 MATERIAL로 |
| MATERIAL | `m:{materialId}` | |
| TOC_ENTRY | `t:{bookRefId}:{keyHash}:{keyLine}` | `toc_key_state=SET` 목차 토픽만 |
| SESSION | `c:{sessionId}` | C 이후 |
| COURSE | `-` | |
| ITEM·PAGE·WEEK | 2·3단계 | CHECK에는 미리 넣는다 |

## 2. 핵심 클래스 (A)

`learning/events/` 패키지.

### 2.1 `LearningEventWriter` — 유일한 쓰기 입구 (`propagation = MANDATORY`)

```java
WriteResult write(Origin origin, List<EventDraft> outputs)   // Origin(userId, courseId, kind, id)
```
1. origin 행 `INSERT IGNORE` 후 `SELECT … FOR UPDATE`(잠금 읽기 — 격리 수준과 무관하게 최신 커밋 값을 본다). course_id가 다르면 예외.
2. 현재 판의 출력을 `SELECT … FOR UPDATE`로 읽는다(같은 이유).
3. **정규화 비교:** 출력마다 (verb, object_kind, object_ref, object_from, object_to, topic_id, evidence, confidence(소수 2자리), claim_at(밀리초),
   claim_seq, occurred_at(밀리초), actor, payload(Jackson 트리 비교, 키 순서 무관)). 출력 목록은 **중복 제거 후** 이 전체 튜플로 정렬해
   `output_no`를 매긴다. 같으면 아무것도 하지 않는다(재시도·같은 값 저장이 판을 올리지 않음).
4. 다르면 `current_revision+1`로 새 판 전체를 INSERT, origin 갱신. 출력 0개도 판이다.
5. 같은 판에 다른 내용이 이미 있으면 `IllegalStateException`(버그 — 롤백).

**대상 밖과 위반을 나눈다.** 과목이 없거나(실행 항목의 course_id NULL), 과목이 그 사용자 것이 아니면 Writer를 부르지 않고 건너뛴다(로그 + 카운터,
기존 동작 유지). Writer 안의 검증 실패는 위반이라 예외 → 원본과 함께 롤백. 호출 서비스는 "대상 밖" 판정을 Writer 호출 전에 `EventScope.of(...)`로 한다.

### 2.2 `LearningEventValidator` (Writer 안, 잠금 없는 읽기 — 소유·존재는 바뀌지 않는 값만 본다)

- **과목:** `courses.user_id = origin.user_id`, 삭제되지 않음.
- **origin 귀속:** origin 원본 행을 읽어 user·course가 맞는지(EXECUTION_RECORD → record·item, USER_CONTEXT → 행, …). 
- **topic_id:** 그 과목의 토픽.
- **SECTION:** 구간의 자료가 사용자 소유. **새 참조**(이 origin의 현재 판에 없던 object)는 그 과목에 지금 연결돼 있어야 한다. **기존 참조**(현재 판에
  이미 있는 object를 다시 쓰는 경우 — 회고 수정 등)는 소유만 확인한다. `object_from/to`는 구간의 쪽 범위(있을 때) 안.
- **MATERIAL:** 사용자 소유(새 참조는 그 과목 연결).
- **TOC_ENTRY:** book ref가 사용자 것이고, 그 ref의 별칭 중 하나가 그 과목의 `courses.textbook_toc_book_key` 또는 그 과목 토픽의
  `source_textbook_key`와 같다. 항목 존재: `toc_key_hash`가 사용자가 접근할 수 있는 리비전(`SHARED`/`USER:{id}` 규칙 그대로의 조회)의
  `toc_raw_hash`이고, `toc_key_line`이 그 원문의 줄 수 이하. (업로드 목차 열쇠 `MATERIAL`은 자료 파일 해시가 그 과목의 목차 자료와 같은지.)
- **SESSION:** 그 사용자·과목의 회차.
- **payload 안의 참조:** verb별 payload record가 `references()`로 원천 목록(`sources[]`)과 이벤트 목록(`resolves`, `target`, `by`)을 내고, 같은 규칙으로
  전부 검증한다. RESOLVED의 `resolves`는 같은 사용자·과목의 **현재 판에 살아 있는 STUCK**이어야 한다.
- `user_id`·`course_id`·origin·evidence는 서버 값(요청 DTO에서 받지 않음).

### 2.3 그 밖

- **`EventDraft`**, verb별 payload record(`v:1`). **원문 텍스트 필드 없음**(메모·막힌 단계 글·기억 본문·시험 이름 외 사용자 글 — 시험 이름은
  `examKey`로 40자 이하 그대로, 이건 키다).
- **`LearningEventLog`** — 읽기: origin 현재 판 − RETRACTED 대상, SUPERSEDES 접기(1단계엔 생산자 없음). 디버그 API
  `GET /api/courses/{courseId}/learning-events?after=&limit=50`(본인 것만, object_ref·verb·payload만 — 제목·본문 없음).
- **`SourceResolver`** (§3.1), **`TextbookRefService`** — `refFor(userId, bookKey)`(없으면 만들고 별칭 INSERT IGNORE), `addAlias(userId, oldKey, newKey)`.
  - `CourseTextbookWriter.write`: 같은 책인데 키가 바뀌면 `addAlias(old → new)`(같은 ref). 다른 책이면 새 키의 ref는 처음 필요할 때 만든다.
  - `linkToc`, 목차로 토픽을 만드는 경로(`TocSkeleton`/`TocOps` 적용)는 `refFor` 호출. 이벤트가 이미 가리킨 ref는 합치지 않는다(별칭 추가만).

## 3. 쓰기 경로 (B)

"잠금 순서"는 기존 호출자가 잡는 것을 포함한 전체. Writer의 origin 잠금은 항상 **마지막**이고, Validator는 잠그지 않는다. 한 트랜잭션에서 여러
origin을 쓰면 (origin_kind, origin_id) 오름차순으로 쓴다.

| 경로 | origin | 잠금 순서 | 출력 |
|---|---|---|---|
| `ExecutionItemService.complete` | EXECUTION_RECORD | item 버전 UPDATE → record INSERT → (리스너 topic_progress) → origin | record를 **id로 다시 읽어**(recordedAt 등 DB 값) 원천마다 ATTEMPTED `{outcome:DONE_UNGRADED, supportLevel:SOLO/GUIDED/UNKNOWN, blockerKind?, hasStuckStep}`. stuckStep 있음 또는 blockerKind=CONCEPT → 원천마다 STUCK. occurred_at=endedAt ?? recordedAt |
| `recordPartial` | EXECUTION_RECORD | 나머지 item INSERT → record INSERT → 원래 item UPDATE → origin | ATTEMPTED `{outcome:PARTIAL, completionPercent}` (+STUCK 규칙) |
| `reopen` | — | — | 없음 |
| `updateReflection` | EXECUTION_RECORD | **record `SELECT … FOR UPDATE` 추가** → UPDATE → origin | **원천은 다시 찾지 않는다** — 현재 판 출력의 원천(object)을 그대로 쓰고 수행 정보(supportLevel·blockerKind·hasStuckStep → STUCK 유무)만 다시 계산 |
| `TopicService.updateProgressStatus` | TOPIC_PROGRESS | progress UPDATE → origin | LEARNED → SELF_ASSESSED `{level:"KNOW", via:"STATUS"}`, 그 밖 → 출력 0 |
| `recordExecutionCompleted`(리스너) | — | — | 없음 |
| `TopicService.updateUserMark` | TOPIC_MARK(topic_id) | course_topics UPDATE → origin | KNOWN → SELF_ASSESSED `{level:"KNOW", via:"MARK"}`, DEFER·null → 출력 0 |
| `saveSelfChecks` | 새 행 USER_CONTEXT, 대체된 옛 행 USER_CONTEXT | **과목 행 `FOR UPDATE` 추가 → 기존 점검 조회를 `FOR UPDATE`로 변경** → UPDATE/INSERT → origin들 | 새 행 SELF_ASSESSED `{level}`, 옛 행 출력 0 |
| `autoSave` 새 행 | USER_CONTEXT | 과목 행(READ COMMITTED, 기존) → INSERT → origin | §3.2 |
| `autoSave` 현재 값 대체(진도·시험 범위) | 옛 행 USER_CONTEXT | 같음 | 옛 행 출력 0 |
| `autoSave` 막힘 → 해결(:337-344, :366-373, :407-409, :441-443) | RESOLUTION(difficulty_context_id) | 같음 → `learning_resolution_links` UPSERT → origin | 그 막힘 origin의 살아 있는 STUCK마다 RESOLVED `{resolves, helped}`. 막힘 origin(STUCK)은 그대로 둔다 |
| `restated`·`promoteToStated`·`touchSaid` | USER_CONTEXT(같은 행) | 과목 행 → UPDATE → origin | 다시 계산(evidence·claim_at·claim_seq가 바뀌면 새 판) |
| `confirm` | USER_CONTEXT | 과목 행 → UPDATE → origin | 다시 계산, 대체된 옛 값은 출력 0 |
| `edit` | 옛 행 출력 0, 새 행 출력 | 과목 행 → … | 옛 행이 해결자였으면(`learning_resolution_links.resolver`) 링크를 새 행으로 옮기고 RESOLUTION 다시 계산 |
| `withdraw` | USER_CONTEXT | 과목 행 → UPDATE → origin | 출력 0. 해결자였으면 RESOLUTION 출력 0(막힘이 다시 열린 것으로 보인다) |
| `retargetPhotoTopic`(`ConsultPhotoTxService.setTopic`) | USER_CONTEXT(바뀐 행들) | 과목 행 → … | 다시 계산 |
| `ContextChangeSuggestionService.apply` | USER_CONTEXT | 후보 행(기존) → **해당 기억 행에 fact_kind가 있으면 과목 행 잠금 추가** → … | SUPERSEDE·ARCHIVE·MARK_STALE → 옛 행 출력 0. 새 행은 fact_kind가 없어 출력 없음 |
| `CourseCorrectionService.apply` SCOPE_EXCLUDE | SCOPE_EXCLUSION | 정리안 적용(자료·연결 → 과목, 기존) → upsert → origin | 원천마다 SCOPE_EXCLUDED `{examKey: label, restore:false}` |
| `removeExclusion` | SCOPE_EXCLUSION | **과목 행 잠금 추가** → UPDATE → origin | 출력 0 |
| `CourseCorrectionService.apply` CLASS | CLASS_PROGRESS | 정리안 적용 → upsert/번호 → origin들(id 오름차순) | STATED(COURSE) `{claim:"COVERED_IN_WEEK", week, classSeq, sources:[원천]}`. 번호가 바뀐 행들도 다시 계산 |
| `AiTurnLifecycleService.completeTurnSuccess` | MESSAGE_PHOTO(사용자 message_id) | 기존 완료 트랜잭션 → origin | 성공한 턴의 사용자 메시지에 붙은 사진마다 STUDIED(SECTION=사진 구간) `{activity:"PHOTO_TURN"}`, LOGGED. 실패한 턴·업로드만은 없음 |
| `MaterialTxService.createWithLink`(일반·ZIP 공통)·`MaterialService.addLink` | MATERIAL_LINK(link_id) | INSERT → 연결 행을 다시 읽어(linked_at) → origin | RELEASED(MATERIAL) `{uploadedAt, materialType}`. 사진 자료(CONSULT_PHOTO) 제외 |
| `updateLinkType` | MATERIAL_LINK | UPDATE → origin | 다시 계산 |
| `MaterialTxService.markDeleted` | — | — | 이벤트 그대로. **구간 정리 추가:** `clearTextByMaterialId`에 제목·라벨 등 텍스트 열도 비우게 한다(§3.3) |

- **기존 동작 무변화:** 반환값·검증·원본 쓰기는 바꾸지 않는다. 바뀌는 것: 잠금 추가 4곳(`updateReflection` 레코드, `saveSelfChecks` 과목·점검 조회,
  `removeExclusion` 과목, 옛 후보 적용 과목), 자료 삭제 시 구간 제목 정리. 잠금은 모두 기존 순서(과목 → 원본)와 같은 방향이거나 단일 행이다.
- **Writer 예외는 원본과 함께 롤백**(누락 0). 상담 자동 저장은 원래 실패해도 턴이 성공하는 구조라 그대로.

### 3.1 원천 찾기 (`SourceResolver`)

- **실행 기록(첫 판에서만):** `PlanItemDetailService.originOf`로 제안 항목 → provenance 인용 중 MATERIAL_SECTION 전부(상한 20) → LOGGED. 인용된 TOPIC이
  목차 토픽이면 TOC_ENTRY(LOGGED — 계획이 그 항목을 직접 인용). 인용이 없으면 `execution_items.topic_id`의 목차 열쇠 → **APPROX**, 그다음 토픽의
  `topic_material_links`(section_id≠0) → APPROX(0.4). 다 없으면 COURSE(APPROX) + `topic_id`. 인용 읽기는 `StartSourceResolver`·
  `PlanItemDetailService.groundingOf`와 공용 메서드로 빼고, 기존 호출자는 지금 필터를 그대로 적용.
- **기억·자기 점검:** `section_id` → SECTION, `topic_photo_id` → 사진 구간, 목차 토픽 → TOC_ENTRY(기억의 evidence 그대로 — 사용자가 그 단원을 가리켰다),
  아니면 토픽 연결 구간(APPROX). 없으면 COURSE.
- **범위 제외·CLASS:** 목차 토픽 → TOC_ENTRY(INPUT), 연결 구간(APPROX), 없으면 COURSE + `topic_id`.

### 3.2 기억 종류별 출력

| fact_kind / source | verb | evidence | payload |
|---|---|---|---|
| PROGRESS | STATED(원천 또는 COURSE) | STATED/SELF_REPORT→STATED, INFERRED→INFERRED | `{claim:"PROGRESS"}` |
| EXAM_SCOPE | SCOPE_ANNOUNCED(COURSE) | 같음 | `{examKey, coordinate:"UNKNOWN", anchor:[원천], state:"AMBIGUOUS"}` |
| DIFFICULTY | STUCK(원천마다) | 같음 | `{}` |
| RESOLVED | 없음 — 해결은 RESOLUTION origin(§3) | | |
| SELF_CHECK | SELF_ASSESSED | STATED | `{level}` |
| 그 밖 | 없음 | | |

claim_at = said_at ?? confirmed_at ?? created_at, claim_seq = source_message_id.
대상 없는 해결 진술(막힘을 가리키지 않음)은 이벤트를 만들지 않는다 — 예외도 내지 않는다.

### 3.3 자료 삭제와 구간 텍스트

`MaterialSectionMapper.clearTextByMaterialId`가 지금은 excerpt·taskText·assignmentQuote만 비운다. 제목·라벨·위치 문구도 비우고(빈 문자열,
NOT NULL 열은 ''), 구간 id·순번·쪽 범위·역할은 남긴다. 삭제된 자료의 구간을 읽는 기존 조회가 DELETED를 거르는지 확인하고 테스트한다.

## 4. 수업 확인 (C)

- **회차 찾기:** `RoutineOccurrenceService`에 `occurrenceOf(userId, routineId, sourceDate)`(원래 날짜 기준, SKIP도 `cancelled=true`로, MOVED는 목적지
  시각) 와 `occurrencesBySourceDate(userId, from, to)`(원래 날짜 창 기준 — 목적지가 창 밖이어도 포함)를 더한다. 기존 `expand`는 그대로.
- **회차 만들기:** `ClassSessionService.ensure(userId, routineId, sourceDate)` — `INSERT IGNORE` 후 조회, 시각은 그때의 스냅샷. 만들 때 그 날짜의
  `routine_exceptions`가 있으면 아래 일정 예외 이벤트도 같이 쓴다.
- **일정 예외 → 회차 이벤트:** origin SCHEDULE_EXCEPTION(routine_exception_id). `RoutineService`의 예외 추가·수정·삭제 경로에서, 그 (routine,
  날짜)의 회차 행이 **이미 있으면** 쓴다(없으면 `ensure`가 나중에 쓴다): SKIP → CANCELLED(SESSION, actor CLASS, INPUT), MOVED → SESSION_MOVED
  `{movedTo}`, 삭제 → 출력 0. 루틴 시각 변경·삭제는 기존 회차 스냅샷을 바꾸지 않는다.
- **조회:** `GET /api/courses/{courseId}/class-sessions/pending?days=14` — 원래 날짜가 창 안이고 끝난(이동은 목적지 끝 시각 기준) 회차 중 살아 있는
  COVERED_IN_CLASS(사용자 확인)·사용자 CANCELLED·ABSENT가 없는 것. 시간표에서 SKIP한 회차는 이미 휴강으로 알고 있으므로 pending에 넣지 않는다(§11-8). 기본값 후보:
  1. 지난 확인 회차의 마지막 원천 다음 구간(같은 자료 안 순서 → 다음 자료; 자료 순서는 `material_week_assignments` 주차 → 파일명 번호 → 연결 시각)
  2. 지난 확인 이후 새로 연결된 수업자료(PROFESSOR_SLIDE·OTHER)의 첫 구간
  - 구간 제목·쪽 범위만(본문 없음). 계획서·교재 기반 기본값은 2단계.
- **확인:** `PUT /api/class-sessions` — 요청 전체가 **한 트랜잭션**(부분 성공 없음).
  ```json
  {"courseId":1,"items":[{"routineId":3,"sourceDate":"2026-10-06","expectedRevision":0,
     "action":"COVERED|UNKNOWN_CONTENT|CANCELLED|ABSENT","sources":[{"kind":"SECTION","ref":"s:123","from":12,"to":25}]}]}
  ```
  - 과목 행 잠금 → 회차 ensure(session_id 오름차순) → origin CLASS_INPUT(session_id) 잠금.
  - 항목마다 출력을 만들고 origin 현재 판과 비교: 같으면 성공(재전송), `expectedRevision`이 현재 판과 같으면 새 판, 둘 다 아니면 **요청 전체 409**
    + 회차별 현재 상태 반환.
  - COVERED → COVERED_IN_CLASS(INPUT) `{sources}`, UNKNOWN_CONTENT → `{sources:[], unknownContent:true}`, CANCELLED → CANCELLED, ABSENT → ABSENT(actor ME).
  - 상한: 회차 20, 회차당 원천 30.
- **끄기:** `PATCH /api/courses/{courseId}`의 `classPromptEnabled`. 꺼진 과목은 pending이 빈 목록(수업 후·주간 둘 다).

## 5. 화면 (D, diary-ui)

- 오늘 화면: 확인 안 한 끝난 회차가 있으면 카드 하나(가장 최근). [맞아요]=기본값, [고치기]=후보·구간 선택·"아직 자료가 안 올라왔어요"·"휴강",
  [나중에]=하루 접기.
- 과목 화면: "이번 주 수업 확인" — 그 과목 pending 전부, [모두 맞아요]. 409면 새 상태로 다시 그린다.
- 과목 설정: "수업 후 확인 묻기" 토글.

## 6. 백필 (E)

- **cutover:** B가 처음 뜰 때 `learning_event_meta.cutover_at`을 한 번 기록한다(이미 있으면 그대로). 백필은 `cutover_at`이 **10분 이상 지난 뒤**에만
  시작한다(그 전 트랜잭션이 끝났음을 보장 — 이 앱의 트랜잭션은 초 단위). 대상은 `created_at < cutover_at`인 원본(원본 표마다 생성 시각 열 확인).
- **단일 실행:** `learning_event_backfill` 행마다 lease(`UPDATE … SET lease_owner=?, lease_until=NOW()+5분 WHERE lease_until IS NULL OR lease_until<NOW()`).
  설정 `learning.events.backfill.enabled=true`일 때만 백그라운드 1회. 운영 실행은 사용자가 정한다(백업 뒤 켜고 한 번 띄움).
- **한 건 = 한 트랜잭션:** 원본 행 `FOR UPDATE` → origin 존재를 잠금 읽기로 확인(있으면 실시간이 썼으니 건너뜀) → Writer → 체크포인트 `last_id`와 보고
  카운터를 **같은 트랜잭션**에서 갱신. 실패하면 그 건만 롤백되고 lease가 남아 있으면 같은 건부터 다시.
- **대상과 순서:** execution_records → user_contexts(fact_kind 있는 것·SELF_CHECK; SUPERSEDED·WITHDRAWN 행도 처리해 출력 0 판까지 맞춤) →
  learning_resolution_links 재구성(`supersedes_context_id`가 DIFFICULTY를 가리키는 RESOLVED 행만 — 반복 해결 경로는 과거 기록으로 복원 불가, 보고서에
  남김) → course_scope_exclusions → topic_class_progress → topic_progress(LEARNED) → course_topics(KNOWN) → material_links(사진 제외).
  `topic_learning_events`는 옮기지 않는다.
- **실시간과 다른 점:** 원천이 APPROX뿐이거나 COURSE뿐이면 이벤트를 만들지 않고 보고서에만(설계 §7.2). 실행 기록은 provenance 인용 구간·인용 목차 토픽이
  있을 때만.
- 보고서: 원본별 처리·이벤트·미대응·건너뜀 수(개인 텍스트 없음).

## 7. 테스트

단위
- `LearningEventWriterTest`: 같은 출력 → 판 그대로, claim_at·claim_seq만 바뀌어도 새 판, 정밀도 차이(나노초·소수 3자리)는 같은 값, payload 키 순서 무관,
  중복 출력 제거, output_no 안정, 출력 0 판, 같은 판 다른 내용 → 예외, origin course 변경 → 예외.
- `LearningEventValidatorTest`: 다른 사용자 과목·자료·구간·회차·이벤트 거부, 새 참조는 연결 필수·기존 참조는 연결 해제 뒤에도 허용, 없는 목차 줄·다른
  과목 교재 ref 거부, payload 중첩 참조 검증, resolves가 살아 있는 STUCK이 아니면 거부, 쪽 범위 밖 거부.
- `LearningEventLogTest`: 현재 판만, 출력 0 판, RETRACTED, SUPERSEDES 접기.
- `SourceResolverTest`: 인용 구간 우선, 인용 목차 토픽 LOGGED, 토픽 fallback APPROX, section_id=0 → MATERIAL, MISSING_SOURCE 제외.
- 매핑: supportLevel NULL → UNKNOWN, CONCEPT·stuckStep → STUCK, 회고 수정은 원천 고정, fact_kind별, 대상 없는 해결 → 없음, 진도 대체 → 옛 행 0 판.
- 직렬화 결과에 note·stuckStep·content 값이 나타나지 않음.

DB(`memo_test`, 합성 사용자·합성 자료, CI 제외 목록 + 개수 주석 갱신)
- `LearningEventsFlowDbTest`: 실행 완료·일부·회고(막힌 단계 삭제 → STUCK 없는 판), 원본 실패 시 이벤트 없음, 기억 자동 저장·확인·재진술·수정·철회,
  최신 진도 철회 뒤 옛 진도가 되살아나지 않음, 막힘 → 해결 → 해결 철회, 반복 해결로 새 막힘 닫기, 자기 점검 동시 저장 2건(최종 하나만 살아 있음),
  범위 제외·해제, CLASS, 사진 턴 성공/실패, 자료 연결·삭제(구간 제목 비워짐, 이벤트 유지, 기존 조회에 노출 없음), 과목 없는 실행 → 이벤트 없음·완료 성공,
  다른 사용자 참조 거부.
- 동시성: 같은 기억 행 확인·수정 경합, 자기 점검 경합, 역순 잠금 시나리오 없음 확인(정리안 적용과 기억 저장 동시 — 교착 없이 끝남).
- `ClassSessionDbTest`: ensure 멱등, SKIP 회차 조회, MOVED 목적지 창 밖 포함, 예외 추가·수정·삭제 → CANCELLED/SESSION_MOVED/0 판, 재전송 성공, stale 409
  (부분 커밋 없음), 끈 과목 빈 목록.
- `LearningEventBackfillDbTest`: cutover 전 행만, 체크포인트 재개, 재실행 0 추가, 실시간 origin 건너뜀, 백필 도중 원본 수정, lease 이중 실행 방지,
  APPROX만 → 미생성 + 보고.

전체 `./gradlew test`(DB 포함) 통과, 기존 테스트 무변화. UI: vitest·build·eslint.

## 8. 문서

05-database §27, api-spec(디버그 조회·수업 확인·classPromptEnabled), 설계 20번(§6.2 coordinate UNKNOWN, §6.3 판 저장 위치, §7 CLASS·현재 값 대체·해결
규칙, §5.3 구간 텍스트), 99-changelog.

## 9. 하지 않는 것(이번)

2단계 뷰·예측·`source_links`·`syllabus_weeks`, 문제지 문항, 몰아 올림 "어디까지 들었나" 화면, 교수 공개 시각 입력, 푸시 알림, 토픽 경로 변경.
수동 실행 생성의 과목 소유권 검사 누락(리뷰 #4에서 발견)은 별도 작업으로 뺐다 — 이번에는 Writer 쪽에서 대상 밖으로 건너뛴다.

## 10. 리뷰 기록

Codex 계획 리뷰 1회차: REVISE 17건(P1 11, P2 6) — 모두 반영.

| # | 지적 | 반영 |
|---|---|---|
| 1 | 판 비교가 claim 시각 변경을 놓침 | §2.1 전체 의미 필드 정규화 비교·정렬 |
| 2 | 과목 잠금만으로는 스냅샷 문제 | §2.1 잠금 읽기, §3 saveSelfChecks 조회 FOR UPDATE, §6 잠금 읽기 |
| 3 | 회고 수정이 원천을 바꾸거나 실패 | §3 원천 고정, §2.2 새/기존 참조 구분 |
| 4 | courseId 신뢰 불가·과목 없는 실행 | §2.1 대상 밖 판정, §2.2 과목·origin·topic 검증, origin course 불변 (생성 버그는 별도 작업) |
| 5 | 참조 존재·범위 검증 약함 | §2.2 목차 항목 존재·과목 관계·쪽 범위·payload 중첩·STUCK 확인 |
| 6 | 대체된 현재 값이 되살아남 | 출력 0 판으로 내림(§0, §3) |
| 7 | 해결 대상 관계 | RESOLUTION origin + `learning_resolution_links`(§3), 대상 없음은 무시 |
| 8 | CLASS를 회차 집합으로 쓰면 덮어씀 | CLASS는 항상 주차 진술(§0, §3) |
| 9 | SKIP·이동·예외 변경 경로 | §4 원래 날짜 기준 조회, 일정 예외 이벤트 |
| 10 | 백필 cutover·원자성·단일 실행 | §6 cutover 기록·대기, 한 건 트랜잭션 + 체크포인트, lease |
| 11 | 교재 ref 운영 중 유지 | §2.3 `TextbookRefService`, writer·목차 경로 연결, 합치기 없음 |
| 12 | 별칭 UNIQUE 접두 | §1 해시 UNIQUE + 전체 비교 |
| 13 | 토픽 fallback·시험 범위 계약 | §3.1 APPROX, §3.2 coordinate UNKNOWN·AMBIGUOUS |
| 14 | 수업 확인 배치 트랜잭션 | §4 전체 한 트랜잭션, 재전송·stale 규칙 |
| 15 | 실패한 턴도 STUDIED | §3 완료 트랜잭션에서 |
| 16 | 실제 잠금 순서·저장 후 재조회·ZIP | §3 표 전체 순서, 다시 읽기, createWithLink 한 곳 |
| 17 | 삭제 후 구간 제목 | §3.3 |

## 11. 재리뷰 반영 (v2.1)

Codex 계획 재리뷰: REVISE 9건(P1 5, P2 4). 재리뷰 상한이라 더 보내지 않고 판단해 반영했다. 반영 8, 일부 기각 1.

### 11.1 기존 원본을 실시간 경로가 처음 만질 때 (#1, #3 일부)

cutover 이전에 만든 원본(`created_at < cutover_at`)에 origin이 아직 없으면, 실시간 경로가 그 원본을 처음 만질 때 **백필 규칙**(`SourceResolver.Mode.HISTORICAL`
— 인용 구간·인용 목차 토픽·기억의 직접 가리킴만, APPROX·COURSE만이면 만들지 않음)으로 원본 잠금 안에서 첫 판을 만든다. 만들 수 없으면 origin을
만들지 않고(빈 origin이 백필을 막지 않게) 보고 카운터만 올린다. 그래서 같은 원본은 실시간이 먼저 만지든 백필이 먼저 하든 같은 결과다.
- 회고 수정(`updateReflection`): origin 없음 + 기존 원본 → HISTORICAL로 원천 확정 후 수행 정보 반영. cutover 이후 원본에 origin이 없으면(대상 밖이었던 것)
  그대로 대상 밖.
- 테스트: 기존 기록 → 백필 전 회고 수정 → 백필(건너뜀, 결과 같음), 원천 미확정 기존 기록 → 회고 수정 → origin 없음.

### 11.2 해결 관계 (#2, #3)

- **해결 때문에 대체된 막힘은 STUCK을 유지한다.** DIFFICULTY 행의 출력 규칙: 상태가 ACTIVE·STALE이거나, SUPERSEDED이면서
  `learning_resolution_links`에 있으면 STUCK. 수정(edit)·철회·옛 후보로 내려간 경우만 출력 0.
- **`ResolutionRecalculator.forResolver(contextId)`** — 해결자 행에 연결된 모든 RESOLUTION origin을 다시 계산한다. 해결자가 살아 있고(ACTIVE·STALE)
  fact_kind=RESOLVED면 RESOLVED 출력, 아니면 출력 0. `edit`(새 행이 RESOLVED일 때만 링크를 새 행으로 옮김, 아니면 링크 제거 → 0), `withdraw`, `confirm`,
  `restated`·`promoteToStated`·`touchSaid`(helped·evidence·claim 변경), 옛 후보 적용(SUPERSEDE·ARCHIVE·MARK_STALE)에서 호출한다.
- 실시간 해결의 대상 막힘에 origin이 없으면(cutover 이전 막힘) 11.1 규칙으로 막힘 origin을 먼저 만들고, STUCK이 생기지 않으면 RESOLUTION은 출력 0.
- 백필 순서: `learning_resolution_links` 재구성 → user_contexts(위 출력 규칙) → RESOLUTION origin. 테스트: 백필 막힘 → 해결 → 해결 철회, 해결자 종류를
  RESOLVED에서 다른 것으로 수정.

### 11.3 가변 관계 검증 (#4 — 일부 기각)

- **일정:** 회차 생성(`ensure`)과 예외 추가·수정·삭제가 모두 `routines` 행을 `FOR UPDATE`로 먼저 잠근다(루틴 → 회차 → origin). 반영.
- **자료 연결:** 기각. 새 참조 검증과 연결 해제가 경합해 "해제 직후 커밋된 이벤트"가 생겨도, 설계상 이벤트는 해제 뒤에도 남는다(연결 해제 직전에 쓴 이벤트와
  같은 상태). 여기에 연결 행 공유 잠금을 더하면 정리안 적용(연결 → 과목)과 기억 저장(과목 → 연결)이 서로 반대 순서가 되어 교착이 생긴다. 잠그지 않는다.
- **목차:** 리비전 원문·해시는 불변이라 경합 없음.

### 11.4 일정 예외 날짜 변경·삭제 (#5)

SCHEDULE_EXCEPTION origin은 **지금 그 예외가 붙은 회차** 하나만 출력한다. 수정 시 예외의 새 날짜로 다시 계산 — 새 날짜의 회차가 있으면 그 회차에 출력,
없으면 출력 0(옛 날짜의 출력은 판 교체로 내려간다). 삭제는 DELETE **전에** 같은 트랜잭션에서 출력 0 판을 쓴다(출력 0이면 원본 존재 검증을 하지 않는다).
테스트: A 날짜 SKIP → B 날짜로 수정(B 회차 없음) → A 회차의 CANCELLED가 살아 있지 않음.

### 11.5 목차 항목 검증 (#6)

새 참조: 그 해시의 리비전을 구조화한 항목 목록(`TocSnapshot.entryByKey`, 업로드 목차는 추출 항목 순번)에 그 열쇠가 있어야 한다. 기존 참조(이 origin의 현재 판에
이미 있는 것): 사용자 ref 소유만 본다 — 교재를 바꾼 뒤에도 과거 목차 이벤트의 정정이 실패하지 않게.

### 11.6 교재 별칭 (#7)

- 시드: 과목마다 현재 교재에서 `BookKey.of`의 ISBN 키와 제목 키(제목+판)를 **둘 다** 만들어 같은 ref의 별칭으로 넣고, 사용자 안에서 별칭이 겹치는 ref는
  합친다(union-find, 이벤트가 생기기 전 A 마이그레이션 러너에서 1회, 체크포인트 `learning_event_meta.textbook_refs_seeded`). 그다음 토픽의
  `source_textbook_key`를 그 별칭의 ref에 붙이고, 없으면 새 ref.
- 운영 중: `CourseTextbookWriter.write`가 교재를 쓸 때마다 그 책의 두 키를 같은 ref로 보장한다. 새 키가 **이미 다른 ref**에 있으면 합치지 않고 WARN 로그 +
  `learning_event_meta`의 충돌 카운터를 올리며, 그 과목의 새 이벤트는 새 키가 가리키는 ref를 쓴다. 조용히 무시하지 않는다. 테스트: 두 과목 중 하나만 ISBN →
  시드 후 같은 ref, 운영 중 충돌 → 카운터.

### 11.7 cutover·lease (#9)

- cutover는 B를 배포하고 **옛 버전 인스턴스가 모두 내려간 뒤**에 유효하다. 이 앱은 단일 인스턴스로 운영하므로, 백필 설정을 켜는 것은 운영자(사용자)가 B 재시작
  이후임을 확인하고 하는 일로 문서에 적는다. 10분 대기는 그 위의 안전 여유다.
- lease: 한 건 처리마다 체크포인트·lease 연장을 `WHERE lease_owner=?` 조건 UPDATE로 하고, 0행이면(소유권을 잃음) 그 트랜잭션을 롤백하고 작업을 멈춘다.

### 11.8 SKIP 회차 pending (#8)

시간표 SKIP은 이미 휴강으로 알고 있으니 pending에 넣지 않는다(§4 수정). 일정 예외의 CANCELLED(actor CLASS, origin SCHEDULE_EXCEPTION)와 사용자 확인
(origin CLASS_INPUT)은 origin으로 구분된다.

| # | 지적 | 판단 |
|---|---|---|
| 1 | 백필 전 기록의 첫 회고 수정 | 반영 §11.1 |
| 2 | 해결자 변경 경로 | 반영 §11.2 |
| 3 | 해결 백필이 STUCK을 먼저 제거 | 반영 §11.2 |
| 4 | 가변 관계 잠금 없는 검증 | 일정 반영, 자료 연결 기각(이벤트는 해제 뒤에도 유지되는 설계 + 교착 위험), 목차 해당 없음 §11.3 |
| 5 | 예외 날짜 변경·삭제 | 반영 §11.4 |
| 6 | 목차 항목 존재 | 반영 §11.5 |
| 7 | 별칭 충돌 | 반영 §11.6 |
| 8 | SKIP pending 모순 | 반영 §11.8 |
| 9 | cutover·lease 배타성 | 반영 §11.7 |
