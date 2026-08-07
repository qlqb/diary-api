package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.domain.AiContextChangeSuggestion;
import com.jungwoo.project.memo.ai.domain.ContextChangeOperation;
import com.jungwoo.project.memo.ai.domain.ContextSourceType;
import com.jungwoo.project.memo.ai.domain.ContextSuggestionStatus;
import com.jungwoo.project.memo.ai.domain.UserContext;
import com.jungwoo.project.memo.ai.domain.UserContextStatus;
import com.jungwoo.project.memo.ai.dto.ContextChangeSuggestion;
import com.jungwoo.project.memo.ai.dto.ContextSuggestionResponse;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.common.exception.ServiceUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AI 판단(ContextChangeSuggestion)을 그대로 신뢰하지 않는다는 것을 검증한다 —
 * createFromSuggestions는 연산별 계약과 소유권을 다시 확인한 뒤에만 저장하고, apply()는
 * 실제 user_contexts 전이가 안전할 때만 반영하며 idempotent하다.
 */
@ExtendWith(MockitoExtension.class)
class ContextChangeSuggestionServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long CONVERSATION_ID = 10L;
    private static final Long SOURCE_MESSAGE_ID = 500L;
    private static final Long TARGET_CONTEXT_ID = 13L;

    @Mock private UserContextMapper userContextMapper;
    @Mock private AiContextChangeSuggestionMapper suggestionMapper;

    @InjectMocks
    private ContextChangeSuggestionService service;

    // ===== createFromSuggestions: 계약 검증 =====

    @Test
    void createFromSuggestions_add_succeeds_andPersistsAsProposed() {
        ContextChangeSuggestion candidate = new ContextChangeSuggestion(
                ContextChangeOperation.ADD, null, "늦게 퇴근한 다음 날에는 가벼운 계획을 선호한다.", "사용자가 직접 알려줌");

        List<ContextSuggestionResponse> result = service.createFromSuggestions(
                USER_ID, CONVERSATION_ID, SOURCE_MESSAGE_ID, List.of(candidate));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo(ContextSuggestionStatus.PROPOSED);
        assertThat(result.get(0).getTargetContextId()).isNull();

        ArgumentCaptor<AiContextChangeSuggestion> captor = ArgumentCaptor.forClass(AiContextChangeSuggestion.class);
        verify(suggestionMapper).insert(captor.capture());
        assertThat(captor.getValue().getOperation()).isEqualTo(ContextChangeOperation.ADD);
        assertThat(captor.getValue().getTargetContextId()).isNull();
        assertThat(captor.getValue().getStatus()).isEqualTo(ContextSuggestionStatus.PROPOSED);
        // 저장만 할 뿐 실제 장기 컨텍스트(user_contexts)는 건드리지 않는다.
        verify(userContextMapper, never()).insert(any());
    }

    @Test
    void createFromSuggestions_supersede_succeeds_whenTargetOwnedByUser() {
        when(userContextMapper.findByIdAndUserId(TARGET_CONTEXT_ID, USER_ID))
                .thenReturn(sampleContext(TARGET_CONTEXT_ID, USER_ID, "현재 알바에서 집까지 약 50분 걸린다.", UserContextStatus.ACTIVE));
        ContextChangeSuggestion candidate = new ContextChangeSuggestion(
                ContextChangeOperation.SUPERSEDE, TARGET_CONTEXT_ID, "현재 알바에서 집까지 약 20분 걸린다.", "이사해서 이동시간이 바뀜");

        List<ContextSuggestionResponse> result = service.createFromSuggestions(
                USER_ID, CONVERSATION_ID, SOURCE_MESSAGE_ID, List.of(candidate));

        assertThat(result.get(0).getTargetContextContent()).isEqualTo("현재 알바에서 집까지 약 50분 걸린다.");
        assertThat(result.get(0).getProposedContent()).isEqualTo("현재 알바에서 집까지 약 20분 걸린다.");
    }

    @Test
    void createFromSuggestions_add_withTargetContextId_fails() {
        ContextChangeSuggestion candidate = new ContextChangeSuggestion(
                ContextChangeOperation.ADD, TARGET_CONTEXT_ID, "content", "reason");

        assertThatThrownBy(() -> service.createFromSuggestions(USER_ID, CONVERSATION_ID, SOURCE_MESSAGE_ID, List.of(candidate)))
                .isInstanceOf(ServiceUnavailableException.class);
        verify(suggestionMapper, never()).insert(any());
    }

    @Test
    void createFromSuggestions_supersede_withoutTarget_fails() {
        ContextChangeSuggestion candidate = new ContextChangeSuggestion(
                ContextChangeOperation.SUPERSEDE, null, "content", "reason");

        assertThatThrownBy(() -> service.createFromSuggestions(USER_ID, CONVERSATION_ID, SOURCE_MESSAGE_ID, List.of(candidate)))
                .isInstanceOf(ServiceUnavailableException.class);
        verify(suggestionMapper, never()).insert(any());
    }

    @Test
    void createFromSuggestions_supersede_withoutContent_fails() {
        ContextChangeSuggestion candidate = new ContextChangeSuggestion(
                ContextChangeOperation.SUPERSEDE, TARGET_CONTEXT_ID, null, "reason");

        assertThatThrownBy(() -> service.createFromSuggestions(USER_ID, CONVERSATION_ID, SOURCE_MESSAGE_ID, List.of(candidate)))
                .isInstanceOf(ServiceUnavailableException.class);
        verify(suggestionMapper, never()).insert(any());
    }

    @Test
    void createFromSuggestions_markStale_withContent_fails() {
        ContextChangeSuggestion candidate = new ContextChangeSuggestion(
                ContextChangeOperation.MARK_STALE, TARGET_CONTEXT_ID, "이건 채우면 안 됨", "reason");

        assertThatThrownBy(() -> service.createFromSuggestions(USER_ID, CONVERSATION_ID, SOURCE_MESSAGE_ID, List.of(candidate)))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void createFromSuggestions_missingReason_fails() {
        ContextChangeSuggestion candidate = new ContextChangeSuggestion(
                ContextChangeOperation.ADD, null, "content", "  ");

        assertThatThrownBy(() -> service.createFromSuggestions(USER_ID, CONVERSATION_ID, SOURCE_MESSAGE_ID, List.of(candidate)))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void createFromSuggestions_tooManyCandidates_fails() {
        ContextChangeSuggestion candidate = new ContextChangeSuggestion(
                ContextChangeOperation.ADD, null, "content", "reason");
        List<ContextChangeSuggestion> six = List.of(candidate, candidate, candidate, candidate, candidate, candidate);

        assertThatThrownBy(() -> service.createFromSuggestions(USER_ID, CONVERSATION_ID, SOURCE_MESSAGE_ID, six))
                .isInstanceOf(ServiceUnavailableException.class);
        verify(suggestionMapper, never()).insert(any());
    }

    @Test
    void createFromSuggestions_targetOwnedByOtherUser_fails() {
        // 다른 사용자 소유(또는 존재하지 않음) -> findByIdAndUserId(targetId, USER_ID)는 null.
        when(userContextMapper.findByIdAndUserId(TARGET_CONTEXT_ID, USER_ID)).thenReturn(null);
        ContextChangeSuggestion candidate = new ContextChangeSuggestion(
                ContextChangeOperation.SUPERSEDE, TARGET_CONTEXT_ID, "new content", "reason");

        assertThatThrownBy(() -> service.createFromSuggestions(USER_ID, CONVERSATION_ID, SOURCE_MESSAGE_ID, List.of(candidate)))
                .isInstanceOf(ServiceUnavailableException.class);
        verify(suggestionMapper, never()).insert(any());
    }

    @Test
    void createFromSuggestions_empty_returnsEmpty_noPersistence() {
        List<ContextSuggestionResponse> result = service.createFromSuggestions(
                USER_ID, CONVERSATION_ID, SOURCE_MESSAGE_ID, List.of());

        assertThat(result).isEmpty();
        verifyNoInteractions(suggestionMapper);
        verifyNoInteractions(userContextMapper);
    }

    // ===== apply =====

    @Test
    void apply_add_createsActiveContext() {
        AiContextChangeSuggestion suggestion = suggestion(1L, ContextChangeOperation.ADD, null,
                "새로 기억할 정보", ContextSuggestionStatus.PROPOSED);
        when(suggestionMapper.findByIdAndUserIdForUpdate(1L, USER_ID)).thenReturn(suggestion);
        doAnswer(inv -> {
            UserContext created = inv.getArgument(0);
            created.setContextId(99L);
            return null;
        }).when(userContextMapper).insert(any());
        when(suggestionMapper.markApplied(eq(1L), eq(USER_ID), any(), eq(99L))).thenReturn(1);
        when(suggestionMapper.findByIdAndUserId(1L, USER_ID))
                .thenReturn(appliedCopy(suggestion, 99L));

        ContextSuggestionResponse response = service.apply(1L, USER_ID);

        assertThat(response.getStatus()).isEqualTo(ContextSuggestionStatus.APPLIED);
        assertThat(response.getResultingContextId()).isEqualTo(99L);
        ArgumentCaptor<UserContext> captor = ArgumentCaptor.forClass(UserContext.class);
        verify(userContextMapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(UserContextStatus.ACTIVE);
        assertThat(captor.getValue().getSourceType()).isEqualTo(ContextSourceType.AI_SUGGESTION_APPROVED);
        assertThat(captor.getValue().getContent()).isEqualTo("새로 기억할 정보");
    }

    @Test
    void apply_supersede_marksOldSuperseded_andCreatesNewActive() {
        AiContextChangeSuggestion suggestion = suggestion(2L, ContextChangeOperation.SUPERSEDE, TARGET_CONTEXT_ID,
                "현재 알바에서 집까지 약 20분 걸린다.", ContextSuggestionStatus.PROPOSED);
        when(suggestionMapper.findByIdAndUserIdForUpdate(2L, USER_ID)).thenReturn(suggestion);
        when(userContextMapper.updateStatusIfIn(eq(TARGET_CONTEXT_ID), eq(USER_ID), any(), eq(UserContextStatus.SUPERSEDED)))
                .thenReturn(1);
        doAnswer(inv -> {
            UserContext created = inv.getArgument(0);
            created.setContextId(200L);
            return null;
        }).when(userContextMapper).insert(any());
        when(suggestionMapper.markApplied(eq(2L), eq(USER_ID), any(), eq(200L))).thenReturn(1);
        when(suggestionMapper.findByIdAndUserId(2L, USER_ID)).thenReturn(appliedCopy(suggestion, 200L));

        ContextSuggestionResponse response = service.apply(2L, USER_ID);

        assertThat(response.getResultingContextId()).isEqualTo(200L);
        verify(userContextMapper).updateStatusIfIn(eq(TARGET_CONTEXT_ID), eq(USER_ID), any(), eq(UserContextStatus.SUPERSEDED));
        ArgumentCaptor<UserContext> captor = ArgumentCaptor.forClass(UserContext.class);
        verify(userContextMapper).insert(captor.capture());
        assertThat(captor.getValue().getSupersedesContextId()).isEqualTo(TARGET_CONTEXT_ID);
        assertThat(captor.getValue().getStatus()).isEqualTo(UserContextStatus.ACTIVE);
    }

    @Test
    void apply_supersede_targetAlreadyChanged_conflicts_doesNotCreateOrMarkApplied() {
        AiContextChangeSuggestion suggestion = suggestion(3L, ContextChangeOperation.SUPERSEDE, TARGET_CONTEXT_ID,
                "content", ContextSuggestionStatus.PROPOSED);
        when(suggestionMapper.findByIdAndUserIdForUpdate(3L, USER_ID)).thenReturn(suggestion);
        // 그 사이 다른 경로로 ARCHIVED 등으로 바뀌어 ACTIVE/STALE 가드에 걸리지 않음 -> 0 rows.
        when(userContextMapper.updateStatusIfIn(eq(TARGET_CONTEXT_ID), eq(USER_ID), any(), eq(UserContextStatus.SUPERSEDED)))
                .thenReturn(0);

        assertThatThrownBy(() -> service.apply(3L, USER_ID)).isInstanceOf(ConflictException.class);
        verify(userContextMapper, never()).insert(any());
        verify(suggestionMapper, never()).markApplied(any(), any(), any(), any());
    }

    @Test
    void apply_markStale_transitionsActiveToStale() {
        AiContextChangeSuggestion suggestion = suggestion(4L, ContextChangeOperation.MARK_STALE, TARGET_CONTEXT_ID,
                null, ContextSuggestionStatus.PROPOSED);
        when(suggestionMapper.findByIdAndUserIdForUpdate(4L, USER_ID)).thenReturn(suggestion);
        when(userContextMapper.updateStatusIfIn(eq(TARGET_CONTEXT_ID), eq(USER_ID), eq(List.of("ACTIVE")), eq(UserContextStatus.STALE)))
                .thenReturn(1);
        when(suggestionMapper.markApplied(eq(4L), eq(USER_ID), any(), eq(TARGET_CONTEXT_ID))).thenReturn(1);
        when(suggestionMapper.findByIdAndUserId(4L, USER_ID)).thenReturn(appliedCopy(suggestion, TARGET_CONTEXT_ID));

        ContextSuggestionResponse response = service.apply(4L, USER_ID);

        assertThat(response.getResultingContextId()).isEqualTo(TARGET_CONTEXT_ID);
        verify(userContextMapper).updateStatusIfIn(eq(TARGET_CONTEXT_ID), eq(USER_ID), eq(List.of("ACTIVE")), eq(UserContextStatus.STALE));
        verify(userContextMapper, never()).insert(any());
    }

    @Test
    void apply_archive_transitionsToArchived() {
        AiContextChangeSuggestion suggestion = suggestion(5L, ContextChangeOperation.ARCHIVE, TARGET_CONTEXT_ID,
                null, ContextSuggestionStatus.PROPOSED);
        when(suggestionMapper.findByIdAndUserIdForUpdate(5L, USER_ID)).thenReturn(suggestion);
        when(userContextMapper.updateStatusIfIn(eq(TARGET_CONTEXT_ID), eq(USER_ID),
                eq(List.of("ACTIVE", "STALE")), eq(UserContextStatus.ARCHIVED))).thenReturn(1);
        when(suggestionMapper.markApplied(eq(5L), eq(USER_ID), any(), eq(TARGET_CONTEXT_ID))).thenReturn(1);
        when(suggestionMapper.findByIdAndUserId(5L, USER_ID)).thenReturn(appliedCopy(suggestion, TARGET_CONTEXT_ID));

        ContextSuggestionResponse response = service.apply(5L, USER_ID);

        assertThat(response.getResultingContextId()).isEqualTo(TARGET_CONTEXT_ID);
    }

    @Test
    void apply_confirm_transitionsStaleToActive_andSetsConfirmedAt() {
        AiContextChangeSuggestion suggestion = suggestion(6L, ContextChangeOperation.CONFIRM, TARGET_CONTEXT_ID,
                null, ContextSuggestionStatus.PROPOSED);
        when(suggestionMapper.findByIdAndUserIdForUpdate(6L, USER_ID)).thenReturn(suggestion);
        when(userContextMapper.confirmIfStale(eq(TARGET_CONTEXT_ID), eq(USER_ID), any())).thenReturn(1);
        when(suggestionMapper.markApplied(eq(6L), eq(USER_ID), any(), eq(TARGET_CONTEXT_ID))).thenReturn(1);
        when(suggestionMapper.findByIdAndUserId(6L, USER_ID)).thenReturn(appliedCopy(suggestion, TARGET_CONTEXT_ID));

        ContextSuggestionResponse response = service.apply(6L, USER_ID);

        assertThat(response.getResultingContextId()).isEqualTo(TARGET_CONTEXT_ID);
        verify(userContextMapper).confirmIfStale(eq(TARGET_CONTEXT_ID), eq(USER_ID), any());
    }

    @Test
    void apply_alreadyApplied_isIdempotent_doesNotCreateDuplicateContext() {
        AiContextChangeSuggestion applied = suggestion(7L, ContextChangeOperation.ADD, null,
                "content", ContextSuggestionStatus.APPLIED);
        applied.setResultingContextId(500L);
        when(suggestionMapper.findByIdAndUserIdForUpdate(7L, USER_ID)).thenReturn(applied);

        ContextSuggestionResponse response = service.apply(7L, USER_ID);

        assertThat(response.getStatus()).isEqualTo(ContextSuggestionStatus.APPLIED);
        assertThat(response.getResultingContextId()).isEqualTo(500L);
        verify(userContextMapper, never()).insert(any());
        verify(suggestionMapper, never()).markApplied(any(), any(), any(), any());
    }

    @Test
    void apply_dismissedSuggestion_throwsConflict() {
        AiContextChangeSuggestion dismissed = suggestion(8L, ContextChangeOperation.ADD, null,
                "content", ContextSuggestionStatus.DISMISSED);
        when(suggestionMapper.findByIdAndUserIdForUpdate(8L, USER_ID)).thenReturn(dismissed);

        assertThatThrownBy(() -> service.apply(8L, USER_ID)).isInstanceOf(ConflictException.class);
        verify(userContextMapper, never()).insert(any());
    }

    @Test
    void apply_otherUsersSuggestion_notFound() {
        when(suggestionMapper.findByIdAndUserIdForUpdate(9L, USER_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.apply(9L, USER_ID)).isInstanceOf(NotFoundException.class);
    }

    // ===== dismiss =====

    @Test
    void dismiss_proposed_marksDismissed() {
        AiContextChangeSuggestion suggestion = suggestion(10L, ContextChangeOperation.ADD, null,
                "content", ContextSuggestionStatus.PROPOSED);
        when(suggestionMapper.findByIdAndUserIdForUpdate(10L, USER_ID)).thenReturn(suggestion);
        when(suggestionMapper.markDismissed(eq(10L), eq(USER_ID), any())).thenReturn(1);
        when(suggestionMapper.findByIdAndUserId(10L, USER_ID))
                .thenReturn(suggestion(10L, ContextChangeOperation.ADD, null, "content", ContextSuggestionStatus.DISMISSED));

        ContextSuggestionResponse response = service.dismiss(10L, USER_ID);

        assertThat(response.getStatus()).isEqualTo(ContextSuggestionStatus.DISMISSED);
    }

    @Test
    void dismiss_alreadyDismissed_isIdempotent() {
        AiContextChangeSuggestion dismissed = suggestion(12L, ContextChangeOperation.ADD, null,
                "content", ContextSuggestionStatus.DISMISSED);
        when(suggestionMapper.findByIdAndUserIdForUpdate(12L, USER_ID)).thenReturn(dismissed);

        ContextSuggestionResponse response = service.dismiss(12L, USER_ID);

        assertThat(response.getStatus()).isEqualTo(ContextSuggestionStatus.DISMISSED);
        verify(suggestionMapper, never()).markDismissed(any(), any(), any());
    }

    @Test
    void dismiss_appliedSuggestion_throwsConflict() {
        AiContextChangeSuggestion applied = suggestion(11L, ContextChangeOperation.ADD, null,
                "content", ContextSuggestionStatus.APPLIED);
        when(suggestionMapper.findByIdAndUserIdForUpdate(11L, USER_ID)).thenReturn(applied);

        assertThatThrownBy(() -> service.dismiss(11L, USER_ID)).isInstanceOf(ConflictException.class);
    }

    // ===== helpers =====

    private static UserContext sampleContext(Long contextId, Long userId, String content, UserContextStatus status) {
        return UserContext.builder()
                .contextId(contextId)
                .userId(userId)
                .content(content)
                .status(status)
                .sourceType(ContextSourceType.USER_CONFIRMED)
                .build();
    }

    private static AiContextChangeSuggestion suggestion(
            Long suggestionId, ContextChangeOperation operation, Long targetContextId,
            String proposedContent, ContextSuggestionStatus status
    ) {
        return AiContextChangeSuggestion.builder()
                .suggestionId(suggestionId)
                .userId(USER_ID)
                .conversationId(CONVERSATION_ID)
                .sourceMessageId(SOURCE_MESSAGE_ID)
                .operation(operation)
                .targetContextId(targetContextId)
                .proposedContent(proposedContent)
                .reason("reason")
                .status(status)
                .build();
    }

    private static AiContextChangeSuggestion appliedCopy(AiContextChangeSuggestion original, Long resultingContextId) {
        return AiContextChangeSuggestion.builder()
                .suggestionId(original.getSuggestionId())
                .userId(original.getUserId())
                .conversationId(original.getConversationId())
                .sourceMessageId(original.getSourceMessageId())
                .operation(original.getOperation())
                .targetContextId(original.getTargetContextId())
                .proposedContent(original.getProposedContent())
                .reason(original.getReason())
                .status(ContextSuggestionStatus.APPLIED)
                .resultingContextId(resultingContextId)
                .build();
    }
}
