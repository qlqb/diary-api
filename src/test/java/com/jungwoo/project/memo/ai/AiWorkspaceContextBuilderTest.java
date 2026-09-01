package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.domain.AiConversation;
import com.jungwoo.project.memo.ai.domain.AiProposalTargetScope;
import com.jungwoo.project.memo.course.CourseNoteService;
import com.jungwoo.project.memo.course.CourseService;
import com.jungwoo.project.memo.execution.ExecutionItemMapper;
import com.jungwoo.project.memo.execution.domain.ExecutionItem;
import com.jungwoo.project.memo.execution.domain.ExecutionPriority;
import com.jungwoo.project.memo.execution.domain.ExecutionStatus;
import com.jungwoo.project.memo.execution.domain.PlacementType;
import com.jungwoo.project.memo.learning.TopicService;
import com.jungwoo.project.memo.material.CourseMaterialMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.jungwoo.project.memo.course.domain.CourseStatus;
import com.jungwoo.project.memo.course.dto.CourseResponse;
import com.jungwoo.project.memo.routine.RoutineService;
import com.jungwoo.project.memo.routine.dto.RoutineResponse;
import java.time.DayOfWeek;
import java.util.Set;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 계획이 이미 틀어진 상황을 AI가 판단하려면 "무엇이 잡혀 있는지"만으로는 부족하다 —
 * 지금 몇 시인지와, 예정 시간이 이미 지났는데 아직 결론이 나지 않은 항목이 무엇인지가
 * 컨텍스트에 함께 실려야 한다. 이 테스트는 그 계약을 고정한다.
 */
@ExtendWith(MockitoExtension.class)
class AiWorkspaceContextBuilderTest {

