package com.jungwoo.project.memo.ai.brief;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 계획 합의의 규칙: 발화자·동의 상태를 잃지 않는다. 사용자가 말한 것은 즉시 유효, AI 제안은 수락 전까지 후보,
 * 없는 번호·발화자 없는 ADD는 추측을 확정으로 올리지 않는 쪽으로 처리한다.
 */
class PlanBriefServiceTest {

    private static final long USER = 7L;
    private static final long CONV = 12L;

    private PlanBriefMapper mapper;
    private PlanBriefService service;

    @BeforeEach
    void setUp() {
        mapper = mock(PlanBriefMapper.class);
        service = new PlanBriefService(mapper, Clock.fixed(Instant.parse("2026-09-17T03:00:00Z"), ZoneId.of("Asia/Seoul")));
    }

    @Test
    void 사용자_발화는_즉시_유효하고_AI_제안은_수락_전까지_후보다() {
        when(mapper.findByConversationIdAndUserId(CONV, USER)).thenReturn(null);

        PlanBriefService.View view = service.applyTurn(USER, CONV, 200L, 201L, List.of(
                new PlanBriefOp("ADD", null, "PRIORITY", "자료구조 복구 우선", "USER", "THIS_DRAFT", null, 1L, null),
                new PlanBriefOp("ADD", null, "TIME_CONSTRAINT", "영어는 하루 15분", "ASSISTANT", "PERIOD", null, null, null),
                new PlanBriefOp("ADD", null, "EXCLUDE", "금요일 밤은 비우기", null, null, null, null, null)));

        assertThat(view.items()).hasSize(3);
        PlanBriefItem user = view.items().get(0);
        assertThat(user.speaker()).isEqualTo("USER");
        assertThat(user.accepted()).isTrue();
        assertThat(user.sourceMessageId()).isEqualTo(200L);
        PlanBriefItem ai = view.items().get(1);
        assertThat(ai.speaker()).isEqualTo("ASSISTANT");
        assertThat(ai.accepted()).isFalse();
        assertThat(ai.sourceMessageId()).isEqualTo(201L);
        assertThat(ai.scope()).isEqualTo("PERIOD");
        // 발화자를 모르면 AI 제안(후보)으로 낮춰 적는다 — 추측을 확정으로 올리지 않는다.
        assertThat(view.items().get(2).speaker()).isEqualTo("ASSISTANT");
        assertThat(view.effective()).extracting(PlanBriefItem::text).containsExactly("자료구조 복구 우선");
        assertThat(view.pendingProposals()).hasSize(2);
        ArgumentCaptor<PlanBrief> saved = ArgumentCaptor.forClass(PlanBrief.class);
        verify(mapper).insert(saved.capture());
        assertThat(saved.getValue().getItems()).contains("\"speaker\":\"USER\"").contains("\"accepted\":true");
    }

    @Test
    void 좋아_그대로는_그_제안을_수락_상태로_바꾸고_출처_메시지를_남긴다() {
        PlanBrief stored = brief(1, service.serialize(List.of(
                new PlanBriefItem(1, "PRIORITY", "자료구조 복구 우선", "ASSISTANT", false, false, false, "THIS_DRAFT", 201L, null,
                        null, null, null, null, 1, List.of(), null),
                new PlanBriefItem(2, "TIME_CONSTRAINT", "영어 15분", "ASSISTANT", false, false, false, "THIS_DRAFT", 201L, null,
                        null, null, null, null, 1, List.of(), null))));
        when(mapper.findByConversationIdAndUserId(CONV, USER)).thenReturn(stored);
        when(mapper.updateItems(eq(1L), eq(USER), eq(1), anyString(), anyString())).thenReturn(1);

        PlanBriefService.View view = service.applyTurn(USER, CONV, 210L, 211L, List.of(
                new PlanBriefOp("ACCEPT", 1, null, null, null, null, null, null, null),
                new PlanBriefOp("REJECT", 2, null, null, null, null, null, null, null),
                new PlanBriefOp("ACCEPT", 99, null, null, null, null, null, null, null)));

        assertThat(view.version()).isEqualTo(2);
        assertThat(view.items().get(0).accepted()).isTrue();
        assertThat(view.items().get(0).acceptedByMessageId()).isEqualTo(210L);
        assertThat(view.items().get(1).rejected()).isTrue();
        assertThat(view.effective()).extracting(PlanBriefItem::text).containsExactly("자료구조 복구 우선");
        assertThat(PlanBriefService.renderForConsultation(view))
                .contains("#1 [AI 제안 · 사용자 수락] 우선순위: 자료구조 복구 우선")
                .contains("#2 [AI 제안 · 거절됨]");
    }

