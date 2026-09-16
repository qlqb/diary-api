# 15. 자료 자동 분석과 자료 기반 계획

> 상태: 구현 완료(2026-09-13, 브랜치 `feat/material-auto-analysis`). 이 문서가 자료 분석·자료 구간·과제·
> 자료 정리 변경안·계획의 자료 입력의 기준이다. 착수 시점의 판단 근거는 `docs/handoff/material-auto-analysis-2026-09-13.md`.
> 선행: 10-core-experience.md(자료와 상태의 역할), 13-plan-judgment.md(판단층).
> 2026-09-15 개정: 계획 입력을 **AI 자료 선택 → 원문 조회 → 계획 호출**로 바꿨다(A12 재개정, A18~A21, §7, §9.2, §10).

## 0. 무엇이 바뀌었는가

| 전 | 후 |
|---|---|
| "구조 분석" 버튼을 눌러야 자료를 읽었다 | 업로드·기존 자료를 서버가 뒤에서 자동으로 읽는다. 상태 요약에서 일시중지/재개 |
| 원문 앞 36,000자만 읽고 전체 분석으로 표시 | 페이지·슬라이드 단위로 끝까지 읽고, 예산에 걸리면 PARTIAL(읽은 범위 기록) |
| 분석 결과 = topic 제목·위치 트리 | 자료 구간(역할·수행 내용·발췌·위치)이 남고 토픽 ↔ 구간이 N:M으로 연결된다 |
| 적용 = 트리 끝에 append | 기존 토픽과 대조한 변경안(연결/추가/이름/이동/병합/분할)을 사용자가 적용 |
| 과제 = analysis_json의 keyDates | course_assignments: 확인 질문 → 마감 → 완료 체크 한 줄 |
| 계획 입력 = 과목당 앞 30개 topic 제목 | 모델이 후보 전체(크면 접힌 묶음)에서 구간·학습 항목을 고르고, 서버가 고른 구간의 원문을 저장된 추출 단위에서 읽어 계획 호출에 싣는다(§7) |

10-core-experience.md §4.1의 "거대한 분석 완료 절차가 대화의 선행 조건이 되어서는 안 된다"는 그대로다.
분석은 뒤에서 돌고 사용자는 기다리지 않는다. 분석이 끝나지 않은 자료가 있어도 계획은 만들어지고,
응답이 "아직 반영되지 않은 자료 N개"를 말한다. 열린/확정된 계획을 분석 완료가 자동으로 다시 쓰지 않는다.

## 1. 확정된 결정

