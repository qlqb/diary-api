# API — 엔드포인트 색인 (diary-api)

컨트롤러 매핑 애너테이션에서 기계 추출했다. 1행 = 1엔드포인트, 클래스 레벨 `@RequestMapping` prefix는 경로에 합성했다.
요청·응답 본문은 싣지 않는다 — 필요하면 `docs/api-spec.md`(변경 이력형 상세)·`docs/openapi.yaml`·해당 컨트롤러를 첨부한다.

- 컨트롤러 41개 · 엔드포인트 **201** · 클래스 prefix 26 · 매핑 애너테이션 합계 227
- 대조: `scripts/check-context-layer.sh` (엔드포인트 행 + 클래스 prefix = 애너테이션 수)
- 인증: `/api/auth/**` 외 전부 JWT 인증 필요(stateless, `common/config/SecurityConfig`).
- 패키지 경로는 `src/main/java/com/jungwoo/project/memo/` 기준.


## ai

**AiContextSuggestionController** `ai/` · prefix `/api/ai/context-suggestions`
- POST /api/ai/context-suggestions/{suggestionId}/apply — 상담이 낸 상황(Context) 변경 후보 승인
- POST /api/ai/context-suggestions/{suggestionId}/dismiss — 상황 변경 후보 거절

**AiConversationController** `ai/` · prefix `/api/ai/conversations`
- POST /api/ai/conversations — 상담 대화 생성
- GET /api/ai/conversations — 내 대화 목록(최근순, courseId로 프로젝트별)
- DELETE /api/ai/conversations/{conversationId} — 대화 삭제(메시지·제안·반영된 컨텍스트는 남김)
- GET /api/ai/conversations/{conversationId}/messages — 대화 메시지 목록
- GET /api/ai/conversations/{conversationId}/context-suggestions — 미결 상황 변경 후보(새로고침 복원)
- GET /api/ai/conversations/{conversationId}/schedule-suggestions — 미결 일정 후보(새로고침 복원)
- POST /api/ai/conversations/{conversationId}/messages — 상담 턴 전송(스트리밍, 저장된 턴 재생 포함)

**AiProposalController** `ai/` · prefix `/api/ai/proposals`
- GET /api/ai/proposals/{proposalId} — AI 제안 조회
- POST /api/ai/proposals/{proposalId}/apply — AI 제안 적용
- POST /api/ai/proposals/{proposalId}/dismiss — AI 제안 버리기(서버에 기록, 대화를 다시 열어도 안 되살아남)

**AiScheduleSuggestionController** `ai/` · prefix `/api/ai/schedule-suggestions`
- POST /api/ai/schedule-suggestions/{suggestionId}/apply — 일정 후보 적용(editedPayload 없으면 원본)
- POST /api/ai/schedule-suggestions/apply-batch — 일정 후보 일괄 적용(실패 시 전부 롤백)
- POST /api/ai/schedule-suggestions/{suggestionId}/dismiss — 일정 후보 거절

**UserContextController** `ai/` · prefix `/api/contexts`
- GET /api/contexts — AI가 이해한 내 상황 목록(활성+낡음)
- PATCH /api/contexts/{contextId} — 상황 항목 수정(낡아진 초안 id 반환)
- POST /api/contexts/{contextId}/confirm — 상황 항목 확인(진도·시험 범위가 현재 값이 됨)
- DELETE /api/contexts/{contextId} — 상황 항목 철회

**ChangeBundleController** `ai/change/`
- GET /api/ai/change-bundles/{bundleId} — 변경 묶음 조회
- GET /api/ai/conversations/{conversationId}/change-bundles — 대화의 변경 묶음 목록
- PUT /api/ai/change-bundles/{bundleId}/selection — 변경 묶음 항목 선택 저장(revision 대조)
- POST /api/ai/change-bundles/{bundleId}/refresh — 변경 묶음을 현재 상태로 다시 계산
- POST /api/ai/change-bundles/{bundleId}/apply — 변경 묶음 적용
- POST /api/ai/change-bundles/{bundleId}/dismiss — 변경 묶음 버리기

**ConsultPhotoController** `ai/photo/`
- POST /api/ai/conversations/{conversationId}/photos — 상담 교재 사진 업로드(multipart, uploadKey 멱등)
- GET /api/ai/conversations/{conversationId}/photos — 대화의 사진 목록(uploadKey로 기존 업로드 조회)
- PUT /api/materials/{materialId}/photo-topic — 사진의 단원 확인·변경·해제
- DELETE /api/materials/{materialId}/original — 사진 원본만 삭제(읽은 글·연결 유지)

