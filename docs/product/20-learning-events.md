# 20. 이벤트 기반 학습 진도 (설계, 2026-10-06)

상태: **설계안 v2.1** — 구현 전. 사용자와 대화로 합의한 내용(2026-10-06)에 Codex 설계 리뷰(1회차 REVISE 14건, 재리뷰 APPROVED + P2 4건 — 모두 반영, §17)를
더했다. 결정 사항은 §16.

목표 흐름:

```text
수업자료·문제지·계획서·교재 목차·사진·상담 발화(원천 — 식별자는 바꾸지 않는다)
→ 일어난 일은 원천을 가리키는 이벤트로 쌓는다(수업 회차에서 다룬 것·공부·풀이·막힘·해결·결석·범위 공지 …)
→ 수업 진도·내 학습 진도·시험 범위·다음 수업 예측은 원천 + 이벤트에서 계산한다(요청 시 계산)
→ 계획·상담·복습은 이 계산된 뷰를 받는다
```

## 1. 왜

지금 학습 구조의 중심은 사람이 다듬는 고정 트리(`course_topics`)다. 자료·목차·계획서가 바뀔 때마다 정리안(`project_tidy_*`)으로 트리를
고치고, 병합·분할·승계·판 충돌·재구성 비용이 따라온다. 진도(`topic_progress`), 실제 수업 정정(`topic_class_progress`), 기억
(`user_contexts`), 사진 단원, 실행 근거가 모두 `topic_id`에 붙어 있어 트리가 흔들리면 기록도 흔들린다.

또 "진도"가 하나로 섞여 있다. 수업이 어디까지 나갔나, 내가 어디까지 이해했나, 시험이 어디까지인가는 다른 사실이다(18번이 기억에서
이미 나눴다). 결석·선행·시험 직전 몰아치기는 셋을 나눠야 제대로 판단된다.

## 2. 목표와 하지 않는 것

목표
- 고정 토픽 트리 없이 진도를 판단한다(트리 제거는 마지막 단계이고 따로 정한다, §14).
- 수업 진도 / 내 학습 진도 / 시험 범위를 따로 계산한다.
- 교재 없음, 교재 순서대로, 중요한 것 먼저, 계획서와 다르게, 문제지만 배포, 판서만, 몰아서 업로드 — 이런 수업 방식에 **설정 없이** 대응한다.

하지 않는 것
- 사용자 한 명 데이터로 학습하는 모델(DKT, 학습형 FSRS).
- LLM이 판정한 선행 관계로 추천하기(1판 제외).
- 프로젝트형 과목의 "결과물 단계" 뷰(§16-4).
- 교재·문제지 본문 옮겨 적기. 위치(절·쪽·문항 번호)만 가리킨다.
- 과거 기록 전체를 억지로 원천에 옮기기. 확실한 대응만 옮긴다(§7).

## 3. 원칙

1. **수업자료가 1순위 사실이다.** 강의계획서는 예정, 교재 목차는 참고 좌표다. 무게는 데이터로 정한다(§10.2).
2. **기록은 원천을 가리킨다.** 개념·토픽이 아니라 자료 구간·문항·교재 항목·쪽·계획서 주차·수업 회차.
3. **원천의 식별자는 불변, 본문은 지울 수 있다.** 식별자와 최소 메타데이터(종류·판·과목·순서)는 남기고, 본문·원본 파일·임베딩은 기존 삭제
   정책(자료 삭제, 사진 원본 30일)을 그대로 따른다(§5.3).
4. **이벤트는 의미를 고정한다.** 검증을 통과한 의미(무엇을, 얼마나, 어떤 근거로)는 이벤트 안에 구조화해 남긴다. 나중에 원본 행이 바뀌어도
   과거 재생 결과가 같아야 한다. 발화 원문은 복사하지 않는다.
5. **구조와 진도는 계산 결과다.** 1~2단계는 요청 시 계산한다. 캐시는 필요해질 때 입력 판과 함께 둔다(§10.6).
6. **좌표 구조(교재 목차, 계획서 주차)는 읽기 전용이다.**
7. **AI 판단은 확신도가 붙은 제안이다.** 사용자의 말과 정정이 가장 강한 증거이고, 그것도 이벤트다. 자료 원문을 본 모델의 출력은 사용자 말로
   승격되지 않는다(18번 경계 유지).
8. **추정은 숨기지 않고, 추정을 정답으로 학습하지 않는다.** 근사로 계산한 값은 "추정"으로 보이고, 예측의 적중 판정에는 쓰지 않는다.
9. **활동 ≠ 이해.** 완료는 활동 수행이지 이해 확정이 아니다(16번 원칙). 이해의 근거는 풀이 결과·독립 수행·사용자 말이다.

## 4. 층

```text
L1 원천          자료 구간 · 문제지 문항 · 계획서 주차 · 교재 항목·쪽 · 사진 쪽 · 상담 발화 · 수업 회차
L1' 원천 대응    구간↔교재 항목, 구간↔계획서 주차, 문항↔구간·교재 항목, 수정본 구간↔이전 판 구간 (source_links)
L2 이벤트 로그   쌓기만 함 — learning_events
L3 개념 그래프   선택 · 4단계
L4 계산된 뷰     수업 진도 · 다음 수업 예측 · 내 학습 진도 · 시험 범위 · 학습 지도
L5 판단          계획 · 상담 · 복습 · 다음 할 일
```

