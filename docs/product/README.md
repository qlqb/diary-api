# diary-app 문서

이 문서는 diary-app의 기획, 기능 구조, 계획 시스템, 요구사항, DB 설계, MVP 범위와 변경 이력을 관리하기 위한 Git 추적용 문서이다.

## 읽는 순서

| 순서 | 파일 | 역할 |
|---:|---|---|
| 1 | `docs/01-product-plan.md` | 앱의 최종 방향, 핵심 포지션, UX 원칙 |
| 2 | `docs/02-feature-structure.md` | 기록 / 정리 / 실행 / 분석 계층 구조 |
| 3 | `docs/03-planning-system.md` | 연간 목표, 월간 계획, 주간 계획, 하루 계획, 시간 블록 |
| 4 | `docs/04-requirements.md` | 구현 요구사항 |
| 5 | `docs/05-database.md` | 테이블, enum, 인덱스 설계 메모 |
| 6 | `docs/06-mvp-roadmap.md` | MVP 포함 / 선택 / 제외 범위와 확장 순서 |
| 7 | `docs/99-changelog.md` | 주요 기획 변경 이력 |

## 문서 수정 기준

- 앱 방향이 바뀌면 `01-product-plan.md`를 수정한다.
- 기능 계층이나 기능 위치가 바뀌면 `02-feature-structure.md`를 수정한다.
- 연간 / 월간 / 주간 / 하루 계획 구조가 바뀌면 `03-planning-system.md`를 수정한다.
- 실제 구현 조건이 바뀌면 `04-requirements.md`를 수정한다.
- 테이블, 컬럼, enum, 인덱스가 바뀌면 `05-database.md`를 수정한다.
- MVP 범위나 확장 순서가 바뀌면 `06-mvp-roadmap.md`를 수정한다.
- 중요한 결정은 `99-changelog.md`에 기록한다.
