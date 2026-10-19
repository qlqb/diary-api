package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.ai.domain.ContextSourceType;
import com.jungwoo.project.memo.ai.domain.UserContextStatus;
import com.jungwoo.project.memo.learning.domain.TopicProgressStatus;
import com.jungwoo.project.memo.learning.domain.TopicUserMark;
import com.jungwoo.project.memo.plan.domain.PlanIntensity;
import com.jungwoo.project.memo.scheduling.service.AvailabilityEstimateResult;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 계획을 세우기 전에 DB에서 모은 현실. 판단(PlanJudgmentService)·조각 생성·v0 생성기가
 * <b>같은 입력</b>을 쓰게 하려고 한 곳에서 모은다.
 *
 * <p>★ 여기서 판단하지 않는다. 모으기만 한다 — 무엇을 건너뛸지, 무엇을 먼저 할지는 다음
 * 층의 몫이다. 이 경계가 흐려지면 "왜 이 항목이 계획에 없나"의 답이 두 곳으로 갈린다.
 *
 * <p>★ 맥락을 가용시간으로 바꾸지 않는다. 가용시간의 유일한 원본은
 * AvailabilityEstimateService이고, 여기서 다시 계산하면 화면과 배치가 조용히 달라진다.
 *
 * @param now                현재 시각. 날짜만이 아니라 시각이다 — 21:37에 "오늘 오전"으로
 *                           시작하는 계획을 만들지 않기 위해 필요하다
 * @param availabilityByDay  {@link AvailabilityDaySummary}가 접은 하루 한 줄 요약
 * @param availability       접기 전 원본. 예산 계산과 배치가 이 값을 쓴다
 * @param previousReview     직전 계획 회고 요약. 없으면 null
 * @param instruction        사용자 지시. 없으면 null
 */
