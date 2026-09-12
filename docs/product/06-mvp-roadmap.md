# 06. Implementation Roadmap

이 문서는 현재 레거시 구현에서 최신 제품 구조로 이동하는 순서를 정한다. 기능을 없애는 목록이 아니라, 같은 원본 데이터로 화면과 AI 흐름을 연결하는 순서다.

## 1. 현재 출발점

현재 구현에는 다음 자산이 있다.

- Spring Security + JWT 사용자 인증
- 일기 CRUD와 통계
- Todo CRUD
- ScheduleBlock 생성·조회·완료·이동·축소·보류 액션
- `plan_item_events` 조정 기록
- React의 TodayView, ExecutionView, TimetableView

현재 문제는 기능 부족보다 제품 문서와 데이터 책임이 최신 기획과 어긋난다는 점이다.

## 2. 0단계: 계약 고정

완료 기준:

- 오늘·계획·실행·기록 탭 책임이 문서마다 같다.
- PlanItem / ExecutionItem / ExecutionRecord / ExecutionItemEvent 역할이 같다.
- AIProposal과 ContextItem 승인 규칙이 요구사항과 DB 문서에 반영돼 있다.
- 현재 구현과 목표 구조가 구분돼 있다.

## 3. 1단계: 실행 데이터 통합

작업:

1. `daily_states`, `plan_items`, `context_items` 생성
2. `execution_items`, `execution_records`, `execution_item_events` 생성
3. `ai_proposals`, `ai_proposal_items` 생성
4. 레거시 데이터를 신규 구조로 복사
5. 보정 내역과 ID 매핑 검증
6. ContextItem의 DECISION/INSIGHT, 유효 기간, 교체·철회 DDL 확인
7. MyBatis 조회·수정을 신규 테이블로 전환
8. 기존 Todo/ScheduleBlock 쓰기 중단

완료 기준:

- 기존 Todo와 ScheduleBlock이 누락 없이 ExecutionItem으로 조회된다.
- 완료 사실은 ExecutionRecord로 확인된다.
- 이동·축소·보류 이력은 ExecutionItemEvent로 확인된다.
- 신규·레거시 이중 쓰기가 없다.

## 4. 2단계: 실행 액션 계약

> **구현 상태(2026-08-04)**: 완료·재열기·이동·축소·보류·삭제는 아래 API로 구현되어 있다.
> 부분 수행(PARTIAL)·미수행(NOT_DONE)·재개(RESUMED)·취소(CANCELLED) 액션은 아직 구현하지 않았다.
>
> ```text
> GET    /api/execution-items?date=
> GET    /api/execution-items/pending?beforeDate=
> POST   /api/execution-items
> POST   /api/execution-items/{id}/complete
> POST   /api/execution-items/{id}/reopen
> POST   /api/execution-items/{id}/move
> POST   /api/execution-items/{id}/reduce
> POST   /api/execution-items/{id}/hold
> DELETE /api/execution-items/{id}
> ```
>
> Today 화면(TodayView)이 이 API로 execution_items를 실제로 읽고 쓴다. schedule_blocks에는 더 이상 쓰지 않는다.
>
> 2026-08-05부터 AI 오늘 제안은 1회성 강제 생성이 아니라 실제 상담 대화다:
> `POST /api/ai/conversations`, `GET/POST /api/ai/conversations/{id}/messages`(POST는 SSE
> 스트리밍), `GET /api/ai/proposals/{id}`, `POST /api/ai/proposals/{id}/apply`. 자유 대화 →
> 목표·제약 파악 → (정보가 충분하지만 요청 전이면) 초안 생성 여부 확인(OFFER) → 사용자 요청
> 또는 동의 시에만 구조화된 후보(1~5개, DATE_ONLY 또는 TIME_FIXED) 생성(PROPOSAL) → 사용자
> 편집·제외 → 묶음 전체 단일 트랜잭션 적용까지 지원한다. 기존 항목 수정 제안은 여전히 후속
> 범위다. AI가 비활성화된 상태(AI_PROVIDER=none 또는 키 없음)에서도 서버는 정상 부팅하며
> 상담 호출은 503을 반환한다.
>
> 2026-08-06부터 PROPOSAL 항목은 `placementType=UNSCHEDULED`(특정 날짜에 묶이지 않은 후보)도
> 지원한다. `POST/GET /api/ai/proposals/{id}/schedule-preview`가 가용시간을 추정하고 Timefold
> Solver로 최대 7일 범위에 배치한 뒤 미리보기를 저장한다(둘 다 OpenAI를 호출하지 않는다).
> 사용자가 가용시간 예외를 고치거나 항목을 고정하면 Timefold만 다시 돈다. 최종 승인은 기존
> `POST /api/ai/proposals/{id}/apply`를 그대로 쓴다(항목별로 다른 날짜를 지정하도록 확장).
> 자세한 흐름은 `03-planning-system.md` §12, 요구사항은 `04-requirements.md` REQ-SCHEDULING-*.

작업:

- 완료, 부분 수행, 미수행
- 이동, 축소, 보류, 재개, 취소, 삭제
- version 증가와 사용자 소유권 검증
- Record/Event와 상태 변경의 트랜잭션 처리

