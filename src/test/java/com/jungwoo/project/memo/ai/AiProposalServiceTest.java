package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.domain.AiProposal;
import com.jungwoo.project.memo.ai.domain.AiProposalItem;
import com.jungwoo.project.memo.ai.domain.AiProposalItemStatus;
import com.jungwoo.project.memo.ai.domain.AiProposalItemType;
import com.jungwoo.project.memo.ai.domain.AiProposalStatus;
import com.jungwoo.project.memo.ai.domain.AiProposalTargetScope;
import com.jungwoo.project.memo.ai.dto.AiProposalApplyRequest;
import com.jungwoo.project.memo.ai.dto.AiProposalResponse;
import com.jungwoo.project.memo.ai.domain.ProposalOperation;
import com.jungwoo.project.memo.ai.dto.ProposalAdjustment;
import com.jungwoo.project.memo.ai.dto.ProposalItem;
import com.jungwoo.project.memo.ai.dto.ProposalItemPayload;
import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.common.exception.ServiceUnavailableException;
import com.jungwoo.project.memo.execution.ExecutionItemService;
import com.jungwoo.project.memo.execution.domain.ExecutionItem;
import com.jungwoo.project.memo.execution.domain.ExecutionPriority;
import com.jungwoo.project.memo.execution.domain.PlacementType;
import com.jungwoo.project.memo.execution.dto.ExecutionItemHoldRequest;
import com.jungwoo.project.memo.execution.dto.ExecutionItemMoveRequest;
import com.jungwoo.project.memo.execution.dto.ExecutionItemReduceRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
    private AiConversationMapper aiConversationMapper;

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
                null, null, null, null, null));

        assertThatThrownBy(() -> service.createFromItems(USER_ID, CONVERSATION_ID, SOURCE_MESSAGE_ID, items, TARGET_DATE, List.of()))
                .isInstanceOfSatisfying(ServiceUnavailableException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AI_GENERATION_FAILED));

        verify(persistenceService, never()).save(any(), any(), any(), any(), any());
    }

    @Test
    void createFromItems_doesNotSave_whenDateOnlyHasTimes() {
        List<ProposalItem> items = List.of(new ProposalItem(
                "제목", "설명", 30, "SHOULD", PlacementType.DATE_ONLY, LocalTime.of(9, 0), null,
                null, null, null, null, null));

        assertThatThrownBy(() -> service.createFromItems(USER_ID, CONVERSATION_ID, SOURCE_MESSAGE_ID, items, TARGET_DATE, List.of()))
                .isInstanceOfSatisfying(ServiceUnavailableException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AI_GENERATION_FAILED));

        verify(persistenceService, never()).save(any(), any(), any(), any(), any());
    }

    @Test
    void createFromItems_saves_whenTimeFixedValid() {
        List<ProposalItem> items = List.of(new ProposalItem(
                "제목", "설명", 30, "SHOULD", PlacementType.TIME_FIXED, LocalTime.of(9, 0), LocalTime.of(9, 30),
                null, null, null, null, null));
        when(persistenceService.save(eq(USER_ID), eq(CONVERSATION_ID), eq(SOURCE_MESSAGE_ID), any(), any()))
                .thenReturn(AiProposalResponse.builder().proposalId(PROPOSAL_ID).build());

        AiProposalResponse response = service.createFromItems(USER_ID, CONVERSATION_ID, SOURCE_MESSAGE_ID, items, TARGET_DATE, List.of());

        assertThat(response.getProposalId()).isEqualTo(PROPOSAL_ID);
        verify(persistenceService).save(eq(USER_ID), eq(CONVERSATION_ID), eq(SOURCE_MESSAGE_ID), any(), any());
    }

    /*
       ===== TIME_FIXED의 길이는 구간이 정한다 =====

       5~120분 상한은 "잘게 쪼갠 할 일"에 맞춘 값인데, 알바·수업처럼 이미 시각이 박힌
       일정에까지 걸려 있었다. 그 결과 모델이 검증을 통과하려고 시각은 진짜로 넣고
       expectedMinutes만 120으로 깎는 일이 실제로 일어났다 — 17:00~23:00짜리 알바가
       120분으로 저장돼 화면 표시와 하루 부하 계산이 어긋났다.
     */

    @Test
    void createFromItems_timeFixed_isNotBoundByTheTwoHourCap() {
        // 6시간짜리 알바. 상한에 걸려 제안 전체가 버려지면 안 된다.
        List<ProposalItem> items = List.of(new ProposalItem(
                "알바 근무", "설명", 120, "MUST", PlacementType.TIME_FIXED,
                LocalTime.of(17, 0), LocalTime.of(23, 0), null, null, null, null, null));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProposalItemPayload>> captor = ArgumentCaptor.forClass(List.class);
        when(persistenceService.save(eq(USER_ID), eq(CONVERSATION_ID), eq(SOURCE_MESSAGE_ID), captor.capture(), any()))
                .thenReturn(AiProposalResponse.builder().proposalId(PROPOSAL_ID).build());

        service.createFromItems(USER_ID, CONVERSATION_ID, SOURCE_MESSAGE_ID, items, TARGET_DATE, List.of());

        // 모델이 적어 낸 120이 아니라 구간에서 계산한 360이 저장된다.
        assertThat(captor.getValue().get(0).expectedMinutes()).isEqualTo(360);
    }

    @Test
    void createFromItems_timeFixed_overwritesModelMinutes_evenWhenWithinTheCap() {
        // 상한 안쪽 숫자라도 구간과 다르면 그대로 두지 않는다 — 두 출처가 어긋나는 것 자체가 버그다.
        List<ProposalItem> items = List.of(new ProposalItem(
                "스터디", "설명", 90, "SHOULD", PlacementType.TIME_FIXED,
                LocalTime.of(9, 0), LocalTime.of(9, 30), null, null, null, null, null));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProposalItemPayload>> captor = ArgumentCaptor.forClass(List.class);
        when(persistenceService.save(eq(USER_ID), eq(CONVERSATION_ID), eq(SOURCE_MESSAGE_ID), captor.capture(), any()))
                .thenReturn(AiProposalResponse.builder().proposalId(PROPOSAL_ID).build());

        service.createFromItems(USER_ID, CONVERSATION_ID, SOURCE_MESSAGE_ID, items, TARGET_DATE, List.of());

        assertThat(captor.getValue().get(0).expectedMinutes()).isEqualTo(30);
    }

    @Test
    void createFromItems_fixedStartAt_derivesMinutesFromTheSpan() {
        // 날짜+시각 고정 경로(fixedStartAt/fixedEndAt)도 같은 규칙을 따른다.
        List<ProposalItem> items = List.of(new ProposalItem(
                "알바 근무", "설명", 120, "MUST", PlacementType.DATE_ONLY, null, null, null, null,
                TARGET_DATE.atTime(18, 0), TARGET_DATE.atTime(23, 0), null));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProposalItemPayload>> captor = ArgumentCaptor.forClass(List.class);
        when(persistenceService.save(eq(USER_ID), eq(CONVERSATION_ID), eq(SOURCE_MESSAGE_ID), captor.capture(), any()))
                .thenReturn(AiProposalResponse.builder().proposalId(PROPOSAL_ID).build());

        service.createFromItems(USER_ID, CONVERSATION_ID, SOURCE_MESSAGE_ID, items, TARGET_DATE, List.of());

        ProposalItemPayload payload = captor.getValue().get(0);
        assertThat(payload.placementType()).isEqualTo(PlacementType.TIME_FIXED);
        assertThat(payload.expectedMinutes()).isEqualTo(300);
    }

    @Test
    void createFromItems_dateOnly_stillRejectsMinutesAboveTheCap() {
        // 상한이 사라진 것이 아니다 — 시각이 없는 후보에는 그대로 적용된다.
        List<ProposalItem> items = List.of(dateOnlyItem("제목", 360, "SHOULD"));

        assertThatThrownBy(() -> service.createFromItems(USER_ID, CONVERSATION_ID, SOURCE_MESSAGE_ID, items, TARGET_DATE, List.of()))
                .isInstanceOfSatisfying(ServiceUnavailableException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AI_GENERATION_FAILED));

        verify(persistenceService, never()).save(any(), any(), any(), any(), any());
    }

    // ===== targetDate 오염 방지(수정사항 4: 항목별 itemTargetDate가 메서드 파라미터를 오염시키지 않음) =====

    @Test
    void createFromItems_fixedStartAtItem_doesNotLeakDateToSubsequentDateOnlyItem() {
        LocalDateTime fixedStart = LocalDateTime.of(2026, 8, 10, 9, 0);
        LocalDateTime fixedEnd = LocalDateTime.of(2026, 8, 10, 9, 30);
        ProposalItem fixedItem = new ProposalItem(
                "고정 일정", "설명", 30, "MUST", null, null, null,
                null, null, fixedStart, fixedEnd, null);
        ProposalItem dateOnly = dateOnlyItem("날짜만 있는 항목", 20, "SHOULD");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProposalItemPayload>> captor = ArgumentCaptor.forClass(List.class);
        when(persistenceService.save(eq(USER_ID), eq(CONVERSATION_ID), eq(SOURCE_MESSAGE_ID), captor.capture(), any()))
                .thenReturn(AiProposalResponse.builder().proposalId(PROPOSAL_ID).build());

        service.createFromItems(USER_ID, CONVERSATION_ID, SOURCE_MESSAGE_ID,
                List.of(fixedItem, dateOnly), TARGET_DATE, List.of());

        List<ProposalItemPayload> saved = captor.getValue();
        // 1번 항목(fixedStartAt=8/10)은 그 날짜를 쓰고, 2번 DATE_ONLY 항목은 메서드에 전달된
        // 원래 targetDate(8/4)를 그대로 유지해야 한다 — 1번 처리 중 targetDate 파라미터 자체를
        // 바꿔버리면 2번도 8/10을 물려받는 오염이 생긴다.
        assertThat(saved.get(0).targetDate()).isEqualTo(LocalDate.of(2026, 8, 10));
        assertThat(saved.get(1).targetDate()).isEqualTo(TARGET_DATE);
    }

    @Test
    void createFromItems_dateOnlyBeforeFixedStartAtItem_keepsIndependentTargetDates() {
        ProposalItem dateOnly = dateOnlyItem("날짜만 있는 항목", 20, "SHOULD");
        LocalDateTime fixedStart = LocalDateTime.of(2026, 8, 10, 9, 0);
        LocalDateTime fixedEnd = LocalDateTime.of(2026, 8, 10, 9, 30);
        ProposalItem fixedItem = new ProposalItem(
                "고정 일정", "설명", 30, "MUST", null, null, null,
                null, null, fixedStart, fixedEnd, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProposalItemPayload>> captor = ArgumentCaptor.forClass(List.class);
        when(persistenceService.save(eq(USER_ID), eq(CONVERSATION_ID), eq(SOURCE_MESSAGE_ID), captor.capture(), any()))
                .thenReturn(AiProposalResponse.builder().proposalId(PROPOSAL_ID).build());

        // 순서를 반대로 바꿔도(먼저 DATE_ONLY, 그다음 fixedStartAt) 각 항목의 날짜가
        // 서로에게 영향을 주지 않아야 한다.
        service.createFromItems(USER_ID, CONVERSATION_ID, SOURCE_MESSAGE_ID,
                List.of(dateOnly, fixedItem), TARGET_DATE, List.of());

        List<ProposalItemPayload> saved = captor.getValue();
        assertThat(saved.get(0).targetDate()).isEqualTo(TARGET_DATE);
        assertThat(saved.get(1).targetDate()).isEqualTo(LocalDate.of(2026, 8, 10));
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
    void apply_passesTheEditedTimesThrough_soTheBoundaryCanDeriveMinutes() {
        /*
           편집으로 시각만 바꾸면 expectedMinutes는 원본 값(30)이 그대로 남는다. 길이를 맞추는
           일은 여기가 아니라 ExecutionItemService.createFromApprovedProposal이 한다
           (PlacementDuration). 여기서 확인할 것은 새 시각이 그 경계까지 그대로 전달되는지다 —
           실제 도출은 ExecutionItemServiceTest가 검증한다.
         */
        when(aiProposalMapper.findByIdAndUserIdForUpdate(PROPOSAL_ID, USER_ID)).thenReturn(proposedProposal());
        when(aiProposalItemMapper.findByProposalIdAndUserId(PROPOSAL_ID, USER_ID))
                .thenReturn(List.of(proposalItem(1L, samplePayloadJson())));
        when(executionItemService.nextOrderIndexStart(USER_ID, TARGET_DATE)).thenReturn(0);
        when(executionItemService.createFromApprovedProposal(
                any(), any(), any(), any(), anyInt(), any(), anyInt(), anyBoolean(), any(), any(), any()))
                .thenReturn(ExecutionItem.builder().executionItemId(501L).build());

        AiProposalApplyRequest request = new AiProposalApplyRequest();
        request.setEditedItems(List.of(AiProposalApplyRequest.EditedProposalItem.builder()
                .proposalItemId(1L)
                .placementType(PlacementType.TIME_FIXED)
                .scheduledStartAt(TARGET_DATE.atTime(17, 0))
                .scheduledEndAt(TARGET_DATE.atTime(23, 0))
                .build()));

        service.apply(PROPOSAL_ID, USER_ID, request);

        verify(executionItemService).createFromApprovedProposal(
                eq(USER_ID), any(), any(), eq(TARGET_DATE), anyInt(), any(), anyInt(), eq(true),
                eq(PlacementType.TIME_FIXED), eq(TARGET_DATE.atTime(17, 0)), eq(TARGET_DATE.atTime(23, 0)));
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
                null, null, null, null, null);
    }

    // ===== 기존 조각을 바꾸는 제안(REDUCE/MOVE/DROP) =====

    @Test
    void createFromItems_adjustment_recordsTargetAndBaseVersion() {
        ExecutionItem target = plannedItem(500L, 3L, "자료구조 복습", 30);
        when(executionItemService.findOwnedForAdjustment(500L, USER_ID)).thenReturn(target);
        when(persistenceService.save(any(), any(), any(), any(), any()))
                .thenReturn(AiProposalResponse.builder().proposalId(PROPOSAL_ID).build());

        service.createFromItems(USER_ID, CONVERSATION_ID, SOURCE_MESSAGE_ID, List.of(),
                List.of(new ProposalAdjustment(500L, ProposalOperation.REDUCE, 20, null, null, null, null, "피곤해서")),
                TARGET_DATE, List.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProposalItemPayload>> captor = ArgumentCaptor.forClass(List.class);
        verify(persistenceService).save(any(), any(), any(), captor.capture(), any());
        ProposalItemPayload payload = captor.getValue().get(0);
        assertThat(payload.effectiveOperation()).isEqualTo(ProposalOperation.REDUCE);
        assertThat(payload.targetExecutionItemId()).isEqualTo(500L);
        // 제안을 만든 시점의 버전을 기록해야 사용자가 그 사이 직접 고쳤을 때 적용이 막힌다.
        assertThat(payload.targetBaseVersion()).isEqualTo(3L);
        assertThat(payload.beforeExpectedMinutes()).isEqualTo(30);
        assertThat(payload.expectedMinutes()).isEqualTo(20);
    }

    @Test
    void createFromItems_adjustment_dropsCandidate_whenTargetNotAdjustable() {
        // 이미 끝났거나 없는 항목을 가리키면 그 후보만 버린다. 다른 후보가 있으면 제안은 살아남는다.
        when(executionItemService.findOwnedForAdjustment(999L, USER_ID)).thenReturn(null);
        when(persistenceService.save(any(), any(), any(), any(), any()))
                .thenReturn(AiProposalResponse.builder().proposalId(PROPOSAL_ID).build());

        service.createFromItems(USER_ID, CONVERSATION_ID, SOURCE_MESSAGE_ID,
                List.of(dateOnlyItem("새 항목", 30, "SHOULD")),
                List.of(new ProposalAdjustment(999L, ProposalOperation.REDUCE, 10, null, null, null, null, "이유")),
                TARGET_DATE, List.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProposalItemPayload>> captor = ArgumentCaptor.forClass(List.class);
        verify(persistenceService).save(any(), any(), any(), captor.capture(), any());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).isAdjustment()).isFalse();
    }

    @Test
    void createFromItems_fails_whenNothingLeftAfterDroppingInvalidAdjustments() {
        when(executionItemService.findOwnedForAdjustment(999L, USER_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.createFromItems(USER_ID, CONVERSATION_ID, SOURCE_MESSAGE_ID, List.of(),
                List.of(new ProposalAdjustment(999L, ProposalOperation.DROP, null, null, null, null, null, "이유")),
                TARGET_DATE, List.of()))
                .isInstanceOf(ServiceUnavailableException.class);

        verify(persistenceService, never()).save(any(), any(), any(), any(), any());
    }

    @Test
    void createFromItems_dropsMoveCandidate_whenTargetDateUnchanged() {
        ExecutionItem target = plannedItem(500L, 0L, "자료구조 복습", 30);
        when(executionItemService.findOwnedForAdjustment(500L, USER_ID)).thenReturn(target);

        assertThatThrownBy(() -> service.createFromItems(USER_ID, CONVERSATION_ID, SOURCE_MESSAGE_ID, List.of(),
                List.of(new ProposalAdjustment(500L, ProposalOperation.MOVE, null, null, TARGET_DATE, null, null, "이유")),
                TARGET_DATE, List.of()))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void createFromItems_keepsMoveCandidate_whenSameDayButTimeMovesLater() {
        // "오전에 못 한 것을 오늘 16시로" — 날짜는 그대로지만 언제 할지가 달라졌으므로 유효한 이동이다.
        ExecutionItem target = timeFixedItem(500L, 1L, 10, 0, 10, 30);
        when(executionItemService.findOwnedForAdjustment(500L, USER_ID)).thenReturn(target);
        when(persistenceService.save(any(), any(), any(), any(), any()))
                .thenReturn(AiProposalResponse.builder().proposalId(PROPOSAL_ID).build());

        service.createFromItems(USER_ID, CONVERSATION_ID, SOURCE_MESSAGE_ID, List.of(),
                List.of(new ProposalAdjustment(500L, ProposalOperation.MOVE, null, null, TARGET_DATE,
                        LocalTime.of(16, 0), LocalTime.of(16, 30), "남은 시간에 맞춰 뒤로")),
                TARGET_DATE, List.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProposalItemPayload>> captor = ArgumentCaptor.forClass(List.class);
        verify(persistenceService).save(any(), any(), any(), captor.capture(), any());
        ProposalItemPayload payload = captor.getValue().get(0);
        assertThat(payload.effectiveOperation()).isEqualTo(ProposalOperation.MOVE);
        assertThat(payload.targetDate()).isEqualTo(TARGET_DATE);
        assertThat(payload.scheduledStartAt()).isEqualTo(TARGET_DATE.atTime(16, 0));
        assertThat(payload.scheduledEndAt()).isEqualTo(TARGET_DATE.atTime(16, 30));
    }

    @Test
    void createFromItems_dropsMoveCandidate_whenTimeGivenForItemWithoutFixedTime() {
        // 시각 없는 항목에 시각을 붙이는 것은 이동이 아니라 배치 형식 변경이다 — 조용히 버린다.
        ExecutionItem target = plannedItem(500L, 0L, "자료구조 복습", 30);
        when(executionItemService.findOwnedForAdjustment(500L, USER_ID)).thenReturn(target);

        assertThatThrownBy(() -> service.createFromItems(USER_ID, CONVERSATION_ID, SOURCE_MESSAGE_ID, List.of(),
                List.of(new ProposalAdjustment(500L, ProposalOperation.MOVE, null, null, TARGET_DATE,
                        LocalTime.of(16, 0), LocalTime.of(16, 30), "이유")),
                TARGET_DATE, List.of()))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void apply_moveAdjustment_passesTimesToDomainAction_forSameDayReschedule() {
        String payload = adjustPayloadJson("MOVE", 500L, 2L, 30, null,
                TARGET_DATE + "T16:00:00", TARGET_DATE + "T16:30:00");
        when(aiProposalMapper.findByIdAndUserIdForUpdate(PROPOSAL_ID, USER_ID)).thenReturn(proposedProposal());
        when(aiProposalItemMapper.findByProposalIdAndUserId(PROPOSAL_ID, USER_ID))
                .thenReturn(List.of(proposalItem(1L, payload)));

        service.apply(PROPOSAL_ID, USER_ID, AiProposalApplyRequest.builder().build());

        ArgumentCaptor<ExecutionItemMoveRequest> captor = ArgumentCaptor.forClass(ExecutionItemMoveRequest.class);
        verify(executionItemService).move(eq(500L), eq(USER_ID), captor.capture());
        assertThat(captor.getValue().getToDate()).isEqualTo(TARGET_DATE);
        assertThat(captor.getValue().getStartTime()).isEqualTo(LocalTime.of(16, 0));
        assertThat(captor.getValue().getEndTime()).isEqualTo(LocalTime.of(16, 30));
        assertThat(captor.getValue().getVersion()).isEqualTo(2L);
    }

    @Test
    void apply_reduceAdjustment_callsDomainActionWithBaseVersion_andCreatesNoNewItem() {
        String payload = adjustPayloadJson("REDUCE", 500L, 3L, 20, null);
        when(aiProposalMapper.findByIdAndUserIdForUpdate(PROPOSAL_ID, USER_ID)).thenReturn(proposedProposal());
        when(aiProposalItemMapper.findByProposalIdAndUserId(PROPOSAL_ID, USER_ID))
                .thenReturn(List.of(proposalItem(1L, payload)));

        service.apply(PROPOSAL_ID, USER_ID, AiProposalApplyRequest.builder().build());

        ArgumentCaptor<ExecutionItemReduceRequest> captor = ArgumentCaptor.forClass(ExecutionItemReduceRequest.class);
        verify(executionItemService).reduce(eq(500L), eq(USER_ID), captor.capture());
        assertThat(captor.getValue().getVersion()).isEqualTo(3L);
        assertThat(captor.getValue().getExpectedMinutes()).isEqualTo(20);
        // 조정 제안은 절대 새 실행 조각을 만들지 않는다.
        verify(executionItemService, never()).createFromApprovedProposal(
                any(), any(), any(), any(), any(), any(), anyInt(), anyBoolean(), any(), any(), any());
    }

    @Test
    void apply_moveAdjustment_usesUserEditedDate() {
        String payload = adjustPayloadJson("MOVE", 500L, 2L, 30, "2026-08-05");
        when(aiProposalMapper.findByIdAndUserIdForUpdate(PROPOSAL_ID, USER_ID)).thenReturn(proposedProposal());
        when(aiProposalItemMapper.findByProposalIdAndUserId(PROPOSAL_ID, USER_ID))
                .thenReturn(List.of(proposalItem(1L, payload)));

        service.apply(PROPOSAL_ID, USER_ID, AiProposalApplyRequest.builder()
                .editedItems(List.of(AiProposalApplyRequest.EditedProposalItem.builder()
                        .proposalItemId(1L).scheduledDate(LocalDate.of(2026, 8, 7)).build()))
                .build());

        ArgumentCaptor<ExecutionItemMoveRequest> captor = ArgumentCaptor.forClass(ExecutionItemMoveRequest.class);
        verify(executionItemService).move(eq(500L), eq(USER_ID), captor.capture());
        assertThat(captor.getValue().getToDate()).isEqualTo(LocalDate.of(2026, 8, 7));
    }

    @Test
    void apply_dropAdjustment_holdsInsteadOfDeleting() {
        String payload = adjustPayloadJson("DROP", 500L, 1L, 30, null);
        when(aiProposalMapper.findByIdAndUserIdForUpdate(PROPOSAL_ID, USER_ID)).thenReturn(proposedProposal());
        when(aiProposalItemMapper.findByProposalIdAndUserId(PROPOSAL_ID, USER_ID))
                .thenReturn(List.of(proposalItem(1L, payload)));

        service.apply(PROPOSAL_ID, USER_ID, AiProposalApplyRequest.builder().build());

        verify(executionItemService).hold(eq(500L), eq(USER_ID), any(ExecutionItemHoldRequest.class));
        verify(executionItemService, never()).delete(any(), any(), any());
    }

    private ExecutionItem plannedItem(Long id, Long version, String title, Integer minutes) {
        return ExecutionItem.builder()
                .executionItemId(id).userId(USER_ID).version(version)
                .title(title).expectedMinutes(minutes)
                .priority(ExecutionPriority.SHOULD)
                .placementType(PlacementType.DATE_ONLY)
                .scheduledDate(TARGET_DATE)
                .build();
    }

    private ExecutionItem timeFixedItem(Long id, Long version, int startHour, int startMinute, int endHour, int endMinute) {
        ExecutionItem item = plannedItem(id, version, "자료구조 복습", 30);
        item.setPlacementType(PlacementType.TIME_FIXED);
        item.setScheduledStartAt(TARGET_DATE.atTime(startHour, startMinute));
        item.setScheduledEndAt(TARGET_DATE.atTime(endHour, endMinute));
        return item;
    }

    /** 조정 제안이 저장된 모습 그대로의 JSON. 예전 payload와 섞여도 읽히는지까지 이 문자열이 지킨다. */
    private String adjustPayloadJson(String operation, Long targetId, Long baseVersion, Integer minutes, String targetDate) {
        return adjustPayloadJson(operation, targetId, baseVersion, minutes, targetDate, null, null);
    }

    private String adjustPayloadJson(String operation, Long targetId, Long baseVersion, Integer minutes,
                                     String targetDate, String startAt, String endAt) {
        return "{\"title\":\"자료구조 복습\",\"description\":null,\"expectedMinutes\":" + minutes
                + ",\"priority\":\"SHOULD\",\"targetDate\":\"" + (targetDate != null ? targetDate : TARGET_DATE)
                + "\",\"placementType\":null,"
                + "\"scheduledStartAt\":" + (startAt != null ? "\"" + startAt + "\"" : "null") + ","
                + "\"scheduledEndAt\":" + (endAt != null ? "\"" + endAt + "\"" : "null") + ","
                + "\"operation\":\"" + operation + "\",\"targetExecutionItemId\":" + targetId
                + ",\"targetBaseVersion\":" + baseVersion
                + ",\"beforeTitle\":\"자료구조 복습\",\"beforeExpectedMinutes\":30,"
                + "\"beforeScheduledDate\":\"" + TARGET_DATE + "\",\"reason\":\"피곤해서\"}";
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