## 5. 원천 (L1)

### 5.1 식별자

| 원천 | 지금 있는 것 | 불변 식별자 |
|---|---|---|
| 자료 구간 | `material_sections`(15번) | (material_id, 파일 해시, 분석 판, section_id). `section_id=0`은 자료 전체 — 범위 학습 근거로는 쓰지 않는다 |
| 자료 역할 | `material_links.material_type`: SYLLABUS · TEXTBOOK_TOC · PROFESSOR_SLIDE · OTHER | 넓힌다: + PROBLEM_SET · LAB · NOTICE |
| 문제지 문항 | 없음 — 3단계 `problem_items` | item_id (자료·파일 해시·순번) |
| 계획서 주차 | 계획서 분석의 SCHEDULE 구간·주차 표현 | `syllabus_weeks`(새 표): (계획서 material_id, 파일 해시, week_no) → week_ref_id. 계획서가 바뀌면 새 판의 주차는 새 id |
| 교재 | `courses.textbook_*` + `BookKey`(제목→ISBN으로 바뀔 수 있음) | `textbook_refs`(새 표): 한 번 정한 book_ref_id + BookKey alias 행들. 웹 리비전·목차 열쇠는 book_ref_id 아래 |
| 교재 목차 항목 | 목차 열쇠(17번 §11: 원문 해시 + 원문 줄) | (book_ref_id, key hash, line). `MISSING_SOURCE` 업로드 목차는 원천으로 쓰지 않는다 |
| 교재 쪽 | 목차 항목의 `page`(시작 쪽) | (book_ref_id, page) |
| 사진 쪽 | 상담 사진(19번) → 자료 구간(IMAGE_PAGE) | 자료 구간과 같음 |
| 상담 발화 | `ai_messages` | message_id |
| 수업 회차 | 시간표(`routines.course_id`)를 매번 펼친 결과 — 시간표 수정에 따라 달라짐 | `class_sessions`(새 표): (routine_id, 원래 발생일) → session_id + 당시 일정 스냅샷(시작·끝 시각). 보강·이동은 같은 session의 시각 변경 이벤트, 휴강은 CANCELLED |

- 수업 회차 행은 **처음 필요할 때**(수업 후 입력을 띄울 때, 이벤트가 회차를 가리킬 때) 만든다. 미리 학기 전체를 만들지 않는다.
- 자료 구간의 분석 판: 이벤트는 기록 당시 판의 구간을 가리킨다. 재분석으로 구간이 바뀌면 이전 판 구간과 새 판 구간의 대응을 `source_links`에
  남긴다(§5.2).

### 5.2 원천 대응 (`source_links`) — 대응과 사실 승계는 다르다

```text
source_links
  from_kind/ref, to_kind/ref
  relation    SAME(내용 동일) · OVERLAPS(일부 겹침) · COVERS(포함) · PRACTICES(문항이 다룸) · PLACED_IN(구간이 주차에 놓임)
  evidence    STATED · INPUT · RULE(파일명·머리 표시·동일 해시) · INFERRED(유사도 + 모델) · APPROX
  confidence, created_by_run, retracted_at
```

- **2단계부터 쓴다.** 예측·시험 범위 해석에 필요한 최소 대응(구간↔계획서 주차, 구간↔교재 항목)을 2단계에 만든다. 이미 있는
  `material_week_assignments`(사용자가 확인한 자료 주차)는 PLACED_IN(INPUT)으로 읽는다. 대응이 없는 원천에는 그 좌표로 예측하지 않는다.
- **사실 승계는 SAME만 자동이다.** 수정본·재분석에서 내용이 같은 구간(SAME)은 이전 판 이벤트를 새 판에서도 센다. OVERLAPS·분할·병합·추가
  내용은 승계하지 않고 "이전 판에서 공부함 — 새 내용 있음"으로 보인다. 사용자가 확인하면 STATED 이벤트.
- **과목 귀속은 옮기지 않는다.** 자료가 여러 과목에 연결될 수 있으므로, 수업·시험 이벤트는 기록 당시 과목에 남는다. 자료 연결을 끊어도
  이벤트는 그대로(기존 연결 해제 계약과 같음). 잘못 귀속된 것은 사용자의 명시적 정정(RETRACTED + 새 이벤트)으로만 고친다.

### 5.3 삭제와의 관계

- 자료 삭제: 기존 정책대로 추출 단위를 지우고 구간 본문을 비운다. 구간 식별자·제목 없는 메타데이터와 이벤트는 남고, 화면에는 "삭제된 자료의
  구간"으로 보인다. 그 구간의 임베딩·`source_links`의 INFERRED 행은 지운다.
- 사진 원본 30일 삭제: 사진 구간의 읽은 글은 19번 정책대로 자료를 지울 때까지 남는다. 이벤트는 영향 없음.
- 사용자 계정 삭제: 이벤트·대응·회차 전부 삭제.

### 5.4 교재 쪽 범위

목차 항목의 끝 쪽 = 같은 깊이 이하의 다음 항목의 시작 쪽 − 1. 쪽이 단조 증가하지 않거나 다음 항목에 쪽이 없으면 끝 쪽은 비운다. 쪽이 없는
항목은 절 이름만 가리킨다. 읽을 때 계산하고 저장하지 않는다.

