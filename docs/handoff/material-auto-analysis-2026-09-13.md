# 자료 자동 분석과 자료 기반 계획 — 구현 지도 (2026-09-13)

> 상태: 착수 시점의 지도. 작업이 끝나면 §6에 결과를 적고, 정식 계약은 `docs/product/15-material-auto-analysis.md`와
> `05-database.md`·`13-plan-judgment.md`·`99-changelog.md`로 옮긴다. 이 문서는 "왜 이렇게 나눴는가"만 남긴다.
> 출발 SHA: API `ff591b6`, UI `8a7fb9e` (둘 다 원격 dev-playground HEAD와 일치, 로컬 변경 없음).
> 작업 브랜치: 두 저장소 모두 `feat/material-auto-analysis`.

## 1. 착수 전에 코드로 확인한 것

| 영역 | 확인된 사실 | 결정 |
|---|---|---|
| 업로드 | `MaterialService.upload`는 파일 저장 → 텍스트 추출(요청 스레드, 동기) → `MaterialTxService.createWithLink` 한 트랜잭션. PENDING 행은 실제로 생기지 않는다 | 추출은 그대로 두고, 트랜잭션 커밋 뒤 작업 행만 등록한다 |
| 추출 | PDF는 PDFBox `getText` 전체 문자열(페이지 구분 없음). PPTX는 `[슬라이드 N]` 표식(링크 제안이 이 표식을 파싱한다) | 새 `material_text_units`에 페이지/슬라이드 단위를 따로 저장. `extracted_text`와 PPTX 표식은 그대로 유지(기존 기능 호환) |
| 분석 | `MaterialAnalysisService.analyze`는 컨트롤러 스레드에서 `blockLast()`. 입력은 앞 36,000자(12,000 토큰 × 3). 결과는 `course_material_analyses`(DRAFT 단일성) | 백그라운드 작업 테이블 + 폴러. 긴 문서는 단위 기준으로 분할, 겹침 1단위. 기존 DRAFT/APPLIED/edited_json은 건드리지 않는다 |
| 백그라운드 인프라 | `@Scheduled`/`@Async`/TaskExecutor 없음. 이벤트는 동기 `@EventListener` 하나 | `@EnableScheduling` + DB 작업 표 + 임대(lease). 외부 인프라 없음 |
| 토픽 | `TopicService.applyAnalyzedTopics`가 유일한 생성 경로, append-only. 이동/이름 변경/병합 코드 없음. `courses`에 버전 컬럼 없음 | `courses.topic_tree_version`으로 트리 낙관적 잠금. 변경안(`topic_change_proposals`) 적용은 한 트랜잭션, 버전 불일치는 409 |
| 토픽-자료 | `course_topics.source_material_id` 단일 값. 실데이터 271행 전부 채워짐, `source_locator`는 전부 "N주차" | `topic_material_links`(토픽 ↔ 자료 구간 N:M) 신설. 기존 값은 `origin=BACKFILL_SOURCE`로 1행씩 백필 — "모델이 본문을 읽었다"는 기록이 아니다 |
| 날짜/과제 | `keyDates`는 analysis_json 안에만 있고 `PeriodPlanDraftGenerator`가 그걸 읽는다. 과제 확정/완료 원본 없음 | `course_assignments`(제목·마감·완료 체크·확인 상태) 신설. keyDates 읽기는 유지하되 확정 과제가 우선 |
| 계획 입력 | 기본 경로 AI: 과목당 앞 30개 topic, 교재명, 일정 일부. V1: 주차 창 −2~+1 | 후보 구성기를 공유 계약으로 새로 두고 두 경로가 같이 쓴다(§4) |
| 상담 자료 | `buildMaterialExcerpt` 앞 3,000자 | 이번엔 손대지 않는다(계획 경로가 대상). 구간 색인이 생기면 다음에 바꾼다 |
| 페이지 | PDF 물리 페이지 수는 어디에도 없다 | `course_materials.page_count` 추가, 업로드·백필 시 PDFBox로 계산 |
| AI 오류 분류 | `AiErrorClassifier`는 429/quota만 구분, 401은 일반 실패 | 작업 실행기에서 인증(401/403)·한도(429)·타임아웃·기타를 나눠 재시도 정책을 달리한다 |
| 실데이터 | user 2: 자료 23건(최대 21,579자, 전부 프로젝트 미연결 `course_id NULL`, 링크는 `material_links` 26행), 분석 13건(APPLIED 8·DRAFT 3·FAILED 2), topic_progress 0행 | 백필 기준 데이터. 긴 문서 시나리오는 합성 PDF(PDFBox)로 만든다 |

