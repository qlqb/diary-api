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
        when(contexts.autoSave(eq(USER), eq(10L), anyString(), anyList(), anyInt())).thenReturn(List.of(
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
                        "blocker", List.of("첫 코드를 못 시작해", "오류가 나면 막혀", "첫 코드를 못 시작해"), true),
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
}