    @Test
    void 사용자가_고쳐_말하면_최신_수정판이_우선하고_이전_문장은_이력에_남는다() {
        PlanBrief stored = brief(3, service.serialize(List.of(
                new PlanBriefItem(1, "TIME_CONSTRAINT", "영어는 하루 15분", "ASSISTANT", true, false, false, "THIS_DRAFT", 201L,
                        205L, null, null, null, null, 1, List.of(), null))));
        when(mapper.findByConversationIdAndUserId(CONV, USER)).thenReturn(stored);
        when(mapper.updateItems(eq(1L), eq(USER), eq(3), anyString(), anyString())).thenReturn(1);

        PlanBriefService.View view = service.applyTurn(USER, CONV, 220L, 221L, List.of(
                new PlanBriefOp("UPDATE", 1, null, "영어는 하루 10분만", null, "PERIOD", null, null, null)));

        PlanBriefItem item = view.items().get(0);
        assertThat(item.text()).isEqualTo("영어는 하루 10분만");
        assertThat(item.speaker()).isEqualTo("USER");
        assertThat(item.revision()).isEqualTo(2);
        assertThat(item.history()).containsExactly("영어는 하루 15분");
        assertThat(item.scope()).isEqualTo("PERIOD");
        assertThat(PlanBriefService.agreedLines(view).get(0)).contains("최신 수정판").contains("이번 기간");
    }

    @Test
    void 변경이_하나도_적용되지_않으면_저장하지_않는다() {
        when(mapper.findByConversationIdAndUserId(CONV, USER)).thenReturn(null);

        PlanBriefService.View view = service.applyTurn(USER, CONV, 1L, 2L, List.of(
                new PlanBriefOp("ACCEPT", 5, null, null, null, null, null, null, null),
                new PlanBriefOp("ADD", null, "GOAL", "   ", "USER", null, null, null, null)));

        assertThat(view.isEmpty()).isTrue();
        verify(mapper, org.mockito.Mockito.never()).insert(any());
    }

    @Test
    void 갱신_경합이면_다시_읽어_한_번_더_시도한다() {
        PlanBrief stored = brief(1, "[]");
        when(mapper.findByConversationIdAndUserId(CONV, USER)).thenReturn(stored);
        when(mapper.updateItems(eq(1L), eq(USER), anyInt(), anyString(), anyString())).thenReturn(0, 1);

        service.applyTurn(USER, CONV, 1L, 2L, List.of(
                new PlanBriefOp("ADD", null, "GOAL", "다음 수업 따라잡기", "USER", null, null, null, null)));

        verify(mapper, org.mockito.Mockito.times(2)).updateItems(eq(1L), eq(USER), anyInt(), anyString(), anyString());
    }

    @Test
    void 다른_대화에서는_확인된_어려움과_기간_합의만_이어_온다() {
        PlanBrief other = brief(1, service.serialize(List.of(
                new PlanBriefItem(1, "DIFFICULTY", "반복문에서 막힘", "USER", true, false, false, "THIS_DRAFT", 1L, 1L, null,
                        44L, 1L, null, 1, List.of(), null),
                new PlanBriefItem(2, "PRIORITY", "이번 초안만 자료구조 우선", "USER", true, false, false, "THIS_DRAFT", 1L, 1L,
                        null, null, null, null, 1, List.of(), null),
                new PlanBriefItem(3, "TIME_CONSTRAINT", "금요일 밤 비움", "USER", true, false, false, "PERIOD", 1L, 1L, null,
                        null, null, null, 1, List.of(), null))));
        other.setConversationId(99L);
        other.setUpdatedAt(java.time.LocalDateTime.of(2026, 9, 16, 10, 0));
        when(mapper.findRecentByUserId(eq(USER), anyInt())).thenReturn(List.of(other));

        List<PlanBriefItem> carried = service.carriedOver(USER, CONV, 5);

        assertThat(carried).extracting(PlanBriefItem::text).containsExactly("반복문에서 막힘", "금요일 밤 비움");
    }

    private static PlanBrief brief(int version, String items) {
        return PlanBrief.builder().briefId(1L).userId(USER).conversationId(CONV).version(version).status("OPEN")
                .items(items).build();
    }
}
