package com.jungwoo.project.memo.plan.selection;

import com.jungwoo.project.memo.ai.brief.PlanBriefItem;
import com.jungwoo.project.memo.ai.brief.PlanBriefService;
import com.jungwoo.project.memo.ai.domain.AiMessage;
import com.jungwoo.project.memo.ai.domain.MessageRole;
import com.jungwoo.project.memo.ai.domain.ProposalOperation;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.ServiceUnavailableException;
import com.jungwoo.project.memo.execution.domain.ExecutionItem;
import com.jungwoo.project.memo.execution.domain.ExecutionStatus;
import com.jungwoo.project.memo.execution.domain.PlacementType;
import com.jungwoo.project.memo.plan.GenerationBudget;
import com.jungwoo.project.memo.plan.PeriodPlanDraftGenerator;
import com.jungwoo.project.memo.plan.PeriodPlanDraftGenerator.Generated;
import com.jungwoo.project.memo.plan.domain.PlanIntensity;
import com.jungwoo.project.memo.plan.provenance.ProvenanceSourceType;
import com.jungwoo.project.memo.routine.domain.RoutineOccurrence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.jungwoo.project.memo.plan.selection.PlanSelectionFixture.START;
import static com.jungwoo.project.memo.plan.selection.PlanSelectionFixture.END;
import static com.jungwoo.project.memo.plan.selection.PlanSelectionFixture.USER;
import static com.jungwoo.project.memo.plan.selection.PlanSelectionFixture.handleOf;
import static com.jungwoo.project.memo.plan.selection.PlanSelectionFixture.planFull;
import static com.jungwoo.project.memo.plan.selection.PlanSelectionFixture.refOf;
import static com.jungwoo.project.memo.plan.selection.PlanSelectionFixture.selection;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 상담·계획·실행을 잇는 생성 경로(2026-09-16 지시서 §4·§5·§7·§13). 모델 응답을 고정하고 <b>실제 생성기</b>로 돌린다.
 *
 * <p>여기서 증명하는 것: 합의·상담 기록·기존 일정·다음 수업이 계획 호출에 인용 번호와 함께 실린다. 추가 읽기와 복구는
 * 상한 안에서 한 번씩만 일어나고, 상한에 닿으면 읽지 않았다고 남긴다. 근거가 같으면 선택 호출을 생략하고, 달라지면
 * 무엇이 달라졌는지 서버가 적는다. 기존 항목 결정은 조정으로, 수업 참조는 마감으로 바뀐다.
 */
class PlanConnectionFlowTest {

    private static final long CONV = 12L;
    private static final long REQUEST_MESSAGE = 300L;

    private PlanSelectionFixture f;

    @BeforeEach
    void setUp() {
        f = new PlanSelectionFixture();
        f.course(1, "자료구조");
        f.material(10, "ds.pdf", 1L);
        f.topic(1, 1, "재귀");
        f.section(101, 10, "재귀 개념", "[\"CONCEPT\"]", 1, "원문-101 고유문장");
        f.section(102, 10, "재귀 연습 문제", "[\"EXERCISE\"]", 2, "원문-102 고유문장");
        f.section(103, 10, "재귀 심화", "[\"CONCEPT\"]", 3, "원문-103 고유문장");
        f.link(1, 1, 101);
        f.link(1, 1, 102);
        f.link(1, 1, 103);
        f.selectionAnswer = prompt -> selection(List.of(handleOf(prompt, "재귀 개념")), List.of(), List.of());
    }

    private PeriodPlanDraftGenerator.Origin conversationOrigin() {
        return new PeriodPlanDraftGenerator.Origin(CONV, REQUEST_MESSAGE, "req-1", null);
    }

    private static PlanBriefItem briefItem(int id, String kind, String text, String speaker, boolean accepted, String scope) {
        return new PlanBriefItem(id, kind, text, speaker, accepted, false, false, scope, 100L, accepted ? 100L : null,
                null, null, 1L, null, 1, List.of(), LocalDateTime.of(2026, 9, 13, 10, 0));
    }

