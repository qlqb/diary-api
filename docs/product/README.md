# diary-app 문서

이 문서는 diary-app의 기획, 기능 구조, 계획 시스템, 요구사항, DB 설계, MVP 범위와 변경 이력을 관리하기 위한 Git 추적용 문서이다.

## 프로젝트 문서 맵

번호는 파일 번호다. "상태"는 2026-10-25 정리 기준이다.

| 번호 | 분류 | 문서 | 설명 | 상태 |
|---:|:---:|---|---|---|
| 01 | 기획 | [01-product-plan.md](01-product-plan.md) | 제품 개요 — 한 줄 정의, 화면 구조(6탭), 핵심 데이터, AI 원칙 | 현행 |
| 02 | 기획 | [02-feature-structure.md](02-feature-structure.md) | 탭별 책임, AI 상담 패널, 화면↔데이터 관계 | 현행 |
| 03 | 설계 | [03-planning-system.md](03-planning-system.md) | 실행 조각·제안 적용 계약 — 배치·상태·기록·조정 액션·baseVersion·Timefold | 현행 |
| 04 | 설계 | [04-requirements.md](04-requirements.md) | 요구사항(REQ) 목록과 상태 불변조건 | 현행(일부 미구현 표시) |
| 05 | 설계 | [05-database.md](05-database.md) | 지금 쓰는 테이블 목록·관계, 날짜별 스키마 변경 기록 | 현행 |
| 06 | 로드맵 | [06-mvp-roadmap.md](06-mvp-roadmap.md) | 2026-08 시점 로드맵 | **보관** |
| 07 | 메모 | [07-ideas.md](07-ideas.md) | 아이디어 주차장 / 미확정 아이디어 기록 | 현행 |
| 08 | 설계 | [08-today-execution-loop.md](08-today-execution-loop.md) | 오늘·일정 화면의 경계와 당일 조정 흐름 | 현행 |
| 09 | 회귀 기록 | [09-ai-consultation-regression-cases.md](09-ai-consultation-regression-cases.md) | AI 상담·계획에서 발견한 실제 판단 실패 사례와 기대 행동 | 현행 |
| 10 | 제품 경험 | [10-core-experience.md](10-core-experience.md) | 자료·상태·AI 대화가 계획 초안과 실제 실행으로 이어지는 핵심 경험과 컨텍스트 경계 | 현행 |
| 11 | 설계 | [11-period-plan.md](11-period-plan.md) | 기간형 계획(plan_versions) 네 층의 책임, 생성 경로·예산·시간 — 기간형 계획의 기준 | 현행 |
| 12 | 제품 가설 | [12-product-thesis.md](12-product-thesis.md) | 무엇을 만드는 것이고 무엇은 아닌지 — 4층 구조, 현실층 갱신 원칙, 확장 순서 | 현행 |
| 13 | 설계 | [13-plan-judgment.md](13-plan-judgment.md) | 판단층 — 출처 추적, 상담 합의, 사용자 이해·전달 기록 | 현행 |
| 14 | 설계 | [14-schedule-image-import.md](14-schedule-image-import.md) | 상담에서 일정 이미지(시간표·근무표) 가져오기 | 구현됨 |
| 15 | 설계 | [15-material-auto-analysis.md](15-material-auto-analysis.md) | 자료 자동 분석·자료 구간·토픽 연결·정리·과제·계획의 자료 입력 — 자료 분석의 기준 | 현행 |
| 16 | 설계 | [16-learning-flow.md](16-learning-flow.md) | 계획 이해·학습 실행 작업 공간·수행 기록 반영·교재·실제 수업 정정 | 구현됨 |
| 17 | 설계 | [17-textbook-web-toc.md](17-textbook-web-toc.md) | 교재 목차 웹 검색·정정·하위항목 | 구현됨 |
| 18 | 설계 | [18-study-memory.md](18-study-memory.md) | 학습 기억(진도·시험 범위·막힘·해결)과 목차 기반 학습 목표 | 구현됨 |
| 19 | 설계 | [19-consult-photo.md](19-consult-photo.md) | 상담 교재 사진과 계획에서의 사진 본문 활용 | 구현됨 |
| 20 | 설계 | [20-learning-events.md](20-learning-events.md) | 이벤트 기반 학습 진도 — 원천·이벤트·계산 뷰 | 1단계 구현, 2단계 이후 미착수 |
| 21 | 설계 | [21-plan-intent-preservation.md](21-plan-intent-preservation.md) | 사용자 의도 보존 — 합의 근거·요청 범위·말한 시간·서버 판정 | 구현됨 |
| 99 | 이력 | [99-changelog.md](99-changelog.md) | 확정 변경 이력 | 현행 |

