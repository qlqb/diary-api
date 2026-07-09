package com.jungwoo.project.memo.schedule;

import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.dailyplan.DailyPlanService;
import com.jungwoo.project.memo.dailyplan.domain.DailyPlan;
import com.jungwoo.project.memo.planitem.PlanItemEventMapper;
import com.jungwoo.project.memo.planitem.domain.PlanItemEvent;
import com.jungwoo.project.memo.planitem.domain.PlanItemEventType;
import com.jungwoo.project.memo.schedule.domain.ScheduleBlock;
import com.jungwoo.project.memo.schedule.domain.ScheduleBlockType;
import com.jungwoo.project.memo.schedule.domain.ScheduleStatus;
import com.jungwoo.project.memo.schedule.dto.ScheduleBlockMoveRequest;
import com.jungwoo.project.memo.schedule.dto.ScheduleBlockReduceRequest;
import com.jungwoo.project.memo.schedule.dto.ScheduleBlockReduceTimeMode;
import com.jungwoo.project.memo.schedule.dto.ScheduleBlockResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 시간 블록 도메인 액션 (move / reduce / hold / complete / uncomplete).
 *
 * 이 액션들은 리소스의 부분 수정이 아니라 부수효과를 포함한 명령이므로
 * PATCH가 아니라 POST 하위 액션으로 분리한다. (v2.1 확정)
 *
 * 모든 액션은 "블록 갱신 + plan_item_events 기록"을 하나의 트랜잭션으로 처리한다.
 *
 * 상태 전이 규칙 (1차-A):
 * - move / reduce / hold : PLANNED 에서만 가능
 * - complete             : PLANNED, HOLD 에서 가능하며 이미 DONE이면 no-op 성공
 * - uncomplete           : DONE 에서 가능하며 이미 PLANNED이면 no-op 성공
 * - toggle API는 사용하지 않는다.
 * - 허용되지 않는 상태 전이는 409 (INVALID_STATUS_TRANSITION)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleBlockActionService {

    // 액션 규칙:
    // - complete는 PLANNED/HOLD를 DONE으로 바꾸고, 이미 DONE이면 성공 no-op으로 처리한다.
    // - uncomplete는 DONE을 PLANNED로 바꾸고, 이미 PLANNED이면 성공 no-op으로 처리한다.
    // - toggle API는 반복 호출 시 상태가 뒤집힐 수 있으므로 사용하지 않는다.

    private final ScheduleBlockMapper scheduleBlockMapper;
    private final PlanItemEventMapper planItemEventMapper;
    private final DailyPlanService dailyPlanService;

    // ===== 이동 (내일로) =====

    /**
     * 블록을 다른 날짜로 이동한다.
     *
     * 트랜잭션 시퀀스 (v2.1 11.5절):
     * 1. 블록 조회 (소유 검증)
     * 2. 상태/대상 날짜 검증
     * 3. 대상 날짜 DailyPlan get-or-create
     * 4. 블록의 blockDate, dailyPlanId 갱신 (시간 지정 블록은 시각도 같은 시각으로 이동)
     * 5. MOVED 이벤트 저장 (todoId는 블록에서 복사)
     * 6. 커밋 — 중간 실패 시 전체 롤백
     *
     * 블록을 복제하지 않는다. 이월 횟수는 이벤트로 추적한다.
     */
    @Transactional
    public ScheduleBlockResponse move(Long scheduleBlockId, Long userId, ScheduleBlockMoveRequest request) {
        log.info("블록 이동 시작: scheduleBlockId={}, userId={}, toDate={}",
                scheduleBlockId, userId, request.getToDate());

        ScheduleBlock block = findBlockOrThrow(scheduleBlockId, userId);
        validateStatusIn(block, ScheduleStatus.PLANNED);

        LocalDate fromDate = block.getBlockDate();
        LocalDate toDate = request.getToDate();

        if (toDate.equals(fromDate)) {
            throw new BadRequestException(ErrorCode.MOVE_TARGET_DATE_INVALID);
        }

        // 대상 날짜의 DailyPlan은 아직 없을 수 있다 → get-or-create
        DailyPlan targetPlan = dailyPlanService.getOrCreate(userId, toDate);

        // 시간 지정 블록은 시각의 날짜 부분만 이동 (시각은 유지)
        long dayDiff = Duration.between(fromDate.atStartOfDay(), toDate.atStartOfDay()).toDays();
        LocalDateTime newStart = block.getStartTime() == null ? null : block.getStartTime().plusDays(dayDiff);
        LocalDateTime newEnd = block.getEndTime() == null ? null : block.getEndTime().plusDays(dayDiff);

        scheduleBlockMapper.updateForMove(scheduleBlockId, userId,
                toDate, targetPlan.getDailyPlanId(), newStart, newEnd);

        planItemEventMapper.insert(PlanItemEvent.builder()
                .userId(userId)
                .todoId(block.getTodoId())                 // 서버가 블록에서 복사 — 클라이언트 입력 아님
                .scheduleBlockId(scheduleBlockId)
                .eventType(PlanItemEventType.MOVED)
                .eventDate(LocalDate.now())
                .fromDate(fromDate)
                .toDate(toDate)
                .memo(request.getMemo())
                .build());

        log.info("블록 이동 완료: scheduleBlockId={}, {} → {}", scheduleBlockId, fromDate, toDate);

        return ScheduleBlockResponse.from(scheduleBlockMapper.findByIdAndUserId(scheduleBlockId, userId));
    }

    // ===== 작게 줄이기 =====

    @Transactional
    public ScheduleBlockResponse reduce(Long scheduleBlockId, Long userId, ScheduleBlockReduceRequest request) {
        log.info("블록 축소 시작: scheduleBlockId={}, userId={}", scheduleBlockId, userId);

        ScheduleBlock block = findBlockOrThrow(scheduleBlockId, userId);
        validateStatusIn(block, ScheduleStatus.PLANNED);

        String beforeTitle = block.getTitle();
        String reducedTitle = request.getReducedTitle();

        if (reducedTitle == null || reducedTitle.isBlank()) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }

        // 축소 후의 블록은 여전히 PLANNED — REDUCED는 상태가 아니라 사건이다
        ScheduleBlockType targetBlockType = block.getBlockType();
        LocalDateTime targetStartTime = block.getStartTime();
        LocalDateTime targetEndTime = block.getEndTime();
        ScheduleBlockReduceTimeMode timeMode = request.getTimeMode() == null
                ? ScheduleBlockReduceTimeMode.KEEP
                : request.getTimeMode();

        switch (timeMode) {
            case KEEP -> {
                if (hasReduceTimeAdjustment(request)) {
                    throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
                }
            }
            case SHRINK -> {
                targetBlockType = request.getBlockType();
                targetStartTime = request.getStartTime();
                targetEndTime = request.getEndTime();
                validateShrinkTime(block, targetBlockType, targetStartTime, targetEndTime);
            }
            case CLEAR -> {
                validateClearTime(request, block);
                targetBlockType = ScheduleBlockType.TASK;
                targetStartTime = null;
                targetEndTime = null;
            }
        }

        validateReduceHasActualChange(block, reducedTitle, targetBlockType, targetStartTime, targetEndTime);

        scheduleBlockMapper.updateForReduce(scheduleBlockId, userId,
                reducedTitle, targetBlockType, targetStartTime, targetEndTime);

        planItemEventMapper.insert(PlanItemEvent.builder()
                .userId(userId)
                .todoId(block.getTodoId())
                .scheduleBlockId(scheduleBlockId)
                .eventType(PlanItemEventType.REDUCED)
                .eventDate(LocalDate.now())
                .beforeTitle(beforeTitle)
                .afterTitle(reducedTitle)
                .beforeBlockType(block.getBlockType())
                .afterBlockType(targetBlockType)
                .beforeStartTime(block.getStartTime())
                .afterStartTime(targetStartTime)
                .beforeEndTime(block.getEndTime())
                .afterEndTime(targetEndTime)
                .memo(request.getMemo())
                .build());

        log.info("블록 축소 완료: scheduleBlockId={}, '{}' → '{}'", scheduleBlockId, beforeTitle, reducedTitle);

        return ScheduleBlockResponse.from(scheduleBlockMapper.findByIdAndUserId(scheduleBlockId, userId));
    }

    // ===== 보류 =====

    @Transactional
    public ScheduleBlockResponse hold(Long scheduleBlockId, Long userId, String memo) {
        log.info("블록 보류 시작: scheduleBlockId={}, userId={}", scheduleBlockId, userId);

        ScheduleBlock block = findBlockOrThrow(scheduleBlockId, userId);
        validateStatusIn(block, ScheduleStatus.PLANNED);

        scheduleBlockMapper.updateStatus(scheduleBlockId, userId, ScheduleStatus.HOLD);

        planItemEventMapper.insert(PlanItemEvent.builder()
                .userId(userId)
                .todoId(block.getTodoId())
                .scheduleBlockId(scheduleBlockId)
                .eventType(PlanItemEventType.HOLD)
                .eventDate(LocalDate.now())
                .memo(memo)
                .build());

        log.info("블록 보류 완료: scheduleBlockId={}", scheduleBlockId);

        return ScheduleBlockResponse.from(scheduleBlockMapper.findByIdAndUserId(scheduleBlockId, userId));
    }

    // ===== 완료 =====

    @Transactional
    public ScheduleBlockResponse complete(Long scheduleBlockId, Long userId) {
        log.info("블록 완료 시작: scheduleBlockId={}, userId={}", scheduleBlockId, userId);

        ScheduleBlock block = findBlockOrThrow(scheduleBlockId, userId);
        if (ScheduleStatus.DONE.equals(block.getStatus())) {
            log.info("블록 완료 no-op 처리: scheduleBlockId={}", scheduleBlockId);
            return ScheduleBlockResponse.from(block);
        }

        validateStatusIn(block, ScheduleStatus.PLANNED, ScheduleStatus.HOLD);

        int updatedRows = scheduleBlockMapper.completeIfNotDone(scheduleBlockId, userId);

        if (updatedRows == 1) {
            planItemEventMapper.insert(PlanItemEvent.builder()
                .userId(userId)
                .todoId(block.getTodoId())
                .scheduleBlockId(scheduleBlockId)
                .eventType(PlanItemEventType.DONE)
                .eventDate(LocalDate.now())
                .build());
        }

        log.info("블록 완료 처리: scheduleBlockId={}", scheduleBlockId);

        return ScheduleBlockResponse.from(scheduleBlockMapper.findByIdAndUserId(scheduleBlockId, userId));
    }

    // ===== 완료취소 =====
    @Transactional
    public ScheduleBlockResponse uncomplete(Long scheduleBlockId, Long userId) {
        log.info("블록 완료취소 시작: scheduleBlockId={}, userId={}", scheduleBlockId, userId);

        ScheduleBlock block = findBlockOrThrow(scheduleBlockId, userId);
        if (ScheduleStatus.PLANNED.equals(block.getStatus())) {
            log.info("블록 완료취소 no-op 처리: scheduleBlockId={}", scheduleBlockId);
            return ScheduleBlockResponse.from(block);
        }

        validateStatusIn(block, ScheduleStatus.DONE);

        int updatedRows = scheduleBlockMapper.uncompleteIfDone(scheduleBlockId, userId);

        if (updatedRows == 1) {
            planItemEventMapper.insert(PlanItemEvent.builder()
                .userId(userId)
                .todoId(block.getTodoId())
                .scheduleBlockId(scheduleBlockId)
                .eventType(PlanItemEventType.REOPENED)
                .eventDate(LocalDate.now())
                .build());
        }

        log.info("블록 완료취소 처리: scheduleBlockId={}", scheduleBlockId);

        return ScheduleBlockResponse.from(scheduleBlockMapper.findByIdAndUserId(scheduleBlockId, userId));
    }

    // ===== pending 조회 =====

    /**
     * 기준 운영일 이전 block_date에 속한 PLANNED 블록을 조회한다.
     * pending은 실패가 아니라 아직 결론을 내리지 않은 이전 계획 항목이다.
     */
    @Transactional(readOnly = true)
    public List<ScheduleBlockResponse> getPendingBlocks(Long userId, LocalDate baseDate) {
        log.info("pending 블록 조회: userId={}, baseDate={}", userId, baseDate);

        return scheduleBlockMapper.findPendingBefore(userId, baseDate)
                .stream()
                .map(ScheduleBlockResponse::from)
                .toList();
    }

    // ===== 공통 =====

    private ScheduleBlock findBlockOrThrow(Long scheduleBlockId, Long userId) {
        ScheduleBlock block = scheduleBlockMapper.findByIdAndUserId(scheduleBlockId, userId);
        if (block == null) {
            throw new NotFoundException(ErrorCode.SCHEDULE_BLOCK_NOT_FOUND);
        }
        return block;
    }

    private boolean hasReduceTimeAdjustment(ScheduleBlockReduceRequest request) {
        return request.getBlockType() != null
                || request.getStartTime() != null
                || request.getEndTime() != null;
    }

    private void validateShrinkTime(
            ScheduleBlock block,
            ScheduleBlockType blockType,
            LocalDateTime startTime,
            LocalDateTime endTime
    ) {
        if (!ScheduleBlockType.TIME_FIXED.equals(block.getBlockType())
                || block.getStartTime() == null
                || block.getEndTime() == null) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }

        if (!ScheduleBlockType.TIME_FIXED.equals(blockType)) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }

        if (startTime == null || endTime == null) {
            throw new BadRequestException(ErrorCode.TIME_FIXED_REQUIRES_TIME);
        }

        if (!endTime.isAfter(startTime)) {
            throw new BadRequestException(ErrorCode.INVALID_TIME_RANGE);
        }

        if (!block.getBlockDate().equals(startTime.toLocalDate())
                || !block.getBlockDate().equals(endTime.toLocalDate())) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }

        Duration beforeDuration = Duration.between(block.getStartTime(), block.getEndTime());
        Duration afterDuration = Duration.between(startTime, endTime);
        if (!afterDuration.minus(beforeDuration).isNegative()) {
            throw new BadRequestException(ErrorCode.INVALID_TIME_RANGE);
        }
    }

    private void validateClearTime(ScheduleBlockReduceRequest request, ScheduleBlock block) {
        if (!ScheduleBlockType.TASK.equals(request.getBlockType())) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }

        if (request.getStartTime() != null || request.getEndTime() != null) {
            throw new BadRequestException(ErrorCode.TASK_MUST_NOT_HAVE_TIME);
        }

        if (ScheduleBlockType.TASK.equals(block.getBlockType())
                && block.getStartTime() == null
                && block.getEndTime() == null) {
            throw new BadRequestException(ErrorCode.REDUCE_TITLE_UNCHANGED);
        }
    }

    private void validateReduceHasActualChange(
            ScheduleBlock block,
            String reducedTitle,
            ScheduleBlockType targetBlockType,
            LocalDateTime targetStartTime,
            LocalDateTime targetEndTime
    ) {
        boolean changed = !Objects.equals(block.getTitle(), reducedTitle)
                || !Objects.equals(block.getBlockType(), targetBlockType)
                || !Objects.equals(block.getStartTime(), targetStartTime)
                || !Objects.equals(block.getEndTime(), targetEndTime);

        if (!changed) {
            throw new BadRequestException(ErrorCode.REDUCE_TITLE_UNCHANGED);
        }
    }

    private void validateStatusIn(ScheduleBlock block, ScheduleStatus... allowed) {
        for (ScheduleStatus status : allowed) {
            if (status.equals(block.getStatus())) {
                return;
            }
        }
        log.warn("허용되지 않는 상태 전이 시도: scheduleBlockId={}, currentStatus={}",
                block.getScheduleBlockId(), block.getStatus());
        throw new ConflictException(ErrorCode.INVALID_STATUS_TRANSITION);
    }
}