## 6. 이벤트 로그 (L2)

### 6.1 표

```text
learning_events
  event_id
  user_id, course_id                         -- 서버가 정한다(요청 값을 믿지 않음)
  actor          ME | CLASS | SYSTEM
  verb           수업: RELEASED · COVERED_IN_CLASS · CANCELLED · SESSION_MOVED · SCOPE_ANNOUNCED · SCOPE_EXCLUDED · PLAN_CHANGED
                 나:   STUDIED · ATTEMPTED · STUCK · RESOLVED · ABSENT · SELF_ASSESSED · SUBMITTED
                 공통: STATED · RETRACTED · SUPERSEDES
  object_kind    SECTION · ITEM · TOC_ENTRY · PAGE · WEEK · SESSION · MATERIAL · COURSE
  object_ref     원천 식별자(§5.1)
  object_from/to 범위일 때(쪽 범위, 주차 범위, 목차 항목 범위)
  payload        JSON(버전 있음) — verb별 구조화 의미(§6.2)
  evidence       STATED · INPUT · LOGGED · RULE · APPROX · INFERRED
  confidence     0~1
  origin_kind, origin_id, origin_revision, output_no   -- 모두 NOT NULL(§6.3)
  claim_at       주장 시각(발화 시각·입력 시각) / claim_seq 같은 초 안의 순번(메시지 번호)
  occurred_at    일어난 때 — 모르면 NULL
  recorded_at    기록한 때
  UNIQUE (user_id, origin_kind, origin_id, origin_revision, output_no)
```

### 6.2 verb별 payload

| verb | object | payload |
|---|---|---|
| COVERED_IN_CLASS | SESSION | `{sources:[{kind,ref,from,to}], unknownContent:bool}` — 회차별로 **다룬 원천 집합**. 누적 지점이 아니다 |
| CANCELLED / SESSION_MOVED | SESSION | `{movedTo?}` |
| RELEASED | MATERIAL | `{uploadedAt, professorReleasedAt?, batchId?}` — 업로드 시각과 교수 공개 시각을 나눈다. 공개 시각을 모르면 비움 |
| SCOPE_ANNOUNCED | COURSE | `{examKey, coordinate:"TOC"|"WEEK_PLANNED"|"WEEK_ACTUAL"|"MATERIAL", sourceVersion, from, to, resolved:[원천]?, state:"RESOLVED"|"AMBIGUOUS"}` |
| SCOPE_EXCLUDED | COURSE | `{examKey, sources:[…], restore:bool}` — 기존 `course_scope_exclusions` 대응 |
| STUDIED | SECTION·PAGE·ITEM | `{activity:"READ"|"PLAN_ITEM"|"PHOTO_TURN", minutes?, supportLevel?}` — 활동 기록. 이해 근거 아님 |
| ATTEMPTED | ITEM·SECTION | `{outcome:"CORRECT"|"PARTIAL"|"WRONG"|"DONE_UNGRADED", supportLevel:"SOLO"|"GUIDED", stuckStep?, completionPercent?}` |
| STUCK | 원천 | `{stuckStep?}` |
| RESOLVED | 원천 | `{resolves: event_id, helped:bool}` — 닫을 STUCK을 이벤트 id로 가리킨다 |
| SELF_ASSESSED | 원천 | `{level: 1~4}` |
| STATED | COURSE·원천 | `{claim:"TEXTBOOK_NOT_USED"|"TEXTBOOK_USED"|"SYLLABUS_NOT_FOLLOWED"|"LINK_FIX"|…, value}` — 정해진 claim 목록만 |
| RETRACTED | — | `{target: event_id}` |
| SUPERSEDES | — | `{target: event_id, by: event_id}` — 추정 → 확인, 진도 현재 값 대체 |

### 6.3 식별과 멱등

- origin은 **원본 행 + 그 행의 판**이다. 같은 원본이 바뀌면(기억 확인, 범위 정정) `origin_revision`이 올라가 새 이벤트가 되고, 이전 이벤트는
  SUPERSEDES로 대체된다. 같은 판을 다시 보내면(재시도) UNIQUE로 무시하되, payload가 다르면 충돌로 처리(로그 + 실패).
- **백필과 실시간이 같은 origin 체계를 쓴다.** 백필도 `origin_kind=EXECUTION_RECORD, origin_id=record_id`처럼 원본 행을 가리키므로, 백필 뒤
  실시간 기록이나 백필 재실행이 같은 사실을 두 번 만들지 않는다.
- 한 원본이 여러 원천을 가리키면 `output_no`로 나눈다. 번호는 원천 식별자를 정렬한 순서로 매겨 재실행해도 같다.
- **판 저장:** 원본 표마다 열을 더하지 않고 `learning_event_origins`(origin별 현재 판) 한 표에 둔다(계획 단계에서 정함). 원본을 바꾸는 쓰기는 기존 잠금(과목 → 원본 행) 뒤에 origin 행을 잠그고 같은 트랜잭션에서 이벤트를 쓴다. 백필도 같은 순서로 잠근다.
- **판 단위 대체:** 같은 origin에서는 **가장 높은 판의 출력만 살아 있다.** 새 판의 출력 수가 줄면(막힌 단계를 지움 → STUCK 없음) 이전 판의
  STUCK은 판 규칙으로 자동으로 빠진다. SUPERSEDES 이벤트는 origin이 다른 대체(추정 기억 → 사용자 확인 입력)에만 쓴다.
