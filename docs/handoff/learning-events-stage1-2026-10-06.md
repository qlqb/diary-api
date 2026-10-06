# 이벤트 기반 학습 진도 1단계 — 작업·검증 기록 (2026-10-06)

설계 `docs/product/20-learning-events.md`, 계획 `docs/handoff/learning-events-stage1-plan-2026-10-06.md`(v2.1).
브랜치: diary-api `feat/learning-events-a`(A) → `feat/learning-events-b`(B·C·E), diary-ui `feat/class-check`(D). 기준 dev-playground(api `8bd90c8`, ui `6135c79`).

## 1. 무엇을 했나

| PR | 내용 | 마이그레이션 |
|---|---|---|
| A | `learning_events`·`learning_event_origins`(판), `LearningEventWriter`(정규화 비교·출력 0 판·origin 먼저 upsert), `LearningEventValidator`, `LearningEventLog`(숨김은 SQL에서), 조회 API, `textbook_refs`·별칭(`TextbookRefService`·시드) | `2026-10-06-learning-events.sql` |
| B | 쓰기 경로 연결: 실행 완료·일부·회고(원천 고정, 전환 전 기록은 백필 규칙), 토픽 진도·표식, 기억(과목 단위 동기화 — 행의 지금 상태만으로 출력), 해결(RESOLUTION origin + 해결 연결), 자기 점검(과목 잠금을 첫 읽기로), 범위 제외·CLASS(주차 진술), 사진 턴(성공한 턴만), 자료 연결(RELEASED), 옛 기억 후보 적용, 자료 삭제 시 구간 제목 정리 | (A 표 사용) |
| C | `class_sessions`(루틴·원래 날짜), 수업 확인 API(pending·confirm·끄기), 일정 예외 → 휴강·이동 이벤트 | `2026-10-06-class-sessions.sql` |
| D | 화면: 오늘 카드(가장 최근 회차, 나중에=하루), 프로젝트 "이번 주 수업 확인"(모두 맞아요·끄기) | — |
| E | 백필(`LearningEventBackfill`): 전환 전 원본, 한 건 = 한 트랜잭션 + 진행 위치, lease, 실시간이 먼저 쓴 origin 건너뜀, 미대응 보고 | `2026-10-06-learning-event-backfill.sql` |

## 2. 계획과 다르게 정한 것

- **목차 항목 검증(A):** 새 참조는 "그 과목 토픽 중 그 열쇠(SET)를 가진 것이 있고 토픽의 교재 키가 그 ref의 별칭"으로 본다. 1단계의 TOC_ENTRY는
  모두 목차 토픽에서 나온다(구조화 항목 직접 검증은 문제→교재 연결이 생기는 3단계에서).
- **STALE 기억은 살아 있는 것으로 본다(B):** 18번이 STALE을 살아 있는 목록에 싣는 것과 같게. 옛 후보 MARK_STALE은 출력을 내리지 않는다.
- **해결 기억을 고칠 때(B, 리뷰 반영):** 연결을 항상 새 행으로 옮긴다. 새 행이 해결이 아니면 해결 이벤트만 내려가고 막힘은 STUCK을 유지한다.
- **수업 확인 끄기(C):** `PATCH /api/courses/{id}` 대신 `PATCH /api/courses/{id}/class-prompt` — 과목 수정 요청의 다른 칸과 섞지 않는다.
- **예외 삭제도 루틴을 잠근다(C):** 기존에는 "삭제는 부모를 잠그지 않는다"였다(RoutineServiceTest 갱신). 지우기 전에 휴강 표시를 내리는 것을
  같은 날 회차 생성과 직렬화하기 위해서다.
- **백필 `onlyUser`(E):** 테스트가 한 사용자만 돌리게 하는 인자. 운영은 null(전부)로만 돈다.
- **교재 식별 운영 경로(A):** 목차 토픽을 만드는 경로에 ref를 붙이지 않고, 이벤트를 쓸 때 토픽의 교재 키로 ref를 찾거나 만든다(`refFor`).
  같은 책의 키 변경은 `CourseTextbookWriter`가 이전 키의 ref를 잇는다.

## 3. 리뷰

