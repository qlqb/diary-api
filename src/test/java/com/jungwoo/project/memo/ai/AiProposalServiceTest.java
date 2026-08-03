package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.domain.AiProposal;
import com.jungwoo.project.memo.ai.domain.AiProposalItem;
import com.jungwoo.project.memo.ai.domain.AiProposalItemStatus;
import com.jungwoo.project.memo.ai.domain.AiProposalItemType;
import com.jungwoo.project.memo.ai.domain.AiProposalStatus;
import com.jungwoo.project.memo.ai.domain.AiProposalTargetScope;
import com.jungwoo.project.memo.ai.dto.AiProposalApplyRequest;
import com.jungwoo.project.memo.ai.dto.AiProposalCreateRequest;
import com.jungwoo.project.memo.ai.dto.AiProposalResponse;
import com.jungwoo.project.memo.ai.dto.ProposalItem;
import com.jungwoo.project.memo.ai.dto.TodayProposal;
import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.common.exception.ServiceUnavailableException;
import com.jungwoo.project.memo.execution.ExecutionItemService;
import com.jungwoo.project.memo.execution.domain.ExecutionItem;
import com.jungwoo.project.memo.execution.domain.ExecutionPriority;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
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
 * TodayProposalGenerator는 실제 OpenAI를 호출하지 않도록 항상 mock 처리한다.
 */
@ExtendWith(MockitoExtension.class)
class AiProposalServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long PROPOSAL_ID = 100L;
    private static final LocalDate TARGET_DATE = LocalDate.of(2026, 8, 4);

    @Mock
    private TodayProposalGenerator proposalGenerator;

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

    @Test
    void get_deniesAccess_whenProposalNotOwnedByCurrentUser() {
        when(aiProposalMapper.findByIdAndUserId(PROPOSAL_ID, USER_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.get(PROPOSAL_ID, USER_ID))
                .isInstanceOfSatisfying(NotFoundException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AI_PROPOSAL_NOT_FOUND));
    }

    @Test
    void create_returns503_whenAiNotConfigured_andDoesNotSaveProposal() {
        when(proposalGenerator.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> service.create(USER_ID, createRequest("오늘 뭔가 하고 싶은데 피곤해")))
                .isInstanceOfSatisfying(ServiceUnavailableException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AI_NOT_CONFIGURED));

        verify(persistenceService, never()).save(any(), any());
    }

    @Test
    void create_doesNotSaveProposal_whenGeneratedItemCountOutOfRange() {
        when(proposalGenerator.isConfigured()).thenReturn(true);
        when(proposalGenerator.generate(any(), any())).thenReturn(new TodayProposal(List.of()));

        assertThatThrownBy(() -> service.create(USER_ID, createRequest("...")))
                .isInstanceOfSatisfying(ServiceUnavailableException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AI_GENERATION_FAILED));

        verify(persistenceService, never()).save(any(), any());
    }

    @Test
    void create_doesNotSaveProposal_whenPriorityInvalid() {
        when(proposalGenerator.isConfigured()).thenReturn(true);
        when(proposalGenerator.generate(any(), any())).thenReturn(new TodayProposal(List.of(
                new ProposalItem("제목", "설명", 30, "URGENT")
        )));

        assertThatThrownBy(() -> service.create(USER_ID, createRequest("...")))
                .isInstanceOfSatisfying(ServiceUnavailableException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AI_GENERATION_FAILED));

        verify(persistenceService, never()).save(any(), any());
    }

    @Test
    void create_doesNotSaveProposal_whenExpectedMinutesNotPositive() {
        when(proposalGenerator.isConfigured()).thenReturn(true);
        when(proposalGenerator.generate(any(), any())).thenReturn(new TodayProposal(List.of(
                new ProposalItem("제목", "설명", 0, "SHOULD")
        )));

        assertThatThrownBy(() -> service.create(USER_ID, createRequest("...")))
                .isInstanceOfSatisfying(ServiceUnavailableException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AI_GENERATION_FAILED));

        verify(persistenceService, never()).save(any(), any());
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
                any(), any(), any(), any(), any(), any(), anyInt(), anyBoolean());
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
                any(), any(), any(), any(), any(), any(), anyInt(), anyBoolean());
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
                any(), any(), any(), any(), any(), any(), anyInt(), anyBoolean());
    }

    @Test
    void apply_createsExecutionItemAsIs_andMarksHeaderApplied_whenNotEdited() {
        when(aiProposalMapper.findByIdAndUserIdForUpdate(PROPOSAL_ID, USER_ID)).thenReturn(proposedProposal());
        when(aiProposalItemMapper.findByProposalIdAndUserId(PROPOSAL_ID, USER_ID))
                .thenReturn(List.of(proposalItem(1L, samplePayloadJson())));

        ExecutionItem created = ExecutionItem.builder().executionItemId(500L).build();
        when(executionItemService.createFromApprovedProposal(
                eq(USER_ID), eq("교재 6장 읽기"), isNull(), eq(TARGET_DATE), eq(30),
                eq(ExecutionPriority.SHOULD), eq(0), eq(false)
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

        ExecutionItem created = ExecutionItem.builder().executionItemId(501L).build();
        when(executionItemService.createFromApprovedProposal(
                eq(USER_ID), eq("바뀐 제목"), isNull(), eq(TARGET_DATE), eq(30),
                eq(ExecutionPriority.SHOULD), eq(0), eq(true)
        )).thenReturn(created);

        AiProposalApplyRequest request = new AiProposalApplyRequest();
        request.setEditedItems(List.of(AiProposalApplyRequest.EditedProposalItem.builder()
                .proposalItemId(1L).title("바뀐 제목").build()));

        AiProposalResponse response = service.apply(PROPOSAL_ID, USER_ID, request);

        assertThat(response.getStatus()).isEqualTo(AiProposalStatus.MODIFIED_APPLIED);
        verify(aiProposalItemMapper).updateAfterApply(
                eq(1L), eq(USER_ID), eq(AiProposalItemStatus.MODIFIED_APPLIED), any(), eq("EXECUTION_ITEM"), eq(501L), any());
    }

    private AiProposalCreateRequest createRequest(String sourceText) {
        return AiProposalCreateRequest.builder().sourceText(sourceText).targetDate(TARGET_DATE).build();
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
                + "\"priority\":\"SHOULD\",\"targetDate\":\"2026-08-04\"}";
    }
}
