package com.jungwoo.project.memo.learning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.ai.AiConsultationClient;
import com.jungwoo.project.memo.ai.AiUsageLimitService;
import com.jungwoo.project.memo.common.exception.ServiceUnavailableException;
import com.jungwoo.project.memo.course.CourseService;
import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.learning.domain.ActivityType;
import com.jungwoo.project.memo.learning.domain.CourseTopic;
import com.jungwoo.project.memo.learning.domain.RecommendationPriority;
import com.jungwoo.project.memo.learning.domain.TopicProgress;
import com.jungwoo.project.memo.learning.domain.TopicProgressStatus;
import com.jungwoo.project.memo.learning.dto.StudyRecommendationResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LearningRecommendationServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long COURSE_ID = 10L;
    private static final Long TOPIC_ID = 100L;

    @Mock private CourseService courseService;
    @Mock private TopicService topicService;
    @Mock private CourseTopicMapper courseTopicMapper;
    @Mock private LearningContextBuilder learningContextBuilder;
    @Mock private StudyRecommendationMapper studyRecommendationMapper;
    @Mock private AiConsultationClient aiConsultationClient;
    @Mock private AiUsageLimitService aiUsageLimitService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private LearningRecommendationService service;

    @Test
    void recommend_throwsServiceUnavailable_whenAiNotConfigured() {
        when(courseService.getOwned(USER_ID, COURSE_ID)).thenReturn(Course.builder().courseId(COURSE_ID).build());
        when(aiConsultationClient.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> service.recommend(USER_ID, COURSE_ID))
                .isInstanceOf(ServiceUnavailableException.class);

        verify(studyRecommendationMapper, never()).insert(any());
    }

    @Test
    void recommend_parsesStructuredOutput_intoStudyRecommendation() {
        when(courseService.getOwned(USER_ID, COURSE_ID)).thenReturn(Course.builder().courseId(COURSE_ID).build());
        when(aiConsultationClient.isConfigured()).thenReturn(true);
        when(learningContextBuilder.buildForCourse(USER_ID, COURSE_ID)).thenReturn("[학습 컨텍스트]...");
        when(courseTopicMapper.findByIdAndUserId(TOPIC_ID, USER_ID)).thenReturn(
                CourseTopic.builder().topicId(TOPIC_ID).courseId(COURSE_ID).title("이중 연결 리스트").build());

        String json = """
                {"topicId":%d,"activityType":"NEW_LEARNING","recommendedMinutesMin":30,"recommendedMinutesIdeal":45,"priority":"SHOULD","reason":"단순/원형을 이미 마쳐서 다음 단계로 적절함"}
                """.formatted(TOPIC_ID);
        when(aiConsultationClient.streamTurn(any(), any(), anyInt()))
                .thenReturn(Flux.just(chatResponse("다음은 이중 연결 리스트를 추천해요\n<<<AI_STRUCTURED>>>\n" + json)));

        StudyRecommendationResponse response = service.recommend(USER_ID, COURSE_ID);

        assertThat(response.getTopicId()).isEqualTo(TOPIC_ID);
        assertThat(response.getActivityType()).isEqualTo(ActivityType.NEW_LEARNING);
        assertThat(response.getPriority()).isEqualTo(RecommendationPriority.SHOULD);
        verify(studyRecommendationMapper).insert(any());
    }

    @Test
    void recommend_fails_whenAiPointsAtTopicOutsideThisCourse() {
        when(courseService.getOwned(USER_ID, COURSE_ID)).thenReturn(Course.builder().courseId(COURSE_ID).build());
        when(aiConsultationClient.isConfigured()).thenReturn(true);
        when(learningContextBuilder.buildForCourse(USER_ID, COURSE_ID)).thenReturn("ctx");
        // 다른 과목(courseId=999)의 topic을 잘못 가리킨 경우 — 존재하더라도 신뢰하지 않는다.
        when(courseTopicMapper.findByIdAndUserId(TOPIC_ID, USER_ID)).thenReturn(
                CourseTopic.builder().topicId(TOPIC_ID).courseId(999L).build());

        String json = """
                {"topicId":%d,"activityType":"NEW_LEARNING","recommendedMinutesMin":30,"recommendedMinutesIdeal":45,"priority":"SHOULD","reason":"이유"}
                """.formatted(TOPIC_ID);
        when(aiConsultationClient.streamTurn(any(), any(), anyInt()))
                .thenReturn(Flux.just(chatResponse("답변\n<<<AI_STRUCTURED>>>\n" + json)));

        assertThatThrownBy(() -> service.recommend(USER_ID, COURSE_ID))
                .isInstanceOf(ServiceUnavailableException.class);

        verify(studyRecommendationMapper, never()).insert(any());
    }

    private ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