    private static AiMessage message(long id, MessageRole role, String content) {
        return AiMessage.builder().messageId(id).conversationId(CONV).userId(USER).role(role).content(content)
                .createdAt(LocalDateTime.of(2026, 9, 13, 9, 0).plusMinutes(id)).build();
    }

    @Test
    void T24_합의와_상담_기록은_발화자와_동의_상태를_붙여_계획_호출에_실리고_요청_메시지는_빼며_인용_번호가_남는다() {
        when(f.briefService.load(USER, CONV)).thenReturn(new PlanBriefService.View(5L, CONV, 3, List.of(
                briefItem(1, "PRIORITY", "자료구조 복구 우선", "USER", true, "THIS_DRAFT"),
                briefItem(2, "TIME_CONSTRAINT", "영어는 하루 15분", "ASSISTANT", false, "PERIOD")), null));
        when(f.messageMapper.findByConversationIdAndUserId(CONV, USER)).thenReturn(List.of(
                message(100, MessageRole.USER, "이번 주는 자료구조 복구가 먼저야"),
                message(101, MessageRole.ASSISTANT, "영어는 하루 15분만 어때요?"),
                message(REQUEST_MESSAGE, MessageRole.USER, "계획 만들어줘")));

        Generated generated = f.generate(f.spec(null, conversationOrigin()), PeriodPlanDraftGenerator.Options.none());

        String plan = f.planPrompt();
        assertThat(plan).contains("[상담에서 합의한 것]")
                .contains("우선순위: 자료구조 복구 우선 (사용자가 말함, 이번 초안) [s")
                .contains("아직 사용자가 답하지 않은 AI 제안")
                .contains("시간 제약(상한): 영어는 하루 15분 (AI 제안, 답 없음) [s");
        assertThat(plan).contains("[상담 기록]")
                .contains("- 사용자: 이번 주는 자료구조 복구가 먼저야 [s")
                .contains("- AI: 영어는 하루 15분만 어때요? [s")
                .doesNotContain("계획 만들어줘");
        // 합의 줄이 상담 기록보다 앞에 온다 — 기록은 배경, 합의가 판단 기준이다.
        assertThat(plan.indexOf("[상담에서 합의한 것]")).isLessThan(plan.indexOf("[상담 기록]"));
        assertThat(generated.provenance().providedSources())
                .anyMatch(s -> s.sourceType() == ProvenanceSourceType.PLAN_BRIEF && s.sourceId() == 5L)
                .anyMatch(s -> s.sourceType() == ProvenanceSourceType.CONVERSATION_MESSAGE && s.sourceId() == 100L)
                .noneMatch(s -> s.sourceType() == ProvenanceSourceType.CONVERSATION_MESSAGE && s.sourceId() == REQUEST_MESSAGE);
        assertThat(generated.extras().briefId()).isEqualTo(5L);
        assertThat(generated.extras().briefVersion()).isEqualTo(3);
        String system = f.planCalls().get(0)[0];
        assertThat(system).contains("[상담에서 합의한 것]").contains("keptDecisions");
    }

    @Test
    void T24b_지난_기간의_합의와_다른_초안_흐름의_합의는_계획_호출에_실리지_않고_일부_겹치면_원래_범위가_붙는다() {
        PlanBriefItem expired = new PlanBriefItem(1, "EXCLUDE", "지난주는 영어 제외", "USER", true, false, false, "PERIOD", 100L,
                100L, null, null, null, null, 1, List.of(), LocalDateTime.of(2026, 9, 6, 10, 0),
                LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 13), LocalDate.of(2026, 9, 6), null);
        PlanBriefItem partial = new PlanBriefItem(2, "TIME_CONSTRAINT", "금요일 밤은 비움", "USER", true, false, false, "PERIOD",
                100L, 100L, null, null, null, null, 1, List.of(), LocalDateTime.of(2026, 9, 13, 10, 0),
                LocalDate.of(2026, 9, 17), LocalDate.of(2026, 9, 25), LocalDate.of(2026, 9, 13), null);
        PlanBriefItem otherFlow = new PlanBriefItem(3, "PRIORITY", "옛 초안만 자료구조 우선", "USER", true, false, false,
                "THIS_DRAFT", 100L, 100L, null, null, null, null, 1, List.of(), LocalDateTime.of(2026, 9, 13, 10, 0),
                null, null, LocalDate.of(2026, 9, 13), 55L);
        when(f.briefService.load(USER, CONV)).thenReturn(new PlanBriefService.View(5L, CONV, 4, List.of(
                expired, partial, otherFlow, briefItem(4, "PRIORITY", "아직 묶이지 않은 합의", "USER", true, "THIS_DRAFT")), null));

