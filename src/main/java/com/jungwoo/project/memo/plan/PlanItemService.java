package com.jungwoo.project.memo.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.ai.AiChatResponseUtils;
import com.jungwoo.project.memo.ai.AiConsultationClient;
import com.jungwoo.project.memo.ai.AiStreamParser;
import com.jungwoo.project.memo.ai.AiUsageLimitService;
import com.jungwoo.project.memo.ai.domain.UsageResultStatus;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.ServiceUnavailableException;
import com.jungwoo.project.memo.plan.PlanningContext.CourseContext;
import com.jungwoo.project.memo.plan.PlanningContext.TopicContext;
import com.jungwoo.project.memo.plan.domain.ActionType;
import com.jungwoo.project.memo.plan.domain.EstimateConfidence;
import com.jungwoo.project.memo.plan.domain.PlanStrategy;
import com.jungwoo.project.memo.plan.domain.PlanStrategy.CourseStrategy;
import com.jungwoo.project.memo.plan.domain.PlanStrategy.TopicTreatment;
import com.jungwoo.project.memo.plan.domain.Treatment;
import com.jungwoo.project.memo.plan.dto.PlanItemAiResult;
import com.jungwoo.project.memo.plan.dto.PlanItemDraft;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 판단이 정한 것을 실제로 할 수 있는 조각으로 옮긴다. 판단하지 않는다.
 *
 * <p>이 서비스가 지키는 것은 하나다: <b>사용자가 앉아서 바로 시작할 수 있는가.</b> 그래서
 * 조각마다 무엇을 볼지(자료 위치)와 무엇을 하면 끝인지(완료 기준)가 있어야 하고, 없으면
 * 조각으로 인정하지 않는다.
 *
 * <p>모델이 정하는 것은 행동과 완료 기준뿐이다. 자료 위치·마감·취급은 서버가 이미 아는
 * 값에서 붙인다 — 모델에게 고르게 하면 없는 자료를 지어낼 수 있고, 그걸 막으려면 결국
 * 서버가 아는 값과 대조해야 한다. 대조할 바에는 처음부터 서버가 채우는 편이 짧다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanItemService {

    private static final String FEATURE = "PLAN_ITEMS";

    private static final DateTimeFormatter CLASS_TIME = DateTimeFormatter.ofPattern("M/d(E) HH:mm", Locale.KOREAN);

    /** 훑기로 정해진 항목의 길이 상한. 이보다 길면 취급이 뜻을 잃는다. */
    private static final int SKIM_MAX_MINUTES = 30;

    /** 자료 분량을 모를 때 제대로 보는 항목에 주는 기본값. v0의 블록 길이와 같다. */
    private static final int FULL_DEFAULT_MINUTES = 45;

    /**
     * 완료 기준으로 인정하지 않는 표현. 무엇을 하면 끝인지를 말하지 않는 말들이다.
     *
     * <p>이 목록이 완벽할 수는 없다. 다만 실측에서 반복해 나온 것들이라 여기서 걸러 내고,
     * 걸린 조각은 버리지 않고 항목 제목으로 기본 문장을 만들어 준다 — 조각 하나를 통째로
     * 잃는 것보다 낫다.
     */
    private static final List<String> VAGUE_DONE_CRITERIA = List.of(
            "공부하기", "공부한다", "복습하기", "복습한다", "정리하기", "정리한다",
            "학습하기", "학습한다", "이해하기", "이해한다", "살펴보기", "읽기");

    private final AiConsultationClient aiConsultationClient;
    private final AiUsageLimitService aiUsageLimitService;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Value("${ai.planning.items.max-completion-tokens:8000}")
    private int maxCompletionTokens = 8000;

    @Value("${ai.request.timeout-seconds:90}")
    private int requestTimeoutSeconds = 90;

    @Value("${spring.ai.openai.chat.model:gpt-5-mini}")
    private String modelName = "gpt-5-mini";

    /**
     * 전략이 남긴 항목들을 조각으로 만든다. AI 1회.
     *
     * <p>SKIP으로 정해진 항목은 프롬프트에 실리지도 않는다. 모델에게 보여주고 "이건 빼라"고
     * 말하는 것보다 애초에 안 보여주는 편이 확실하다.
     */
    public List<PlanItemDraft> generate(PlanStrategy strategy, PlanningContext context, int maxItems) {
        if (!aiConsultationClient.isConfigured()) {
            throw new ServiceUnavailableException(ErrorCode.AI_NOT_CONFIGURED);
        }
        List<Planned> planned = plannedTopics(strategy, context, maxItems);
        if (planned.isEmpty()) {
            log.info("조각 생성: 만들 항목이 없다(전부 SKIP이거나 창이 비었다). userId={}", context.userId());
            return List.of();
        }

        PlanItemAiResult ai = callAi(strategy, context, planned, maxItems);
        List<PlanItemDraft> drafts = normalize(ai, planned, maxItems);
        warnOnStrategyMismatch(strategy, drafts, planned);
        return drafts;
    }

    // ===== 대상 고르기 =====

    /** 조각을 만들 항목 하나와 그 항목에 대해 서버가 이미 아는 것. */
    private record Planned(TopicContext topic, CourseContext course, Treatment treatment, int rank) {
    }

    private List<Planned> plannedTopics(PlanStrategy strategy, PlanningContext context, int maxItems) {
        Map<Long, TopicTreatment> byTopic = new HashMap<>();
        for (TopicTreatment topic : strategy.topics()) {
            if (topic != null && topic.topicId() != null) {
                byTopic.putIfAbsent(topic.topicId(), topic);
            }
        }
        Map<Long, Integer> courseRank = new HashMap<>();
        for (CourseStrategy course : strategy.courses()) {
            if (course != null && course.courseId() != null) {
                courseRank.putIfAbsent(course.courseId(), course.rank());
            }
        }

        List<Planned> planned = new ArrayList<>();
        for (CourseContext course : context.courses()) {
            for (TopicContext topic : course.topics()) {
                TopicTreatment decided = byTopic.get(topic.topicId());
                // 판단에 없는 항목은 근거 없음이고, 근거 없음은 FULL이다(판단 서비스가 이미
                // 채워 주지만 여기서도 같은 규칙을 쓴다 — 다른 경로로 들어와도 결과가 같아야 한다).
                Treatment treatment = decided == null ? Treatment.FULL : decided.treatment();
                if (treatment == Treatment.SKIP) {
                    continue;
                }
                planned.add(new Planned(topic, course, treatment,
                        decided == null ? Integer.MAX_VALUE : decided.rank()));
            }
        }
        /*
         * 과목 순위 → 항목 순위 순으로 정렬한 뒤 상한에서 자른다. 자르는 것이 항상 덜 급한
         * 쪽이어야 하므로 순서가 먼저다 — 자른 다음 정렬하면 무엇이 잘렸는지가 판단과 무관해진다.
         */
        planned.sort(Comparator
                .<Planned>comparingInt(p -> courseRank.getOrDefault(p.course().courseId(), Integer.MAX_VALUE))
                .thenComparingInt(Planned::rank));
        return planned.size() > maxItems ? new ArrayList<>(planned.subList(0, maxItems)) : planned;
    }

    // ===== 검증과 정규화 =====

    private List<PlanItemDraft> normalize(PlanItemAiResult ai, List<Planned> planned, int maxItems) {
        Map<Long, Planned> byTopic = new HashMap<>();
        for (Planned one : planned) {
            byTopic.put(one.topic().topicId(), one);
        }

        List<PlanItemDraft> drafts = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        int dropped = 0;
        int repaired = 0;
        for (PlanItemAiResult.AiItem raw : ai.items() == null ? List.<PlanItemAiResult.AiItem>of() : ai.items()) {
            if (raw == null || raw.topicId() == null || !seen.add(raw.topicId())) {
                dropped++;
                continue;
            }
            Planned target = byTopic.get(raw.topicId());
            if (target == null) {
                // 계획 대상이 아닌 항목이다. 지어냈거나 SKIP으로 뺀 것이고, 어느 쪽이든 만들지 않는다.
                dropped++;
                continue;
            }
            if (drafts.size() >= maxItems) {
                dropped++;
                continue;
            }

            ActionType actionType = parseActionType(raw.actionType());
            if (actionType == null) {
                actionType = ActionType.READ;
                repaired++;
            }
            /*
             * anchor 규칙(D6): READ는 반드시 자료와 위치가 있어야 한다. 자료를 모르는 항목을
             * "읽기"로 두면 사용자는 무엇을 펴야 할지 모른 채 45분을 배정받는다. 그런 경우는
             * 자료 없이도 성립하는 행동으로 바꾼다 — 조각을 버리는 것보다 낫다.
             */
            boolean hasSource = target.topic().sourceMaterialId() != null
                    || blankToNull(target.topic().sourceLocator()) != null;
            if (actionType == ActionType.READ && !hasSource) {
                actionType = ActionType.RECALL;
                repaired++;
            }

            String doneCriteria = usableDoneCriteria(raw.doneCriteria(), target, actionType);
            if (!doneCriteria.equals(blankToNull(raw.doneCriteria()))) {
                repaired++;
            }

            drafts.add(new PlanItemDraft(
                    target.topic().topicId(),
                    target.course().courseId(),
                    title(raw.title(), target),
                    blankToNull(raw.description()),
                    doneCriteria,
                    actionType,
                    minutes(raw.expectedMinutes(), target.treatment()),
                    priority(raw.priority()),
                    target.topic().sourceMaterialId(),
                    target.topic().sourceLocator(),
                    // 마감은 서버가 아는 값에서만 온다. 모델은 마감을 내지 않는다 —
                    // "그래야 할 것 같아서" 붙은 마감이 배치를 조용히 망가뜨린다.
                    target.course().nextClassAt(),
                    // 자료 분량을 모르는 동안에는 전부 LOW다(13-plan-judgment.md §2).
                    EstimateConfidence.LOW,
                    blankToNull(raw.reason())));
        }

        if (dropped > 0 || repaired > 0) {
            log.info("조각 정규화: 만든 조각={}개, 버린 것={}개, 고쳐 쓴 것={}개",
                    drafts.size(), dropped, repaired);
        }
        return drafts;
    }

    /**
     * 완료 기준. 없거나 무엇을 하면 끝인지 말하지 않으면 서버가 행동에 맞는 기본 문장을 만든다.
     *
     * <p>조각을 버리지 않는 이유는, 버리면 그 학습 항목이 계획에서 통째로 사라지는데 그건
     * 모델이 문장 하나를 못 쓴 것에 비해 너무 큰 대가이기 때문이다.
     */
    private String usableDoneCriteria(String raw, Planned target, ActionType actionType) {
        String value = blankToNull(raw);
        if (value != null && !isVague(value)) {
            return value;
        }
        String what = target.topic().title();
        return switch (actionType) {
            case READ -> target.treatment() == Treatment.SKIM
                    ? what + "에서 무엇을 다루는지와 이미 아는 부분을 말할 수 있음"
                    : what + "의 내용을 자료 없이 한 문단으로 설명할 수 있음";
            case PRACTICE -> what + " 관련 문제를 스스로 풀어 답을 확인함";
            case RECALL -> what + "의 핵심을 자료를 덮고 다시 적어 봄";
            case LAB -> what + "을 실제로 동작시켜 결과를 확인함";
        };
    }

    private boolean isVague(String value) {
        String normalized = value.replace(" ", "");
        if (normalized.length() < 6) {
            return true;
        }
        for (String vague : VAGUE_DONE_CRITERIA) {
            if (normalized.equals(vague.replace(" ", ""))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 훑기는 짧게 자른다. 모델이 훑기라면서 제대로 볼 때와 같은 시간을 내면 그 판단은
     * 문구로만 남고 총량에는 반영되지 않는다.
     */
    private int minutes(Integer raw, Treatment treatment) {
        int value = raw == null || raw < PeriodPlanDraftGenerator.MIN_ITEM_MINUTES
                ? (treatment == Treatment.SKIM ? SKIM_MAX_MINUTES : FULL_DEFAULT_MINUTES)
                : Math.min(raw, PeriodPlanDraftGenerator.MAX_ITEM_MINUTES);
        return treatment == Treatment.SKIM ? Math.min(value, SKIM_MAX_MINUTES) : value;
    }

    private String title(String raw, Planned target) {
        String value = blankToNull(raw);
        return value != null ? value : target.course().title() + " · " + target.topic().title();
    }

    private String priority(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase();
        return switch (value) {
            case "MUST", "SHOULD", "OPTIONAL" -> value;
            default -> "SHOULD";
        };
    }

    private ActionType parseActionType(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return ActionType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ===== 전략과 조각이 어긋나는가 =====

    /**
     * 판단과 조각이 서로 다른 말을 하는지 센다. 막지는 않고 로그로만 남긴다.
     *
     * <p>이 숫자가 D1(판단과 조각을 나눈 결정)의 근거다. 나눠 두면 두 층이 어긋날 수 있는데,
     * 그 어긋남이 실제로 얼마나 나는지 모르면 "합치는 게 나았다"도 "나누길 잘했다"도
     * 말할 수 없다. 세어 두고 쌓이면 그때 판단한다.
     */
    private void warnOnStrategyMismatch(PlanStrategy strategy, List<PlanItemDraft> drafts, List<Planned> planned) {
        Map<Long, Treatment> treatmentByTopic = new HashMap<>();
        for (TopicTreatment topic : strategy.topics()) {
            treatmentByTopic.put(topic.topicId(), topic.treatment());
        }
        Set<Long> topRankCourses = new HashSet<>();
        for (CourseStrategy course : strategy.courses()) {
            if (course.rank() == 1) {
                topRankCourses.add(course.courseId());
            }
        }

        int skimTooLong = 0;
        int topCourseWithoutDeadline = 0;
        for (PlanItemDraft draft : drafts) {
            if (treatmentByTopic.get(draft.topicId()) == Treatment.SKIM
                    && draft.expectedMinutes() > SKIM_MAX_MINUTES) {
                skimTooLong++;
            }
            if (topRankCourses.contains(draft.courseId()) && draft.deadlineAt() == null) {
                topCourseWithoutDeadline++;
            }
        }
        int missing = planned.size() - drafts.size();

        if (skimTooLong > 0 || topCourseWithoutDeadline > 0 || missing > 0) {
            log.warn("전략-조각 불일치: 훑기인데 길다={}, 1순위 과목인데 마감 없음={}, 계획했는데 조각 없음={} "
                            + "(대상 {}개 중 조각 {}개)",
                    skimTooLong, topCourseWithoutDeadline, missing, planned.size(), drafts.size());
        }

        /*
         * 우선순위가 하나뿐이면 우선순위가 없는 것과 같다. 시간이 모자랄 때 무엇을 지킬지
         * 배치가 판단할 근거가 사라지고(Timefold의 MEDIUM 제약이 전부 같은 무게가 된다),
         * 회고에서 "중요한 것은 지켰는가"도 물을 수 없다. 막지는 않는다 — 실제로 전부 MUST인
         * 기간이 있을 수 있다. 다만 이게 잦으면 프롬프트를 고쳐야 한다는 신호다.
         */
        long distinctPriorities = drafts.stream().map(PlanItemDraft::priority).distinct().count();
        if (drafts.size() > 2 && distinctPriorities == 1) {
            log.warn("조각 우선순위가 한 가지뿐이다: {} {}개 — 시간이 모자랄 때 무엇을 미룰지 정할 수 없다",
                    drafts.get(0).priority(), drafts.size());
        }
    }

    // ===== 모델 호출 =====

    private static final String SYSTEM_PROMPT = """
            너는 이미 정해진 판단을 실제로 할 수 있는 조각으로 옮긴다. 무엇을 할지는 이미
            정해져 있다 — 네가 정하는 것은 **어떤 행동으로 하고, 무엇을 하면 끝인가**뿐이다.

            규칙.
            - 조각 하나는 [대상 항목]의 topicId 하나에 대응한다. 거기 없는 topicId를 쓰지 마라.
            - 한 항목에 조각은 하나다.
            - doneCriteria는 **확인 가능한 상태**로 쓴다. "무엇을 하면 이 조각이 끝났다고
              말할 수 있는가"에 답해야 한다.
              좋은 예: "단일·중첩 반복문 코드 5개의 시간복잡도를 자료 없이 판별함"
              나쁜 예: "공부하기", "복습", "개념 정리", "이해하기"
              (나쁜 예를 쓰면 서버가 버리고 기본 문장으로 바꾼다 — 네 문장이 더 나을 것이다)
            - actionType은 넷 중 하나다.
              READ 자료를 읽는다 / PRACTICE 직접 해 본다 / RECALL 자료를 덮고 떠올린다 /
              LAB 장비·환경을 실제로 다룬다
              전부 READ로 만들지 마라. 읽기만 있는 계획은 읽기만 하게 만든다. 다만 억지로
              섞지도 마라 — 개념 항목에 실습을 붙이면 사용자가 무엇을 할지 모른다.
            - expectedMinutes는 그 항목을 그 취급으로 하는 데 실제로 필요한 시간이다.
              [취급]이 SKIM이면 30분을 넘기지 않는다.
            - priority는 **시간이 모자랄 때 무엇을 지킬 것인가**다. 전부 MUST로 만들면
              우선순위가 없는 것과 같고, 실제로 시간이 모자랄 때 무엇을 미뤄야 할지 아무도
              모른다. 조각 대부분은 SHOULD다.
              MUST: 다음 수업을 따라가지 못하게 되는 것. 과목당 한둘.
              SHOULD: 기본값.
              OPTIONAL: 시간이 남으면 좋은 것.
            - 자료 위치·마감·우선순위 근거는 서버가 이미 알고 있으므로 쓰지 않는다.
            - 사용자를 탓하거나 뒤처졌다는 식으로 쓰지 마라. "부족", "미흡", "수준", "실력"은
              쓰지 않는다.

            먼저 사용자에게 보여줄 한국어 요약을 한두 문장으로 쓰고, 그 다음 줄에 %s 를 쓰고,
            그 아래에 아래 형식의 JSON만 쓴다.

            {
              "title": "계획 제목",
              "items": [
                {"topicId": 0, "title": "과목 · 무엇을 하는가", "description": "실제로 할 행동 1~3개",
                 "doneCriteria": "무엇을 하면 끝인가", "actionType": "READ",
                 "expectedMinutes": 45, "priority": "SHOULD", "reason": "왜 이번에 이걸 하나"}
              ]
            }
            """.formatted(AiStreamParser.DELIMITER);

    private PlanItemAiResult callAi(PlanStrategy strategy, PlanningContext context,
                                    List<Planned> planned, int maxItems) {
        Long userId = context.userId();
        String userPrompt = buildUserPrompt(strategy, context, planned, maxItems);
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
            recordUsage(userId, lastUsage.get(), UsageResultStatus.FAILED, ErrorCode.AI_GENERATION_FAILED.getCode());
            log.warn("조각 생성 AI 호출 실패: userId={}", userId, e);
            throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
        }
        recordUsage(userId, lastUsage.get(), UsageResultStatus.SUCCESS, null);

        String finishReason = lastFinishReason.get();
        if (AiChatResponseUtils.isTruncatedByTokenLimit(finishReason)) {
            log.warn("조각 생성: 출력이 토큰 상한({})에서 잘림. userId={}, 대상={}개, outputTokens={}",
                    maxCompletionTokens, userId, planned.size(),
                    AiChatResponseUtils.safeTokenCount(lastUsage.get(), false));
            throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
        }

        AiStreamParser.Result result = parser.finish();
        if (result.structuredJson() == null) {
            log.warn("조각 생성: 구조화 JSON이 없음. userId={}, finishReason={}", userId, finishReason);
            throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
        }
        try {
            return objectMapper.readValue(result.structuredJson(), PlanItemAiResult.class);
        } catch (Exception e) {
            log.warn("조각 생성: 구조화 JSON 파싱 실패. userId={}, finishReason={}", userId, finishReason, e);
            throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
        }
    }

    String buildUserPrompt(PlanStrategy strategy, PlanningContext context, List<Planned> planned, int maxItems) {
        StringBuilder sb = new StringBuilder();

        sb.append("[이번 기간의 판단]\n");
        sb.append("목표: ").append(strategy.goal()).append('\n');
        if (strategy.strategySummary() != null) {
            sb.append("운영: ").append(strategy.strategySummary()).append('\n');
        }
        for (String rule : strategy.planningRules()) {
            sb.append("- ").append(rule).append('\n');
        }
        sb.append('\n');

        sb.append("[대상 항목] ").append(planned.size()).append("개, 조각은 최대 ")
                .append(maxItems).append("개\n");
        for (Planned one : planned) {
            sb.append("- topicId=").append(one.topic().topicId())
                    .append(" [").append(one.course().title()).append("] ")
                    .append(one.topic().title())
                    .append(" · 취급 ").append(one.treatment());
            if (blankToNull(one.topic().sourceLocator()) != null) {
                sb.append(" · 자료 위치 ").append(one.topic().sourceLocator());
            }
            if (blankToNull(one.topic().sourceMaterialFilename()) != null) {
                sb.append(" · 자료 ").append(one.topic().sourceMaterialFilename());
            } else {
                sb.append(" · 자료 없음(READ로 만들지 마라)");
            }
            if (one.course().nextClassAt() != null) {
                sb.append(" · 다음 수업 ").append(one.course().nextClassAt().format(CLASS_TIME));
            }
            sb.append('\n');
        }
        sb.append('\n');

        if (context.instruction() != null) {
            sb.append("[사용자 지시]\n").append(context.instruction()).append("\n\n");
        }
        return sb.toString();
    }

    private void recordUsage(Long userId, Usage usage, UsageResultStatus status, String errorCode) {
        aiUsageLimitService.record(userId, null, null, modelName,
                AiChatResponseUtils.safeTokenCount(usage, true), null,
                AiChatResponseUtils.safeTokenCount(usage, false), status, errorCode,
                FEATURE, null, UUID.randomUUID().toString(), null);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