| # | 결정 | 기각한 대안 |
|---|------|------------|
| A1 | 작업 표(`material_analysis_jobs`)가 원본. `@Scheduled` 폴러가 선점·실행 | 브라우저 비동기 호출, Kafka·외부 큐 |
| A2 | 선점은 `UPDATE … WHERE status/lease 조건` 한 문장, 결과 저장은 전부 `lease_token` 대조 | 완료 행 UNIQUE만으로 중복 방지 |
| A3 | CONTENT(프로젝트 무관 원문 분석)와 LINK(프로젝트 맥락 연결 변경안)를 나눈다 | 프로젝트 연결을 분석의 선행 조건으로 |
| A4 | 청크 경계는 단위 경계, 겹침은 직전 단위 하나, 중복은 `dedupe_key`로 흡수 | 문자 수로 자르고 겹침 없음 |
| A5 | 구간의 역할은 닫힌 집합(`SectionRole`) + 별칭 정리, 모르는 값은 OTHER | 모델 문자열 무검증 저장 |
| A6 | 토픽 ↔ 구간 N:M(`topic_material_links`). `source_material_id`는 최초 출처로 보존, 백필은 `BACKFILL_SOURCE` | source_material_id 교체 |
| A7 | 변경안은 트리 버전(`courses.topic_tree_version`)을 들고 있고 적용 시 대조. 전부 아니면 전무 | 오래된 diff 부분 적용 |
| A8 | 이동·이름 변경은 id 유지. 병합은 살아남은 id + ARCHIVED/merged_into, 분할은 원본을 부모로. 기록은 복제하지 않고 `review_note` | 완료 하나를 자식 전부 완료로 복제 |
| A9 | 과제는 제목·마감·완료 체크만. CANDIDATE/CONFIRMED/NOT_ASSIGNMENT/LATER/DUPLICATE | 진행 단계·작업 조각·시간 분할 |
| A10 | 원문에 연도까지 명시된 제출 날짜만 마감으로 미리 채운다(SOURCE). 상대 표현·연도 없는 날짜는 추정 후보 | 올해 연도 자동 보정 |
| A11 | 「이 날짜 맞아요」·날짜 선택·마감 없음·아직 몰라요가 각각 다른 요청. 「과제 맞아요」는 날짜를 확정하지 않는다 | 한 버튼으로 과제·마감 동시 확정 |
| A12 | (2026-09-15 재개정) 서버는 계획 후보를 고르지 않는다. **판단 사실**(진행 중·첫 미학습·확정 과제의 마감·완료·구간·이번 요청의 지정 자료·확인된 맥락·지시·KNOWN/DEFER/이번만 제외)은 항상 입력에 넣되 실행 항목으로 강제하지 않는다. 나머지는 모델이 선택 호출에서 고른다. 역할 순위·토픽당 2개·미연결 8개·역할 제외·글자 예산 컷 없음 | 반드시 포함 + 트리 순서 글자 예산(같은 날 오전 판), 과목당 45줄 |
| A16 | (2026-09-15) 분석 결과(구간·과제 후보·변경안)는 작업 행을 `FOR UPDATE`로 잡아 토큰을 대조한 같은 트랜잭션에서만 쓴다. 자료 삭제의 작업 취소는 삭제 트랜잭션 안에서 먼저 | 확인 뒤 저장(사이에 틈) |
| A17 | (2026-09-15) 확정 과제의 마감·완료는 판단 입력과 마감 근거(DEADLINE)에 들어간다. 과제 수행 자체를 항목·조각으로 만드는 것은 사용자가 요청했을 때만 | — |
| A13 | 모델은 회차에 실제로 준 구간 `[sN]`만 인용한다. 13번 D6의 "모델은 materialId를 고르지 않는다"는 "서버가 준 구간 참조 중에서 고른다"로 개정 | 파일명·쪽수 자유 작성 |
| A14 | 「자세히」는 (항목, 근거판)으로 저장·재사용. 항목·시간·마감·선택을 바꾸지 않는다. 인용 구간이 없으면 만들지 않는다 | 매번 생성, 근거 없는 단계 |
| A18 | (2026-09-15) 모델 호출은 **선택 1 → (접힌 경우만) 펼친 묶음 선택 1 → 계획 1**. 선택 호출 상한 `MAX_SELECTION_CALLS = 2`. 선택 실패·시간 초과는 503 `E503_003`, 구조 없음·보여 주지 않은 id뿐이면 503 `E503_004` — 서버 순위로 대체하지 않고 기존 초안을 둔다. 정상적인 빈 선택은 원문 없이 계획 단계로 | 선택 실패 시 서버 순위 폴백, 무제한 탐색 |
| A19 | (2026-09-15) 예산은 호출별 입력 토큰 추정(jtokkit `o200k_base`, 여유 10%). 선택 16,000(넘으면 묶음 → 과목으로 접는다), 계획 24,000(넘으면 구간당 원문 2,400 → 300자로 줄이고, 그래도 넘으면 뒤에서부터 원문을 뺀다). 판단 사실만으로 상한(선택 32,000 / 계획 48,000)을 넘으면 호출하지 않고 400 `E400_034` | 한국어 1회 실측의 글자/토큰 비율 |
| A20 | (2026-09-15) 이번 요청의 지정 자료(`requestedMaterialIds`·`requestedSectionIds`)는 `origin=USER` 연결·업로드와 별개다. 화면 지정(자료함 [이 자료로 계획])은 id로, 지시 문장의 파일 이름은 대상 프로젝트 자료 안에서만 찾는다. 같은 이름이 여럿이면 고르지 않고 초안에서 묻는다. 영구 연결을 만들지 않는다 | 파일명 전역 검색, 지정 시 토픽 연결 생성 |
| A21 | (2026-09-15) 초안을 만든 요청(기간·강도·지시·범위·제외·지정 자료·대화)을 `ai_proposals.plan_request_json`에 두고, 일반·상담 초안의 다시 만들기는 `POST /api/plans/proposals/{id}/redraft`로 그 요청을 재사용한다. 본문은 제외 목록·지정 자료만 바꾼다 | 화면이 기본 날짜·빈 지시로 요청을 다시 조립 |
| A15 | 우선순위는 계획 시점에 모델이 판단하고(13번 D7·D11 유지), 자료 종류별 고정 순위를 서버 if문으로 두지 않는다 | 학교 자료 순위 하드코딩 |

## 2. 저장 계약

`docs/sql/2026-09-13-material-auto-analysis.sql`(추가 전용, 재실행 가능). 세부 컬럼은 05-database.md §17.

```text
material_text_units       추출 단위(PDF 페이지/PPTX 슬라이드/텍스트 블록). (material_id, file_hash, unit_index) UNIQUE
material_analysis_jobs    작업. (material_id, course_id(0=없음), job_kind, file_hash, analysis_version) UNIQUE. lease_owner/until/token, checkpoint_json
material_sections         구간. unit_start~end(물리), printed_page_*(확인된 인쇄 쪽수만), roles_json, task_text, excerpt,
                          assignment_cue/quote, date_candidates_json, dedupe_key
topic_material_links      토픽↔구간. (topic_id, material_id, section_id(0=자료 전체)) UNIQUE. origin BACKFILL_SOURCE|PROPOSAL_APPLIED|USER
topic_change_proposals    변경안. base_tree_version, ops_json, summary_json. (material_id, course_id, PROPOSED) 하나
course_assignments        과제. confirm_status, due_kind(UNKNOWN|NONE|DATE|DATETIME), due_source(SOURCE|ESTIMATED|USER),
                          due_estimate_json, completed_at, title_edited/due_edited, dedupe_key(user 내 UNIQUE), version
plan_item_details         「자세히」. (proposal_item_id, evidence_version) UNIQUE. steps_json, user_text, status CURRENT|STALE
material_analysis_controls 사용자별 일시중지
courses.topic_tree_version / course_topics.merged_into_topic_id, review_note / course_materials.page_count
```