**ScheduleImageImportController** `ai/scheduleimport/` · prefix `/api/ai/conversations/{conversationId}/schedule-image`
- POST /api/ai/conversations/{conversationId}/schedule-image/extract — 시간표·근무표 이미지에서 표 추출
- POST /api/ai/conversations/{conversationId}/schedule-image/confirm — 검토한 표로 일정 후보 생성(약속은 아직 안 생김)

**StudyStateController** `ai/state/` · prefix `/api/courses/{courseId}/study-state`
- GET /api/courses/{courseId}/study-state — 프로젝트 학습 기억 상태


## assignment

**CourseAssignmentController** `assignment/`
- GET /api/courses/{courseId}/assignments — 프로젝트의 과제 목록
- GET /api/assignments — 미완료 과제 전체
- POST /api/assignments — 과제 생성
- PATCH /api/assignments/{assignmentId}/answer — 과제 답 저장
- PATCH /api/assignments/{assignmentId}/due — 과제 마감 변경
- PATCH /api/assignments/{assignmentId}/title — 과제 제목 변경
- PATCH /api/assignments/{assignmentId}/completed — 과제 완료 표시


## auth

**AuthController** `auth/controller/` · prefix `/api/auth`
- POST /api/auth/signup — 회원가입
- POST /api/auth/login — 로그인(JWT 발급)


## commitment

**CommitmentController** `commitment/` · prefix `/api/commitments`
- GET /api/commitments — 기간 내 약속 목록(from~to)
- POST /api/commitments — 약속 생성(source=MANUAL)
- PUT /api/commitments/{commitmentId} — 약속 수정
- DELETE /api/commitments/{commitmentId} — 약속 삭제(version 대조)


## course

**CourseController** `course/` · prefix `/api/courses`
- GET /api/courses — 프로젝트 목록(status 기본 ACTIVE, ARCHIVED=보관함)
- GET /api/courses/{courseId} — 프로젝트 상세
- GET /api/courses/{courseId}/notes — 프로젝트 노트
- POST /api/courses — 프로젝트 생성
- PATCH /api/courses/{courseId} — 프로젝트 수정(교재 칸은 판 대조)
- DELETE /api/courses/{courseId} — 프로젝트 보관(삭제 아님)
- POST /api/courses/{courseId}/restore — 보관함에서 복원

**TextbookController** `course/textbook/` · prefix `/api/courses/{courseId}/textbook`
- GET /api/courses/{courseId}/textbook — 교재 검토 화면 데이터
- POST /api/courses/{courseId}/textbook/apply — 자료를 지금 교재로 지정
- POST /api/courses/{courseId}/textbook/clue/apply — 강의계획서 교재 단서 하나를 교재로
- POST /api/courses/{courseId}/textbook/web/choose — 웹에서 찾은 판 중 하나를 교재로
- POST /api/courses/{courseId}/textbook/web/retry — 교재 웹 검색 재시도(캐시 무시)
- POST /api/courses/{courseId}/textbook/web/link — 사용자가 준 도서 링크로 찾기
- PUT /api/courses/{courseId}/textbook/web/enabled — 교재 단서 외부 검색 허용 여부
- POST /api/courses/{courseId}/textbook/toc-link — 업로드 목차를 지금 교재 목차로 연결


## diary

**DiaryController** `diary/` · prefix `/api/diaries`
- GET /api/diaries — 일기 목록(페이지·기분·즐겨찾기 필터)
- GET /api/diaries/search — 일기 검색
- GET /api/diaries/{diaryId} — 일기 상세
- POST /api/diaries — 일기 작성
- PUT /api/diaries/{diaryId} — 일기 수정
- DELETE /api/diaries/{diaryId} — 일기 삭제
- PATCH /api/diaries/{diaryId}/favorite — 즐겨찾기 토글
- GET /api/diaries/{diaryId}/revisions — 수정 이력 목록
- POST /api/diaries/{diaryId}/revisions/{revisionId}/restore — 수정 이력으로 복구
- GET /api/diaries/statistics/summary — 일기 통계 요약
- GET /api/diaries/statistics/mood — 기분별 통계
- GET /api/diaries/statistics/monthly — 월별 통계
- GET /api/diaries/statistics/streak — 연속 작성 일수


