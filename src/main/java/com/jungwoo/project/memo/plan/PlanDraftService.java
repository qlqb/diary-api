package com.jungwoo.project.memo.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.ai.AiConsultationClient;
import com.jungwoo.project.memo.ai.AiProposalService;
import com.jungwoo.project.memo.ai.AiProposalMapper;
import com.jungwoo.project.memo.ai.AiStreamParser;
import com.jungwoo.project.memo.ai.AiUsageLimitService;
import com.jungwoo.project.memo.ai.domain.UsageResultStatus;
import com.jungwoo.project.memo.ai.dto.AiProposalResponse;
import com.jungwoo.project.memo.ai.dto.ProposalItem;
import com.jungwoo.project.memo.ai.AiChatResponseUtils;
import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.ServiceUnavailableException;
import com.jungwoo.project.memo.course.CourseMapper;
import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.execution.ExecutionItemMapper;
import com.jungwoo.project.memo.execution.domain.ExecutionItem;
import com.jungwoo.project.memo.execution.domain.PlacementType;
import com.jungwoo.project.memo.plan.domain.PlanIntensity;
import com.jungwoo.project.memo.plan.dto.PlanDraftAiResult;
import com.jungwoo.project.memo.plan.dto.PlanDraftRequest;
import com.jungwoo.project.memo.plan.dto.PlanDraftResponse;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 기간 계획 초안 생성. 기존 PlanningAgentService.createDraft와 별개 경로다 — 그쪽은
 * recommendationId가 필수라 여러 프로젝트를 아우를 수 없고, 기존 동작을 건드리지 않는다.
 *
 * 이 서비스는 execution_items도 plan_versions도 만들지 않는다. ai_proposals만 만들고,
 * 실제 데이터는 사용자가 확정(PlanConfirmService)해야 생긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanDraftService {

    private static final int MAX_PLAN_DAYS = 31;

    /** 개수는 주 제약이 아니다(§5-1-1). "확실히 뭔가 잘못됐다"는 폭주 방지선으로만 둔다. */
    private static final int MAX_ITEMS_SHORT = 15;
    private static final int MAX_ITEMS_LONG = 30;
    private static final int SHORT_PLAN_DAYS = 7;

    /** AiProposalService가 강제하는 항목별 시간 범위. 프롬프트에도 같은 값을 알려준다. */
    private static final int MIN_ITEM_MINUTES = 5;
    private static final int MAX_ITEM_MINUTES = 120;

    private static final String FEATURE = "PLAN_DRAFT";

    private final AiConsultationClient aiConsultationClient;
    private final AiProposalService aiProposalService;
    private final AiProposalMapper aiProposalMapper;
    private final AiUsageLimitService aiUsageLimitService;
    private final PlanVersionService planVersionService;
    private final PlanReviewService planReviewService;
    private final CourseMapper courseMapper;
    private final ExecutionItemMapper executionItemMapper;
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

    @Transactional
    public PlanDraftResponse createDraft(Long userId, PlanDraftRequest request) {
        LocalDate start = request.getStartDate();
        LocalDate end = request.getEndDate();
        if (start == null || end == null || end.isBefore(start)) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }
        int days = (int) ChronoUnit.DAYS.between(start, end) + 1;
        if (days > MAX_PLAN_DAYS) {
            // 31일을 넘으면 계획이 아니라 목표에 가깝다. 그건 이 기능이 다룰 대상이 아니다.
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }
        if (!aiConsultationClient.isConfigured()) {
            throw new ServiceUnavailableException(ErrorCode.AI_NOT_CONFIGURED);
        }

        PlanIntensity intensity = planVersionService.resolveIntensity(userId, request.getIntensity());
        int baseline = intensity.baselineMinutes(days);
        int maxItems = days <= SHORT_PLAN_DAYS ? MAX_ITEMS_SHORT : MAX_ITEMS_LONG;

        PlanDraftAiResult ai = callAi(userId, request, start, end, days, intensity, baseline, maxItems);

        // 모델이 목표를 빼먹거나 말이 안 되는 값을 주면 기준선으로 되돌린다. 조정 권한을
        // 주는 것과 출력을 그대로 믿는 것은 다르다.
        Integer aiTarget = ai.targetMinutes();
        boolean adjusted = aiTarget != null && aiTarget > 0 && aiTarget != baseline;
        int targetMinutes = adjusted ? aiTarget : baseline;
        String reason = adjusted ? blankToNull(ai.targetMinutesReason()) : null;

        List<ProposalItem> items = toProposalItems(ai, start, end);
        if (items.isEmpty()) {
            throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
        }

        AiProposalResponse proposal = aiProposalService.createFromItems(
                userId, null, null, items, List.of(), start, List.of(), maxItems);

        aiProposalMapper.updatePlanMetadata(
                proposal.getProposalId(), userId, start, end, intensity, targetMinutes);

        log.info("기간 계획 초안 생성: userId={}, proposalId={}, {}~{}({}일), intensity={}, "
                        + "baseline={}분, target={}분, 조정={}, 항목={}개",
                userId, proposal.getProposalId(), start, end, days, intensity,
                baseline, targetMinutes, adjusted, items.size());

        return PlanDraftResponse.builder()
                .proposalId(proposal.getProposalId())
                .startDate(start)
                .endDate(end)
                .days(days)
                .intensity(intensity)
                .baselineMinutes(baseline)
                .targetMinutes(targetMinutes)
                .targetMinutesReason(reason)
                .suggestedTitle(blankToNull(ai.title()) != null ? ai.title() : defaultTitle(start, end))
                .goalSummary(blankToNull(ai.goalSummary()))
                .proposal(proposal)
                .build();
    }

    // ===== AI 호출 =====

    private static final String SYSTEM_PROMPT = """
            너는 사용자의 기간 학습 계획 초안을 만드는 조수다.

            응답 형식: 자연어 한두 문장 + "%s" + 구조화 JSON.

            구조화 JSON:
            {
              "title": "계획 제목 (짧게)",
              "goalSummary": "이 기간에 무엇을 이루려는지 한 문장 (없으면 null)",
              "targetMinutes": 정수,
              "targetMinutesReason": "기준선을 조정했을 때만 한 문장, 조정 안 했으면 null",
              "items": [
                {
                  "title": "한 번에 앉아서 할 만한 단위의 할 일",
                  "description": "필요하면 한 문장, 아니면 null",
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
            - 사용자를 탓하거나 뒤처졌다는 식으로 쓰지 마라. 못 한 것은 "아직 시작하지
              않았어요" 정도로만 다룬다.
            """.formatted(AiStreamParser.DELIMITER, MIN_ITEM_MINUTES, MAX_ITEM_MINUTES);

    private PlanDraftAiResult callAi(
            Long userId, PlanDraftRequest request, LocalDate start, LocalDate end,
            int days, PlanIntensity intensity, int baseline, int maxItems
    ) {
        String userPrompt = buildUserPrompt(userId, request, start, end, days, intensity, baseline, maxItems);
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

    private String buildUserPrompt(
            Long userId, PlanDraftRequest request, LocalDate start, LocalDate end,
            int days, PlanIntensity intensity, int baseline, int maxItems
    ) {
        StringBuilder sb = new StringBuilder();
        LocalDate today = ZonedDateTime.now(clock).withZoneSameInstant(ZoneId.of(defaultTimeZoneId)).toLocalDate();

        sb.append("[기간]\n")
                .append(start).append(" ~ ").append(end)
                .append(" (").append(days).append("일, 오늘은 ").append(today).append(")\n\n");

        // ★ 기준선으로 제시한다. 고정값이 아니다 — 프리셋 숫자에 실사용 근거가 없으므로
        //   상황을 더 많이 아는 모델에게 조정 권한을 준다.
        sb.append("[시간]\n")
                .append("이 기간의 기준 학습 시간은 약 ").append(baseline).append("분이다. ")
                .append("사용자 상황(아래 지시, 고정 일정, 직전 회고)상 조정이 필요하면 조정하고 ")
                .append("이유를 한 문장으로 밝혀라. 항목들의 예상 시간 합이 최종 목표 근처가 되게 하되 ")
                .append("넘기지 마라. 항목을 잘게 쪼개 개수를 늘리지 마라. 채울 내용이 없으면 ")
                .append("억지로 채우지 말고 적게 제안하라. 항목은 최대 ").append(maxItems).append("개다.\n\n");

        List<Course> courses = resolveCourses(userId, request.getCourseIds());
        sb.append("[대상 프로젝트]\n");
        if (courses.isEmpty()) {
            sb.append("(없음 — 프로젝트에 묶이지 않는 할 일만 제안해도 된다)\n");
        }
        for (Course course : courses) {
            sb.append("- id=").append(course.getCourseId()).append(" ").append(course.getTitle());
            if (course.getTextbookTitle() != null) {
                sb.append(" (교재: ").append(course.getTextbookTitle()).append(")");
            }
            sb.append("\n");
        }
        sb.append("\n");

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

        if (request.getInstruction() != null && !request.getInstruction().isBlank()) {
            sb.append("[사용자 지시]\n").append(request.getInstruction()).append("\n\n");
        }
        if (request.getTitle() != null && !request.getTitle().isBlank()) {
            sb.append("[사용자가 정한 제목]\n").append(request.getTitle()).append("\n");
        }
        return sb.toString();
    }

    private List<Course> resolveCourses(Long userId, List<Long> courseIds) {
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
     */
    private List<ProposalItem> toProposalItems(PlanDraftAiResult ai, LocalDate start, LocalDate end) {
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
                    blankToNull(raw.reason()) != null ? raw.reason() : blankToNull(raw.description()),
                    raw.expectedMinutes(),
                    normalizePriority(raw.priority()),
                    scheduled != null ? PlacementType.DATE_ONLY : PlacementType.UNSCHEDULED,
                    null, null,
                    scheduled != null ? scheduled : start,
                    scheduled != null ? scheduled : end,
                    null, null,
                    raw.courseId()
            ));
        }
        return items;
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
