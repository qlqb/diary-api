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
import com.jungwoo.project.memo.learning.TopicService;
import com.jungwoo.project.memo.learning.dto.TopicResponse;
import com.jungwoo.project.memo.material.CourseMaterialAnalysisMapper;
import com.jungwoo.project.memo.material.domain.CourseMaterialAnalysis;
import com.jungwoo.project.memo.material.dto.MaterialAnalysisPayload;
import com.jungwoo.project.memo.plan.domain.PlanIntensity;
import com.jungwoo.project.memo.plan.dto.PlanDraftAiResult;
import com.jungwoo.project.memo.scheduling.domain.AvailabilityConfidence;
import com.jungwoo.project.memo.scheduling.domain.AvailabilitySource;
import com.jungwoo.project.memo.scheduling.domain.AvailabilityWindow;
import com.jungwoo.project.memo.scheduling.domain.BusyWindow;
import com.jungwoo.project.memo.scheduling.service.AvailabilityEstimateResult;
import com.jungwoo.project.memo.scheduling.service.AvailabilityEstimateService;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;
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
import java.util.List;
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

    /**
     * 프로젝트당 학습 항목 줄 수 상한.
     *
     * 계획은 프로젝트를 고르지 않으면 ACTIVE 전체를 대상으로 한다. 상한이 없으면 과목이
     * 늘수록 프롬프트가 그대로 커진다. 30줄이면 한 과목의 주차별 진도를 담기에 넉넉하고,
     * 넘치면 뒤쪽은 접고 개수만 알린다 — 뒤쪽 주차는 어차피 지금 계획할 범위가 아니다.
     */
    private static final int MAX_TOPIC_LINES_PER_COURSE = 30;

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
    private final ExecutionItemMapper executionItemMapper;
    private final AvailabilityEstimateService availabilityEstimateService;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Value("${ai.planning.max-completion-tokens:2000}")
    private int maxCompletionTokens = 2000;

    @Value("${ai.request.timeout-seconds:90}")
    private int requestTimeoutSeconds = 90;

    @Value("${spring.ai.openai.chat.model:gpt-5-mini}")
    private String modelName = "gpt-5-mini";

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
            List<Long> courseIds
    ) {
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
            boolean targetCappedByItemLimit
    ) {
        /** 가용시간 정보가 없는 호출부(테스트 등)용. */
        public Generated(Spec spec, int baselineMinutes, int targetMinutes, String targetMinutesReason,
                         boolean targetAdjusted, String suggestedTitle, String goalSummary, List<ProposalItem> items) {
            this(spec, baselineMinutes, targetMinutes, targetMinutesReason, targetAdjusted, suggestedTitle,
                    goalSummary, items, 0, null, 0, false, false);
        }

        /** 가용시간까지만 아는 호출부용(상한 조정 없음). */
        public Generated(Spec spec, int baselineMinutes, int targetMinutes, String targetMinutesReason,
                         boolean targetAdjusted, String suggestedTitle, String goalSummary, List<ProposalItem> items,
                         int estimatedAvailableMinutes, String availabilityConfidenceSummary,
                         int reservedBufferMinutes, boolean noAvailableTime) {
            this(spec, baselineMinutes, targetMinutes, targetMinutesReason, targetAdjusted, suggestedTitle,
                    goalSummary, items, estimatedAvailableMinutes, availabilityConfidenceSummary,
                    reservedBufferMinutes, noAvailableTime, false);
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

        PlanDraftAiResult ai = callAi(spec, courses, availability, days, available, target, confidence,
                maxItems, cappedByItemLimit);

        List<ProposalItem> items = toProposalItems(ai, spec.start(), spec.end(), courses);
        if (items.isEmpty()) {
            throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
        }

        return new Generated(spec, target, target, null, false,
                blankToNull(ai.title()) != null ? ai.title() : defaultTitle(spec.start(), spec.end()),
                blankToNull(ai.goalSummary()), items, available, confidence, available - target, false,
                cappedByItemLimit);
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
            return "기본 시간대(평일 19~22시, 주말 10~18시)를 사용한 추정";
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
                  "reason": "왜 이걸 지금 하는지 한 문장"
                }
              ]
            }

            규칙:
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
              근거가 없으면 출처를 적지 않는다.
            - "전체 학습 구조 설계", "기본 학습목록 만들기", "커리큘럼 정리", "공부 계획 다시
              세우기" 같은 관리 작업은 사용자가 [사용자 지시]에서 그것을 요청했을 때만 만든다.
              계획 요청은 학습 실행 항목을 달라는 뜻이다.
            - 사용자를 탓하거나 뒤처졌다는 식으로 쓰지 마라. 못 한 것은 "아직 시작하지
              않았어요" 정도로만 다룬다.
            """.formatted(AiStreamParser.DELIMITER, MIN_ITEM_MINUTES, MAX_ITEM_MINUTES);

    private PlanDraftAiResult callAi(Spec spec, List<Course> courses, AvailabilityEstimateResult availability,
                                     int days, int available, int target, String confidence, int maxItems,
                                     boolean cappedByItemLimit) {
        Long userId = spec.userId();
        String userPrompt = buildUserPrompt(spec, courses, availability, days, available, target, confidence,
                maxItems, cappedByItemLimit);
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
            recordUsage(userId, lastUsage.get(), UsageResultStatus.FAILED, ErrorCode.AI_GENERATION_FAILED.getCode());
            log.warn("계획 초안 생성 AI 호출 실패: userId={}", userId, e);
            throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
        }
        recordUsage(userId, lastUsage.get(), UsageResultStatus.SUCCESS, null);

        AiStreamParser.Result result = parser.finish();
        if (result.structuredJson() == null) {
            log.warn("계획 초안 생성: 구조화 JSON이 없음. userId={}", userId);
            throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
        }
        try {
            return objectMapper.readValue(result.structuredJson(), PlanDraftAiResult.class);
        } catch (Exception e) {
            log.warn("계획 초안 생성: 구조화 JSON 파싱 실패. userId={}", userId, e);
            throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
        }
    }

    private String buildUserPrompt(Spec spec, List<Course> courses, AvailabilityEstimateResult availability,
                                   int days, int available, int target, String confidence, int maxItems,
                                   boolean cappedByItemLimit) {
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

        appendAvailabilityBlocks(sb, availability, available, confidence);

        /*
          학습 항목과 일정을 줘도 어디까지가 "지금"인지는 따로 말해주지 않으면 모른다.
          실험에서 이 지시가 없으면 한참 뒤 주차 내용까지 당겨왔다.
        */
        sb.append("[진도 기준]\n")
                .append("프로젝트에 학습 항목이 실려 있으면 그 안에서 고른다. 항목 옆 괄호는 ")
                .append("자료에서 확인한 위치다. 일정에 개강일이 있으면 오늘 날짜와 대조해 지금이 ")
                .append("몇 주차인지 계산하고, 이번 주와 다음 주 진도에 집중하라. 한참 뒤 주차 ")
                .append("내용을 당겨오지 마라. 학습 항목에 없는 것을 지어내지 마라 — 교재의 장 ")
                .append("번호나 쪽수처럼 자료에 없는 값은 쓰지 않는다.\n\n");

        sb.append("[대상 프로젝트]\n");
        if (courses.isEmpty()) {
            sb.append("(없음 — 프로젝트에 묶이지 않는 할 일만 제안해도 된다)\n");
        }
        for (Course course : courses) {
            appendCourseContext(sb, userId, course);
        }

        List<ExecutionItem> existing =
                executionItemMapper.findByUserIdAndPlanningRange(userId, start, end);
        sb.append("[이 기간에 이미 있는 일정]\n");
        if (existing.isEmpty()) {
            sb.append("(없음)\n");
        }
        for (ExecutionItem item : existing) {
            sb.append("- ").append(item.getTitle());
            if (item.getPlacementType() == PlacementType.TIME_FIXED) {
                sb.append(" · ").append(item.getScheduledStartAt()).append(" (고정)");
            } else if (item.getScheduledDate() != null) {
                sb.append(" · ").append(item.getScheduledDate());
            } else {
                sb.append(" · 날짜 미정");
            }
            if (item.getExpectedMinutes() != null) {
                sb.append(" · ").append(item.getExpectedMinutes()).append("분");
            }
            sb.append("\n");
        }
        sb.append("\n");

        String previous = planReviewService.summarizeLatestForPrompt(userId);
        if (previous != null) {
            sb.append("[직전 계획 회고]\n").append(previous).append("\n\n");
        }

        if (spec.instruction() != null && !spec.instruction().isBlank()) {
            sb.append("[사용자 지시]\n").append(spec.instruction()).append("\n\n");
        }
        if (spec.title() != null && !spec.title().isBlank()) {
            sb.append("[사용자가 정한 제목]\n").append(spec.title()).append("\n");
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
                                          int available, String confidence) {
        sb.append("[이번 기간에 이미 등록된 일정]\n");
        List<BusyWindow> busy = new ArrayList<>(availability.busyWindows());
        busy.sort(java.util.Comparator.comparing(BusyWindow::startAt));
        if (busy.isEmpty()) {
            sb.append("(없음)\n");
        }
        int shown = 0;
        for (BusyWindow window : busy) {
            if (shown++ >= MAX_WINDOW_LINES) {
                sb.append("- … 외 ").append(busy.size() - MAX_WINDOW_LINES).append("건\n");
                break;
            }
            sb.append("- ").append(renderSpan(window.startAt(), window.endAt())).append(' ')
                    .append(window.label()).append('\n');
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
     * 프로젝트 한 줄 + 그 프로젝트에 대해 자료에서 뽑아 둔 것.
     *
     * 이게 없으면 모델이 아는 것은 과목명과 교재명뿐이다. 실측해 보니 그 상태에서는 교재
     * 장 번호를 지어내고("교재 1장(전처리 기본)") 실제 수업 진도와 무관한 계획이 나왔다.
     * 사용자가 자료를 올리고 분석까지 적용했는데 그 결과가 계획에 한 글자도 반영되지 않고
     * 있었다.
     *
     * course_topics를 쓰는 이유는 교재 목차가 아니라 강의 진도라서다. 교재 목차는 저자가
     * 정한 순서이고 수업이 그 순서대로 나가지 않는다 — 이 과목만 해도 전처리가 교재
     * 앞쪽인데 수업은 9주차다. source_locator에 "2주차"처럼 위치가 붙어 있어 그대로 전달된다.
     */
    private void appendCourseContext(StringBuilder sb, Long userId, Course course) {
        sb.append("- id=").append(course.getCourseId()).append(" ").append(course.getTitle());
        if (course.getTextbookTitle() != null) {
            sb.append(" (교재: ").append(course.getTextbookTitle()).append(")");
        }
        sb.append("\n");

        List<String> topicLines = new ArrayList<>();
        for (TopicResponse root : topicService.getTopicTree(userId, course.getCourseId())) {
            appendTopicLine(topicLines, root, 0);
        }
        if (!topicLines.isEmpty()) {
            sb.append("  [학습 항목]").append("\n");
            int shown = Math.min(topicLines.size(), MAX_TOPIC_LINES_PER_COURSE);
            for (int i = 0; i < shown; i++) {
                sb.append("  ").append(topicLines.get(i)).append("\n");
            }
            if (topicLines.size() > shown) {
                sb.append("    … 외 ").append(topicLines.size() - shown).append("개\n");
            }
        }

        List<String> scheduleLines = courseScheduleLines(userId, course.getCourseId());
        if (!scheduleLines.isEmpty()) {
            sb.append("  [일정·평가]").append("\n");
            for (String line : scheduleLines) {
                sb.append("  - ").append(line).append("\n");
            }
        }
        sb.append("\n");
    }

    /*
      수집 단계에서 미리 자르지 않는다. 자르면 "외 N개"의 N이 실제로 접힌 개수가 아니라
      "상한을 넘긴 만큼"이 되어, 40개 중 30개를 보여주고 "외 1개"라고 말하게 된다.
      자르는 것은 출력할 때 한 번만 한다.
    */
    private void appendTopicLine(List<String> out, TopicResponse node, int depth) {
        StringBuilder line = new StringBuilder("  ".repeat(depth)).append("- ").append(node.getTitle());
        if (node.getSourceLocator() != null && !node.getSourceLocator().isBlank()) {
            line.append(" (").append(node.getSourceLocator()).append(")");
        }
        out.add(line.toString());
        if (node.getChildren() != null) {
            for (TopicResponse child : node.getChildren()) {
                appendTopicLine(out, child, depth + 1);
            }
        }
    }

    /**
     * 일정과 평가. 개강일이 있어야 모델이 "지금 몇 주차인지"를 계산할 수 있다.
     *
     * keyDates는 별도 테이블이 없고 analysis_json 안에만 있다 — apply가 course_topics와
     * course_notes만 꺼내 저장하고 날짜는 원문에 남겨둔다. 그래서 여기서 읽어 파싱한다.
     * 파싱이 실패하면 조용히 건너뛴다. 일정이 없다고 계획을 못 만들 이유는 없다.
     */
    private List<String> courseScheduleLines(Long userId, Long courseId) {
        List<String> lines = new ArrayList<>();
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
                        lines.add(keyDate.title() + ": " + detail);
                    }
                }
            } catch (Exception e) {
                log.debug("계획 생성: 분석 JSON에서 일정을 읽지 못했다. analysisId={}", analysis.getAnalysisId());
            }
        }
        courseNoteMapper.findByCourseIdAndUserId(courseId, userId).stream()
                .filter(note -> CourseNoteCategory.ASSESSMENT.name().equals(String.valueOf(note.getCategory())))
                .forEach(note -> lines.add(note.getLabel() + ": " + note.getDetail()));

        return lines.stream().distinct().limit(MAX_SCHEDULE_LINES_PER_COURSE).toList();
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
    private List<ProposalItem> toProposalItems(
            PlanDraftAiResult ai, LocalDate start, LocalDate end, List<Course> targetCourses) {
        Set<Long> allowedCourseIds = targetCourses.stream()
                .map(Course::getCourseId).collect(Collectors.toSet());
        Long soleCourseId = targetCourses.size() == 1 ? targetCourses.get(0).getCourseId() : null;
        List<ProposalItem> items = new ArrayList<>();
        if (ai.items() == null) {
            return items;
        }
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
        }
        return items;
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

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private void recordUsage(Long userId, Usage usage, UsageResultStatus status, String errorCode) {
        aiUsageLimitService.record(userId, null, null, modelName,
                AiChatResponseUtils.safeTokenCount(usage, true), null,
                AiChatResponseUtils.safeTokenCount(usage, false), status, errorCode,
                FEATURE, null, UUID.randomUUID().toString(), null);
    }
}
