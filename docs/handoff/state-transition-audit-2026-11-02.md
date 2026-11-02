# 상태 전이 동시성 감사 (2026-11-02)

상태 열을 바꾸는 UPDATE가 동시 요청에 대해 보호되는지 전수로 본 기록이다. 코드는 고치지 않았다. 고칠 일은 `docs/product/07-ideas.md`
「확인된 결함·후속 작업」에 있고, 규칙은 `docs/context/CONVENTIONS.md` 「상태 전이」다. 판정은 코드를 읽어 내린 것이고
**동시 요청을 실제로 재현한 것은 없다** — "추론"이라고 적은 칸은 특히 그렇다.

> 후속(2026-11-02 같은 날): 급한 순서 1~3(옛 초안 재생성, 자료 분석 apply·dismiss, ZIP 가져오기 상태)은 고쳤다 — 현황은 07번.
> 아래 표는 감사 당시의 판정이다. ZIP은 감사 때 "경합"으로 봤지만, 대기 항목이 남은 채 취소하면 작업자가 그 항목을 실패 처리한 뒤
> `finishIfDone`이 CANCELLED를 덮어 **경합이 아니어도 매번** 일어나는 문제였다.

## 대상을 고른 방법

- 매퍼 `<update>` 중 SET에 상태 열(`status`, `*_status`, `*_state`)이 있고 WHERE에 `status`·`*_state`·`version`·`revision`·`lease_token`이
  없는 것 22곳 + 상태를 쓰는 `INSERT … ON DUPLICATE KEY UPDATE` 2곳. 처음 기계 집계(상태 열을 `status`로만 봄)는 20곳이었다.
- **상태가 아닌 열로 거는 가드를 기계 집계는 못 본다.** 예: `ZipImportEntryMapper.xml` `markDone`의 `WHERE … AND material_id IS NULL`
  + 서비스의 `if (updated != 1) throw`. 조건부 UPDATE의 교과서적 형태인데 상태 열이 아니라서 후보에 걸렸다. 같은 모양이 다른 곳에도 있을 수 있다.
- 판정 단위는 **(문장, 호출 경로) 쌍**이다. 같은 문장이 경로마다 다르게 판정된다(예: 자료 분석 `updateStatus`의 apply·dismiss).

## 등급과 표시

| 등급 | 뜻 |
|---|---|
| 보호됨(문장) | 문장 자체의 WHERE 조건·가드(+행 수 확인)나 멱등 upsert. 호출자가 무엇을 하든 안전하다 |
| 보호됨(호출 경로) | 같은 트랜잭션에서 경쟁하는 모든 경로가 같은 행을 먼저 잡는다(그 행의 `FOR UPDATE`, 부모 행 `FOR UPDATE`, 조건부 선점, 임대 토큰). **지금 호출자가 모두 그렇게 해서 안전하다 — 다음 호출자가 잡지 않으면 뚫린다** |
| 보호 없음 · 무해 | 보호는 없지만 멱등이고 다른 쓰기도 멱등·조건부라 동시 요청의 결과가 같다 |
| 보호 없음 · 상태 꼬임 | 늦게 온 쪽이 덮어쓴다. 사용자가 본 결과와 저장된 상태가 달라질 수 있다 |
| 보호 없음 · 중복 부수효과 | 같은 트랜잭션의 다른 표 쓰기가 두 번 일어난다 |

표시(등급과 다른 축): **도달 없음**(호출자 없음) · **기본 꺼짐**(설정으로 막힘 — 켜는 날 그대로 살아난다) · **화면 미사용**(서버 경로는 있으나 diary-ui가 부르지 않음).

## 보호 없음 — 급한 순서

