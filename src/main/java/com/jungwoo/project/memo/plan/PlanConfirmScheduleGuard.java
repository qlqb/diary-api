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
 * <p>★ 약속·시각이 박힌 조각·반복 일정(본체·요일·예외) 전부를 <b>잠금 조회</b>(FOR UPDATE)로
 * 읽는다. 일반 조회는 이 트랜잭션이 처음 읽은 시점의 스냅샷(REPEATABLE READ)이라, 확정이
 * 시작된 뒤에 커밋된 약속·수업 시각 변경을 보지 못한다. 잠금 조회는 최신 커밋을 읽고, 아직
 * 커밋되지 않은 겹치는 변경(INSERT 포함)이 있으면 그것이 끝날 때까지 기다린다.
 *
 * <p>보장하는 순서: 일정 변경이 먼저 커밋됐으면 확정이 그 변경을 보고 거절한다. 확정이 먼저
 * 잠금을 잡았으면 뒤따르는 변경(루틴 수정·예외·새 루틴 INSERT·약속 INSERT)은 확정이 끝난 뒤
 * 진행되고, 그 뒤의 일은 기존 일정 변경 정책대로다 — 여기서 미래 일정 편집을 막지 않는다.
 * 잠금은 그 사용자의 행에만 걸린다. 잠금 순서는 약속 → 조각 → 루틴이고 각 표 안에서는 id
 * 오름차순이다.
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
        ensureNoConflicts(userId, planStart, planEnd, editedItems, null);
    }

    /**
     * @param condition 초안에 고정한 말한 시간 조건. 범위가 있으면 각 항목이 그 범위 안이고 제외 구간과 겹치지 않아야 한다. 확정할
     *                  항목끼리도 겹치면 안 된다. 없으면 null(기존 검사만)
     */
    public void ensureNoConflicts(Long userId, LocalDate planStart, LocalDate planEnd,
                                  List<AiProposalApplyRequest.EditedProposalItem> editedItems,
                                  com.jungwoo.project.memo.ai.brief.StudyTimeCondition condition) {
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
        conflicts.addAll(studyTimeViolations(timed, condition, from, to));
        if (!conflicts.isEmpty()) {
            log.warn("계획 확정 거절: 미리보기 이후 일정과 겹치는 항목 {}건. userId={}", conflicts.size(), userId);
            throw new ConflictException(ErrorCode.PLAN_CONFIRM_SCHEDULE_CONFLICT, conflicts);
        }
    }

    /** 말한 범위 밖·제외 구간·확정 항목끼리의 겹침. 같은 409 목록에 reason으로 구분해 싣는다. */
    static List<Map<String, Object>> studyTimeViolations(List<AiProposalApplyRequest.EditedProposalItem> timed,
                                                         com.jungwoo.project.memo.ai.brief.StudyTimeCondition condition,
                                                         LocalDate from, LocalDate to) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < timed.size(); i++) {
            for (int j = i + 1; j < timed.size(); j++) {
                var a = timed.get(i);
                var b = timed.get(j);
                if (a.getScheduledStartAt().isBefore(b.getScheduledEndAt()) && b.getScheduledStartAt().isBefore(a.getScheduledEndAt())) {
                    out.add(violation(a, "ITEM_OVERLAP", "다른 확정 항목과 겹친다"));
                }
            }
        }
        var study = StudyTimeBudget.windowsFor(condition, from, to);
        if (study == null) {
            return out;
        }
        java.util.Set<LocalDate> covered = new java.util.HashSet<>();
        study.windows().forEach(w -> covered.add(w.start().toLocalDate()));
        for (var edit : timed) {
            boolean dayCovered = covered.contains(edit.getScheduledStartAt().toLocalDate())
                    || covered.contains(edit.getScheduledStartAt().toLocalDate().minusDays(1));
            boolean inside = study.windows().stream().anyMatch(w -> !edit.getScheduledStartAt().isBefore(w.start())
                    && !edit.getScheduledEndAt().isAfter(w.end()));
            if (dayCovered && !study.windows().isEmpty() && !inside) {
                out.add(violation(edit, "OUTSIDE_STUDY_TIME", "말한 공부 시간 밖이다"));
            }
            for (var x : study.exclusions()) {
                if (edit.getScheduledStartAt().isBefore(x.end()) && x.start().isBefore(edit.getScheduledEndAt())) {
                    out.add(violation(edit, "IN_EXCLUSION", "말한 제외 시간(" + x.start().toLocalTime() + "~"
                            + x.end().toLocalTime() + ")과 겹친다"));
                }
            }
        }
        return out;
    }

    private static Map<String, Object> violation(AiProposalApplyRequest.EditedProposalItem edit, String reason, String label) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("proposalItemId", edit.getProposalItemId());
        v.put("scheduledStartAt", edit.getScheduledStartAt());
        v.put("scheduledEndAt", edit.getScheduledEndAt());
        v.put("reason", reason);
        v.put("busyLabel", label);
        return v;
    }

    /**
     * 지금 이 사용자의 막힌 시간. 가용시간 추정(AvailabilityEstimateService)과 같은 세 원천을
     * 보되, 셋 다 잠금 조회로 읽는다. 반복 발생분 계산은 화면·배치와 같은 expand다.
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
        for (RoutineOccurrence occurrence : routineOccurrenceService.expandForUpdate(userId, from, to)) {
            windows.add(new BusyWindow(occurrence.startAt(), occurrence.endAt(), occurrence.title(),
                    BusySource.ROUTINE_OCCURRENCE, occurrence.routineId()));
        }
        return windows;
    }
}
