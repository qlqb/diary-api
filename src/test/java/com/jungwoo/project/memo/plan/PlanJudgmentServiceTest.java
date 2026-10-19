package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.ai.AiConsultationClient;
import com.jungwoo.project.memo.ai.AiStreamParser;
import com.jungwoo.project.memo.ai.AiUsageLimitService;
import com.jungwoo.project.memo.ai.domain.ContextSourceType;
import com.jungwoo.project.memo.ai.domain.UserContextStatus;
import com.jungwoo.project.memo.learning.domain.TopicProgressStatus;
import com.jungwoo.project.memo.learning.domain.TopicUserMark;
import com.jungwoo.project.memo.plan.PlanningContext.ContextLine;
import com.jungwoo.project.memo.plan.PlanningContext.CourseContext;
import com.jungwoo.project.memo.plan.PlanningContext.TopicContext;
import com.jungwoo.project.memo.plan.domain.AskReason;
import com.jungwoo.project.memo.plan.domain.EvidenceType;
import com.jungwoo.project.memo.plan.domain.PlanIntensity;
import com.jungwoo.project.memo.plan.domain.PlanStrategy;
import com.jungwoo.project.memo.plan.domain.PlanVersion;
import com.jungwoo.project.memo.plan.domain.StrategySource;
import com.jungwoo.project.memo.plan.domain.Treatment;
import com.jungwoo.project.memo.plan.dto.PlanJudgmentResult;
import com.jungwoo.project.memo.scheduling.domain.AvailabilityConfidence;
import com.jungwoo.project.memo.scheduling.domain.AvailabilitySource;
import com.jungwoo.project.memo.scheduling.domain.AvailabilityWindow;
import com.jungwoo.project.memo.scheduling.service.AvailabilityEstimateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 판단층. 이 스위트가 지키는 것은 "좋은 판단"이 아니라 <b>서버가 모델을 어디까지 믿는가</b>다 —
 * 근거 없는 압축은 되돌아오는가, 지어낸 근거는 버려지는가, 되묻기를 코드가 정하는가.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlanJudgmentServiceTest {

    private static final Long USER_ID = 7L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 6, 21, 37);
    private static final LocalDate START = LocalDate.of(2026, 9, 7);
    private static final LocalDate END = LocalDate.of(2026, 9, 13);
    private static final LocalDateTime NEXT_CLASS = LocalDateTime.of(2026, 9, 8, 14, 0);

    @Mock
    private AiConsultationClient aiConsultationClient;
    @Mock
    private AiUsageLimitService aiUsageLimitService;

    private PlanJudgmentService service;

    @BeforeEach
    void setUp() {
        service = new PlanJudgmentService(aiConsultationClient, aiUsageLimitService);
        when(aiConsultationClient.isConfigured()).thenReturn(true);
    }

    // ===== 근거 등급 =====

    @Test
    @DisplayName("GD-1: 확인이 오래된 맥락만 있는데 모델이 SKIP을 내면 서버가 SKIM으로 되돌린다")
    void staleContextCannotBuyASkip() {
        givenAi(topicJson(217L, "SKIP", "이미 안다", contextEvidence(1L, "DIRECT")));

        PlanStrategy strategy = judge(contextWith(
                staleContext(1L, "파이썬 기본 문법은 익숙하다"),
                topic(217L, "파이썬 기초", TopicProgressStatus.NOT_STARTED, null)));

        assertThat(treatment(strategy, 217L).treatment()).isEqualTo(Treatment.SKIM);
        assertThat(treatment(strategy, 217L).reason()).endsWith("(서버 조정)");
    }

    @Test
    @DisplayName("아직 유효한 맥락이 범위를 직접 덮는다고 하면 SKIP까지 허용한다")
    void activeContextWithDirectScopeAllowsSkip() {
        givenAi(topicJson(217L, "SKIP", "이미 안다", contextEvidence(1L, "DIRECT")));

        PlanStrategy strategy = judge(contextWith(
                activeContext(1L, "파이썬 기본 문법은 익숙하다"),
                topic(217L, "파이썬 기초", TopicProgressStatus.NOT_STARTED, null)));

        assertThat(treatment(strategy, 217L).treatment()).isEqualTo(Treatment.SKIP);
        assertThat(treatment(strategy, 217L).reason()).doesNotContain("서버 조정");
    }

    @Test
    @DisplayName("유효한 맥락이라도 범위가 넓거나 간접이면 SKIM까지다")
    void activeContextWithBroadScopeStopsAtSkim() {
        givenAi(topicJson(217L, "SKIP", "대충 안다", contextEvidence(1L, "BROAD")));

        PlanStrategy strategy = judge(contextWith(
                activeContext(1L, "프로그래밍 경험이 좀 있다"),
                topic(217L, "파이썬 기초", TopicProgressStatus.NOT_STARTED, null)));

        assertThat(treatment(strategy, 217L).treatment()).isEqualTo(Treatment.SKIM);
    }

    @Test
    @DisplayName("존재하지 않는 맥락을 근거로 대면 아무것도 줄이지 못한다")
    void fabricatedContextBuysNothing() {
        givenAi(topicJson(217L, "SKIP", "안다고 했었다", contextEvidence(999L, "DIRECT")));

        PlanStrategy strategy = judge(contextWith(
                activeContext(1L, "주말은 휴무"),
                topic(217L, "파이썬 기초", TopicProgressStatus.NOT_STARTED, null)));

        assertThat(treatment(strategy, 217L).treatment())
                .as("근거가 없으면 FULL이다")
                .isEqualTo(Treatment.FULL);
        assertThat(treatment(strategy, 217L).evidence())
                .as("없는 맥락을 가리키는 근거는 남지 않는다")
                .isEmpty();
    }

    @Test
    @DisplayName("GD-2: 모르는 근거 종류는 버리고, 그것뿐이었으면 취급도 FULL로 되돌린다")
    void unknownEvidenceTypeIsDropped() {
        givenAi(topicJson(217L, "SKIM", "느낌상",
                "{\"type\":\"MODEL_GUESS\",\"value\":\"쉬워 보인다\",\"refId\":null,\"scopeMatch\":null}"));

        PlanStrategy strategy = judge(contextWith(
                activeContext(1L, "주말은 휴무"),
                topic(217L, "파이썬 기초", TopicProgressStatus.NOT_STARTED, null)));

        assertThat(treatment(strategy, 217L).evidence()).isEmpty();
        assertThat(treatment(strategy, 217L).treatment()).isEqualTo(Treatment.FULL);
    }

    @Test
    @DisplayName("사용자가 이미 안다고 한 항목은 모델이 FULL을 내도 그 사실이 근거로 남는다")
    void userMarkIsAServerFact() {
        givenAi(topicJson(217L, "FULL", "처음일 것 같다", null));

        PlanStrategy strategy = judge(contextWith(
                activeContext(1L, "주말은 휴무"),
                topic(217L, "파이썬 기초", TopicProgressStatus.NOT_STARTED, TopicUserMark.KNOWN)));

        assertThat(treatment(strategy, 217L).evidence())
                .anySatisfy(e -> {
                    assertThat(e.type()).isEqualTo(EvidenceType.USER_MARK);
                    assertThat(e.value()).isEqualTo("KNOWN");
                });
    }

    @Test
    @DisplayName("컨텍스트에 없는 학습 항목을 지어내면 버린다")
    void inventedTopicsAreDropped() {
        givenAi(topicJson(999L, "SKIP", "이건 안 해도 된다", null));

        PlanStrategy strategy = judge(contextWith(
                activeContext(1L, "주말은 휴무"),
                topic(217L, "파이썬 기초", TopicProgressStatus.NOT_STARTED, null)));

        assertThat(strategy.topics()).extracting(PlanStrategy.TopicTreatment::topicId)
                .containsExactly(217L);
    }

    @Test
    @DisplayName("모델이 언급하지 않은 항목은 FULL로 채운다 — 언급 안 함이 빼도 됨이 되면 안 된다")
    void unmentionedTopicsBecomeFull() {
        givenAi(topicJson(217L, "FULL", "다음 수업 내용", null));

        PlanStrategy strategy = judge(contextWith(
                activeContext(1L, "주말은 휴무"),
                topic(217L, "파이썬 기초", TopicProgressStatus.NOT_STARTED, null),
                topic(218L, "넘파이", TopicProgressStatus.NOT_STARTED, null)));

        assertThat(treatment(strategy, 218L).treatment()).isEqualTo(Treatment.FULL);
        assertThat(treatment(strategy, 218L).reason()).contains("언급되지 않아");
    }

    // ===== 되묻기 =====

    @Test
    @DisplayName("ASK-1: 지시문이 기간 둘을 겨냥하면 모델을 부르지도 않고 되묻는다")
    void multiPeriodInstructionAsksWithoutCallingTheModel() {
        PlanJudgmentResult result = service.judge(contextWith(
                "중간고사까지는 시험 준비만 하고 그 후에는 평소대로 돌아가고 싶어",
                activeContext(1L, "주말은 휴무"),
                topic(217L, "파이썬 기초", TopicProgressStatus.NOT_STARTED, null)));

        assertThat(result.isAsk()).isTrue();
        assertThat(result.ask().reason()).isEqualTo(AskReason.MULTI_PERIOD);
        verify(aiConsultationClient, never()).streamTurn(any(), any(), anyInt());
    }

    @Test
    @DisplayName("마감만 말하는 지시문은 되묻지 않는다 — 잘못 되물으면 계획 대신 질문을 받는다")
    void aPlainDeadlineIsNotTwoPeriods() {
        assertThat(PlanJudgmentService.mentionsTwoPeriods("금요일까지 끝내고 싶어")).isFalse();
        assertThat(PlanJudgmentService.mentionsTwoPeriods("그 후에 알려줘")).isFalse();
        assertThat(PlanJudgmentService.mentionsTwoPeriods(null)).isFalse();
        assertThat(PlanJudgmentService.mentionsTwoPeriods("시험까지 몰아치고 그다음엔 쉬고 싶어")).isTrue();
    }

    @Test
    @DisplayName("모델이 목표 기간을 둘 이상 내면 계획을 만들지 않는다 — 세는 것은 서버다")
    void twoGoalsFromTheModelAlsoAsk() {
        givenAi("""
                {"goals":[{"goal":"시험 준비","until":"중간고사"},{"goal":"진도 따라잡기","until":"학기 말"}],
                 "strategySummary":"","courses":[],"topics":[],"planningRules":[],"referencedContextIds":[]}
                """);

        PlanJudgmentResult result = service.judge(contextWith(
                activeContext(1L, "주말은 휴무"),
                topic(217L, "파이썬 기초", TopicProgressStatus.NOT_STARTED, null)));

        assertThat(result.isAsk()).isTrue();
        assertThat(result.ask().reason()).isEqualTo(AskReason.MULTI_PERIOD);
        assertThat(result.ask().options()).containsExactly("중간고사", "학기 말");
    }

    @Test
    @DisplayName("ASK-2: 근거 없는 항목의 시간 차가 크면 한 번 되묻는다")
    void unknownFamiliarityAsksOnce() {
        // 근거가 하나도 없는 FULL만 넷. 훑기로 바꾸면 총량이 33% 줄어든다.
        List<TopicContext> topics = new ArrayList<>();
        StringBuilder json = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            long id = 300L + i;
            topics.add(topic(id, "처음 보는 내용 " + i, TopicProgressStatus.NOT_STARTED, null));
            json.append(i > 0 ? "," : "").append(topicEntry(id, "FULL", "처음인 것 같다", null));
        }
        givenAi(goalsAndTopics(json.toString()));

        PlanJudgmentResult result = service.judge(contextWith(
                activeContext(1L, "주말은 휴무"), topics.toArray(new TopicContext[0])));

        assertThat(result.isAsk()).isTrue();
        assertThat(result.ask().reason()).isEqualTo(AskReason.UNKNOWN_FAMILIARITY);
        assertThat(result.ask().question())
                .as("어느 과목 이야기인지가 질문에 있어야 답할 수 있다")
                .startsWith("자료구조의")
                .contains("처음 보는 내용 0");
        assertThat(result.ask().options()).contains("처음이에요", "이미 익숙해요");
        assertThat(result.ask().topicIds())
                .as("답을 맥락으로 저장하려면 무엇을 물었는지가 응답에 실려야 한다")
                .containsExactly(300L, 301L, 302L, 303L);
    }

    @Test
    @DisplayName("답에 이미 답했으면 다시 묻지 않는다 — 이게 없으면 '처음이에요'가 무한히 반복된다")
    void answeringSuppressesTheAsk() {
        List<TopicContext> topics = new ArrayList<>();
        StringBuilder json = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            long id = 300L + i;
            topics.add(topic(id, "처음 보는 내용 " + i, TopicProgressStatus.NOT_STARTED, null));
            json.append(i > 0 ? "," : "").append(topicEntry(id, "FULL", "처음인 것 같다", null));
        }
        givenAi(goalsAndTopics(json.toString()));
        PlanningContext ctx = contextWith(
                activeContext(1L, "주말은 휴무"), topics.toArray(new TopicContext[0]));

        assertThat(service.judge(ctx, false).isAsk()).isTrue();
        assertThat(service.judge(ctx, true).isAsk()).isFalse();
    }

    @Test
    @DisplayName("「이미 익숙해요」는 어느 과목의 무엇인지가 담긴 맥락 문장이 된다")
    void familiarityStatementNamesTheCourseAndTopics() {
        PlanningContext ctx = contextWith(
                activeContext(1L, "주말은 휴무"),
                topic(300L, "파이썬 기초", TopicProgressStatus.NOT_STARTED, null),
                topic(301L, "변수와 연산자", TopicProgressStatus.NOT_STARTED, null));

        assertThat(service.familiarityStatement(ctx, List.of(300L, 301L)))
                .isEqualTo("자료구조의 파이썬 기초, 변수와 연산자은(는) 이미 익숙하다");
        assertThat(service.familiarityStatement(ctx, List.of())).isNull();
        assertThat(service.familiarityStatement(ctx, null)).isNull();
    }

    @Test
    @DisplayName("근거 없는 항목이 있어도 총량을 크게 흔들지 않으면 묻지 않는다")
    void aSingleUnknownAmongManyDoesNotAsk() {
        List<TopicContext> topics = new ArrayList<>();
        StringBuilder json = new StringBuilder();
        // 하나만 익숙함을 모르고, 나머지 아홉은 맥락이 덮는다(덮여도 제대로 하기로 했으니 FULL).
        topics.add(topic(300L, "처음 보는 내용", TopicProgressStatus.NOT_STARTED, null));
        json.append(topicEntry(300L, "FULL", "처음인 것 같다", null));
        for (int i = 1; i < 10; i++) {
            long id = 300L + i;
            topics.add(topic(id, "맥락이 덮는 내용 " + i, TopicProgressStatus.NOT_STARTED, null));
            json.append(",").append(topicEntry(id, "FULL", "익숙하다고 했지만 제대로 보기로 했다",
                    contextEvidence(1L, "BROAD")));
        }
        givenAi(goalsAndTopics(json.toString()));

        PlanJudgmentResult result = service.judge(contextWith(
                activeContext(1L, "이 과목 앞부분은 예전에 다뤄 본 적이 있다"),
                topics.toArray(new TopicContext[0])));

        assertThat(result.isAsk())
                .as("15분 차이는 총 450분의 25%에도, 블록 하나(45분)에도 못 미친다")
                .isFalse();
    }

    // ===== 전략 재사용 =====

    @Test
    @DisplayName("ST-2: 새 기간이 원본 버전의 종료일 안이면 판단을 이어받는다")
    void reuseWithinTheOriginalEndDate() {
        PlanVersion source = PlanVersion.builder()
                .planVersionId(42L).endDate(LocalDate.of(2026, 9, 30)).build();
        PlanStrategy original = strategy();

        assertThat(service.reuse(source, original, LocalDate.of(2026, 9, 30)))
                .hasValueSatisfying(reused -> {
                    assertThat(reused.strategySource()).isEqualTo(StrategySource.REUSED);
                    assertThat(reused.reusedFromVersionId()).isEqualTo(42L);
                    assertThat(reused.goal()).isEqualTo(original.goal());
                });
    }

    @Test
    @DisplayName("종료일을 넘기면 이어받지 않는다 — 그때 내린 판단이 아직 맞다고 볼 근거가 없다")
    void doNotReuseBeyondTheOriginalEndDate() {
        PlanVersion source = PlanVersion.builder()
                .planVersionId(42L).endDate(LocalDate.of(2026, 9, 30)).build();

        assertThat(service.reuse(source, strategy(), LocalDate.of(2026, 10, 1))).isEmpty();
    }

    // ===== 프롬프트 =====

    @Test
    @DisplayName("프롬프트에 맥락 번호가 실린다 — 없는 번호를 적었는지 대조할 수 있어야 한다")
    void promptCarriesContextRefIds() {
        String prompt = service.buildUserPrompt(contextWith(
                staleContext(9L, "파이썬은 익숙하다"),
                topic(217L, "파이썬 기초", TopicProgressStatus.NOT_STARTED, TopicUserMark.KNOWN)));

        assertThat(prompt)
                .contains("refId=9")
                .contains("(확인이 오래됨)")
                .contains("topicId=217")
                .contains("[사용자: 이미 알아요]")
                .contains("다음 수업");
    }

    // ===== fixture =====

    private PlanStrategy judge(PlanningContext context) {
        PlanJudgmentResult result = service.judge(context);
        assertThat(result.isAsk()).as("이 케이스는 되묻지 않아야 한다").isFalse();
        return result.strategy();
    }

    private static PlanStrategy.TopicTreatment treatment(PlanStrategy strategy, Long topicId) {
        return strategy.topics().stream()
                .filter(t -> t.topicId().equals(topicId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("전략에 topicId=" + topicId + " 판단이 없다"));
    }

    private void givenAi(String structuredJson) {
        when(aiConsultationClient.streamTurn(any(), any(), anyInt()))
                .thenReturn(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(
                        "이렇게 봤어요\n" + AiStreamParser.DELIMITER + "\n" + structuredJson))))));
    }

    private static String topicJson(long topicId, String treatment, String reason, String evidenceJson) {
        return goalsAndTopics(topicEntry(topicId, treatment, reason, evidenceJson));
    }

    private static String goalsAndTopics(String topicEntries) {
        return """
                {"goals":[{"goal":"다음 수업 따라가기","until":"화요일 수업"}],
                 "strategySummary":"필요한 것만 고른다",
                 "courses":[{"courseId":36,"rank":1,"focus":"화요일 전까지","reason":"가장 빠른 수업"}],
                 "topics":[%s],
                 "planningRules":["다음 수업 전 선수내용 완료"],
                 "referencedContextIds":[1]}
                """.formatted(topicEntries);
    }

    private static String topicEntry(long topicId, String treatment, String reason, String evidenceJson) {
        return """
                {"topicId":%d,"treatment":"%s","rank":1,"reason":"%s","evidence":[%s]}
                """.formatted(topicId, treatment, reason, evidenceJson == null ? "" : evidenceJson);
    }

    private static String contextEvidence(long refId, String scopeMatch) {
        return "{\"type\":\"CONTEXT\",\"value\":\"확인된 맥락\",\"refId\":%d,\"scopeMatch\":\"%s\"}"
                .formatted(refId, scopeMatch);
    }

    private static PlanningContext contextWith(ContextLine context, TopicContext... topics) {
        return contextWith(null, context, topics);
    }

    private static PlanningContext contextWith(String instruction, ContextLine context, TopicContext... topics) {
        List<AvailabilityWindow> windows = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            LocalDate date = START.plusDays(i);
            windows.add(new AvailabilityWindow(date.atTime(19, 0), date.atTime(22, 0),
                    AvailabilitySource.DEFAULT_INFERENCE, AvailabilityConfidence.LOW, "기본 시간대"));
        }
        AvailabilityEstimateResult availability = new AvailabilityEstimateResult(windows, List.of());
        CourseContext course = new CourseContext(36L, "자료구조", null, NEXT_CLASS, 85L, 2, List.of(topics));
        return new PlanningContext(USER_ID, NOW, START, END, PlanIntensity.NORMAL,
                List.of(course), AvailabilityDaySummary.format(START, END, availability),
                availability, List.of(context), null, instruction);
    }

    private static TopicContext topic(Long topicId, String title, TopicProgressStatus status, TopicUserMark mark) {
        return new TopicContext(topicId, title, "2주차", 2, status, null, mark, 0, 901L, "강의자료.pdf");
    }

    private static ContextLine activeContext(Long id, String content) {
        return new ContextLine(id, content, UserContextStatus.ACTIVE, ContextSourceType.AI_SUGGESTION_APPROVED);
    }

    private static ContextLine staleContext(Long id, String content) {
        return new ContextLine(id, content, UserContextStatus.STALE, ContextSourceType.USER_CONFIRMED);
    }

    private static PlanStrategy strategy() {
        return new PlanStrategy("다음 수업 따라가기", "필요한 것만", StrategySource.NEW, null,
                List.of(1L), List.of(), List.of(), List.of("다음 수업 전 선수내용 완료"));
    }

    @Test
    @DisplayName("과목이 여럿이어도 되묻기가 죽지 않는다 — 비율은 묻는 과목 안에서 잰다")
    void theAskSurvivesAMultiCourseePlan() {
        // 근거 없는 과목 하나(4개) + 근거가 덮는 큰 과목 하나(20개).
        // 계획 전체를 분모로 두면 60/1140 = 5%라 영영 묻지 않게 된다.
        List<TopicContext> unknownCourse = new ArrayList<>();
        List<TopicContext> knownCourse = new ArrayList<>();
        StringBuilder json = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            long id = 300L + i;
            unknownCourse.add(topic(id, "처음 보는 내용 " + i, TopicProgressStatus.NOT_STARTED, null));
            json.append(i > 0 ? "," : "").append(topicEntry(id, "FULL", "처음인 것 같다", null));
        }
        for (int i = 0; i < 20; i++) {
            long id = 400L + i;
            knownCourse.add(topic(id, "맥락이 덮는 내용 " + i, TopicProgressStatus.NOT_STARTED, null));
            json.append(",").append(topicEntry(id, "FULL", "익숙하지만 제대로 본다",
                    contextEvidence(1L, "BROAD")));
        }
        givenAi(goalsAndTopics(json.toString()));

        PlanningContext ctx = twoCourses(
                activeContext(1L, "두 번째 과목 내용은 예전에 다뤄 봤다"), unknownCourse, knownCourse);
        PlanJudgmentResult result = service.judge(ctx, false);

        assertThat(result.isAsk()).isTrue();
        assertThat(result.ask().question()).startsWith("자료구조의");
        assertThat(result.ask().topicIds()).containsExactly(300L, 301L, 302L, 303L);
    }

    private static PlanningContext twoCourses(ContextLine context,
                                              List<TopicContext> first, List<TopicContext> second) {
        List<AvailabilityWindow> windows = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            LocalDate date = START.plusDays(i);
            windows.add(new AvailabilityWindow(date.atTime(19, 0), date.atTime(22, 0),
                    AvailabilitySource.DEFAULT_INFERENCE, AvailabilityConfidence.LOW, "기본 시간대"));
        }
        AvailabilityEstimateResult availability = new AvailabilityEstimateResult(windows, List.of());
        return new PlanningContext(USER_ID, NOW, START, END, PlanIntensity.NORMAL,
                List.of(new CourseContext(36L, "자료구조", null, NEXT_CLASS, 85L, 2, first),
                        new CourseContext(31L, "빅데이터분석", null, NEXT_CLASS, 80L, 2, second)),
                AvailabilityDaySummary.format(START, END, availability),
                availability, List.of(context), null, null);
    }
}