## execution

**ExecutionItemActionController** `execution/` · prefix `/api/execution-items`
- PATCH /api/execution-items/records/{executionRecordId} — 기록의 "어떻게 했나"만 수정
- POST /api/execution-items/{executionItemId}/complete — 실행 항목 완료
- POST /api/execution-items/{executionItemId}/reopen — 완료 되돌리기
- POST /api/execution-items/{executionItemId}/move — 다른 날·시각으로 옮기기
- PATCH /api/execution-items/{executionItemId}/unschedule — 배치 해제(롤링 배치 되돌리기, 멱등)
- POST /api/execution-items/{executionItemId}/reduce — 분량 줄이기
- POST /api/execution-items/{executionItemId}/partial — 부분 수행 기록
- POST /api/execution-items/{executionItemId}/hold — 보류
- POST /api/execution-items/{executionItemId}/resume — 보류 해제

**ExecutionItemController** `execution/` · prefix `/api/execution-items`
- GET /api/execution-items — 날짜별 실행 항목
- GET /api/execution-items/range — 기간별 실행 항목
- GET /api/execution-items/pending — 기준일 이전 미처리 항목
- GET /api/execution-items/by-course/{courseId} — 프로젝트별 실행 항목
- GET /api/execution-items/records — 기간 내 실행 기록
- POST /api/execution-items — 실행 항목 직접 생성
- DELETE /api/execution-items/{executionItemId} — 실행 항목 삭제(soft, version 대조)
- POST /api/execution-items/{executionItemId}/restore — 삭제 되돌리기(Ctrl+Z)


## learning

**LearningConversationController** `learning/` · prefix `/api/learning/conversations`
- POST /api/learning/conversations — 학습 대화 생성
- GET /api/learning/conversations/{conversationId}/messages — 학습 대화 메시지
- POST /api/learning/conversations/{conversationId}/messages — 학습 대화 턴 전송

**LearningMapController** `learning/` · prefix `/api/courses/{courseId}`
- GET /api/courses/{courseId}/learning-map — 학습 지도(자료·분석·변경안·단원 상태)
- POST /api/courses/{courseId}/self-checks — 자기 점검 결과 저장(선택, 상담·계획 입력)

**LearningRecommendationController** `learning/`
- POST /api/courses/{courseId}/learning/recommendation — 학습 추천 생성
- GET /api/learning/recommendations/{recommendationId} — 학습 추천 조회

**TopicController** `learning/`
- GET /api/courses/{courseId}/topics — 학습 항목 트리
- PATCH /api/topics/{topicId}/progress — 학습 항목 진행 상태 변경
- PATCH /api/topics/{topicId}/user-mark — 익숙함 표식(KNOWN/DEFER/해제)

**LearningEventController** `learning/events/` · prefix `/api/courses/{courseId}/learning-events`
- GET /api/courses/{courseId}/learning-events — 학습 이벤트 로그(after 커서)

**ClassSessionController** `learning/events/session/`
- GET /api/courses/{courseId}/class-sessions/pending — 확인 대기 수업 회차(최근 days일)
- PUT /api/class-sessions — 수업 회차 진행 확인
- PATCH /api/courses/{courseId}/class-prompt — 수업 확인 묻기 켜기·끄기

**TopicChangeProposalController** `learning/structure/`
- GET /api/courses/{courseId}/topic-change-proposals — 자료 정리 변경안 목록(2026-09-21 이후 이력 전용)
- GET /api/topic-change-proposals/{proposalId} — 변경안 조회
- POST /api/topic-change-proposals/{proposalId}/apply — 변경안 적용
- POST /api/topic-change-proposals/{proposalId}/dismiss — 변경안 거절

**ProjectTidyController** `learning/tidy/`
- GET /api/courses/{courseId}/tidy — 프로젝트 정리 현황
- POST /api/courses/{courseId}/tidy — 프로젝트 정리 요청(refresh·지시 선택)
- PUT /api/project-tidy/{proposalId}/edits — 정리안 편집 저장
- POST /api/project-tidy/{proposalId}/apply — 정리안 적용
- POST /api/courses/{courseId}/tidy/retry — 실패한 정리를 같은 입력으로 재시도
- POST /api/courses/{courseId}/tidy/dismiss — 정리안 버리기
- GET /api/courses/{courseId}/tidy/history — 정리 이력