- 원본이 없는 입력(수업 후 입력, 주간 확인)은 요청마다 클라이언트 요청 키를 origin_id로 쓴다(`CLASS_PROMPT` + 요청 키).

### 6.4 적용 순서 (결정적)

뷰는 이벤트를 이렇게 접는다.
1. RETRACTED 대상은 버린다(철회의 철회는 없음 — 다시 원하면 새 이벤트).
2. origin마다 가장 높은 판의 출력만 남기고(§6.3), SUPERSEDES 사슬의 마지막만 남긴다.
3. 같은 대상의 "현재 값"(수업 회차의 다룬 집합, 시험 범위, 진술)은 (claim_at, claim_seq) 순서로 마지막이 이긴다. 같은 시각이면 INPUT·STATED가
   APPROX·INFERRED를, 사용자 수정이 자동 저장을 이긴다(18번 규칙과 같음). 추정은 확인된 값을 덮지 않는다.
4. 누적 사실(STUDIED·ATTEMPTED·STUCK·RESOLVED)은 모두 센다. RESOLVED는 payload의 resolves가 가리키는 STUCK만 닫는다.

### 6.5 참조 검증 (같은 쓰기 트랜잭션)

종류별 검증기 하나를 거쳐서만 쓴다(`LearningEventWriter`).
- 원천이 그 사용자·과목에 속하는지(자료 연결, 회차의 루틴 소유, 교재 ref의 과목), 범위가 원천 경계 안인지(쪽·주차·구간 순번), 판이 존재하는지.
- 웹 교재 원천은 `SHARED` / `USER:{id}` 접근 범위를 지금 규칙대로 확인.
- RETRACTED·SUPERSEDES·RESOLVED의 대상 이벤트가 같은 사용자·과목인지.
- `user_id`·`course_id`·origin·evidence는 서버가 정한다. 화면 입력은 INPUT, 상담은 18번 검증을 통과한 사용자 말만 STATED, 자료 원문을 실은 턴의
  모델 출력은 INFERRED 이하.

## 7. 쓰기 경로 대응

새 이벤트는 기존 원본을 쓰는 **모든 경로**에서 같은 트랜잭션으로 `LearningEventWriter`를 부른다. 원본 표는 그대로 두고(호환), 이벤트가 그
옆에 쌓인다.

| 원본 · 경로 | 이벤트 |
|---|---|
| `execution_records` 완료(`complete`) | 연결 원천(§7.1)에 ATTEMPTED(DONE_UNGRADED, supportLevel·stuckStep 보존). 도움 수준을 답하지 않았으면(NULL) `supportLevel:"UNKNOWN"` — SOLO로 보지 않는다. 막힌 단계가 있으면 STUCK |
| `execution_records` 일부 수행(`recordPartial`) | ATTEMPTED(completionPercent) |
| 실행 재열기(REOPENED) | 그 완료 이벤트를 RETRACTED하지 **않는다** — 활동은 일어났다. 재완료는 새 origin(새 record) |
| 실행 회고 수정 | 그 record의 다음 판(§6.3 판 단위 대체) |
| 토픽 진도 수동 변경(`TopicService`) | 상태별: 이해 평가가 아닌 것(`IN_PROGRESS`·`DEFER`·표식 해제 등)은 이벤트 없음. 사용자가 "다 했다/이해했다"로 바꾼 것만, 토픽이 원천에 확실히 대응될 때 SELF_ASSESSED. 상태별 매핑표는 1단계 구현 계획에서 열거값 전부로 확정 |
| 사용자 표식(`updateUserMark`) | 표식 종류별로 같은 원칙 — 이해 평가인 표식만 SELF_ASSESSED, 표식 해제는 그 SELF_ASSESSED를 RETRACTED |
| 자기 점검(`saveSelfChecks`, fact_kind 없이 저장) | SELF_ASSESSED(level). 같은 대상의 새 점검은 새 판, 해제는 RETRACTED |
| `user_contexts` 저장·확인·재진술·수정·철회(18번) | PROGRESS → COVERED_IN_CLASS(STATED, 회차를 정할 수 있을 때) 또는 STATED(COURSE 진도 주장) / EXAM_SCOPE → SCOPE_ANNOUNCED / DIFFICULTY → STUCK / RESOLVED → RESOLVED. 확인·재진술·수정은 새 판(SUPERSEDES), 철회는 RETRACTED |
| `course_scope_exclusions` 추가·해제 | SCOPE_EXCLUDED(restore) |
| 상담 사진 같이 공부한 턴(19번) | STUDIED(PHOTO_TURN). 사진 단원 정정 → SUPERSEDES. 업로드만으로는 없음 |
| 자료 업로드·과목 연결 | RELEASED(MATERIAL) |
| 정리안 CLASS 작업(`topic_class_progress`) | 순서·주차만 있고 수업 날짜가 없다. 회차가 확인되면(그 주차에 그 과목 회차가 하나) COVERED_IN_CLASS(INPUT, SESSION), 아니면 STATED(COURSE, `claim:"COVERED_IN_WEEK"`, 주차·순서) — 회차 미상이라 예측 적중 관측에서 제외. 둘 다 토픽이 원천에 확실히 대응될 때만 |
| 수업 후 입력 · 주간 확인 · 몰아 올림 확인(§8, §9) | COVERED_IN_CLASS · CANCELLED · SESSION_MOVED · ABSENT (INPUT) |

