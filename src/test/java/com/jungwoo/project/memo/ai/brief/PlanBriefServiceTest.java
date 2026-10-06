package com.jungwoo.project.memo.ai.brief;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
                // 기간 합의는 날짜가 있고 아직 지나지 않은 것만 이어 온다(날짜 없는 옛 항목은 범위 미확인).
                new PlanBriefItem(3, "TIME_CONSTRAINT", "금요일 밤 비움", "USER", true, false, false, "PERIOD", 1L, 1L, null,
                        null, null, null, 1, List.of(), null, LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 27),
                        LocalDate.of(2026, 9, 16), null))));
        other.setConversationId(99L);
        other.setUpdatedAt(java.time.LocalDateTime.of(2026, 9, 16, 10, 0));
        when(mapper.findRecentByUserId(eq(USER), anyInt())).thenReturn(List.of(other));

        List<PlanBriefItem> carried = service.carriedOver(USER, CONV, 5);

        assertThat(carried).extracting(PlanBriefItem::text).containsExactly("반복문에서 막힘", "금요일 밤 비움");
    }

    // ===== 적용 범위(2026-09-17 후속 §5) =====

    private static PlanBriefItem period(int id, String text, LocalDate start, LocalDate end) {
        return new PlanBriefItem(id, "EXCLUDE", text, "USER", true, false, false, "PERIOD", 1L, 1L, null, null, 2L, null, 1,
                List.of(), LocalDateTime.of(2026, 9, 15, 10, 0), start, end, LocalDate.of(2026, 9, 15), null);
    }

    private static PlanBriefItem thisDraft(int id, String text, Long flow) {
        return new PlanBriefItem(id, "PRIORITY", text, "USER", true, false, false, "THIS_DRAFT", 1L, 1L, null, null, 1L, null,
                1, List.of(), LocalDateTime.of(2026, 9, 15, 10, 0), null, null, LocalDate.of(2026, 9, 15), flow);
    }

    @Test
    void 이번_주만_영어_제외는_그_주에만_적용되고_다음_주_계획에는_넘어가지_않는다() {
        PlanBriefService.View view = new PlanBriefService.View(1L, CONV, 3, List.of(
                period(1, "이번 주만 영어 제외", LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 20))), null);

        assertThat(view.effectiveFor(LocalDate.of(2026, 9, 16), LocalDate.of(2026, 9, 20), null))
                .extracting(a -> a.item().id()).containsExactly(1);
        // 같은 대화에서 다음 주 계획을 요청해도 같다.
        assertThat(view.effectiveFor(LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 27), null)).isEmpty();
        // 일부만 겹치면 원래 범위를 함께 전달한다 — 새 기간 전체로 넓히지 않는다.
        List<PlanBriefService.Applicable> partial = view.effectiveFor(LocalDate.of(2026, 9, 18), LocalDate.of(2026, 9, 24), null);
        assertThat(partial).hasSize(1);
        assertThat(partial.get(0).note()).isEqualTo("원래 9/14~9/20에 적용");
        assertThat(PlanBriefService.agreedLines(partial).get(0)).contains("이번 기간 9/14~9/20 — 원래 9/14~9/20에 적용");
        // 상담 프롬프트는 지난 기간 합의를 "지금 조건 아님"으로 표시한다.
        assertThat(PlanBriefService.renderForConsultation(view, LocalDate.of(2026, 9, 22)))
                .contains("(기간 지남 9/14~9/20 — 지금 조건 아님)");
        assertThat(PlanBriefService.renderForConsultation(view, LocalDate.of(2026, 9, 17))).contains("(이번 기간 9/14~9/20)");
    }

    @Test
    void 이번_초안_제외는_같은_흐름의_다시_만들기에는_남고_별도_새_계획에는_전파되지_않는다() {
        PlanBriefService.View view = new PlanBriefService.View(1L, CONV, 3, List.of(
                thisDraft(1, "이번 초안만 자료구조 우선", 77L), thisDraft(2, "아직 초안을 만들지 않은 합의", null)), 77L);

        // 초안 77의 다시 만들기(같은 흐름): 둘 다.
        assertThat(view.effectiveFor(LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 20), 77L))
                .extracting(a -> a.item().id()).containsExactly(1, 2);
        // 새 계획(흐름 없음): 묶인 것은 빠지고 아직 묶이지 않은 것만.
        assertThat(view.effectiveFor(LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 27), null))
                .extracting(a -> a.item().id()).containsExactly(2);
        // 다른 흐름(초안 90)의 다시 만들기: 77의 것은 빠진다.
        assertThat(view.effectiveFor(LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 20), 90L))
                .extracting(a -> a.item().id()).containsExactly(2);
    }

    @Test
    void 날짜_없는_옛_기간_합의는_범위_미확인으로_남고_현재_합의처럼_전달되지_않는다() {
        PlanBriefItem legacy = new PlanBriefItem(1, "EXCLUDE", "영어 제외", "USER", true, false, false, "PERIOD", 1L, 1L, null,
                null, null, null, 1, List.of(), LocalDateTime.of(2026, 8, 1, 10, 0));
        PlanBriefService.View view = new PlanBriefService.View(1L, CONV, 1, List.of(legacy), null);

        assertThat(view.effectiveFor(LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 20), null)).isEmpty();
        assertThat(view.unknownRange()).hasSize(1);
        assertThat(PlanBriefService.renderForConsultation(view, LocalDate.of(2026, 9, 17))).contains("날짜 미확인, 과거 참고");
    }

    @Test
    void 초안을_만들면_묶이지_않은_합의가_그_초안_흐름과_기간에_묶이고_이미_묶인_것은_그대로다() {
        PlanBriefItem legacyPeriod = new PlanBriefItem(3, "TIME_CONSTRAINT", "저녁만", "USER", true, false, false, "PERIOD",
                1L, 1L, null, null, null, null, 1, List.of(), LocalDateTime.of(2026, 9, 15, 10, 0));
        PlanBrief stored = brief(4, service.serialize(List.of(thisDraft(1, "묶인 것", 50L), thisDraft(2, "안 묶인 것", null),
                legacyPeriod)));
        when(mapper.findByIdAndUserId(1L, USER)).thenReturn(stored);
        when(mapper.updateItems(eq(1L), eq(USER), eq(4), anyString(), anyString())).thenReturn(1);

        service.markProposal(USER, 1L, 88L, 77L, LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 20));

        verify(mapper).updateLastProposal(1L, USER, 88L);
        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(mapper).updateItems(eq(1L), eq(USER), eq(4), saved.capture(), anyString());
        List<PlanBriefItem> items = service.parse(saved.getValue());
        assertThat(items.get(0).flowProposalId()).isEqualTo(50L);
        assertThat(items.get(1).flowProposalId()).isEqualTo(77L);
        assertThat(items.get(2).periodStart()).isEqualTo(LocalDate.of(2026, 9, 14));
        assertThat(items.get(2).periodEnd()).isEqualTo(LocalDate.of(2026, 9, 20));
    }

    @Test
    void 이번_주는_발언_시점의_사용자_시간대_주로_해석하고_OFFER_기간이_있으면_그것을_쓴다() {
        // 고정 시각 2026-09-17T03:00Z = 한국 9/17(목) 12:00 → 이번 주 = 9/14(월)~9/20(일). 시간대는 서비스가 정한다.
        when(mapper.findByConversationIdAndUserId(CONV, USER)).thenReturn(null);
        PlanBriefService.View weekView = service.applyTurn(USER, CONV, 1L, 2L, List.of(
                new PlanBriefOp("ADD", null, "EXCLUDE", "이번 주는 영어 제외", "USER", "PERIOD", null, null, null)), null);
        PlanBriefItem week = weekView.items().get(0);
        assertThat(week.periodStart()).isEqualTo(LocalDate.of(2026, 9, 14));
        assertThat(week.periodEnd()).isEqualTo(LocalDate.of(2026, 9, 20));
        assertThat(week.saidOn()).isEqualTo(LocalDate.of(2026, 9, 17));

        PlanBriefService.View offerView = service.applyTurn(USER, CONV, 1L, 2L, List.of(
                new PlanBriefOp("ADD", null, "EXCLUDE", "시험 전까지 영어 제외", "USER", "PERIOD", null, null, null)),
                new PlanBriefService.TurnPeriod(LocalDate.of(2026, 9, 17), LocalDate.of(2026, 9, 22)));
        assertThat(offerView.items().get(0).periodStart()).isEqualTo(LocalDate.of(2026, 9, 17));
        assertThat(offerView.items().get(0).periodEnd()).isEqualTo(LocalDate.of(2026, 9, 22));

        // 모델이 날짜를 적었으면 그것이 우선이고, 사용자가 기간을 고쳐 말하면 최신 조건과 출처가 남는다.
        PlanBrief stored = brief(1, service.serialize(List.of(period(1, "영어 제외", LocalDate.of(2026, 9, 14),
                LocalDate.of(2026, 9, 20)))));
        when(mapper.findByConversationIdAndUserId(CONV, USER)).thenReturn(stored);
        when(mapper.updateItems(eq(1L), eq(USER), eq(1), anyString(), anyString())).thenReturn(1);
        PlanBriefService.View revised = service.applyTurn(USER, CONV, 9L, 10L, List.of(
                new PlanBriefOp("UPDATE", 1, null, "영어 제외는 다음 주까지", null, "PERIOD", null, null, null,
                        "2026-09-14", "2026-09-27")), null);
        PlanBriefItem item = revised.items().get(0);
        assertThat(item.periodEnd()).isEqualTo(LocalDate.of(2026, 9, 27));
        assertThat(item.acceptedByMessageId()).isEqualTo(9L);
        assertThat(item.revision()).isEqualTo(2);
        assertThat(item.history()).containsExactly("영어 제외");
    }

    @Test
    void 다른_대화에서는_과목_항목이_붙은_어려움과_아직_지나지_않은_기간_합의만_이어_오고_날짜_없는_기간_합의는_뺀다() {
        PlanBrief other = brief(1, service.serialize(List.of(
                new PlanBriefItem(1, "DIFFICULTY", "반복문에서 막힘", "USER", true, false, false, "THIS_DRAFT", 1L, 1L, null,
                        44L, 1L, null, 1, List.of(), null),
                period(2, "이번 주는 영어 제외", LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 13)),
                period(3, "시험 전 금요일 밤 비움", LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 25)),
                new PlanBriefItem(4, "EXCLUDE", "옛 기간 합의", "USER", true, false, false, "PERIOD", 1L, 1L, null, null,
                        null, null, 1, List.of(), null))));
        other.setConversationId(99L);
        other.setUpdatedAt(java.time.LocalDateTime.of(2026, 9, 16, 10, 0));
        when(mapper.findRecentByUserId(eq(USER), anyInt())).thenReturn(List.of(other));

        List<PlanBriefItem> carried = service.carriedOver(USER, CONV, 5);

        assertThat(carried).extracting(PlanBriefItem::id).containsExactly(1, 3);
        assertThat(carried.get(0).courseId()).isEqualTo(1L);
        assertThat(carried.get(0).topicId()).isEqualTo(44L);
        // 계획 생성 기간(9/28~10/4)과 겹치지 않으면 기간 합의는 빠진다.
        assertThat(service.carriedOver(USER, CONV, 5, LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 4)))
                .extracting(PlanBriefItem::id).containsExactly(1);
    }

    private static PlanBrief brief(int version, String items) {
        return PlanBrief.builder().briefId(1L).userId(USER).conversationId(CONV).version(version).status("OPEN")
                .items(items).build();
    }
}