| 대상 | 결과 |
|---|---|
| 설계 | 1회차 REVISE 14 → 반영, 재리뷰 APPROVED + P2 4 반영 |
| 계획 | 1회차 REVISE 17 → 반영, 재리뷰 REVISE 9 → 8 반영·1 일부 기각(자료 연결 잠금 — 교착 위험, 설계상 이벤트 유지) |
| 코드 A | 1회차 WARNING 6·SUGGESTION 2 → 반영, 재리뷰 WARNING 1(ensureBook 재귀) → 반영 |
| 코드 B | 1회차 CRITICAL 1·WARNING 5·SUGGESTION 1 → 6 반영, 1 보류(과목 단위 동기화 범위 축소 — 2단계에서 측정 후) |
| 코드 C·E | 1회차 14건 반영 → 재리뷰 4건 반영(재리뷰는 1회로 끝, 반영분은 자체 재검토) |
| 코드 D | 9건 반영 → 재리뷰 3건 반영 |

B 1회차 반영 내용: 해결이 승계한 막힘의 원천은 기존 참조(연결 해제 뒤 해결 저장이 롤백되지 않게), 사라진 해결 관계도 출력 0으로, 동기화가 읽는
원본·출력·origin·해결 연결은 공유 잠금 읽기(과목 잠금 뒤 최신 값) + 옛 후보 적용은 READ_COMMITTED, 기존 참조에 payload 원천 포함, 전환 전 시험
범위는 원천이 없으면 만들지 않음, 자료 삭제 시 구간 제목·라벨 정리.

### 3.1 C·E·D 리뷰

- **C·E 1회차(14건 반영):** 수업 확인은 READ_COMMITTED·요청 전체 롤백, 재전송 비교에서 확인 시각 제외, 다른 과목으로 기록된 회차 제외,
  예외 삭제 시 기록 당시 과목으로 출력 0(삭제도 루틴 잠금), 보강 시각 저장, 자료 유형·요청 모양 검증, 시간대 설정, CI 제외 목록, 백필 이벤트·
  진행 위치 원자성과 유효 lease에서만 완료.
- **C·E 재리뷰(4건 반영):**
  - 실행 완료가 학습 이벤트(교재 별칭)를 쓴 뒤 진도 행을 갱신해, 토픽 경로·백필(진도 → 별칭 → origin)과 잠금 순서가 거꾸로였다 →
    완료 리스너(진도) 다음에 이벤트를 쓴다. 순서는 단위 테스트로 고정(동시 실행 회귀 테스트는 두지 않음).
  - 해결 연결 복원이 수정 사슬을 20단계에서 조용히 잘랐다 → 끝까지 따라가고, 순환이면 잇지 않고 경고 로그. 25번 고친 사슬 테스트.
  - 기억·정정 백필 보고에 미대응 수가 없었다 → 전환 전 살아 있는 원본 행 중 판 0인 행 수를 `unmapped`로(막힘에 이어진 해결 기억 제외).
  - API 문서 예시의 `from`·`to` 제거(1단계는 구간·자료 단위만).
- **자체 재검토에서 추가:** 해결 연결 복원이 동기화마다 모든 해결 기억의 사슬을 다시 따라갔다 → 이미 연결된 막힘은 시작점 조회에서 뺀다.
- **D(9건 + 재리뷰 3건 반영):** 불러오기 실패·늦은 응답 구분, 저장 중·불러오는 중 잠금, 렌더 중 `Date.now()` 제거 등.

## 4. 테스트

- API 전체 `./gradlew test`(DB 포함, memo_test에 세 마이그레이션 적용) — 마지막 실행(C·E 재리뷰 반영 뒤) 1,621건 통과, 건너뜀 2.
- 새 DB 테스트(CI 제외 목록 51개): `LearningEventWriterDbTest`, `TextbookRefDbTest`, `LearningEventsFlowDbTest`, `ClassSessionDbTest`,
  `LearningEventBackfillDbTest`. 단위: `EventOutputsTest`, `LearningEventViewSerializationTest`.
- UI `npx vitest run` 806(`ClassCheck.test.jsx` 16 포함), `npm run build`, 바꾼 파일 eslint.
- 화면을 브라우저에서 직접 확인하지는 못했다(격리 DB 로그인 제약, 이전 작업과 같음) — 컴포넌트 테스트로 대신했다.

## 5. 운영 반영 전에 할 일(사용자)

1. 백업 뒤 마이그레이션 세 개를 순서대로(learning-events → class-sessions → learning-event-backfill).
2. 새 서버를 띄우면 `cutover_at`이 기록되고 교재 식별자가 시드된다.
3. 옛 서버가 모두 내려간 것을 확인하고 10분 뒤, `learning.events.backfill.enabled=true`로 한 번 띄워 백필 → `learning_event_backfill.report` 확인.

## 6. 하지 않은 것

2단계 뷰(수업 진도·내 상태·시험 범위·예측), `source_links`·`syllabus_weeks`, 계획서·교재 기반 기본값, 몰아 올림 "어디까지 들었나" 화면, 문제지 문항.