**StructureController** `learning/tidy/`
- POST /api/courses/{courseId}/structure/manual — 학습 구조 직접 조정
- POST /api/courses/{courseId}/structure/requests — 학습 구조 조정 요청을 해석
- GET /api/courses/{courseId}/corrections — 실제 수업 진행 정정·범위 제외 목록
- DELETE /api/courses/{courseId}/scope-exclusions/{exclusionId} — 범위 제외 해제

**MaterialWeekController** `learning/week/` · prefix `/api/courses/{courseId}/material-weeks`
- GET /api/courses/{courseId}/material-weeks — 자료 주차 배치 검토
- PUT /api/courses/{courseId}/material-weeks/{materialId} — 자료 주차 지정
- POST /api/courses/{courseId}/material-weeks/apply-suggestions — 주차 추천 일괄 적용


## material

**MaterialAnalysisController** `material/`
- POST /api/courses/{courseId}/materials/{materialId}/analyses — 프로젝트 맥락 자료 분석 시작
- GET /api/courses/{courseId}/materials/{materialId}/analyses — 이 프로젝트 맥락의 분석 목록
- GET /api/material-analyses/{analysisId} — 분석 결과 조회
- PUT /api/material-analyses/{analysisId} — 분석 초안 편집
- POST /api/material-analyses/{analysisId}/apply — 분석 적용
- POST /api/material-analyses/{analysisId}/dismiss — 분석 버리기

**MaterialController** `material/` · prefix `/api/courses/{courseId}/materials`
- POST /api/courses/{courseId}/materials — 프로젝트에 자료 업로드(materialType 지정)
- GET /api/courses/{courseId}/materials — 프로젝트 자료 목록

**MaterialStoreController** `material/` · prefix `/api/materials`
- GET /api/materials — 자료함 전체 목록
- POST /api/materials — 프로젝트 없이 업로드(타입은 연결 때 정함)
- GET /api/materials/{materialId} — 자료 상세(연결·분석 이력)
- GET /api/materials/{materialId}/file — 원본 파일 내려받기(inline)
- POST /api/materials/{materialId}/extraction/retry — 본문 추출만 재시도
- DELETE /api/materials/{materialId} — 자료와 원본 삭제(연결 해제와 다름)
- POST /api/materials/link-proposal — 미연결 자료 연결 제안(저장 없음, 실패도 200)
- POST /api/materials/link-proposal/apply — 승인한 연결 묶음 적용(단일 트랜잭션)
- POST /api/materials/{materialId}/links — 자료를 프로젝트에 연결
- PATCH /api/materials/{materialId}/links/{courseId} — 연결의 자료 역할 변경
- DELETE /api/materials/{materialId}/links/{courseId} — 연결만 해제

**MaterialAnalysisStatusController** `material/analysis/`
- GET /api/materials/analysis/overview — 분석 대기열·한도 개요
- POST /api/materials/analysis/pause — 자동 분석 일시정지
- POST /api/materials/analysis/resume — 자동 분석 재개
- GET /api/materials/{materialId}/analysis-status — 자료별 분석 상태
- POST /api/materials/{materialId}/analysis-status/retry — 실패 분석 재시도
- POST /api/materials/{materialId}/analysis-status/retry-link — (자료,프로젝트) 연결 변경안 재생성
- GET /api/materials/{materialId}/sections — 자료 구간 목록
- GET /api/material-sections/{sectionId} — 구간 상세

**MaterialAnalysisBatchController** `material/batch/`
- POST /api/materials/analysis/estimate — 업로드 전 분석 비용 추정
- POST /api/materials/analysis/batches — 분석 묶음 생성
- GET /api/materials/analysis/batches/{batchId} — 분석 묶음 조회
- GET /api/materials/analysis/batches/open — 열린 묶음 페이지(nextCursor)
- GET /api/materials/analysis/batches — 열린 묶음 목록(옛 경로)

**MaterialZipImportController** `material/zip/` · prefix `/api/materials/zip-imports`
- POST /api/materials/zip-imports — ZIP 가져오기 시작(multipart)
- GET /api/materials/zip-imports — 최근 ZIP 가져오기
- GET /api/materials/zip-imports/{importId} — ZIP 가져오기 조회
- POST /api/materials/zip-imports/{importId}/confirm — 선택 항목 확정(분석 묶음 열림)
- POST /api/materials/zip-imports/{importId}/entries/{entryId}/retry — ZIP 항목 재시도
- DELETE /api/materials/zip-imports/{importId} — ZIP 가져오기 취소


