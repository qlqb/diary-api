package com.jungwoo.project.memo.scheduling.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.ai.AiProposalItemMapper;
import com.jungwoo.project.memo.ai.AiProposalMapper;
import com.jungwoo.project.memo.ai.domain.AiProposal;
import com.jungwoo.project.memo.ai.domain.AiProposalItem;
import com.jungwoo.project.memo.ai.domain.AiProposalItemStatus;
import com.jungwoo.project.memo.ai.dto.ProposalItemPayload;
import com.jungwoo.project.memo.ai.dto.UnavailableWindowSpec;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.execution.domain.ExecutionPriority;
import com.jungwoo.project.memo.execution.domain.PlacementType;
import com.jungwoo.project.memo.scheduling.SchedulePreviewMapper;
import com.jungwoo.project.memo.scheduling.domain.AiProposalSchedulePreview;
import com.jungwoo.project.memo.scheduling.domain.AvailabilityConfidence;
import com.jungwoo.project.memo.scheduling.domain.AvailabilityWindow;
import com.jungwoo.project.memo.scheduling.domain.BusyWindow;
import com.jungwoo.project.memo.scheduling.domain.SchedulingContext;
import com.jungwoo.project.memo.scheduling.domain.SchedulingPlan;
import com.jungwoo.project.memo.scheduling.domain.SchedulingTask;
import com.jungwoo.project.memo.scheduling.domain.TimeSlotOption;
import com.jungwoo.project.memo.scheduling.dto.AvailabilityOverrideRequest;
import com.jungwoo.project.memo.scheduling.dto.AvailabilityWindowDto;
import com.jungwoo.project.memo.scheduling.dto.PlacedItemDto;
import com.jungwoo.project.memo.scheduling.dto.ScheduleItemOverride;
import com.jungwoo.project.memo.scheduling.dto.SchedulePreviewRequest;
import com.jungwoo.project.memo.scheduling.dto.SchedulePreviewResponse;
import com.jungwoo.project.memo.scheduling.dto.UnplacedItemDto;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 제안의 "새 PROPOSAL 항목 → 7일 범위 가용시간 추정 → Timefold 배치 → 미리보기" 전체를
 * 조정한다. 이 서비스는 OpenAI를 절대 호출하지 않는다 — 사용자가 가용시간을 고치고 "다시
 * 배치"를 눌러도 여기서는 Timefold만 다시 돈다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SchedulePreviewService {

    private final AiProposalMapper aiProposalMapper;
    private final AiProposalItemMapper aiProposalItemMapper;
    private final SchedulePreviewMapper schedulePreviewMapper;
    private final AvailabilityEstimateService availabilityEstimateService;
    private final SchedulingSolverService solverService;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Value("${scheduling.horizon.max-days:7}")
    private int maxHorizonDays = 7;

    @Value("${scheduling.availability.slot-granularity-minutes:15}")
    private int slotGranularityMinutes = 15;

    @Value("${scheduling.availability.default-time-zone:Asia/Seoul}")
    private String defaultTimeZoneId = "Asia/Seoul";

    @Transactional
    public SchedulePreviewResponse computePreview(Long userId, Long proposalId, SchedulePreviewRequest request) {
        AiProposal proposal = aiProposalMapper.findByIdAndUserId(proposalId, userId);
        if (proposal == null) {
            throw new NotFoundException(ErrorCode.AI_PROPOSAL_NOT_FOUND);
        }

        List<AiProposalItem> openItems = aiProposalItemMapper.findByProposalIdAndUserId(proposalId, userId).stream()
                .filter(item -> item.getStatus() == AiProposalItemStatus.PROPOSED)
                .toList();

        ZoneId zone = ZoneId.of(defaultTimeZoneId);
        LocalDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(zone).toLocalDateTime();
        LocalDate today = now.toLocalDate();

        LocalDate horizonStart = request.getHorizonStart() != null && request.getHorizonStart().isAfter(today)
                ? request.getHorizonStart() : today;
        LocalDate maxHorizonEnd = horizonStart.plusDays(maxHorizonDays - 1L);
        LocalDate horizonEnd = request.getHorizonEnd() != null && request.getHorizonEnd().isBefore(maxHorizonEnd)
                ? request.getHorizonEnd() : maxHorizonEnd;
        if (horizonEnd.isBefore(horizonStart)) {
            horizonEnd = horizonStart;
        }

        List<UnavailableWindowSpec> aiUnavailable = parseUnavailableWindows(proposal.getUnavailableWindows());
        List<AvailabilityOverrideRequest> overrides = request.getAvailabilityOverrides() != null
                ? request.getAvailabilityOverrides() : List.of();

        AvailabilityEstimateResult availability = availabilityEstimateService.estimate(
                userId, horizonStart, horizonEnd, aiUnavailable, overrides);

        Map<Long, ScheduleItemOverride> itemOverrides = indexItemOverrides(request.getItems());

        List<BusyWindow> busyWindows = new ArrayList<>(availability.busyWindows());
        List<SchedulingTask> tasks = new ArrayList<>();
        List<PlacedItemDto> placed = new ArrayList<>();
        List<UnplacedItemDto> unplaced = new ArrayList<>();

        for (AiProposalItem item : openItems) {
            ProposalItemPayload payload = fromJson(item.getOriginalPayload());
            ScheduleItemOverride override = itemOverrides.get(item.getProposalItemId());

            String title = valueOr(override != null ? override.getTitle() : null, payload.title());
            Integer duration = numberOr(override != null ? override.getExpectedMinutes() : null, payload.expectedMinutes());
            String priorityStr = valueOr(override != null ? override.getPriority() : null, payload.priority());
            LocalDate earliestStartDate = override != null && override.getEarliestStartDate() != null
                    ? override.getEarliestStartDate() : payload.earliestStartDate();
            LocalDate deadlineDate = override != null && override.getDeadlineDate() != null
                    ? override.getDeadlineDate() : payload.deadlineDate();

            LocalDateTime fixedStartAt = override != null ? override.getFixedStartAt() : null;
            LocalDateTime fixedEndAt = override != null ? override.getFixedEndAt() : null;
            if (fixedStartAt == null && payload.placementType() == PlacementType.TIME_FIXED) {
                fixedStartAt = payload.scheduledStartAt();
                fixedEndAt = payload.scheduledEndAt();
            }

            if (fixedStartAt != null && fixedEndAt != null) {
                LocalDateTime pinnedStart = fixedStartAt;
                LocalDateTime pinnedEnd = fixedEndAt;
                boolean conflict = busyWindows.stream().anyMatch(b -> b.overlaps(pinnedStart, pinnedEnd));
                placed.add(PlacedItemDto.builder()
                        .proposalItemId(item.getProposalItemId())
                        .title(title)
                        .placementType(PlacementType.TIME_FIXED)
                        .scheduledDate(pinnedStart.toLocalDate())
                        .scheduledStartAt(pinnedStart)
                        .scheduledEndAt(pinnedEnd)
                        .reason(conflict
                                ? "사용자가 지정한 시각이에요 (다른 일정과 겹칠 수 있어 확인이 필요해요)"
                                : "사용자가 지정한 시각이에요")
                        .build());
                busyWindows.add(new BusyWindow(pinnedStart, pinnedEnd, title));
                continue;
            }

            LocalDateTime earliestFloor = earliestStartDate != null
                    ? earliestStartDate.atStartOfDay() : horizonStart.atStartOfDay();
            LocalDateTime effectiveEarliest = now.isAfter(earliestFloor) ? now : earliestFloor;
            LocalDateTime effectiveDeadline = deadlineDate != null ? deadlineDate.plusDays(1).atStartOfDay() : null;

            List<TimeSlotOption> candidates = buildCandidates(
                    availability.windows(), duration, effectiveEarliest, horizonEnd, effectiveDeadline);

            if (candidates.isEmpty()) {
                unplaced.add(new UnplacedItemDto(item.getProposalItemId(), title, "배치 가능한 시간이 부족함"));
                continue;
            }

            // 미리보기는 아직 execution_items가 없어 courseId·orderIndex를 모른다.
            // null이면 preferOrderIndexSequence가 걸리지 않는데, 그게 의도다 —
            // 확정 전 제안에는 지켜야 할 순서라는 것이 아직 없다. 나중에 이 자리를 채우면
            // 미리보기에도 순서 제약이 걸리면서 결과가 조용히 달라진다.
            tasks.add(new SchedulingTask(item.getProposalItemId(), title, duration,
                    ExecutionPriority.valueOf(priorityStr), effectiveDeadline,
                    null, null, candidates));
        }

        if (!tasks.isEmpty()) {
            SchedulingContext context = new SchedulingContext(
                    now, horizonStart.atStartOfDay(), horizonEnd.plusDays(1).atStartOfDay());
            SchedulingPlan solved = solverService.solve(new SchedulingPlan(tasks, busyWindows, context));
            for (SchedulingTask task : solved.getTasks()) {
                if (task.isScheduled()) {
                    placed.add(PlacedItemDto.builder()
                            .proposalItemId(task.getProposalItemId())
                            .title(task.getTitle())
                            .placementType(PlacementType.TIME_FIXED)
                            .scheduledDate(task.getAssignedStart().toLocalDate())
                            .scheduledStartAt(task.getAssignedStart())
                            .scheduledEndAt(task.getAssignedEnd())
                            .reason(reasonFor(task.getAssignedSlot().confidence()))
                            .build());
                } else {
                    unplaced.add(new UnplacedItemDto(task.getProposalItemId(), task.getTitle(), "배치 가능한 시간이 부족함"));
                }
            }
        }

        List<AvailabilityWindowDto> windowDtos = availability.windows().stream()
                .map(w -> AvailabilityWindowDto.builder()
                        .startAt(w.startAt()).endAt(w.endAt())
                        .source(w.source()).confidence(w.confidence()).reason(w.reason())
                        .build())
                .toList();

        LocalDateTime computedAt = now;
        persistPreview(proposalId, userId, horizonStart, horizonEnd, windowDtos, overrides, placed, unplaced, computedAt);

        return SchedulePreviewResponse.builder()
                .proposalId(proposalId)
                .horizonStart(horizonStart)
                .horizonEnd(horizonEnd)
                .availabilityWindows(windowDtos)
                .userOverrides(overrides)
                .placedItems(placed)
                .unplacedItems(unplaced)
                .computedAt(computedAt)
                .build();
    }

    @Transactional(readOnly = true)
    public SchedulePreviewResponse getStoredPreview(Long userId, Long proposalId) {
        AiProposal proposal = aiProposalMapper.findByIdAndUserId(proposalId, userId);
        if (proposal == null) {
            throw new NotFoundException(ErrorCode.AI_PROPOSAL_NOT_FOUND);
        }
        AiProposalSchedulePreview stored = schedulePreviewMapper.findByProposalIdAndUserId(proposalId, userId);
        if (stored == null) {
            return null;
        }
        return SchedulePreviewResponse.builder()
                .proposalId(proposalId)
                .horizonStart(stored.getHorizonStart())
                .horizonEnd(stored.getHorizonEnd())
                .availabilityWindows(readList(stored.getAvailabilityWindows(), AvailabilityWindowDto[].class))
                .userOverrides(readList(stored.getUserOverrides(), AvailabilityOverrideRequest[].class))
                .placedItems(readList(stored.getPlacedItems(), PlacedItemDto[].class))
                .unplacedItems(readList(stored.getUnplacedItems(), UnplacedItemDto[].class))
                .computedAt(stored.getComputedAt())
                .build();
    }

    // ===== 후보 시간 생성 =====

    private List<TimeSlotOption> buildCandidates(
            List<AvailabilityWindow> windows, int durationMinutes,
            LocalDateTime effectiveEarliest, LocalDate horizonEnd, LocalDateTime effectiveDeadline
    ) {
        LocalDateTime horizonEndExclusive = horizonEnd.plusDays(1).atStartOfDay();
        List<TimeSlotOption> options = new ArrayList<>();
        Set<LocalDateTime> seen = new HashSet<>();

        for (AvailabilityWindow window : windows) {
            LocalDateTime latestStart = window.endAt().minusMinutes(durationMinutes);
            if (latestStart.isBefore(window.startAt())) {
                continue;
            }
            LocalDateTime cursor = window.startAt().isBefore(effectiveEarliest) ? effectiveEarliest : window.startAt();
            cursor = roundUpToGranularity(cursor);
            while (!cursor.isAfter(latestStart)) {
                LocalDateTime end = cursor.plusMinutes(durationMinutes);
                boolean withinHorizon = !end.isAfter(horizonEndExclusive);
                boolean withinDeadline = effectiveDeadline == null || !end.isAfter(effectiveDeadline);
                if (withinHorizon && withinDeadline && seen.add(cursor)) {
                    options.add(new TimeSlotOption(cursor, window.confidence()));
                }
                cursor = cursor.plusMinutes(slotGranularityMinutes);
            }
        }
        return options;
    }

    private LocalDateTime roundUpToGranularity(LocalDateTime dt) {
        LocalDateTime truncated = dt.withSecond(0).withNano(0);
        int remainder = truncated.getMinute() % slotGranularityMinutes;
        if (remainder == 0) {
            return truncated;
        }
        return truncated.plusMinutes(slotGranularityMinutes - remainder);
    }

    private String reasonFor(AvailabilityConfidence confidence) {
        return switch (confidence) {
            case HIGH -> "확인된 가능한 시간대에 배치했어요";
            case MEDIUM -> "선호 시간대로 보이는 시간에 배치했어요";
            case LOW -> "구체적인 근거가 없어 기본 활동 시간대에 배치했어요";
        };
    }

    // ===== 저장/복원 =====

    private void persistPreview(
            Long proposalId, Long userId, LocalDate horizonStart, LocalDate horizonEnd,
            List<AvailabilityWindowDto> windows, List<AvailabilityOverrideRequest> overrides,
            List<PlacedItemDto> placed, List<UnplacedItemDto> unplaced, LocalDateTime computedAt
    ) {
        AiProposalSchedulePreview existing = schedulePreviewMapper.findByProposalIdAndUserId(proposalId, userId);
        AiProposalSchedulePreview preview = AiProposalSchedulePreview.builder()
                .proposalId(proposalId)
                .userId(userId)
                .horizonStart(horizonStart)
                .horizonEnd(horizonEnd)
                .availabilityWindows(toJson(windows))
                .userOverrides(toJson(overrides))
                .placedItems(toJson(placed))
                .unplacedItems(toJson(unplaced))
                .computedAt(computedAt)
                .build();
        if (existing == null) {
            schedulePreviewMapper.insert(preview);
        } else {
            schedulePreviewMapper.update(preview);
        }
    }

    private Map<Long, ScheduleItemOverride> indexItemOverrides(List<ScheduleItemOverride> overrides) {
        Map<Long, ScheduleItemOverride> byId = new HashMap<>();
        if (overrides == null) {
            return byId;
        }
        for (ScheduleItemOverride override : overrides) {
            if (override.getProposalItemId() != null) {
                byId.put(override.getProposalItemId(), override);
            }
        }
        return byId;
    }

    private List<UnavailableWindowSpec> parseUnavailableWindows(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return List.of(objectMapper.readValue(json, UnavailableWindowSpec[].class));
        } catch (Exception e) {
            log.warn("사용 불가 시간 파싱 실패 - 무시하고 진행", e);
            return List.of();
        }
    }

    private String valueOr(String override, String original) {
        return override != null && !override.isBlank() ? override : original;
    }

    private Integer numberOr(Integer override, Integer original) {
        return override != null ? override : original;
    }

    private ProposalItemPayload fromJson(String json) {
        try {
            return objectMapper.readValue(json, ProposalItemPayload.class);
        } catch (Exception e) {
            throw new IllegalStateException("제안 payload 역직렬화 실패", e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("일정 미리보기 직렬화 실패", e);
        }
    }

    private <T> List<T> readList(String json, Class<T[]> arrayType) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return List.of(objectMapper.readValue(json, arrayType));
        } catch (Exception e) {
            log.warn("일정 미리보기 역직렬화 실패", e);
            return List.of();
        }
    }
}