## 3. 백그라운드 실행

- 등록: 업로드 커밋 뒤 `enqueueContent`(priority 0). 폴러 틱마다 backlog(추출 성공 + 현재 해시·판의 CONTENT 없음)를
  상한(`material.analysis.backlog.batch`)만큼 priority 10으로 등록하고, CONTENT가 끝난 자료의 프로젝트 연결마다 LINK를 등록한다.
  해시가 없던 옛 자료는 파일(없으면 원문 텍스트)에서 해시를 만들어 채운다.
- 선점(`claimNext`): 우선순위 순이되 backfill이 대기 중이면 한 자리는 반드시 backfill에 준다. 사용자별 하루 시작 한도.
  일시중지한 사용자의 작업은 후보에서 빠진다.
- 실행: 청크마다 임대 갱신 → 모델 1회 → 구간 INSERT IGNORE → checkpoint(완료 청크 목록·마지막 단위·문서 날짜). 임대를 잃으면
  즉시 물러난다(저장 없음). 최대 청크 수를 넘기면 PARTIAL + 읽은 범위.
- 실패: 인증(401/403) → UNAVAILABLE + 전역 쿨다운. 한도(429) → 시도 횟수 소모 없이 지수 백오프. 그 외 → 30초·2분·8분 뒤 재시도,
  3회 넘으면 FAILED. 성공한 청크는 다시 부르지 않는다.
- 유효성: 실행 시작·저장 시 자료 ACTIVE·해시 일치·링크 존재·프로젝트 ACTIVE를 다시 확인. 삭제/해제는 열린 작업을 CANCELLED로
  내리고 토큰을 올려 늦은 저장을 0행으로 만든다. 확정 과제·진도·백필 링크는 남는다.
- 로그: material_id·청크 번호·토큰 수·실패 종류만. 원문·파일명·사용자 식별은 남기지 않는다.
- 설정: `material.analysis.worker.enabled/poll-ms/concurrency/lease-seconds`, `backlog.batch`, `daily-job-limit`,
  `chunk-chars/overlap-chars/max-chunks-per-job`, `auth-cooldown-seconds`. 테스트 실행은 build.gradle이 worker를 끈다.

## 4. 원문 추출과 구간

- PDF는 PDFBox로 페이지마다, PPTX는 슬라이드마다 단위를 만든다(그림만 있는 페이지는 단위를 만들지 않는다 — 읽은 척하지 않는다).
  원본 파일이 없는 옛 자료는 `extracted_text`를 6,000자 블록으로 나누고 위치는 "구간 N"이다.
- HWP·IPYNB·ZIP(2026-09-16)은 물리 페이지가 없어 업로드 때 바로 "구간 N" 블록이 된다. `page_count`는 null이다.
  - HWP: HWP 5.0만(hwplib). 표·글상자 글자를 제자리에 넣는다. HWP 3.0·HWPX·암호 문서는 받지 않거나 추출 실패다.
  - IPYNB: 마크다운·코드 셀 원문만 `--- 셀 N · 코드 ---` 머리글로 잇는다. 실행 출력(base64 이미지 포함)은 읽지 않는다.
  - ZIP: 자료 하나로 저장한다(안의 파일을 자료 여러 개로 풀지 않는다). 안의 PDF·PPTX·HWP·IPYNB와 텍스트·소스 파일을 이름순으로
    `===== 파일: 경로 =====` 머리글과 함께 잇고, 끝에 읽지 않은 파일 목록을 붙인다. 중첩 압축은 풀지 않는다. 상한: 문서 100개,
    항목당 30MB·전체 150MB 해제(실제로 읽으며 센다), 글자 100만. 이름은 UTF-8, 깨지면 CP949로 읽는다.
  - 표식에 대괄호를 쓰지 않는다(zip 안 슬라이드는 `(슬라이드 N)`). 분석 입력의 단위 표식 `[구간 N]`과 섞이지 않게 하려는 것이다.
  - 업로드 검증: 브라우저가 보내는 content type이 제각각이라 이 셋은 앞머리 시그니처(OLE·`PK`·JSON `{`)로 확인하고,
    DB에는 형식별 대표 content type(`application/x-hwp`, `application/x-ipynb+json`, `application/zip`)을 저장한다.
- `page_count`는 파일에서 센 물리 페이지/슬라이드 수다. 인쇄 쪽수는 모델이 원문에서 실제로 봤을 때만 `printed_page_*`에 들어가고
  일괄 오프셋으로 만들지 않는다. 위치 문자열은 "p.3~4 (인쇄 21~22쪽)" 모양이다.
