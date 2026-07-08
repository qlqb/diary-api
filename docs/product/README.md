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
| 9 | 설계 | [../api-spec.md](../api-spec.md) | 마크다운 기반 수동 API 명세 |
| 10 | 설계 | [../openapi.yaml](../openapi.yaml) | OpenAPI(OAS) 표준 API 스펙 |
| 11 | 이력 | [99-changelog.md](99-changelog.md) | 확정 변경 이력 |

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
- 아이디어가 확정되면 해당 문서와 `99-changelog.md`에 반영한다.
- 중요한 결정은 확정 변경 이력인 `99-changelog.md`에 기록한다.