    private static final Long USER_ID = 1L;
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 15);
    private static final LocalDateTime NOW = TODAY.atTime(15, 40);

    @Mock
    private CourseService courseService;

    @Mock
    private CourseNoteService courseNoteService;

    @Mock
    private TopicService topicService;

    @Mock
    private CourseMaterialMapper courseMaterialMapper;

    @Mock
    private ExecutionItemMapper executionItemMapper;

    @Mock
    private RoutineService routineService;

    @InjectMocks
    private AiWorkspaceContextBuilder builder;

    @Test
    void todayBlock_marksItemsWhoseScheduledTimeHasPassed_andCountsThem() {
        when(executionItemMapper.findByUserIdAndDate(USER_ID, TODAY)).thenReturn(List.of(
                timeFixed(101L, "아침 루틴", LocalTime.of(10, 0), LocalTime.of(10, 15), ExecutionStatus.PLANNED),
                timeFixed(102L, "프로젝트 작업", LocalTime.of(10, 30), LocalTime.of(11, 20), ExecutionStatus.PLANNED),
                timeFixed(103L, "산책", LocalTime.of(11, 30), LocalTime.of(11, 50), ExecutionStatus.PLANNED),
                timeFixed(104L, "알바", LocalTime.of(17, 0), LocalTime.of(23, 0), ExecutionStatus.PLANNED)));

        String block = builder.build(todayConversation(), USER_ID, NOW);

        assertThat(block).contains("현재 시각 15:40");
        assertThat(block).contains("#101 2026-08-15 10:00~10:15 아침 루틴 [PLANNED, SHOULD, 30분, 예정 시간 지남]");
        assertThat(block).contains("예정 시간이 이미 지났는데 아직 결론이 나지 않은 항목이 3개 있다");
        // 앞으로 올 일정은 밀린 것이 아니다.
        assertThat(block).contains("#104 2026-08-15 17:00~23:00 알바 [PLANNED, SHOULD, 30분]");
    }

    @Test
    void todayBlock_doesNotMarkFinishedOrUntimedItems() {
        when(executionItemMapper.findByUserIdAndDate(USER_ID, TODAY)).thenReturn(List.of(
                // 이미 결론이 난 항목은 조정 대상이 아니다.
                timeFixed(201L, "아침 루틴", LocalTime.of(10, 0), LocalTime.of(10, 15), ExecutionStatus.DONE),
                timeFixed(202L, "정리", LocalTime.of(10, 30), LocalTime.of(11, 0), ExecutionStatus.HOLD),
                // 시각을 정하지 않은 항목은 "오후가 됐다"는 이유만으로 밀린 것이 아니다.
                dateOnly(203L, "책 읽기")));

        String block = builder.build(todayConversation(), USER_ID, NOW);

        assertThat(block).doesNotContain("예정 시간 지남");
        assertThat(block).doesNotContain("아직 결론이 나지 않은 항목이");
    }

    private AiConversation todayConversation() {
        return AiConversation.builder()
                .conversationId(9L)
                .userId(USER_ID)
                .scope(AiProposalTargetScope.TODAY)
                .build();
    }

    private ExecutionItem timeFixed(Long id, String title, LocalTime start, LocalTime end, ExecutionStatus status) {
        return ExecutionItem.builder()
                .executionItemId(id)
                .userId(USER_ID)
                .title(title)
                .placementType(PlacementType.TIME_FIXED)
                .scheduledDate(TODAY)
                .scheduledStartAt(TODAY.atTime(start))
                .scheduledEndAt(TODAY.atTime(end))
                .expectedMinutes(30)
                .status(status)
                .priority(ExecutionPriority.SHOULD)
                .build();
    }

    private ExecutionItem dateOnly(Long id, String title) {
        return ExecutionItem.builder()
                .executionItemId(id)
                .userId(USER_ID)
                .title(title)
                .placementType(PlacementType.DATE_ONLY)
                .scheduledDate(TODAY)
                .expectedMinutes(30)
                .status(ExecutionStatus.PLANNED)
                .priority(ExecutionPriority.SHOULD)
                .build();
    }

    /*
     * "내 프로젝트들 보고 일주일 계획 짜줘"에서 모델이 받은 프로젝트 정보가 0바이트였다.
     * courseId가 없으면 프로젝트 블록을 통째로 건너뛰고 있었고, 그래서 과목 이름조차 모른 채
     * "다른 과목/활동 균형 잡기" 같은 일반론이 나왔다.
     */
    @Test
    void weekPlanning_includesActiveProjects_evenWithoutCourseId() {
        when(courseService.list(USER_ID, CourseStatus.ACTIVE)).thenReturn(List.of(
                course(36L, "자료구조", 12, 5, "연결 리스트"),
                course(31L, "빅데이터분석", 0, 0, null)));
        when(routineService.list(USER_ID)).thenReturn(List.of(
                classRoutine(85L, 36L, DayOfWeek.TUESDAY, LocalTime.of(14, 0), LocalTime.of(17, 0))));

        String block = builder.build(weekConversation(), USER_ID, NOW);

        assertThat(block).contains("[프로젝트] 활성 2개");
        assertThat(block).contains("#36 자료구조");
        assertThat(block).contains("#31 빅데이터분석");
        // 계획을 짜려면 무엇이 남았는지를 알아야 한다.
        assertThat(block).contains("학습 항목 5/12 완료");
        assertThat(block).contains("다음 학습 항목: 연결 리스트");
        assertThat(block).contains("수업: 화 14:00~17:00");
        // 학습 구조가 없는 프로젝트도 숨기지 않는다 — 없다는 것도 판단 재료다.
        assertThat(block).contains("학습 구조 아직 없음");
    }

    @Test
    void todayConversation_listsProjectsBriefly_withoutTopicDetail() {
        when(courseService.list(USER_ID, CourseStatus.ACTIVE)).thenReturn(List.of(
                course(36L, "자료구조", 12, 5, "연결 리스트")));

        String block = builder.build(todayConversation(), USER_ID, NOW);

        // 이름과 진도는 항상 싣는다.
        assertThat(block).contains("#36 자료구조");
        assertThat(block).contains("학습 항목 5/12 완료");
        // 오늘 대화에까지 학습 항목·수업 일정을 붙이면 예산만 쓴다.
        assertThat(block).doesNotContain("다음 학습 항목:");
        verify(routineService, never()).list(any());
    }

    @Test
    void projectsBlock_isSkipped_whenConversationHasItsOwnProject() {
        // 프로젝트가 정해진 대화는 기존 상세 블록이 맡는다 — 목록을 또 싣지 않는다.
        when(courseService.getOwned(USER_ID, 36L)).thenThrow(new IllegalStateException("stop"));

        String block = builder.build(projectConversation(36L), USER_ID, NOW);

        assertThat(block).doesNotContain("[프로젝트] 활성");
        verify(courseService, never()).list(any(), any());
    }

    private AiConversation weekConversation() {
        return AiConversation.builder()
                .conversationId(9L)
                .userId(USER_ID)
                .scope(AiProposalTargetScope.EXECUTION)
                .build();
    }

    private AiConversation projectConversation(Long courseId) {
        return AiConversation.builder()
                .conversationId(9L)
                .userId(USER_ID)
                .courseId(courseId)
                .scope(AiProposalTargetScope.TODAY)
                .build();
    }

    private CourseResponse course(Long id, String title, int topicCount, int learned, String currentTopic) {
        return CourseResponse.builder()
                .courseId(id)
                .title(title)
                .status(CourseStatus.ACTIVE)
                .topicCount(topicCount)
                .learnedTopicCount(learned)
                .currentTopicTitle(currentTopic)
                .build();
    }

    private RoutineResponse classRoutine(Long routineId, Long courseId, DayOfWeek day,
                                         LocalTime start, LocalTime end) {
        return new RoutineResponse(routineId, courseId, "수업", null, Set.of(day), start, end,
                LocalDate.of(2026, 8, 25), LocalDate.of(2026, 12, 11), false, false, false, List.of());
    }
}