- 스캔 PDF는 기존대로 추출 실패(NO_TEXT)로 표시되고 분석 작업을 만들지 않는다. OCR은 이번 범위 밖이다.
- 구간 한 개 = 역할 복수 가능(`CONCEPT/EXAMPLE/EXERCISE/ASSIGNMENT/SCHEDULE/ADMIN/SUMMARY/REFERENCE/OTHER`) + 표시 제목 +
  수행 내용 + 발췌(≤600자) + 제출 단서(`assignment_cue`, 원문 인용) + 날짜 후보(`text/isoDate/monthDay/kind/relative/basis`).
- 자료 안의 지시문은 데이터다(프롬프트가 명시). 삭제된 자료의 구간은 발췌가 비워지고 계획 입력에서 빠진다.

## 5. 토픽 연결과 구조 변경안

LINK 분석은 기존 트리(id·계층·연결 수)와 이 자료의 구간(S번호), 기존 과제(A번호)를 보고 작업 목록을 낸다.

| 상황 | 작업 |
|------|------|
| 기존 주제에 새 설명·문제 | `LINK(topicId, sectionIds, role)` |
| 독립 진도 관리할 세부 내용 | `ADD(tempId, parentTopicId|parentTempId, title, sectionIds)` |
| 이름 보완 | `RENAME(topicId, title)` |
| 계층 오류 | `MOVE(topicId, parentTopicId|null)` |
| 중복/혼합 | `MERGE(survivingTopicId, absorbedTopicIds)` / `SPLIT(topicId, children)` |
| 마감이 가까움 | 변경 없음(계획이 다룬다) |

서버(`TopicChangeOpsValidator`)는 없는 id·이 자료에 없는 구간·자기/자손 아래 이동·자기 병합·해석 안 되는 tempId를 거른다(생성 시
lenient, 적용 시 strict = 하나라도 나쁘면 전체 거부). 적용(`TopicTreeEditor`)은 프로젝트 행 FOR UPDATE + `topic_tree_version`
대조 + 활성 항목 FOR UPDATE 한 트랜잭션이다. 같은 변경안 재적용·동시 적용은 409, 오래된 diff는 `TOPIC_TREE_CONFLICT`(409)이고
화면은 `stale`로 미리 보여 준다. 예전 append 경로(`MaterialAnalysisService.apply`)도 버전을 올린다.

화면 요약은 "기존 내용에 자료 3곳 연결 · 새 항목 2개 제안"이고, 이동·병합·분할은 접힌 상태에서도 요약 아래에 보인다.
사용자는 작업마다 체크를 풀거나 ADD/RENAME 제목을 고칠 수 있다.

## 6. 과제

- 후보는 CONTENT 단계에서 `assignment_cue`가 있는 구간마다 만들어진다(자료가 프로젝트 하나에만 연결돼 있으면 그 프로젝트,
  아니면 프로젝트 없이). LINK 단계가 프로젝트·토픽을 채우고, 문서 날짜가 있고 "다음 수업까지"류면 확인된 수업 일정으로 추정 후보에
  날짜를 붙인다(`due_estimate_json`, 마감이 아니다).
- 화면: "이 부분은 과제인가요?" [과제 맞아요] [연습용이에요] [나중에] · 원문 보기. 같은 카드에서 마감 — 원문 명시 날짜는 미리 채워져
  근거가 보이고, 추정은 [이 날짜 맞아요]로만 확정, 없으면 [날짜 선택] [아직 몰라요] [마감 없음].
- 목록: `☐ 제목 · 9월 18일까지 · 자료 보기`. 체크는 즉시 저장(version 대조, 409면 다시 읽음). 지난 마감은 표시하고 숨기지 않는다.
  끝낸 과제는 접힌 목록에서 해제할 수 있다. 오늘 화면은 같은 원본에서 오늘/지난/7일 안 마감만 보여 준다.
- 재분석은 CANDIDATE의 인용·추정만 갱신하고, 답한 상태·사용자가 고친 제목·마감(`*_edited`)은 덮지 않는다. 같은 구간은 `dedupe_key`로
  한 행이고, 제목만 같은 다른 자료의 과제는 별개 행이며 모델의 "같은 과제" 힌트는 `duplicate_of_assignment_id`로만 남는다.
- 마감만 있고 수행 날짜·시간이 없어도 정상이다. 과제 확인이 시간표를 채우지 않는다. 계획 프롬프트는 과제 수행 시간을 사용자가 요청할
  때만 잡고, 준비 개념·연습은 제안해도 된다고 말한다.

## 7. 계획 입력 — AI 자료 선택 (2026-09-15)

기본 AI 경로(`PeriodPlanDraftGenerator`, `plan.draft.generator=AI` 기본값)가 계획 화면의 [초안 만들기]와 상담의 CREATE_PERIOD_PLAN을
함께 처리한다. 두 경로 모두 아래 순서를 탄다.

