package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.ai.UserContextMapper;
import com.jungwoo.project.memo.ai.domain.ContextSourceType;
import com.jungwoo.project.memo.ai.domain.UserContext;
import com.jungwoo.project.memo.ai.domain.UserContextStatus;
import com.jungwoo.project.memo.course.CourseMapper;
import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.learning.TopicService;
import com.jungwoo.project.memo.learning.domain.TopicProgressStatus;
import com.jungwoo.project.memo.learning.dto.TopicResponse;
import com.jungwoo.project.memo.plan.PeriodPlanDraftGenerator.Spec;
import com.jungwoo.project.memo.plan.PlanningContext.CourseContext;
import com.jungwoo.project.memo.plan.PlanningContext.TopicContext;
import com.jungwoo.project.memo.plan.domain.PlanIntensity;
import com.jungwoo.project.memo.routine.RoutineOccurrenceService;
import com.jungwoo.project.memo.routine.RoutineReader;
import com.jungwoo.project.memo.routine.domain.Routine;
import com.jungwoo.project.memo.routine.domain.RoutineOccurrence;
import com.jungwoo.project.memo.scheduling.service.AvailabilityEstimateResult;
import com.jungwoo.project.memo.scheduling.service.AvailabilityEstimateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 컨텍스트 수집. 실데이터의 모양(2026-08-25 개강, 6과목 시간표, "N주차" locator)을 그대로
 * 픽스처로 쓴다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlanningContextBuilderTest {

    private static final Long USER_ID = 7L;
    /** 2026-09-06(일) 21:37. 개강 2026-08-25로부터 12일 → 2주차. */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 6, 21, 37);
    private static final LocalDate SEMESTER_START = LocalDate.of(2026, 8, 25);
    private static final LocalDate START = LocalDate.of(2026, 9, 7);
    private static final LocalDate END = LocalDate.of(2026, 9, 13);

    @Mock
    private CourseMapper courseMapper;
    @Mock
    private TopicService topicService;
    @Mock
    private RoutineReader routineReader;
    @Mock
    private RoutineOccurrenceService routineOccurrenceService;
    @Mock
    private AvailabilityEstimateService availabilityEstimateService;
    @Mock
    private UserContextMapper userContextMapper;
    @Mock
    private PlanReviewService planReviewService;

    private PlanningContextBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new PlanningContextBuilder(courseMapper, topicService, routineReader,
                routineOccurrenceService, availabilityEstimateService, userContextMapper, planReviewService,
                Clock.fixed(NOW.atZone(ZoneId.of("Asia/Seoul")).toInstant(), ZoneId.of("Asia/Seoul")));
        ReflectionTestUtils.setField(builder, "defaultTimeZoneId", "Asia/Seoul");

        when(availabilityEstimateService.estimate(anyLong(), any(), any(), any(), any()))
                .thenReturn(new AvailabilityEstimateResult(List.of(), List.of()));
        when(userContextMapper.findActiveAndStaleByUserId(anyLong(), any())).thenReturn(List.of());
        when(planReviewService.summarizeLatestForPrompt(anyLong())).thenReturn(null);
    }

    @Test
    @DisplayName("과목은 다음 수업이 빠른 순으로 놓인다 — 수업이 없는 과목은 뒤로")
    void coursesAreOrderedByNextClass() {
        givenCourses(course(36L, "자료구조"), course(31L, "빅데이터분석"), course(90L, "수업 없는 과목"));
        givenRoutines(classRoutine(85L, 36L), classRoutine(80L, 31L));
        // 화요일 자료구조 14:00, 목요일 빅데이터 10:00
        givenOccurrences(
                occurrence(80L, 31L, "빅데이터분석", LocalDateTime.of(2026, 9, 10, 10, 0)),
                occurrence(85L, 36L, "자료구조", LocalDateTime.of(2026, 9, 8, 14, 0)));
        givenTopics(36L);
        givenTopics(31L);
        givenTopics(90L);

        PlanningContext context = builder.build(spec());

        assertThat(context.courses()).extracting(CourseContext::title)
                .containsExactly("자료구조", "빅데이터분석", "수업 없는 과목");
        assertThat(context.courses().get(0).nextClassAt()).isEqualTo(LocalDateTime.of(2026, 9, 8, 14, 0));
        assertThat(context.courses().get(0).nextClassRoutineId()).isEqualTo(85L);
        assertThat(context.courses().get(2).nextClassAt()).isNull();
    }

    @Test
    @DisplayName("현재 주차는 수업 루틴의 개강일에서 계산한다")
    void currentWeekComesFromTheClassRoutine() {
        givenCourses(course(36L, "자료구조"));
        givenRoutines(classRoutine(85L, 36L));
        givenOccurrences();
        givenTopics(36L);

        assertThat(builder.build(spec()).courses().get(0).currentWeek())
                .as("2026-08-25 개강, 오늘 2026-09-06 → 12일차 → 2주차")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("학습 항목은 현재 주차 −2 ~ +1 창으로 자르고, 주차를 모르는 항목은 뒤에 소량만")
    void topicsAreLimitedToTheWindow() {
        givenCourses(course(36L, "자료구조"));
        givenRoutines(classRoutine(85L, 36L));
        givenOccurrences();
        givenTopics(36L,
                topic(216L, "자료구조 개요", "1주차"),
                topic(217L, "추상데이터타입(ADT) 및 성능분석", "2주차"),
                topic(218L, "재귀 및 재귀 알고리즘", "3주차"),
                topic(219L, "배열, 구조체, 포인터", "4주차"),
                topic(229L, "정렬 알고리즘", "13주차"),
                topic(300L, "과목 개요", "과목 개요"),
                topic(301L, "평가 방법", null),
                topic(302L, "교수 연락처", ""),
                topic(303L, "네 번째 미상", null));

        List<TopicContext> topics = builder.build(spec()).courses().get(0).topics();

        assertThat(topics).extracting(TopicContext::topicId)
                .as("창(0~3주차) 안의 항목이 주차 순으로, 그 뒤에 주차 미상 3개")
                .containsExactly(216L, 217L, 218L, 300L, 301L, 302L);
        assertThat(topics).extracting(TopicContext::week)
                .containsExactly(1, 2, 3, null, null, null);
    }

    @Test
    @DisplayName("개강일을 모르면 창을 걸지 않는다 — 근거 없이 자르면 왜 빠졌는지 답할 수 없다")
    void noWindowWithoutASemesterStart() {
        givenCourses(course(90L, "루틴 없는 과목"));
        givenRoutines();
        givenOccurrences();
        givenTopics(90L,
                topic(400L, "1주차 내용", "1주차"),
                topic(401L, "13주차 내용", "13주차"));

        CourseContext course = builder.build(spec()).courses().get(0);

        assertThat(course.currentWeek()).isNull();
        assertThat(course.topics()).extracting(TopicContext::topicId).containsExactly(400L, 401L);
    }

    @Test
    @DisplayName("맥락은 ACTIVE가 앞, STALE이 뒤 — 잘릴 때 약한 근거가 먼저 잘려야 한다")
    void activeContextsComeFirst() {
        givenCourses();
        givenRoutines();
        givenOccurrences();
        when(userContextMapper.findActiveAndStaleByUserId(anyLong(), any())).thenReturn(List.of(
                userContext(2L, "예전에 이랬다", UserContextStatus.STALE),
                userContext(1L, "매주 토요일과 일요일은 휴무(주말)", UserContextStatus.ACTIVE)));

        assertThat(builder.build(spec()).contexts())
                .extracting(PlanningContext.ContextLine::contextId)
                .containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("현재 시각은 날짜가 아니라 시각까지 담는다 — 21:37에 오전 계획을 만들지 않기 위해")
    void nowKeepsTheTimeOfDay() {
        givenCourses();
        givenRoutines();
        givenOccurrences();

        assertThat(builder.build(spec()).now()).isEqualTo(NOW);
    }

    // ===== fixture =====

    private Spec spec() {
        return new Spec(USER_ID, START, END, PlanIntensity.NORMAL, null, null, List.of());
    }

    private void givenCourses(Course... courses) {
        when(courseMapper.findByUserIdAndStatus(eq(USER_ID), eq("ACTIVE"))).thenReturn(List.of(courses));
    }

    private void givenRoutines(Routine... routines) {
        when(routineReader.findAllWithWeekdays(USER_ID)).thenReturn(List.of(routines));
    }

    private void givenOccurrences(RoutineOccurrence... occurrences) {
        when(routineOccurrenceService.expand(eq(USER_ID), any(), any())).thenReturn(List.of(occurrences));
    }

    private void givenTopics(Long courseId, TopicResponse... topics) {
        when(topicService.getTopicTree(USER_ID, courseId)).thenReturn(List.of(topics));
    }

    private static Course course(Long courseId, String title) {
        return Course.builder().courseId(courseId).userId(USER_ID).title(title).build();
    }

    private static Routine classRoutine(Long routineId, Long courseId) {
        Routine routine = new Routine();
        routine.setRoutineId(routineId);
        routine.setUserId(USER_ID);
        routine.setCourseId(courseId);
        routine.setStartTime(LocalTime.of(14, 0));
        routine.setEndTime(LocalTime.of(17, 0));
        routine.setEffectiveFrom(SEMESTER_START);
        return routine;
    }

    private static RoutineOccurrence occurrence(Long routineId, Long courseId, String title, LocalDateTime startAt) {
        return new RoutineOccurrence(routineId, courseId, title, null,
                startAt, startAt.plusHours(3), startAt.toLocalDate(), false);
    }

    private static TopicResponse topic(Long topicId, String title, String locator) {
        return TopicResponse.builder()
                .topicId(topicId)
                .title(title)
                .sourceLocator(locator)
                .progressStatus(TopicProgressStatus.NOT_STARTED)
                .children(List.of())
                .build();
    }

    private static UserContext userContext(Long contextId, String content, UserContextStatus status) {
        UserContext context = new UserContext();
        context.setContextId(contextId);
        context.setUserId(USER_ID);
        context.setContent(content);
        context.setStatus(status);
        context.setSourceType(ContextSourceType.AI_SUGGESTION_APPROVED);
        return context;
    }
}
