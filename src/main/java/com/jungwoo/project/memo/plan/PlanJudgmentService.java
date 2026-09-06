package com.jungwoo.project.memo.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.ai.AiChatResponseUtils;
import com.jungwoo.project.memo.ai.AiConsultationClient;
import com.jungwoo.project.memo.ai.AiStreamParser;
import com.jungwoo.project.memo.ai.AiUsageLimitService;
import com.jungwoo.project.memo.ai.domain.UsageResultStatus;
import com.jungwoo.project.memo.ai.domain.UserContextStatus;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.ServiceUnavailableException;
import com.jungwoo.project.memo.learning.domain.TopicProgressStatus;
import com.jungwoo.project.memo.learning.domain.TopicUserMark;
import com.jungwoo.project.memo.plan.PlanningContext.ContextLine;
import com.jungwoo.project.memo.plan.PlanningContext.CourseContext;
import com.jungwoo.project.memo.plan.PlanningContext.TopicContext;
import com.jungwoo.project.memo.plan.domain.AskReason;
import com.jungwoo.project.memo.plan.domain.EvidenceType;
import com.jungwoo.project.memo.plan.domain.PlanStrategy;
import com.jungwoo.project.memo.plan.domain.PlanStrategy.CourseStrategy;
import com.jungwoo.project.memo.plan.domain.PlanStrategy.Evidence;
import com.jungwoo.project.memo.plan.domain.PlanStrategy.TopicTreatment;
import com.jungwoo.project.memo.plan.domain.PlanVersion;
import com.jungwoo.project.memo.plan.domain.StrategySource;
import com.jungwoo.project.memo.plan.domain.Treatment;
import com.jungwoo.project.memo.plan.dto.PlanJudgmentAiResult;
import com.jungwoo.project.memo.plan.dto.PlanJudgmentResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 이번 기간을 왜 이렇게 운영할 것인가를 정한다. 조각은 만들지 않는다.
 *
 * <p>판단과 조각을 나눈 이유는 재계획이다. 실행 기록을 보고 조각만 다시 만드는 일은 자주
 * 있지만 판단까지 다시 하는 일은 드물다 — 한 호출에 섞으면 그때마다 판단이 흔들린다.
 *
 * <p>이 서비스가 지키는 경계 셋:
 * <ul>
 *   <li><b>학습 항목(topic)까지만 정한다.</b> 어느 자료의 몇 쪽을 볼지는 조각의 몫이다.
 *   <li><b>시각을 정하지 않는다.</b> 실제 배치는 Timefold가 한다.
 *   <li><b>근거 없이 압축하지 않는다.</b> 모델이 근거 없이 SKIP을 내면 서버가 되돌린다(§4).
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanJudgmentService {

    private static final String FEATURE = "PLAN_JUDGMENT";

    private static final DateTimeFormatter CLASS_TIME = DateTimeFormatter.ofPattern("M/d(E) HH:mm");

    /**
     * 되묻기 문턱을 계산할 때 쓰는 항목당 시간 추정.
     *
     * <p>실제 조각 길이가 아니다 — 조각은 아직 없다. "제대로 볼 때와 훑을 때의 차이가 초안
     * 총량에서 얼마나 되는가"를 재기 위한 값이고, 그 비율만 의미가 있다. FULL 쪽은 v0의
     * 기본 블록과 같은 값이고 SKIM 쪽은 조각 생성이 SKIM에 두는 상한이다.
     */
    private static final int FULL_ESTIMATE_MINUTES = 45;
    private static final int SKIM_ESTIMATE_MINUTES = 30;

    /** 근거 없는 항목의 FULL/SKIM 시간 차가 초안 총량의 이 비율을 넘어야 되묻는다. */
    private static final double UNKNOWN_FAMILIARITY_THRESHOLD = 0.25;

    /**
     * 그리고 그 차이가 절대량으로도 이만큼은 돼야 한다.
     *
     * <p>비율만 보면 작은 초안이 항상 걸린다 — 항목이 하나뿐이면 차이는 늘 33%다. 15분을
     * 아끼려고 사용자에게 질문을 하나 더 던지는 것은 남는 장사가 아니다. 항목 하나를 통째로
     * 더 하거나 덜 하는 만큼(= 블록 하나) 차이가 나야 물을 값이 있다.
     */
    private static final int UNKNOWN_FAMILIARITY_MIN_SWING_MINUTES = 45;

    /** 되묻기 문구에 이름을 몇 개까지 적을지. 다 적으면 질문이 목록이 된다. */
    private static final int MAX_NAMED_TOPICS_IN_QUESTION = 3;

    private final AiConsultationClient aiConsultationClient;
    private final AiUsageLimitService aiUsageLimitService;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Value("${ai.planning.judgment.max-completion-tokens:6000}")
    private int maxCompletionTokens = 6000;

    @Value("${ai.request.timeout-seconds:90}")
    private int requestTimeoutSeconds = 90;

    @Value("${spring.ai.openai.chat.model:gpt-5-mini}")
    private String modelName = "gpt-5-mini";

    // ===== 진입점 =====

    /**
     * 전략을 만든다. 되물어야 하면 모델을 부르지 않거나(지시문에서 이미 드러난 경우) 부른
     * 결과를 버리고 질문을 돌려준다.
     */
    public PlanJudgmentResult judge(PlanningContext context) {
        return judge(context, false);
    }

    /**
     * @param familiarityAnswered 익숙함 되묻기에 이미 답했다. 그 답이 근거로 남지 않는
     *                            경우("처음이에요")에도 되묻기가 끝나게 하는 유일한 신호다 —
     *                            자세한 이유는 PlanDraftRequest의 같은 이름 필드에 적어 두었다
     */
    public PlanJudgmentResult judge(PlanningContext context, boolean familiarityAnswered) {
        if (!aiConsultationClient.isConfigured()) {
            throw new ServiceUnavailableException(ErrorCode.AI_NOT_CONFIGURED);
        }

        /*
         * 지시문만으로 기간이 둘인 것이 드러나면 모델을 부르지 않는다. 불러 봐야 그 출력은
         * 버릴 것이고, 되묻기는 코드가 판정하므로 모델의 답이 판정을 바꾸지도 않는다.
         */
        if (mentionsTwoPeriods(context.instruction())) {
            log.info("계획 판단: 지시문에 목표 기간이 둘 이상 — 모델을 부르지 않고 되묻는다. userId={}",
                    context.userId());
            return askMultiPeriod(List.of());
        }

        PlanJudgmentAiResult ai = callAi(context);

        List<PlanJudgmentAiResult.GoalPeriod> goals = ai.goals() == null ? List.of() : ai.goals();
        if (goals.size() > 1) {
            log.info("계획 판단: 모델이 목표 기간을 {}개 냈다 — 계획을 만들지 않고 되묻는다. userId={}",
                    goals.size(), context.userId());
            return askMultiPeriod(goals);
        }

        PlanStrategy strategy = normalize(ai, context, goals);

        Optional<PlanJudgmentResult> ask = familiarityAnswered
                ? Optional.empty()
                : askUnknownFamiliarity(strategy, context);
        if (ask.isPresent()) {
            log.info("계획 판단: 근거 없는 항목의 시간 차가 커서 되묻는다. userId={}", context.userId());
            return ask.get();
        }
        return PlanJudgmentResult.of(strategy);
    }

    /**
     * 이전 버전의 판단을 그대로 이어받을 수 있는가.
     *
     * <p>전략의 유효기간은 그것을 만든 버전의 {@code end_date}다. 새 계획이 그 안에서 끝나면
     * 그때 내린 판단이 아직 유효하고(같은 수업, 같은 진도), 넘어가면 다시 판단해야 한다.
     * 별도 {@code validUntil}을 두지 않는 이유이기도 하다 — 두 값이 어긋날 여지를 만들지 않는다.
     *
     * <p>재계획 오케스트레이션 자체는 아직 없다. 여기서는 판정과 변환만 제공한다.
     *
     * @return 이어받을 수 있으면 REUSED로 표시된 전략, 아니면 비어 있음(호출부가 새로 판단한다)
     */
    public Optional<PlanStrategy> reuse(PlanVersion source, PlanStrategy sourceStrategy, LocalDate newEnd) {
        if (source == null || sourceStrategy == null || newEnd == null || source.getEndDate() == null) {
            return Optional.empty();
        }
        if (newEnd.isAfter(source.getEndDate())) {
            return Optional.empty();
        }
        return Optional.of(new PlanStrategy(
                sourceStrategy.goal(), sourceStrategy.strategySummary(),
                StrategySource.REUSED, source.getPlanVersionId(),
                sourceStrategy.referencedContextIds(), sourceStrategy.courses(), sourceStrategy.topics(),
                sourceStrategy.planningRules()));
    }

    // ===== 되묻기 =====

    /**
     * "~까지 … 그 후"류를 지시문에서 찾는다.
     *
     * <p>둘 다 있어야 한다 — 마감을 말하는 "까지"만으로는 기간이 둘이 아니고("금요일까지
     * 끝내줘"), 이어짐을 말하는 "그 후"만으로도 아니다("그 후에 알려줘"). 둘이 함께 있을
     * 때만 "이 시점까지는 이렇게, 그 뒤로는 저렇게"라는 요청으로 읽는다.
     *
     * <p>넓게 잡지 않는다. 잘못 되물으면 사용자는 만들어 줬어야 할 계획 대신 질문을 받는다.
     */
    static boolean mentionsTwoPeriods(String instruction) {
        if (instruction == null || instruction.isBlank()) {
            return false;
        }
        String text = instruction.replace(" ", "");
        boolean hasBoundary = text.contains("까지");
        if (!hasBoundary) {
            return false;
        }
        for (String after : List.of("그후", "그다음", "그뒤", "이후에는", "끝나고나서", "끝난뒤", "끝난후")) {
            int boundary = text.indexOf("까지");
            int idx = text.indexOf(after);
            if (idx > boundary) {
                return true;
            }
        }
        return false;
    }

    private PlanJudgmentResult askMultiPeriod(List<PlanJudgmentAiResult.GoalPeriod> goals) {
        List<String> options = new ArrayList<>();
        for (PlanJudgmentAiResult.GoalPeriod goal : goals) {
            String label = blankToNull(goal.until()) != null ? goal.until() : goal.goal();
            if (label != null && !options.contains(label)) {
                options.add(label);
            }
        }
        if (options.isEmpty()) {
            options = List.of("앞쪽 기간부터", "뒤쪽 기간부터");
        }
        return PlanJudgmentResult.ask(AskReason.MULTI_PERIOD,
                "이번 계획은 어느 기간을 기준으로 잡을까요? 하나씩 잡는 편이 실제로 지키기 쉬워요.",
                options);
    }

    /**
     * 근거가 하나도 없는 항목들 때문에 계획 총량이 크게 흔들릴 때만 묻는다.
     *
     * <p>근거 없는 항목이 있다는 사실만으로는 묻지 않는다. 그런 항목은 늘 있고, 그때마다
     * 물으면 계획을 만들 때마다 설문을 받게 된다. 답이 계획을 실제로 바꿀 때만 묻는다.
     *
     * <p>한 초안에 한 번이다. 이 메서드는 판단 한 번에 한 번만 불리므로 그 자체로 보장된다.
     */
    private Optional<PlanJudgmentResult> askUnknownFamiliarity(PlanStrategy strategy, PlanningContext context) {
        Map<Long, Treatment> treatmentByTopic = new HashMap<>();
        Map<Long, Boolean> knownByTopic = new HashMap<>();
        for (TopicTreatment treatment : strategy.topics()) {
            treatmentByTopic.put(treatment.topicId(), treatment.treatment());
            knownByTopic.put(treatment.topicId(), knowsFamiliarity(treatment));
        }

        int totalMinutes = 0;
        CourseContext worstCourse = null;
        List<TopicContext> worstUnknown = List.of();
        for (CourseContext course : context.courses()) {
            List<TopicContext> unknown = new ArrayList<>();
            for (TopicContext topic : course.topics()) {
                Treatment treatment = treatmentByTopic.get(topic.topicId());
                if (treatment == null || treatment == Treatment.SKIP) {
                    continue;
                }
                totalMinutes += treatment == Treatment.SKIM ? SKIM_ESTIMATE_MINUTES : FULL_ESTIMATE_MINUTES;
                if (treatment == Treatment.FULL && !Boolean.TRUE.equals(knownByTopic.get(topic.topicId()))) {
                    unknown.add(topic);
                }
            }
            if (unknown.size() > worstUnknown.size()) {
                worstCourse = course;
                worstUnknown = unknown;
            }
        }
        if (totalMinutes == 0 || worstCourse == null || worstUnknown.isEmpty()) {
            return Optional.empty();
        }

        /*
         * ★ 한 과목만 묻는다. 계획 전체의 근거 없는 항목을 한 질문에 몰아넣으면 "이 46개는
         * 처음 보는 내용일까요?"가 되는데, 그건 답할 수 있는 질문이 아니다. 답이 뭉뚱그려지면
         * 저장할 수도 없다 — 46개에 대한 "익숙하다"는 맥락으로 남길 만한 사실이 아니다.
         * 가장 많이 걸린 과목 하나만 물으면 답이 구체적이고, 그 답은 그대로 맥락이 된다.
         * 다음 계획에서 또 물을 항목이 남아 있으면 그때 그 과목을 묻는다.
         */
        int swingMinutes = worstUnknown.size() * (FULL_ESTIMATE_MINUTES - SKIM_ESTIMATE_MINUTES);
        if (swingMinutes < UNKNOWN_FAMILIARITY_MIN_SWING_MINUTES
                || (double) swingMinutes / totalMinutes < UNKNOWN_FAMILIARITY_THRESHOLD) {
            return Optional.empty();
        }

        List<String> titles = worstUnknown.stream().map(TopicContext::title).toList();
        String named = String.join(", ", titles.subList(0, Math.min(titles.size(), MAX_NAMED_TOPICS_IN_QUESTION)));
        String suffix = titles.size() > MAX_NAMED_TOPICS_IN_QUESTION
                ? " 외 " + (titles.size() - MAX_NAMED_TOPICS_IN_QUESTION) + "개"
                : "";
        return Optional.of(PlanJudgmentResult.ask(AskReason.UNKNOWN_FAMILIARITY,
                worstCourse.title() + "의 " + named + suffix + "는 이번에 처음 보는 내용일까요? "
                        + "익숙하면 훑는 정도로 잡을게요.",
                List.of("처음이에요", "이미 익숙해요", "일부는 익숙해요"),
                worstUnknown.stream().map(TopicContext::topicId).toList()));
    }

    /**
     * "익숙하다"는 답을 맥락 문장으로 옮긴다. 되묻기가 한 과목만 묻기 때문에 이 문장이
     * 구체적일 수 있고, 구체적이라야 다음 판단에서 어느 항목을 덮는지 알아볼 수 있다.
     */
    public String familiarityStatement(PlanningContext context, List<Long> topicIds) {
        if (topicIds == null || topicIds.isEmpty()) {
            return null;
        }
        Set<Long> ids = new HashSet<>(topicIds);
        for (CourseContext course : context.courses()) {
            List<String> titles = course.topics().stream()
                    .filter(topic -> ids.contains(topic.topicId()))
                    .map(TopicContext::title)
                    .toList();
            if (titles.isEmpty()) {
                continue;
            }
            String named = String.join(", ",
                    titles.subList(0, Math.min(titles.size(), MAX_NAMED_TOPICS_IN_QUESTION)));
            String suffix = titles.size() > MAX_NAMED_TOPICS_IN_QUESTION
                    ? " 등 " + titles.size() + "개 항목"
                    : "";
            return course.title() + "의 " + named + suffix + "은(는) 이미 익숙하다";
        }
        return null;
    }

    /**
     * 이 항목이 익숙한지에 대해 무언가 아는 것이 있는가.
     *
     * <p>모든 근거가 여기 해당하지는 않는다. 다음 수업 시각(NEXT_CLASS)이나 기한(DEADLINE)은
     * "언제까지 해야 하는가"를 말할 뿐 "얼마나 아는가"는 말하지 않는다. 그 둘만 붙어 있는
     * 항목은 여전히 익숙함을 모르는 항목이다 — 근거 칸이 비어 있지 않다는 이유로 물어볼
     * 기회를 놓치면 안 된다.
     */
    private boolean knowsFamiliarity(TopicTreatment treatment) {
        for (Evidence evidence : treatment.evidence()) {
            if (evidence.type() == EvidenceType.CONTEXT
                    || evidence.type() == EvidenceType.USER_MARK
                    || evidence.type() == EvidenceType.PROGRESS) {
                return true;
            }
        }
        return false;
    }

    // ===== 정규화와 근거 등급 강제 =====

    /**
     * 모델 출력을 서버가 인정하는 범위로 줄인다.
     *
     * <p>버리는 것: 대상 범위 밖의 과목·항목, 모르는 취급, 모르는 근거 종류, 존재하지 않는
     * 맥락을 가리키는 근거. 되돌리는 것: 근거가 허용하는 것보다 강한 압축(§4).
     */
    private PlanStrategy normalize(PlanJudgmentAiResult ai, PlanningContext context,
                                   List<PlanJudgmentAiResult.GoalPeriod> goals) {
        Set<Long> knownCourseIds = new HashSet<>();
        Map<Long, TopicContext> topicsById = topicsById(context);
        for (CourseContext course : context.courses()) {
            knownCourseIds.add(course.courseId());
        }
        Map<Long, UserContextStatus> contextStatuses = new HashMap<>();
        for (ContextLine line : context.contexts()) {
            contextStatuses.put(line.contextId(), line.status());
        }

        List<CourseStrategy> courses = new ArrayList<>();
        Set<Long> seenCourses = new LinkedHashSet<>();
        if (ai.courses() != null) {
            for (PlanJudgmentAiResult.AiCourse raw : ai.courses()) {
                if (raw == null || raw.courseId() == null || !knownCourseIds.contains(raw.courseId())
                        || !seenCourses.add(raw.courseId())) {
                    continue;
                }
                // rank는 정렬 힌트일 뿐이라 겹쳐도 된다. 다만 null·음수는 정렬을 뒤집으므로 막는다.
                int rank = raw.rank() == null || raw.rank() < 1 ? seenCourses.size() : raw.rank();
                courses.add(new CourseStrategy(raw.courseId(), rank, raw.focus(), raw.reason()));
            }
        }

        List<TopicTreatment> topics = new ArrayList<>();
        Set<Long> seenTopics = new HashSet<>();
        int adjusted = 0;
        if (ai.topics() != null) {
            for (PlanJudgmentAiResult.AiTopic raw : ai.topics()) {
                if (raw == null || raw.topicId() == null || !seenTopics.add(raw.topicId())) {
                    continue;
                }
                TopicContext topic = topicsById.get(raw.topicId());
                if (topic == null) {
                    // 컨텍스트에 없는 항목이다. 지어냈거나 창 밖이고, 어느 쪽이든 계획 대상이 아니다.
                    continue;
                }
                Treatment claimed = parseTreatment(raw.treatment());
                if (claimed == null) {
                    continue;
                }
                List<Evidence> evidence = verifiedEvidence(raw.evidence(), topic, contextStatuses);
                Treatment cap = cap(topic, raw.evidence(), contextStatuses);
                Treatment finalTreatment = weaker(claimed, cap);

                String reason = raw.reason();
                if (finalTreatment != claimed) {
                    adjusted++;
                    reason = (blankToNull(reason) == null ? "" : reason + " ") + "(서버 조정)";
                }
                int rank = raw.rank() == null || raw.rank() < 1 ? topics.size() + 1 : raw.rank();
                topics.add(new TopicTreatment(raw.topicId(), finalTreatment, rank, reason, evidence));
            }
        }

        /*
         * 모델이 빠뜨린 항목은 FULL로 채운다. 판단에 없는 항목을 계획에서 빼면 "모델이 언급을
         * 안 했다"가 "이건 안 해도 된다"가 되어 버린다 — 근거 없음은 FULL이다.
         */
        int filled = 0;
        for (CourseContext course : context.courses()) {
            for (TopicContext topic : course.topics()) {
                if (seenTopics.contains(topic.topicId())) {
                    continue;
                }
                Treatment cap = cap(topic, List.of(), contextStatuses);
                topics.add(new TopicTreatment(topic.topicId(), weaker(Treatment.FULL, cap),
                        topics.size() + 1, "판단에 언급되지 않아 그대로 두었어요",
                        verifiedEvidence(List.of(), topic, contextStatuses)));
                filled++;
            }
        }

        String goal = goals.isEmpty() || blankToNull(goals.get(0).goal()) == null
                ? "이번 기간에 다음 수업을 따라갈 수 있는 상태로 만들기"
                : goals.get(0).goal();

        log.info("계획 판단 완료: userId={}, 과목={}개, 항목={}개(서버 조정 {}건, 미언급 보충 {}건)",
                context.userId(), courses.size(), topics.size(), adjusted, filled);

        return new PlanStrategy(goal, blankToNull(ai.strategySummary()), StrategySource.NEW, null,
                referencedContextIds(ai, contextStatuses), courses, topics,
                ai.planningRules() == null ? List.of() : ai.planningRules());
    }

    /**
     * 이 항목을 어디까지 줄일 수 있는가(§4).
     *
     * <p>서버가 아는 사실이 먼저다 — {@code user_mark}와 진행 상태는 조회하면 나오는 값이라
     * 모델이 뭐라고 하든 그대로 쓴다. 맥락은 모델이 어느 행을 근거로 삼았는지 말해야 알 수
     * 있으므로 그 주장을 받되, <b>그 행이 실제로 있고 아직 유효한지는 서버가 대조한다.</b>
     * 없는 맥락을 가리키는 근거로는 아무것도 줄일 수 없다.
     *
     * <p>맥락이 이 항목의 범위를 직접 덮는지(DIRECT)는 모델의 판단으로 남는다. 그 문장이
     * 무엇을 덮는지는 의미의 문제라 서버가 대조할 방법이 없다. 대신 그 주장은 반드시 실재하는
     * ACTIVE 맥락에 붙어 있어야 하고, 그 조합이 아니면 SKIP까지 내려가지 못한다.
     */
    private Treatment cap(TopicContext topic, List<PlanJudgmentAiResult.AiEvidence> claimed,
                          Map<Long, UserContextStatus> contextStatuses) {
        if (topic.userMark() == TopicUserMark.KNOWN || topic.userMark() == TopicUserMark.DEFER) {
            return Treatment.SKIP;
        }
        if (topic.progressStatus() == TopicProgressStatus.LEARNED) {
            return Treatment.SKIP;
        }
        // 근거가 없으면 FULL이다. 아래 반복은 줄일 수 있는 한도를 넓히기만 한다.
        Treatment cap = Treatment.FULL;
        if (claimed == null) {
            return cap;
        }
        for (PlanJudgmentAiResult.AiEvidence evidence : claimed) {
            if (evidence == null || parseEvidenceType(evidence.type()) != EvidenceType.CONTEXT) {
                continue;
            }
            UserContextStatus status = evidence.refId() == null ? null : contextStatuses.get(evidence.refId());
            if (status == null) {
                // 없는 맥락을 가리키는 근거로는 아무것도 줄일 수 없다.
                continue;
            }
            if (status == UserContextStatus.ACTIVE && "DIRECT".equalsIgnoreCase(evidence.scopeMatch())) {
                return Treatment.SKIP;
            }
            // ACTIVE + 넓거나 간접, 그리고 STALE은 둘 다 여기까지다.
            cap = Treatment.SKIM;
        }
        return cap;
    }

    /** 실재하는 것만 남긴다. 근거의 개수가 취급 상한을 정하므로 세는 것과 남는 것이 같아야 한다. */
    private List<Evidence> verifiedEvidence(List<PlanJudgmentAiResult.AiEvidence> claimed, TopicContext topic,
                                            Map<Long, UserContextStatus> contextStatuses) {
        List<Evidence> kept = new ArrayList<>();
        // 서버가 아는 사실은 모델이 적었는지와 무관하게 남긴다 — 화면이 "왜 뺐는지"를 말해야 한다.
        if (topic.userMark() != null) {
            kept.add(new Evidence(EvidenceType.USER_MARK, topic.userMark().name(), topic.topicId()));
        }
        if (topic.progressStatus() == TopicProgressStatus.LEARNED) {
            kept.add(new Evidence(EvidenceType.PROGRESS, topic.progressStatus().name(), topic.topicId()));
        }
        if (claimed == null) {
            return kept;
        }
        for (PlanJudgmentAiResult.AiEvidence evidence : claimed) {
            if (evidence == null) {
                continue;
            }
            EvidenceType type = parseEvidenceType(evidence.type());
            if (type == null) {
                // 모르는 근거 종류. 버린다 — 근거의 종류를 모델이 늘릴 수 있으면 등급표가 무의미해진다.
                continue;
            }
            if (type == EvidenceType.USER_MARK || type == EvidenceType.PROGRESS) {
                // 서버가 이미 사실대로 넣었다. 모델이 주장한 값은 쓰지 않는다.
                continue;
            }
            if (type == EvidenceType.CONTEXT
                    && (evidence.refId() == null || !contextStatuses.containsKey(evidence.refId()))) {
                // 없는 맥락을 가리킨다.
                continue;
            }
            kept.add(new Evidence(type, evidence.value(), evidence.refId()));
        }
        return kept;
    }

    /** 둘 중 덜 줄이는 쪽. FULL이 가장 덜 줄이고 SKIP이 가장 많이 줄인다. */
    private Treatment weaker(Treatment a, Treatment b) {
        return level(a) <= level(b) ? a : b;
    }

    private int level(Treatment treatment) {
        return switch (treatment) {
            case FULL -> 0;
            case REVIEW, SKIM -> 1;
            case SKIP -> 2;
        };
    }

    private Treatment parseTreatment(String raw) {
        if (raw == null) {
            return null;
        }
        // REVIEW는 v1에서 쓰지 않는다. 모델이 내면 SKIM으로 읽는다 — 자리는 있고 뜻은 아직 없다.
        return switch (raw.trim().toUpperCase()) {
            case "FULL" -> Treatment.FULL;
            case "SKIM", "REVIEW" -> Treatment.SKIM;
            case "SKIP" -> Treatment.SKIP;
            default -> null;
        };
    }

    private EvidenceType parseEvidenceType(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return EvidenceType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private List<Long> referencedContextIds(PlanJudgmentAiResult ai, Map<Long, UserContextStatus> statuses) {
        if (ai.referencedContextIds() == null) {
            return List.of();
        }
        List<Long> kept = new ArrayList<>();
        for (Long id : ai.referencedContextIds()) {
            if (id != null && statuses.containsKey(id) && !kept.contains(id)) {
                kept.add(id);
            }
        }
        return kept;
    }

    private Map<Long, TopicContext> topicsById(PlanningContext context) {
        Map<Long, TopicContext> byId = new HashMap<>();
        for (CourseContext course : context.courses()) {
            for (TopicContext topic : course.topics()) {
                byId.put(topic.topicId(), topic);
            }
        }
        return byId;
    }

    // ===== 모델 호출 =====

    private static final String SYSTEM_PROMPT = """
            너는 학습 계획의 판단을 맡는다. 이번 기간을 **왜 이렇게 운영할 것인지**만 정하고,
            실제로 할 일 목록은 만들지 않는다.

            순서대로 생각한다.
            1. 이 기간의 현실적인 목적을 먼저 정한다. 밀린 내용 전부를 자동으로 목표로 삼지 않는다.
            2. 과목별 다음 수업이 언제인지 본다.
            3. 그 수업을 이해하는 데 필요한 이전 내용만 고른다.
            4. 이미 마친 내용은 다시 시키지 않는다.
            5. 사용자가 이미 안다고 확인된 맥락이 있으면 그 범위는 훑는 정도로 줄인다.
               확인된 근거가 없으면 익숙한지 아닌지를 추측하지 않는다.
            6. 과목 사이의 순서는 빠른 수업, 선수지식 필요, 진행 상태로 정한다.
            7. [학습 항목]에 없는 것을 지어내지 않는다. topicId는 반드시 거기 있는 값이다.
            8. 실제 시각을 정하지 않는다. 언제 할지는 다른 곳에서 계산한다.
            9. 사용자를 탓하거나 뒤처졌다는 식으로 쓰지 않는다. 못 한 것은 "아직 시작하지
               않았어요" 정도로만 다룬다. "부족", "미흡", "수준", "실력"은 쓰지 않는다.

            취급(treatment)은 셋 중 하나다.
            - FULL: 자료 분량대로 제대로 한다. **근거가 없으면 항상 FULL이다.**
            - SKIM: 훑는다. 이미 안다는 근거가 있을 때만.
            - SKIP: 이번 계획에서 뺀다.

            근거(evidence)는 아래 종류만 쓴다. 여기 없는 종류를 만들면 그 근거는 버려진다.
            - CONTEXT: [확인된 맥락]의 한 줄. refId에 그 줄의 번호를 적고, 그 맥락이 이 학습
              항목의 범위를 직접 덮으면 scopeMatch를 DIRECT, 넓거나 간접이면 BROAD로 적는다.
            - NEXT_CLASS: 다음 수업 시각
            - DEADLINE: 시험·과제 기한
            진행 상태와 사용자 표식은 서버가 이미 알고 있으므로 적지 않아도 된다.

            rank는 **과목마다 다음 수업 전에 반드시 해야 하는 항목을 최대 3개만** 골라
            1, 2, 3으로 매긴다. 나머지 항목은 rank를 비워 둔다(생략한다).
            전체에 순위를 매기려 하지 마라 — 그렇게 하면 다 비슷한 값이 되어 "무엇이 먼저인지"를
            아무것도 말하지 않게 된다. rank가 붙은 항목이 계획 앞쪽에 놓이고, 시간이 모자라면
            rank 없는 항목부터 잘린다. 그래서 이 세 개를 고르는 것이 이 판단의 알맹이다.

            네가 적은 근거가 실제로 있는지 서버가 대조한다. 없는 맥락 번호를 적으면 그 근거는
            버려지고, 근거가 없어진 항목은 FULL로 되돌아간다. 그럴싸한 문장을 근거 자리에
            쓰지 마라 — 근거는 어느 줄을 봤는지이지 왜 그렇게 생각했는지가 아니다.

            [사용자 지시]가 서로 다른 목표 기간 둘 이상을 겨냥하면 goals에 그 기간들을 각각
            적는다. 어느 쪽을 고를지는 네가 정하지 않는다.

            먼저 사용자에게 보여줄 한국어 요약을 두세 문장으로 쓰고, 그 다음 줄에 %s 를 쓰고,
            그 아래에 아래 형식의 JSON만 쓴다.

            {
              "goals": [{"goal": "이 기간에 이루려는 것", "until": "그 목표의 끝(사람 말로)"}],
              "strategySummary": "이 기간을 어떻게 운영하는지 한두 문장",
              "courses": [{"courseId": 0, "rank": 1, "focus": "이 과목에서 무엇에 집중하나",
                           "reason": "왜 이 순서인가"}],
              "topics": [{"topicId": 0, "treatment": "FULL", "rank": 1, "reason": "왜 이렇게 다루나",
                          "evidence": [{"type": "NEXT_CLASS", "value": "9/9 10:00 웹서버프로그래밍",
                                        "refId": null, "scopeMatch": null}]}],
              "planningRules": ["이 기간 내내 지켜야 할 조건"],
              "referencedContextIds": [0]
            }
            """.formatted(AiStreamParser.DELIMITER);

    private PlanJudgmentAiResult callAi(PlanningContext context) {
        Long userId = context.userId();
        String userPrompt = buildUserPrompt(context);
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
            log.warn("계획 판단 AI 호출 실패: userId={}", userId, e);
            throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
        }
        recordUsage(userId, lastUsage.get(), UsageResultStatus.SUCCESS, null);

        String finishReason = lastFinishReason.get();
        if (AiChatResponseUtils.isTruncatedByTokenLimit(finishReason)) {
            // 초안 생성에서 한 번 겪은 실패 방식이다. 파싱 실패로 보고되면 원인이 토큰 예산이라는
            // 사실이 스택트레이스 뒤에 숨는다.
            log.warn("계획 판단: 출력이 토큰 상한({})에서 잘림. userId={}, 항목={}개, outputTokens={}",
                    maxCompletionTokens, userId, topicsById(context).size(),
                    AiChatResponseUtils.safeTokenCount(lastUsage.get(), false));
            throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
        }

        AiStreamParser.Result result = parser.finish();
        if (result.structuredJson() == null) {
            log.warn("계획 판단: 구조화 JSON이 없음. userId={}, finishReason={}", userId, finishReason);
            throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
        }
        try {
            return objectMapper.readValue(result.structuredJson(), PlanJudgmentAiResult.class);
        } catch (Exception e) {
            log.warn("계획 판단: 구조화 JSON 파싱 실패. userId={}, finishReason={}", userId, finishReason, e);
            throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
        }
    }

    String buildUserPrompt(PlanningContext context) {
        StringBuilder sb = new StringBuilder();

        sb.append("[지금]\n").append(context.now()).append("\n\n");
        sb.append("[계획 기간]\n").append(context.start()).append(" ~ ").append(context.end())
                .append(" · 강도 ").append(context.intensity().name()).append("\n\n");

        sb.append("[요일별 남는 시간]\n");
        for (String line : context.availabilityByDay()) {
            sb.append(line).append('\n');
        }
        sb.append("이 시간을 어떻게 쓸지는 정하지 않는다. 무엇을 할지만 정한다.\n\n");

        sb.append("[과목과 학습 항목]\n");
        if (context.courses().isEmpty()) {
            sb.append("(없음)\n");
        }
        for (CourseContext course : context.courses()) {
            sb.append("- courseId=").append(course.courseId()).append(' ').append(course.title());
            if (course.nextClassAt() != null) {
                sb.append(" · 다음 수업 ").append(course.nextClassAt().format(CLASS_TIME));
            } else {
                sb.append(" · 등록된 수업 없음");
            }
            if (course.currentWeek() != null) {
                sb.append(" · 현재 ").append(course.currentWeek()).append("주차");
            }
            sb.append('\n');
            for (TopicContext topic : course.topics()) {
                sb.append("    topicId=").append(topic.topicId()).append(' ').append(topic.title());
                if (topic.week() != null) {
                    sb.append(" [").append(topic.week()).append("주차]");
                } else if (blankToNull(topic.sourceLocator()) != null) {
                    sb.append(" [").append(topic.sourceLocator()).append(']');
                }
                sb.append(" [").append(topic.progressStatus()).append(']');
                if (topic.userMark() != null) {
                    sb.append(" [사용자: ")
                            .append(topic.userMark() == TopicUserMark.KNOWN ? "이미 알아요" : "이번엔 빼기")
                            .append(']');
                }
                sb.append('\n');
            }
        }
        sb.append('\n');

        sb.append("[확인된 맥락]\n");
        if (context.contexts().isEmpty()) {
            sb.append("(없음 — 익숙함을 추측할 근거가 없다는 뜻이다)\n");
        }
        for (ContextLine line : context.contexts()) {
            sb.append("- refId=").append(line.contextId())
                    .append(line.status() == UserContextStatus.STALE ? " (확인이 오래됨) " : " ")
                    .append(line.content()).append('\n');
        }
        sb.append('\n');

        if (context.previousReview() != null) {
            sb.append("[직전 계획 회고]\n").append(context.previousReview()).append("\n\n");
        }
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
