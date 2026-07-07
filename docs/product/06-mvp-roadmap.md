# 06. MVP Roadmap

## 1. MVP 원칙

MVP는 많은 기능을 한 번에 넣는 것이 아니라, 하루 운영 루프가 실제로 돌아가는지 검증하는 데 집중한다.

핵심 검증 기준은 개발자 본인이 30일 연속 매일 사용하는 것이다.

MVP의 중심 흐름은 다음과 같다.

```text
오늘 상태 확인
→ 할 일과 계획 확인
→ 실행
→ 완료/이동/축소/보류
→ 하루 마무리
→ 주간 회고
```

## 2. 1차-A: 이벤트와 도메인 액션

범위:

- plan_item_events
- ScheduleBlock 도메인 액션 API
- `move`
- `reduce`
- `hold`
- `complete`
- 아직 못 한 것 카드
- ScheduleBlock 우선

API:

```text
POST /api/schedule-blocks/{id}/move
POST /api/schedule-blocks/{id}/reduce
POST /api/schedule-blocks/{id}/hold
POST /api/schedule-blocks/{id}/complete
```

완료 판정:

- 아직 못 한 것 카드에서 버튼이 동작한다.
- 완료/이동/축소/보류 이벤트가 저장된다.
- move 액션이 하나의 트랜잭션으로 검증된다.
- move는 ScheduleBlock row를 복제하지 않고 기존 row의 date와 daily_plan_id를 갱신한다.

1차-A의 아직 못 한 것 카드는 ScheduleBlock만 표시한다. 이는 의도된 범위이며 버그가 아니다.

## 3. 1차-B: quick_logs와 주간 회고

범위:

- 감정 quick_logs
- 수면 quick_logs
- 하루 마무리 카드
- 이벤트 집계 기반 주간 회고 화면
- Todo 액션 확장

완료 판정:

- 폰에서 3탭/10초 안에 하루 마무리를 기록할 수 있다.
- 주간 회고 화면에 완료/이동/축소/보류 집계가 표시된다.
- Todo에도 필요한 액션 흐름이 확장된다.

## 4. 1차-C: DailyPlan v2

범위:

- DailyPlan v2
- viewMode
- intensity
- conditionTags
- 오늘 상태 카드

완료 판정:

- 날짜별 DailyPlan 기본값이 생성된다.
- 오늘만 viewMode와 intensity를 변경할 수 있다.
- conditionTags를 자유 태그로 기록할 수 있다.

## 5. 1차-D: 오늘 화면 통합

범위:

- 오늘 화면 4카드 통합
- 모바일 반응형
- PWA

완료 판정:

- 폰 홈 화면에서 앱에 진입할 수 있다.
- 오늘 상태 확인부터 하루 마무리까지 하루 루프를 완주할 수 있다.
- 모바일 화면에서 주요 조작이 깨지지 않는다.

## 6. 1.5차: AI 주간 요약

범위:

- AI 주간 요약
- 개인정보 처리 원칙 적용
- 집계 데이터 기반 요약 후보

조건:

- quick_logs와 plan_item_events 기반 집계 데이터가 2주 이상 쌓인 뒤 실행한다.
- 사용자가 명시적으로 요청할 때만 실행한다.
- 일기 원문 전체를 기본 전송하지 않는다.
- 결과는 참고용 후보이며 자동 저장하지 않는다.

## 7. 30일 자가사용 검증

검증 대상:

- 개발자 본인 30일 자가사용
- viewMode 가설
- 오늘 화면 카드 구성
- 입력 마찰 예산
- 이동/축소/보류 흐름

검증 후 2차 착수 여부를 판단한다.

## 8. 2차 MVP

범위:

- 정리함 CRUD
- 정리함-Todo 연결
- 정리함-일기 연결
- AI 문제 후보
- AI 행동 후보
- ai_suggestions 테이블
- AI 제안 피드백 루프
- 오늘 해볼 것 카드 확장

## 9. 3차 확장

범위:

- 루틴 등록
- 루틴 → Todo 생성
- 공부 기록
- 지출 기록
- 기본값 변경 제안
- 첫 교차 분석: 컨디션 태그 × 이월률

## 10. 장기 확장

범위:

- MonthlyPlan
- YearlyPlan
- LifeGoal
- BehaviorPattern
- 심화 교차 분석

소셜 템플릿 공유는 별도 프로젝트로 취급한다.

## 11. MVP 제외

MVP에서는 다음을 제외한다.

```text
MonthlyPlan / YearlyPlan / LifeGoal / BehaviorPattern 테이블
고급 AI 상담
월간 리포트
커뮤니티
멘토링
계좌/카드 연동
예산·자산·투자 관리
구독 탐지
영수증 OCR
푸시 알림
CSV 업로드
```