### 7.1 실행 기록의 원천

계획 항목이 실제로 인용한 구간(`StartSourceResolver`가 쓰는 계획 근거·provenance)을 먼저 쓴다. 없으면 토픽의 목차 열쇠, 그다음 토픽의 자료 연결
— 이 둘은 "내용 대응"이지 공부한 범위의 증거가 아니므로 `evidence=APPROX`, 낮은 확신도로 두고 등급 계산에서 상한을 둔다(§10.3).

### 7.2 백필

- 대상: `execution_records`, `user_contexts`(fact_kind 있는 것), `course_scope_exclusions`, `topic_class_progress`.
  `topic_learning_events`의 `EXECUTION_COMPLETED`는 `execution_records`와 같은 사실이므로 옮기지 않는다(중복 방지). `STATUS_CHANGED`는 위 진도
  수동 변경 규칙으로.
- 원천은 §7.1 순서. 확실한 대응이 없으면 **옮기지 않고** 보고서에 남긴다(COURSE에 억지로 붙이지 않음).
- 실시간 이중 기록을 먼저 켜고, 그 시점(`cutover_at`) 이전 원본만 백필한다. 원본별 체크포인트(표·마지막 id)로 끊겨도 이어서 돈다. 백필 중
  원본이 수정되면 실시간 경로가 새 판을 쓰고, 백필은 UNIQUE로 같은 판을 건너뛴다.
- 보고서: 원본별 기대 이벤트 수 / 실제 / 원천 미대응 / 중복 제거.

## 8. 수업에서 다룬 범위 (COVERED_IN_CLASS)

다룬 범위는 **회차별 원천 집합**이다. "어디까지"라는 누적 지점은 순서가 검증된 과목(교재·자료 순서와 실제가 계속 일치)에서만 입력 편의로
쓰고, 저장은 항상 회차별 집합으로 펼친다. 중요한 것 먼저·건너뛰기·재방문이 그대로 남는다.

| 근거 | evidence | 무게 | 예측 적중 판정에 사용 |
|---|---|---|---|
| 사용자 말("오늘 4장 했어" — 18번 검증) | STATED | 덮어씀 | 예(회차를 정할 수 있을 때) |
| 수업 후 입력(§8.1) · 주간 확인 | INPUT | 높음 | 예 |
| 파일명·자료 안 표시 | RULE | 낮음 | 아니요 |
| 교수 공개 시각 + 시간표 | APPROX | 낮음 | 아니요 |

입력이 없는 회차는 근사로 보이고 "추정"으로 표시된다. 근사는 이벤트로 저장하지 않고 뷰에서 계산한다(저장하면 나중에 철회할 일이 생긴다).

### 8.1 수업 후 입력

```text
네트워크프로그래밍 · 오늘 수업 어디까지 했어요?
  ✓ 3.AWS_구성하기 · EC2와 SSH 서버 운영 (12~25쪽)   ← 기본값
    3.AWS_구성하기 · Route 53 도메인 (26~34쪽)
    아직 자료가 안 올라왔어요
    휴강 / 다른 내용
  [맞아요] [고치기] [나중에]
```

- 기본값: 지난 회차의 다음 구간 → 그 사이 새로 올라온 자료 → 계획서 그 주차의 대응 구간 → (교재 일치 수치가 높고 대응이 있으면) 다음 교재 항목.
- 목록은 올라온 수업자료의 구간. 자료가 없으면 계획서 주차·교재 항목.
- "아직 자료가 안 올라왔어요" → `unknownContent=true`. 자료가 올라오면 "지난 화요일 수업이 이 자료였나요?"로 잇는다(새 판 COVERED_IN_CLASS가 SUPERSEDES).
- "휴강" → CANCELLED. "나중에"·무응답 → 주간 확인에 모은다.
- 알림은 과목별로 끌 수 있다(§16-1).

## 9. 몰아서 업로드

1. **업로드 전에 묶음을 정한다.** 다중 선택·ZIP은 이미 묶음(`material_analysis_batches`, `material_zip_imports`)이라 처음부터 묶음으로 기록한다.
   사후 감지(짧은 시간에 여러 개)는 묶음 표시만 붙인다 — 근사는 저장하지 않으므로(§8) 철회할 것이 없다.
2. **시각 근거:** 업로드 시각은 교수 공개 시각이 아니다. 교수 공개 시각은 자료 안 표시·사용자 입력이 있을 때만 쓰고, 없으면 비운다. 묶음의 자료는
   시각 근사를 쓰지 않는다.
