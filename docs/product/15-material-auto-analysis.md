# 15. 자료 자동 분석과 자료 기반 계획

> 상태: 구현 완료(2026-09-13, 브랜치 `feat/material-auto-analysis`). 이 문서가 자료 분석·자료 구간·과제·
> 자료 정리 변경안·계획의 자료 입력의 기준이다. 착수 시점의 판단 근거는 `docs/handoff/material-auto-analysis-2026-09-13.md`.
> 선행: 10-core-experience.md(자료와 상태의 역할), 13-plan-judgment.md(판단층).

## 0. 무엇이 바뀌었는가

| 전 | 후 |
|---|---|
| "구조 분석" 버튼을 눌러야 자료를 읽었다 | 업로드·기존 자료를 서버가 뒤에서 자동으로 읽는다. 상태 요약에서 일시중지/재개 |
| 원문 앞 36,000자만 읽고 전체 분석으로 표시 | 페이지·슬라이드 단위로 끝까지 읽고, 예산에 걸리면 PARTIAL(읽은 범위 기록) |
| 분석 결과 = topic 제목·위치 트리 | 자료 구간(역할·수행 내용·발췌·위치)이 남고 토픽 ↔ 구간이 N:M으로 연결된다 |
| 적용 = 트리 끝에 append | 기존 토픽과 대조한 변경안(연결/추가/이름/이동/병합/분할)을 사용자가 적용 |
| 과제 = analysis_json의 keyDates | course_assignments: 확인 질문 → 마감 → 완료 체크 한 줄 |
| 계획 입력 = 과목당 앞 30개 topic 제목 | 진행 중·첫 미학습·과제 연결 항목이 우선 남고, 구간 발췌·과제·확인된 맥락이 함께 실린다 |

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
| A12 | 계획 후보는 개수가 아니라 순서로 지킨다: 진행 중 → 과제 연결 → 첫 미학습부터 → 나머지. KNOWN/DEFER 제외, `excludeTopicIds`는 이번 요청만 | 과목당 앞 30개 |
| A13 | 모델은 회차에 실제로 준 구간 `[sN]`만 인용한다. 13번 D6의 "모델은 materialId를 고르지 않는다"는 "서버가 준 구간 참조 중에서 고른다"로 개정 | 파일명·쪽수 자유 작성 |
| A14 | 「자세히」는 (항목, 근거판)으로 저장·재사용. 항목·시간·마감·선택을 바꾸지 않는다. 인용 구간이 없으면 만들지 않는다 | 매번 생성, 근거 없는 단계 |
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

## 7. 계획 입력

`PlanMaterialContextService`가 프로젝트마다 묶음을 만들고 기본 AI 경로(`PeriodPlanDraftGenerator`)가 그대로 싣는다.

```text
[사용자가 확인한 맥락]        user_contexts ACTIVE/STALE (USER_CONTEXT 인용)
[대상 프로젝트]
  [학습 항목]                 후보 선택 순서(A12), 진행 중/학습 완료/← 첫 미학습 표시, 표식·이번만 제외 개수
    · [문제] 제목 (p.12) — 수행: … — "발췌" [sN]     연결된 구간(항목당 2, 프로젝트당 30)  MATERIAL_SECTION 인용
  [아직 학습 항목에 연결되지 않은 자료 구간]   문제·예제·제출 구간 최대 8
  [과제]                      확정(마감·출처) / (확인 전) / 완료 N개 제외           ASSIGNMENT 인용
  [아직 분석이 끝나지 않은 자료 N개]
```

- 프롬프트 규칙: 자료 역할·상태·맥락·지시를 보고 무엇을 먼저 할지 모델이 정하고 reason에 적는다. 구간 줄이 없는 항목에 "이 문제를
  확인했다"고 쓰지 않는다. 학습 완료는 근거가 있을 때만 "복습"으로. 확인 전 과제 후보와 추정 마감은 확정처럼 다루지 않는다.
- 응답 `pendingMaterials`로 미반영 자료를 돌려준다. 요청 `excludeTopicIds`는 이번 회차만 뺀다(저장 없음).
- V1 경로: `applyUserMarks`가 표식만이 근거였던 SKIP은 표식이 지워지면 FULL로 되돌린다(「다시 포함」의 최소 형태).
- 근거 UX는 그대로다: 인용은 회차 안에서만 유효하고, 새 출처 종류(`MATERIAL_SECTION`, `ASSIGNMENT`)는 기존 스냅샷 2판 안에서 줄로
  남으며 자료 파일 열기는 옆의 `material`로 이어진다.

## 8. 「자세히」

`POST /api/plans/drafts/items/{proposalItemId}/detail`(없을 때만 모델 1회) / `GET`(있으면 그대로, 없으면 available=false) /
확정 조각은 `/api/plans/items/{executionItemId}/detail`. 근거판 = sha256(제목|설명|구간 id:해시…). 항목이나 원문이 바뀌면
이전 판은 STALE로 보이고 새 판을 만든다. 사용자가 고친 `user_text`는 보존한다. 인용한 구간이 없으면 400
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
| 19·20 | 첫 미학습·KNOWN·이번만 제외 | `PlanCandidateSelectionTest`(5) | 통과 |
| 21·22 | 자세히 전환 불변·늦은 응답 | `PlanItemDetail.test.jsx` | 통과 |

### 9.1 실호출 기록

`scripts/ai-baseline/verify-auto-analysis-2026-09-13.py --seed`(합성 계정·합성 PDF 3종: 문제 없는 자료, 36쪽 뒤쪽에만 실습·제출,
마감이 애매한 과제 안내). 결과는 `docs/reviews/material-auto-analysis-completion.md`(diary-ui)와 99-changelog에 적는다.

## 10. 알려진 한계

- 상담 경로의 자료 발췌(`buildMaterialExcerpt`, 앞 3,000자)는 이번에 바꾸지 않았다. 구간 색인이 생겼으므로 다음에 옮긴다.
- "다음 수업까지" 추정은 문서 날짜(`docMeta.documentDate`)를 모델이 읽었을 때만 붙는다. 업로드일을 문서 날짜로 쓰지 않는다.
- 변경안은 분석마다 새로 만들어지고 이전 열린 변경안은 STALE이 된다. 적용 전 선택·제목 수정은 저장되지 않는다.
- 도서 메타데이터 조회(전체 쪽수·판본)는 붙이지 않았다. 교재 편집 기능은 그대로다. 목차만 있는 교재에 대해 구체적 문제 번호를
  만들지 않는 것은 프롬프트 규칙으로만 지킨다.
- CONTENT 작업의 청크 상한(12)을 넘는 자료는 PARTIAL로 남고 "다시 시도"가 이어서 읽는다.
