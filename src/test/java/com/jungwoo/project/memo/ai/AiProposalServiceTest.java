package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.domain.AiProposal;
import com.jungwoo.project.memo.ai.domain.AiProposalItem;
import com.jungwoo.project.memo.ai.domain.AiProposalItemStatus;
import com.jungwoo.project.memo.ai.domain.AiProposalItemType;
import com.jungwoo.project.memo.ai.domain.AiProposalStatus;
import com.jungwoo.project.memo.ai.domain.AiProposalTargetScope;
import com.jungwoo.project.memo.ai.dto.AiProposalApplyRequest;
import com.jungwoo.project.memo.ai.dto.AiProposalResponse;
import com.jungwoo.project.memo.ai.dto.ProposalItem;
import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.common.exception.ServiceUnavailableException;
import com.jungwoo.project.memo.execution.ExecutionItemService;
import com.jungwoo.project.memo.execution.domain.ExecutionItem;
import com.jungwoo.project.memo.execution.domain.ExecutionPriority;
import com.jungwoo.project.memo.execution.domain.PlacementType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AiProposalService는 더 이상 LLM을 직접 부르지 않는다 (AiConversationService가 스트리밍/파싱을
 * 담당). 여기서는 createFromItems()의 서버 검증과 apply()의 적용/제외/시간 규칙만 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class AiProposalServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long PROPOSAL_ID = 100L;
    private static final Long CONVERSATION_ID = 10L;
    private static final Long SOURCE_MESSAGE_ID = 50L;
    private static final LocalDate TARGET_DATE = LocalDate.of(2026, 8, 4);

    @Mock
    private AiProposalPersistenceService persistenceService;

    @Mock
    private AiProposalMapper aiProposalMapper;

    @Mock
    private AiProposalItemMapper aiProposalItemMapper;

    @Mock
    private ExecutionItemService executionItemService;

    @InjectMocks
    private AiProposalService service;

    // ===== createFromItems =====

    @Test
    void createFromItems_doesNotSave_whenItemCountOutOfRange() {
        assertThatThrownBy(() -> service.createFromItems(USER_ID, CONVERSATION_ID, SOURCE_MESSAGE_ID, List.of(), TARGET_DATE, List.of()))
                .isInstanceOfSatisfying(ServiceUnavailableException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AI_GENERATION_FAILED));

        verify(persistenceService, never()).save(any(), any(), any(), any(), any());
    }

    @Test
    void createFromItems_doesNotSave_whenPriorityInvalid() {
        List<ProposalItem> items = List.of(dateOnlyItem("제목", 30, "URGENT"));

        assertThatThrownBy(() -> service.createFromItems(USER_ID, CONVERSATION_ID, SOURCE_MESSAGE_ID, items, TARGET_DATE, List.of()))
                .isInstanceOfSatisfying(ServiceUnavailableException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AI_GENERATION_FAILED));

        verify(persistenceService, never()).save(any(), any(), any(), any(), any());
    }

    @Test
    void createFromItems_doesNotSave_whenExpectedMinutesIs4_belowMinimum() {
        List<ProposalItem> items = List.of(dateOnlyItem("제목", 4, "SHOULD"));

        assertThatThrownBy(() -> service.createFromItems(USER_ID, CONVERSATION_ID, SOURCE_MESSAGE_ID, items, TARGET_DATE, List.of()))
                .isInstanceOfSatisfying(ServiceUnavailableException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AI_GENERATION_FAILED));

        verify(persistenceService, never()).save(any(), any(), any(), any(), any());
    }

    @Test
    void createFromItems_saves_whenExpectedMinutesIs5_atMinimum() {
        List<ProposalItem> items = List.of(dateOnlyItem("제목", 5, "SHOULD"));
        when(persistenceService.save(eq(USER_ID), eq(CONVERSATION_ID), eq(SOURCE_MESSAGE_ID), any(), any()))
                .thenReturn(AiProposalResponse.builder().proposalId(PROPOSAL_ID).build());

        service.createFromItems(USER_ID, CONVERSATION_ID, SOURCE_MESSAGE_ID, items, TARGET_DATE, List.of());

        verify(persistenceService).save(eq(USER_ID), eq(CONVERSATION_ID), eq(SOURCE_MESSAGE_ID), any(), any());
    }

    @Test
    void createFromItems_saves_whenExpectedMinutesIs120_atMaximum() {
        List<ProposalItem> items = List.of(dateOnlyItem("제목", 120, "SHOULD"));
        when(persistenceService.save(eq(USER_ID), eq(CONVERSATION_ID), eq(SOURCE_MESSAGE_ID), any(), any()))
                .thenReturn(AiProposalResponse.builder().proposalId(PROPOSAL_ID).build());

        service.createFromItems(USER_ID, CONVERSATION_ID, SOURCE_MESSAGE_ID, items, TARGET_DATE, List.of());

        verify(persistenceService).save(eq(USER_ID), eq(CONVERSATION_ID), eq(SOURCE_MESSAGE_ID), any(), any());
    }

    @Test
    void createFromItems_doesNotSave_whenExpectedMinutesIs121_aboveMaximum() {
        List<ProposalItem> items = List.of(dateOnlyItem("제목", 121, "SHOULD"));

        assertThatThrownBy(() -> service.createFromItems(USER_ID, CONVERSATION_ID, SOURCE_MESSAGE_ID, items, TARGET_DATE, List.of()))
                .isInstanceOfSatisfying(ServiceUnavailableException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AI_GENERATION_FAILED));

        verify(persistenceService, never()).save(any(), any(), any(), any(), any());
    }

    @Test
    void createFromItems_doesNotSave_whenTimeFixedMissingTimes() {
        List<ProposalItem> items = List.of(new ProposalItem(
                "제목", "설명", 30, "SHOULD", PlacementType.TIME_FIXED, null, null,
                null, null, null, null));

        assertThatThrownBy(() -> service.createFromItems(USER_ID, CONVERSATION_ID, SOURCE_MESSAGE_ID, items, TARGET_DATE, List.of()))
                .isInstanceOfSatisfying(ServiceUnavailableException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AI_GENERATION_FAILED));

        verify(persistenceService, never()).save(any(), any(), any(), any(), any());
    }

    @Test
    void createFromItems_doesNotSave_whenDateOnlyHasTimes() {
        List<ProposalItem> items = List.of(new ProposalItem(
                "제목", "설명", 30, "SHOULD", PlacementType.DATE_ONLY, LocalTime.of(9, 0), null,
                null, null, null, null));

        assertThatThrownBy(() -> service.createFromItems(USER_ID, CONVERSATION_ID, SOURCE_MESSAGE_ID, items, TARGET_DATE, List.of()))
                .isInstanceOfSatisfying(ServiceUnavailableException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AI_GENERATION_FAILED));

        verify(persistenceService, never()).save(any(), any(), any(), any(), any());
    }

    @Test
    void createFromItems_saves_whenTimeFixedValid() {
        List<ProposalItem> items = List.of(new ProposalItem(
                "제목", "설명", 30, "SHOULD", PlacementType.TIME_FIXED, LocalTime.of(9, 0), LocalTime.of(9, 30),
                null, null, null, null));
        when(persistenceService.save(eq(USER_ID), eq(CONVERSATION_ID), eq(SOURCE_MESSAGE_ID), any(), any()))
                .thenReturn(AiProposalResponse.builder().proposalId(PROPOSAL_ID).build());

        AiProposalResponse response = service.createFromItems(USER_ID, CONVERSATION_ID, SOURCE_MESSAGE_ID, items, TARGET_DATE, List.of());

        assertThat(response.getProposalId()).isEqualTo(PROPOSAL_ID);
        verify(persistenceService).save(eq(USER_ID), eq(CONVERSATION_ID), eq(SOURCE_MESSAGE_ID), any(), any());
    }

    // ===== apply =====

    @Test
    void get_deniesAccess_whenProposalNotOwnedByCurrentUser() {
        when(aiProposalMapper.findByIdAndUserId(PROPOSAL_ID, USER_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.get(PROPOSAL_ID, USER_ID))
                .isInstanceOfSatisfying(NotFoundException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AI_PROPOSAL_NOT_FOUND));
    }

    @Test
    void apply_returnsConflict_whenProposalAlreadyResponded_andCreatesNothing() {
        AiProposal proposal = AiProposal.builder()
                .proposalId(PROPOSAL_ID).userId(USER_ID)
                .status(AiProposalStatus.APPLIED)
                .targetScope(AiProposalTargetScope.TODAY)
                .build();
        when(aiProposalMapper.findByIdAndUserIdForUpdate(PROPOSAL_ID, USER_ID)).thenReturn(proposal);

        assertThatThrownBy(() -> service.apply(PROPOSAL_ID, USER_ID, new AiProposalApplyRequest()))
                .isInstanceOfSatisfying(ConflictException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AI_PROPOSAL_ALREADY_RESPONDED));

        verify(executionItemService, never()).createFromApprovedProposal(
                any(), any(), any(), any(), any(), any(), anyInt(), anyBoolean(), any(), any(), any());
    }

    @Test
    void apply_rejectsUnknownProposalItemId_andCreatesNothing() {
        when(aiProposalMapper.findByIdAndUserIdForUpdate(PROPOSAL_ID, USER_ID)).thenReturn(proposedProposal());
        when(aiProposalItemMapper.findByProposalIdAndUserId(PROPOSAL_ID, USER_ID))
                .thenReturn(List.of(proposalItem(1L, samplePayloadJson())));

        AiProposalApplyRequest request = new AiProposalApplyRequest();
        request.setEditedItems(List.of(AiProposalApplyRequest.EditedProposalItem.builder()
                .proposalItemId(999L).title("다른 제목").build()));

        assertThatThrownBy(() -> service.apply(PROPOSAL_ID, USER_ID, request))
                .isInstanceOfSatisfying(BadRequestException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_PROPOSAL_ITEM_SELECTION));

        verify(executionItemService, never()).createFromApprovedProposal(
                any(), any(), any(), any(), any(), any(), anyInt(), anyBoolean(), any(), any(), any());
    }

    @Test
    void apply_rejectsDuplicateProposalItemId() {
        when(aiProposalMapper.findByIdAndUserIdForUpdate(PROPOSAL_ID, USER_ID)).thenReturn(proposedProposal());
        when(aiProposalItemMapper.findByProposalIdAndUserId(PROPOSAL_ID, USER_ID))
                .thenReturn(List.of(proposalItem(1L, samplePayloadJson())));

        AiProposalApplyRequest request = new AiProposalApplyRequest();
        request.setEditedItems(List.of(
                AiProposalApplyRequest.EditedProposalItem.builder().proposalItemId(1L).title("A").build(),
                AiProposalApplyRequest.EditedProposalItem.builder().proposalItemId(1L).title("B").build()
        ));

        assertThatThrownBy(() -> service.apply(PROPOSAL_ID, USER_ID, request))
                .isInstanceOfSatisfying(BadRequestException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_PROPOSAL_ITEM_SELECTION));

        verify(executionItemService, never()).createFromApprovedProposal(
                any(), any(), any(), any(), any(), any(), anyInt(), anyBoolean(), any(), any(), any());
    }

    @Test
    void apply_createsExecutionItemAsIs_andMarksHeaderApplied_whenNotEdited() {
        when(aiProposalMapper.findByIdAndUserIdForUpdate(PROPOSAL_ID, USER_ID)).thenReturn(proposedProposal());
        when(aiProposalItemMapper.findByProposalIdAndUserId(PROPOSAL_ID, USER_ID))
                .thenReturn(List.of(proposalItem(1L, samplePayloadJson())));
        when(executionItemService.nextOrderIndexStart(USER_ID, TARGET_DATE)).thenReturn(0);

        ExecutionItem created = ExecutionItem.builder().executionItemId(500L).build();
        when(executionItemService.createFromApprovedProposal(
                eq(USER_ID), eq("교재 6장 읽기"), isNull(), eq(TARGET_DATE), eq(30),
                eq(ExecutionPriority.SHOULD), eq(0), eq(false),
                eq(PlacementType.DATE_ONLY), isNull(), isNull()
        )).thenReturn(created);

        AiProposalResponse response = service.apply(PROPOSAL_ID, USER_ID, new AiProposalApplyRequest());

        assertThat(response.getStatus()).isEqualTo(AiProposalStatus.APPLIED);
        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getCreatedItemId()).isEqualTo(500L);

        verify(aiProposalMapper).updateStatusAndRespondedAt(eq(PROPOSAL_ID), eq(USER_ID), eq(AiProposalStatus.APPLIED), any());
        verify(aiProposalItemMapper).updateAfterApply(
                eq(1L), eq(USER_ID), eq(AiProposalItemStatus.APPLIED), isNull(), eq("EXECUTION_ITEM"), eq(500L), any());
    }

    @Test
    void apply_marksHeaderModifiedApplied_whenAnyItemEdited() {
        when(aiProposalMapper.findByIdAndUserIdForUpdate(PROPOSAL_ID, USER_ID)).thenReturn(proposedProposal());
        when(aiProposalItemMapper.findByProposalIdAndUserId(PROPOSAL_ID, USER_ID))
                .thenReturn(List.of(proposalItem(1L, samplePayloadJson())));
        when(executionItemService.nextOrderIndexStart(USER_ID, TARGET_DATE)).thenReturn(0);

        ExecutionItem created = ExecutionItem.builder().executionItemId(501L).build();
        when(executionItemService.createFromApprovedProposal(
                eq(USER_ID), eq("바뀐 제목"), isNull(), eq(TARGET_DATE), eq(30),
                eq(ExecutionPriority.SHOULD), eq(0), eq(true),
                eq(PlacementType.DATE_ONLY), isNull(), isNull()
        )).thenReturn(created);

        AiProposalApplyRequest request = new AiProposalApplyRequest();
        request.setEditedItems(List.of(AiProposalApplyRequest.EditedProposalItem.builder()
                .proposalItemId(1L).title("바뀐 제목").build()));

        AiProposalResponse response = service.apply(PROPOSAL_ID, USER_ID, request);

        assertThat(response.getStatus()).isEqualTo(AiProposalStatus.MODIFIED_APPLIED);
        verify(aiProposalItemMapper).updateAfterApply(
                eq(1L), eq(USER_ID), eq(AiProposalItemStatus.MODIFIED_APPLIED), any(), eq("EXECUTION_ITEM"), eq(501L), any());
    }

    @Test
    void apply_dismissesExcludedItem_withoutCreatingExecutionItem() {
        when(aiProposalMapper.findByIdAndUserIdForUpdate(PROPOSAL_ID, USER_ID)).thenReturn(proposedProposal());
        when(aiProposalItemMapper.findByProposalIdAndUserId(PROPOSAL_ID, USER_ID))
                .thenReturn(List.of(proposalItem(1L, samplePayloadJson()), proposalItem(2L, samplePayloadJson())));
        when(executionItemService.nextOrderIndexStart(USER_ID, TARGET_DATE)).thenReturn(0);
        when(executionItemService.createFromApprovedProposal(
                any(), any(), any(), any(), any(), any(), anyInt(), anyBoolean(), any(), any(), any()
        )).thenReturn(ExecutionItem.builder().executionItemId(700L).build());

        AiProposalApplyRequest request = new AiProposalApplyRequest();
        request.setExcludedItemIds(List.of(1L));

        AiProposalResponse response = service.apply(PROPOSAL_ID, USER_ID, request);

        assertThat(response.getItems()).hasSize(2);
        assertThat(response.getItems().stream().filter(i -> i.getProposalItemId().equals(1L)).findFirst().orElseThrow().getStatus())
                .isEqualTo(AiProposalItemStatus.DISMISSED);

        // 제외된 항목(1L)만큼 줄어든 1번만 execution_items가 만들어진다 (전체 2개 중 제외 1개).
        verify(executionItemService, org.mockito.Mockito.times(1)).createFromApprovedProposal(
                any(), any(), any(), any(), any(), any(), anyInt(), anyBoolean(), any(), any(), any());
        verify(aiProposalItemMapper).updateAfterApply(
                eq(1L), eq(USER_ID), eq(AiProposalItemStatus.DISMISSED), isNull(), isNull(), isNull(), any());
    }

    @Test
    void apply_rejectsExcludingEveryItem() {
        when(aiProposalMapper.findByIdAndUserIdForUpdate(PROPOSAL_ID, USER_ID)).thenReturn(proposedProposal());
        when(aiProposalItemMapper.findByProposalIdAndUserId(PROPOSAL_ID, USER_ID))
                .thenReturn(List.of(proposalItem(1L, samplePayloadJson())));

        AiProposalApplyRequest request = new AiProposalApplyRequest();
        request.setExcludedItemIds(List.of(1L));

        assertThatThrownBy(() -> service.apply(PROPOSAL_ID, USER_ID, request))
                .isInstanceOfSatisfying(BadRequestException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_PROPOSAL_ITEM_SELECTION));
    }

    @Test
    void apply_usesEditedScheduledDate_forMultiDayPlacement() {
        when(aiProposalMapper.findByIdAndUserIdForUpdate(PROPOSAL_ID, USER_ID)).thenReturn(proposedProposal());
        when(aiProposalItemMapper.findByProposalIdAndUserId(PROPOSAL_ID, USER_ID))
                .thenReturn(List.of(proposalItem(1L, samplePayloadJson())));

        LocalDate differentDate = LocalDate.of(2026, 8, 6);
        LocalDateTime start = LocalDateTime.of(differentDate, LocalTime.of(10, 0));
        LocalDateTime end = LocalDateTime.of(differentDate, LocalTime.of(10, 30));
        when(executionItemService.nextOrderIndexStart(USER_ID, differentDate)).thenReturn(0);
        when(executionItemService.createFromApprovedProposal(
                eq(USER_ID), any(), any(), eq(differentDate), any(), any(), anyInt(), eq(true),
                eq(PlacementType.TIME_FIXED), eq(start), eq(end)
        )).thenReturn(ExecutionItem.builder().executionItemId(600L).build());

        // 7일 범위 배치 미리보기가 원래 제안 날짜(TARGET_DATE)와 다른 날(differentDate)에
        // 배치를 확정한 뒤, 사용자가 "최종 적용"을 누르면 프론트가 이 값을 editedItems로 보낸다.
        AiProposalApplyRequest request = new AiProposalApplyRequest();
        request.setEditedItems(List.of(AiProposalApplyRequest.EditedProposalItem.builder()
                .proposalItemId(1L)
                .placementType(PlacementType.TIME_FIXED)
                .scheduledStartAt(start)
                .scheduledEndAt(end)
                .scheduledDate(differentDate)
                .build()));

        AiProposalResponse response = service.apply(PROPOSAL_ID, USER_ID, request);

        assertThat(response.getItems().get(0).getTargetDate()).isEqualTo(differentDate);
        assertThat(response.getItems().get(0).getCreatedItemId()).isEqualTo(600L);
        verify(executionItemService).createFromApprovedProposal(
                eq(USER_ID), any(), any(), eq(differentDate), any(), any(), anyInt(), eq(true),
                eq(PlacementType.TIME_FIXED), eq(start), eq(end));
    }

    @Test
    void apply_clearsDateAndTimes_whenEditedToUnscheduled() {
        when(aiProposalMapper.findByIdAndUserIdForUpdate(PROPOSAL_ID, USER_ID)).thenReturn(proposedProposal());
        when(aiProposalItemMapper.findByProposalIdAndUserId(PROPOSAL_ID, USER_ID))
                .thenReturn(List.of(proposalItem(1L, samplePayloadJson())));
        when(executionItemService.nextOrderIndexStart(eq(USER_ID), isNull())).thenReturn(0);
        when(executionItemService.createFromApprovedProposal(
                eq(USER_ID), any(), any(), isNull(), any(), any(), anyInt(), eq(true),
                eq(PlacementType.UNSCHEDULED), isNull(), isNull()
        )).thenReturn(ExecutionItem.builder().executionItemId(601L).build());

        // Timefold가 배치하지 못해 사용자가 "그래도 남기기"로 선택한 항목 — UNSCHEDULED는
        // 날짜·시각을 전혀 갖지 않아야 한다(REQ-EXECUTION-002).
        AiProposalApplyRequest request = new AiProposalApplyRequest();
        request.setEditedItems(List.of(AiProposalApplyRequest.EditedProposalItem.builder()
                .proposalItemId(1L)
                .placementType(PlacementType.UNSCHEDULED)
                .build()));

        AiProposalResponse response = service.apply(PROPOSAL_ID, USER_ID, request);

        assertThat(response.getItems().get(0).getPlacementType()).isEqualTo(PlacementType.UNSCHEDULED);
        assertThat(response.getItems().get(0).getScheduledStartAt()).isNull();
        assertThat(response.getItems().get(0).getScheduledEndAt()).isNull();
        verify(executionItemService).createFromApprovedProposal(
                eq(USER_ID), any(), any(), isNull(), any(), any(), anyInt(), eq(true),
                eq(PlacementType.UNSCHEDULED), isNull(), isNull());
    }

    private ProposalItem dateOnlyItem(String title, int expectedMinutes, String priority) {
        return new ProposalItem(title, "설명", expectedMinutes, priority, PlacementType.DATE_ONLY, null, null,
                null, null, null, null);
    }

    private AiProposal proposedProposal() {
        return AiProposal.builder()
                .proposalId(PROPOSAL_ID).userId(USER_ID)
                .status(AiProposalStatus.PROPOSED)
                .targetScope(AiProposalTargetScope.TODAY)
                .build();
    }

    private AiProposalItem proposalItem(Long id, String originalPayloadJson) {
        return AiProposalItem.builder()
                .proposalItemId(id)
                .proposalId(PROPOSAL_ID)
                .userId(USER_ID)
                .itemType(AiProposalItemType.EXECUTION_ITEM)
                .originalPayload(originalPayloadJson)
                .status(AiProposalItemStatus.PROPOSED)
                .build();
    }

    private String samplePayloadJson() {
        return "{\"title\":\"교재 6장 읽기\",\"description\":null,\"expectedMinutes\":30,"
                + "\"priority\":\"SHOULD\",\"targetDate\":\"2026-08-04\",\"placementType\":\"DATE_ONLY\","
                + "\"scheduledStartAt\":null,\"scheduledEndAt\":null}";
    }
}
