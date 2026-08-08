package com.jungwoo.project.memo.learning;

import com.jungwoo.project.memo.course.CourseService;
import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.learning.domain.CourseTopic;
import com.jungwoo.project.memo.learning.domain.LearningEventType;
import com.jungwoo.project.memo.learning.domain.TopicLearningEvent;
import com.jungwoo.project.memo.learning.domain.TopicProgress;
import com.jungwoo.project.memo.learning.domain.TopicProgressStatus;
import com.jungwoo.project.memo.learning.domain.TopicSourceType;
import com.jungwoo.project.memo.learning.dto.TopicDraft;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * source/AI_DERIVED provenance 구분, topic 트리 확정, 학습 진행 상태 전이(완료=이해 자동
 * 확정 금지)를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class TopicServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long COURSE_ID = 10L;
    private static final Long TOPIC_ID = 100L;

    @Mock
    private CourseService courseService;

    @Mock
    private CourseTopicMapper courseTopicMapper;

    @Mock
    private TopicProgressMapper topicProgressMapper;

    @Mock
    private TopicLearningEventMapper topicLearningEventMapper;

    @InjectMocks
    private TopicService service;

    @Test
    void applyAnalyzedTopics_preservesSourceType_forEachNode() {
        when(courseService.getOwned(USER_ID, COURSE_ID)).thenReturn(Course.builder().courseId(COURSE_ID).build());

        // 원문에 "3.2 단순 연결 리스트"까지만 있고, AI가 학습 편의를 위해 하위 항목을 만든 상황.
        TopicDraft child = new TopicDraft("노드 구조", "AI_DERIVED", null, List.of());
        TopicDraft root = new TopicDraft("단순 연결 리스트", "SOURCE", "3.2절", List.of(child));

        int created = service.applyAnalyzedTopics(USER_ID, COURSE_ID, 5L, List.of(root));

        assertThat(created).isEqualTo(2);

        ArgumentCaptor<CourseTopic> captor = ArgumentCaptor.forClass(CourseTopic.class);
        verify(courseTopicMapper, times(2)).insert(captor.capture());

        CourseTopic insertedRoot = captor.getAllValues().get(0);
        assertThat(insertedRoot.getSourceType()).isEqualTo(TopicSourceType.SOURCE);
        assertThat(insertedRoot.getSourceLocator()).isEqualTo("3.2절");

        CourseTopic insertedChild = captor.getAllValues().get(1);
        assertThat(insertedChild.getSourceType()).isEqualTo(TopicSourceType.AI_DERIVED);
    }

    @Test
    void applyAnalyzedTopics_treatsUnknownSourceType_asAiDerived_neverAsSource() {
        when(courseService.getOwned(USER_ID, COURSE_ID)).thenReturn(Course.builder().courseId(COURSE_ID).build());

        TopicDraft malformed = new TopicDraft("이상한 항목", "NOT_A_VALID_VALUE", null, List.of());

        service.applyAnalyzedTopics(USER_ID, COURSE_ID, 5L, List.of(malformed));

        ArgumentCaptor<CourseTopic> captor = ArgumentCaptor.forClass(CourseTopic.class);
        verify(courseTopicMapper).insert(captor.capture());
        assertThat(captor.getValue().getSourceType()).isEqualTo(TopicSourceType.AI_DERIVED);
    }

    @Test
    void updateProgressStatus_recordsStatusChangedEvent_onExplicitUserAction() {
        when(courseTopicMapper.findByIdAndUserId(TOPIC_ID, USER_ID))
                .thenReturn(CourseTopic.builder().topicId(TOPIC_ID).userId(USER_ID).build());
        when(topicProgressMapper.findByUserIdAndTopicId(USER_ID, TOPIC_ID)).thenReturn(
                TopicProgress.builder().userId(USER_ID).topicId(TOPIC_ID)
                        .status(TopicProgressStatus.IN_PROGRESS).reviewCount(0).build());

        service.updateProgressStatus(USER_ID, TOPIC_ID, TopicProgressStatus.LEARNED);

        verify(topicProgressMapper).updateProgress(eq(USER_ID), eq(TOPIC_ID), eq("LEARNED"), any(), isNull(), eq(false));
        ArgumentCaptor<TopicLearningEvent> eventCaptor = ArgumentCaptor.forClass(TopicLearningEvent.class);
        verify(topicLearningEventMapper).insert(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getEventType()).isEqualTo(LearningEventType.STATUS_CHANGED);
        assertThat(eventCaptor.getValue().getToStatus()).isEqualTo(TopicProgressStatus.LEARNED);
    }

    @Test
    void recordExecutionCompleted_movesNotStartedToInProgress_neverAutoLearned() {
        when(topicProgressMapper.findByUserIdAndTopicId(USER_ID, TOPIC_ID)).thenReturn(
                TopicProgress.builder().userId(USER_ID).topicId(TOPIC_ID)
                        .status(TopicProgressStatus.NOT_STARTED).reviewCount(0).build());

        service.recordExecutionCompleted(USER_ID, TOPIC_ID, 999L);

        // "완료 버튼을 눌렀다"는 이유만으로 LEARNED로 넘어가지 않는다 — IN_PROGRESS까지만.
        verify(topicProgressMapper).updateProgress(eq(USER_ID), eq(TOPIC_ID), eq("IN_PROGRESS"), any(), isNull(), eq(false));
        verify(topicProgressMapper, never()).updateProgress(any(), any(), eq("LEARNED"), any(), any(), anyBoolean());
    }

    @Test
    void recordExecutionCompleted_treatsAlreadyLearnedTopicAsReview_incrementsReviewCount() {
        when(topicProgressMapper.findByUserIdAndTopicId(USER_ID, TOPIC_ID)).thenReturn(
                TopicProgress.builder().userId(USER_ID).topicId(TOPIC_ID)
                        .status(TopicProgressStatus.LEARNED).reviewCount(1).build());

        service.recordExecutionCompleted(USER_ID, TOPIC_ID, 999L);

        // status는 null(=변경 없음, LEARNED 유지)로 전달하고 review_count만 늘린다.
        verify(topicProgressMapper).updateProgress(eq(USER_ID), eq(TOPIC_ID), isNull(), any(), any(), eq(true));

        ArgumentCaptor<TopicLearningEvent> eventCaptor = ArgumentCaptor.forClass(TopicLearningEvent.class);
        verify(topicLearningEventMapper).insert(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getEventType()).isEqualTo(LearningEventType.EXECUTION_COMPLETED);
        assertThat(eventCaptor.getValue().getToStatus()).isEqualTo(TopicProgressStatus.LEARNED);
    }
}