3. **과목 나누기:** 기존 과목 연결 제안(`MaterialLinkProposalService`). 확신 높은 것은 미리 체크, 애매한 것만 묻는다.
4. **순서:** 파일명 번호·주차 → 자료 안 표시 → 계획서 주차·교재 장 대응 → 업로드 순서. 중복·수정본은 해시·SAME 대응으로 하나로 센다.
5. **어디까지 들었나:** 과목별 기본값을 한 화면에 보이고 [모두 맞아요] 한 번. 확인하면 지난 회차들에 COVERED_IN_CLASS(INPUT)를 회차별 집합으로
   펼쳐 쓴다(회차를 모르는 부분은 `session=미정` 대신 과목 단위 STATED "여기까지 다룸"으로 남기고, 뷰가 회차 없이 센다). 답이 없으면 근사.
6. **학기 초 전부 공개:** 공개 ≠ 다룸. 미래 범위가 보이니 예측에는 좋다.
7. **분석:** 기존 분석 큐에서 보고 있는 과목부터, 알림은 요약 하나, 하루 상한·예상치를 업로드 전에 보인다.

## 10. 계산된 뷰 (L4)

### 10.1 수업 진도

회차별 COVERED_IN_CLASS·CANCELLED와 자료 순서로 "수업은 지금 여기". 확인되지 않은 회차는 근사("추정").

### 10.2 원천별 일치 수치와 다음 수업 예측

- **관측:** 연속한 두 회차가 모두 INPUT·STATED로 확인됐고, 두 회차의 원천이 그 좌표(계획서 주차·교재 항목)에 `source_links`로 대응될 때만 하나의
  관측이 된다. 근사·미확인·대응 없음은 관측에서 뺀다.
- **적중:** 그 좌표가 예측한 다음 위치(허용 폭 상수)와 실제가 맞으면 1. 최근 관측에 큰 가중치, 작은 사전값:
  `a = (α·p₀ + Σ wₖ·hitₖ) / (α + Σ wₖ)`
- **시작점:** 계획서가 있으면 계획서 사전값을 높게, 교재만 있으면 교재, 둘 다 없으면 예측하지 않는다.
- **사용자 말:** STATED `TEXTBOOK_NOT_USED`는 교재 수치를 0으로 고정. 이후 관측이 계속 교재와 맞으면 다시 제안한다.
- **예측:** 후보 = 대응이 있는 좌표가 말하는 다음 위치 + "다음 자료(파일명 순서)". 점수 = 일치 수치. 수업 전에 예측을 `class_predictions`에
  저장해 두고, 실제 입력이 오면 적중을 기록한다(적중률 보고용).
- 라벨("교재 순서대로 진행 중")은 표시용.

### 10.3 내 학습 진도

원천 단위로 증거 가중 합 × 감쇠. **손으로 정한 상수**이고 BKT라고 부르지 않는다.

- 상태: **근거 없음**(이벤트 0) / 익힘 / 보통 / 약함 / 복습 필요.
- 이해 근거: ATTEMPTED(CORRECT, SOLO) > ATTEMPTED(CORRECT, GUIDED)·STATED > ATTEMPTED(DONE_UNGRADED) > STUDIED > INFERRED.
  STUCK은 RESOLVED 전까지 음의 기여.
- 상한: STUDIED·DONE_UNGRADED·APPROX 원천만으로는 "보통"까지. "익힘"은 정답 풀이·독립 수행·사용자 말 중 하나가 있어야 한다. 같은 시도(origin)는
  한 번만 센다. 같은 원천의 STUDIED 누적에는 상한.
- 감쇠: 회상 = 2^(−경과/반감기). 반감기는 **간격을 둔 성공(정답·독립 수행)**에서만 늘어난다. 조회 시각 기준으로 계산한다.
- 상수는 한 클래스에 모은다. 근거 이벤트는 펼쳐서 본다.

### 10.4 시험 범위

- SCOPE_ANNOUNCED는 좌표 체계(`TOC` / `WEEK_PLANNED` / `WEEK_ACTUAL` / `MATERIAL`)와 원천 판을 함께 저장한다.
- 원천 집합은 `source_links`의 실제 대응으로 푼다. 일치 수치로 범위를 변환하지 않는다. `WEEK_ACTUAL`은 회차별 다룬 집합으로, `WEEK_PLANNED`는
  계획서 주차의 대응 구간으로.
- 대응이 모자라 모호하면 `AMBIGUOUS`로 남기고 "범위 확인 필요"를 보인다.
- 공지 당시 해석(`resolved`)과 현재 해석(대응이 늘어난 뒤)을 구분해 보인다.
- 포함·제외 우선순위: SCOPE_EXCLUDED(restore=false)가 포함을 이긴다, restore=true가 제외를 푼다, 같은 시험 안에서 claim 순서.

### 10.5 학습 지도

수업 순서대로 원천(자료 → 구간)을 보이고, 각각에 수업 진도·내 상태·시험 범위 표시를 붙인다. 교재 일치 수치가 높은 과목은 교재 목차 모양으로도
볼 수 있다.

### 10.6 계산 시점

1~2단계는 **요청 시 계산**(과목 하나의 이벤트 수백~수천 건 규모). 느려지면 그때 과목별 캐시를 두고, 캐시에는 입력 판(이벤트 최대 id, 대응
run, 시간표·교재 판), 계산 규칙 판, 계산 기준 시각을 같이 저장해 저장 직전에 입력 판을 대조한다. 감쇠는 항상 조회 시 계산.

