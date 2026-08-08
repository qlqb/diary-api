package com.jungwoo.project.memo.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.ai.AiChatResponseUtils;
import com.jungwoo.project.memo.ai.AiConsultationClient;
import com.jungwoo.project.memo.ai.AiProposalService;
import com.jungwoo.project.memo.ai.AiStreamParser;
import com.jungwoo.project.memo.ai.AiUsageLimitService;
import com.jungwoo.project.memo.ai.domain.UsageResultStatus;
import com.jungwoo.project.memo.ai.dto.AiProposalApplyRequest;
import com.jungwoo.project.memo.ai.dto.AiProposalItemResponse;
import com.jungwoo.project.memo.ai.dto.AiProposalResponse;
import com.jungwoo.project.memo.ai.dto.ProposalItem;
import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.execution.ExecutionItemService;
import com.jungwoo.project.memo.execution.domain.PlacementType;
import com.jungwoo.project.memo.learning.LearningRecommendationService;
import com.jungwoo.project.memo.learning.TopicService;
import com.jungwoo.project.memo.learning.domain.CourseTopic;
import com.jungwoo.project.memo.learning.domain.RecommendationStatus;
import com.jungwoo.project.memo.learning.domain.StudyRecommendation;
import com.jungwoo.project.memo.learning.StudyRecommendationMapper;
import com.jungwoo.project.memo.scheduling.dto.PlacedItemDto;
import com.jungwoo.project.memo.scheduling.dto.SchedulePreviewRequest;
import com.jungwoo.project.memo.scheduling.dto.SchedulePreviewResponse;
import com.jungwoo.project.memo.scheduling.service.SchedulePreviewService;
import com.jungwoo.project.memo.planning.dto.PlanningDraftResponse;
import com.jungwoo.project.memo.planning.dto.PlanningInstructionDraft;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Planning Agent: Learning Agent의 StudyRecommendation(WHAT)을 받아 기존 스케줄링
 * 파이프라인(AvailabilityEstimateService/SchedulingSolverService, SchedulePreviewService가
 * 이미 감싸고 있다)에 태워 실제 시간 후보(WHEN)를 붙인 계획 초안을 만든다.
 *
 * 이 서비스 자체는 시간을 계산하지 않는다 — AI는 사용자의 자연어 지시(있다면)를 해석해
 * horizon/분량 힌트만 만들고, 실제 배치는 그대로 SchedulePreviewService/Timefold가 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanningAgentService {

    private static final String FEATURE = "PLANNING_CHAT";
    private static final int DEFAULT_HORIZON_DAYS = 7;

    private final TopicService topicService;
    private final LearningRecommendationService learningRecommendationService;
    private final StudyRecommendationMapper studyRecommendationMapper;
    private final AiProposalService aiProposalService;
    private final SchedulePreviewService schedulePreviewService;
    private final ExecutionItemService executionItemService;
    private final AiConsultationClient aiConsultationClient;
    private final AiUsageLimitService aiUsageLimitService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Value("${spring.ai.openai.chat.model:gpt-5-mini}")
    private String modelName;

    @Value("${ai.planning.max-completion-tokens:2000}")
    private int maxCompletionTokens;

    @Value("${ai.request.timeout-seconds:90}")
    private int requestTimeoutSeconds;

    @Value("${scheduling.horizon.max-days:7}")
    private int maxHorizonDays;

    @Value("${scheduling.availability.default-time-zone:Asia/Seoul}")
    private String defaultTimeZoneId;

    private static final String SYSTEM_PROMPT = """
            너는 Planning Agent다. 학생이 이미 정해진 학습 추천(무엇을 공부할지)을 언제
            할지에 대해 자연어로 말한 지시를 해석한다. 너는 실제 시간을 계산하지 않는다 —
            며칠 범위 안에서 배치할지(horizonDays, 1~7)와, 시간 분량을 바꿔 달라는 요청이
            있으면 그 값만 뽑는다. 그 외에는 원래 추천값을 그대로 둔다.

            응답 형식: 자연스러운 한 문장 답변 다음 줄에 <<<AI_STRUCTURED>>>, 그 아래 JSON만.
            {
              "horizonDays": 1~7 사이 정수 (예: "이번 주"는 7, "내일까지"는 1~2, 지시가 없으면 7),
              "expectedMinutesOverride": 정수 또는 null (사용자가 분량을 명시했을 때만),
              "reason": "해석 이유 한 문장"
            }
            """;

    @Transactional
    public PlanningDraftResponse createDraft(Long userId, Long recommendationId, String instruction) {
        return createDraft(userId, recommendationId, instruction, null);
    }

    /** workflowId가 있으면 Orchestrator가 연쇄 호출한 다른 Agent 호출과 같은 워크플로로 묶인다. */
    @Transactional
    public PlanningDraftResponse createDraft(Long userId, Long recommendationId, String instruction, String workflowId) {
        StudyRecommendation recommendation = learningRecommendationService.getOwned(userId, recommendationId);
        if (recommendation.getStatus() != RecommendationStatus.SUGGESTED) {
            throw new BadRequestException(ErrorCode.STUDY_RECOMMENDATION_NOT_SUGGESTED);
        }
        CourseTopic topic = topicService.getOwnedTopic(userId, recommendation.getTopicId());

        ZoneId zone = ZoneId.of(defaultTimeZoneId);
        LocalDate today = ZonedDateTime.now(clock).withZoneSameInstant(zone).toLocalDate();

        int horizonDays = DEFAULT_HORIZON_DAYS;
        Integer minutesOverride = null;
        if (instruction != null && !instruction.isBlank() && aiConsultationClient.isConfigured()) {
            PlanningInstructionDraft parsed = interpretInstruction(userId, instruction, workflowId);
            if (parsed != null) {
                if (parsed.horizonDays() != null && parsed.horizonDays() >= 1 && parsed.horizonDays() <= maxHorizonDays) {
                    horizonDays = parsed.horizonDays();
                }
                minutesOverride = parsed.expectedMinutesOverride();
            }
        }
        LocalDate horizonStart = today;
        LocalDate horizonEnd = today.plusDays(Math.min(horizonDays, maxHorizonDays) - 1L);

        Integer expectedMinutes = minutesOverride != null ? minutesOverride
                : recommendation.getRecommendedMinutesIdeal() != null ? recommendation.getRecommendedMinutesIdeal()
                : recommendation.getRecommendedMinutesMin() != null ? recommendation.getRecommendedMinutesMin() : 30;

        ProposalItem item = new ProposalItem(
                topic.getTitle() + " 학습",
                recommendation.getReason(),
                expectedMinutes,
                recommendation.getPriority().name(),
                PlacementType.UNSCHEDULED,
                null, null,
                horizonStart, horizonEnd,
                null, null
        );

        AiProposalResponse proposal = aiProposalService.createFromItems(
                userId, null, null, List.of(item), horizonStart, List.of());

        studyRecommendationMapper.attachProposal(
                recommendationId, proposal.getProposalId(), RecommendationStatus.SENT_TO_PLANNING.name());

        SchedulePreviewResponse preview = schedulePreviewService.computePreview(userId, proposal.getProposalId(),
                SchedulePreviewRequest.builder().horizonStart(horizonStart).horizonEnd(horizonEnd).build());

        log.info("Planning 계획 초안 생성: userId={}, recommendationId={}, proposalId={}, horizon={}~{}",
                userId, recommendationId, proposal.getProposalId(), horizonStart, horizonEnd);

        return PlanningDraftResponse.builder()
                .recommendationId(recommendationId)
                .proposalId(proposal.getProposalId())
                .courseId(recommendation.getCourseId())
                .topicId(topic.getTopicId())
                .topicTitle(topic.getTitle())
                .horizonStart(horizonStart)
                .horizonEnd(horizonEnd)
                .proposal(proposal)
                .preview(preview)
                .build();
    }

    /**
     * 계획 초안 Apply 전용 진입점. 실제 승인·ExecutionItem 생성은 기존
     * AiProposalService.apply()를 그대로 재사용하고(승인 전 미반영 원칙은 거기서 이미 보장됨),
     * 여기서는 그 결과로 생긴 실행 조각에 학습 topic 연결과 추천 상태 갱신만 덧붙인다.
     */
    @Transactional
    public AiProposalResponse apply(Long userId, Long proposalId, AiProposalApplyRequest request) {
        StudyRecommendation recommendation = studyRecommendationMapper.findByProposalIdAndUserId(proposalId, userId);

        // UNSCHEDULED 항목은 AiProposalService.apply()가 날짜/시각을 그대로 비워버린다(원래
        // Today 화면에서 사용자가 배치 미리보기를 보고 editedItems로 TIME_FIXED를 확정해 보내는
        // 것을 전제하기 때문). Planning Agent는 이미 계산해 저장해 둔 스케줄 미리보기가 있으므로,
        // 호출자가 별도 편집을 안 보냈다면 그 미리보기 결과로 editedItems를 직접 만들어 채운다.
        AiProposalApplyRequest effectiveRequest = request;
        if (recommendation != null && (request.getEditedItems() == null || request.getEditedItems().isEmpty())) {
            effectiveRequest = withScheduledPlacement(userId, proposalId, request);
        }

        AiProposalResponse response = aiProposalService.apply(proposalId, userId, effectiveRequest);

        if (recommendation != null) {
            for (AiProposalItemResponse item : response.getItems()) {
                if (item.getCreatedItemId() != null) {
                    executionItemService.linkTopic(item.getCreatedItemId(), userId, recommendation.getTopicId());
                }
            }
            studyRecommendationMapper.updateStatus(
                    recommendation.getRecommendationId(), RecommendationStatus.PLANNED.name());
            log.info("Planning 계획 Apply 완료: userId={}, proposalId={}, recommendationId={}, topicId={}",
                    userId, proposalId, recommendation.getRecommendationId(), recommendation.getTopicId());
        }

        return response;
    }

    /** 저장된 스케줄 미리보기(placedItems)를 기존 apply()가 이해하는 editedItems로 변환한다. */
    private AiProposalApplyRequest withScheduledPlacement(Long userId, Long proposalId, AiProposalApplyRequest original) {
        AiProposalResponse proposal = aiProposalService.get(proposalId, userId);
        SchedulePreviewResponse preview = schedulePreviewService.getStoredPreview(userId, proposalId);
        Map<Long, PlacedItemDto> placedByItemId = preview != null && preview.getPlacedItems() != null
                ? preview.getPlacedItems().stream().collect(Collectors.toMap(PlacedItemDto::getProposalItemId, p -> p))
                : Map.of();

        List<AiProposalApplyRequest.EditedProposalItem> edited = new ArrayList<>();
        for (AiProposalItemResponse item : proposal.getItems()) {
            PlacedItemDto placed = placedByItemId.get(item.getProposalItemId());
            AiProposalApplyRequest.EditedProposalItem.EditedProposalItemBuilder builder =
                    AiProposalApplyRequest.EditedProposalItem.builder()
                            .proposalItemId(item.getProposalItemId())
                            .title(item.getTitle())
                            .description(item.getDescription())
                            .expectedMinutes(item.getExpectedMinutes())
                            .priority(item.getPriority());
            if (placed != null) {
                builder.placementType(PlacementType.TIME_FIXED)
                        .scheduledDate(placed.getScheduledDate())
                        .scheduledStartAt(placed.getScheduledStartAt())
                        .scheduledEndAt(placed.getScheduledEndAt());
            } else {
                builder.placementType(PlacementType.UNSCHEDULED);
            }
            edited.add(builder.build());
        }

        return AiProposalApplyRequest.builder()
                .editedItems(edited)
                .excludedItemIds(original.getExcludedItemIds())
                .build();
    }

    private PlanningInstructionDraft interpretInstruction(Long userId, String instruction, String workflowId) {
        aiUsageLimitService.checkLimit(userId);
        AiStreamParser parser = new AiStreamParser();
        AtomicReference<Usage> lastUsage = new AtomicReference<>();
        try {
            aiConsultationClient.streamTurn(SYSTEM_PROMPT, instruction, maxCompletionTokens)
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
            log.warn("Planning 지시 해석 실패 - 기본값으로 진행: userId={}", userId, e);
            recordUsage(userId, workflowId, lastUsage.get(), UsageResultStatus.FAILED, ErrorCode.AI_GENERATION_FAILED.getCode());
            return null;
        }
        recordUsage(userId, workflowId, lastUsage.get(), UsageResultStatus.SUCCESS, null);

        AiStreamParser.Result result = parser.finish();
        if (result.structuredJson() == null) {
            return null;
        }
        try {
            return objectMapper.readValue(result.structuredJson(), PlanningInstructionDraft.class);
        } catch (Exception e) {
            log.warn("Planning 지시 해석 JSON 파싱 실패 - 기본값으로 진행", e);
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
