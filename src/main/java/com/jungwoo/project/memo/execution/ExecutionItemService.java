package com.jungwoo.project.memo.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.execution.domain.ExecutionEventActorType;
import com.jungwoo.project.memo.execution.domain.ExecutionEventType;
import com.jungwoo.project.memo.execution.domain.ExecutionItem;
import com.jungwoo.project.memo.execution.domain.ExecutionItemCompletedEvent;
import com.jungwoo.project.memo.execution.domain.ExecutionItemEvent;
import com.jungwoo.project.memo.execution.domain.ExecutionOriginType;
import com.jungwoo.project.memo.execution.domain.ExecutionPriority;
import com.jungwoo.project.memo.execution.domain.ExecutionRecord;
import com.jungwoo.project.memo.execution.domain.ExecutionRecordOutcome;
import com.jungwoo.project.memo.execution.domain.ExecutionStatus;
import com.jungwoo.project.memo.execution.domain.PlacementType;
import com.jungwoo.project.memo.execution.dto.ExecutionItemCompleteRequest;
import com.jungwoo.project.memo.execution.dto.ExecutionItemCreateRequest;
import com.jungwoo.project.memo.execution.dto.ExecutionItemHoldRequest;
import com.jungwoo.project.memo.execution.dto.ExecutionItemMoveRequest;
import com.jungwoo.project.memo.execution.dto.ExecutionItemUnscheduleRequest;
import com.jungwoo.project.memo.execution.dto.ExecutionItemPartialRequest;
import com.jungwoo.project.memo.execution.dto.ExecutionItemReduceRequest;
import com.jungwoo.project.memo.execution.dto.ExecutionItemReopenRequest;
import com.jungwoo.project.memo.execution.dto.ExecutionItemResponse;
import com.jungwoo.project.memo.execution.dto.ExecutionItemResumeRequest;
import com.jungwoo.project.memo.execution.dto.ExecutionRecordResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 실행 조각(execution_items) 서비스.
 *
 * Today/Execution 화면의 공식 실행 원본이다. schedule_blocks에는 더 이상 쓰지 않는다.
 * 공식 변경마다 version을 1 증가시키고, 클라이언트가 보낸 version과 다르면 409를 반환한다.
 * 모든 조회/수정은 user_id 소유권 조건을 건다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionItemService {

    private final ExecutionItemMapper executionItemMapper;
    private final ExecutionItemEventMapper executionItemEventMapper;
    private final ExecutionRecordMapper executionRecordMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    // ===== 조회 =====

    @Transactional(readOnly = true)
    public List<ExecutionItemResponse> getByDate(Long userId, LocalDate date) {
        return executionItemMapper.findByUserIdAndDate(userId, date)
                .stream()
                .map(ExecutionItemResponse::from)
                .toList();
    }

    /**
     * 날짜 범위 조회. 주간 시간표에서 쓴다 — getByDate와 같은 원본(execution_items)을
     * 같은 규칙(상태 필터 없음)으로 여러 날짜에 걸쳐 투영할 뿐, 별도 데이터를 만들지 않는다.
     */
    @Transactional(readOnly = true)
    public List<ExecutionItemResponse> getByDateRange(Long userId, LocalDate startDate, LocalDate endDate) {
        return getByDateRange(userId, startDate, endDate, false);
    }

    /**
     * includeUnscheduled=true면 아직 날짜를 정하지 않은 조각도 함께 돌려준다 — 그 조각의
     * planning_start/end_date가 조회 범위와 겹치는 경우다.
     *
     * 기본값을 false로 두는 것은 주간 시간표 때문이다. 그 화면은 날짜 칸에 그리므로 날짜 없는
     * 조각이 섞이면 놓을 자리가 없다. 계획 화면만 true로 부른다.
     */
    @Transactional(readOnly = true)
    public List<ExecutionItemResponse> getByDateRange(
            Long userId, LocalDate startDate, LocalDate endDate, boolean includeUnscheduled) {
        if (endDate.isBefore(startDate)) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }
        List<ExecutionItem> items = includeUnscheduled
                ? executionItemMapper.findByUserIdAndPlanningRange(userId, startDate, endDate)
                : executionItemMapper.findByUserIdAndDateRange(userId, startDate, endDate);
        return items.stream()
                .map(ExecutionItemResponse::from)
                .toList();
    }

    /** 기록 화면: 실제로 일어난 결과를 기간으로 조회한다. */
    @Transactional(readOnly = true)
    public List<ExecutionRecordResponse> getRecords(Long userId, LocalDate startDate, LocalDate endDate) {
        if (endDate.isBefore(startDate)) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }
        return executionRecordMapper.findByUserIdAndDateRange(userId, startDate, endDate);
    }

    @Transactional(readOnly = true)
    public List<ExecutionItemResponse> getPending(Long userId, LocalDate beforeDate) {
        return executionItemMapper.findPendingBefore(userId, beforeDate)
                .stream()
                .map(ExecutionItemResponse::from)
                .toList();
    }

    // ===== 생성 =====

    @Transactional
    public ExecutionItemResponse create(Long userId, ExecutionItemCreateRequest request) {
        PlacementType placementType = derivePlacementType(
                request.getScheduledStartAt(), request.getScheduledEndAt());
        validatePlacement(placementType, request.getScheduledDate(),
                request.getScheduledStartAt(), request.getScheduledEndAt());

        ExecutionItem item = ExecutionItem.builder()
                .userId(userId)
                .courseId(request.getCourseId())
                .title(request.getTitle())
                .description(request.getDescription())
                .placementType(placementType)
                .scheduledDate(request.getScheduledDate())
                .scheduledStartAt(request.getScheduledStartAt())
                .scheduledEndAt(request.getScheduledEndAt())
                .expectedMinutes(request.getExpectedMinutes())
                .status(ExecutionStatus.PLANNED)
                .priority(request.getPriority() != null ? request.getPriority() : ExecutionPriority.SHOULD)
                .orderIndex(0)
                .originType(ExecutionOriginType.MANUAL)
                .modifiedAfterCreation(false)
                .version(0L)
                .isDeleted(false)
                .build();

        executionItemMapper.insert(item);

        Map<String, Object> afterState = new LinkedHashMap<>();
        afterState.put("title", item.getTitle());
        afterState.put("placementType", item.getPlacementType());
        afterState.put("scheduledDate", item.getScheduledDate());
        afterState.put("scheduledStartAt", item.getScheduledStartAt());
        afterState.put("scheduledEndAt", item.getScheduledEndAt());
        afterState.put("priority", item.getPriority());
        afterState.put("expectedMinutes", item.getExpectedMinutes());

        insertEvent(item.getExecutionItemId(), userId, ExecutionEventType.CREATED,
                ExecutionEventActorType.USER, null, null, toJson(afterState), null, item.getVersion());

        return ExecutionItemResponse.from(
                executionItemMapper.findByIdAndUserId(item.getExecutionItemId(), userId));
    }

    // ===== 완료 =====

    @Transactional
    public ExecutionItemResponse complete(Long executionItemId, Long userId, ExecutionItemCompleteRequest request) {
        ExecutionItem item = findOwnedOrThrow(executionItemId, userId);
        requireVersion(item, request.getVersion());
        // HOLD는 먼저 resume()으로 PLANNED로 되돌린 뒤에만 완료할 수 있다 — 보류 중인 항목을
        // 건너뛰고 바로 완료 처리하지 않는다.
        requireStatusIn(item, ExecutionStatus.PLANNED);

        int updated = executionItemMapper.completeWithVersion(executionItemId, userId, request.getVersion());
        if (updated != 1) {
            throw new ConflictException(ErrorCode.VERSION_CONFLICT);
        }

        ExecutionRecord record = ExecutionRecord.builder()
                .userId(userId)
                .executionItemId(executionItemId)
                .outcome(ExecutionRecordOutcome.COMPLETED)
                .actualMinutes(request.getActualMinutes())
                .completionPercent(100)
                .note(request.getNote())
                .build();
        executionRecordMapper.insert(record);

        log.info("실행 조각 완료: executionItemId={}, userId={}", executionItemId, userId);

        if (item.getTopicId() != null) {
            // "완료 버튼을 눌렀다"는 사실만으로 학습 상태를 LEARNED로 단정하지 않는다 —
            // 리스너(TopicService.recordExecutionCompleted)가 IN_PROGRESS/복습 여부만 갱신한다.
            eventPublisher.publishEvent(new ExecutionItemCompletedEvent(executionItemId, userId, item.getTopicId()));
        }

        return ExecutionItemResponse.from(executionItemMapper.findByIdAndUserId(executionItemId, userId));
    }

    // ===== 재열기 =====

    @Transactional
    public ExecutionItemResponse reopen(Long executionItemId, Long userId, ExecutionItemReopenRequest request) {
        ExecutionItem item = findOwnedOrThrow(executionItemId, userId);
        requireVersion(item, request.getVersion());
        requireStatusIn(item, ExecutionStatus.DONE);

        int updated = executionItemMapper.updateStatusWithVersion(
                executionItemId, userId, request.getVersion(), ExecutionStatus.PLANNED);
        if (updated != 1) {
            throw new ConflictException(ErrorCode.VERSION_CONFLICT);
        }

        insertEvent(executionItemId, userId, ExecutionEventType.REOPENED, ExecutionEventActorType.USER,
                request.getReason(),
                toJson(Map.of("status", ExecutionStatus.DONE)),
                toJson(Map.of("status", ExecutionStatus.PLANNED)),
                item.getVersion(), item.getVersion() + 1);

        log.info("실행 조각 재열기: executionItemId={}, userId={}", executionItemId, userId);

        return ExecutionItemResponse.from(executionItemMapper.findByIdAndUserId(executionItemId, userId));
    }

    // ===== 배치 / 배치 해제 =====

    /**
     * 롤링 배치가 정한 시각을 실제로 적용한다. UNSCHEDULED → TIME_FIXED.
     *
     * ★ version과 status='PLANNED'를 조건으로 걸고 영향 행 수를 검사한다. 0이면 대상
     * 조회와 이 UPDATE 사이에 누군가 이 조각을 옮겼거나 보류했거나 지웠다는 뜻이고,
     * 그대로 넘어가면 응답에는 "배치했다"고 적히는데 DB는 안 바뀐 상태가 된다.
     *
     * status 조건이 특히 중요하다 — 없으면 사용자가 HOLD로 바꾼 항목을 솔버가 시간표에
     * 앉힌다. "잠시 멈춰뒀다"고 한 것이 다시 일정에 나타나면 안 된다.
     *
     * ★ execution_items.version은 올린다. plan_versions.version을 올리지 않는 것과 혼동
     * 하면 안 된다 — 저쪽은 불변 스냅샷의 세대라 배치가 건드릴 대상이 아니고, 이쪽은 행
     * 낙관적 락 카운터라 행을 바꿨으면 반드시 올려야 한다. 안 올리면 배치 직후 다른
     * 요청이 낡은 version으로도 같은 행을 또 고칠 수 있다.
     *
     * 이벤트는 MOVED로 남긴다. 배치는 이동과 다른 사건이지만 event_type이 CHECK로
     * 고정돼 있어 새 값을 넣으려면 마이그레이션이 필요하고, "언제 할지가 바뀌었다"는
     * MOVED의 의미에 배치도 들어간다. 구분은 before/after 상태 JSON이 한다 —
     * before에 날짜가 없고 after에 있으면 배치다. 회고도 같은 방식으로 판정한다
     * (스냅샷 날짜 없음 → 현재 날짜 있음 = SCHEDULED, 이동 아님).
     *
     * actorType은 SYSTEM이다. 사용자가 [배치하기]를 누른 것은 맞지만 시각을 고른 것은
     * 솔버이므로, 사용자가 직접 그 시각을 지정한 move()와 구별한다.
     */
    @Transactional
    public void applyRollingPlacement(
            Long userId, Long executionItemId,
            LocalDate scheduledDate, LocalDateTime scheduledStartAt, LocalDateTime scheduledEndAt
    ) {
        ExecutionItem before = findOwnedOrThrow(executionItemId, userId);

        int updated = executionItemMapper.applyTimeFixedPlacement(
                userId, executionItemId, before.getVersion(),
                scheduledDate, scheduledStartAt, scheduledEndAt);
        if (updated != 1) {
            throw new ConflictException(ErrorCode.VERSION_CONFLICT);
        }

        insertEvent(executionItemId, userId, ExecutionEventType.MOVED, ExecutionEventActorType.SYSTEM,
                "이번 주 배치",
                toJson(Map.of("placementType", PlacementType.UNSCHEDULED,
                        "planningStartDate", String.valueOf(before.getPlanningStartDate()),
                        "planningEndDate", String.valueOf(before.getPlanningEndDate()))),
                toJson(Map.of("placementType", PlacementType.TIME_FIXED,
                        "scheduledDate", String.valueOf(scheduledDate),
                        "scheduledStartAt", String.valueOf(scheduledStartAt),
                        "scheduledEndAt", String.valueOf(scheduledEndAt))),
                before.getVersion(), before.getVersion() + 1);
    }

    /**
     * 날짜/시각을 뗀다. 배치의 역방향이고, 롤링 배치 결과를 되돌리는 수단이다.
     *
     * ★ 기간을 요청으로 받는다. 서버가 추론하지 않는다(11-period-plan.md §2-5) —
     * 같은 날짜에 계획이 여럿 걸릴 수 있어 서버는 "그 계획의 기간"을 고를 수 없다.
     *
     * 기간을 주면 계획 안에 남고, 안 주면 미분류로 나간다. 전자가 기본이어야 한다:
     * 사용자가 날짜만 뗐는데 항목이 화면에서 증발하면 안 된다.
     *
     * plan_versions는 건드리지 않는다. 배치와 해제는 실행 조각 조작이고, 스냅샷은
     * 확정 시점의 사실이다 — 나중에 날짜를 떼는 것이 "그때 무엇을 하기로 했는가"를
     * 바꾸지는 않는다.
     */
    @Transactional
    public ExecutionItemResponse unschedule(
            Long executionItemId, Long userId, ExecutionItemUnscheduleRequest request) {
        ExecutionItem item = findOwnedOrThrow(executionItemId, userId);
        requireVersion(item, request.getVersion());
        requireStatusIn(item, ExecutionStatus.PLANNED, ExecutionStatus.HOLD);
        if (item.getPlacementType() == PlacementType.UNSCHEDULED) {
            // 이미 날짜가 없다. 기간만 바꾸는 것은 이 액션의 일이 아니다.
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }

        LocalDate planningStart = request.getPlanningStartDate();
        LocalDate planningEnd = request.getPlanningEndDate();
        // planning_* 는 둘 다 있거나 둘 다 없다(chk_execution_items_planning_range).
        // 한쪽만 온 요청을 통과시키면 UPDATE가 제약에 걸려 원인 없는 500이 된다.
        if ((planningStart == null) != (planningEnd == null)) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }
        if (planningStart != null && planningEnd.isBefore(planningStart)) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }

        int updated = executionItemMapper.clearPlacement(
                executionItemId, userId, request.getVersion(), planningStart, planningEnd);
        if (updated != 1) {
            throw new ConflictException(ErrorCode.VERSION_CONFLICT);
        }

        insertEvent(executionItemId, userId, ExecutionEventType.MOVED, ExecutionEventActorType.USER,
                request.getReason(),
                toJson(Map.of("placementType", item.getPlacementType(),
                        "scheduledDate", String.valueOf(item.getScheduledDate()))),
                toJson(Map.of("placementType", PlacementType.UNSCHEDULED,
                        "planningStartDate", String.valueOf(planningStart),
                        "planningEndDate", String.valueOf(planningEnd))),
                item.getVersion(), item.getVersion() + 1);

        return ExecutionItemResponse.from(executionItemMapper.findByIdAndUserId(executionItemId, userId));
    }

    // ===== 이동 =====

    /**
     * 이동 = "언제 할지"를 바꾼다. 다른 날짜로 옮기는 것과, 같은 날 안에서 시각만 뒤로 미는
     * 것("오늘 뒤로")이 모두 이 액션이고 둘 다 MOVED 이벤트 하나로 남는다 — 같은 날 시각 이동을
     * 위해 새 액션/새 이벤트 타입을 만들지 않는다.
     *
     * 날짜도 시각도 실제로 달라지지 않으면 이동이 아니므로 막는다(MOVE_TARGET_DATE_INVALID).
     */
    @Transactional
    public ExecutionItemResponse move(Long executionItemId, Long userId, ExecutionItemMoveRequest request) {
        ExecutionItem item = findOwnedOrThrow(executionItemId, userId);
        requireVersion(item, request.getVersion());
        requireStatusIn(item, ExecutionStatus.PLANNED);

        if (PlacementType.UNSCHEDULED.equals(item.getPlacementType())) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }

        LocalDate fromDate = item.getScheduledDate();
        LocalDate toDate = request.getToDate();
        boolean timeGiven = request.getStartTime() != null || request.getEndTime() != null;
        if (timeGiven) {
            if (request.getStartTime() == null || request.getEndTime() == null) {
                throw new BadRequestException(ErrorCode.PARTIAL_TIME_RANGE);
            }
            if (!request.getEndTime().isAfter(request.getStartTime())) {
                throw new BadRequestException(ErrorCode.INVALID_TIME_RANGE);
            }
            // 시각 없는 항목에 시각을 붙이는 것은 이동이 아니라 배치 형식 변경이다 — 여기서 하지 않는다.
            if (!PlacementType.TIME_FIXED.equals(item.getPlacementType())) {
                throw new BadRequestException(ErrorCode.TASK_MUST_NOT_HAVE_TIME);
            }
        }

        LocalDateTime newStart = item.getScheduledStartAt();
        LocalDateTime newEnd = item.getScheduledEndAt();
        if (PlacementType.TIME_FIXED.equals(item.getPlacementType())) {
            if (timeGiven) {
                newStart = LocalDateTime.of(toDate, request.getStartTime());
                newEnd = LocalDateTime.of(toDate, request.getEndTime());
            } else {
                long dayDiff = java.time.temporal.ChronoUnit.DAYS.between(fromDate, toDate);
                newStart = item.getScheduledStartAt().plusDays(dayDiff);
                newEnd = item.getScheduledEndAt().plusDays(dayDiff);
            }
        }

        boolean dateChanged = !toDate.equals(fromDate);
        boolean timeChanged = !Objects.equals(newStart, item.getScheduledStartAt())
                || !Objects.equals(newEnd, item.getScheduledEndAt());
        if (!dateChanged && !timeChanged) {
            throw new BadRequestException(ErrorCode.MOVE_TARGET_DATE_INVALID);
        }

        int updated = executionItemMapper.updateForMove(
                executionItemId, userId, request.getVersion(), toDate, newStart, newEnd);
        if (updated != 1) {
            throw new ConflictException(ErrorCode.VERSION_CONFLICT);
        }

        insertEvent(executionItemId, userId, ExecutionEventType.MOVED, ExecutionEventActorType.USER,
                request.getReason(),
                toJson(moveState(fromDate, item.getScheduledStartAt(), item.getScheduledEndAt())),
                toJson(moveState(toDate, newStart, newEnd)),
                item.getVersion(), item.getVersion() + 1);

        log.info("실행 조각 이동: executionItemId={}, {} {} -> {} {}",
                executionItemId, fromDate, item.getScheduledStartAt(), toDate, newStart);

        return ExecutionItemResponse.from(executionItemMapper.findByIdAndUserId(executionItemId, userId));
    }

    /** MOVED 이벤트의 before/after. 같은 날 시각 이동도 무엇이 달라졌는지 남아야 한다. */
    private Map<String, Object> moveState(LocalDate date, LocalDateTime start, LocalDateTime end) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("scheduledDate", date);
        state.put("scheduledStartAt", start);
        state.put("scheduledEndAt", end);
        return state;
    }

    // ===== 축소 =====

    @Transactional
    public ExecutionItemResponse reduce(Long executionItemId, Long userId, ExecutionItemReduceRequest request) {
        ExecutionItem item = findOwnedOrThrow(executionItemId, userId);
        requireVersion(item, request.getVersion());
        requireStatusIn(item, ExecutionStatus.PLANNED);

        if (request.getReducedTitle() == null && request.getExpectedMinutes() == null) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }

        boolean titleChanged = request.getReducedTitle() != null
                && !Objects.equals(item.getTitle(), request.getReducedTitle());
        boolean minutesChanged = request.getExpectedMinutes() != null
                && !Objects.equals(item.getExpectedMinutes(), request.getExpectedMinutes());
        if (!titleChanged && !minutesChanged) {
            throw new BadRequestException(ErrorCode.EXECUTION_ITEM_NO_ACTUAL_CHANGE);
        }

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("title", item.getTitle());
        before.put("expectedMinutes", item.getExpectedMinutes());

        int updated = executionItemMapper.updateForReduce(
                executionItemId, userId, request.getVersion(),
                request.getReducedTitle(), request.getExpectedMinutes());
        if (updated != 1) {
            throw new ConflictException(ErrorCode.VERSION_CONFLICT);
        }

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("title", request.getReducedTitle() != null ? request.getReducedTitle() : item.getTitle());
        after.put("expectedMinutes",
                request.getExpectedMinutes() != null ? request.getExpectedMinutes() : item.getExpectedMinutes());

        insertEvent(executionItemId, userId, ExecutionEventType.REDUCED, ExecutionEventActorType.USER,
                request.getReason(), toJson(before), toJson(after),
                item.getVersion(), item.getVersion() + 1);

        log.info("실행 조각 축소: executionItemId={}, userId={}", executionItemId, userId);

        return ExecutionItemResponse.from(executionItemMapper.findByIdAndUserId(executionItemId, userId));
    }

    // ===== 보류 =====

    @Transactional
    public ExecutionItemResponse hold(Long executionItemId, Long userId, ExecutionItemHoldRequest request) {
        ExecutionItem item = findOwnedOrThrow(executionItemId, userId);
        requireVersion(item, request.getVersion());
        requireStatusIn(item, ExecutionStatus.PLANNED);

        int updated = executionItemMapper.updateStatusWithVersion(
                executionItemId, userId, request.getVersion(), ExecutionStatus.HOLD);
        if (updated != 1) {
            throw new ConflictException(ErrorCode.VERSION_CONFLICT);
        }

        insertEvent(executionItemId, userId, ExecutionEventType.HOLD, ExecutionEventActorType.USER,
                request.getReason(),
                toJson(Map.of("status", ExecutionStatus.PLANNED)),
                toJson(Map.of("status", ExecutionStatus.HOLD)),
                item.getVersion(), item.getVersion() + 1);

        log.info("실행 조각 보류: executionItemId={}, userId={}", executionItemId, userId);

        return ExecutionItemResponse.from(executionItemMapper.findByIdAndUserId(executionItemId, userId));
    }

    // ===== 보류 해제(재개) =====

    @Transactional
    public ExecutionItemResponse resume(Long executionItemId, Long userId, ExecutionItemResumeRequest request) {
        ExecutionItem item = findOwnedOrThrow(executionItemId, userId);
        requireVersion(item, request.getVersion());
        requireStatusIn(item, ExecutionStatus.HOLD);

        int updated = executionItemMapper.updateStatusWithVersion(
                executionItemId, userId, request.getVersion(), ExecutionStatus.PLANNED);
        if (updated != 1) {
            throw new ConflictException(ErrorCode.VERSION_CONFLICT);
        }

        insertEvent(executionItemId, userId, ExecutionEventType.RESUMED, ExecutionEventActorType.USER,
                request.getReason(),
                toJson(Map.of("status", ExecutionStatus.HOLD)),
                toJson(Map.of("status", ExecutionStatus.PLANNED)),
                item.getVersion(), item.getVersion() + 1);

        log.info("실행 조각 보류 해제: executionItemId={}, userId={}", executionItemId, userId);

        return ExecutionItemResponse.from(executionItemMapper.findByIdAndUserId(executionItemId, userId));
    }

    // ===== 삭제 =====

    @Transactional
    public void delete(Long executionItemId, Long userId, Long version) {
        ExecutionItem item = findOwnedOrThrow(executionItemId, userId);
        requireVersion(item, version);

        int updated = executionItemMapper.softDeleteWithVersion(executionItemId, userId, version);
        if (updated != 1) {
            throw new ConflictException(ErrorCode.VERSION_CONFLICT);
        }

        insertEvent(executionItemId, userId, ExecutionEventType.DELETED, ExecutionEventActorType.USER,
                null,
                toJson(Map.of("isDeleted", false)),
                toJson(Map.of("isDeleted", true)),
                item.getVersion(), item.getVersion() + 1);

        log.info("실행 조각 삭제: executionItemId={}, userId={}", executionItemId, userId);
    }

    /**
     * 삭제 되돌리기 (Ctrl+Z).
     *
     * soft delete라 지운 행이 그대로 남아 있어서, 되살리는 데 필요한 정보가 이미 전부
     * execution_items에 있다. 삭제 이력을 따로 쌓지 않는 이유다.
     *
     * version을 그대로 요구한다. 지운 뒤 그 항목이 다른 경로로 또 바뀌었다면 되돌리기가
     * 무엇을 되살리는지 알 수 없으므로, 낙관적 락을 느슨하게 풀지 않는다.
     */
    @Transactional
    public ExecutionItemResponse restore(Long executionItemId, Long userId, Long version) {
        ExecutionItem item = executionItemMapper.findByIdAndUserIdIncludingDeleted(executionItemId, userId);
        if (item == null) {
            throw new NotFoundException(ErrorCode.EXECUTION_ITEM_NOT_FOUND);
        }
        requireVersion(item, version);

        int updated = executionItemMapper.restoreWithVersion(executionItemId, userId, version);
        if (updated != 1) {
            // 이미 살아 있거나(되돌릴 것이 없음) 그 사이에 버전이 어긋났다. 둘 다 사용자가
            // 할 수 있는 일은 같다 — 화면을 새로 읽는 것.
            throw new ConflictException(ErrorCode.VERSION_CONFLICT);
        }

        insertEvent(executionItemId, userId, ExecutionEventType.RESTORED, ExecutionEventActorType.USER,
                null,
                toJson(Map.of("isDeleted", true)),
                toJson(Map.of("isDeleted", false)),
                item.getVersion(), item.getVersion() + 1);

        log.info("실행 조각 삭제 되돌리기: executionItemId={}, userId={}", executionItemId, userId);

        return ExecutionItemResponse.from(executionItemMapper.findByIdAndUserId(executionItemId, userId));
    }

    // ===== AI 제안 적용 전용 =====

    /**
     * 해당 날짜에 이미 있는 항목 뒤로 이어 붙일 order_index 시작값.
     * AiProposalService.apply()가 묶음 안 항목들에 순서를 매기기 전에 한 번 호출한다.
     */
    @Transactional(readOnly = true)
    public int nextOrderIndexStart(Long userId, LocalDate date) {
        Integer max = executionItemMapper.findMaxOrderIndexByUserIdAndDate(userId, date);
        return max != null ? max + 1 : 0;
    }

    /**
     * AI 제안이 승인되어 execution_items에 생성될 때 쓰는 생성 경로.
     * AiProposalService.apply()의 트랜잭션 안에서 호출된다 (기본 전파: REQUIRED로 합류).
     *
     * placementType/scheduledStartAt/scheduledEndAt은 AI가 만들거나 사용자가 편집한 값을
     * 그대로 받는다 — DATE_ONLY로 무조건 덮어쓰지 않는다. 대신 execution_items의 배치
     * 무결성 조건(validatePlacement)을 그대로 통과해야 하고, 어기면 검증 오류로 막는다.
     *
     * actor_type은 USER로 남긴다 — origin_type=AI_GENERATED가 "무엇이 만들었는지"를 이미
     * 담고, actor_type은 "누가 이 이벤트를 일으켰는지"(즉 적용을 누른 사용자)를 뜻한다.
     */
    @Transactional
    public ExecutionItem createFromApprovedProposal(
            Long userId, String title, String description, LocalDate scheduledDate,
            Integer expectedMinutes, ExecutionPriority priority, int orderIndex, boolean modifiedAfterCreation,
            PlacementType placementType, LocalDateTime scheduledStartAt, LocalDateTime scheduledEndAt
    ) {
        validatePlacement(placementType, scheduledDate, scheduledStartAt, scheduledEndAt);

        ExecutionItem item = ExecutionItem.builder()
                .userId(userId)
                .title(title)
                .description(description)
                .placementType(placementType)
                .scheduledDate(scheduledDate)
                .scheduledStartAt(scheduledStartAt)
                .scheduledEndAt(scheduledEndAt)
                .expectedMinutes(expectedMinutes)
                .status(ExecutionStatus.PLANNED)
                .priority(priority)
                .orderIndex(orderIndex)
                .originType(ExecutionOriginType.AI_GENERATED)
                .modifiedAfterCreation(modifiedAfterCreation)
                .version(0L)
                .isDeleted(false)
                .build();

        executionItemMapper.insert(item);

        Map<String, Object> afterState = new LinkedHashMap<>();
        afterState.put("title", title);
        afterState.put("placementType", placementType);
        afterState.put("scheduledDate", scheduledDate);
        afterState.put("scheduledStartAt", scheduledStartAt);
        afterState.put("scheduledEndAt", scheduledEndAt);
        afterState.put("priority", priority);
        afterState.put("expectedMinutes", expectedMinutes);

        insertEvent(item.getExecutionItemId(), userId, ExecutionEventType.CREATED,
                ExecutionEventActorType.USER, "AI 제안 적용", null, toJson(afterState), null, item.getVersion());

        return item;
    }

    /**
     * Planning Agent가 학습 추천을 제안으로 적용한 직후, 생성된 실행 조각에 학습 topic을
     * 연결한다. AiProposalService.apply()가 이미 소유권을 검증한 뒤 만든 조각만 대상이 되므로
     * 여기서는 존재 확인 없이 그대로 갱신한다(없으면 0행 갱신으로 조용히 끝난다).
     */
    @Transactional
    public void linkTopic(Long executionItemId, Long userId, Long topicId) {
        executionItemMapper.updateTopicId(executionItemId, userId, topicId);
    }

    /**
     * 프로젝트 대화에서 만든 제안을 적용한 직후, 생성된 실행 조각에 그 프로젝트를 연결한다.
     * linkTopic과 같은 이유로 존재 확인 없이 갱신한다.
     */
    @Transactional
    public void linkCourse(Long executionItemId, Long userId, Long courseId) {
        executionItemMapper.updateCourseId(executionItemId, userId, courseId);
    }

    /**
     * AI 조정 후보(줄이기/옮기기/빼기)가 가리킨 실행 조각이 실제로 조정 가능한지 확인한다.
     * 없거나 이미 결론이 난(PLANNED가 아닌) 항목이면 null을 반환한다 — 예외를 던지지 않는
     * 이유는 모델이 잘못 짚은 후보 하나 때문에 사용자의 요청 전체를 실패시키지 않기 위해서다.
     */
    @Transactional(readOnly = true)
    public ExecutionItem findOwnedForAdjustment(Long executionItemId, Long userId) {
        ExecutionItem item = executionItemMapper.findByIdAndUserId(executionItemId, userId);
        if (item == null || item.getStatus() != ExecutionStatus.PLANNED) {
            return null;
        }
        return item;
    }

    /** 프로젝트 화면의 "관련 실행". 지난 7일 ~ 앞으로 14일 + 날짜 미정 항목. */
    @Transactional(readOnly = true)
    public List<ExecutionItemResponse> getByCourse(Long userId, Long courseId, LocalDate today) {
        return executionItemMapper
                .findByUserIdAndCourseId(userId, courseId, today.minusDays(7), today.plusDays(14))
                .stream()
                .map(ExecutionItemResponse::from)
                .toList();
    }

    // ===== 일부 수행 =====

    /**
     * "일부만 했다"를 그대로 기록한다. 완료(DONE)로 올림하지도, 아무 일 없었던 것으로
     * 내림하지도 않는다 — 실제로 한 만큼이 PARTIAL 결과로 남는다.
     *
     * 남은 분량은 새 실행 조각으로 분리한다. execution_records는 원래부터 이 모양을 전제로
     * 설계돼 있다(chk_execution_records_remaining: PARTIAL이면 remaining_execution_item_id가
     * 반드시 있어야 한다) — "한 만큼"과 "남은 것"을 한 행에 뭉뚱그리지 않고, 남은 것은 다시
     * 옮기거나 줄일 수 있는 독립된 조각으로 둔다는 뜻이다.
     *
     * 남은 조각은 같은 날짜에 두고 시각은 비운다 — 언제 다시 할지는 사용자(또는 사용자가
     * 승인한 AI 제안)가 정할 일이므로 서버가 임의의 시각을 잡지 않는다.
     */
    @Transactional
    public ExecutionItemResponse recordPartial(Long executionItemId, Long userId, ExecutionItemPartialRequest request) {
        ExecutionItem item = findOwnedOrThrow(executionItemId, userId);
        requireVersion(item, request.getVersion());
        requireStatusIn(item, ExecutionStatus.PLANNED);

        int percent = request.getCompletionPercent() != null ? request.getCompletionPercent() : 50;
        if (percent < 1 || percent > 99) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }

        ExecutionItem remaining = createRemainderItem(item, userId, percent);

        executionRecordMapper.insert(ExecutionRecord.builder()
                .userId(userId)
                .executionItemId(executionItemId)
                .outcome(ExecutionRecordOutcome.PARTIAL)
                .actualMinutes(request.getActualMinutes())
                .completionPercent(percent)
                .note(request.getNote())
                .remainingExecutionItemId(remaining.getExecutionItemId())
                .build());

        // 이 조각에 대한 이번 시도는 끝났다 — 남은 분량은 위에서 만든 새 조각이 들고 있다.
        int updated = executionItemMapper.updateStatusWithVersion(
                executionItemId, userId, request.getVersion(), ExecutionStatus.DONE);
        if (updated != 1) {
            throw new ConflictException(ErrorCode.VERSION_CONFLICT);
        }
        insertEvent(executionItemId, userId, ExecutionEventType.REDUCED, ExecutionEventActorType.USER,
                "일부 수행: " + percent + "%",
                toJson(Map.of("status", item.getStatus())),
                toJson(Map.of("status", ExecutionStatus.DONE, "remainingExecutionItemId", remaining.getExecutionItemId())),
                item.getVersion(), item.getVersion() + 1);

        log.info("실행 조각 일부 수행 기록: executionItemId={}, userId={}, percent={}, remainingItemId={}",
                executionItemId, userId, percent, remaining.getExecutionItemId());

        return ExecutionItemResponse.from(executionItemMapper.findByIdAndUserId(executionItemId, userId));
    }

    /** 남은 분량을 담은 새 조각. 원본과 같은 프로젝트/주제를 그대로 잇는다. */
    private ExecutionItem createRemainderItem(ExecutionItem source, Long userId, int donePercent) {
        Integer sourceMinutes = source.getExpectedMinutes();
        Integer remainingMinutes = sourceMinutes != null
                ? Math.max(5, (int) Math.round(sourceMinutes * (100 - donePercent) / 100.0))
                : null;

        ExecutionItem remaining = ExecutionItem.builder()
                .userId(userId)
                .topicId(source.getTopicId())
                .courseId(source.getCourseId())
                .sourceExecutionItemId(source.getExecutionItemId())
                .title(source.getTitle() + " (남은 분량)")
                .description(source.getDescription())
                .placementType(PlacementType.DATE_ONLY)
                .scheduledDate(source.getScheduledDate())
                .expectedMinutes(remainingMinutes)
                .status(ExecutionStatus.PLANNED)
                .priority(source.getPriority())
                .orderIndex(nextOrderIndexStart(userId, source.getScheduledDate()))
                .originType(ExecutionOriginType.MANUAL)
                .modifiedAfterCreation(false)
                .version(0L)
                .isDeleted(false)
                .build();
        executionItemMapper.insert(remaining);

        insertEvent(remaining.getExecutionItemId(), userId, ExecutionEventType.CREATED,
                ExecutionEventActorType.USER, "일부 수행 후 남은 분량", null,
                toJson(Map.of("title", remaining.getTitle(), "expectedMinutes",
                        remainingMinutes != null ? remainingMinutes : 0)),
                null, remaining.getVersion());

        return remaining;
    }

    // ===== 공통 =====

    private ExecutionItem findOwnedOrThrow(Long executionItemId, Long userId) {
        ExecutionItem item = executionItemMapper.findByIdAndUserId(executionItemId, userId);
        if (item == null) {
            throw new NotFoundException(ErrorCode.EXECUTION_ITEM_NOT_FOUND);
        }
        return item;
    }

    private void requireVersion(ExecutionItem item, Long expectedVersion) {
        if (!Objects.equals(item.getVersion(), expectedVersion)) {
            throw new ConflictException(ErrorCode.VERSION_CONFLICT);
        }
    }

    private void requireStatusIn(ExecutionItem item, ExecutionStatus... allowed) {
        for (ExecutionStatus status : allowed) {
            if (status.equals(item.getStatus())) {
                return;
            }
        }
        throw new ConflictException(ErrorCode.INVALID_STATUS_TRANSITION);
    }

    private PlacementType derivePlacementType(java.time.LocalDateTime start, java.time.LocalDateTime end) {
        if (start != null && end != null) {
            return PlacementType.TIME_FIXED;
        }
        return PlacementType.DATE_ONLY;
    }

    private void validatePlacement(
            PlacementType placementType,
            LocalDate scheduledDate,
            java.time.LocalDateTime start,
            java.time.LocalDateTime end
    ) {
        if ((start == null) != (end == null)) {
            throw new BadRequestException(ErrorCode.PARTIAL_TIME_RANGE);
        }

        if (PlacementType.TIME_FIXED.equals(placementType)) {
            if (start == null || end == null) {
                throw new BadRequestException(ErrorCode.TIME_FIXED_REQUIRES_TIME);
            }
            if (!end.isAfter(start)) {
                throw new BadRequestException(ErrorCode.INVALID_TIME_RANGE);
            }
            if (!scheduledDate.equals(start.toLocalDate()) || !scheduledDate.equals(end.toLocalDate())) {
                throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
            }
        } else if (PlacementType.DATE_ONLY.equals(placementType)) {
            if (start != null || end != null) {
                throw new BadRequestException(ErrorCode.TASK_MUST_NOT_HAVE_TIME);
            }
            if (scheduledDate == null) {
                throw new BadRequestException(ErrorCode.MISSING_INPUT_VALUE);
            }
        }
    }

    private void insertEvent(
            Long executionItemId, Long userId, ExecutionEventType eventType, ExecutionEventActorType actorType,
            String reason, String beforeState, String afterState, Long beforeVersion, Long afterVersion
    ) {
        executionItemEventMapper.insert(ExecutionItemEvent.builder()
                .userId(userId)
                .executionItemId(executionItemId)
                .eventType(eventType)
                .actorType(actorType)
                .reason(reason)
                .beforeState(beforeState)
                .afterState(afterState)
                .beforeVersion(beforeVersion)
                .afterVersion(afterVersion)
                .build());
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("이벤트 상태 JSON 직렬화 실패", e);
            return null;
        }
    }
}
