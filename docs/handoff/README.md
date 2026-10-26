# 작업 기록 (handoff)

기능 하나를 만들 때 남긴 계획(`*-plan-*.md`, 리뷰 이력 포함)과 작업·검증 기록이다. **계약의 기준은 `docs/product/`다.**
아래 "완료" 문서는 내용이 product 문서로 옮겨졌고, 설계 근거·리뷰 이력·검증 수치를 찾을 때만 읽는다.
코드 주석·테스트·SQL·changelog가 이 경로를 가리키므로 파일을 옮기지 않는다(2026-10-25 정리).

| 기록 | 상태 | 기준 문서 |
|---|---|---|
| `findings-2026-09-08.md` | 완료 | 99-changelog 2026-09-08 |
| `material-auto-analysis-2026-09-13.md` | 완료 | 15번 |
| `project-consult-workspace-2026-09-19.md` | 완료(T01~T18 대응표는 changelog가 가리킨다) | 11·13·15·05번, changelog 2026-09-19 |
| `project-tidy-2026-09-21.md`, `project-tidy-review-2026-09-21.md` | 완료 | 15번 §11~13, 05번 §19·20 |
| `learning-flow-2026-09-29.md` | 완료 | 16번 |
| `consult-grounding-2026-10-03.md` | 완료 | 10번 §5.2, api-spec(상담 근거) |
| `textbook-web-toc-plan-2026-10-04.md`, `textbook-web-toc-2026-10-04.md` | 완료 | 17번 |
| `study-memory-plan-2026-10-05.md`, `study-memory-2026-10-05.md` | 완료 | 18번 |
| `consult-photo-plan-2026-10-05.md`, `consult-photo-2026-10-05.md` | 완료 | 19번 |
| `toc-subitems-plan-2026-10-06.md`, `toc-subitems-2026-10-06.md` | 완료 | 17번 §11 |
| `learning-events-stage1-plan-2026-10-06.md`, `learning-events-stage1-2026-10-06.md` | 완료(1단계) | 20번 §14.1 |
| `plan-intent-preservation-plan-2026-10-18.md`, `plan-intent-preservation-2026-10-18.md` | 완료 | 21번 |
| `consult-tools-plan-2026-10-25.md`, `consult-tools-2026-10-26.md` | 완료(dev-playground 합침, 마이그레이션 `2026-10-26-change-bundles.sql`) | 99-changelog 2026-10-26 (product 기준 문서는 아직 없다) |

## 운영 DB 반영 확인

여러 기록에 "운영 `memo` DB에는 아직 적용하지 않았다"는 메모가 있다. 적용 여부는 이 문서에서 추적하지 않는다 —
운영에 올리기 전에 `docs/sql/`을 날짜 순으로 대조한다. 특히 2026-09-29 이후 마이그레이션(learning-flow, textbook-web-toc,
study-memory, consult-photo, toc-subitems, learning-events, class-sessions, learning-event-backfill, change-bundles)과 20번 §14.1의 백필 절차.