```text
PlanRequestedMaterialResolver  지정 자료 id 검증(소유·범위, 아니면 400 E400_033) + 지시 문장의 파일 이름 해석
                               (대상 프로젝트 자료만, 이름 줄기 4자 이상, 여럿이면 ambiguities로 돌려주고 고르지 않음)
PlanMaterialContextService     프로젝트별 카탈로그. 학습 항목 전체, 구간 전체(토픽 연결·미연결, 역할 무관, 한 토픽의 세 번째 이후 포함),
                               확정 과제(미완료·완료, 구간 식별), KNOWN/DEFER/이번만 제외, 아직 분석 중인 자료. 고르지 않는다.
PlanMaterialSelector           선택 호출 1: [요청] [판단에 꼭 필요한 사실] [후보 — 전부] 또는 [접힌 묶음] + [항상 보이는 후보 — 지정 자료와 과제 구간]
                               → {"sections":[{"id":"m12","reason":…}], "topics":[…], "expandGroupIds":["g3"], "insufficientEvidence", "note"}
                               접힌 경우에만 선택 호출 2: 펼친 묶음의 줄 + [이미 고른 것]. 여기서 낸 expandGroupIds는 무시한다.
PlanMaterialRetriever          고른 구간을 다시 검증(사용자·구간 ACTIVE·자료 ACTIVE·파일 해시·범위=프로젝트 연결 또는 지정)하고
                               material_text_units에서 구간 범위(unit_start~unit_end)의 원문을 읽는다.
PeriodPlanDraftGenerator       계획 호출: [판단에 필요한 사실] [이번 계획을 위해 고른 학습 항목] [고른 자료 구간 — 저장된 원문에서 읽음]
                               (따옴표 원문 블록 + "읽은 범위") [자료 선택 결과] + 일정·가용시간·사용자 지시
```

- **핸들**: 선택 호출은 요청마다 새로 붙인 `t`(학습 항목)·`m`(구간)·`g`(묶음)·`c`(과목) 핸들만 본다. DB id를 모델에 보이지 않는다.
  보여 주지 않은 핸들은 버리고 센다(`unknownIds`). 낸 값이 전부 모르는 값이면 잘못된 응답이다.
- **학습 항목을 고르면** 그 항목이 계획 호출의 초점 줄로 실린다. 하위 구간 원문을 통째로 싣지 않는다 — 원문은 고른 구간만이다.
- **목록 설명과 원문을 나눈다**: 후보 줄은 제목·역할·위치·수행·짧은 발췌(목록 설명)이고, 계획 호출의 따옴표 블록만 원문이다. 원문
  블록이 없는 항목에 "이 문제를 확인했다"고 쓰지 않게 프롬프트가 막는다.
- **판단 사실은 실행 지시가 아니다**: "진행 중"은 이어서, "← 첫 미학습"은 기록 없는 사용자의 기본 출발점(다른 목표를 말했으면 그
  목표가 먼저), "미완료 과제의 항목"은 과제 자체라 요청이 없으면 수행·작성·제출 항목을 만들지 않는다. 과제 행이 구간만 가리켜도
  (topicId 없음) 그 구간에 연결된 학습 항목에 과제 표시가 붙는다. 완료한 과제는 원문에 과제 문구가 남아 있어도 다시 수행하지 않는다.
- **인용**: 계획 호출에 준 `[sN]`만 유효하다. 선택 호출의 핸들은 근거 스냅샷에 들어가지 않는다. 원문을 실은 구간은
  `MATERIAL_SECTION`(providedValue에 `retrievedRange`·`retrieval`·`selectionReason`, `parentSourceId`=학습 항목)이고, 선택 요약은 서버
  계산 `MATERIAL_SELECTION`(입력 전문은 남기지 않음)이다. 사용량 기록은 `PLAN_MATERIAL_SELECTION`·`PLAN_DRAFT`가 같은 `workflow_id`
  (=generationId)로 묶인다.
- **조회 결과**(`outcome`): FULL / PARTIAL(앞 N자) / EXCERPT_ONLY(추출 단위 없음) / DROPPED_DELETED / DROPPED_CHANGED(해시 바뀜) /
  DROPPED_SCOPE(연결 해제) / NOT_RETRIEVED_BUDGET. DROPPED는 싣지 않고 이유를 결과에 남긴다. 오래된 원문과 새 위치를 섞지 않는다.
- **실패와 빈 결과**: 선택 호출 실패 503 `E503_003`, 잘못된 응답 503 `E503_004`, 정상적인 빈 선택은 "이번에 읽은 원문은 없다"로 계획
  단계에 간다. 계획 호출이 성공했는데 쓸 항목이 없으면 503 `E503_005`(빈 초안을 저장하지 않고 조건을 바꾸라고 안내). 범위가 너무
  커 판단 사실만으로 상한을 넘으면 400 `E400_034`.
