package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.ai.dto.ProposalItem;
import com.jungwoo.project.memo.learning.domain.TopicProgressStatus;
import com.jungwoo.project.memo.learning.domain.TopicUserMark;
import com.jungwoo.project.memo.plan.PeriodPlanDraftGenerator.Generated;
import com.jungwoo.project.memo.plan.PeriodPlanDraftGenerator.Spec;
import com.jungwoo.project.memo.plan.PlanningContext.CourseContext;
import com.jungwoo.project.memo.plan.PlanningContext.TopicContext;
import com.jungwoo.project.memo.plan.domain.EvidenceType;
import com.jungwoo.project.memo.plan.domain.PlanIntensity;
import com.jungwoo.project.memo.plan.domain.PlanStrategy;
import com.jungwoo.project.memo.plan.domain.StrategySource;
import com.jungwoo.project.memo.plan.domain.Treatment;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v0 결정적 생성기. 이 스위트가 지키는 것은 "좋은 계획"이 아니라 <b>규칙이 규칙대로
 * 적용되는가</b>다 — 판단의 품질은 판단층이 생긴 뒤에 eval로 본다.
 */
@ExtendWith(MockitoExtension.class)
class PlanBlockGeneratorV0Test {

    private static final Long USER_ID = 7L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 6, 21, 37);
    private static final LocalDate START = LocalDate.of(2026, 9, 7);
    private static final LocalDate END = LocalDate.of(2026, 9, 13);

    /** 화 14:00 자료구조. 이 값이 그 과목 조각의 마감이 된다. */
    private static final LocalDateTime DATA_STRUCTURES_CLASS = LocalDateTime.of(2026, 9, 8, 14, 0);
    /** 수 10:00 웹서버프로그래밍. */
    private static final LocalDateTime WEB_SERVER_CLASS = LocalDateTime.of(2026, 9, 9, 10, 0);

    @Mock
    private PlanningContextBuilder contextBuilder;

    private PlanBlockGeneratorV0 generator;

    @BeforeEach
    void setUp() {
        generator = new PlanBlockGeneratorV0(contextBuilder);
        ReflectionTestUtils.setField(generator, "blockMinutes", 45);
    }

    @Test
    @DisplayName("v0-2: 이미 안다고 표시한 항목은 조각이 되지 않는다")
    void knownTopicsProduceNoBlock() {
        Generated generated = generator.generate(spec(), context(
                course(36L, "자료구조", DATA_STRUCTURES_CLASS,
                        topic(217L, "ADT와 성능분석", TopicProgressStatus.NOT_STARTED, null),
                        topic(218L, "재귀 알고리즘", TopicProgressStatus.NOT_STARTED, TopicUserMark.KNOWN))));

        assertThat(generated.items()).extracting(ProposalItem::title)
                .containsExactly("자료구조 · ADT와 성능분석");
        assertThat(treatment(generated, 218L).treatment()).isEqualTo(Treatment.SKIP);
        assertThat(treatment(generated, 218L).reason()).isEqualTo("이미 알고 있다고 알려주셨어요");
        assertThat(treatment(generated, 218L).evidence())
                .as("사용자가 말한 사실이 근거로 남는다")
                .anySatisfy(e -> {
                    assertThat(e.type()).isEqualTo(EvidenceType.USER_MARK);
                    assertThat(e.value()).isEqualTo("KNOWN");
                });
    }

    @Test
    @DisplayName("이번엔 빼기(DEFER)도 조각이 되지 않지만 이유가 다르다")
    void deferredTopicsAreSkippedForADifferentReason() {
        Generated generated = generator.generate(spec(), context(
                course(36L, "자료구조", DATA_STRUCTURES_CLASS,
                        topic(218L, "재귀 알고리즘", TopicProgressStatus.NOT_STARTED, TopicUserMark.DEFER))));

        assertThat(generated.items()).isEmpty();
        assertThat(treatment(generated, 218L).reason()).isEqualTo("이번에는 빼기로 하셨어요");
    }

    @Test
    @DisplayName("v0-3: 한 번 마친 항목은 조각이 되지 않는다")
    void learnedTopicsProduceNoBlock() {
        Generated generated = generator.generate(spec(), context(
                course(36L, "자료구조", DATA_STRUCTURES_CLASS,
                        topic(216L, "자료구조 개요", TopicProgressStatus.LEARNED, null),
                        topic(217L, "ADT와 성능분석", TopicProgressStatus.NOT_STARTED, null))));

        assertThat(generated.items()).hasSize(1);
        assertThat(treatment(generated, 216L).treatment()).isEqualTo(Treatment.SKIP);
        assertThat(treatment(generated, 216L).reason()).isEqualTo("한 번 마친 내용이에요");
    }

    @Test
    @DisplayName("이미 시작한 항목은 이어간다 — 빼면 반쯤 열어 둔 것이 계획에서 조용히 사라진다")
    void inProgressTopicsAreContinued() {
        Generated generated = generator.generate(spec(), context(
                course(36L, "자료구조", DATA_STRUCTURES_CLASS,
                        topic(217L, "ADT와 성능분석", TopicProgressStatus.IN_PROGRESS, null))));

        assertThat(generated.items()).hasSize(1);
        assertThat(treatment(generated, 217L).treatment()).isEqualTo(Treatment.FULL);
        assertThat(treatment(generated, 217L).reason()).isEqualTo("이어서 진행");
    }

    @Test
    @DisplayName("과목 안에서 시작한 항목이 앞에 온다 — 나머지는 주차 순 그대로")
    void inProgressTopicsComeFirstWithinACourse() {
        Generated generated = generator.generate(spec(), context(
                course(36L, "자료구조", DATA_STRUCTURES_CLASS,
                        topic(216L, "1주차 내용", TopicProgressStatus.NOT_STARTED, null),
                        topic(217L, "2주차 내용", TopicProgressStatus.NOT_STARTED, null),
                        topic(218L, "3주차 내용", TopicProgressStatus.IN_PROGRESS, null))));

        assertThat(generated.items()).extracting(ProposalItem::title)
                .containsExactly("자료구조 · 3주차 내용", "자료구조 · 1주차 내용", "자료구조 · 2주차 내용");
        assertThat(generated.strategy().topics()).extracting(PlanStrategy.TopicTreatment::topicId)
                .as("판단 순서도 같아야 한다 — rank가 곧 그 순서다")
                .containsExactly(218L, 216L, 217L);
    }

    @Test
    @DisplayName("마감은 그 과목의 다음 수업 시작 시각이다")
    void deadlineIsTheNextClass() {
        Generated generated = generator.generate(spec(), context(
                course(36L, "자료구조", DATA_STRUCTURES_CLASS,
                        topic(217L, "ADT와 성능분석", TopicProgressStatus.NOT_STARTED, null)),
                course(32L, "웹서버프로그래밍", WEB_SERVER_CLASS,
                        topic(500L, "HTTP 기초", TopicProgressStatus.NOT_STARTED, null))));

        assertThat(generated.items()).extracting(ProposalItem::deadlineAt)
                .containsExactly(DATA_STRUCTURES_CLASS, WEB_SERVER_CLASS);
    }

    @Test
    @DisplayName("수업이 등록되지 않은 과목에는 마감을 붙이지 않는다 — 근거 없는 마감은 배치만 좁힌다")
    void noClassMeansNoDeadline() {
        Generated generated = generator.generate(spec(), context(
                course(90L, "수업 없는 과목", null,
                        topic(600L, "무언가", TopicProgressStatus.NOT_STARTED, null))));

        assertThat(generated.items().get(0).deadlineAt()).isNull();
    }

    @Test
    @DisplayName("블록 길이는 설정값 하나이고, 분량을 모른다는 사실을 조각에 적는다")
    void blockLengthIsAConfiguredConstantAndSaysSo() {
        Generated generated = generator.generate(spec(), context(
                course(36L, "자료구조", DATA_STRUCTURES_CLASS,
                        topic(217L, "ADT와 성능분석", TopicProgressStatus.NOT_STARTED, null, "2주차"))));

        ProposalItem item = generated.items().get(0);
        assertThat(item.expectedMinutes()).isEqualTo(45);
        assertThat(item.description())
                .contains("(2주차)")
                .contains("완료:")
                .contains("분량 정보 없음 · 기본 45분");
    }

    @Test
    @DisplayName("전략은 NEW로 남고 과목 순위는 컨텍스트가 준 순서를 따른다")
    void strategyRecordsTheCourseOrder() {
        Generated generated = generator.generate(spec(), context(
                course(36L, "자료구조", DATA_STRUCTURES_CLASS,
                        topic(217L, "ADT와 성능분석", TopicProgressStatus.NOT_STARTED, null)),
                course(32L, "웹서버프로그래밍", WEB_SERVER_CLASS,
                        topic(500L, "HTTP 기초", TopicProgressStatus.NOT_STARTED, null))));

        PlanStrategy strategy = generated.strategy();
        assertThat(strategy.strategySource()).isEqualTo(StrategySource.NEW);
        assertThat(strategy.reusedFromVersionId()).isNull();
        assertThat(strategy.courses()).extracting(PlanStrategy.CourseStrategy::courseId)
                .containsExactly(36L, 32L);
        assertThat(strategy.courses()).extracting(PlanStrategy.CourseStrategy::rank)
                .containsExactly(1, 2);
        assertThat(strategy.courses().get(0).focus()).contains("9/8 14:00");
    }

    @Test
    @DisplayName("항목 상한을 넘어도 취급 판단은 전부 남는다 — 잘린 항목이 '판단 안 된 항목'이 되면 안 된다")
    void treatmentsSurviveTheItemCap() {
        List<TopicContext> topics = new ArrayList<>();
        for (int i = 0; i < PeriodPlanDraftGenerator.MAX_ITEMS + 5; i++) {
            topics.add(topic(1000L + i, "항목" + i, TopicProgressStatus.NOT_STARTED, null));
        }
        Generated generated = generator.generate(spec(), context(
                new CourseContext(36L, "자료구조", null, DATA_STRUCTURES_CLASS, 85L, 2, topics)));

        assertThat(generated.items()).hasSize(PeriodPlanDraftGenerator.MAX_ITEMS);
        assertThat(generated.strategy().topics()).hasSize(PeriodPlanDraftGenerator.MAX_ITEMS + 5);
    }

    // ===== 판단을 받았을 때 =====

    @Test
    @DisplayName("판단이 있으면 v0 규칙 대신 그 취급을 따른다")
    void judgmentOverridesTheV0Rules() {
        PlanningContext context = context(
                course(36L, "자료구조", DATA_STRUCTURES_CLASS,
                        topic(216L, "이미 아는 내용", TopicProgressStatus.NOT_STARTED, null),
                        topic(217L, "훑을 내용", TopicProgressStatus.NOT_STARTED, null),
                        topic(218L, "제대로 볼 내용", TopicProgressStatus.NOT_STARTED, null)));

        Generated generated = generator.generate(spec(), context, judgment(
                new PlanStrategy.TopicTreatment(216L, Treatment.SKIP, 1, "이미 안다고 하셨어요", List.of()),
                new PlanStrategy.TopicTreatment(217L, Treatment.SKIM, 2, "겹치는 내용이에요", List.of()),
                new PlanStrategy.TopicTreatment(218L, Treatment.FULL, 3, "다음 수업에 필요해요", List.of())));

        assertThat(generated.items()).extracting(ProposalItem::title)
                .as("v0 규칙이라면 셋 다 FULL로 만들었을 것이다")
                .containsExactly("자료구조 · 훑을 내용", "자료구조 · 제대로 볼 내용");
    }

    @Test
    @DisplayName("훑기로 정해진 항목은 짧게 잡는다 — 아니면 판단이 총량에 반영되지 않는다")
    void skimBlocksAreShorter() {
        PlanningContext context = context(
                course(36L, "자료구조", DATA_STRUCTURES_CLASS,
                        topic(217L, "훑을 내용", TopicProgressStatus.NOT_STARTED, null)));

        Generated generated = generator.generate(spec(), context, judgment(
                new PlanStrategy.TopicTreatment(217L, Treatment.SKIM, 1, "겹치는 내용", List.of())));

        ProposalItem item = generated.items().get(0);
        assertThat(item.expectedMinutes()).isEqualTo(30);
        assertThat(item.description()).contains("훑어보기").contains("이미 아는 부분을 확인함");
    }

    @Test
    @DisplayName("저장되는 전략은 판단이 낸 것 그대로다 — v0가 자기 판단을 덧씌우지 않는다")
    void theJudgmentIsTheStrategy() {
        PlanningContext context = context(
                course(36L, "자료구조", DATA_STRUCTURES_CLASS,
                        topic(217L, "내용", TopicProgressStatus.NOT_STARTED, null)));
        PlanStrategy given = judgment(
                new PlanStrategy.TopicTreatment(217L, Treatment.FULL, 1, "다음 수업에 필요해요", List.of()));

        assertThat(generator.generate(spec(), context, given).strategy()).isSameAs(given);
    }

    @Test
    @DisplayName("과목 안의 순서도 판단의 rank를 따른다 — 선수지식 판단이 순서로 드러나야 뜻이 생긴다")
    void judgmentDecidesTopicOrderWithinACourse() {
        PlanningContext context = context(
                course(36L, "자료구조", DATA_STRUCTURES_CLASS,
                        topic(216L, "1주차 내용", TopicProgressStatus.NOT_STARTED, null),
                        topic(217L, "2주차 내용", TopicProgressStatus.NOT_STARTED, null),
                        topic(218L, "3주차 내용", TopicProgressStatus.NOT_STARTED, null)));

        Generated generated = generator.generate(spec(), context, judgment(
                new PlanStrategy.TopicTreatment(218L, Treatment.FULL, 1, "다음 수업의 전제", List.of()),
                new PlanStrategy.TopicTreatment(216L, Treatment.FULL, 2, "그 다음", List.of()),
                new PlanStrategy.TopicTreatment(217L, Treatment.FULL, 3, "마지막", List.of())));

        assertThat(generated.items()).extracting(ProposalItem::title)
                .as("주차 순이 아니라 판단이 매긴 순서다")
                .containsExactly("자료구조 · 3주차 내용", "자료구조 · 1주차 내용", "자료구조 · 2주차 내용");
    }

    @Test
    @DisplayName("판단이 정한 과목 순서를 따른다 — 언급되지 않은 과목은 뒤에 그대로 남는다")
    void judgmentDecidesCourseOrder() {
        PlanningContext context = context(
                course(36L, "자료구조", DATA_STRUCTURES_CLASS,
                        topic(217L, "자료구조 내용", TopicProgressStatus.NOT_STARTED, null)),
                course(32L, "웹서버프로그래밍", WEB_SERVER_CLASS,
                        topic(500L, "웹서버 내용", TopicProgressStatus.NOT_STARTED, null)),
                course(90L, "언급 안 된 과목", null,
                        topic(600L, "다른 내용", TopicProgressStatus.NOT_STARTED, null)));

        PlanStrategy given = new PlanStrategy("목표", "요약", StrategySource.NEW, null, List.of(),
                List.of(new PlanStrategy.CourseStrategy(32L, 1, "먼저", "선수지식"),
                        new PlanStrategy.CourseStrategy(36L, 2, "다음", "수업이 늦다")),
                List.of(), List.of());

        assertThat(generator.generate(spec(), context, given).items()).extracting(ProposalItem::title)
                .containsExactly("웹서버프로그래밍 · 웹서버 내용", "자료구조 · 자료구조 내용",
                        "언급 안 된 과목 · 다른 내용");
    }

    @Test
    @DisplayName("만들 블록이 없으면 빈 초안이지만 실패는 아니다")
    void noBlocksIsNotAFailure() {
        Generated generated = generator.generate(spec(), context(
                course(36L, "자료구조", DATA_STRUCTURES_CLASS,
                        topic(216L, "자료구조 개요", TopicProgressStatus.LEARNED, null))));

        assertThat(generated.items()).isEmpty();
        assertThat(generated.noAvailableTime()).isTrue();
        assertThat(generated.strategy().topics()).hasSize(1);
    }

    // ===== fixture =====

    private static PlanStrategy.TopicTreatment treatment(Generated generated, Long topicId) {
        return generated.strategy().topics().stream()
                .filter(t -> t.topicId().equals(topicId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("전략에 topicId=" + topicId + " 판단이 없다"));
    }

    private static PlanStrategy judgment(PlanStrategy.TopicTreatment... topics) {
        return new PlanStrategy("다음 수업 따라가기", "필요한 것만", StrategySource.NEW, null, List.of(),
                List.of(new PlanStrategy.CourseStrategy(36L, 1, "화요일 전까지", "가장 빠른 수업")),
                List.of(topics), List.of());
    }

    private static Spec spec() {
        return new Spec(USER_ID, START, END, PlanIntensity.NORMAL, null, null, List.of());
    }

    private static PlanningContext context(CourseContext... courses) {
        // 하루 4시간씩 일곱 날. 예산 계산만 하고 배치는 하지 않으므로 대략적인 값이면 된다.
        List<AvailabilityWindow> windows = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            LocalDate date = START.plusDays(i);
            windows.add(new AvailabilityWindow(date.atTime(18, 0), date.atTime(22, 0),
                    AvailabilitySource.DEFAULT_INFERENCE, AvailabilityConfidence.LOW, "기본 시간대"));
        }
        AvailabilityEstimateResult availability = new AvailabilityEstimateResult(windows, List.of());
        return new PlanningContext(USER_ID, NOW, START, END, PlanIntensity.NORMAL,
                List.of(courses),
                AvailabilityDaySummary.format(START, END, availability),
                availability, List.of(), null, null);
    }

    private static CourseContext course(Long courseId, String title, LocalDateTime nextClassAt,
                                        TopicContext... topics) {
        return new CourseContext(courseId, title, null, nextClassAt,
                nextClassAt != null ? 85L : null, 2, List.of(topics));
    }

    private static TopicContext topic(Long topicId, String title, TopicProgressStatus status, TopicUserMark mark) {
        return topic(topicId, title, status, mark, null);
    }

    private static TopicContext topic(Long topicId, String title, TopicProgressStatus status,
                                      TopicUserMark mark, String locator) {
        return new TopicContext(topicId, title, locator, TopicLocators.weekOf(locator), status, null, mark, 0);
    }
}
