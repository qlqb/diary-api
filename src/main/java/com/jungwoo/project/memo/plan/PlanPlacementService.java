package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.execution.ExecutionItemMapper;
import com.jungwoo.project.memo.execution.ExecutionItemService;
import com.jungwoo.project.memo.execution.domain.ExecutionItem;
import com.jungwoo.project.memo.execution.domain.ExecutionPriority;
import com.jungwoo.project.memo.plan.domain.PlanVersion;
import com.jungwoo.project.memo.plan.dto.PlanPlacementResponse;
import com.jungwoo.project.memo.scheduling.domain.AvailabilityWindow;
import com.jungwoo.project.memo.scheduling.domain.BusyWindow;
import com.jungwoo.project.memo.scheduling.domain.SchedulingContext;
import com.jungwoo.project.memo.scheduling.domain.SchedulingPlan;
import com.jungwoo.project.memo.scheduling.domain.SchedulingTask;
import com.jungwoo.project.memo.scheduling.domain.TimeSlotOption;
import com.jungwoo.project.memo.scheduling.service.AvailabilityEstimateResult;
import com.jungwoo.project.memo.scheduling.service.AvailabilityEstimateService;
import com.jungwoo.project.memo.scheduling.solver.SchedulingSolverService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 롤링 배치 — 장기 계획의 시각을 정하는 유일한 경로다.
 *
 * 3주 뒤의 14시를 지금 정하는 것은 정보가 없어 의미가 없다. 그래서 시각은 다가온 창(최대
 * 7일)에 대해서만 정하고, 나머지는 UNSCHEDULED + planning_* 로 남겨둔다. 다음 주가 되면
 * 다시 부른다.
 *
 * ★ SchedulePreviewService.computePreview를 쓰지 않는다. 그쪽은 proposalId가 필수이고
 * ai_proposal_schedule_previews에 미리보기를 영속한다 — 확정이 끝난 계획을 배치하려고 제안을
 * 다시 만들고 미리보기 행까지 남기는 것은 앞뒤가 뒤집힌 구조다. 대신 그 아래의 두 조각
 * (AvailabilityEstimateService, SchedulingSolverService)을 직접 쓴다. preview 경로는 손대지
 * 않았다.
 *
 * 후보 슬롯 생성 로직은 preview와 같은 규칙을 따르지만 코드를 공유하지 않는다. 공유하려면
 * preview의 private 메서드를 밖으로 끌어내야 하는데, 그 리팩터링이 preview 동작을 건드릴
 * 위험이 여기서 30줄을 다시 쓰는 비용보다 크다고 판단했다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanPlacementService {

    /** 창 길이. 솔버의 7일 지평과 맞춘다. */
    private static final int WINDOW_DAYS = 7;

    private final PlanVersionService planVersionService;
    private final AvailabilityEstimateService availabilityEstimateService;
    private final SchedulingSolverService solverService;
    private final ExecutionItemMapper executionItemMapper;
    private final ExecutionItemService executionItemService;
    private final Clock clock;

    @Value("${scheduling.availability.slot-granularity-minutes:15}")
    private int slotGranularityMinutes = 15;

    @Value("${ai.context.default-time-zone:Asia/Seoul}")
    private String defaultTimeZoneId = "Asia/Seoul";

    @Transactional
    public PlanPlacementResponse place(Long userId, Long planVersionId, LocalDate requestedStart) {
        PlanVersion plan = planVersionService.getOwned(userId, planVersionId);

        ZoneId zone = ZoneId.of(defaultTimeZoneId);
        LocalDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(zone).toLocalDateTime();
        LocalDate today = now.toLocalDate();

        // 창 시작은 오늘보다 과거일 수 없다 — 지난 시각에 배치해도 할 수 없다.
        LocalDate windowStart = requestedStart != null && requestedStart.isAfter(today) ? requestedStart : today;
        if (windowStart.isBefore(plan.getStartDate())) {
            windowStart = plan.getStartDate();
        }
        if (windowStart.isAfter(plan.getEndDate())) {
            // 계획이 이미 끝났다. 배치할 창이 없다.
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }
        LocalDate windowEnd = windowStart.plusDays(WINDOW_DAYS - 1L);
        if (windowEnd.isAfter(plan.getEndDate())) {
            windowEnd = plan.getEndDate();
        }

        // planKey로 거른다 — plan_version_id는 불변 출처라, 재계획으로 v2가 생기면
        // v1이 만든 조각을 v2 화면·배치가 놓친다.
        List<ExecutionItem> targets = executionItemMapper.findUnscheduledByPlanKey(
                userId, plan.getPlanKey(), windowStart, windowEnd);

        List<PlanPlacementResponse.PlacedItem> placed = new ArrayList<>();
        List<PlanPlacementResponse.UnplacedItem> unplaced = new ArrayList<>();

        if (targets.isEmpty()) {
            return response(planVersionId, windowStart, windowEnd, placed, unplaced);
        }

        // 가용시간은 기존 서비스를 그대로 쓴다. 기존 TIME_FIXED 조각을 busy로 차감하는 것도
        // 그 안에서 이미 한다 — 여기서 다시 조회하지 않는다.
        AvailabilityEstimateResult availability = availabilityEstimateService.estimate(
                userId, windowStart, windowEnd, List.of(), List.of());

        List<SchedulingTask> tasks = new ArrayList<>();
        for (ExecutionItem item : targets) {
            int duration = item.getExpectedMinutes() != null ? item.getExpectedMinutes() : 30;
            List<TimeSlotOption> candidates = buildCandidates(
                    availability.windows(), duration, now, windowEnd);
            if (candidates.isEmpty()) {
                unplaced.add(toUnplaced(item));
                continue;
            }
            tasks.add(new SchedulingTask(item.getExecutionItemId(), item.getTitle(), duration,
                    item.getPriority() != null ? item.getPriority() : ExecutionPriority.SHOULD,
                    null, candidates));
        }

        if (!tasks.isEmpty()) {
            SchedulingContext context = new SchedulingContext(
                    now, windowStart.atStartOfDay(), windowEnd.plusDays(1).atStartOfDay());
            SchedulingPlan solved = solverService.solve(
                    new SchedulingPlan(tasks, new ArrayList<>(availability.busyWindows()), context));

            for (SchedulingTask task : solved.getTasks()) {
                ExecutionItem item = findById(targets, task.getProposalItemId());
                if (!task.isScheduled()) {
                    unplaced.add(toUnplaced(item));
                    continue;
                }
                // 전이 규칙(§2-5)과 이벤트 기록, 영향 행 수 검증을 한곳에서 한다 —
                // 실행 조각 변경과 이력 기록은 ExecutionItemService가 소유한다.
                executionItemService.applyRollingPlacement(userId, item.getExecutionItemId(),
                        task.getAssignedStart().toLocalDate(),
                        task.getAssignedStart(), task.getAssignedEnd());
                placed.add(PlanPlacementResponse.PlacedItem.builder()
                        .executionItemId(item.getExecutionItemId())
                        .title(item.getTitle())
                        .scheduledDate(task.getAssignedStart().toLocalDate())
                        .scheduledStartAt(task.getAssignedStart())
                        .scheduledEndAt(task.getAssignedEnd())
                        .build());
            }
        }

        log.info("롤링 배치: userId={}, planVersionId={}, 창={}~{}, 대상={}개, 배치={}개, 미배치 유지={}개",
                userId, planVersionId, windowStart, windowEnd, targets.size(), placed.size(), unplaced.size());
        return response(planVersionId, windowStart, windowEnd, placed, unplaced);
    }

    /**
     * 가용시간 구간을 슬롯 단위로 끊어 후보 시각을 만든다. preview와 같은 규칙이다 —
     * 가용시간 안, 현재 시각 이후, 창 안에서 끝나는 시각만 후보가 된다.
     */
    private List<TimeSlotOption> buildCandidates(
            List<AvailabilityWindow> windows, int durationMinutes,
            LocalDateTime earliest, LocalDate windowEnd
    ) {
        LocalDateTime windowEndExclusive = windowEnd.plusDays(1).atStartOfDay();
        List<TimeSlotOption> options = new ArrayList<>();
        Set<LocalDateTime> seen = new HashSet<>();
        for (AvailabilityWindow window : windows) {
            LocalDateTime latestStart = window.endAt().minusMinutes(durationMinutes);
            if (latestStart.isBefore(window.startAt())) {
                continue;
            }
            LocalDateTime cursor = window.startAt().isBefore(earliest) ? earliest : window.startAt();
            cursor = roundUpToGranularity(cursor);
            while (!cursor.isAfter(latestStart)) {
                if (!cursor.plusMinutes(durationMinutes).isAfter(windowEndExclusive) && seen.add(cursor)) {
                    options.add(new TimeSlotOption(cursor, window.confidence()));
                }
                cursor = cursor.plusMinutes(slotGranularityMinutes);
            }
        }
        return options;
    }

    private LocalDateTime roundUpToGranularity(LocalDateTime time) {
        LocalDateTime truncated = time.withSecond(0).withNano(0);
        int minute = truncated.getMinute();
        int remainder = minute % slotGranularityMinutes;
        return remainder == 0 ? truncated : truncated.plusMinutes(slotGranularityMinutes - remainder);
    }

    private ExecutionItem findById(List<ExecutionItem> items, Long executionItemId) {
        return items.stream()
                .filter(i -> i.getExecutionItemId().equals(executionItemId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("배치 대상에 없는 항목: " + executionItemId));
    }

    private PlanPlacementResponse.UnplacedItem toUnplaced(ExecutionItem item) {
        return PlanPlacementResponse.UnplacedItem.builder()
                .executionItemId(item.getExecutionItemId())
                .title(item.getTitle())
                .expectedMinutes(item.getExpectedMinutes())
                .build();
    }

    private PlanPlacementResponse response(
            Long planVersionId, LocalDate windowStart, LocalDate windowEnd,
            List<PlanPlacementResponse.PlacedItem> placed,
            List<PlanPlacementResponse.UnplacedItem> unplaced
    ) {
        return PlanPlacementResponse.builder()
                .planVersionId(planVersionId)
                .windowStart(windowStart)
                .windowEnd(windowEnd)
                .placed(placed)
                .unplaced(unplaced)
                .build();
    }
}