## 11. 판단 (L5)

- **따라잡기:** (수업 진도 ∪ 시험 범위 − 제외) 중 내 상태가 약함·근거 없음인 원천.
- **복습:** 회상이 기준 아래로 떨어진 원천.
- **다음 수업 대비:** §10.2 예측 범위.
- 선행 관계 추천은 1판에서 넣지 않는다.

## 12. 교재 없는 과목

교재가 없는 과목이 오히려 기본 경우다. 교재 좌표만 빠지고 나머지는 그대로다.

| | 교재 있음 | 교재 없음 |
|---|---|---|
| 진도 단위 | 자료 구간 (+ 교재 좌표) | 자료 구간 |
| 다음 수업 예측 | 계획서·교재 수치 | 계획서 수치, 없으면 "다음 자료" |
| 학습 지도 | 수업 순서 또는 교재 모양 | 수업 순서 |
| 시험 범위 | 교재·주차·자료 좌표 | 주차·자료 좌표 |

1. **자료가 수업 후에 올라옴:** §8.1 `unknownContent` → 나중에 연결.
2. **수업 후 문제지만 줌:** 문항이 대응된 구간이 그 회차의 다룬 집합 후보. 대응할 구간이 없으면 문제지 자체가 원천.
3. **판서·구두만:** 사진(19번)과 상담 발화가 원천.
4. **계획서도 교재도 없음:** 예측은 "다음 자료" 정도.

## 13. 문제지 문항 (3단계)

- **문항 분할:** PROBLEM_SET 자료를 `problem_items`(자료·파일 해시·순번·원문 위치)로. 문항 글을 지어내지 않는다.
- **문항 → 구간:** 파일명·머리의 장 표시(RULE) 먼저, 그다음 유사도 + 모델 확인(INFERRED).
- **문항 → 교재 항목:** 문제지에 적힌 표시("교재 5장 연습문제 3번", "p.150")(RULE) → 문항 글과 목차 제목의 유사도 + 모델(INFERRED) → 교재 일치
  수치가 높으면 주차로 후보를 좁힘. 약하면 붙이지 않는다. 화면: "막히면 교재 5.2 HttpServletRequest, p.143~158 (추정)".
- **고치기:** "추정" 연결은 한 번 눌러 고친다 → `source_links` STATED.
- **풀이:** ATTEMPTED(ITEM). STUCK이면 대응된 구간·교재 항목 상태에도 약함 근거(대응 확신도만큼).

## 14. 단계와 완료 기준

| 단계 | 내용 | 완료 기준 |
|---|---|---|
| 1 | `learning_events` + `LearningEventWriter`(참조 검증) + 원본 표 `event_revision` + 쓰기 경로 대응(§7) + `class_sessions` + `textbook_refs`(최소: id + BookKey alias — 목차 원천 식별에 필요) + 수업 후 입력·주간 확인(§8) + 몰아 올림 묶음 기록(§9) | 기존 테스트 무변화. 원본별 기대 이벤트와 실제 이벤트 대조 0 차이. MariaDB 10.4 DB 테스트: 롤백 시 이벤트 없음, 동시 정정, 재전송 멱등, payload 충돌, 다른 사용자 원천·이벤트 참조 거부, 삭제 후 재계산, 백필 도중 원본 수정, 막힌 단계 삭제 뒤 이전 STUCK이 살아 있지 않음, 자기 점검 저장·대체·해제 |
| 1b | 백필(§7.2) | 체크포인트 재개, 재실행 0 추가, 보고서(원천 미대응 비율) |
| 2 | `syllabus_weeks`·`source_links` 최소 대응 + 요청 시 계산 뷰(수업 진도·내 상태·시험 범위·예측) + 교재 쪽 범위 | 실제 과목 7개에서 "수업은 여기·나는 여기·시험은 여기"를 직접 확인, 예측 적중률 기록 |
| 3 | 문제지 문항 + 문항 대응(§13) | 실제 문제지 연결 정답률 80% 이상(직접 채점, 합성 fixture로 회귀) |
| 4 | 개념 그래프(선택, 아래) | 실제 과목 2~3개에서 의미 있는 개념 80% 이상 |
| 5 | 계획·상담 입력을 새 뷰로 | 같은 입력으로 전·후 계획 초안 비교, 회귀 테스트 |
| 6 | 정리 중지 → `topic_id` 의존 제거 → 트리 제거 | 의존 0, 백업·되돌리기 경로 |

DB 통합 테스트는 `memo_test`에서 돌리고 `build.gradle`의 CI 제외 목록에 올린다(지금 관례). 로컬 전체 테스트는 DB 포함으로 돌린다.

**4단계 개념 그래프(요약):** 좌표가 없거나 자료 단위가 너무 큰 과목부터. 구간 임베딩 → 코사인 임계값 응집 군집 → 모델 대표 이름. 정정은 원천 쌍의
must-link / cannot-link. 개념 ID 승계는 원천 집합 Jaccard(갈라지면 겹침 큰 쪽, 합쳐지면 오래된 ID). 빌드마다 모델·프롬프트 판·임베딩 모델 기록.

## 15. 기술·위험

