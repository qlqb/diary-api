package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.ai.UserContextMapper;
import com.jungwoo.project.memo.ai.domain.UserContext;
import com.jungwoo.project.memo.course.CourseMapper;
import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.learning.TopicService;
import com.jungwoo.project.memo.learning.dto.TopicResponse;
import com.jungwoo.project.memo.plan.PeriodPlanDraftGenerator.Spec;
import com.jungwoo.project.memo.plan.PlanningContext.ContextLine;
import com.jungwoo.project.memo.plan.PlanningContext.CourseContext;
import com.jungwoo.project.memo.plan.PlanningContext.TopicContext;
import com.jungwoo.project.memo.routine.RoutineOccurrenceService;
import com.jungwoo.project.memo.routine.RoutineReader;
import com.jungwoo.project.memo.routine.domain.Routine;
import com.jungwoo.project.memo.routine.domain.RoutineOccurrence;
import com.jungwoo.project.memo.scheduling.service.AvailabilityEstimateResult;
import com.jungwoo.project.memo.scheduling.service.AvailabilityEstimateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 계획을 세우기 전에 DB의 현실을 한 곳에서 모은다.
 *
 * <p>기존 {@link PeriodPlanDraftGenerator}도 재료를 모으지만 그 결과가 곧바로 프롬프트
 * 문자열이 된다 — 다른 층이 같은 재료를 쓸 수 없다. 여기서는 구조를 남겨 판단·조각 생성·
 * v0 생성기가 같은 입력을 공유한다.
 *
 * <p>기존 수집과 달라지는 것은 넷이다: 학습 항목의 <b>진행 상태</b>, 과목별 <b>다음 수업
 * 시각</b>, 확정된 <b>맥락</b>, 그리고 날짜가 아닌 <b>현재 시각</b>. 가용시간은 옮겨오기만
 * 한다(계산은 여전히 AvailabilityEstimateService 한 곳).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlanningContextBuilder {

    /**
     * 학습 항목 창의 크기. 현재 주차 −{@value #WEEKS_BEHIND} ~ +{@value #WEEKS_AHEAD}.
     *
     * <p>뒤로 두 주를 보는 것은 밀린 내용을 따라잡기 위해서고, 앞으로 한 주만 보는 것은
     * 다음 수업까지가 이번 계획이 책임질 범위이기 때문이다. 더 당겨오면 아직 배우지 않은
     * 내용을 예습하는 계획이 되고, 그건 사용자가 요청했을 때 할 일이다.
     */
    private static final int WEEKS_BEHIND = 2;
    private static final int WEEKS_AHEAD = 1;

    /**
     * 주차를 모르는 항목(예: 과목 개요)을 과목당 몇 개까지 뒤에 붙일지.
     *
     * <p>빼지는 않는다 — 주차가 안 적혔다는 것이 중요하지 않다는 뜻은 아니다. 다만 창 안의
     * 항목보다 뒤에 두고 소량만 남긴다.
     */
    private static final int MAX_UNDATED_TOPICS_PER_COURSE = 3;

    /**
     * 다음 수업을 찾을 때 계획 종료일 이후로 며칠까지 더 볼지.
     *
     * <p>금요일에 끝나는 계획도 다음 화요일 수업을 알아야 한다 — 그 수업이 이번 계획의
     * 마감 근거이기 때문이다. 창을 계획 기간으로 자르면 기간 끝 무렵 조각이 마감을 잃는다.
     */
    private static final int NEXT_CLASS_LOOKAHEAD_DAYS = 14;

    /** 프롬프트에 실을 맥락 줄 수. 상담 경로(ContextSnapshotService)와 같은 규모로 맞춘다. */
    private static final int MAX_CONTEXT_LINES = 20;

    private final CourseMapper courseMapper;
    private final TopicService topicService;
    private final RoutineReader routineReader;
    private final RoutineOccurrenceService routineOccurrenceService;
    private final AvailabilityEstimateService availabilityEstimateService;
    private final UserContextMapper userContextMapper;
    private final PlanReviewService planReviewService;
    private final Clock clock;

    @Value("${ai.context.default-time-zone:Asia/Seoul}")
    private String defaultTimeZoneId = "Asia/Seoul";

    @Transactional(readOnly = true)
    public PlanningContext build(Spec spec) {
        Long userId = spec.userId();
        LocalDateTime now = ZonedDateTime.now(clock)
                .withZoneSameInstant(ZoneId.of(defaultTimeZoneId)).toLocalDateTime();

        AvailabilityEstimateResult availability = availabilityEstimateService.estimate(
                userId, spec.start(), spec.end(), List.of(), List.of());

        List<Course> courses = resolveCourses(userId, spec.courseIds());
        Map<Long, LocalDate> semesterStarts = semesterStartByCourse(userId);
        Map<Long, RoutineOccurrence> nextClasses = nextClassByCourse(userId, now, spec.end());

        List<CourseContext> courseContexts = new ArrayList<>();
        for (Course course : courses) {
            courseContexts.add(courseContext(userId, course, now.toLocalDate(),
                    semesterStarts.get(course.getCourseId()), nextClasses.get(course.getCourseId())));
        }
        /*
         * 과목 순서는 여기서 정하지 않는다 — 그건 판단이다. 다만 "다음 수업이 빠른 순"은
         * 어느 층에서도 뒤집을 이유가 없는 기본 정렬이라 여기서 맞춰 둔다. 수업이 없는
         * 과목은 뒤로 간다(비교할 값이 없는 것이지 덜 중요한 것이 아니다).
         */
        courseContexts.sort(Comparator.comparing(
                CourseContext::nextClassAt, Comparator.nullsLast(Comparator.naturalOrder())));

        PlanningContext context = new PlanningContext(
                userId, now, spec.start(), spec.end(), spec.intensity(),
                courseContexts,
                AvailabilityDaySummary.format(spec.start(), spec.end(), availability),
                availability,
                contextLines(userId),
                planReviewService.summarizeLatestForPrompt(userId),
                blankToNull(spec.instruction()));

        log.debug("계획 컨텍스트 수집: userId={}, {}~{}, 과목={}개, 항목={}개, 맥락={}건",
                userId, spec.start(), spec.end(), courseContexts.size(),
                courseContexts.stream().mapToInt(c -> c.topics().size()).sum(),
                context.contexts().size());
        return context;
    }

    public List<Course> resolveCourses(Long userId, List<Long> courseIds) {
        if (courseIds != null && !courseIds.isEmpty()) {
            return courseMapper.findByIdsAndUserId(courseIds, userId);
        }
        return courseMapper.findByUserIdAndStatus(userId, "ACTIVE");
    }

    private CourseContext courseContext(Long userId, Course course, LocalDate today,
                                        LocalDate semesterStart, RoutineOccurrence nextClass) {
        Integer currentWeek = weekOf(semesterStart, today);

        List<TopicContext> all = new ArrayList<>();
        for (TopicResponse root : topicService.getTopicTree(userId, course.getCourseId())) {
            flatten(all, root, 0);
        }

        return new CourseContext(
                course.getCourseId(),
                course.getTitle(),
                course.getTextbookTitle(),
                nextClass != null ? nextClass.startAt() : null,
                nextClass != null ? nextClass.routineId() : null,
                currentWeek,
                withinWindow(all, currentWeek));
    }

    private void flatten(List<TopicContext> out, TopicResponse node, int depth) {
        out.add(new TopicContext(
                node.getTopicId(),
                node.getTitle(),
                node.getSourceLocator(),
                TopicLocators.weekOf(node.getSourceLocator()),
                node.getProgressStatus(),
                node.getLastStudiedAt(),
                node.getUserMark(),
                depth,
                node.getSourceMaterialId(),
                node.getSourceMaterialFilename()));
        if (node.getChildren() != null) {
            for (TopicResponse child : node.getChildren()) {
                flatten(out, child, depth + 1);
            }
        }
    }

    /**
     * 창 안의 항목을 주차 순으로, 그 뒤에 주차를 모르는 항목 소량.
     *
     * <p>현재 주차를 모르면(개강일 없음) 자르지 않고 전부 원래 순서로 돌려준다 — 근거 없이
     * 자르면 왜 그 항목이 빠졌는지 아무도 답할 수 없다.
     */
    private List<TopicContext> withinWindow(List<TopicContext> all, Integer currentWeek) {
        if (currentWeek == null) {
            return all;
        }
        int from = currentWeek - WEEKS_BEHIND;
        int to = currentWeek + WEEKS_AHEAD;

        List<TopicContext> dated = new ArrayList<>();
        List<TopicContext> undated = new ArrayList<>();
        for (TopicContext topic : all) {
            if (topic.week() == null) {
                undated.add(topic);
            } else if (topic.week() >= from && topic.week() <= to) {
                dated.add(topic);
            }
        }
        // 같은 주차 안에서는 원래 순서(order_index)를 지킨다 — List.sort는 안정 정렬이다.
        dated.sort(Comparator.comparingInt(TopicContext::week));

        List<TopicContext> result = new ArrayList<>(dated);
        result.addAll(undated.subList(0, Math.min(undated.size(), MAX_UNDATED_TOPICS_PER_COURSE)));
        return result;
    }

    /**
     * 개강일과 오늘로 계산한 현재 주차. 개강일이 속한 주가 1주차다.
     *
     * <p>개강 전이면 1주차로 본다 — 0이나 음수 주차는 창 계산을 무의미하게 만들고, 개강 전에
     * 계획을 세운다면 볼 것은 어차피 1주차다.
     */
    private Integer weekOf(LocalDate semesterStart, LocalDate today) {
        if (semesterStart == null) {
            return null;
        }
        long days = ChronoUnit.DAYS.between(semesterStart, today);
        if (days < 0) {
            return 1;
        }
        return (int) (days / 7) + 1;
    }

    /**
     * 과목별 개강일. 그 과목 수업 루틴의 effective_from 중 가장 이른 날이다.
     *
     * <p>강의계획서의 개강일을 파싱하지 않는다. 루틴은 사용자가 확인한 값이고 분석 JSON의
     * keyDates는 모델이 원문에서 읽은 값이라, 둘이 다르면 루틴이 옳다.
     */
    private Map<Long, LocalDate> semesterStartByCourse(Long userId) {
        Map<Long, LocalDate> starts = new HashMap<>();
        for (Routine routine : routineReader.findAllWithWeekdays(userId)) {
            if (routine.getCourseId() == null || routine.isDeleted() || routine.getEffectiveFrom() == null) {
                continue;
            }
            starts.merge(routine.getCourseId(), routine.getEffectiveFrom(),
                    (a, b) -> a.isBefore(b) ? a : b);
        }
        return starts;
    }

    /** 과목별로 지금 이후 가장 이른 수업 한 건. */
    private Map<Long, RoutineOccurrence> nextClassByCourse(Long userId, LocalDateTime now, LocalDate planEnd) {
        LocalDate to = planEnd.plusDays(NEXT_CLASS_LOOKAHEAD_DAYS);
        Map<Long, RoutineOccurrence> earliest = new HashMap<>();
        for (RoutineOccurrence occurrence : routineOccurrenceService.expand(userId, now.toLocalDate(), to)) {
            // 이동시간 발생분은 수업이 아니다. 걸러내지 않으면 "다음 수업"이 한 시간 당겨진다.
            if (occurrence.lead() || occurrence.courseId() == null || !occurrence.startAt().isAfter(now)) {
                continue;
            }
            earliest.merge(occurrence.courseId(), occurrence,
                    (a, b) -> a.startAt().isBefore(b.startAt()) ? a : b);
        }
        return earliest;
    }

    /**
     * 확정된 맥락. ACTIVE가 앞, STALE이 뒤다 — 근거의 무게가 다르고, 잘릴 때는 약한 쪽이
     * 먼저 잘려야 한다.
     */
    private List<ContextLine> contextLines(Long userId) {
        List<UserContext> contexts = userContextMapper.findActiveAndStaleByUserId(userId, MAX_CONTEXT_LINES);
        List<ContextLine> lines = new ArrayList<>();
        for (UserContext context : contexts) {
            lines.add(new ContextLine(context.getContextId(), context.getContent(),
                    context.getStatus(), context.getSourceType()));
        }
        lines.sort(Comparator.comparingInt(line -> line.status().ordinal()));
        return lines;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