- **응답**: `materialSelection`(상태·모드·호출 수·펼침 여부·후보 수/보여 준 수·고른 구간과 읽은 범위·검토하지 못한 범위·지정 자료·
  모호한 이름·선택/계획 입력 토큰 추정)과 `requestContext`(출처 PLAN_SCREEN|CONVERSATION·범위·이번만 제외·지정 자료·redraftable),
  기존 `pendingMaterials`.
- **다시 만들기**: `plan_request_json`에 요청을 저장한다. `redraft`는 옛 초안을 FOR UPDATE로 잡아 PROPOSED인지 다시 보고, 새 초안을
  (상담이면 같은 대화에) 저장한 뒤 옛 초안을 DISMISSED로 바꾼다(한 트랜잭션). 생성이 실패하면 옛 초안은 그대로다. 요청이 저장되지
  않은 옛 초안은 409 `E409_019`, 이미 처리된 초안은 409 `E409_020`.
- **화면**: 「이번만 빼기」·되돌리기(하나/모두)·「이미 알아요」와 그 되돌리기가 계획 화면과 상담 초안에서 같은 redraft로 간다.
  제외 목록은 같은 작성 흐름(기간·범위·지정 자료가 같음)에서만 이어지고 새 기간·범위에는 넘기지 않는다. 늦은 응답은 요청 순번으로
  버린다. 「이미 알아요」 저장 뒤 다시 만들기가 실패하면 확정을 막고 [초안 다시 만들기]·[표시 되돌리기]를 준다. 자료함의
  [이 자료로 계획]은 지정 자료를 들고 계획 화면으로 간다. 선택 결과는 접힌 패널 "AI가 이번 계획을 위해 고른 자료"에 읽은 범위·
  검토하지 못한 범위와 함께 보인다.
- **판단 경로**(`PlanningContextBuilder`, `plan.draft.generator=JUDGMENT|V1`)는 이 선택 흐름에 연결하지 않았다. 창 밖 판단 사실과 확정
  과제 입력(A17)은 그대로다(§10).
- 근거 UX는 그대로다: 인용은 회차 안에서만 유효하고, 자료 파일 열기는 providedSources 옆의 `material`로 이어진다.

## 8. 「자세히」

`POST /api/plans/drafts/items/{proposalItemId}/detail`(없을 때만 모델 1회) / `GET`(있으면 그대로, 없으면 available=false) /
확정 조각은 `/api/plans/items/{executionItemId}/detail`. 근거판 = sha256(제목|설명|구간 id:해시…). 항목이나 원문이 바뀌면
이전 판은 STALE로 보인다. **저절로 다시 만들지 않는다** — 화면의 [지금 원문으로 다시 만들기](POST)를 눌러야 새 판이 생기고, 그때
사용자가 고친 `user_text`는 새 판으로 옮긴다(`PlanItemDetailServiceTest`). 메모는 화면에서 바로 남기고 고친다(PATCH, 모델 호출 없음). 인용한 구간이 없으면 400
`PLAN_ITEM_DETAIL_UNAVAILABLE`이고 화면은 "자세히 볼 근거 자료가 없어요"다. 전체 전환과 항목 하나만 펼치기가 있고 영구 선호로
저장하지 않는다.

## 9. 검증 (2026-09-13)

| # | 시나리오 | 방법 | 결과 |
|---|---|---|---|
| 1·2 | 업로드 후 화면 이동 / 기존 자료 함께 처리 | 실서버 + 합성 계정 스크립트, 폴러 로그 | §9.1 |
| 3·4 | 동시 선점·임대 만료·옛 결과 차단·취소 후 늦은 저장 | `MaterialAnalysisJobLeaseTest`(실DB, 7건) | 통과 |
| 5 | 실패 분류·재시도 예약 | `AnalysisFailureClassifier`·`reschedule` 테스트 | 통과 |
| 6 | 긴 자료 마지막 쪽의 문제 | `MaterialTextUnitServiceTest`(40쪽 합성 PDF, 마지막 단위·청크 포함) + 실호출 | §9.1 |
| 7 | 물리 페이지 vs 인쇄 쪽수 | 구간 `locator()`·`page_count` | 단위 테스트 |
| 8·9 | 기존 토픽에 새 실습자료, 역할 혼재 | 실호출 변경안 + 구간 roles | §9.1 |
| 10·11·12 | 이동/이름/병합/분할, 순환·오래된 diff·재적용 | `TopicChangeOpsValidatorTest`(7), `TopicChangeProposalApplyDbTest`(5) | 통과 |
| 13~17 | 과제 확인·마감 구분·완료·dedupe | `CourseAssignmentServiceDbTest`(6) + 화면 테스트 | 통과 |
| 19·20 | 첫 미학습·KNOWN·이번만 제외 | `PlanMaterialSelectionFlowTest`(2026-09-15에 `PlanCandidateSelectionTest`를 대체) | 통과 |
| 21·22 | 자세히 전환 불변·늦은 응답 | `PlanItemDetail.test.jsx` | 통과 |

### 9.2 AI 자료 선택 검증 (2026-09-15)