완료 기준:

- DONE인데 완료 Record가 없는 상태를 만들 수 없다.
- PARTIAL이면 남은 ExecutionItem이 반드시 존재한다.
- 오래된 version으로 수정하면 409를 반환한다.
- 각 액션의 전후 상태가 Event에 남는다.

## 5. 3단계: 리스트에서 제안 미리보기

구현 순서의 첫 화면은 리스트다.

작업:

- TodayView와 ExecutionView를 ExecutionItem 계약으로 연결
- AI가 만든 신규/변경/삭제 후보를 ghost/diff로 표시
- 항목별 편집·적용·무시
- 적용 전 공식 상태와 제안 상태 분리

완료 기준:

- 새로고침 후에도 PROPOSED 제안이 유지된다.
- 사용자가 직접 바꾼 값이 AI 재응답으로 사라지지 않는다.
- 일부 항목만 적용할 수 있다.

## 6. 4단계: 주간 시간표 연결

기존 TimetableView를 버리지 않고 같은 diff 계약을 연결한다.

작업:

- TIME_FIXED와 DATE_ONLY 시각 표현 분리
- 이동 전후 블록 ghost 표시
- 충돌·빈 시간·현재 시각 표시
- Today 리스트와 동일 ExecutionItem 동기화

완료 기준:

- 리스트에서 승인한 변경이 시간표에 즉시 반영된다.
- 시간표에서 직접 고친 값이 제안 payload에도 반영된다.
- 1536x760 viewport에서 핵심 화면과 AI 패널이 함께 보인다.

## 7. 5단계: 계획 작업 공간

작업:

- 자연어 요청을 PlanItem 초안으로 변환
- 외부 ChatGPT 대화 텍스트/파일 가져오기
- 기간·목표·제약·우선순위 편집
- 날짜별·주차별·단계별·월별 그룹 보기
- PlanItem에서 ExecutionItem 후보 생성
- 계획 적용 전 변경 결과 미리보기

완료 기준:

- 사용자가 AI 없이도 PlanItem과 실행 조각을 수정할 수 있다.
- AI 상담으로 같은 초안을 다시 조정할 수 있다.
- 적용한 항목만 오늘·실행에 나타난다.

## 8. 6단계: 장기 컨텍스트

작업:

- 상담에서 ContextItem 후보 추출
- 확인 상태와 수명주기 편집
- PlanItem/ExecutionItem 연결
- 다음 상담에 관련 컨텍스트 주입
- 기존 컨텍스트 충돌 경고

완료 기준:

- AI 추정과 사용자 확정이 구분된다.
- 보관한 컨텍스트는 다음 계획의 현재 제약으로 사용되지 않는다.
- 교체·철회·유효 기간이 지난 컨텍스트는 다음 계획에서 제외된다.
- 실행 조각에서 생성 이유를 확인할 수 있다.

## 9. 7단계: 기록과 피드백 루프

작업:

- 하루·주·월·학기·방학·연간 기록 보기
- Record와 Event 기반 회고
- AI 제안 원안 적용·수정 적용·무시 비율
- 예상 분량과 실제 수행 차이
- 반복 이동·축소·부분 수행 패턴

완료 기준:

- 계획과 실제를 같은 값으로 착각하지 않는다.
- 다음 AI 제안에 집계된 실제 패턴이 사용된다.
- 사용자가 원문 기록을 자동 전송하지 않아도 피드백 루프가 성립한다.

## 10. 8단계: 대학생 사용 사례

작업:

- 과목과 강의계획서 등록
- AI 추출 확인
- 학기/방학 운영 기간
- 수준·범위·우선순위 수정
- 과목 계획을 PlanItem과 ExecutionItem으로 변환

대학생 기능은 공통 계획·실행 구조를 사용하는 첫 사용 사례이며 별도 실행 원본을 만들지 않는다.

## 11. 9단계: 캘린더

캘린더는 리스트와 주간 시간표의 데이터·diff·승인 흐름이 안정된 뒤 구현한다.

완료 기준:

- 캘린더가 별도 상태 저장소가 아니다.
- Today·Execution·Timetable과 같은 ExecutionItem을 사용한다.
- 제안 미리보기와 공식 상태 구분이 유지된다.

## 12. 검증 순서

각 단계에서는 관련된 최소 검증만 수행한다.

- 문서: 용어, 상태값, 관계, 구현 순서 일치
- 데이터: 이전 건수, 누락, 중복, 소유권, 시간 범위
- 서비스: 트랜잭션과 version 충돌
- 화면: 직접 편집, AI 편집, 부분 적용, 새로고침 유지
- 통합: 상담 → 초안 → 적용 → 오늘/실행 → 기록 → 다음 상담

## 13. 지금 보류하는 항목

- 리스트·시간표 검증 전 캘린더 신규 구현
- AI의 사용자 확인 없는 자동 적용
- ContextItem 자동 확정
- Kafka, Redis 등 현재 필요가 입증되지 않은 인프라
- 소셜·커뮤니티 기능
- 실제 금융 계좌 연동
- 같은 데이터를 별도 탭별 테이블로 복제하는 구조
