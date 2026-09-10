package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.ai.dto.AiProposalApplyRequest;
import com.jungwoo.project.memo.commitment.CommitmentMapper;
import com.jungwoo.project.memo.commitment.domain.Commitment;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.execution.ExecutionItemMapper;
import com.jungwoo.project.memo.execution.domain.ExecutionItem;
import com.jungwoo.project.memo.execution.domain.PlacementType;
import com.jungwoo.project.memo.routine.RoutineOccurrenceService;
import com.jungwoo.project.memo.routine.domain.RoutineOccurrence;
import com.jungwoo.project.memo.scheduling.domain.BusySource;
import com.jungwoo.project.memo.scheduling.domain.BusyWindow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 확정 직전, 시각이 정해진 항목이 <b>지금의</b> 일정과 겹치지 않는지 본다.
 *
 * <p>확정 요청의 시각은 배치 미리보기가 정한 값이다. 미리보기와 확정 사이에는 시간이
 * 흐르고, 그 사이 근무가 추가되거나 옮겨질 수 있다. 미리보기는 그때의 일정을 봤을 뿐이라
 * 그대로 확정하면 사용자는 근무와 겹친 학습 조각을 나중에 발견한다.
 *
 * <p>★ 하나라도 겹치면 확정 전체를 거절한다. 겹친 항목만 빼고 확정하면 사용자가 승인한
 * 계획과 저장된 계획이 달라지고, 그 차이를 사용자가 알 길이 없다. 거절 응답은 무엇이
 * 무엇과 겹쳤는지 말하고, 화면은 미리보기를 다시 계산해 보여준다.
 *
 * <p>★ 약속과 시각이 박힌 조각은 <b>잠금 조회</b>(FOR UPDATE)로 읽는다. 일반 조회는 이
 * 트랜잭션이 처음 읽은 시점의 스냅샷이라, 그 뒤에 커밋된 약속을 보지 못한다. 잠금 조회는
 * 최신 커밋을 읽고, 아직 커밋되지 않은 겹치는 INSERT가 있으면 그것이 끝날 때까지 기다린다.
 * 반복 일정은 여기서 잠그지 않는다 — 발생분에는 행이 없고, 시간표는 확정 사이에 바뀌는
 * 일이 드물다. 그 경우는 최신 커밋 기준으로만 본다.
 *
 * <p>검사 대상은 이번 요청이 TIME_FIXED로 보낸 항목뿐이다. 날짜만 있는 항목과 미배치
 * 항목은 겹칠 시각이 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlanConfirmScheduleGuard {

    private final CommitmentMapper commitmentMapper;
    private final ExecutionItemMapper executionItemMapper;
    private final RoutineOccurrenceService routineOccurrenceService;

    /**
     * @throws ConflictException 겹치는 항목이 하나라도 있으면. details에 겹침 목록이 실린다
     */
    public void ensureNoConflicts(Long userId, LocalDate planStart, LocalDate planEnd,
                                  List<AiProposalApplyRequest.EditedProposalItem> editedItems) {
        List<AiProposalApplyRequest.EditedProposalItem> timed = new ArrayList<>();
        for (AiProposalApplyRequest.EditedProposalItem edit : editedItems == null ? List.<AiProposalApplyRequest.EditedProposalItem>of() : editedItems) {
            if (edit != null && edit.getPlacementType() == PlacementType.TIME_FIXED
                    && edit.getScheduledStartAt() != null && edit.getScheduledEndAt() != null) {
                timed.add(edit);
            }
        }
        if (timed.isEmpty()) {
            return;
        }

        // 확정 항목이 계획 기간 밖 시각을 가질 수도 있으므로 조회 범위는 항목 시각으로 잡는다.
        LocalDate from = planStart;
        LocalDate to = planEnd;
        for (AiProposalApplyRequest.EditedProposalItem edit : timed) {
            LocalDate start = edit.getScheduledStartAt().toLocalDate();
            LocalDate end = edit.getScheduledEndAt().toLocalDate();
            if (from == null || start.isBefore(from)) {
                from = start;
            }
            if (to == null || end.isAfter(to)) {
                to = end;
            }
        }

        List<BusyWindow> busy = currentBusyWindows(userId, from, to);
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (AiProposalApplyRequest.EditedProposalItem edit : timed) {
            for (BusyWindow window : busy) {
                if (window.overlaps(edit.getScheduledStartAt(), edit.getScheduledEndAt())) {
                    Map<String, Object> conflict = new LinkedHashMap<>();
                    conflict.put("proposalItemId", edit.getProposalItemId());
                    conflict.put("scheduledStartAt", edit.getScheduledStartAt());
                    conflict.put("scheduledEndAt", edit.getScheduledEndAt());
                    conflict.put("busyLabel", window.label());
                    conflict.put("busySource", window.source());
                    conflict.put("busyStartAt", window.startAt());
                    conflict.put("busyEndAt", window.endAt());
                    conflicts.add(conflict);
                }
            }
        }
        if (!conflicts.isEmpty()) {
            log.warn("계획 확정 거절: 미리보기 이후 일정과 겹치는 항목 {}건. userId={}", conflicts.size(), userId);
            throw new ConflictException(ErrorCode.PLAN_CONFIRM_SCHEDULE_CONFLICT, conflicts);
        }
    }

    /**
     * 지금 이 사용자의 막힌 시간. 가용시간 추정(AvailabilityEstimateService)과 같은 세 원천을
     * 보되, 약속과 조각은 잠금 조회로 읽는다.
     */
    private List<BusyWindow> currentBusyWindows(Long userId, LocalDate from, LocalDate to) {
        List<BusyWindow> windows = new ArrayList<>();
        for (Commitment commitment : commitmentMapper.findOverlappingForUpdate(
                userId, from.atStartOfDay(), to.plusDays(1).atStartOfDay())) {
            windows.add(new BusyWindow(commitment.getStartAt(), commitment.getEndAt(), commitment.getTitle(),
                    BusySource.COMMITMENT, commitment.getCommitmentId()));
        }
        for (ExecutionItem item : executionItemMapper.findTimeFixedByUserIdAndDateRangeForUpdate(userId, from, to)) {
            windows.add(new BusyWindow(item.getScheduledStartAt(), item.getScheduledEndAt(), item.getTitle(),
                    BusySource.EXECUTION_ITEM, item.getExecutionItemId()));
        }
        for (RoutineOccurrence occurrence : routineOccurrenceService.expand(userId, from, to)) {
            windows.add(new BusyWindow(occurrence.startAt(), occurrence.endAt(), occurrence.title(),
                    BusySource.ROUTINE_OCCURRENCE, occurrence.routineId()));
        }
        return windows;
    }
}
