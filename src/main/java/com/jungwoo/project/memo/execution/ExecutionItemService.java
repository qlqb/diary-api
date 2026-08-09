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
import com.jungwoo.project.memo.execution.dto.ExecutionItemReduceRequest;
import com.jungwoo.project.memo.execution.dto.ExecutionItemReopenRequest;
import com.jungwoo.project.memo.execution.dto.ExecutionItemResponse;
import com.jungwoo.project.memo.execution.dto.ExecutionItemResumeRequest;
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
        if (endDate.isBefore(startDate)) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }
        return executionItemMapper.findByUserIdAndDateRange(userId, startDate, endDate)
                .stream()
                .map(ExecutionItemResponse::from)
                .toList();
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
                .planItemId(request.getPlanItemId())
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

    // ===== 이동 =====

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
        if (toDate.equals(fromDate)) {
            throw new BadRequestException(ErrorCode.MOVE_TARGET_DATE_INVALID);
        }

        var newStart = item.getScheduledStartAt();
        var newEnd = item.getScheduledEndAt();
        if (PlacementType.TIME_FIXED.equals(item.getPlacementType())) {
            long dayDiff = java.time.temporal.ChronoUnit.DAYS.between(fromDate, toDate);
            newStart = item.getScheduledStartAt().plusDays(dayDiff);
            newEnd = item.getScheduledEndAt().plusDays(dayDiff);
        }

        int updated = executionItemMapper.updateForMove(
                executionItemId, userId, request.getVersion(), toDate, newStart, newEnd);
        if (updated != 1) {
            throw new ConflictException(ErrorCode.VERSION_CONFLICT);
        }

        insertEvent(executionItemId, userId, ExecutionEventType.MOVED, ExecutionEventActorType.USER,
                request.getReason(),
                toJson(Map.of("scheduledDate", fromDate)),
                toJson(Map.of("scheduledDate", toDate)),
                item.getVersion(), item.getVersion() + 1);

        log.info("실행 조각 이동: executionItemId={}, {} -> {}", executionItemId, fromDate, toDate);

        return ExecutionItemResponse.from(executionItemMapper.findByIdAndUserId(executionItemId, userId));
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
