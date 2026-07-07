package com.jungwoo.project.memo.schedule;

import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.schedule.domain.ScheduleBlock;
import com.jungwoo.project.memo.schedule.domain.ScheduleBlockType;
import com.jungwoo.project.memo.schedule.domain.ScheduleOriginType;
import com.jungwoo.project.memo.schedule.dto.ScheduleBlockCreateRequest;
import com.jungwoo.project.memo.schedule.dto.ScheduleBlockPatchRequest;
import com.jungwoo.project.memo.schedule.dto.ScheduleBlockResponse;
import com.jungwoo.project.memo.schedule.dto.ScheduleBlockUpdateRequest;
import com.jungwoo.project.memo.todo.TodoMapper;
import com.jungwoo.project.memo.todo.domain.Todo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleBlockService {

    private final ScheduleBlockMapper scheduleBlockMapper;
    private final TodoMapper todoMapper;

    // ===== 생성 =====

    @Transactional
    public ScheduleBlockResponse createScheduleBlock(Long userId, ScheduleBlockCreateRequest request) {
        log.info("시간 블록 생성 시작: userId={}, title={}", userId, request.getTitle());

        validateBlockTime(
                request.getBlockType(),
                request.getBlockDate(),
                request.getStartTime(),
                request.getEndTime()
        );

        if (request.getTodoId() != null) {
            validateTodoOwnership(request.getTodoId(), userId);
        }

        ScheduleBlock block = ScheduleBlock.builder()
                .userId(userId)
                .todoId(request.getTodoId())
                .blockDate(request.getBlockDate())
                .title(request.getTitle())
                .blockType(request.getBlockType())
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .memo(request.getMemo())
                .originType(ScheduleOriginType.MANUAL)
                .modifiedAfterCreation(false)
                .isDeleted(false)
                .build();

        scheduleBlockMapper.insert(block);

        ScheduleBlock saved = scheduleBlockMapper.findByIdAndUserId(block.getScheduleBlockId(), userId);

        log.info("시간 블록 생성 완료: scheduleBlockId={}", block.getScheduleBlockId());

        return ScheduleBlockResponse.from(saved);
    }

    // ===== 조회 =====

    @Transactional(readOnly = true)
    public List<ScheduleBlockResponse> getScheduleBlocksByDate(Long userId, LocalDate blockDate) {
        log.info("날짜별 시간 블록 조회: userId={}, blockDate={}", userId, blockDate);

        return scheduleBlockMapper.findByUserIdAndDate(userId, blockDate)
                .stream()
                .map(ScheduleBlockResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public ScheduleBlockResponse getScheduleBlock(Long scheduleBlockId, Long userId) {
        log.info("시간 블록 단건 조회: scheduleBlockId={}, userId={}", scheduleBlockId, userId);

        return ScheduleBlockResponse.from(findBlockByIdAndUserId(scheduleBlockId, userId));
    }

    // ===== 전체 수정 (PUT) =====

    /**
     * 시간 블록 전체 수정
     * 모든 필드를 요청값으로 덮어씀.
     * null 허용 필드(todoId, memo)는 null로 보내면 null로 덮어써진다.
     */
    @Transactional
    public ScheduleBlockResponse replaceScheduleBlock(
            Long scheduleBlockId, Long userId, ScheduleBlockUpdateRequest request) {
        log.info("시간 블록 전체 수정(PUT) 시작: scheduleBlockId={}, userId={}", scheduleBlockId, userId);

        ScheduleBlock block = findBlockByIdAndUserId(scheduleBlockId, userId);

        validateBlockTime(
                request.getBlockType(),
                request.getBlockDate(),
                request.getStartTime(),
                request.getEndTime()
        );

        if (request.getTodoId() != null) {
            validateTodoOwnership(request.getTodoId(), userId);
        }

        // 전체 교체 — 모든 필드를 요청값으로 덮어씀
        block.setTodoId(request.getTodoId());       // null이면 연결 해제
        block.setBlockDate(request.getBlockDate());
        block.setTitle(request.getTitle());
        block.setBlockType(request.getBlockType());
        block.setStartTime(request.getStartTime());
        block.setEndTime(request.getEndTime());
        block.setMemo(request.getMemo());           // null이면 null로 덮어씀

        if (ScheduleOriginType.AI_GENERATED.equals(block.getOriginType())) {
            block.setModifiedAfterCreation(true);
        }

        scheduleBlockMapper.update(block);

        log.info("시간 블록 전체 수정(PUT) 완료: scheduleBlockId={}", scheduleBlockId);

        return ScheduleBlockResponse.from(scheduleBlockMapper.findByIdAndUserId(scheduleBlockId, userId));
    }

    // ===== 부분 수정 (PATCH) =====

    /**
     * 시간 블록 부분 수정
     * null인 필드는 기존값 유지. 보낸 필드만 수정.
     */
    @Transactional
    public ScheduleBlockResponse updateScheduleBlock(
            Long scheduleBlockId, Long userId, ScheduleBlockPatchRequest request) {
        log.info("시간 블록 부분 수정(PATCH) 시작: scheduleBlockId={}, userId={}", scheduleBlockId, userId);

        ScheduleBlock block = findBlockByIdAndUserId(scheduleBlockId, userId);

        // 요청값을 기존값에 병합한 최종값으로 검증
        // null인 필드는 기존값을 유지
        LocalDate     mergedDate  = request.getBlockDate() != null ? request.getBlockDate() : block.getBlockDate();
        ScheduleBlockType mergedType = request.getBlockType() != null ? request.getBlockType() : block.getBlockType();
        LocalDateTime mergedStart = request.getStartTime() != null ? request.getStartTime() : block.getStartTime();
        LocalDateTime mergedEnd   = request.getEndTime()   != null ? request.getEndTime()   : block.getEndTime();

        validateBlockTime(mergedType, mergedDate, mergedStart, mergedEnd);

        if (request.getTodoId() != null) {
            validateTodoOwnership(request.getTodoId(), userId);
        }

        // null이 아닌 필드만 수정
        if (request.getTodoId()    != null) block.setTodoId(request.getTodoId());
        if (request.getBlockDate() != null) block.setBlockDate(request.getBlockDate());
        if (request.getTitle()     != null) block.setTitle(request.getTitle());
        if (request.getBlockType() != null) block.setBlockType(request.getBlockType());
        if (request.getStartTime() != null) block.setStartTime(request.getStartTime());
        if (request.getEndTime()   != null) block.setEndTime(request.getEndTime());
        if (request.getMemo()      != null) block.setMemo(request.getMemo());

        if (ScheduleOriginType.AI_GENERATED.equals(block.getOriginType())) {
            block.setModifiedAfterCreation(true);
        }

        scheduleBlockMapper.update(block);

        log.info("시간 블록 부분 수정(PATCH) 완료: scheduleBlockId={}", scheduleBlockId);

        return ScheduleBlockResponse.from(scheduleBlockMapper.findByIdAndUserId(scheduleBlockId, userId));
    }

    // ===== 삭제 =====

    @Transactional
    public void deleteScheduleBlock(Long scheduleBlockId, Long userId) {
        log.info("시간 블록 삭제 시작: scheduleBlockId={}, userId={}", scheduleBlockId, userId);

        ScheduleBlock block = findBlockByIdAndUserId(scheduleBlockId, userId);
        block.setIsDeleted(true);

        scheduleBlockMapper.update(block);

        log.info("시간 블록 삭제 완료: scheduleBlockId={}", scheduleBlockId);
    }

    // ===== private 헬퍼 =====

    private ScheduleBlock findBlockByIdAndUserId(Long scheduleBlockId, Long userId) {
        ScheduleBlock block = scheduleBlockMapper.findByIdAndUserId(scheduleBlockId, userId);

        if (block == null) {
            throw new NotFoundException(ErrorCode.SCHEDULE_BLOCK_NOT_FOUND);
        }

        return block;
    }

    private void validateBlockTime(
            ScheduleBlockType blockType,
            LocalDate blockDate,
            LocalDateTime startTime,
            LocalDateTime endTime
    ) {
        if (ScheduleBlockType.TIME_FIXED.equals(blockType) && (startTime == null || endTime == null)) {
            throw new BadRequestException(ErrorCode.TIME_FIXED_REQUIRES_TIME);
        }

        if ((startTime == null) != (endTime == null)) {
            throw new BadRequestException(ErrorCode.PARTIAL_TIME_RANGE);
        }

        if (startTime == null) {
            return;
        }

        validateTimeRange(startTime, endTime);
        validateBlockDateConsistency(blockDate, startTime, endTime);
    }

    private void validateTimeRange(LocalDateTime startTime, LocalDateTime endTime) {
        if (!endTime.isAfter(startTime)) {
            throw new BadRequestException(ErrorCode.INVALID_TIME_RANGE);
        }
    }

    /**
     * blockDate와 startTime/endTime의 날짜가 모두 일치하는지 검증
     * 예: blockDate=2026-07-01, startTime=2026-07-01T09:00 → 정상
     *     blockDate=2026-07-01, startTime=2026-07-02T09:00 → 오류
     */
    private void validateBlockDateConsistency(
            LocalDate blockDate, LocalDateTime startTime, LocalDateTime endTime) {
        if (!startTime.toLocalDate().equals(blockDate)) {
            throw new BadRequestException(ErrorCode.BLOCK_DATE_MISMATCH);
        }
        if (!endTime.toLocalDate().equals(blockDate)) {
            throw new BadRequestException(ErrorCode.BLOCK_DATE_MISMATCH);
        }
    }

    private void validateTodoOwnership(Long todoId, Long userId) {
        Todo todo = todoMapper.findByIdAndUserId(todoId, userId);

        if (todo == null) {
            throw new NotFoundException(ErrorCode.TODO_NOT_FOUND);
        }
    }
}