모델 출력은 테스트가 정하고, 단언은 **계획 호출에 실제로 들어간 사용자 프롬프트**와 저장된 근거까지 본다
(`PlanSelectionFixture`: 실제 선택기·조회기·해석기·카탈로그·생성기 + 메모리 DB).

| # | 요구 | 테스트 | 결과 |
|---|---|---|---|
| T1 | 토픽 120개 뒤쪽 구간을 고르면 그 원문이 계획 호출에 | `PlanMaterialSelectionFlowTest.T1_…` | 통과 |
| T2 | 같은 토픽의 문제 둘·개념 중 개념을 고르면 개념 원문 | `T2_…` | 통과 |
| T3 | 토픽에 연결되지 않은 개념 PDF도 후보·조회 | `T3_…` | 통과 |
| T4 | origin=USER 없이 지정한 자료, 파일 이름 해석·모호함, 범위 밖 400 | `T4_이번_요청의_자료_지정`(4) | 통과 |
| T5 | 개념 먼저/문제 먼저에서 모델 순서를 서버가 바꾸지 않음 | `T5_…` | 통과 |
| T6 | 마감·진행 중·첫 미학습은 판단에 남고 항목 강제 없음 | `T6_…` | 통과 |
| T7 | 예산 초과 시 접기 → 펼치기로 뒤쪽 구간 접근, 호출 2회 상한 / 판단 사실만으로 상한 초과 시 호출 없음 | `T7_…`(2) | 통과 |
| T8 | 보여 주지 않은 id, 선택~조회 사이 삭제·변경·연결 해제, 타 사용자 구간 | `T8_돌아온_id의_검증`(3) | 통과 |
| T9 | 실패·잘못된 응답·정상적인 빈 선택·항목 없는 계획 구분 | `T9_…`(4) | 통과 |
| T10 | 선택/계획 호출 분리, refIds는 계획 호출 근거만(모르는 값 2 → unknownRefCount) | `T10_…` | 통과 |
| T11 | 활성 경로가 새 흐름을 쓴다 | `PlanDraftServiceTest.defaultAiPath_runsTheMaterialSelectionCallBeforeThePlanCall`, `AiConversationServiceTest`(상담 → 같은 서비스), 실호출·브라우저(아래) | 통과 |
| T12~T17 | 빼기·되돌리기·기간 변경·같은 흐름·상담 초안·실패/역전·KNOWN 되돌리기 | diary-ui `PlanCreateViewExclude.test.jsx`, `PlanRedraftServiceTest`(6) | 통과 |
| T18 | 미완료 과제는 마감 사실로만, 구간만 가리키는 과제도 학습 항목에 과제 표시 | `T18_…`(2) | 통과 |
| T19 | 완료 과제 구간은 완료 사실과 함께 | `T19_…` | 통과 |
| T20 | 오래된 상세 갱신: 새 근거·메모 유지·계획 불변 | `PlanItemDetailServiceTest` | 통과 |
| T21~T23 | CONTENT 삭제·임대 교체, LINK 삭제·교체 | `MaterialAnalysisResultRaceDbTest`(4, 로컬 DB), `MaterialAnalysisJobLeaseTest`(7) | 통과(로컬 DB, CI 제외 목록) |

실호출(gpt-5.6-luna, 합성 계정만): `scripts/ai-baseline/verify-ai-material-selection-2026-09-15.py`.

| 시나리오 | 모드 · 선택 호출 | 후보/보여 줌 | 선택 입력 추정 → 실측 | 계획 입력 추정 → 실측 | 결과 |
|---|---|---|---|---|---|
| 작은 과목 · 개념 먼저 | FULL · 1 | 47/47 | 4,845 → 4,402 | 3,832 → 3,481 | 과제 안내 구간 2개를 읽고 개념 이해 항목 2개(과제 수행 없음) |
| 작은 과목 · 문제 먼저 | FULL · 1 | 47/47 | 4,846 → 4,403 | 3,645 → 3,311 | 연습 문제 구간 1개 → 구현 연습 1개 |
| 작은 과목 · 지정 자료(40쪽 PDF) | FULL · 1 | 47/47 | 5,181 → 4,708 | 8,172 → 7,427 | 지정 자료 p.1~5 원문 → 항목 5 |
| 작은 과목 · 지시 문장의 파일 이름 | FULL · 1 | 47/47 | 5,180 → 4,707 | 7,240 → 6,579 | 이름 해석 → 같은 자료 p.1~4 |
| 지정 자료 초안 다시 만들기(본문 없음) | FULL · 1 | 47/47 | 5,181 → 4,708 | 8,194 → 7,447 | 저장 요청의 지정 자료·기간 유지 |
| 큰 계정(과목 6·항목 288·구간 540) · 지시 있음 | FOLDED_GROUPS · 2 | 828/33 | 7,320 → 6,652 · 3,976 → 3,612 | 7,923 → 7,200 | 네트워크프로그래밍 묶음만 펼쳐 10구간 |
| 큰 계정 · 지시 없음 | FOLDED_GROUPS · 2 | 828/171 | 7,308 → 6,641 · 15,788 → 14,350 | 7,599 → 6,906 | 과목별 진행 중·과제 개념 구간 9 |
| 큰 계정 · 이번만 빼기 후 다시 만들기 | FOLDED_GROUPS · 2 | 825/170 | 7,302 → 6,636 · 15,856 → 14,412 | 9,009 → 8,188 | 뺀 항목 인용 0, 옛 초안 DISMISSED, 기간 유지 |
| 큰 계정 · 되돌리기 | FOLDED_GROUPS · 2 | 828/171 | 7,308 → 6,641 · 15,788 → 14,350 | 6,764 → 6,147 | 후보 복귀, 그 항목 다시 인용 |

