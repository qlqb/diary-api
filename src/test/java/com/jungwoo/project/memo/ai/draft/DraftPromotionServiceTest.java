package com.jungwoo.project.memo.ai.draft;

import com.jungwoo.project.memo.ai.ScheduleSuggestionService;
import com.jungwoo.project.memo.ai.domain.ScheduleSuggestionKind;
import com.jungwoo.project.memo.ai.dto.ScheduleSuggestion;
import com.jungwoo.project.memo.ai.dto.ScheduleSuggestionResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.jungwoo.project.memo.ai.draft.DraftFixtures.CONVERSATION_ID;
import static com.jungwoo.project.memo.ai.draft.DraftFixtures.OM;
import static com.jungwoo.project.memo.ai.draft.DraftFixtures.USER_ID;
import static com.jungwoo.project.memo.ai.draft.DraftFixtures.facts;
import static com.jungwoo.project.memo.ai.draft.DraftFixtures.openRoutine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** B-8 #11: ROUTINE anchor → suggestion 4건 −60분, 같은 트랜잭션에서 PROMOTED, promoted_suggestion_ids 4개. */
@ExtendWith(MockitoExtension.class)
class DraftPromotionServiceTest {

    @Mock private AiConversationDraftMapper draftMapper;
    @Mock private ScheduleSuggestionService scheduleSuggestionService;

    @Test
    void routineAnchor_promotesToFourSuggestions_minusDuration() {
        DraftPromotionService service = new DraftPromotionService(draftMapper, scheduleSuggestionService, OM);
        DraftState draft = openRoutine(31, LocalDateTime.of(2026, 9, 8, 1, 0), true);
        DraftTurnResolver.Outcome outcome = new DraftTurnResolver.Outcome(
                List.of(draft), Set.of("31"), DraftTurnResolver.Action.PROPOSE, false, List.of(draft), null,
                "요약", List.of(), List.of());
        when(scheduleSuggestionService.createFromDraft(eq(USER_ID), eq(CONVERSATION_ID), eq(900L), any()))
                .thenAnswer(inv -> {
                    List<ScheduleSuggestion> in = inv.getArgument(3);
                    List<ScheduleSuggestionResponse> out = new ArrayList<>();
                    for (int i = 0; i < in.size(); i++) {
                        out.add(ScheduleSuggestionResponse.builder().suggestionId(700L + i).kind(in.get(i).kind())
                                .payload(Map.of()).build());
                    }
                    return out;
                });

        DraftPromotionService.PromotionResult result = service.applyTurn(USER_ID, CONVERSATION_ID, 900L, outcome, facts());

        ArgumentCaptor<List<ScheduleSuggestion>> captor = ArgumentCaptor.forClass(List.class);
        verify(scheduleSuggestionService).createFromDraft(eq(USER_ID), eq(CONVERSATION_ID), eq(900L), captor.capture());
        List<ScheduleSuggestion> suggestions = captor.getValue();
        assertThat(suggestions).hasSize(4).allMatch(s -> s.kind() == ScheduleSuggestionKind.ROUTINE);
        assertThat(suggestions).extracting(s -> s.payload().get("daysOfWeek").get(0).asText())
                .containsExactly("TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY");
        assertThat(suggestions).extracting(s -> s.payload().get("startTime").asText())
                .containsExactly("13:00", "09:00", "09:00", "09:00");
        assertThat(suggestions).extracting(s -> s.payload().get("endTime").asText())
                .containsExactly("14:00", "10:00", "10:00", "10:00");
        assertThat(suggestions.get(0).payload().get("effectiveUntil").asText()).isEqualTo("2026-12-11");

        assertThat(result.scheduleSuggestions()).hasSize(4);
        assertThat(draft.getStatus()).isEqualTo(DraftStatus.PROMOTED);
        assertThat(draft.getPromotedSuggestionIds()).containsExactly(700L, 701L, 702L, 703L);
        ArgumentCaptor<AiConversationDraft> saved = ArgumentCaptor.forClass(AiConversationDraft.class);
        verify(draftMapper).update(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(DraftStatus.PROMOTED);
        assertThat(saved.getValue().getPromotedSuggestionIds()).isEqualTo("[700,701,702,703]");
    }

    /** 새 draft 둘: 첫 것은 자기 id가 그룹, 둘째는 sameGroupAs=new-0으로 같은 그룹. */
    @Test
    void newDrafts_shareGroupId_viaSameGroupAs() {
        DraftPromotionService service = new DraftPromotionService(draftMapper, scheduleSuggestionService, OM);
        DraftState first = DraftState.newDraft("new-0", USER_ID, CONVERSATION_ID, DraftType.CREATE_ROUTINE, "수업 전 이동 루틴");
        DraftState second = DraftState.newDraft("new-1", USER_ID, CONVERSATION_ID, DraftType.CREATE_SCHEDULE, "근무 전 이동 블록");
        second.setSameGroupAs("new-0");
        long[] nextId = {31L};
        doAnswer(inv -> {
            AiConversationDraft entity = inv.getArgument(0);
            entity.setDraftId(nextId[0]++);
            return null;
        }).when(draftMapper).insert(any());
        DraftTurnResolver.Outcome outcome = new DraftTurnResolver.Outcome(
                List.of(first, second), Set.of("new-0", "new-1"), DraftTurnResolver.Action.ASK, false, List.of(),
                first, "q?", List.of(), List.of());

        service.applyTurn(USER_ID, CONVERSATION_ID, 900L, outcome, facts());

        assertThat(first.getDraftId()).isEqualTo(31L);
        assertThat(first.getDraftGroupId()).isEqualTo(31L);
        assertThat(second.getDraftId()).isEqualTo(32L);
        assertThat(second.getDraftGroupId()).isEqualTo(31L);
        verify(draftMapper).updateGroupId(31L, USER_ID, 31L);
    }
}