## orchestration

**OrchestrationController** `orchestration/` · prefix `/api/courses/{courseId}/learning`
- POST /api/courses/{courseId}/learning/recommend-and-plan — 추천 후 바로 계획 초안


## plan

**PlanController** `plan/` · prefix `/api/plans`
- POST /api/plans/draft — 계획 초안 생성
- POST /api/plans/drafts/{proposalId}/items:regenerate — 판단 유지, 조각만 재생성
- POST /api/plans/proposals/{proposalId}/redraft — 같은 조건으로 초안 다시 만들기
- PUT /api/plans/proposals/{proposalId}/review-state — 초안 검토 상태 저장(version 409)
- GET /api/plans/draft/progress — 초안 생성 진행 단계(모르는 키는 known=false)
- GET /api/plans/proposals/{proposalId}/draft — 저장된 초안 다시 읽기(모델 호출 없음)
- POST /api/plans/proposals/{proposalId}/confirm — 초안 확정 → plan_version
- GET /api/plans — 날짜를 포함하는 계획 목록(첫 번째가 대표)
- GET /api/plans/{planVersionId} — 확정 계획 조회
- GET /api/plans/{planVersionId}/items — 이 계획의 현재 항목
- GET /api/plans/drafts/{proposalId}/provenance — 초안 근거 스냅샷(없으면 recorded=false)
- GET /api/plans/drafts/{proposalId}/trace — 모델 호출별 실제 전송 내용(개발 검증용)
- GET /api/plans/items/{executionItemId}/provenance — 적용된 실행 조각의 근거
- GET /api/plans/{planVersionId}/review — 계획 회고(실측·예정 시간)
- POST /api/plans/{planVersionId}/place — 롤링 배치(최대 7일, 확인 없이 적용)

**PlanItemDetailController** `plan/` · prefix `/api/plans`
- GET /api/plans/drafts/items/{proposalItemId}/detail — 초안 항목 「자세히」 조회
- POST /api/plans/drafts/items/{proposalItemId}/detail — 초안 항목 「자세히」 생성
- GET /api/plans/items/{executionItemId}/detail — 확정 항목 「자세히」 조회
- POST /api/plans/items/{executionItemId}/detail — 확정 항목 「자세히」 생성
- PATCH /api/plans/item-details/{detailId} — 「자세히」 사용자 글 수정

**ExecutionWorkspaceController** `plan/help/`
- GET /api/execution-items/{executionItemId}/workspace — 실행 작업 공간
- POST /api/execution-items/{executionItemId}/start-help — 시작 도움 요청(확정 항목)
- GET /api/plans/drafts/items/{proposalItemId}/start-help — 초안 항목 최근 시작 도움(없으면 204)
- POST /api/plans/drafts/items/{proposalItemId}/start-help — 초안 항목 시작 도움 요청
- PUT /api/plans/items/{executionItemId}/memo — 확정 항목 메모
- PUT /api/plans/drafts/items/{proposalItemId}/memo — 초안 항목 메모


## planning

**PlanningController** `planning/`
- POST /api/learning/recommendations/{recommendationId}/planning-draft — 추천에서 계획 초안(planning agent)
- POST /api/planning/proposals/{proposalId}/apply — planning agent 제안 적용


## routine

**RoutineController** `routine/` · prefix `/api/routines`
- GET /api/routines — 반복 일정 목록
- GET /api/routines/occurrences — 기간 내 반복 일정 전개(저장 행 없음)
- POST /api/routines — 반복 일정 생성
- PUT /api/routines/{routineId} — 반복 일정 수정
- DELETE /api/routines/{routineId} — 반복 일정 삭제
- POST /api/routines/{routineId}/exceptions — 반복 예외 추가
- PUT /api/routines/{routineId}/exceptions/{routineExceptionId} — 반복 예외 수정
- DELETE /api/routines/{routineId}/exceptions/{routineExceptionId} — 반복 예외 삭제


## scheduling

**SchedulePreviewController** `scheduling/`
- GET /api/ai/proposals/{proposalId}/schedule-preview — 배치 미리보기 복원(미계산 204)
- POST /api/ai/proposals/{proposalId}/schedule-preview — 배치 미리보기 재계산(Timefold)


## user

**UserController** `user/` · prefix `/api/users`
- GET /api/users/me — 현재 사용자 기본 정보
- GET /api/users/me/detail — 현재 사용자 상세 정보