문서에서 개정하는 방침: 03/05 계열에 "분석은 수동 버튼"이라는 명시 문장은 없고, 코드 주석과 `10-core-experience.md §4.1`("거대한 분석 완료 절차가 선행 조건이 되면 안 된다")이 있다. 자동 분석은 이 원칙과 충돌하지 않는다 — 분석은 뒤에서 돌고 사용자는 기다리지 않는다.

## 2. 저장 계약 (마이그레이션 `docs/sql/2026-09-13-material-auto-analysis.sql`)

추가만 한다. 기존 컬럼·행·ID는 바꾸지 않는다. FK 없음(2026-08-16 이후 신규 테이블 컨벤션), VARCHAR + CHECK, MariaDB 10.4.

```text
course_materials         + page_count INT NULL, + analysis_state VARCHAR(20) (NONE|QUEUED|RUNNING|PARTIAL|DONE|FAILED|UNAVAILABLE|PAUSED) 캐시
material_text_units      추출 단위. (material_id, file_hash, unit_index) UNIQUE. unit_type PDF_PAGE|PPTX_SLIDE|TEXT_BLOCK, unit_no, text
material_analysis_jobs   작업 표. kind CONTENT(프로젝트 무관 구간 분석) | LINK(프로젝트 맥락 연결 변경안)
                         (material_id, course_id(0=없음), kind, file_hash, analysis_version) UNIQUE
                         status QUEUED|RUNNING|DONE|PARTIAL|FAILED|UNAVAILABLE|PAUSED, priority, attempt, next_run_at,
                         lease_owner, lease_until, lease_token(선점마다 +1, 결과 저장 시 대조), checkpoint_json, error_code, error_message
material_sections        AI가 식별한 자료 구간. (material_id, file_hash, dedupe_key) UNIQUE
                         unit_start/unit_end(물리), printed_page_start/end(인쇄 쪽수, 확인된 것만), label, display_title,
                         roles_json, task_text, excerpt, assignment_cue, assignment_quote, date_candidates_json, chunk_index
topic_material_links     토픽 ↔ 자료 구간. (topic_id, material_id, section_key(0=자료 전체)) UNIQUE
                         role, locator, origin BACKFILL_SOURCE|PROPOSAL_APPLIED|USER, status ACTIVE|REMOVED
topic_change_proposals   LINK 작업의 결과. base_tree_version, ops_json, summary_json, status PROPOSED|APPLIED|DISMISSED|CONFLICT|STALE
courses                  + topic_tree_version BIGINT NOT NULL DEFAULT 0
course_topics            + merged_into_topic_id BIGINT NULL, + review_note VARCHAR(200) NULL (병합·분할 승계가 애매할 때)
course_assignments       과제. title, confirm_status CANDIDATE|CONFIRMED|NOT_ASSIGNMENT|LATER, due_kind UNKNOWN|NONE|DATE|DATETIME,
                         due_date, due_at, due_source SOURCE|ESTIMATED|USER, due_quote, due_estimate_json, completed_at,
                         user_edited_flags, source material/section/topic, dedupe_key UNIQUE(user_id, dedupe_key), version
plan_item_details        「자세히」 안내. (proposal_item_id, evidence_version) UNIQUE. steps_json, status CURRENT|STALE, user_text
material_analysis_controls 사용자별 자동 분석 일시중지 (user_id PK, paused, paused_at)
```

백필(같은 파일, 재실행 가능):
- `topic_material_links` ← `course_topics.source_material_id` (INSERT … SELECT … WHERE NOT EXISTS), origin=BACKFILL_SOURCE, locator=source_locator.
- 작업 표는 SQL로 채우지 않는다. 서버가 뜬 뒤 폴러가 "추출 성공 + 작업 없음"인 자료를 idempotent하게 등록한다(UNIQUE + INSERT IGNORE). 기존 자료는 priority 10(새 업로드 0), 틱마다 backfill 1건은 반드시 집어 굶지 않게 한다.

## 3. 백그라운드 실행 (`material/analysis/*`)