public record PlanningContext(
        Long userId,
        LocalDateTime now,
        LocalDate start,
        LocalDate end,
        PlanIntensity intensity,
        List<CourseContext> courses,
        List<String> availabilityByDay,
        AvailabilityEstimateResult availability,
        List<ContextLine> contexts,
        String previousReview,
        String instruction
) {

    /**
     * 과목 하나와 그 과목에서 이번에 볼 만한 학습 항목들.
     *
     * @param nextClassAt      다음 수업 시작 시각. 수업 루틴이 없으면 null이다. 마감의 근거이자
     *                         과목 순서의 근거다
     * @param nextClassRoutineId 그 수업의 routine_id. 근거(Evidence.refId)로 남긴다
     * @param currentWeek      개강일(수업 루틴의 effective_from)과 오늘로 계산한 현재 주차.
     *                         개강일을 모르면 null
     * @param topics           창(현재 주차 −2 ~ +1) 안의 항목이 앞에, 주차를 모르는 항목이 뒤에.
     *                         수집 단계에서 개수로 자르지 않는다 — 자르는 것은 쓰는 쪽의 몫이다
     */
    public record CourseContext(
            Long courseId,
            String title,
            String textbookTitle,
            LocalDateTime nextClassAt,
            Long nextClassRoutineId,
            Integer currentWeek,
            List<TopicContext> topics,
            /**
             * 이 과목의 확정 과제(완료 포함). 마감과 완료 상태는 판단 입력이자 마감 근거(DEADLINE)다.
             * 과제 수행 자체를 조각으로 만들라는 뜻이 아니다 — 그건 사용자가 요청했을 때만이다.
             */
            List<AssignmentContext> assignments
    ) {
        /** 과제 없이 만드는 예전 모양. 단위 테스트가 쓴다. */
        public CourseContext(Long courseId, String title, String textbookTitle, LocalDateTime nextClassAt,
                             Long nextClassRoutineId, Integer currentWeek, List<TopicContext> topics) {
            this(courseId, title, textbookTitle, nextClassAt, nextClassRoutineId, currentWeek, topics, List.of());
        }
    }

    /**
     * 확정 과제 한 건의 현재 사실.
     *
     * @param dueDate   마감일. 마감 없음·미확인이면 null
     * @param completed 사용자가 완료 체크했다
     * @param topicId   연결된 학습 항목. 없으면 null
     */
    public record AssignmentContext(
            Long assignmentId,
            String title,
            LocalDate dueDate,
            boolean completed,
            Long topicId
    ) {
    }

    /**
     * 학습 항목 하나의 현재 사실.
     *
     * @param week      locator에서 읽은 주차. 읽을 수 없으면 null이고, 그것은 0주차가 아니라
     *                  "모른다"다
     * @param userMark  사용자가 직접 말한 사실. AI가 쓰지 않는다
     * @param depth     topic 트리에서의 깊이. 0이 뿌리. 표시 들여쓰기에만 쓴다
     */
    public record TopicContext(
            Long topicId,
            String title,
            String sourceLocator,
            Integer week,
            TopicProgressStatus progressStatus,
            LocalDateTime lastStudiedAt,
            TopicUserMark userMark,
            int depth,

            /**
             * 이 학습 항목이 어느 자료에서 나왔는가. 조각이 자료에 anchor할 때 쓴다.
             *
             * <p>어느 자료를 볼지는 <b>모델이 고르지 않는다.</b> 항목이 이미 출처를 알고
             * 있으므로 서버가 그대로 붙인다 — 모델에게 고르게 하면 없는 자료를 지어낼 수
             * 있고, 그것을 막으려면 다시 대조 코드를 써야 한다.
             */
            Long sourceMaterialId,
            String sourceMaterialFilename,
            /**
             * 반드시 판단 입력에 있어야 하는 이유. 진행 중·첫 미학습·과제 연결·사용자 연결 중 하나라도 있으면
             * 주차 창 밖이어도 실린다. 창을 걸지 않는 과목에서는 의미가 없다.
             */
            LocalDate assignmentDue,
            boolean assignmentLinked,
            boolean userLinked,
            boolean firstUnlearned
    ) {
        /** 과제·사용자 연결 없는 예전 모양. 단위 테스트가 쓴다. */
        public TopicContext(Long topicId, String title, String sourceLocator, Integer week,
                            TopicProgressStatus progressStatus, LocalDateTime lastStudiedAt, TopicUserMark userMark,
                            int depth, Long sourceMaterialId, String sourceMaterialFilename) {
            this(topicId, title, sourceLocator, week, progressStatus, lastStudiedAt, userMark, depth, sourceMaterialId,
                    sourceMaterialFilename, null, false, false, false);
        }

        /** 주차 창과 무관하게 판단 입력에 들어가야 하는가. */
        public boolean mustInclude() {
            return progressStatus == TopicProgressStatus.IN_PROGRESS || firstUnlearned || assignmentLinked || userLinked;
        }

        TopicContext withFlags(LocalDate due, boolean assignment, boolean user, boolean first) {
            return new TopicContext(topicId, title, sourceLocator, week, progressStatus, lastStudiedAt, userMark, depth,
                    sourceMaterialId, sourceMaterialFilename, due, assignment, user, first);
        }
    }

    /**
     * 확정된 맥락 한 줄(user_contexts).
     *
     * <p>status와 sourceType을 둘 다 남긴다. 근거 등급을 가르는 것은 <b>수명주기(status)</b>다 —
     * ACTIVE면 아직 유효한 사실, STALE이면 확인이 필요한 사실. sourceType(USER_CONFIRMED /
     * AI_SUGGESTION_APPROVED)은 등급을 가르지 않는다. 둘 다 사용자가 승인 버튼을 눌러야
     * 생기는 행이고, AI가 문장을 만들었다는 사실이 사용자의 승인을 약하게 만들지 않는다.
     */
    public record ContextLine(
            Long contextId,
            String content,
            UserContextStatus status,
            ContextSourceType sourceType
    ) {
    }
}
