# diary-app 문서

이 문서는 diary-app의 기획, 기능 구조, 계획 시스템, 요구사항, DB 설계, MVP 범위와 변경 이력을 관리하기 위한 Git 추적용 문서이다.

## 📂 프로젝트 문서 맵 (가이드라인)

> 프로덕트의 기획 의도부터 상세 설계까지 **아래 순서대로** 읽어보시는 것을 추천합니다.

| 순서 | 분류 | 문서 링크 | 설명 |
|---:|:---:|---|---|
| 1 | 기획 | [01-product-plan.md](docs/product/01-product-plan.md) | 앱의 최종 방향, 핵심 포지션, UX 원칙 |
| 2 | 기획 | [02-feature-structure.md](docs/product/02-feature-structure.md) | 기록 / 정리 / 실행 / 분석 계층 구조 |
| 3 | 기획 | [03-planning-system.md](docs/product/03-planning-system.md) | 연/월/주/일 계획 및 시간 블록 구조 |
| 4 | 설계 | [04-requirements.md](docs/product/04-requirements.md) | 상세 기능별 구현 요구사항 |
| 5 | 설계 | [05-database.md](docs/product/05-database.md) | 테이블, enum, 복합 인덱스 설계 메모 |
| 6 | 로드맵 | [06-mvp-roadmap.md](docs/product/06-mvp-roadmap.md) | MVP 스코프(포함/제외 범위) 및 확장 순서 |
| 7 | 설계 | [api-spec.md](docs/api-spec.md) | 마크다운 기반 수동 API 명세 |
| 8 | 설계 | [openapi.yaml](docs/openapi.yaml) | OpenAPI(OAS) 표준 API 스펙 |
| 9 | 이력 | [99-changelog.md](docs/product/99-changelog.md) | 주요 기획 및 아키텍처 변경 이력 |

---

## 🔗 관련 저장소 (Repositories)

* **Frontend UI:** [GitHub - diary-ui](https://github.com/qlqb/diary-ui) (React)
## 문서 수정 기준

- 앱 방향이 바뀌면 `01-product-plan.md`를 수정한다.
- 기능 계층이나 기능 위치가 바뀌면 `02-feature-structure.md`를 수정한다.
- 연간 / 월간 / 주간 / 하루 계획 구조가 바뀌면 `03-planning-system.md`를 수정한다.
- 실제 구현 조건이 바뀌면 `04-requirements.md`를 수정한다.
- 테이블, 컬럼, enum, 인덱스가 바뀌면 `05-database.md`를 수정한다.
- MVP 범위나 확장 순서가 바뀌면 `06-mvp-roadmap.md`를 수정한다.
- 중요한 결정은 `99-changelog.md`에 기록한다.