| 순 | 자리 (문장 / 경로) | 등급 · 표시 | 근거 |
|---|---|---|---|
| 1 | `AiProposalMapper.xml:64` `updateStatusAndRespondedAt` / `PlanDraftService.regenerateItems` → `persist(…, proposalId)` → `persistInTransaction` | 상태 꼬임 | 잠금 없는 읽기(`PlanDraftService.java:259`) → PROPOSED 확인(`:273`) → **모델 호출** → 옛 초안을 조건 없이 DISMISSED(`:494`). 창 안에 모델 호출이 있어 가장 넓다. 그 사이 확정하면 APPLIED가 DISMISSED로 덮인다(추론). 요청 맥락이 없는 옛 초안만 이 경로로 온다(`:268`). 같은 파일의 `persistSuperseding`(`:363-367`)은 `FOR UPDATE` 후 PROPOSED를 확인한다 — 두 재생성 경로가 다르게 구현돼 있다 |
| 2 | `CourseMaterialAnalysisMapper.xml:68` `updateStatus` / `MaterialAnalysisService.apply` | 중복 부수효과 | `WHERE analysis_id = #{analysisId}`뿐, 매퍼 반환 `void`, 분석 행 잠금 없음. UPDATE **앞에서** course_topics·노트·courses(교재) 쓰기. 동시 apply 두 번이면 토픽·노트가 두 번 들어간다(추론) |
| 2 | 같은 문장 / `MaterialAnalysisService.dismiss` | 상태 꼬임 | apply와 겹치면 마지막에 커밋한 쪽이 이긴다(추론) |
| 3 | `ZipImportMapper.xml:48` `updateStatus` / 스케줄러 `finishIfDone`(`MaterialZipImportScheduler.java:70·93` → `ZipImportTxService.java:80`) | 상태 꼬임 | 항목 개수만 보고 COMPLETED/PARTIAL/FAILED를 써서 사용자가 취소한 가져오기(CANCELLED)를 완료로 뒤집을 수 있다(추론) |
| 3 | 같은 문장 / `confirm`·`retryEntry`·`cancel`(`MaterialZipImportService.java`)·`expire`(`ZipImportTxService`) | 상태 꼬임 | 상태를 메모리에서 보고 조건 없이 쓴다. `confirm`의 IMPORTING과 `expire`의 EXPIRED가 서로 덮을 수 있다(추론). 함께 부르는 항목 쓰기는 조건부라 중복은 없다: `queueSelected`(`status IN ('PENDING','FAILED')`), `resetZipItemForRetry`(`upload_state IN (…)`), `reopenBatchOfItem`(`b.status = 'FINISHED'`). 자료 중복은 `markDone` 가드가 막는다 |
| 4 | `StudyRecommendationMapper.xml:49` `attachProposal` / `PlanningAgentService.createDraft` | 중복 부수효과 · 화면 미사용 | 읽기에 `FOR UPDATE` 없음(`StudyRecommendationMapper.xml:34-36`), 상태 확인은 메모리(`PlanningAgentService.java:113`). UPDATE **앞에서** 제안·항목 INSERT(`createFromItems`), **뒤에서** 미리보기 저장(`computePreview`). 두 번이면 제안이 둘, 추천엔 마지막 것만(추론) |
| 5 | `TopicProgressMapper.xml:35` `updateProgress` / `TopicService.updateProgressStatus` | 중복 부수효과(작음) | UPDATE **뒤에서** `topic_learning_events` STATUS_CHANGED INSERT(`TopicService.java:224`). 이력이 두 줄 생긴다. 뒤의 `learningEvents.progressChanged`는 판 모델이라 같은 출력이면 UNCHANGED |
| 5 | `MaterialAnalysisBatchMapper.xml:193` `updateStatus` / `MaterialAnalysisBatchService.touch` | 상태 꼬임(작음) | STAGED를 메모리에서 보고 조건 없이 UPLOADING(`:291-294`). 24시간 방치 정리(ABANDONED)와 겹치면 되살아날 수 있다(추론, 창이 작다) |
| — | `TopicChangeProposalMapper.xml:59` `updateStatus` / `TopicChangeProposalService.create` | 상태 꼬임 · **기본 꺼짐** | 잠금 없이 열린 안을 찾아 STALE로(`:87-90`) — 그 사이 적용된 안(APPLIED)을 덮을 수 있다(추론). `isLinkJobsEnabled()`가 false면 바로 반환(`link-jobs.enabled=false`) |
| — | `StudyRecommendationMapper.xml:45` `updateStatus` / `LearningRecommendationService.markSentToPlanning` | 상태 꼬임 · **도달 없음** | SUGGESTED를 메모리에서 보고 조건 없이 갱신. 호출자가 없다 |

## 보호 없음 · 무해