- **새 표:** 1단계 `learning_events`, `class_sessions`, `textbook_refs`(+alias). 2단계 `syllabus_weeks`, `source_links`, `class_predictions`.
  3단계 `problem_items`. 4단계 `concept_*`. 그래프 DB 없음.
- **임베딩:** 서버의 기존 OpenAI 키, BLOB 저장, 유사도는 Java. MariaDB 10.4(벡터·JSON_TABLE 없음 — payload JSON은 Java에서 읽는다).
- **개인 정보:** 원본 PDF·문제지·교재 원문을 fixture·로그에 넣지 않는다(합성 자료). 이벤트에 발화 원문을 복사하지 않는다. payload에도 원문
  인용을 넣지 않는다.

| 위험 | 대응 |
|---|---|
| 다룬 범위 입력을 안 함 | 기본값, 주간 확인, 상담 입력, 근사 + "추정" |
| 근사를 정답으로 학습 | 근사는 저장하지 않고, 예측 관측은 INPUT·STATED만 |
| 활동을 이해로 오인 | 활동/풀이 분리, 상한, "근거 없음" 상태 |
| 이중 기록 누락 | 모든 쓰기 경로 표(§7), 같은 트랜잭션, 원본별 대조 |
| 백필 부풀림·중복 | 실제 인용 구간 우선, 내용 대응은 APPROX + 상한, 공통 origin, 토픽 이력 중복 제외 |
| 수정본·과목 이동으로 사실 오염 | SAME만 승계, 과목 귀속 유지, 명시적 정정 |
| 다른 사용자 원천 참조 | `LearningEventWriter` 참조 검증 |
| 삭제 정책 충돌 | 식별자·메타데이터와 본문 분리(§5.3) |
| 손으로 정한 상수 부적절 | 한 곳에 모으고 근거를 펼쳐 보이게 |

## 16. 결정 (사용자 확인 2026-10-06)

1. **수업 후 입력 알림:** 수업 직후 알림(과목별로 끌 수 있음) + 놓친 것은 주간 확인.
2. **내 학습 등급:** 4단계(익힘 / 보통 / 약함 / 복습 필요) + 근거 없음 표시.
3. **트리 제거까지 갈지:** 1~2단계를 써 보고 5~6단계를 정한다.
4. **프로젝트형 과목(결과물 단계 뷰):** 이번 범위 밖.

## 17. 리뷰 기록

Codex 설계 리뷰 1회차: REVISE 14건(P1 10, P2 4) — 모두 반영. 재리뷰(v2): **APPROVED**, 구현 계획에서 정할 P2 4건 — 모두 반영(아래 2표).

| # | 지적 | 반영 |
|---|---|---|
| 1 | 완료 원본은 `execution_records`, 일부 수행·재열기 | §7 표, §7.1 |
| 2 | 이중 기록이 생성 경로에 치우침 | §7 모든 쓰기 경로 표, `LearningEventWriter` |
| 3 | 이벤트만으로 의미 복원 불가 | §6.2 payload, §6.4 적용 순서, claim_at/seq |
| 4 | UNIQUE 멱등·정상 변경 | §6.3 origin + revision + output_no, NOT NULL, 충돌 처리 |
| 5 | 주차·회차·교재 열쇠가 불변이 아님 | §5.1 `syllabus_weeks`·`class_sessions`·`textbook_refs` |
| 6 | 백필이 연결 구간 전체로 부풀림 | §7.1 인용 구간 우선, §7.2 미대응은 옮기지 않음, 토픽 이력 중복 제외 |
| 7 | 수정본·과목 이동 자동 승계 | §5.2 SAME만 승계, 과목 귀속 유지 |
| 8 | 예측 대응 관계가 3단계 | §5.2 `source_links`를 2단계로, §10.2 관측 조건 |
| 9 | 일치 수치로 범위 변환 불가 | §10.4 좌표 체계·판·대응으로, AMBIGUOUS, 제외 이벤트 |
| 10 | 공개 시각·끊는 지점 과잉 | §8 회차별 집합, 근사 비저장, §9 업로드/공개 시각 분리 |
| 11 | 완료를 이해로 해석 | §3-9, §6.2 ATTEMPTED, §10.3 상한·근거 없음 |
| 12 | 캐시 갱신 | §10.6 요청 시 계산, 캐시 시 입력 판 |
| 13 | 다형 참조 권한 | §6.5 |
| 14 | 삭제 정책·전환 검증 | §3-3, §5.3, §7.2 체크포인트, §14 완료 기준 |

재리뷰 P2

| # | 지적 | 반영 |
|---|---|---|
| 1 | 목차 원천 식별에 `textbook_refs`가 1단계부터 필요 | §14·§15 1단계로 옮김(최소: id + alias) |
| 2 | `topic_class_progress`에는 수업 날짜가 없음 | §7 회차 확인 시만 SESSION, 아니면 COURSE 주차 진술(예측 관측 제외) |
| 3 | 도움 수준 NULL, 이해 평가가 아닌 상태, 자기 점검 경로 | §7 UNKNOWN 보존, 상태별 매핑 원칙(열거 전부는 구현 계획), `saveSelfChecks` 행 |
| 4 | 원본 판 저장·경합, 출력 수 감소 | §6.3 `event_revision` 열·잠금 순서·정렬 기반 output_no·판 단위 대체, §14 DB 테스트 |
