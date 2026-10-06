package com.jungwoo.project.memo.ai.consult;

import com.jungwoo.project.memo.ai.AiMessageMapper;
import com.jungwoo.project.memo.ai.UserContextService;
import com.jungwoo.project.memo.ai.brief.PlanBriefItem;
import com.jungwoo.project.memo.ai.brief.PlanBriefService;
import com.jungwoo.project.memo.ai.domain.AiMessage;
import com.jungwoo.project.memo.ai.domain.ContextEvidenceType;
import com.jungwoo.project.memo.ai.dto.UserContextResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * handoff §14.2 — 답변이 무엇을 알게 했고 무엇을 바꿨는지 추적할 수 있다. 선택 답과 자유 답은 같은 경로다.
 */
class ConsultTurnServiceTest {

    private static final long USER = 7L;
    private static final long CONVERSATION = 3L;

    private final UserContextService contexts = mock(UserContextService.class);
    private final PlanBriefService briefs = mock(PlanBriefService.class);
    private final AiMessageMapper messages = mock(AiMessageMapper.class);
    private final ConsultTurnService service = new ConsultTurnService(contexts, briefs, messages);

    @Test
    void 질문_카드와_이번_턴에_이해한_것과_바뀐_방향을_만들어_메시지에_붙여_저장한다() {
        when(contexts.autoSave(eq(USER), eq(10L), anyString(), anyList(), anyInt(), any(), any(), any())).thenReturn(List.of(
                UserContextResponse.builder().contextId(91L).content("실습을 따라 했지만 혼자서는 시작하지 못한다")
                        .evidenceType(ContextEvidenceType.SELF_REPORT).courseTitle("파이썬 기초").build()));
        PlanBriefItem fromThisTurn = new PlanBriefItem(4, "TIME_BUDGET", "오늘 한 시간만", PlanBriefItem.SPEAKER_USER, true,
                false, false, PlanBriefItem.SCOPE_THIS_DRAFT, 10L, 10L, null, null, null, null, 1, List.of(),
                LocalDateTime.now());
        PlanBriefItem older = new PlanBriefItem(2, "GOAL", "전체 훑기", PlanBriefItem.SPEAKER_USER, true, false, false,
                PlanBriefItem.SCOPE_THIS_DRAFT, 5L, 5L, null, null, null, null, 1, List.of(), LocalDateTime.now());
        when(briefs.load(USER, CONVERSATION)).thenReturn(new PlanBriefService.View(1L, CONVERSATION, 3,
                List.of(older, fromThisTurn), null));
        ConsultOut out = new ConsultOut(
                new ConsultOut.QuestionOut("첫 코드를 못 시작하는 쪽이야, 오류가 나면 막히는 쪽이야?", "연습 형태가 달라져",
                        "blocker", List.of(ConsultOut.ChoiceOut.of("첫 코드를 못 시작해"), ConsultOut.ChoiceOut.of("오류가 나면 막혀"),
                                ConsultOut.ChoiceOut.of("첫 코드를 못 시작해")), true),
                new ConsultOut.DirectionOut("전체 문법 복습", "예제 일부를 가리고 직접 시작해 보는 연습", "혼자 시작이 어렵다고 함", true),
                List.of(new ConsultOut.MemoryOut("실습을 따라 했지만 혼자서는 시작하지 못한다", "SELF_REPORT", 1L, null, null,
                        "혼자 못 해")), null);

        ConsultView view = service.finish(USER, CONVERSATION, 10L, 11L, "따라 하긴 했는데 혼자 못 해", out);

        assertThat(view.question().id()).isEqualTo("q-11");
        assertThat(view.question().topic()).isEqualTo("BLOCKER");
        assertThat(view.question().multiSelect()).isTrue();
        // 중복 선택지는 하나로.
        assertThat(view.question().choices()).extracting(ConsultView.Choice::id, ConsultView.Choice::label)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("c1", "첫 코드를 못 시작해"),
                        org.assertj.core.groups.Tuple.tuple("c2", "오류가 나면 막혀"));
        assertThat(view.understanding()).extracting(ConsultView.Understanding::source, ConsultView.Understanding::id)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("MEMORY", "91"),
                        org.assertj.core.groups.Tuple.tuple("BRIEF", "4")); // 예전 턴의 합의(2번)는 이번 턴의 이해가 아니다.
        assertThat(view.understanding().get(0).scopeLabel()).isEqualTo("파이썬 기초");
        assertThat(view.direction().affectsDraft()).isTrue();
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(messages).updateConsultJson(eq(11L), eq(USER), json.capture());
        // 저장한 것을 다시 읽으면 같은 카드다(새로고침 복구).
        assertThat(service.read(json.getValue()).question().text()).isEqualTo(view.question().text());
    }

    @Test
    void 줄_것이_없으면_아무것도_저장하지_않고_부가_단계가_실패해도_턴은_실패하지_않는다() {
        when(briefs.load(USER, CONVERSATION)).thenReturn(PlanBriefService.View.empty(CONVERSATION));
        assertThat(service.finish(USER, CONVERSATION, 10L, 11L, "고마워", null)).isNull();
        verify(messages, never()).updateConsultJson(any(), any(), any());

        when(briefs.load(USER, CONVERSATION)).thenThrow(new IllegalStateException("db"));
        assertThat(service.finish(USER, CONVERSATION, 10L, 11L, "고마워", null)).isNull();
    }

    @Test
    void 빠른_답은_저장된_질문의_선택지에서만_사용자_발화를_만든다() throws Exception {
        ConsultView stored = new ConsultView(new ConsultView.Question("q-11", "어느 쪽이야?", null, "BLOCKER",
                List.of(new ConsultView.Choice("c1", "첫 코드를 못 시작해"), new ConsultView.Choice("c2", "오류가 나면 막혀")), true),
                List.of(), null, null);
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(stored);
        when(messages.findByIdAndUserId(11L, USER)).thenReturn(AiMessage.builder().messageId(11L)
                .conversationId(CONVERSATION).userId(USER).consultJson(json).build());
        when(messages.findByIdAndUserId(12L, USER)).thenReturn(AiMessage.builder().messageId(12L)
                .conversationId(999L).userId(USER).consultJson(json).build());

        assertThat(service.composeAnswer(USER, CONVERSATION, "q-11", List.of("c2", "c1"), false))
                .isEqualTo("첫 코드를 못 시작해, 오류가 나면 막혀");
        assertThat(service.composeAnswer(USER, CONVERSATION, "q-11", List.of("c9"), false)).isNull();
        // 다른 대화의 질문, 남의 메시지(조회 안 됨), 형식이 다른 id는 받지 않는다.
        assertThat(service.composeAnswer(USER, CONVERSATION, "q-12", List.of("c1"), false)).isNull();
        assertThat(service.composeAnswer(USER, CONVERSATION, "q-77", List.of("c1"), false)).isNull();
        assertThat(service.composeAnswer(USER, CONVERSATION, "abc", List.of("c1"), false)).isNull();
        assertThat(service.composeAnswer(USER, CONVERSATION, "q-11", List.of(), true)).isEqualTo("이 질문은 건너뛸게요.");
    }

    @Test
    void 답하는_방법을_안내하는_선택지는_입력_안내가_되고_답으로_받지_않는다() throws Exception {
        when(briefs.load(USER, CONVERSATION)).thenReturn(null);
        // 2026-10-03 재현: 모델이 "가장 빠른 시험부터 말하기"를 답 선택지로 냈고, 누르면 그 글이 답으로 가서 같은 질문이 반복됐다.
        String modelJson = "{\"question\":{\"text\":\"과목별 시험 날짜가 어떻게 되나요?\",\"topic\":\"OTHER\","
                + "\"choices\":[\"가장 빠른 시험부터 말하기\",\"과목명과 날짜 말하기\",{\"label\":\"시험 날짜 적기\",\"kind\":\"INPUT\"},"
                + "{\"label\":\"강의계획서에서 찾아봐\",\"kind\":\"LOOKUP\"},\"아직 몰라\"],\"multiSelect\":true}}";
        ConsultOut out = new com.fasterxml.jackson.databind.ObjectMapper().readValue(modelJson, ConsultOut.class);

        ConsultView view = service.finish(USER, CONVERSATION, 20L, 21L, "무슨 과목부터 할까", out);

        assertThat(view.question().choices()).extracting(ConsultView.Choice::label, ConsultView.Choice::kind).containsExactly(
                org.assertj.core.groups.Tuple.tuple("가장 빠른 시험부터 말하기", "INPUT"),
                org.assertj.core.groups.Tuple.tuple("과목명과 날짜 말하기", "INPUT"),
                org.assertj.core.groups.Tuple.tuple("시험 날짜 적기", "INPUT"),
                org.assertj.core.groups.Tuple.tuple("강의계획서에서 찾아봐", "LOOKUP"),
                org.assertj.core.groups.Tuple.tuple("아직 몰라", null));
        // 답 선택지가 하나뿐이면 여러 개 고르기가 아니다.
        assertThat(view.question().multiSelect()).isFalse();

        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(view);
        when(messages.findByIdAndUserId(21L, USER)).thenReturn(AiMessage.builder().messageId(21L)
                .conversationId(CONVERSATION).userId(USER).consultJson(json).build());
        assertThat(service.compose(USER, CONVERSATION, "q-21", List.of("c1"), false, false)).isNull();
        assertThat(service.compose(USER, CONVERSATION, "q-21", List.of("c4"), false, false))
                .isEqualTo(new ConsultTurnService.Composed("강의계획서에서 찾아봐", true));
        assertThat(service.compose(USER, CONVERSATION, "q-21", List.of("c5"), false, false))
                .isEqualTo(new ConsultTurnService.Composed("아직 몰라", false));
        assertThat(service.compose(USER, CONVERSATION, "q-21", List.of(), false, true))
                .isEqualTo(new ConsultTurnService.Composed(ConsultTurnService.LOOKUP_MESSAGE, true));
    }

    @Test
    void 확인한_자료는_질문이_없어도_저장되고_예전_선택지_모양도_읽힌다() throws Exception {
        when(briefs.load(USER, CONVERSATION)).thenReturn(null);
        ConsultView.Evidence evidence = new ConsultView.Evidence("원문 1곳 확인", List.of(new ConsultView.Source("E1",
                "MATERIAL_TEXT", "자료구조_강의계획서.pdf", "자료구조", null, 5L, "p.5", 5, "PARTIAL", true, 1)), List.of(), 1);

        ConsultView view = service.finish(USER, CONVERSATION, 30L, 31L, "시험 언제야", null, evidence);

        assertThat(view.evidence()).isEqualTo(evidence);
        verify(messages).updateConsultJson(eq(31L), eq(USER), contains("\"evidence\""));
        ConsultView legacy = service.read("{\"question\":{\"id\":\"q-1\",\"text\":\"어느 쪽?\",\"choices\":[{\"id\":\"c1\","
                + "\"label\":\"첫 코드\"}],\"multiSelect\":false}}");
        assertThat(legacy.question().choices().get(0).kind()).isNull();
        assertThat(legacy.evidence()).isNull();
    }
}
