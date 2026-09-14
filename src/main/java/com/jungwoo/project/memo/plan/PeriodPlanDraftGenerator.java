package com.jungwoo.project.memo.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.ai.AiChatResponseUtils;
import com.jungwoo.project.memo.ai.AiConsultationClient;
import com.jungwoo.project.memo.ai.AiStreamParser;
import com.jungwoo.project.memo.ai.AiUsageLimitService;
import com.jungwoo.project.memo.ai.domain.UsageResultStatus;
import com.jungwoo.project.memo.ai.dto.ProposalItem;
import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.ServiceUnavailableException;
import com.jungwoo.project.memo.course.CourseMapper;
import com.jungwoo.project.memo.course.CourseNoteMapper;
import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.course.domain.CourseNoteCategory;
import com.jungwoo.project.memo.execution.ExecutionItemMapper;
import com.jungwoo.project.memo.execution.domain.ExecutionItem;
import com.jungwoo.project.memo.execution.domain.PlacementType;
import com.jungwoo.project.memo.ai.UserContextMapper;
import com.jungwoo.project.memo.ai.domain.UserContext;
import com.jungwoo.project.memo.assignment.domain.AssignmentConfirmStatus;
import com.jungwoo.project.memo.assignment.domain.CourseAssignment;
import com.jungwoo.project.memo.learning.TopicService;
import com.jungwoo.project.memo.learning.domain.TopicProgressStatus;
import com.jungwoo.project.memo.material.domain.MaterialSection;
import com.jungwoo.project.memo.material.CourseMaterialAnalysisMapper;
import com.jungwoo.project.memo.material.CourseMaterialMapper;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.material.domain.CourseMaterialAnalysis;
import com.jungwoo.project.memo.material.dto.MaterialAnalysisPayload;
import com.jungwoo.project.memo.plan.domain.PlanIntensity;
import com.jungwoo.project.memo.plan.domain.PlanStrategy;
import com.jungwoo.project.memo.plan.provenance.PlanItemEvidence;
import com.jungwoo.project.memo.plan.provenance.PlanProvenance;
import com.jungwoo.project.memo.plan.provenance.ProvenanceCollector;
import com.jungwoo.project.memo.plan.provenance.ProvenanceRepresentation;
import com.jungwoo.project.memo.plan.provenance.ProvenanceSourceType;
import com.jungwoo.project.memo.plan.provenance.ProvidedMaterial;
import com.jungwoo.project.memo.plan.provenance.ServerCalculation;
import com.jungwoo.project.memo.plan.dto.PlanDraftAiResult;
import com.jungwoo.project.memo.plan.dto.PlanJudgmentResult;
import com.jungwoo.project.memo.plan.selection.MaterialSelectionSummary;
import com.jungwoo.project.memo.plan.selection.PlanCatalogText;
import com.jungwoo.project.memo.plan.selection.PlanMaterialRetriever;
import com.jungwoo.project.memo.plan.selection.PlanMaterialSelector;
import com.jungwoo.project.memo.plan.selection.PlanRequestedMaterialResolver;
import com.jungwoo.project.memo.plan.selection.PromptTokenEstimator;
import com.jungwoo.project.memo.scheduling.domain.AvailabilityConfidence;
import com.jungwoo.project.memo.scheduling.domain.AvailabilitySource;
import com.jungwoo.project.memo.scheduling.domain.AvailabilityWindow;
import com.jungwoo.project.memo.scheduling.domain.BusySource;
import com.jungwoo.project.memo.scheduling.domain.BusyWindow;
import com.jungwoo.project.memo.scheduling.service.AvailabilityEstimateResult;
import com.jungwoo.project.memo.scheduling.service.AvailabilityEstimateService;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Set;
import java.util.HashSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 기간 계획 초안의 <b>생성 핵심</b>. 기간·강도·프로젝트 컨텍스트를 조립해 모델을 부르고,
 * 결과를 검증·정규화해 제안 항목으로 만든다. DB에 아무것도 쓰지 않는다.
 *
 * <p>계획 화면(PlanDraftService)과 AI 대화(AiConversationService)가 같은 기간 계획을 만들어야
 * 한다. 어느 탭에서 시작했든 같은 프롬프트·같은 검증·같은 항목 상한을 타야 하므로, 그 규칙을
 * 여기 한 곳에 두고 두 진입점이 호출만 한다. 프롬프트 문자열만 공유하고 검증을 각자 복사하는
 * 것은 이 클래스가 막으려는 상태다.
 *
 * <p>저장(ai_proposals + 계획 메타데이터)은 PlanDraftService가 한다 — 대화 경로는 저장을 자기
 * 턴 트랜잭션 안에서 해야 하고, 여기서 모델 호출(수십 초)과 DB 쓰기를 한 트랜잭션에 묶으면
 * 커넥션을 오래 붙잡기 때문이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PeriodPlanDraftGenerator {

    public static final int MAX_PLAN_DAYS = 31;

    /**
     * 기간 계획의 항목 개수 안전 상한. 기간 길이와 무관하게 하나다.
     *
     * <p>개수는 주 제약이 아니다(§5-1-1). "확실히 뭔가 잘못됐다"는 폭주 방지선으로만 둔다.
     * 일반 단건·조정 제안의 5개와 다르다 — 기간 계획만 이 값을 쓴다.
     *
     * <p>7일 상한이 15였을 때는 강도 목표(가용시간의 65~85%)를 15개로 나누면 항목당 평균
     * 80~105분이 되어, 다양한 길이를 요구하는 프롬프트 규칙과 상한이 서로를 무효화했다.
     * 상한은 목표 개수가 아니라 폭주 방지선이다. (2026-09-04)
     *
     * <p>실측(기본 가용시간 기준 7일 1,860분): FOCUSED 목표 1,575분을 15개로 나누면 105분,
     * 30개로 나누면 52.5분이다. NORMAL 1,200분은 40분, LIGHT 735분은 24.5분이 되어 프롬프트의
     * 참고 범위(15~90분)와 맞는다.
     */
    public static final int MAX_ITEMS = 30;

    /** 이 일수까지는 학습 예산이 상한 안에 들어온다. 넘으면 예산을 상한으로 깎는다. */
    public static final int SHORT_PLAN_DAYS = 7;

    /** 일정·평가 줄 수 상한. 개강일·시험·평가 비율이면 충분하다. */
    private static final int MAX_SCHEDULE_LINES_PER_COURSE = 8;

    /** AiProposalService가 강제하는 항목별 시간 범위. 프롬프트에도 같은 값을 알려준다. */
    public static final int MIN_ITEM_MINUTES = 5;
    public static final int MAX_ITEM_MINUTES = 120;

    private static final String FEATURE = "PLAN_DRAFT";

    private final AiConsultationClient aiConsultationClient;
    private final AiUsageLimitService aiUsageLimitService;
    private final PlanReviewService planReviewService;
    private final CourseMapper courseMapper;
    private final TopicService topicService;
    private final CourseNoteMapper courseNoteMapper;
    private final CourseMaterialAnalysisMapper analysisMapper;
    private final CourseMaterialMapper courseMaterialMapper;
    private final ExecutionItemMapper executionItemMapper;
    private final AvailabilityEstimateService availabilityEstimateService;
    private final Clock clock;
    private final PlanMaterialContextService materialContextService;
    private final UserContextMapper userContextMapper;
    private final PlanMaterialSelector materialSelector;
    private final PlanMaterialRetriever materialRetriever;
    private final PlanRequestedMaterialResolver requestedMaterialResolver;
    private final PromptTokenEstimator tokenEstimator;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    /**
     * 계획 초안 한 번의 출력 상한. 이 값은 {@link #MAX_ITEMS}에 묶여 있다 — 항목 상한을
     * 올리면 여기도 함께 올려야 한다. 항목 하나가 제목·description(행동 1~3개 + 완료 기준)·
     * reason까지 한국어로 150~200토큰이라, 30개면 그것만 5,000토큰 안팎이다. 여기에 title/
     * goalSummary와 gpt-5 계열의 reasoning 토큰까지 같은 상한을 나눠 쓴다.
     * (2026-09-05: 상한이 2,000이던 동안 9일짜리 계획이 items[5]에서 잘려 실패했다.)
     */
    /**
     * 계획 호출 한 번의 입력 토큰 예산(시스템 + 사용자, 추정·여유 포함). 넘으면 고른 구간의 원문 글자 상한을 줄이고,
     * 그래도 넘으면 뒤에서부터 원문을 싣지 않는다(결과에 NOT_RETRIEVED_BUDGET으로 남긴다).
     */
    @Value("${plan.draft.input-token-budget:24000}")
    private int planInputTokenBudget = 24000;

    /** 구간 하나에 싣는 원문 글자 상한의 단계. 예산에 맞을 때까지 앞에서부터 시도한다. */
    static final int[] RETRIEVAL_CAPS = {2400, 1600, 1000, 600, 300};

    @Value("${ai.planning.max-completion-tokens:8000}")
    private int maxCompletionTokens = 8000;

    @Value("${ai.request.timeout-seconds:90}")
    private int requestTimeoutSeconds = 90;

    @Value("${spring.ai.openai.chat.model:gpt-5.6-luna}")
    private String modelName = "gpt-5.6-luna";

    @Value("${ai.context.default-time-zone:Asia/Seoul}")
    private String defaultTimeZoneId = "Asia/Seoul";

    /**
     * 무엇을 만들지. 진입점(화면 요청, 대화)이 각자의 입력을 이 모양으로 옮긴다.
     *
     * @param instruction 자유 지시. 대화 경로는 대화에서 정한 우선순위를 여기에 요약해 넣는다.
     * @param courseIds   비어 있으면 활성 프로젝트 전체.
     */
    public record Spec(
            Long userId,
            LocalDate start,
            LocalDate end,
            PlanIntensity intensity,
            String instruction,
            String title,
            List<Long> courseIds,
            /** 이번 계획에서만 제외할 학습 항목. 저장되지 않는다 — 영구 표식(user_mark)과 다르다. */
            List<Long> excludeTopicIds,
            /** 이번 요청에서 사용자가 지정한 자료. 연결 출처(origin=USER)나 업로드 여부와 다른 것이다. */
            List<Long> requestedMaterialIds,
            /** 이번 요청에서 사용자가 지정한 구간. */
            List<Long> requestedSectionIds
    ) {
        public Spec(Long userId, LocalDate start, LocalDate end, PlanIntensity intensity, String instruction,
                    String title, List<Long> courseIds) {
            this(userId, start, end, intensity, instruction, title, courseIds, List.of(), List.of(), List.of());
        }

        public Spec(Long userId, LocalDate start, LocalDate end, PlanIntensity intensity, String instruction,
                    String title, List<Long> courseIds, List<Long> excludeTopicIds) {
            this(userId, start, end, intensity, instruction, title, courseIds, excludeTopicIds, List.of(), List.of());
        }

        public Set<Long> excludedTopicIdSet() {
            return excludeTopicIds == null ? Set.of() : new HashSet<>(excludeTopicIds);
        }

        public int days() {
            return (int) ChronoUnit.DAYS.between(start, end) + 1;
        }

        public int maxItems() {
            return maxItemsFor(days());
        }
    }

    /**
     * 모델이 만든 결과를 검증·정규화한 것. 저장 전 상태다.
     *
     * @param baselineMinutes             예전 화면 호환. targetMinutes와 같다.
     * @param targetMinutes               학습 예산. 추정 가용시간 × 강도 비율, 15분 단위 내림.
     * @param estimatedAvailableMinutes   계획 기간의 추정 남는 시간(고정 일정·지난 시간 제외).
     * @param availabilityConfidenceSummary 그 추정의 근거 요약.
     * @param reservedBufferMinutes       남는 시간 − 학습 예산. 휴식·변동 여유.
     * @param noAvailableTime             남는 시간이 0이라 모델을 부르지 않았다. items는 비어 있다.
     * @param targetCappedByItemLimit     강도 비율로 계산한 예산이 한 제안의 물리적 상한
     *                                    (30개 × 120분)을 넘어 상한으로 깎였다. 8일 이상 계획에서
     *                                    나온다 — 화면은 이 사실을 사용자에게 말해야 한다.
     * @param strategy                    이 초안을 만든 판단. 판단층을 거치지 않은 경로는 null이고,
     *                                    그때 ai_proposals.plan_strategy_json도 NULL로 남는다.
     * @param ask                         판단이 되물어야 한다고 본 경우의 질문. 이 값이 있으면
     *                                    items는 비어 있고 제안도 만들어지지 않는다 —
     *                                    noAvailableTime과 같은 성격의 "초안 없음"이다.
     */
    public record Generated(
            Spec spec,
            int baselineMinutes,
            int targetMinutes,
            String targetMinutesReason,
            boolean targetAdjusted,
            String suggestedTitle,
            String goalSummary,
            List<ProposalItem> items,
            int estimatedAvailableMinutes,
            String availabilityConfidenceSummary,
            int reservedBufferMinutes,
            boolean noAvailableTime,
            boolean targetCappedByItemLimit,
            PlanStrategy strategy,
            PlanJudgmentResult.Ask ask,

            /**
             * 이 회차에 모델에 <b>무엇을 줬는가</b>. 서버만 만들고 모델·클라이언트가 쓰지 못한다.
             * 모델을 부르지 않았거나 아직 붙이지 않은 경로는 null이고, 그때 제안의
             * plan_provenance_json도 NULL로 남아 화면이 "출처 기록 없음"으로 처리한다.
             */
            PlanProvenance provenance,

            /**
             * items와 <b>같은 순서·같은 길이</b>인 항목별 근거. 저장이 인덱스로 짝지으므로
             * 둘의 길이가 어긋나면 근거를 붙이지 않는다(잘못 붙이는 것보다 없는 편이 낫다).
             */
            List<PlanItemEvidence> itemEvidence,

            /**
             * 이번 생성에서 모델이 고른 자료와 서버가 읽어 넣은 원문. 자료 선택을 거치지 않은 경로(v0·판단)는 null.
             */
            MaterialSelectionSummary materialSelection
    ) {
        /** 자료 선택 결과가 없는 호출부용(기존 17개 인자 경로). */
        public Generated(Spec spec, int baselineMinutes, int targetMinutes, String targetMinutesReason,
                         boolean targetAdjusted, String suggestedTitle, String goalSummary,
                         List<ProposalItem> items, int estimatedAvailableMinutes,
                         String availabilityConfidenceSummary, int reservedBufferMinutes,
                         boolean noAvailableTime, boolean targetCappedByItemLimit,
                         PlanStrategy strategy, PlanJudgmentResult.Ask ask,
                         PlanProvenance provenance, List<PlanItemEvidence> itemEvidence) {
            this(spec, baselineMinutes, targetMinutes, targetMinutesReason, targetAdjusted, suggestedTitle,
                    goalSummary, items, estimatedAvailableMinutes, availabilityConfidenceSummary,
                    reservedBufferMinutes, noAvailableTime, targetCappedByItemLimit, strategy, ask,
                    provenance, itemEvidence, null);
        }

        public Generated withMaterialSelection(MaterialSelectionSummary summary) {
            return new Generated(spec, baselineMinutes, targetMinutes, targetMinutesReason, targetAdjusted,
                    suggestedTitle, goalSummary, items, estimatedAvailableMinutes,
                    availabilityConfidenceSummary, reservedBufferMinutes, noAvailableTime,
                    targetCappedByItemLimit, strategy, ask, provenance, itemEvidence, summary);
        }

        /** 되물어야 해서 초안을 만들지 않은 경우. */
        public static Generated asking(Spec spec, PlanJudgmentResult.Ask ask) {
            return new Generated(spec, 0, 0, null, false, null, null, List.of(),
                    0, null, 0, false, false, null, ask, null, List.of());
        }

        /** 만들어진 초안에 이번 회차의 출처를 붙인다. 다른 값은 그대로다. */
        public Generated withProvenance(PlanProvenance newProvenance, List<PlanItemEvidence> newEvidence) {
            return new Generated(spec, baselineMinutes, targetMinutes, targetMinutesReason, targetAdjusted,
                    suggestedTitle, goalSummary, items, estimatedAvailableMinutes,
                    availabilityConfidenceSummary, reservedBufferMinutes, noAvailableTime,
                    targetCappedByItemLimit, strategy, ask, newProvenance,
                    newEvidence == null ? List.of() : newEvidence, materialSelection);
        }

        /** 출처를 아직 붙이지 않은 호출부용(기존 15개 인자 경로). */
        public Generated(Spec spec, int baselineMinutes, int targetMinutes, String targetMinutesReason,
                         boolean targetAdjusted, String suggestedTitle, String goalSummary,
                         List<ProposalItem> items, int estimatedAvailableMinutes,
                         String availabilityConfidenceSummary, int reservedBufferMinutes,
                         boolean noAvailableTime, boolean targetCappedByItemLimit,
                         PlanStrategy strategy, PlanJudgmentResult.Ask ask) {
            this(spec, baselineMinutes, targetMinutes, targetMinutesReason, targetAdjusted, suggestedTitle,
                    goalSummary, items, estimatedAvailableMinutes, availabilityConfidenceSummary,
                    reservedBufferMinutes, noAvailableTime, targetCappedByItemLimit, strategy, ask,
                    null, List.of());
        }

        /** 가용시간 정보가 없는 호출부(테스트 등)용. */
        public Generated(Spec spec, int baselineMinutes, int targetMinutes, String targetMinutesReason,
                         boolean targetAdjusted, String suggestedTitle, String goalSummary, List<ProposalItem> items) {
            this(spec, baselineMinutes, targetMinutes, targetMinutesReason, targetAdjusted, suggestedTitle,
                    goalSummary, items, 0, null, 0, false, false, null, null, null, List.of());
        }

        /** 가용시간까지만 아는 호출부용(상한 조정 없음). */
        public Generated(Spec spec, int baselineMinutes, int targetMinutes, String targetMinutesReason,
                         boolean targetAdjusted, String suggestedTitle, String goalSummary, List<ProposalItem> items,
                         int estimatedAvailableMinutes, String availabilityConfidenceSummary,
                         int reservedBufferMinutes, boolean noAvailableTime) {
            this(spec, baselineMinutes, targetMinutes, targetMinutesReason, targetAdjusted, suggestedTitle,
                    goalSummary, items, estimatedAvailableMinutes, availabilityConfidenceSummary,
                    reservedBufferMinutes, noAvailableTime, false, null, null, null, List.of());
        }

        /** 판단 없이 만든 초안(모델 경로). 상한 조정 여부까지 아는 호출부용. */
        public Generated(Spec spec, int baselineMinutes, int targetMinutes, String targetMinutesReason,
                         boolean targetAdjusted, String suggestedTitle, String goalSummary, List<ProposalItem> items,
                         int estimatedAvailableMinutes, String availabilityConfidenceSummary,
                         int reservedBufferMinutes, boolean noAvailableTime, boolean targetCappedByItemLimit) {
            this(spec, baselineMinutes, targetMinutes, targetMinutesReason, targetAdjusted, suggestedTitle,
                    goalSummary, items, estimatedAvailableMinutes, availabilityConfidenceSummary,
                    reservedBufferMinutes, noAvailableTime, targetCappedByItemLimit, null, null,
                    null, List.of());
        }
    }

    public boolean isConfigured() {
        return aiConsultationClient.isConfigured();
    }

    /**
     * 기간을 검증하고 포함 일수를 돌려준다. 31일을 넘으면 계획이 아니라 목표에 가깝다 — 그건
     * 이 기능이 다룰 대상이 아니다.
     */
    public static int validatePeriod(LocalDate start, LocalDate end) {
        if (start == null || end == null || end.isBefore(start)) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }
        int days = (int) ChronoUnit.DAYS.between(start, end) + 1;
        if (days > MAX_PLAN_DAYS) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }
        return days;
    }

    public static int maxItemsFor(int days) {
        return MAX_ITEMS;
    }

    /** 한 제안에 담을 수 있는 학습 시간의 물리적 최대치. 항목 수 × 항목 최대 길이다. */
    public static int maxPlannableMinutes() {
        return MAX_ITEMS * MAX_ITEM_MINUTES;
    }

    /**
     * 모델을 한 번 부르고 결과를 정규화한다. 항목이 하나도 안 나오면 실패다 — 빈 계획을
     * 저장하지 않는다.
     */
    public Generated generate(Spec spec) {
        int days = validatePeriod(spec.start(), spec.end());
        if (!aiConsultationClient.isConfigured()) {
            throw new ServiceUnavailableException(ErrorCode.AI_NOT_CONFIGURED);
        }
        int maxItems = maxItemsFor(days);
        List<Course> courses = resolveCourses(spec.userId(), spec.courseIds());

        /*
         * 학습 예산은 서버가 정한다 — 추정 남는 시간 × 강도 비율. 예전에는 고정 기준선을 주고
         * 모델이 조정하게 했는데, 모델은 시간표를 못 보고 사용자는 왜 그 숫자인지 알 수 없었다.
         * 가용시간은 기존 AvailabilityEstimateService 그대로다: 지난 시간, 수업·알바(routine),
         * 약속(commitment), 시각이 박힌 실행 항목을 뺀 값이고, 근거가 없으면 기본 시간대다.
         */
        AvailabilityEstimateResult availability = availabilityEstimateService.estimate(
                spec.userId(), spec.start(), spec.end(), List.of(), List.of());
        int available = availableMinutes(availability.windows());
        int target = spec.intensity().targetMinutesFor(available);
        String confidence = confidenceSummary(availability.windows());

        if (target <= 0) {
            // 남는 시간이 없으면 항목을 억지로 만들지 않는다. 실패가 아니라 안내다.
            log.info("기간 계획 초안: 추정 가용시간 0 — 모델을 부르지 않는다. userId={}, {}~{}",
                    spec.userId(), spec.start(), spec.end());
            return new Generated(spec, target, target, null, false, defaultTitle(spec.start(), spec.end()),
                    null, List.of(), available, confidence, Math.max(0, available - target), true);
        }

        /*
         * 예산이 "항목 30개 × 120분"을 넘으면 한 제안에 물리적으로 담기지 않는다. 이때 400으로
         * 거절하지 않는다 — 사용자가 고를 수 있는 기간·강도의 조합에서 정상적으로 나오는 값이고
         * (31일 FOCUSED는 6,780분, 상한은 3,600분), 거절하면 "긴 계획은 만들 수 없다"가 된다.
         * 예산을 상한으로 깎고 그 사실을 응답과 프롬프트에 정직하게 싣는다.
         *
         * 이것은 8~31일 계획의 최종 도메인이 아니라 그 결정 전까지의 정의된 동작이다. 필요한
         * 도메인(주차별 반복 생성 / 장기 개요와 첫 주 실행 분리 / 반복 실행 항목)은 별도 판단
         * 대상이고, 그전까지 8일 이상 계획은 "이 기간에서 최대 60시간까지"로 잘려 나온다.
         *
         * 7일 이하는 실측상 이 선에 닿지 않는다(최대 FOCUSED 1,575분 < 3,600분). 그래도 계산이
         * 어긋나면 조용히 넘기지 않고 오류 로그를 남긴다.
         */
        int cap = maxPlannableMinutes();
        boolean cappedByItemLimit = target > cap;
        if (cappedByItemLimit) {
            if (days <= SHORT_PLAN_DAYS) {
                log.error("기간 계획 예산이 항목 상한을 넘음(7일 이하 — 계산 점검 필요): userId={}, days={}, "
                                + "available={}, intensity={}, target={}, cap={}",
                        spec.userId(), days, available, spec.intensity(), target, cap);
            } else {
                log.info("기간 계획 예산을 항목 상한으로 조정: userId={}, days={}, intensity={}, target={} -> {}",
                        spec.userId(), days, spec.intensity(), target, cap);
            }
            target = cap;
        }

        String generationId = "gen-" + UUID.randomUUID();
        LocalDateTime capturedAt = ZonedDateTime.now(clock).withZoneSameInstant(ZoneId.of(defaultTimeZoneId))
                .toLocalDateTime();

        /*
         * 자료 선택(모델) → 조회(서버) → 계획(모델). 서버는 후보를 보여 주고 id를 검증할 뿐 고르지 않는다.
         * 판단에 반드시 필요한 사실(진행 중·첫 미학습·과제·사용자 수정)은 선택 결과와 무관하게 계획 호출에 들어간다.
         */
        PlanRequestedMaterialResolver.Resolution requested = requestedMaterialResolver.resolve(spec.userId(), courses,
                spec.courseIds() != null && !spec.courseIds().isEmpty(), spec.requestedMaterialIds(),
                spec.requestedSectionIds(), spec.instruction());
        Set<Long> excluded = spec.excludedTopicIdSet();
        List<PlanMaterialContextService.CourseCatalog> catalogs = new ArrayList<>();
        for (Course course : courses) {
            catalogs.add(materialContextService.build(spec.userId(), course.getCourseId(), course.getTitle(),
                    excluded, requested.materialIdsOfCourse(course.getCourseId()), requested.sectionIds()));
        }
        if (!requested.unscoped().isEmpty()) {
            catalogs.add(materialContextService.buildUnscoped(spec.userId(), requested.unscoped(),
                    requested.materialIds(), requested.sectionIds()));
        }
        List<UserContext> contexts = loadUserContexts(spec.userId());

        PlanMaterialSelector.Result selection = materialSelector.select(new PlanMaterialSelector.Request(
                spec.userId(), generationId, spec.start(), spec.end(), capturedAt.toLocalDate(), spec.instruction(),
                catalogs, contexts.stream().map(UserContext::getContent).toList(), requested.materials(),
                requested.ambiguities()));

        Set<Long> scope = courses.stream().map(Course::getCourseId).collect(Collectors.toSet());
        List<PlanMaterialRetriever.Target> targets = selection.sections().stream()
                .map(s -> new PlanMaterialRetriever.Target(s.handle(), s.line().section().getSectionId(),
                        s.line().section().getFileHash(), s.catalog().courseId(),
                        s.line().topicIds().isEmpty() ? null : s.line().topicIds().get(0), s.reason()))
                .toList();
        List<PlanMaterialRetriever.Retrieved> retrieved = materialRetriever.retrieve(spec.userId(), targets, scope,
                requested.materialIds());

        PlanInputs inputs = new PlanInputs(catalogs, selection, retrieved, new java.util.LinkedHashSet<>(),
                RETRIEVAL_CAPS[0], contexts, requested);
        PlanPrompt prompt = null;
        for (int retrievalCap : RETRIEVAL_CAPS) {
            inputs = inputs.withCap(retrievalCap);
            prompt = planPrompt(spec, courses, availability, days, available, target, confidence, maxItems,
                    cappedByItemLimit, generationId, capturedAt, inputs);
            if (prompt.estimatedTokens() <= planInputTokenBudget) {
                break;
            }
        }
        Set<Long> budgetDropped = inputs.budgetDropped();
        while (prompt.estimatedTokens() > planInputTokenBudget) {
            List<Long> still = retrieved.stream().filter(r -> r.outcome().retrieved())
                    .map(r -> r.target().sectionId()).filter(id -> !budgetDropped.contains(id)).toList();
            if (still.isEmpty()) {
                log.warn("계획 초안: 원문을 모두 빼도 입력 예산을 넘는다(판단 사실·일정이 크다). 추정={}/{}",
                        prompt.estimatedTokens(), planInputTokenBudget);
                break;
            }
            int drop = Math.max(1, (int) Math.ceil(still.size() * 0.2));
            inputs.budgetDropped().addAll(still.subList(still.size() - drop, still.size()));
            prompt = planPrompt(spec, courses, availability, days, available, target, confidence, maxItems,
                    cappedByItemLimit, generationId, capturedAt, inputs);
        }

        PlanDraftAiResult ai = callAi(spec, prompt.text(), days, maxItems, generationId);
        ProvenanceCollector collector = prompt.collector();

        Normalized normalized = toProposalItems(ai, spec.start(), spec.end(), courses, collector);
        if (normalized.items().isEmpty()) {
            throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
        }

        MaterialSelectionSummary summary = summarize(inputs, prompt);
        collector.calculation(ServerCalculation.ServerCalculationKind.MATERIAL_SELECTION, true,
                new ArrayList<>(prompt.refBySection().values()), ServerCalculation.InputLineage.PARTIAL,
                "선택 호출의 입력 전문은 남기지 않는다 — 고른 id·이유·검토 범위·읽은 원문 범위만 남긴다",
                objectMapper.convertValue(summary, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                }));

        return new Generated(spec, target, target, null, false,
                blankToNull(ai.title()) != null ? ai.title() : defaultTitle(spec.start(), spec.end()),
                blankToNull(ai.goalSummary()), normalized.items(), available, confidence,
                available - target, false, cappedByItemLimit)
                .withProvenance(collector.build(), normalized.evidence())
                .withMaterialSelection(summary);
    }

    /**
     * 계획 호출 입력. 원문 상한(cap)과 예산 때문에 뺀 구간(budgetDropped)만 바뀌고 나머지는 한 생성 안에서 같다.
     */
    record PlanInputs(List<PlanMaterialContextService.CourseCatalog> catalogs, PlanMaterialSelector.Result selection,
                      List<PlanMaterialRetriever.Retrieved> retrieved, Set<Long> budgetDropped, int cap,
                      List<UserContext> contexts, PlanRequestedMaterialResolver.Resolution requested) {
        PlanInputs withCap(int newCap) {
            return new PlanInputs(catalogs, selection, retrieved, budgetDropped, newCap, contexts, requested);
        }
    }

    /**
     * @param refBySection 원문을 실제로 실은 구간 → 그 줄의 인용 번호
     * @param rendered     구간 → 실은 형태(범위·글자 수)
     */
    record PlanPrompt(String text, ProvenanceCollector collector, int estimatedTokens, Map<Long, String> refBySection,
                      Map<Long, PlanMaterialRetriever.Rendered> rendered) {
    }

    private PlanPrompt planPrompt(Spec spec, List<Course> courses, AvailabilityEstimateResult availability, int days,
                                  int available, int target, String confidence, int maxItems,
                                  boolean cappedByItemLimit, String generationId, LocalDateTime capturedAt,
                                  PlanInputs inputs) {
        /*
         * ★ 스냅샷은 프롬프트를 만들면서 같은 자리에서 모은다. 모델을 부른 뒤 DB를 다시
         *   조회해 만들면 그 사이 바뀐 값이 "그때 준 값"으로 저장된다(handoff §4).
         *   예산에 맞추느라 여러 번 만들면 매번 새 수집기로 만든다 — 실제로 보낸 마지막 판만 남는다.
         */
        ProvenanceCollector collector = new ProvenanceCollector(generationId, capturedAt, defaultTimeZoneId,
                spec.start(), spec.end(), "AI", modelName);
        Map<Long, String> refBySection = new LinkedHashMap<>();
        Map<Long, PlanMaterialRetriever.Rendered> rendered = new LinkedHashMap<>();
        String text = buildUserPrompt(spec, courses, availability, days, available, target, confidence, maxItems,
                cappedByItemLimit, collector, inputs, refBySection, rendered);
        return new PlanPrompt(text, collector, tokenEstimator.estimateCall(SYSTEM_PROMPT, text), refBySection, rendered);
    }

    private MaterialSelectionSummary summarize(PlanInputs inputs, PlanPrompt prompt) {
        PlanMaterialSelector.Result selection = inputs.selection();
        Map<Long, PlanMaterialSelector.SelectedSection> byId = new LinkedHashMap<>();
        selection.sections().forEach(s -> byId.put(s.line().section().getSectionId(), s));
        List<MaterialSelectionSummary.SectionPick> sections = new ArrayList<>();
        for (PlanMaterialRetriever.Retrieved r : inputs.retrieved()) {
            PlanMaterialSelector.SelectedSection picked = byId.get(r.target().sectionId());
            if (picked == null) {
                continue;
            }
            var line = picked.line();
            String outcome;
            PlanMaterialRetriever.Rendered shown = prompt.rendered().get(r.target().sectionId());
            if (!r.outcome().retrieved()) {
                outcome = r.outcome().name();
            } else if (shown == null) {
                outcome = PlanMaterialRetriever.Outcome.NOT_RETRIEVED_BUDGET.name();
            } else {
                outcome = shown.outcome().name();
            }
            sections.add(new MaterialSelectionSummary.SectionPick(line.section().getSectionId(),
                    line.section().getMaterialId(), picked.catalog().courseId(), line.section().getDisplayTitle(),
                    line.section().locator(), line.material() == null ? null : line.material().getOriginalFilename(),
                    picked.reason(), outcome, shown == null ? null : shown.rangeLabel(),
                    shown == null ? null : shown.chars(), r.target().topicId(),
                    prompt.refBySection().get(r.target().sectionId())));
        }
        List<MaterialSelectionSummary.TopicPick> topics = selection.topics().stream()
                .map(t -> new MaterialSelectionSummary.TopicPick(t.line().topicId(), t.catalog().courseId(),
                        t.line().title(), t.reason()))
                .toList();
        List<MaterialSelectionSummary.Unreviewed> unreviewed = selection.unreviewed().stream()
                .map(u -> new MaterialSelectionSummary.Unreviewed(u.courseTitle(), u.title(), u.topics(), u.sections(),
                        u.summarized()))
                .toList();
        List<MaterialSelectionSummary.RequestedMaterialView> requested = inputs.requested().materials().stream()
                .map(m -> new MaterialSelectionSummary.RequestedMaterialView(m.materialId(), m.filename(), m.courseId(),
                        m.source().name()))
                .toList();
        List<MaterialSelectionSummary.AmbiguityView> ambiguities = inputs.requested().ambiguities().stream()
                .map(a -> new MaterialSelectionSummary.AmbiguityView(a.mention(), a.candidates().stream()
                        .map(c -> new MaterialSelectionSummary.RequestedMaterialView(c.materialId(), c.filename(),
                                c.courseId(), "CANDIDATE"))
                        .toList()))
                .toList();
        List<MaterialSelectionSummary.ExcludedTopicView> excludedTopics = inputs.catalogs().stream()
                .flatMap(c -> c.excluded().stream())
                .map(e -> new MaterialSelectionSummary.ExcludedTopicView(e.topicId(), e.title(), e.reason()))
                .toList();
        return new MaterialSelectionSummary(selection.status().name(), selection.mode().name(), selection.calls(),
                selection.expanded(), selection.candidateTotal(), selection.candidateShown(), sections, topics,
                unreviewed, selection.insufficientEvidence(), selection.note(), selection.unknownIds(),
                selection.overLimit(), requested, ambiguities, excludedTopics, selection.estimatedInputTokens(),
                prompt.estimatedTokens(), inputs.cap());
    }

    /** 가용 구간의 분 합계. 구간은 이미 겹치지 않게 계산돼 있다. */
    static int availableMinutes(List<AvailabilityWindow> windows) {
        long total = 0;
        for (AvailabilityWindow window : windows) {
            total += Math.max(0, window.durationMinutes());
        }
        return Math.toIntExact(total);
    }

    /**
     * 추정 근거 요약. 기본 시간대(근거 없음, LOW)가 섞여 있으면 그 사실을 말한다 — 화면과
     * 모델 모두 이 값을 확정 사실처럼 다루면 안 된다.
     */
    static String confidenceSummary(List<AvailabilityWindow> windows) {
        if (windows.isEmpty()) {
            return "배치할 수 있는 시간이 없음";
        }
        boolean anyDefault = windows.stream()
                .anyMatch(w -> w.source() == AvailabilitySource.DEFAULT_INFERENCE
                        || w.confidence() == AvailabilityConfidence.LOW);
        boolean allDefault = windows.stream()
                .allMatch(w -> w.source() == AvailabilitySource.DEFAULT_INFERENCE);
        if (allDefault) {
            return "기본 시간대(09~23시에서 확정 일정을 뺀 시간)를 사용한 추정";
        }
        if (anyDefault) {
            return "일부는 기본 시간대를 사용한 추정";
        }
        return "사용자가 확인한 시간 기준";
    }

    // ===== AI 호출 =====

    private static final String SYSTEM_PROMPT = """
            너는 사용자의 기간 학습 계획 초안을 만드는 조수다.

            응답 형식: 자연어 한두 문장 + "%s" + 구조화 JSON.

            구조화 JSON:
            {
              "title": "계획 제목 (짧게)",
              "goalSummary": "이 기간에 무엇을 이루려는지 한 문장 (없으면 null)",
              "items": [
                {
                  "title": "과목·대상·행동이 드러나는, 한 번에 앉아서 할 만한 단위의 할 일",
                  "description": "실제로 할 행동 1~3개 · 완료: 확인 가능한 완료 기준",
                  "expectedMinutes": 정수,
                  "priority": "MUST" | "SHOULD" | "OPTIONAL",
                  "courseId": 정수 또는 null,
                  "scheduledDate": "YYYY-MM-DD" 또는 null,
                  "reason": "왜 이걸 지금 하는지 한 문장",
                  "refIds": ["s3"]
                }
              ]
            }

            규칙:
            - refIds에는 이 항목의 근거가 된 입력 줄의 대괄호 값(예: s3)만 넣는다. 아래
              입력에 실제로 있는 값만 쓴다 — 없는 값은 서버가 버리고 근거로 보여주지 않는다.
              근거로 삼은 줄이 없으면 빈 배열로 둔다. 있어 보이게 채우지 마라.
            - courseId는 [대상 프로젝트]에 실린 id만 쓴다. 해당 없으면 null.
            - scheduledDate는 "반드시 그날 해야 하는" 항목에만 넣는다(마감·수업 연동 등).
              대부분은 null로 두어라 — 날짜는 나중에 사용자가 주 단위로 배치한다.
            - expectedMinutes는 %d~%d 사이여야 한다. 벗어나면 그 항목은 버려진다.
            - 항목 길이는 실제 행동과 완료 기준에 필요한 시간으로 정한다. 목표 시간은 반드시
              소진할 할당량이 아니라 계획 예산이다 — 목표를 채우려고 개별 항목 시간을 늘리지
              않는다. 같은 행동을 의미 없이 쪼개 항목 수만 늘리지 않고, 서로 다른 행동을 억지로
              한 항목에 합치지도 않는다.
              참고 범위: 짧은 회수·환경 확인 15~30분, 수업 직후 핵심 복습 20~40분, 짧은 코드
              실습 30~45분, 개념 이해와 문제 풀이 45~90분. 배치 격자와 맞도록 짧은 작업은
              15분을 우선한다. 15분 미만은 사용자가 명시했거나 작업상 필요한 경우에만 쓴다.
            - 항목은 사용자가 앉아서 바로 시작할 수 있는 학습 행동이어야 한다. 제목에
              과목·대상·행동이 드러나야 하고, description에는 실제로 할 행동 1~3개와
              확인 가능한 완료 기준을 "행동 · 완료: 기준" 형식으로 적는다. "교재 진도 복습",
              "개념 정리", "복습 및 실습"처럼 무엇을 할지 사용자가 다시 판단해야 하는 표현은
              쓰지 않는다.
              나쁜 예: 제목 "자료구조 핵심 복습", description "교재 진도 정리"
              좋은 예: 제목 "자료구조 · 반복문 코드의 Big-O 판단", description "단일·중첩
              반복문 코드 5개의 시간복잡도를 판단하고 이유를 한 줄씩 작성 · 완료: 5개 중
              4개 이상 설명 가능"
            - 출처는 [대상 프로젝트]에 실린 학습 항목 제목과 그 옆 괄호의 위치만 쓴다.
              자료 파일명·교재 장·쪽수·"같은 출처"처럼 거기 없는 출처 표현을 만들지 않는다.
              근거가 없으면 출처를 적지 않는다. [고른 자료 구간]에 실린 구간 제목·위치·자료 이름은 거기 있는 출처다.
            - "전체 학습 구조 설계", "기본 학습목록 만들기", "커리큘럼 정리", "공부 계획 다시
              세우기" 같은 관리 작업은 사용자가 [사용자 지시]에서 그것을 요청했을 때만 만든다.
              계획 요청은 학습 실행 항목을 달라는 뜻이다.
            - 사용자를 탓하거나 뒤처졌다는 식으로 쓰지 마라. 못 한 것은 "아직 시작하지
              않았어요" 정도로만 다룬다.
            - [고른 자료 구간]의 따옴표 블록은 이번 계획을 위해 저장된 자료에서 읽은 원문이다("읽은 범위"에 어디까지
              읽었는지 있다). 구체적인 문제·예제·쪽수는 이 블록에 있는 것만 쓴다. 이 구간의 인용 번호를 refIds에 넣으면
              "이 원문을 봤다"는 뜻이다. 원문 블록이 없는 학습 항목에 대해 "이 문제를 확인했다"처럼 원문을 본 듯 쓰지 마라.
              [자료 선택 결과]에 보지 못한 범위가 있으면 그 범위를 검토했다고 쓰지 않는다.
            - 무엇을 먼저 할지는 네가 정한다 — 사용자 지시·확인된 맥락(선호), 진행 상태, 마감, 자료의 내용을 함께 보고
              판단하고 reason에 한 문장으로 적는다. 서버는 역할(문제/예제/설명)의 순서를 정하지 않는다.
            - [판단에 필요한 사실]은 서버가 반드시 보여 주는 사실이지 "이 항목을 꼭 넣어라"는 지시가 아니다.
              "진행 중"은 이어서 하고, "← 첫 미학습"은 기록이 없는 사용자가 출발할 기본 자리다 — 사용자가 다른 목표를
              말했으면 그 목표가 먼저다. "학습 완료"는 사용자 지시나 시험 근거가 있을 때만 "복습"임을 밝혀 넣는다.
            - 과제: "(확인 전)" 후보는 과제로 단정하지 않고, 마감이 "추정"인 것은 확정 마감처럼 다루지 않는다.
              과제 제출·과제 수행 자체를 항목으로 만들거나 시간을 잡는 것은 [사용자 지시]가 요청했을 때만 한다 —
              마감이 있다는 사실만으로 과제 작업 시간을 넣지 않는다. 대신 과제에 필요한 개념 학습·연습은 제안해도 된다.
              완료한 과제는 원문에 과제 문구가 남아 있어도 다시 수행하게 하지 않는다.
            - description에는 "어느 자료의 어느 부분을 어떤 순서로 보고, 어디에 집중할지"가 드러나게 쓴다.
              제목을 길게 만들지 말고, 원문에 없는 문제 번호·페이지는 만들지 않는다.
            - 제목은 짧고 구체적으로. 보는 순서·집중할 부분·참고할 자료는 description에 적는다.
            """.formatted(AiStreamParser.DELIMITER, MIN_ITEM_MINUTES, MAX_ITEM_MINUTES);

    private PlanDraftAiResult callAi(Spec spec, String userPrompt, int days, int maxItems, String workflowId) {
        Long userId = spec.userId();
        // 예산(plan.draft.input-token-budget)을 실측과 대조하기 위한 숫자. 원문은 남기지 않는다.
        log.info("계획 초안 프롬프트: userId={}, 글자={}자, 추정토큰(여유 없음)={}, workflowId={}", userId,
                userPrompt.length(), tokenEstimator.rawCall(SYSTEM_PROMPT, userPrompt), workflowId);
        AiStreamParser parser = new AiStreamParser();
        AtomicReference<Usage> lastUsage = new AtomicReference<>();
        AtomicReference<String> lastFinishReason = new AtomicReference<>();
        try {
            aiConsultationClient.streamTurn(SYSTEM_PROMPT, userPrompt, maxCompletionTokens)
                    .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                    .doOnNext(chatResponse -> {
                        parser.onChunk(AiChatResponseUtils.extractText(chatResponse));
                        Usage usage = AiChatResponseUtils.extractUsage(chatResponse);
                        if (usage != null) {
                            lastUsage.set(usage);
                        }
                        String finishReason = AiChatResponseUtils.extractFinishReason(chatResponse);
                        if (finishReason != null) {
                            lastFinishReason.set(finishReason);
                        }
                    })
                    .blockLast();
        } catch (Exception e) {
            recordUsage(userId, lastUsage.get(), UsageResultStatus.FAILED, ErrorCode.AI_GENERATION_FAILED.getCode(),
                    workflowId);
            log.warn("계획 초안 생성 AI 호출 실패: userId={}", userId, e);
            throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
        }
        recordUsage(userId, lastUsage.get(), UsageResultStatus.SUCCESS, null, workflowId);

        AiStreamParser.Result result = parser.finish();
        /*
         * 출력이 상한에서 잘렸으면 JSON도 반드시 중간에서 끊긴다. 이걸 먼저 잡지 않으면
         * 아래 파싱 실패로만 보고돼 "모델이 이상한 JSON을 냈다"로 읽히고, 실제 원인인 토큰
         * 예산이 스택트레이스 뒤에 숨는다(2026-09-05에 실제로 그렇게 한 번 헤맸다).
         * gpt-5 계열은 reasoning 토큰도 이 상한을 함께 쓰므로 여유가 생각보다 적다.
         */
        String finishReason = lastFinishReason.get();
        if (AiChatResponseUtils.isTruncatedByTokenLimit(finishReason)) {
            log.warn("계획 초안 생성: 출력이 토큰 상한({})에서 잘림 — 항목 상한({})을 담기에 예산이 "
                            + "부족하다. userId={}, days={}, maxItems={}, outputTokens={}",
                    maxCompletionTokens, MAX_ITEMS, userId, days, maxItems,
                    AiChatResponseUtils.safeTokenCount(lastUsage.get(), false));
            throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
        }
        if (result.structuredJson() == null) {
            log.warn("계획 초안 생성: 구조화 JSON이 없음. userId={}, finishReason={}", userId, finishReason);
            throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
        }
        try {
            return objectMapper.readValue(result.structuredJson(), PlanDraftAiResult.class);
        } catch (Exception e) {
            log.warn("계획 초안 생성: 구조화 JSON 파싱 실패. userId={}, finishReason={}", userId, finishReason, e);
            throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
        }
    }

    private String buildUserPrompt(Spec spec, List<Course> courses, AvailabilityEstimateResult availability,
                                   int days, int available, int target, String confidence, int maxItems,
                                   boolean cappedByItemLimit, ProvenanceCollector collector, PlanInputs inputs,
                                   Map<Long, String> refBySection,
                                   Map<Long, PlanMaterialRetriever.Rendered> rendered) {
        Long userId = spec.userId();
        LocalDate start = spec.start();
        LocalDate end = spec.end();
        StringBuilder sb = new StringBuilder();
        LocalDate today = ZonedDateTime.now(clock).withZoneSameInstant(ZoneId.of(defaultTimeZoneId)).toLocalDate();

        sb.append("[기간]\n")
                .append(start).append(" ~ ").append(end)
                .append(" (").append(days).append("일, 오늘은 ").append(today).append(")\n\n");

        // ★ 예산은 서버가 정했다. 모델은 이 안에서 무엇을 할지만 정한다 — 숫자를 다시 정하지
        //   않는다. 예전의 "기준선을 조정하라"는 시간표를 못 보는 모델에게 숫자를 맡기는 것이었다.
        sb.append("[시간]\n")
                .append("이 기간의 추정 남는 시간은 약 ").append(available).append("분이다")
                .append("(고정 일정과 지난 시간을 뺀 값, ").append(confidence).append("). ")
                .append("강도 ").append(spec.intensity().name()).append("(남는 시간의 ")
                .append(spec.intensity().getFillPercent()).append("%) 기준 학습 예산은 ")
                .append(target).append("분이다. ")
                .append("이 목표는 계획 예산이지 소진할 할당량이 아니다 — ")
                .append("항목들의 예상 시간 합이 목표를 넘기지 않게 하되, 목표를 채우려고 항목 시간을 ")
                .append("늘리지 마라. 항목을 잘게 쪼개 개수를 늘리지 마라. 채울 내용이 없으면 ")
                .append("억지로 채우지 말고 적게 제안하라.\n")
                // 개수 상한은 목표가 아니다. 15개였을 때는 상한이 사실상 "항목당 100분"을
                // 강제해 길이 규칙을 무효화했다 — 그래서 상한을 올리고 그 의미를 명시한다.
                .append("항목은 최대 ").append(maxItems).append("개다. 이것은 만들어야 할 개수가 아니라 ")
                .append("넘으면 안 되는 최대치다. 개수를 채우려 하지 말고, 각 작업에 실제로 필요한 ")
                .append("길이를 먼저 정한 다음 필요한 만큼만 만들어라. 15~30분짜리 짧은 항목을 넣기 위해 ")
                .append("다른 항목을 길게 부풀리지 마라 — 남는 예산은 그대로 남겨도 된다.\n\n");
        if (cappedByItemLimit) {
            sb.append("[분량 안내]\n")
                    .append("이 기간의 남는 시간에 강도를 적용한 값은 위 학습 예산보다 크지만, 한 번에 ")
                    .append("계획할 수 있는 최대치(항목 ").append(maxItems).append("개 × ")
                    .append(MAX_ITEM_MINUTES).append("분)에 맞춰 예산을 줄였다. ")
                    .append("기간 전체를 빈틈없이 채우려 하지 말고, 이 예산 안에서 지금 가장 중요한 것부터 ")
                    .append("담아라.\n\n");
        }

        appendAvailabilityBlocks(sb, availability, available, confidence,
                spec.intensity(), target, collector);

        /*
          학습 항목과 일정을 줘도 어디까지가 "지금"인지는 따로 말해주지 않으면 모른다.
          실험에서 이 지시가 없으면 한참 뒤 주차 내용까지 당겨왔다.
        */
        sb.append("[진도 기준]\n")
                .append("학습 항목과 자료는 [판단에 필요한 사실]과 [고른 학습 항목]·[고른 자료 구간] 안에서 고른다. ")
                .append("항목 옆 괄호는 자료에서 확인한 위치다. 일정에 개강일이 있으면 오늘 날짜와 대조해 지금이 ")
                .append("몇 주차인지 계산하고, 이번 주와 다음 주 진도에 집중하라. 한참 뒤 주차 ")
                .append("내용을 당겨오지 마라. 학습 항목에 없는 것을 지어내지 마라 — 교재의 장 ")
                .append("번호나 쪽수처럼 자료에 없는 값은 쓰지 않는다.\n")
                .append("줄 끝 대괄호(예: [s7])는 그 줄의 인용 번호다. 항목의 refIds에 이 값만 쓴다.\n\n");

        appendUserContexts(sb, inputs.contexts(), collector);

        appendSelectionNote(sb, inputs);

        sb.append("[대상 프로젝트]\n");
        if (courses.isEmpty() && inputs.catalogs().isEmpty()) {
            sb.append("(없음 — 프로젝트에 묶이지 않는 할 일만 제안해도 된다)\n");
        }
        Map<Long, PlanMaterialContextService.CourseCatalog> catalogByCourse = new LinkedHashMap<>();
        inputs.catalogs().forEach(c -> catalogByCourse.put(c.courseId(), c));
        for (Course course : courses) {
            appendCourseContext(sb, userId, course, catalogByCourse.get(course.getCourseId()), inputs, collector,
                    refBySection, rendered);
        }
        if (catalogByCourse.containsKey(null)) {
            appendCourseContext(sb, userId, null, catalogByCourse.get(null), inputs, collector, refBySection, rendered);
        }

        List<ExecutionItem> existing =
                executionItemMapper.findByUserIdAndPlanningRange(userId, start, end);
        sb.append("[이 기간에 이미 있는 일정]\n");
        if (existing.isEmpty()) {
            sb.append("(없음)\n");
        }
        for (ExecutionItem item : existing) {
            StringBuilder line = new StringBuilder(item.getTitle());
            if (item.getPlacementType() == PlacementType.TIME_FIXED) {
                line.append(" · ").append(item.getScheduledStartAt()).append(" (고정)");
            } else if (item.getScheduledDate() != null) {
                line.append(" · ").append(item.getScheduledDate());
            } else {
                line.append(" · 날짜 미정");
            }
            if (item.getExpectedMinutes() != null) {
                line.append(" · ").append(item.getExpectedMinutes()).append("분");
            }
            sb.append("- ").append(collector.mark(
                    ProvenanceSourceType.EXECUTION_ITEM_PLANNED, item.getExecutionItemId(),
                    item.getVersion(), item.getUpdatedAt(), ProvenanceRepresentation.SELECTED_FIELDS,
                    ProvenanceCollector.value(
                            "title", item.getTitle(),
                            "placementType", String.valueOf(item.getPlacementType()),
                            "scheduledDate", item.getScheduledDate(),
                            "scheduledStartAt", item.getScheduledStartAt(),
                            "expectedMinutes", item.getExpectedMinutes()),
                    line.toString()).text()).append("\n");
        }
        sb.append("\n");

        String previous = planReviewService.summarizeLatestForPrompt(userId);
        if (previous != null) {
            /*
             * 회고 요약은 특정 행 하나가 아니라 서버가 여러 행을 접어 만든 문장이다.
             * SUMMARY_LINE으로 남겨 "원본 한 줄"로 오해되지 않게 한다.
             */
            sb.append("[직전 계획 회고]\n").append(collector.mark(
                    ProvenanceSourceType.PLAN_REVIEW, null, null, null,
                    ProvenanceRepresentation.SUMMARY_LINE,
                    ProvenanceCollector.value("summary", previous), previous).text()).append("\n\n");
        }

        /*
         * 이번 턴에 사용자가 방금 말한 것. 확정된 맥락(user_contexts)으로 가장하지 않으려고
         * TURN_INPUT으로 따로 남긴다(handoff §4). 대화 이력 전체를 출처로 바꾸지는 않는다 —
         * 여기 실리는 것은 이 요청에 실제로 담겨 프롬프트로 나간 문장뿐이다.
         */
        if (spec.instruction() != null && !spec.instruction().isBlank()) {
            sb.append("[사용자 지시]\n").append(collector.mark(
                    ProvenanceSourceType.TURN_INPUT, null, null, null,
                    ProvenanceRepresentation.EXCERPT,
                    ProvenanceCollector.value("field", "instruction", "text", spec.instruction()),
                    spec.instruction()).text()).append("\n\n");
        }
        if (spec.title() != null && !spec.title().isBlank()) {
            sb.append("[사용자가 정한 제목]\n").append(collector.mark(
                    ProvenanceSourceType.TURN_INPUT, null, null, null,
                    ProvenanceRepresentation.EXCERPT,
                    ProvenanceCollector.value("field", "title", "text", spec.title()),
                    spec.title()).text()).append("\n");
        }
        return sb.toString();
    }

    /** 한 블록에 실을 일정·가용 구간 줄 수 상한. 31일 계획이면 하루 한두 줄로도 이 근처다. */
    private static final int MAX_WINDOW_LINES = 40;

    /**
     * 기간 안의 고정 일정과 남는 시간. 계획 화면에서든 대화에서든 같은 재료다 — 대화의
     * [이번 주 일정]/[남는 시간(추정)]과 같은 뜻이지만 여기서는 기간 전체를 본다.
     */
    private void appendAvailabilityBlocks(StringBuilder sb, AvailabilityEstimateResult availability,
                                          int available, String confidence, PlanIntensity intensity,
                                          int target, ProvenanceCollector collector) {
        sb.append("[이번 기간에 이미 등록된 일정]\n");
        List<BusyWindow> busy = new ArrayList<>(availability.busyWindows());
        busy.sort(java.util.Comparator.comparing(BusyWindow::startAt));
        if (busy.isEmpty()) {
            sb.append("(없음)\n");
        }
        int shown = 0;
        boolean busyTruncated = false;
        for (BusyWindow window : busy) {
            if (shown++ >= MAX_WINDOW_LINES) {
                sb.append("- … 외 ").append(busy.size() - MAX_WINDOW_LINES).append("건\n");
                busyTruncated = true;
                break;
            }
            /*
             * ★ 줄을 쓰고 나서 따로 기록하지 않는다. 기록이 줄을 만들어 돌려주고 그 값을
             *   그대로 쓴다 — 준 것과 남은 것이 갈라질 자리를 없앤다. 상한에 걸려 실리지
             *   않은 건은 mark를 부르지 않으므로 출처 목록에도 없다.
             */
            sb.append("- ").append(collector.mark(
                    busySourceType(window), window.sourceId(), null, null,
                    ProvenanceRepresentation.SELECTED_FIELDS,
                    ProvenanceCollector.value(
                            "label", window.label(),
                            "startAt", window.startAt(),
                            "endAt", window.endAt()),
                    renderSpan(window.startAt(), window.endAt()) + " " + window.label()).text()).append('\n');
        }
        sb.append("이 시간은 새로 만들 대상이 아니다. 계획은 이 시간을 피해 잡힌다.\n\n");

        sb.append("[남는 시간(추정)]\n");
        List<AvailabilityWindow> windows = availability.windows();
        if (windows.isEmpty()) {
            sb.append("(없음)\n");
        }
        shown = 0;
        for (AvailabilityWindow window : windows) {
            if (shown++ >= MAX_WINDOW_LINES) {
                sb.append("- … 외 ").append(windows.size() - MAX_WINDOW_LINES).append("구간\n");
                break;
            }
            sb.append("- ").append(renderSpan(window.startAt(), window.endAt()))
                    .append(" (").append(window.durationMinutes()).append("분)\n");
        }
        sb.append("합계 약 ").append(available).append("분 · ").append(confidence)
                .append(". 사용자가 확정한 값이 아니라 추정이므로 확정된 것처럼 말하지 않는다.\n\n");

        recordAvailabilityCalculations(availability, available, confidence, intensity, target,
                busyTruncated, collector);
    }

    /**
     * 남는 시간과 학습 예산은 <b>출처가 아니라 서버 계산</b>이다.
     *
     * <p>원본 일정과 같은 목록에 두면 사용자가 "등록된 사실"과 "추정"을 구분할 수 없다
     * (handoff §3.2). AvailabilityEstimateService가 낸 source/confidence는 의미를 그대로
     * 옮긴다 — 새 척도로 바꾸지 않는다.
     *
     * <p>입력 계보는 PARTIAL이다. 하루 기본 창(09~23시)과 현재 시각은 서버 상수·시계에서
     * 오므로 가리킬 원본 행이 없다. 일부만 담아 놓고 "전체 재현 가능"이라고 말하지 않는다.
     */
    private void recordAvailabilityCalculations(AvailabilityEstimateResult availability, int available,
                                                String confidence, PlanIntensity intensity, int target,
                                                boolean busyTruncated, ProvenanceCollector collector) {
        List<String> busyRefs = collector.refIdsOfType(Set.of(
                ProvenanceSourceType.ROUTINE_OCCURRENCE,
                ProvenanceSourceType.COMMITMENT,
                ProvenanceSourceType.EXECUTION_ITEM_FIXED));

        String note = "하루 기본 창(09~23시)과 현재 시각은 서버 상수·시계에서 와 가리킬 원본 행이 없다";
        if (busyTruncated) {
            note = note + ". 프롬프트 줄 상한에 걸려 일부 일정은 모델 입력에도 출처 목록에도 실리지 않았다";
        }

        List<Map<String, Object>> windowValues = new ArrayList<>();
        for (AvailabilityWindow window : availability.windows()) {
            windowValues.add(ProvenanceCollector.value(
                    "startAt", window.startAt(),
                    "endAt", window.endAt(),
                    "minutes", window.durationMinutes(),
                    "source", window.source() != null ? window.source().name() : null,
                    "confidence", window.confidence() != null ? window.confidence().name() : null,
                    "reason", window.reason()));
        }

        String availabilityCalc = collector.calculation(
                ServerCalculation.ServerCalculationKind.AVAILABILITY_ESTIMATE, true, busyRefs,
                ServerCalculation.InputLineage.PARTIAL, note,
                ProvenanceCollector.value(
                        "availableMinutes", available,
                        "confidenceSummary", confidence,
                        "windows", windowValues));

        collector.calculation(
                ServerCalculation.ServerCalculationKind.STUDY_BUDGET, true, List.of(),
                ServerCalculation.InputLineage.COMPLETE,
                "가용시간 추정(" + availabilityCalc + ")에 강도 비율을 적용한 값이다",
                ProvenanceCollector.value(
                        "intensity", intensity.name(),
                        "fillPercent", intensity.getFillPercent(),
                        "targetMinutes", target));
    }

    /** 막힌 시간 하나의 원본 종류. 출처를 모르는 창(예전 경로)은 실행 조각으로 보지 않는다. */
    private static ProvenanceSourceType busySourceType(BusyWindow window) {
        if (window.source() == null) {
            return ProvenanceSourceType.EXECUTION_ITEM_FIXED;
        }
        return switch (window.source()) {
            case ROUTINE_OCCURRENCE -> ProvenanceSourceType.ROUTINE_OCCURRENCE;
            case COMMITMENT -> ProvenanceSourceType.COMMITMENT;
            case EXECUTION_ITEM, PINNED_PROPOSAL_ITEM -> ProvenanceSourceType.EXECUTION_ITEM_FIXED;
        };
    }

    private static String renderSpan(LocalDateTime startAt, LocalDateTime endAt) {
        LocalDate date = startAt.toLocalDate();
        String day = date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN);
        String end = endAt.toLocalDate().equals(date)
                ? endAt.toLocalTime().format(TIME_FMT)
                : endAt.getMonthValue() + "/" + endAt.getDayOfMonth() + " " + endAt.toLocalTime().format(TIME_FMT);
        return date.getMonthValue() + "/" + date.getDayOfMonth() + " " + day + " "
                + startAt.toLocalTime().format(TIME_FMT) + "~" + end;
    }

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * 프로젝트 한 줄 + 판단에 필요한 사실 + 이번 생성에서 모델이 고른 학습 항목과 원문.
     *
     * <p>예전에는 서버가 고른 학습 항목 줄(과목당 30줄 → 45줄 → 글자 예산)을 실었다. 지금은 선택 호출이 고른 것만
     * 싣고, 진행 중·첫 미학습·과제(마감·완료)·사용자 수정은 선택과 무관하게 싣는다. "반드시 입력에 포함"은 "반드시 실행
     * 항목으로 삽입"이 아니다 — 그 구분은 프롬프트 규칙이 말한다.
     *
     * @param course 프로젝트. 프로젝트에 연결되지 않은 지정 자료 묶음이면 null
     */
    private void appendCourseContext(StringBuilder sb, Long userId, Course course,
                                     PlanMaterialContextService.CourseCatalog catalog, PlanInputs inputs,
                                     ProvenanceCollector collector, Map<Long, String> refBySection,
                                     Map<Long, PlanMaterialRetriever.Rendered> rendered) {
        Long courseId = course == null ? null : course.getCourseId();
        if (course != null) {
            StringBuilder head = new StringBuilder("id=").append(course.getCourseId())
                    .append(" ").append(course.getTitle());
            if (course.getTextbookTitle() != null) {
                head.append(" (교재: ").append(course.getTextbookTitle()).append(")");
            }
            sb.append("- ").append(collector.mark(
                    ProvenanceSourceType.COURSE, course.getCourseId(), null, course.getUpdatedAt(),
                    ProvenanceRepresentation.SELECTED_FIELDS,
                    ProvenanceCollector.value(
                            "title", course.getTitle(),
                            "textbookTitle", course.getTextbookTitle()),
                    head.toString()).text()).append("\n");
        } else {
            sb.append("- (프로젝트 없음) 이번 요청에서 지정했지만 대상 프로젝트에 연결되지 않은 자료 — courseId는 null\n");
        }
        if (catalog == null) {
            sb.append("\n");
            return;
        }

        PlanMaterialSelector.Result selection = inputs.selection();
        Map<Long, String> topicReasons = new LinkedHashMap<>();
        selection.topics().stream().filter(t -> java.util.Objects.equals(t.catalog().courseId(), courseId))
                .forEach(t -> topicReasons.put(t.line().topicId(), t.reason()));
        Map<Long, CourseMaterial> sourceMaterials = findMaterials(userId, catalog.topics().stream()
                .map(PlanMaterialContextService.TopicLine::sourceMaterialId)
                .filter(java.util.Objects::nonNull).distinct().toList());

        sb.append("  [판단에 필요한 사실]\n");
        boolean anyFact = false;
        Set<Long> printedTopics = new HashSet<>();
        for (PlanMaterialContextService.TopicLine topic : catalog.requiredTopics()) {
            String reason = topicReasons.get(topic.topicId());
            appendTopicLine(sb, courseId, topic, reason, collector, sourceMaterials, "  - ");
            printedTopics.add(topic.topicId());
            anyFact = true;
        }
        for (PlanMaterialContextService.AssignmentLine line : catalog.open()) {
            appendAssignmentLine(sb, courseId, line, collector);
            anyFact = true;
        }
        for (PlanMaterialContextService.AssignmentLine line : catalog.completed()) {
            appendAssignmentLine(sb, courseId, line, collector);
            anyFact = true;
        }
        for (PlanMaterialContextService.AssignmentLine line : catalog.unconfirmed()) {
            appendAssignmentLine(sb, courseId, line, collector);
            anyFact = true;
        }
        String excludedText = PlanCatalogText.excludedText(catalog.excluded());
        if (excludedText != null) {
            sb.append("  - ").append(excludedText).append(" — 이 항목들로 계획을 만들지 않는다\n");
            anyFact = true;
        }
        List<PlanRequestedMaterialResolver.RequestedMaterial> requestedHere = inputs.requested().materials().stream()
                .filter(m -> java.util.Objects.equals(m.courseId(), courseId)).toList();
        if (!requestedHere.isEmpty()) {
            long picked = selection.sections().stream()
                    .filter(s -> java.util.Objects.equals(s.catalog().courseId(), courseId) && s.line().requested()).count();
            sb.append("  - 이번 요청에서 지정한 자료: ")
                    .append(requestedHere.stream().map(PlanRequestedMaterialResolver.RequestedMaterial::filename)
                            .collect(Collectors.joining(", ")))
                    .append(" (선택 단계에서 이 자료의 구간 ").append(picked).append("개를 골랐다)\n");
            anyFact = true;
        }
        if (!catalog.pending().isEmpty()) {
            sb.append("  - 아직 분석이 끝나지 않은 자료 ").append(catalog.pending().size())
                    .append("개 — 그 내용은 이번 계획에 반영되지 않았다\n");
            anyFact = true;
        }
        if (!anyFact) {
            sb.append("  - (없음)\n");
        }

        List<PlanMaterialContextService.TopicLine> chosen = catalog.topics().stream()
                .filter(t -> topicReasons.containsKey(t.topicId()) && !printedTopics.contains(t.topicId()))
                .toList();
        if (!chosen.isEmpty()) {
            sb.append("  [이번 계획을 위해 고른 학습 항목]\n");
            for (PlanMaterialContextService.TopicLine topic : chosen) {
                appendTopicLine(sb, courseId, topic, topicReasons.get(topic.topicId()), collector, sourceMaterials, "  - ");
            }
        }

        List<PlanMaterialRetriever.Retrieved> mine = inputs.retrieved().stream()
                .filter(r -> java.util.Objects.equals(r.target().courseId(), courseId)).toList();
        int droppedChanged = 0;
        int droppedBudget = 0;
        boolean headerPrinted = false;
        for (PlanMaterialRetriever.Retrieved r : mine) {
            if (!r.outcome().retrieved()) {
                droppedChanged++;
                continue;
            }
            if (inputs.budgetDropped().contains(r.target().sectionId())) {
                droppedBudget++;
                continue;
            }
            if (!headerPrinted) {
                sb.append("  [고른 자료 구간 — 저장된 원문에서 읽음]\n");
                headerPrinted = true;
            }
            boolean completedAssignment = selection.sections().stream()
                    .anyMatch(sel -> sel.line().section().getSectionId().equals(r.target().sectionId())
                            && sel.line().completedAssignment());
            appendRetrievedSection(sb, courseId, r, inputs.cap(), completedAssignment, collector, refBySection, rendered);
        }
        if (droppedChanged > 0) {
            sb.append("  (고른 구간 중 ").append(droppedChanged)
                    .append("개는 그 사이 삭제·변경됐거나 범위를 벗어나 싣지 않았다)\n");
        }
        if (droppedBudget > 0) {
            sb.append("  (고른 구간 중 ").append(droppedBudget)
                    .append("개는 입력 한도 때문에 원문을 싣지 못했다 — 그 구간을 읽은 듯 쓰지 않는다)\n");
        }

        if (course != null) {
            List<ScheduleLine> scheduleLines = courseScheduleLines(userId, course.getCourseId());
            if (!scheduleLines.isEmpty()) {
                sb.append("  [일정·평가]").append("\n");
                Map<Long, CourseMaterial> materials = materialsOfSchedule(userId, scheduleLines);
                for (ScheduleLine line : scheduleLines) {
                    CourseMaterial material = line.materialId() != null ? materials.get(line.materialId()) : null;
                    sb.append("  - ").append(collector.mark(
                            line.type(), line.sourceId(), null, null,
                            ProvenanceRepresentation.SELECTED_FIELDS,
                            ProvenanceCollector.value("courseId", course.getCourseId(), "text", line.text()),
                            line.text(),
                            null,
                            material == null ? null
                                    : providedMaterial(material, line.materialId(), material.getOriginalFilename(), null))
                            .text()).append("\n");
                }
            }
        }
        sb.append("\n");
    }

    /** 이번 생성의 자료 선택이 무엇을 봤고 무엇을 보지 못했는지. 계획 모델이 전체를 검토한 척하지 않게 한다. */
    private void appendSelectionNote(StringBuilder sb, PlanInputs inputs) {
        PlanMaterialSelector.Result selection = inputs.selection();
        if (selection.status() == PlanMaterialSelector.Status.NO_CANDIDATES) {
            return;
        }
        sb.append("[자료 선택 결과]\n")
                .append("선택 단계에서 후보 ").append(selection.candidateTotal()).append("개 중 ")
                .append(selection.candidateShown()).append("개를 목록으로 보고 구간 ")
                .append(selection.sections().size()).append("개·학습 항목 ").append(selection.topics().size())
                .append("개를 골랐다. ");
        long readable = inputs.retrieved().stream().filter(r -> r.outcome().retrieved())
                .filter(r -> !inputs.budgetDropped().contains(r.target().sectionId())).count();
        sb.append(readable == 0
                ? "이번에 읽은 원문은 없다 — 자료의 문제·쪽수·내용을 본 듯 쓰지 않는다.\n"
                : "원문은 [고른 자료 구간]에 실린 " + readable + "개만 읽을 수 있다.\n");
        if (!selection.unreviewed().isEmpty()) {
            sb.append("이번에 후보 목록으로 보지 못한 범위: ")
                    .append(selection.unreviewed().stream().limit(8)
                            .map(u -> u.courseTitle() + " · " + u.title() + "(구간 " + u.sections() + ")")
                            .collect(Collectors.joining(", ")));
            if (selection.unreviewed().size() > 8) {
                sb.append(" 외 ").append(selection.unreviewed().size() - 8).append("묶음");
            }
            sb.append(" — 이 범위를 검토했다고 쓰지 않는다\n");
        }
        if (selection.insufficientEvidence()) {
            sb.append("선택 단계가 근거가 부족하다고 표시했다");
            if (selection.note() != null) {
                sb.append(": ").append(selection.note());
            }
            sb.append(" — 없는 문제·쪽수·내용을 지어내지 않는다\n");
        } else if (selection.note() != null) {
            sb.append("선택 메모: ").append(selection.note()).append("\n");
        }
        sb.append("\n");
    }

    private void appendTopicLine(StringBuilder sb, Long courseId, PlanMaterialContextService.TopicLine topic,
                                 String reason, ProvenanceCollector collector, Map<Long, CourseMaterial> materials,
                                 String prefix) {
        String text = PlanCatalogText.topicTitle(topic) + PlanCatalogText.topicFlags(topic)
                + (reason == null ? "" : " · 고른 이유: " + reason);
        sb.append(prefix).append(collector.mark(
                ProvenanceSourceType.TOPIC, topic.topicId(), null, null,
                ProvenanceRepresentation.SELECTED_FIELDS,
                ProvenanceCollector.value(
                        "courseId", courseId,
                        "title", topic.title(),
                        "sourceLocator", topic.locator(),
                        "progressStatus", topic.progress() == null ? null : topic.progress().name(),
                        "selectionReason", reason),
                text,
                topic.parentTopicId(),
                providedMaterial(
                        topic.sourceMaterialId() == null ? null : materials.get(topic.sourceMaterialId()),
                        topic.sourceMaterialId(), topic.sourceMaterialFilename(), topic.locator()))
                .text()).append("\n");
    }

    /**
     * 고른 구간 한 줄 + 저장된 원문. 줄에는 역할·위치·고른 이유·<b>읽은 범위</b>가 붙고, 원문은 그 아래 따옴표 블록이다.
     * 인용 번호는 줄에 붙는다 — 이 번호를 인용하면 "이 원문을 읽었다"는 뜻이다.
     */
    private void appendRetrievedSection(StringBuilder sb, Long courseId, PlanMaterialRetriever.Retrieved r, int cap,
                                        boolean completedAssignment,
                                        ProvenanceCollector collector, Map<Long, String> refBySection,
                                        Map<Long, PlanMaterialRetriever.Rendered> rendered) {
        MaterialSection section = r.section();
        PlanMaterialRetriever.Rendered body = PlanMaterialRetriever.render(r, cap);
        List<String> roles = materialContextService.rolesOf(section);
        String roleLabels = roles.stream().map(com.jungwoo.project.memo.material.domain.SectionRole::parseOne)
                .filter(java.util.Objects::nonNull).map(com.jungwoo.project.memo.material.domain.SectionRole::label)
                .distinct().collect(Collectors.joining("/"));
        StringBuilder text = new StringBuilder("· [").append(roleLabels.isBlank() ? "기타" : roleLabels).append("] ")
                .append(section.getDisplayTitle());
        String locator = section.locator();
        if (!locator.isBlank()) {
            text.append(" (").append(locator).append(")");
        }
        text.append(" · 자료 ").append(r.material().getOriginalFilename());
        if (section.getTaskText() != null) {
            text.append(" · 수행: ").append(PlanMaterialContextService.shortExcerpt(section.getTaskText()));
        }
        if (r.target().reason() != null) {
            text.append(" · 고른 이유: ").append(r.target().reason());
        }
        text.append(" · 읽은 범위: ").append(body.rangeLabel());
        if (completedAssignment) {
            text.append(" · 사용자가 완료한 과제의 구간 — 과제를 다시 수행하게 하지 않는다");
        }
        ProvenanceCollector.Marked marked = collector.mark(
                ProvenanceSourceType.MATERIAL_SECTION, section.getSectionId(), null, section.getCreatedAt(),
                ProvenanceRepresentation.EXCERPT,
                ProvenanceCollector.value(
                        "courseId", courseId,
                        "materialId", section.getMaterialId(),
                        "title", section.getDisplayTitle(),
                        "roles", roles,
                        "locator", locator,
                        "task", section.getTaskText(),
                        "selectionReason", r.target().reason(),
                        "retrievedRange", body.rangeLabel(),
                        "retrievedChars", body.chars(),
                        "retrievedTextSha256", body.textHash(),
                        "retrieval", body.outcome().name(),
                        "completedAssignment", completedAssignment ? Boolean.TRUE : null),
                text.toString(),
                r.target().topicId(),
                providedMaterial(r.material(), section.getMaterialId(), r.material().getOriginalFilename(), locator));
        sb.append("  ").append(marked.text()).append("\n");
        sb.append("    \"\"\"\n");
        for (String line : body.text().split("\n")) {
            sb.append("    ").append(line).append("\n");
        }
        sb.append("    \"\"\"\n");
        refBySection.put(section.getSectionId(), marked.refId());
        rendered.put(section.getSectionId(), body);
    }

    /**
     * 프롬프트에 실을 일정·평가 한 줄과 그 원본의 종류.
     *
     * @param materialId 자료 분석에서 읽은 줄이면 그 분석의 자료. 과목 메모는 null
     */
    private record ScheduleLine(ProvenanceSourceType type, Long sourceId, String text, Long materialId) {
    }

    /** 과제 한 줄. 확정/확인 전, 마감의 출처, 완료 여부, 원문 위치는 서버가 붙인 사실이다. */
    private void appendAssignmentLine(StringBuilder sb, Long courseId, PlanMaterialContextService.AssignmentLine line,
                                      ProvenanceCollector collector) {
        CourseAssignment a = line.assignment();
        sb.append("  - ").append(collector.mark(
                ProvenanceSourceType.ASSIGNMENT, a.getAssignmentId(), a.getVersion(), a.getUpdatedAt(),
                ProvenanceRepresentation.SELECTED_FIELDS,
                ProvenanceCollector.value(
                        "courseId", courseId,
                        "title", a.getTitle(),
                        "confirmStatus", a.getConfirmStatus().name(),
                        "dueKind", a.getDueKind() == null ? null : a.getDueKind().name(),
                        "dueDate", a.getDueDate(),
                        "dueAt", a.getDueAt(),
                        "dueSource", a.getDueSource() == null ? null : a.getDueSource().name(),
                        "completed", a.isCompleted() ? Boolean.TRUE : null,
                        "sectionId", a.getSectionId()),
                PlanCatalogText.assignmentText(line),
                null,
                line.material() == null ? null
                        : providedMaterial(line.material(), line.material().getMaterialId(),
                        line.material().getOriginalFilename(), line.section() == null ? null : line.section().locator()))
                .text()).append("\n");
    }

    /** 사용자가 확인한 장기 맥락. 기본 AI 경로도 이제 본다 — 서버 if문 대신 모델이 선호를 판단할 근거다. */
    private List<UserContext> loadUserContexts(Long userId) {
        try {
            List<UserContext> contexts = userContextMapper.findActiveAndStaleByUserId(userId, 20);
            return contexts == null ? List.of() : contexts;
        } catch (Exception e) {
            return List.of();
        }
    }

    private void appendUserContexts(StringBuilder sb, List<UserContext> contexts, ProvenanceCollector collector) {
        if (contexts == null || contexts.isEmpty()) {
            return;
        }
        sb.append("[사용자가 확인한 맥락]\n");
        for (UserContext context : contexts) {
            String stale = context.getStatus() != null && "STALE".equals(context.getStatus().name()) ? " (확인이 오래됨)" : "";
            sb.append("- ").append(collector.mark(
                    ProvenanceSourceType.USER_CONTEXT, context.getContextId(), null, context.getUpdatedAt(),
                    ProvenanceRepresentation.EXCERPT,
                    ProvenanceCollector.value("content", context.getContent(),
                            "status", context.getStatus() == null ? null : context.getStatus().name()),
                    context.getContent() + stale).text()).append("\n");
        }
        sb.append("\n");
    }

    private Map<Long, CourseMaterial> materialsOfSchedule(Long userId, List<ScheduleLine> lines) {
        List<Long> ids = lines.stream().map(ScheduleLine::materialId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        return findMaterials(userId, ids);
    }

    private Map<Long, CourseMaterial> findMaterials(Long userId, List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, CourseMaterial> byId = new java.util.HashMap<>();
        List<CourseMaterial> found = courseMaterialMapper.findByIdsAndUserIdIncludingDeleted(ids, userId);
        for (CourseMaterial material : found == null ? List.<CourseMaterial>of() : found) {
            byId.putIfAbsent(material.getMaterialId(), material);
        }
        return byId;
    }

    /**
     * 스냅샷에 남길 자료 정보. 자료 행을 못 찾아도(지워진 지 오래) 학습 항목이 아는 id·이름은
     * 남긴다 — "무엇이었는지"는 그것만으로도 말할 수 있다. 자료 연결이 없으면 null.
     */
    private static ProvidedMaterial providedMaterial(CourseMaterial material, Long materialId,
                                                     String fallbackFilename, String locator) {
        if (materialId == null) {
            return null;
        }
        if (material == null) {
            return new ProvidedMaterial(materialId, fallbackFilename, null, null, blankToNull(locator));
        }
        return new ProvidedMaterial(materialId, material.getOriginalFilename(), material.getContentType(),
                material.getFileHash(), blankToNull(locator));
    }



    /**
     * 일정과 평가. 개강일이 있어야 모델이 "지금 몇 주차인지"를 계산할 수 있다.
     *
     * keyDates는 별도 테이블이 없고 analysis_json 안에만 있다 — apply가 course_topics와
     * course_notes만 꺼내 저장하고 날짜는 원문에 남겨둔다. 그래서 여기서 읽어 파싱한다.
     * 파싱이 실패하면 조용히 건너뛴다. 일정이 없다고 계획을 못 만들 이유는 없다.
     */
    private List<ScheduleLine> courseScheduleLines(Long userId, Long courseId) {
        List<ScheduleLine> lines = new ArrayList<>();
        for (CourseMaterialAnalysis analysis : analysisMapper.findAppliedByCourseIdAndUserId(courseId, userId)) {
            String json = analysis.getEditedJson() != null ? analysis.getEditedJson() : analysis.getAnalysisJson();
            try {
                MaterialAnalysisPayload payload = objectMapper.readValue(json, MaterialAnalysisPayload.class);
                if (payload.keyDates() == null) {
                    continue;
                }
                for (MaterialAnalysisPayload.KeyDate keyDate : payload.keyDates()) {
                    String detail = keyDate.date() != null ? keyDate.date() : keyDate.description();
                    if (detail != null && !detail.isBlank()) {
                        /*
                         * keyDate에는 행 id가 없다. 가리킬 수 있는 것은 분석 행뿐이라 그것을
                         * 남기고, 없는 쪽·문단 번호를 지어내지 않는다(handoff §3.1).
                         */
                        lines.add(new ScheduleLine(ProvenanceSourceType.MATERIAL_KEY_DATE,
                                analysis.getAnalysisId(), keyDate.title() + ": " + detail,
                                analysis.getMaterialId()));
                    }
                }
            } catch (Exception e) {
                log.debug("계획 생성: 분석 JSON에서 일정을 읽지 못했다. analysisId={}", analysis.getAnalysisId());
            }
        }
        courseNoteMapper.findByCourseIdAndUserId(courseId, userId).stream()
                .filter(note -> CourseNoteCategory.ASSESSMENT.name().equals(String.valueOf(note.getCategory())))
                .forEach(note -> lines.add(new ScheduleLine(ProvenanceSourceType.COURSE_NOTE,
                        note.getNoteId(), note.getLabel() + ": " + note.getDetail(), null)));

        List<ScheduleLine> distinct = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (ScheduleLine line : lines) {
            if (distinct.size() >= MAX_SCHEDULE_LINES_PER_COURSE) {
                break;
            }
            if (seen.add(line.text())) {
                distinct.add(line);
            }
        }
        return distinct;
    }

    /** 대상 프로젝트. 지정한 id는 소유 확인을 거치고, 비어 있으면 활성 전체다. */
    public List<Course> resolveCourses(Long userId, List<Long> courseIds) {
        if (courseIds != null && !courseIds.isEmpty()) {
            return courseMapper.findByIdsAndUserId(courseIds, userId);
        }
        return courseMapper.findByUserIdAndStatus(userId, "ACTIVE");
    }

    // ===== 변환 =====

    /**
     * 모델 출력을 기존 제안 항목으로 옮긴다.
     *
     * 날짜를 정한 항목은 DATE_ONLY로, 나머지는 UNSCHEDULED로 만든다. 확정 시점에는 솔버를
     * 돌리지 않으므로(§5-2) TIME_FIXED는 여기서 만들지 않는다 — 시각 배치는 롤링 배치가
     * 전담한다. 계획 기간 밖 날짜는 조용히 버리고 UNSCHEDULED로 떨어뜨린다.
     *
     * courseId는 모델 출력을 그대로 믿지 않는다. 대상 프로젝트 목록에 없는 id는 버린다 —
     * 모델이 존재하지 않는 id를 만들어내면 그 항목이 어느 프로젝트에도 안 잡히거나
     * 남의 프로젝트에 붙는다.
     *
     * 대상이 정확히 하나일 때는 모델이 null을 줘도 그 프로젝트로 채운다. 후보가 하나뿐이면
     * 추측이 아니라 유일한 답이고, 비워두면 초안 검토 화면에서 전부 "기타"로 묶여 그룹핑이
     * 의미를 잃는다. 대상이 여럿이면 추측하지 않고 null로 둔다.
     *
     * 설명은 description을 우선하고 reason은 그것이 비었을 때만 쓴다. 프롬프트가 description에
     * "행동 · 완료: 기준"을 요구하는데, 예전처럼 reason("왜 지금 하는지")을 우선하면 모델이
     * 규칙을 지켜도 그 내용이 제안에 실리지 않는다 — reason은 거의 항상 채워지기 때문이다.
     */
    private Normalized toProposalItems(
            PlanDraftAiResult ai, LocalDate start, LocalDate end, List<Course> targetCourses,
            ProvenanceCollector collector) {
        Set<Long> allowedCourseIds = targetCourses.stream()
                .map(Course::getCourseId).collect(Collectors.toSet());
        Long soleCourseId = targetCourses.size() == 1 ? targetCourses.get(0).getCourseId() : null;
        List<ProposalItem> items = new ArrayList<>();
        List<PlanItemEvidence> evidence = new ArrayList<>();
        if (ai.items() == null) {
            return new Normalized(items, evidence);
        }
        Set<String> validRefs = collector.build().refIds();
        int totalUnknown = 0;
        for (PlanDraftAiResult.PlanDraftAiItem raw : ai.items()) {
            if (raw == null || raw.title() == null || raw.title().isBlank()) {
                continue;
            }
            LocalDate scheduled = parseDateInRange(raw.scheduledDate(), start, end);
            items.add(new ProposalItem(
                    raw.title(),
                    blankToNull(raw.description()) != null ? raw.description() : blankToNull(raw.reason()),
                    raw.expectedMinutes(),
                    normalizePriority(raw.priority()),
                    scheduled != null ? PlacementType.DATE_ONLY : PlacementType.UNSCHEDULED,
                    null, null,
                    scheduled != null ? scheduled : start,
                    scheduled != null ? scheduled : end,
                    null, null,
                    resolveItemCourseId(raw.courseId(), allowedCourseIds, soleCourseId)
            ));

            List<String> refs = new ArrayList<>();
            int unknown = 0;
            for (String ref : raw.refIds() == null ? List.<String>of() : raw.refIds()) {
                String trimmed = ref == null ? null : ref.trim();
                if (trimmed == null || trimmed.isEmpty()) {
                    continue;
                }
                /*
                 * ★ 이번 회차에 실제로 준 것만 근거다. 다른 회차·다른 사용자의 값이나 조회만
                 *   하고 안 준 값은 여기서 떨어진다. 떨어진 것을 조용히 없애지 않고 개수를
                 *   남겨, 화면이 "AI가 확인되지 않는 출처를 인용했다"고 말할 수 있게 한다.
                 */
                if (validRefs.contains(trimmed) && !refs.contains(trimmed)) {
                    refs.add(trimmed);
                } else if (!validRefs.contains(trimmed)) {
                    unknown++;
                }
            }
            totalUnknown += unknown;

            /*
             * 서버 계산 id는 붙이지 않는다. 이 경로에서 서버가 계산한 것은 기간 전체의
             * 남는 시간과 예산이고, 그것은 어느 한 항목의 근거가 아니라 회차 전체의 조건이다.
             * 항목 옆에 붙이면 "이 항목에 대해 서버가 계산했다"로 읽힌다.
             */
            evidence.add(PlanItemEvidence.of(collector.generationId(), refs,
                    blankToNull(raw.reason()), aiEstimates(raw, scheduled), List.of(), unknown));
        }
        if (totalUnknown > 0) {
            // 원문을 남기지 않는다 — 진단에 필요한 것은 빈도이지 모델이 쓴 문자열이 아니다.
            log.warn("계획 초안: 모델이 이번 회차에 없는 인용 {}건을 냈다. generationId={}",
                    totalUnknown, collector.generationId());
        }
        return new Normalized(items, evidence);
    }

    /** 모델 출력 중 <b>추정·판단</b>인 것들. 서버가 계산한 값과 같은 자리에 두지 않는다. */
    private List<String> aiEstimates(PlanDraftAiResult.PlanDraftAiItem raw, LocalDate scheduled) {
        List<String> estimates = new ArrayList<>();
        if (raw.expectedMinutes() != null) {
            estimates.add("예상 소요 시간 " + raw.expectedMinutes() + "분");
        }
        if (blankToNull(raw.priority()) != null) {
            /*
             * enum 원문을 그대로 넣지 않는다. 이 문자열은 화면에 그대로 뜨는데, MUST/SHOULD는
             * 서버가 값을 구분하려고 쓰는 이름이지 사용자에게 할 말이 아니다(계획 화면의
             * 표시 규칙과 같다). 화면이 다시 번역할 수 있게 토큰을 내리는 편이 나을 수도
             * 있지만, 이 목록은 "AI가 이렇게 봤다"를 사람 문장으로 나열하는 자리다.
             */
            estimates.add(switch (normalizePriority(raw.priority())) {
                case "MUST" -> "시간이 모자라도 꼭 해야 한다고 봄";
                case "OPTIONAL" -> "여유가 되면 하는 쪽으로 봄";
                default -> "보통 순위로 봄";
            });
        }
        if (scheduled != null) {
            estimates.add("이 날짜에 해야 한다고 봄: " + scheduled);
        }
        return estimates;
    }

    /** 검증을 마친 항목과, 그 항목과 <b>같은 순서</b>인 근거. */
    private record Normalized(List<ProposalItem> items, List<PlanItemEvidence> evidence) {
    }

    private Long resolveItemCourseId(Long raw, Set<Long> allowed, Long soleCourseId) {
        if (raw != null && allowed.contains(raw)) {
            return raw;
        }
        return soleCourseId;
    }

    private LocalDate parseDateInRange(String raw, LocalDate start, LocalDate end) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            LocalDate parsed = LocalDate.parse(raw.trim());
            return parsed.isBefore(start) || parsed.isAfter(end) ? null : parsed;
        } catch (Exception e) {
            return null;
        }
    }

    private String normalizePriority(String raw) {
        if (raw == null) {
            return "SHOULD";
        }
        return switch (raw.trim().toUpperCase()) {
            case "MUST" -> "MUST";
            case "OPTIONAL" -> "OPTIONAL";
            default -> "SHOULD";
        };
    }

    private String defaultTitle(LocalDate start, LocalDate end) {
        return start.getMonthValue() + "월 " + start.getDayOfMonth() + "일 ~ "
                + end.getMonthValue() + "월 " + end.getDayOfMonth() + "일 계획";
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private void recordUsage(Long userId, Usage usage, UsageResultStatus status, String errorCode, String workflowId) {
        aiUsageLimitService.record(userId, null, null, modelName,
                AiChatResponseUtils.safeTokenCount(usage, true), null,
                AiChatResponseUtils.safeTokenCount(usage, false), status, errorCode,
                FEATURE, workflowId, UUID.randomUUID().toString(), null);
    }
}