| 자리 | 근거 |
|---|---|
| `AiConversationMapper.xml:111` / `AiConversationService.deleteConversation` | ACTIVE → ARCHIVED 멱등, 뒤의 draft 취소는 `AND status = 'OPEN'`. 단서: 진행 중 턴이 삭제 뒤에 끝나면 보관 대화에 OPEN draft가 남을 수 있다(다른 작업과의 경쟁, 추론) |
| `CourseMapper.xml:138` / `CourseService.archive`·`restore` | 멱등, tidy 취소는 `status IN ('QUEUED','RUNNING')`. 보관과 복원이 겹치면 늦은 쪽(사용자의 상반된 요청) |
| `TextbookLookupMapper.xml:88` `markSuperseded` / `retry`·`supersede`(작업자) | 끝 상태로만, `lease_token + 1`이라 늦은 `finish`가 실패한다. `retry`가 두 번이면 새 INSERT를 `uk_textbook_lookups_open(course_id, open_guard)`가 막는다(두 번째 요청은 오류) |
| `TextbookWebMapper.xml:55` `updatePageFetch` / `WebEvidenceStore` | 캐시 메타(`last_fetch_status`). 리비전 INSERT는 `insertRevisionIgnore` + UNIQUE |
| `CourseMaterialMapper.xml:140` `markDeleted` / `MaterialTxService.markDeleted` | 같은 트랜잭션 쓰기가 전부 멱등·조건부: 작업 취소 `status IN ('QUEUED','RUNNING','PAUSED')`, 연결·본문 단위 DELETE, 구간 본문 비우기 |
| `ZipImportMapper.xml:58` `updateListing`, `:48` `updateStatus` / `saveListing`·`create` | 새 가져오기를 만드는 한 요청 안에서만 쓴다. 단서: PREPARING 중 취소하면 READY가 CANCELLED를 덮을 수 있다(3순위와 같은 계열, 추론) |

## 보호됨

| 자리 | 등급 | 근거 |
|---|---|---|
| `ZipImportEntryMapper.xml:87·93` `markFailed`·`markDone` | 문장 | `AND material_id IS NULL`, `completeEntry`는 `updated != 1`이면 롤백 |
| `CourseCorrectionMapper.xml:67` `upsertExclusion`, `TopicMaterialLinkMapper.xml:22` `upsert` | 문장 | `ON DUPLICATE KEY UPDATE`로 같은 결과 |
| `AiConversationDraftMapper.xml:59` `update` / `DraftPromotionService.applyTurn` | 호출 경로 | 턴 시작에 대화 행 `FOR UPDATE`(`AiTurnLifecycleService.java:85`), 완료 트랜잭션 맨 앞 `updateStatusIfCurrent(PROCESSING → COMPLETED)`가 1행이 아니면 중단(`:315-321`) |
| `AiProposalItemMapper.xml:83`, `AiProposalMapper.xml:64` / `AiProposalService.apply`·`dismiss` | 호출 경로 | `findByIdAndUserIdForUpdate` |
| `AiProposalMapper.xml:64` / `PlanDraftService.persistSuperseding` | 호출 경로 | `FOR UPDATE` 후 PROPOSED 확인(`:365-367`) |
| `StudyRecommendationMapper.xml:45` / `PlanningAgentService.apply` | 호출 경로 | 안쪽 `aiProposalService.apply`가 제안 행을 잠그고 PROPOSED가 아니면 거절 |
| `TopicChangeProposalMapper.xml:59` / `apply`·`dismiss` | 호출 경로 | `findByIdAndUserIdForUpdate` |
| `TextbookLookupMapper.xml:88` / `ensureInTx`·`link`·`choose`·`finishIfCurrent`·`afterClue` | 호출 경로 | courses 행 `FOR UPDATE`, 작업자 쪽은 `lockIfLeased` |
| `ProjectTidyMapper.xml:220` / `ProjectTidyWorker.save` | 호출 경로 | `lockOpenPrevious` + `supersedeProposalIfOpen(…) == 1` + 임대 |
| `TopicMaterialLinkMapper.xml:60·65` / `ConsultPhotoTxService.setTopic` | 호출 경로 | courses 행 `FOR UPDATE`(`READ_COMMITTED`) |
| `MaterialAnalysisBatchMapper.xml:179` / `bindUpload`·`failUpload` | 호출 경로 | `findItemForUpdate` |
| `TopicProgressMapper.xml:35` / `TopicService.recordExecutionCompleted` | 호출 경로 | 유일한 발행처 `ExecutionItemService.complete`가 version CAS(`updated != 1`이면 409) 뒤에 같은 트랜잭션 동기 리스너(`@EventListener`)로 부른다 |

"보호됨(호출 경로)" 자리는 그 근처를 고칠 때 WHERE 조건을 보태 "보호됨(문장)"으로 옮길 후보다.

## 알려진 공백

- INSERT로 같은 행이 두 번 생길 수 있는 자리, 읽어서 객체를 바꾸고 전체 열을 다시 쓰는 패턴은 **전수로 보지 않았다**. 조사 중 마주친 것만 확인했다
  (`uk_textbook_lookups_open`, 토픽 변경안 `DuplicateKeyException` 처리, draft 엔티티 통째 갱신).
- `@Transactional(isolation = READ_COMMITTED)` 자리는 세지 않았다. 판정에 쓴 것은 사진 경로 하나다.
- 상태가 아닌 열로 거는 가드를 기준으로 다시 집계하지 않았다.
