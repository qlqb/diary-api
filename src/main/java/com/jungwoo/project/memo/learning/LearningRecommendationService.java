package com.jungwoo.project.memo.learning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.ai.AiChatResponseUtils;
import com.jungwoo.project.memo.ai.AiConsultationClient;
import com.jungwoo.project.memo.ai.AiStreamParser;
import com.jungwoo.project.memo.ai.AiUsageLimitService;
import com.jungwoo.project.memo.ai.domain.UsageResultStatus;
import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.common.exception.ServiceUnavailableException;
import com.jungwoo.project.memo.course.CourseService;
import com.jungwoo.project.memo.learning.domain.ActivityType;
import com.jungwoo.project.memo.learning.domain.CourseTopic;
import com.jungwoo.project.memo.learning.domain.RecommendationPriority;
import com.jungwoo.project.memo.learning.domain.RecommendationStatus;
import com.jungwoo.project.memo.learning.domain.StudyRecommendation;
import com.jungwoo.project.memo.learning.dto.RecommendationDraft;
import com.jungwoo.project.memo.learning.dto.StudyRecommendationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Learning Agent의 "다음 학습 추천". WHAT(무엇을 공부할지)만 결정하고 시간 배치는 하지 않는다 —
 * Planning Agent가 이 결과(StudyRecommendation)를 입력으로 받는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LearningRecommendationService {

    private static final String FEATURE = "LEARNING_RECOMMENDATION";

    private final CourseService courseService;
    private final TopicService topicService;
    private final CourseTopicMapper courseTopicMapper;
    private final LearningContextBuilder learningContextBuilder;
    private final StudyRecommendationMapper studyRecommendationMapper;
    private final AiConsultationClient aiConsultationClient;
    private final AiUsageLimitService aiUsageLimitService;
    private final ObjectMapper objectMapper;

    @Value("${spring.ai.openai.chat.model:gpt-5-mini}")
    private String modelName;

    @Value("${ai.learning.max-completion-tokens:2000}")
    private int maxCompletionTokens;

    @Value("${ai.request.timeout-seconds:90}")
    private int requestTimeoutSeconds;

    private static final String SYSTEM_PROMPT = """
            너는 Learning Agent다. [학습 컨텍스트]를 보고 이 학생이 지금 다음으로 무엇을
            공부해야 하는지 딱 하나만 추천한다. 시간 배치는 네 역할이 아니다 — 몇 시에 할지는
            정하지 않는다.

            activityType 판단 기준:
            - NEW_LEARNING: 아직 NOT_STARTED인 topic 중 선행 topic들이 이미 LEARNED거나 IN_PROGRESS인,
              지금 배우기 적절한 다음 topic이 있을 때.
            - REVIEW: 이미 LEARNED인 topic인데 마지막 학습/복습이 꽤 지났거나, 관련 새로운 학습을
              시작하기 전에 가볍게 다시 볼 필요가 있을 때.
            - CONTINUE: IN_PROGRESS 상태인 topic을 이어서 계속할 때.
            - SKIP: 이미 최근에 학습/복습했거나 현재는 굳이 다루지 않아도 되는 topic을 대상으로,
              "이번엔 안 해도 된다"고 판단할 때(진행 상태를 바꾸는 게 아니라 이번 추천에서
              제외해도 된다는 뜻).

            학기 진도가 이미 지나간 topic이라고 해서 무조건 REVIEW나 SKIP으로 단정하지 않는다 —
            마지막 학습일이 최근이면 SKIP, 꽤 지났으면 REVIEW, 아예 학습한 적 없으면 NEW_LEARNING이
            맞다. 근거 없이 추측하지 않는다 — [학습 컨텍스트]에 나온 상태/날짜만 근거로 삼는다.

            reason에는 왜 이 topic을 추천했는지 학생이 이해할 수 있게 한두 문장으로 쓴다.

            응답 형식(반드시 그대로 지킨다):
            1) 추천 이유를 한 문장으로 자연스럽게 적는다(reason과 같은 내용). JSON을 섞지 않는다.
            2) 그 다음 줄에 정확히 이 문자열만 한 줄로 적는다: <<<AI_STRUCTURED>>>
            3) 그 아래에는 다른 텍스트 없이 아래 스키마를 따르는 JSON 객체 하나만 적는다.

            {
              "topicId": [학습 컨텍스트]에 있던 항목의 # 뒤 숫자(정수, 반드시 그 목록에 있는 값),
              "activityType": "NEW_LEARNING" 또는 "REVIEW" 또는 "CONTINUE" 또는 "SKIP",
              "recommendedMinutesMin": 5 이상의 정수,
              "recommendedMinutesIdeal": recommendedMinutesMin 이상의 정수,
              "priority": "MUST" 또는 "SHOULD" 또는 "OPTIONAL",
              "reason": "추천 이유"
            }
            """;

    public StudyRecommendationResponse recommend(Long userId, Long courseId) {
        return recommend(userId, courseId, null);
    }

    /** workflowId가 있으면 Orchestrator가 연쇄 호출한 다른 Agent 호출과 같은 워크플로로 묶인다. */
    public StudyRecommendationResponse recommend(Long userId, Long courseId, String workflowId) {
        courseService.getOwned(userId, courseId);
        if (!aiConsultationClient.isConfigured()) {
            throw new ServiceUnavailableException(ErrorCode.AI_NOT_CONFIGURED);
        }
        aiUsageLimitService.checkLimit(userId);

        String contextBlock = learningContextBuilder.buildForCourse(userId, courseId);
        String userPrompt = "[학습 컨텍스트]\n" + contextBlock
                + "\n위 컨텍스트를 보고 다음 학습을 하나 추천해줘.";

        AiStreamParser parser = new AiStreamParser();
        AtomicReference<Usage> lastUsage = new AtomicReference<>();
        try {
            aiConsultationClient.streamTurn(SYSTEM_PROMPT, userPrompt, maxCompletionTokens)
                    .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                    .doOnNext(chatResponse -> {
                        parser.onChunk(AiChatResponseUtils.extractText(chatResponse));
                        Usage usage = AiChatResponseUtils.extractUsage(chatResponse);
                        if (usage != null) {
                            lastUsage.set(usage);
                        }
                    })
                    .blockLast();
        } catch (Exception e) {
            log.warn("Learning 추천 호출 실패: userId={}, courseId={}", userId, courseId, e);
            recordUsage(userId, workflowId, lastUsage.get(), UsageResultStatus.FAILED, ErrorCode.AI_GENERATION_FAILED.getCode());
            throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
        }

        AiStreamParser.Result result = parser.finish();
        RecommendationDraft draft = parseDraft(result.structuredJson());
        if (draft == null || draft.topicId() == null) {
            recordUsage(userId, workflowId, lastUsage.get(), UsageResultStatus.FAILED, ErrorCode.AI_GENERATION_FAILED.getCode());
            throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
        }

        CourseTopic topic = courseTopicMapper.findByIdAndUserId(draft.topicId(), userId);
        if (topic == null || !topic.getCourseId().equals(courseId)) {
            log.warn("Learning 추천이 존재하지 않는 topicId를 가리킴: userId={}, courseId={}, topicId={}",
                    userId, courseId, draft.topicId());
            recordUsage(userId, workflowId, lastUsage.get(), UsageResultStatus.FAILED, ErrorCode.AI_GENERATION_FAILED.getCode());
            throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
        }

        recordUsage(userId, workflowId, lastUsage.get(), UsageResultStatus.SUCCESS, null);

        StudyRecommendation recommendation = StudyRecommendation.builder()
                .userId(userId).courseId(courseId).topicId(topic.getTopicId())
                .activityType(toActivityType(draft.activityType()))
                .recommendedMinutesMin(draft.recommendedMinutesMin())
                .recommendedMinutesIdeal(draft.recommendedMinutesIdeal())
                .priority(toPriority(draft.priority()))
                .reason(draft.reason() != null ? draft.reason() : result.reply())
                .status(RecommendationStatus.SUGGESTED)
                .build();
        studyRecommendationMapper.insert(recommendation);
        log.info("학습 추천 생성: userId={}, courseId={}, topicId={}, activityType={}",
                userId, courseId, topic.getTopicId(), recommendation.getActivityType());

        return StudyRecommendationResponse.of(recommendation, topic.getTitle());
    }

    @Transactional(readOnly = true)
    public StudyRecommendationResponse get(Long userId, Long recommendationId) {
        StudyRecommendation recommendation = getOwned(userId, recommendationId);
        CourseTopic topic = courseTopicMapper.findByIdAndUserId(recommendation.getTopicId(), userId);
        return StudyRecommendationResponse.of(recommendation, topic != null ? topic.getTitle() : null);
    }

    @Transactional
    public void markSentToPlanning(Long userId, Long recommendationId) {
        StudyRecommendation recommendation = getOwned(userId, recommendationId);
        if (recommendation.getStatus() != RecommendationStatus.SUGGESTED) {
            throw new BadRequestException(ErrorCode.STUDY_RECOMMENDATION_NOT_SUGGESTED);
        }
        studyRecommendationMapper.updateStatus(recommendationId, RecommendationStatus.SENT_TO_PLANNING.name());
    }

    @Transactional(readOnly = true)
    public StudyRecommendation getOwned(Long userId, Long recommendationId) {
        StudyRecommendation recommendation = studyRecommendationMapper.findByIdAndUserId(recommendationId, userId);
        if (recommendation == null) {
            throw new NotFoundException(ErrorCode.STUDY_RECOMMENDATION_NOT_FOUND);
        }
        return recommendation;
    }

    private ActivityType toActivityType(String raw) {
        try {
            return ActivityType.valueOf(raw);
        } catch (Exception e) {
            return ActivityType.NEW_LEARNING;
        }
    }

    private RecommendationPriority toPriority(String raw) {
        try {
            return RecommendationPriority.valueOf(raw);
        } catch (Exception e) {
            return RecommendationPriority.SHOULD;
        }
    }

    private RecommendationDraft parseDraft(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, RecommendationDraft.class);
        } catch (Exception e) {
            log.warn("학습 추천 구조화 응답 파싱 실패: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    private void recordUsage(Long userId, String workflowId, Usage usage, UsageResultStatus resultStatus, String errorCode) {
        aiUsageLimitService.record(userId, null, null, modelName,
                AiChatResponseUtils.safeTokenCount(usage, true), null,
                AiChatResponseUtils.safeTokenCount(usage, false), resultStatus, errorCode,
                FEATURE, workflowId, java.util.UUID.randomUUID().toString(), null);
    }
}
