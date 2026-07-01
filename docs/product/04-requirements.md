# 04. Requirements

이 문서는 실제 구현 기준이 되는 요구사항을 관리한다.

## 1. 공통 원칙

```text
REQ-COMMON-001
AI가 생성한 데이터는 자동 저장되지 않고 후보로 표시된다.

REQ-COMMON-002
사용자가 선택하거나 적용한 항목만 최종 저장된다.

REQ-COMMON-003
AI가 생성한 항목을 사용자가 수정 후 적용하면 origin_type은 AI_GENERATED로 유지하고 modified_after_creation은 true로 저장한다.
```

## 2. 회원 요구사항

```text
REQ-USER-001
사용자는 이메일, 비밀번호, 닉네임을 입력하여 회원가입할 수 있다.

REQ-USER-002
사용자는 이메일과 비밀번호로 로그인할 수 있다.

REQ-USER-003
이메일은 중복될 수 없다.
```

## 3. 일기 요구사항

```text
REQ-DIARY-001
로그인한 사용자는 제목, 내용, 감정 상태를 입력하여 일기를 작성할 수 있다.

REQ-DIARY-002
사용자는 자신의 일기 목록을 조회할 수 있다.

REQ-DIARY-003
사용자는 자신의 일기 상세 내용을 조회할 수 있다.

REQ-DIARY-004
사용자는 자신의 일기를 수정할 수 있다.

REQ-DIARY-005
사용자는 자신의 일기를 삭제할 수 있다.
```

## 4. 문제함 / 정리함 요구사항

```text
REQ-PROBLEM-001
사용자는 문제를 직접 등록할 수 있다.

REQ-PROBLEM-002
사용자는 문제 목록을 조회할 수 있다.

REQ-PROBLEM-003
사용자는 문제를 수정할 수 있다.

REQ-PROBLEM-004
사용자는 문제를 삭제할 수 있다.

REQ-PROBLEM-005
사용자는 일기 내용을 바탕으로 AI 문제 후보를 생성할 수 있다.

REQ-PROBLEM-006
AI 문제 후보는 자동 저장되지 않고 후보로 표시된다.

REQ-PROBLEM-007
사용자가 선택한 문제 후보만 문제함에 저장된다.
```

## 5. Todo 요구사항

```text
REQ-TODO-001
사용자는 Todo를 등록할 수 있다.

REQ-TODO-002
사용자는 날짜별 Todo 목록을 조회할 수 있다.

REQ-TODO-003
사용자는 Todo 완료 상태를 변경할 수 있다.

REQ-TODO-004
Todo가 DONE 상태로 변경되면 completed_at을 기록한다.

REQ-TODO-005
사용자는 Todo를 수정할 수 있다.

REQ-TODO-006
사용자는 Todo를 삭제할 수 있다.

REQ-TODO-007
Todo는 origin_type을 가진다.

REQ-TODO-008
AI가 추천한 Todo를 사용자가 수정 후 저장하면 origin_type은 AI_SUGGESTED로 유지하고 modified_after_creation은 true로 저장한다.
```

## 6. 계획 요구사항

계획 시스템의 개념 구조는 `03-planning-system.md`를 따른다.

```text
REQ-PLAN-001
사용자는 하루 계획을 생성할 수 있다.

REQ-PLAN-002
사용자는 하루 계획에 시간 블록을 추가할 수 있다.

REQ-PLAN-003
사용자는 시간 블록에 Todo를 연결할 수 있다.

REQ-PLAN-004
사용자는 시간 블록의 제목, 시작 시간, 종료 시간, 메모를 수정할 수 있다.

REQ-PLAN-005
사용자는 시간 블록을 삭제할 수 있다.

REQ-PLAN-006
사용자는 오늘 Todo와 사용 가능 시간을 바탕으로 AI 하루 계획 후보를 생성할 수 있다.

REQ-PLAN-007
AI가 생성한 하루 계획 후보는 자동 저장되지 않고 미리보기로 표시된다.

REQ-PLAN-008
사용자는 AI 하루 계획 후보를 수정한 뒤 적용할 수 있다.

REQ-PLAN-009
AI가 생성한 계획을 사용자가 수정 후 적용하면 origin_type은 AI_GENERATED로 저장하고 modified_after_creation은 true로 저장한다.
```

## 7. 지출 기록 요구사항

```text
REQ-EXPENSE-001
사용자는 금액, 카테고리, 한 줄 메모, 감정 태그를 입력하여 지출을 기록할 수 있다.

REQ-EXPENSE-002
사용자는 일기 내용에서 AI가 추출한 지출 후보를 확인할 수 있다.

REQ-EXPENSE-003
AI가 추출한 지출 후보는 자동 저장되지 않는다.

REQ-EXPENSE-004
사용자가 저장한 지출 후보만 지출 기록으로 저장된다.
```
