# diary-app 문서

이 문서는 diary-app의 기획, 기능 구조, 계획 시스템, 요구사항, DB 설계, MVP 범위와 변경 이력을 관리하기 위한 Git 추적용 문서이다.

## 프로젝트 문서 맵

프로덕트의 기획 의도부터 상세 설계까지 아래 순서대로 읽는다.

| 순서 | 분류 | 문서 링크 | 설명 |
|---:|:---:|---|---|
| 1 | 기획 | [01-product-plan.md](01-product-plan.md) | 앱의 최종 방향, 1차 사용자, 성공 기준, UX 원칙 |
| 2 | 기획 | [02-feature-structure.md](02-feature-structure.md) | 기록 / 정리 / 실행 / 분석 계층 구조 |
| 3 | 기획 | [03-planning-system.md](03-planning-system.md) | DailyPlan / ScheduleBlock / Todo 역할 분리 |
| 4 | 설계 | [04-requirements.md](04-requirements.md) | 상세 기능별 구현 요구사항 |
| 5 | 설계 | [05-database.md](05-database.md) | 테이블, enum, 인덱스 설계 메모 |
| 6 | 로드맵 | [06-mvp-roadmap.md](06-mvp-roadmap.md) | 1차-A부터 1.5차까지 MVP 릴리스 단위 |
| 7 | 메모 | [07-ideas.md](07-ideas.md) | 아이디어 주차장 / 미확정 아이디어 기록 |
| 8 | 설계 | [08-today-execution-loop.md](08-today-execution-loop.md) | Today 화면 중심 실행 루프, Todo/ScheduleBlock 역할, 1차-A 사용자 입력 범위 정리 |
| 9 | 회귀 기록 | [09-ai-consultation-regression-cases.md](09-ai-consultation-regression-cases.md) | AI 상담(AUTO)에서 발견한 실제 판단 실패 사례와 기대 행동을 자연어로 보존하는 회귀 기준 |
| 10 | 제품 경험 | [10-core-experience.md](10-core-experience.md) | 자료·상태·AI 대화가 계획 초안과 실제 실행으로 이어지는 핵심 사용자 경험과 컨텍스트 경계 |
| 11 | 제품 가설 | [12-product-thesis.md](12-product-thesis.md) | 무엇을 만드는 것이고 무엇은 아닌지 — 4층 구조, 현실층 갱신 원칙, 확장 순서 |
| 12 | 설계 | [../api-spec.md](../api-spec.md) | 마크다운 기반 수동 API 명세 |
| 13 | 설계 | [../openapi.yaml](../openapi.yaml) | OpenAPI(OAS) 표준 API 스펙 |
| 14 | 이력 | [99-changelog.md](99-changelog.md) | 확정 변경 이력 |

---

## 관련 저장소

* **Frontend UI:** [GitHub - diary-ui](https://github.com/qlqb/diary-ui) (React)

## 문서 수정 기준

- 앱 방향이 바뀌면 `01-product-plan.md`를 수정한다.
- 기능 계층이나 기능 위치가 바뀌면 `02-feature-structure.md`를 수정한다.
- DailyPlan / ScheduleBlock / Todo 구조가 바뀌면 `03-planning-system.md`를 수정한다.
- 실제 구현 조건이 바뀌면 `04-requirements.md`를 수정한다.
- 테이블, 컬럼, enum, 인덱스가 바뀌면 `05-database.md`를 수정한다.
- MVP 범위나 확장 순서가 바뀌면 `06-mvp-roadmap.md`를 수정한다.
- 미확정 아이디어는 `07-ideas.md`에 기록한다.
- Today 화면 중심 실행 루프와 Todo/ScheduleBlock 역할이 바뀌면 `08-today-execution-loop.md`를 수정한다.
- AI 상담(AUTO)에서 실제로 판단이 틀린 사례를 발견하면 `09-ai-consultation-regression-cases.md`에 사례로 남긴다(정확한 문장을 고정하는 것이 아니라 기대 판단/행동을 기록한다).
- 자료 공간, 컨텍스트, AI 초안, 사용자 승인, 일정/오늘/기록으로 이어지는 제품의 핵심 경험이 바뀌면 `10-core-experience.md`를 수정한다.
- 무엇을 만드는 것이고 무엇은 아닌지(제품 가설, 4층 구조, 현실층 갱신 원칙)가 바뀌면 `12-product-thesis.md`를 수정한다.
- 아이디어가 확정되면 해당 문서와 `99-changelog.md`에 반영한다.
- 중요한 결정은 확정 변경 이력인 `99-changelog.md`에 기록한다.
