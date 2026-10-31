# MAP — 무엇을 알려면 어디를 보나 (diary-api)

docs/context는 압축 요약이다. 깊이가 필요하면 아래 원문을 하나씩 첨부한다.
[ARCHITECTURE](ARCHITECTURE.md) · [DOMAIN](DOMAIN.md) · [SCHEMA](SCHEMA.md) · [API](API.md) · [CONVENTIONS](CONVENTIONS.md) · [DECISIONS](DECISIONS.md)

## docs/product — 제품 설계 원천 (21장)

| 장 | 이걸 알고 싶으면 |
|---|---|
| 01 제품 개요 | 한 줄 정의·6탭 화면 구조·핵심 데이터·AI 원칙 |
| 02 기능 구조 | 탭별 책임, 상담 패널, 하루 시나리오, 화면↔데이터 |
| 03 실행 조각·제안 적용 계약 | ExecutionItem 배치·기록·조정 액션, AI 제안 적용, Timefold 배치 |
| 04 Requirements | REQ 번호별 요구사항(상담·장기 기억·계획·실행) |
| 05 Database | 표 정의·관계·version·트랜잭션 경계·마이그레이션 절별 설명 |
| 06 Implementation Roadmap | 옛 단계별 로드맵(보관 표시, 갱신 안 함) |
| 07 아이디어 주차장 | 미확정 아이디어·후속 작업(예: 로컬 ObjectMapper 정리) |
| 08 Today/일정 실행 루프 | 오늘·일정 화면 경계, 결과 입력, 남은 오늘 다시 짜기 |
| 09 AI 상담 회귀 사례 | CASE별 잘못된 상담 판단과 기대 동작 |
| 10 핵심 경험·컨텍스트 구조 | 주제별 컨텍스트, AI가 읽는 것 vs 상태를 바꾸는 것 |
| 11 기간형 계획 | plan_versions, 초안·확정·회고·롤링 배치, 생성 단계 |
| 12 제품 가설 | 4층 구조, 현실층 갱신 원칙, 확장 순서 |
| 13 계획 판단층 | 근거 등급→취급 상한, 판단·되묻기 규칙 |
| 14 이미지 일정 가져오기 | 근무표·시간표 이미지 → 일정 후보, 검증 |
| 15 자료 자동 분석 | 추출·구간·분석 작업·토픽 연결·정리안·ZIP·한도 |
| 16 학습 흐름 | 모르는 과목 판단, 실행 작업 공간, 실제 수업 정정 |
| 17 교재 목차 웹 검색 | 교재 단서·basis·외부 조회·목차 선택·변경안 |
| 18 학습 기억 | user_contexts 저장 규칙, 확인된 상태 블록 |
| 19 상담 교재 사진 | 사진 업로드·단원 추정·계획 활용 |
| 20 학습 이벤트 | 원천(L1)·이벤트 로그(L2)·쓰기 경로·수업 범위 |
| 21 사용자 의도 보존 | 합의 주체·근거·승인, 요청 범위, 말한 시간 |

그 밖: `README.md`(문서 목록·읽는 순서), `99-changelog.md`(날짜별 결정 원문 — 색인은 DECISIONS.md).
상담 도구·변경 묶음(2026-10-26)은 product 기준 문서가 아직 없다 → `docs/handoff/consult-tools-2026-10-26.md`.

## 다른 원문

- `docs/api-spec.md` — 계약 의미·날짜별 추가 필드 / `docs/openapi.yaml` — springdoc 전체 스키마(크다)
- `docs/sql/YYYY-MM-DD-*.sql` — 표 DDL·되돌리기 / `docs/handoff/` — 기능별 계획·작업·검증 기록(색인 `README.md`)
- `scripts/ai-baseline/` — 실모델 검증 스크립트 / `.github/workflows/ci.yml` — CI

## 코드에서 찾기 (`src/main/java/com/jungwoo/project/memo/`)

| 주제 | 패키지 |
|---|---|
| 실행 조각·기록·이벤트 | `execution/` (`ExecutionItemService`) |
| 계획 초안·확정·근거 | `plan/` (`PlanDraftService`, `PlanConfirmService`, `provenance/`, `scope/`, `selection/`) |
| 배치·가용 시간 | `scheduling/` (`solver/SchedulingSolverService`) |
| 상담·제안·draft·묶음 | `ai/` (`AiConversationService`, `AiProposalService`, `draft/`, `change/`, `turn/`) |
| 자료·분석 | `material/` (`analysis/`, `extract/`, `zip/`, `batch/`) |
| 학습 항목·정리·이벤트 | `learning/` (`tidy/`, `events/`, `week/`) |
| 프로젝트·교재 | `course/` (`textbook/web/`) |
| 일정 | `routine/`, `commitment/`, `assignment/` |
| 공통 | `common/` (config·exception·security) / SQL은 `src/main/resources/mapper/<feature>/` |