참고 명세(번호 없음):

- [../api-spec.md](../api-spec.md) — 손으로 쓰는 API 계약 설명(오류 코드·필드 뜻·날짜별 추가)
- [../openapi.yaml](../openapi.yaml) — springdoc이 만든 전체 엔드포인트 스냅샷(2026-10-25). 실행 중 서버의 `/v3/api-docs.yaml`이 최신이다
- [../handoff/README.md](../handoff/README.md) — 기능별 계획·작업·검증 기록 색인(완료 기록은 근거·리뷰 이력용)

---

## 관련 저장소

* **Frontend UI:** [GitHub - diary-ui](https://github.com/qlqb/diary-ui) (React)

## 문서 수정 기준

- 앱 방향·화면 구조·핵심 데이터 요약이 바뀌면 `01-product-plan.md`를 수정한다.
- 기능 계층이나 기능 위치가 바뀌면 `02-feature-structure.md`를 수정한다.
- 실행 조각의 배치·상태·기록·조정 액션이나 AI 제안 적용 규칙이 바뀌면 `03-planning-system.md`를 수정한다.
- 실제 구현 조건이 바뀌면 `04-requirements.md`를 수정한다.
- 테이블, 컬럼, enum, 인덱스가 바뀌면 `05-database.md`를 수정한다.
- `06-mvp-roadmap.md`는 보관 문서라 고치지 않는다. 확장 순서는 `12-product-thesis.md`.
- 미확정 아이디어는 `07-ideas.md`에 기록한다.
- 오늘·일정 화면의 경계와 당일 조정 흐름이 바뀌면 `08-today-execution-loop.md`를 수정한다.
- AI 상담(AUTO)이나 계획 생성에서 실제로 판단이 틀린 사례를 발견하면 `09-ai-consultation-regression-cases.md`에 사례로 남긴다(정확한 문장을 고정하는 것이 아니라 기대 판단/행동을 기록한다).
- 자료 공간, 컨텍스트, AI 초안, 사용자 승인, 일정/오늘/기록으로 이어지는 제품의 핵심 경험이 바뀌면 `10-core-experience.md`를 수정한다.
- 기간형 계획(PlanVersion)의 구조나 네 층의 책임 경계가 바뀌면 `11-period-plan.md`를 수정한다.
- 무엇을 만드는 것이고 무엇은 아닌지(제품 가설, 4층 구조, 현실층 갱신 원칙)가 바뀌면 `12-product-thesis.md`를 수정한다.
- 판단층(출처 추적, 상담 합의 구조, 사용자 이해·전달 기록)이 바뀌면 `13-plan-judgment.md`를 수정한다.
- 일정 이미지 가져오기가 바뀌면 `14-schedule-image-import.md`를 수정한다.
- 자료 자동 분석·자료 구간·토픽 연결·과제·계획의 자료 입력 계약이 바뀌면 `15-material-auto-analysis.md`를 수정한다.
- 학습 실행 작업 공간·교재·실제 수업 정정은 `16-learning-flow.md`, 교재 목차 웹 검색은 `17-textbook-web-toc.md`를 수정한다.
- 학습 기억은 `18-study-memory.md`, 상담 사진은 `19-consult-photo.md`, 학습 이벤트·수업 확인은 `20-learning-events.md`를 수정한다.
- 상담 합의의 근거·승인, 요청 범위 추출·판정, 말한 시간 규칙이 바뀌면 `21-plan-intent-preservation.md`를 수정한다.
- API 엔드포인트를 바꾸면 `api-spec.md`에 뜻을 적고 `openapi.yaml`을 다시 받는다(api-spec 머리말).
- 기능 작업의 계획·검증 기록은 `docs/handoff/`에 남기고, 확정된 계약은 product 문서로 옮긴다.
- 아이디어가 확정되면 해당 문서와 `99-changelog.md`에 반영한다.
- 중요한 결정은 확정 변경 이력인 `99-changelog.md`에 기록한다.