        f.generate(f.spec(null, conversationOrigin()), PeriodPlanDraftGenerator.Options.none());

        String plan = f.planPrompt();
        assertThat(plan).contains("[상담에서 합의한 것]")
                .contains("시간 제약(상한): 금요일 밤은 비움 (사용자가 말함, 이번 기간 9/17~9/25 — 원래 9/17~9/25에 적용)")
                .contains("우선순위: 아직 묶이지 않은 합의 (사용자가 말함, 이번 초안)")
                .doesNotContain("지난주는 영어 제외")
                .doesNotContain("옛 초안만 자료구조 우선");
    }

    @Test
    void T24c_계획_호출_규칙은_일일_반복과_상한을_구분하고_날짜가_다른_반복을_중복으로_보지_않는다() {
        f.generate(null);

        String system = f.planCalls().get(0)[0];
        assertThat(system).contains("일일 반복: 합의가 \"매일 15분\"처럼 실행 빈도(반복 빈도)이면 기간의 날짜마다 항목 하나를 만들고")
                .contains("\"하루 15분까지\"처럼 상한만")
                .contains("날짜가 다른 일일 실행은 중복이 아니다");
        assertThat(PlanBriefService.kindLabel("FREQUENCY")).isEqualTo("반복 빈도(매일 등)");
        assertThat(PlanBriefService.kindLabel("TIME_CONSTRAINT")).isEqualTo("시간 제약(상한)");
    }

    @Test
    void T25_최종_판단이_더_읽자고_하면_상한_안에서_한_번만_더_읽고_두_번째_요청은_읽지_않았다고_남긴다() {
        f.planAnswer = prompt -> {
            if (!prompt.contains("원문-102")) {
                return planFull(refOf(prompt, "재귀 개념"), null, null, null, handleOf(prompt, "재귀 연습 문제"));
            }
            // 두 번째 판단도 또 더 읽자고 한다 — 조회 라운드 상한(2)이라 세 번째 호출은 없어야 한다.
            return planFull(refOf(prompt, "재귀 연습 문제"), null, null, null, handleOf(prompt, "재귀 심화"));
        };

        Generated generated = f.generate(null);

        assertThat(f.planCalls()).hasSize(2);
        String second = f.planPrompt();
        assertThat(second).contains("[이번 회차 추가 읽기]").contains("구간 1개를 더 읽었다").contains("문제 원문이 필요")
                .contains("원문-102 고유문장").contains("원문-101 고유문장").doesNotContain("원문-103 고유문장");
        GenerationBudget.Summary budget = generated.extras().budget();
        assertThat(budget.retrievalRounds()).isEqualTo(2);
        assertThat(budget.normalCalls()).isEqualTo(3);
        assertThat(budget.recoveryCalls()).isZero();
        assertThat(budget.calls()).extracting(GenerationBudget.CallRecord::kind)
                .containsExactly(GenerationBudget.Call.SELECTION, GenerationBudget.Call.PLAN,
                        GenerationBudget.Call.PLAN_MORE_EVIDENCE);
        assertThat(generated.itemEvidence().get(0).refIds()).containsExactly(refOf(second, "재귀 연습 문제"));
        assertThat(generated.strategy().unreadNotes()).anyMatch(n -> n.contains("두 번째 판단도 근거를 더 요청했지만"));
        assertThat(generated.materialSelection().sections()).extracting(s -> s.sectionId()).containsExactly(101L, 102L);
    }

    @Test
    void T26_조회_라운드_상한이_1이면_추가_읽기_요청을_거절하고_읽지_못했다고_남긴다() {
        ReflectionTestUtils.setField(f.generator, "maxRetrievalRounds", 1);
        f.planAnswer = prompt -> planFull(refOf(prompt, "재귀 개념"), null, null, null, handleOf(prompt, "재귀 연습 문제"));

        Generated generated = f.generate(null);

        assertThat(f.planCalls()).hasSize(1);
        assertThat(generated.extras().budget().retrievalRounds()).isEqualTo(1);
        assertThat(generated.strategy().unreadNotes())
                .anyMatch(n -> n.contains("구간 1개를 더 읽자고 했지만 이번 회차의 한도") && n.contains("1라운드"));
        assertThat(generated.materialSelection().sections()).extracting(s -> s.sectionId()).containsExactly(101L);
    }

    @Test
    void T27_근거가_같으면_선택_호출을_생략하고_같은_구간을_다시_읽으며_지시가_바뀌면_달라진_점을_적고_다시_고른다() {
        Generated first = f.generate(f.spec("개념 위주", null), PeriodPlanDraftGenerator.Options.none());
        assertThat(f.selectionPrompts()).hasSize(1);
        PlanRequestContext previous = new PlanRequestContext(PlanRequestContext.VERSION, "PLAN_SCREEN", START, END,
                PlanIntensity.NORMAL, null, "개념 위주", List.of(), List.of(), List.of(), List.of(), null, List.of(), null,
                "req-0", null, null, 77L, first.extras().evidence(), null);
        f.calls.clear();

        Generated reused = f.generate(f.spec("개념 위주", null),
                new PeriodPlanDraftGenerator.Options(previous, null));

        assertThat(f.selectionPrompts()).isEmpty();
        assertThat(f.planCalls()).hasSize(1);
        assertThat(f.planPrompt()).contains("원문-101 고유문장").doesNotContain("[이전 초안과 달라진 것]");
        assertThat(reused.extras().selectionReused()).isTrue();
        assertThat(reused.extras().changesFromPrevious()).isEmpty();
        assertThat(reused.materialSelection().mode()).isEqualTo("REUSED");
        assertThat(reused.extras().budget().calls()).extracting(GenerationBudget.CallRecord::kind)
                .containsExactly(GenerationBudget.Call.PLAN);
        assertThat(reused.extras().evidence().fingerprint()).isEqualTo(first.extras().evidence().fingerprint());
        f.calls.clear();

        Generated changed = f.generate(f.spec("문제 풀이 위주로", null),
                new PeriodPlanDraftGenerator.Options(previous, null));

        assertThat(f.selectionPrompts()).hasSize(1);
        assertThat(f.planPrompt()).contains("[이전 초안과 달라진 것]").contains("- 사용자 지시가 달라졌다");
        assertThat(changed.extras().selectionReused()).isFalse();
        assertThat(changed.extras().changesFromPrevious()).containsExactly("사용자 지시가 달라졌다");
        assertThat(changed.strategy().changes()).extracting(c -> c.what()).contains("사용자 지시가 달라졌다");
        assertThat(changed.extras().evidence().fingerprint()).isNotEqualTo(first.extras().evidence().fingerprint());
    }

    @Test
    void T28_읽을_수_없는_응답이면_복구_호출을_한_번만_하고_그래도_안_되면_상한_안에서_실패한다() {
        AtomicInteger n = new AtomicInteger();
        f.planAnswer = prompt -> n.incrementAndGet() == 1 ? "{\"title\": 이건 JSON이 아니다"
                : planFull(refOf(prompt, "재귀 개념"), null, null, null, null);

        Generated generated = f.generate(null);

        assertThat(f.planCalls()).hasSize(2);
        assertThat(f.planCalls().get(1)[1]).contains("[다시 답하기]").startsWith(f.planCalls().get(0)[1]);
        GenerationBudget.Summary budget = generated.extras().budget();
        assertThat(budget.recoveryCalls()).isEqualTo(1);
        assertThat(budget.normalCalls()).isEqualTo(2);
        assertThat(budget.calls()).extracting(GenerationBudget.CallRecord::kind)
                .containsExactly(GenerationBudget.Call.SELECTION, GenerationBudget.Call.PLAN,
                        GenerationBudget.Call.PLAN_RECOVERY);
        assertThat(budget.calls().get(1).success()).isFalse();
        assertThat(budget.calls().get(1).note()).isEqualTo("JSON 파싱 실패");

        f.calls.clear();
        f.planAnswer = prompt -> "{\"title\": 계속 깨진 응답";
        assertThatThrownBy(() -> f.generate(null))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.AI_GENERATION_FAILED);
        // 정상 1 + 복구 1에서 끝난다 — 세 번째 계획 호출은 없다.
        assertThat(f.planCalls()).hasSize(2);
    }

    @Test
    void T29_기존_계획_항목의_결정은_조정이_되고_다음_수업_참조는_측정된_마감으로_바뀐다() {
        when(f.executionItemMapper.findByUserIdAndPlanningRange(eq(USER), any(), any())).thenReturn(List.of(
                ExecutionItem.builder().executionItemId(70L).userId(USER).courseId(1L).planVersionId(500L).title("연결 리스트")
                        .status(ExecutionStatus.PLANNED).placementType(PlacementType.DATE_ONLY)
                        .scheduledDate(LocalDate.of(2026, 9, 15)).expectedMinutes(60).version(2L).build(),
                ExecutionItem.builder().executionItemId(71L).userId(USER).title("치과").status(ExecutionStatus.PLANNED)
                        .placementType(PlacementType.TIME_FIXED).scheduledStartAt(LocalDateTime.of(2026, 9, 16, 10, 0))
                        .build()));
        when(f.occurrenceService.expand(anyLong(), any(), any())).thenReturn(List.of(
                new RoutineOccurrence(9L, 1L, "자료구조 수업", null, LocalDateTime.of(2026, 9, 16, 14, 0),
                        LocalDateTime.of(2026, 9, 16, 15, 15), LocalDate.of(2026, 9, 16), false),
                new RoutineOccurrence(9L, 1L, "자료구조 수업", null, LocalDateTime.of(2026, 9, 18, 14, 0),
                        LocalDateTime.of(2026, 9, 18, 15, 15), LocalDate.of(2026, 9, 18), false)));
        f.planAnswer = prompt -> planFull(refOf(prompt, "재귀 개념"), refOf(prompt, "다음 수업: 9/16"),
                refOf(prompt, "#70 연결 리스트"), "REDUCE", null);

        Generated generated = f.generate(null);

        String plan = f.planPrompt();
        assertThat(plan).contains("다음 수업: 9/16 수 14:00~15:15 자료구조 수업 — 수업 전에 끝내야 하는 항목은 이 줄을 deadlineRefId로 가리킨다 [s")
                .contains("[이 기간에 이미 있는 일정]")
                .contains("- #70 연결 리스트 · 2026-09-15 · 60분 · 상태 PLANNED [s")
                .contains("- 치과 · 2026-09-16T10:00 (고정)");
        assertThat(generated.items()).hasSize(1);
        assertThat(generated.items().get(0).deadlineAt()).isEqualTo(LocalDateTime.of(2026, 9, 16, 14, 0));
        assertThat(generated.items().get(0).deadlineSource()).isEqualTo("CLASS");
        assertThat(generated.items().get(0).topicId()).isEqualTo(1L);
        assertThat(generated.adjustments()).hasSize(1);
        assertThat(generated.adjustments().get(0).executionItemId()).isEqualTo(70L);
        assertThat(generated.adjustments().get(0).operation()).isEqualTo(ProposalOperation.REDUCE);
        assertThat(generated.adjustments().get(0).expectedMinutes()).isEqualTo(20);
        assertThat(generated.strategy().existingDecisions()).extracting(d -> d.executionItemId() + ":" + d.action())
                .containsExactly("70:REDUCE");
        assertThat(generated.strategy().openQuestions()).containsExactly("반복문에서 막힌 이유가 개념인가요, 문제 유형인가요?");
        assertThat(generated.strategy().keptDecisions()).containsExactly("자료구조 복구 우선");
        assertThat(generated.provenance().providedSources())
                .anyMatch(s -> s.sourceType() == ProvenanceSourceType.NEXT_CLASS && s.sourceId() == 9L);
    }
}