- `MaterialAnalysisJobScheduler` `@Scheduled(fixedDelay)`: (1) 신규 등록(backfill) 상한 N건 (2) 만료 임대 회수 (3) 동시 처리량만큼 선점 → 실행기에 넘김.
- 선점은 `UPDATE … SET status='RUNNING', lease_owner=?, lease_until=?, lease_token=lease_token+1, attempt=attempt+1 WHERE job_id=? AND (status='QUEUED' AND next_run_at<=NOW() OR status='RUNNING' AND lease_until<NOW())` — 영향 행 1이어야 실행. 여러 worker/인스턴스가 같은 행을 두 번 실행하지 못한다.
- 결과 저장은 전부 `WHERE job_id=? AND lease_token=?`. 늦게 돌아온 이전 임대의 결과는 0행이라 버려진다. 구간 저장도 같은 트랜잭션에서 job의 토큰을 확인한 뒤 한다.
- CONTENT 작업: 단위 → 청크(문자 예산, 겹침 1단위) → 청크마다 모델 1회 → 구간 UPSERT(dedupe_key = 정규화 제목 + 단위 범위) → `checkpoint_json.completedChunks`에 기록. 실패 시 완료 청크는 다시 부르지 않는다. 예산·재시도 상한에 걸리면 `PARTIAL`(읽은 범위 기록).
- 오류 정책: 401/403 → 작업 UNAVAILABLE + 전역 쿨다운(설정) · 429 → next_run_at 뒤로(지수) · 타임아웃/5xx → 최대 3회 · JSON 파싱 실패 → 1회 재시도 후 FAILED · 추출 실패 → 작업 만들지 않음(자료 상태로 표시).
- 사용자별 하루 작업 상한(설정)과 `ai_usage_logs`(feature=MATERIAL_ANALYSIS_JOB) 기록. 로그에는 material_id·청크 번호·토큰 수만 남기고 원문·파일명은 남기지 않는다.
- LINK 작업은 CONTENT DONE/PARTIAL 뒤에 (material, course) 링크마다 만든다. 프로젝트 연결이 나중에 생기면 그때 LINK만 추가된다(원문 분석 재사용). 실행 시점에 링크·자료·프로젝트가 아직 유효한지 다시 확인하고, 아니면 취소.
- 유효성 재확인: 적용(proposal apply)·조회·저장 모두 자료 ACTIVE·링크 존재·프로젝트 ACTIVE를 본다. 삭제/해제/보관 뒤 도착한 결과는 버린다. 확정 과제·진도·백필 링크는 원본 삭제와 함께 지우지 않는다.

## 4. 계획 입력 (`plan/PlanCandidateAssembler` — 공통 계약)

두 경로(AI 기본, V1)가 같은 후보 집합을 쓴다.
- 후보 = 과목의 ACTIVE topic 전부에서 (a) 진행 중 (b) 첫 미학습(주차 무관, 가장 이른 순서) (c) 현재 주차 창 (d) 확정 과제·마감이 연결된 항목 (e) 사용자가 지정한 자료의 항목. KNOWN/DEFER는 후보에서 뺀다(요청의 `excludeTopicIds`는 이번 회차에만). 상한은 과목당 개수가 아니라 전체 문자 예산이고, 자르는 순서는 (a)(b)(d)가 먼저 남는다.
- 항목마다 연결된 자료 구간(`topic_material_links` → `material_sections`)의 역할·표시 제목·수행 내용·짧은 발췌를 `[sN]` 인용 번호로 준다. 프로젝트 자료 중 아직 연결되지 않은 구간(과제 후보 포함)은 "미연결 구간"으로 따로 준다. 분석이 끝나지 않은 자료는 `pendingMaterials`로 응답에 실어 화면이 "아직 반영되지 않은 자료 N개"를 보이게 한다.
- 모델은 `refIds`로 제공된 구간만 인용한다(기존 provenance 규칙 그대로). 서버가 소유권·존재·프로젝트 범위·해시를 대조한다.
- 과제는 `[확정 과제]`(제목·마감·완료 여부)와 `[확인 전 과제 후보]`를 구분해 준다. 완료된 과제는 후보에서 빠진다. 추정 마감은 HARD 마감으로 쓰지 않는다(deadlineAt을 붙이지 않고 설명에만).
- 사용자 선호는 `user_contexts` ACTIVE 행을 그대로 준다. 서버 if문으로 "문제 먼저"를 강제하지 않는다.
- V1 재생성: `applyUserMarks`가 USER_MARK 근거만 있는 SKIP은 표식이 사라지면 FULL로 되돌린다(「다시 포함」).

## 5. 화면 (`diary-ui`)

- 자료함 목록: 분석 상태 칩(대기/분석 중/일부 완료/완료/재시도/사용 불가/일시중지) + 상태 요약 줄(일시중지/재개). 폴링은 진행 중인 작업이 있을 때만 8초 간격, 화면을 떠나면 멈춘다.
- 프로젝트: "확인할 내용 N개" 카드(과제인가요? → 마감) · 과제 목록(체크 한 줄) · 접힌 "자료 정리 변경안" 요약(연결 n곳 · 새 항목 n개 · 이동/이름/병합/분할은 요약에 표시) → 적용/제외.
- 계획 초안: 항목별 [간단히 · 자세히] 전환(전역 기본 간단히), 자세히는 `plan_item_details`를 lazy 생성, 시간·마감·선택 상태 불변. "아직 반영되지 않은 자료" 안내. 근거 화면은 기존 `planEvidence` 그대로.
- 오늘: 마감이 오늘/지난 미완료 과제를 같은 원본(`course_assignments`)에서 읽어 한 줄로. 별도 Todo 생성 없음.

## 6. 진행 기록

(작업하면서 채운다)