- 추정은 여유 10%를 더한 값이고, 여유를 뺀 원 추정은 실측과 0.1% 안이었다(선택·계획, 한국어, 짧은/긴 입력 모두).
- 모든 실호출에서 항목이 인용한 구간은 모델이 고르고 서버가 원문을 읽은(retrieval=FULL) 구간뿐이었고, 모르는 인용은 0이었다.
- 관찰된 실패: 개념 자료가 없는 작은 과목에서 "개념 먼저"가 항목 0개로 끝났다(수정 전에는 일반 503으로 보였다 → `E503_005`로 구분).
  학습 항목의 첫 미학습이 과제 자체인데 과제 표시가 없어 과제 구현 항목이 한 번 나왔다 → 구간 기준 과제 표시로 고친 뒤 재발 없음.
  작은 과목(학습 항목 4개 중 3개가 과제)에서 연습 문제 항목을 빼고 다시 만들면 항목 0개(`E503_005`)가 정상 결과로 돌아온다.

### 9.1 실호출 기록

`scripts/ai-baseline/verify-auto-analysis-2026-09-13.py --seed`(합성 계정·합성 PDF 3종: 문제 없는 자료, 36쪽 뒤쪽에만 실습·제출,
마감이 애매한 과제 안내). 결과는 `docs/reviews/material-auto-analysis-completion.md`(diary-ui)와 99-changelog에 적는다.

## 10. 알려진 한계

- "사용자가 자료를 연결한 항목"(`topic_material_links.origin = USER`)을 만드는 화면은 아직 없다. 지금 있는 연결은 백필(`BACKFILL_SOURCE`)과
  변경안 적용(`PROPOSAL_APPLIED`)뿐이라, 반드시 포함 규칙의 이 갈래는 계약만 있고 실데이터에서는 비어 있다.
- (2026-09-15) 판단 경로(`plan.draft.generator=JUDGMENT|V1`)는 AI 자료 선택에 연결하지 않았다. 기본값(AI)만 새 흐름이다.
- (2026-09-15) 상담 패널에는 자료를 "고르는" 화면이 없다. 상담에서의 지정은 대화 문장의 파일 이름 해석으로만 되고, 같은 이름이
  여럿이면 초안의 선택지로 고른다. 자료함의 [이 자료로 계획]은 계획 화면으로 간다.
- (2026-09-15) 토큰 추정은 o200k_base 인코딩 기준이다. 모델이 바뀌면 실측으로 다시 확인한다.
- (2026-09-15) 초안 생성은 한 트랜잭션이라 **실패한 요청의 사용 기록(`ai_usage_logs`)도 함께 롤백된다.** 실패한 호출의 토큰은 서버
  로그로만 남는다. 계획 초안 경로는 이전부터 일일 호출 한도를 검사하지 않고, 이제 초안 1회가 모델 2~3회다.
- (2026-09-15) `plan_request_json`이 없는 옛 초안은 같은 조건으로 다시 만들 수 없다(409, 화면은 새로 만들기 안내). 되묻기 답
  (familiarity)은 판단 경로 전용이라 요청 맥락에 저장하지 않는다.
- (2026-09-15) 판단 사실 자체는 접지 않는다. 진행 중 항목이 매우 많아 상한을 넘으면 요약하지 않고 400으로 범위를 좁혀 달라고 한다.

- 상담 경로의 자료 발췌(`buildMaterialExcerpt`, 앞 3,000자)는 이번에 바꾸지 않았다. 구간 색인이 생겼으므로 다음에 옮긴다.
- "다음 수업까지" 추정은 문서 날짜(`docMeta.documentDate`)를 모델이 읽었을 때만 붙는다. 업로드일을 문서 날짜로 쓰지 않는다.
- 변경안은 분석마다 새로 만들어지고 이전 열린 변경안은 STALE이 된다. 적용 전 선택·제목 수정은 저장되지 않는다.
- 도서 메타데이터 조회(전체 쪽수·판본)는 붙이지 않았다. 교재 편집 기능은 그대로다. 목차만 있는 교재에 대해 구체적 문제 번호를
  만들지 않는 것은 프롬프트 규칙으로만 지킨다.
- CONTENT 작업의 청크 상한(12)을 넘는 자료는 PARTIAL로 남고 "다시 시도"가 이어서 읽는다.
