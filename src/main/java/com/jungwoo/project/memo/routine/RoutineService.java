package com.jungwoo.project.memo.routine;

import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.common.time.MinutePrecision;
import com.jungwoo.project.memo.course.CourseService;
import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.course.domain.CourseStatus;
import com.jungwoo.project.memo.routine.domain.Routine;
import com.jungwoo.project.memo.routine.domain.RoutineException;
import com.jungwoo.project.memo.routine.domain.RoutineExceptionType;
import com.jungwoo.project.memo.routine.dto.RoutineExceptionResponse;
import com.jungwoo.project.memo.routine.dto.RoutineExceptionSaveRequest;
import com.jungwoo.project.memo.routine.dto.RoutineExceptionsConflictDetails;
import com.jungwoo.project.memo.routine.dto.RoutineResponse;
import com.jungwoo.project.memo.routine.dto.RoutineSaveRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 반복 일정 CRUD.
 *
 * <p>세 가지 규칙이 이 클래스의 모양을 정한다.
 *
 * <p><b>1. 저장했으면 전개에 나타난다.</b> 허용하지 않을 값이면 저장 시점에 400으로 거부하고,
 * 저장했으면 반드시 전개에 나타난다. 둘 중 하나만 한다 — "저장은 해놓고 전개에서 조용히
 * 누락"이 가장 나쁜 상태다. 이 원칙 때문에 루틴 수정이 기존 예외를 재검증해야 한다.
 *
 * <p><b>2. 수정은 전체 교체(PUT)다.</b> courseId·location·effectiveUntil은 "생략"과 "null로
 * 비우기"를 구분해야 하는데, nullable 필드로는 그 둘이 같아 보인다.
 *
 * <p><b>3. 검증 전에 부모 루틴 행을 잠근다.</b> 루틴 수정과 예외 변경이 겹치면 검증이 서로를
 * 못 본다. 잠금 → 검증 → 변경을 한 트랜잭션 안에서 한다. 나누면 첫 트랜잭션이 끝나는 순간
 * 잠금이 풀려 아무것도 지키지 못하는데, 경합을 일부러 만들지 않는 한 테스트는 그대로 통과한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoutineService {

    private final RoutineMapper routineMapper;
    private final RoutineExceptionMapper routineExceptionMapper;
    private final RoutineReader routineReader;
    private final CourseService courseService;
    private final Clock clock;

    /**
     * 지금 이 앱이 가진 유일한 사용자 시간대 설정이다(AvailabilityEstimateService와 같은 키).
     * "종료됨" 판정은 날짜 비교라 시간대가 하루를 좌우하므로, 새 키를 만들어 두 벌이 되게
     * 하지 않는다.
     */
    @Value("${scheduling.availability.default-time-zone:Asia/Seoul}")
    private String defaultTimeZoneId = "Asia/Seoul";

    // ===== 루틴 =====

    @Transactional(readOnly = true)
    public List<RoutineResponse> list(Long userId) {
        LocalDate today = today();
        List<Routine> routines = routineReader.findAllWithWeekdays(userId);
        List<RoutineResponse> responses = new ArrayList<>();
        for (Routine routine : routines) {
            responses.add(RoutineResponse.of(routine,
                    routineExceptionMapper.findByRoutineId(routine.getRoutineId()), today));
        }
        return responses;
    }

    @Transactional
    public RoutineResponse create(Long userId, RoutineSaveRequest request) {
        Set<DayOfWeek> daysOfWeek = validate(userId, request);

        Routine routine = Routine.builder()
                .userId(userId)
                .courseId(request.getCourseId())
                .title(request.getTitle().trim())
                .location(blankToNull(request.getLocation()))
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .effectiveFrom(request.getEffectiveFrom())
                .effectiveUntil(request.getEffectiveUntil())
                .daysOfWeek(daysOfWeek)
                .build();
        routineMapper.insert(routine);
        routineMapper.insertWeekdays(routine.getRoutineId(), names(daysOfWeek));

        log.info("반복 일정 생성: userId={}, routineId={}, title={}, days={}",
                userId, routine.getRoutineId(), routine.getTitle(), daysOfWeek);
        return RoutineResponse.of(routine, List.of(), today());
    }

    /**
     * 전체 교체. 요일이나 기간이 바뀌면 기존 예외가 소급으로 무효가 될 수 있어, 저장 전에
     * 그 루틴의 모든 예외를 새 요일·새 기간으로 다시 검증한다. 하나라도 무효면 전체를 409로
     * 거부하고 어떤 예외가 걸렸는지 돌려준다 — 자동으로 지우지 않는다. 보강 일정을 요일
     * 변경의 부수효과로 지우면 사용자가 모르는 사이에 일정이 사라진다.
     */
    @Transactional
    public RoutineResponse update(Long userId, Long routineId, RoutineSaveRequest request) {
        Routine locked = lock(userId, routineId);
        Set<DayOfWeek> daysOfWeek = validate(userId, request);

        List<RoutineException> existing = routineExceptionMapper.findByRoutineId(routineId);
        List<Long> conflictingIds = new ArrayList<>();
        List<LocalDate> conflictingDates = new ArrayList<>();
        for (RoutineException exception : existing) {
            // movedDate는 검증 대상이 아니다 — 기간 밖 보강은 정상이다.
            if (!isValidExceptionDate(exception.getExceptionDate(), daysOfWeek,
                    request.getEffectiveFrom(), request.getEffectiveUntil())) {
                conflictingIds.add(exception.getRoutineExceptionId());
                conflictingDates.add(exception.getExceptionDate());
            }
        }
        if (!conflictingIds.isEmpty()) {
            log.info("반복 일정 수정 거부(기존 예외 무효화): userId={}, routineId={}, 걸린 예외={}",
                    userId, routineId, conflictingIds);
            throw new ConflictException(ErrorCode.ROUTINE_EXCEPTIONS_CONFLICT,
                    new RoutineExceptionsConflictDetails(conflictingIds, conflictingDates));
        }

        routineMapper.updateAll(routineId, userId, request.getCourseId(), request.getTitle().trim(),
                blankToNull(request.getLocation()), request.getStartTime(), request.getEndTime(),
                request.getEffectiveFrom(), request.getEffectiveUntil());
        routineMapper.deleteWeekdays(routineId);
        routineMapper.insertWeekdays(routineId, names(daysOfWeek));

        locked.setCourseId(request.getCourseId());
        locked.setTitle(request.getTitle().trim());
        locked.setLocation(blankToNull(request.getLocation()));
        locked.setStartTime(request.getStartTime());
        locked.setEndTime(request.getEndTime());
        locked.setEffectiveFrom(request.getEffectiveFrom());
        locked.setEffectiveUntil(request.getEffectiveUntil());
        locked.setDaysOfWeek(daysOfWeek);

        log.info("반복 일정 수정: userId={}, routineId={}", userId, routineId);
        return RoutineResponse.of(locked, existing, today());
    }

    /** 소프트 삭제. 요일·예외 행은 남긴다 — 복구할 수 있어야 한다. */
    @Transactional
    public void delete(Long userId, Long routineId) {
        if (routineMapper.softDelete(routineId, userId) == 0) {
            throw new NotFoundException(ErrorCode.ROUTINE_NOT_FOUND);
        }
        log.info("반복 일정 삭제: userId={}, routineId={}", userId, routineId);
    }

    // ===== 예외 =====

    @Transactional
    public RoutineExceptionResponse addException(Long userId, Long routineId,
                                                 RoutineExceptionSaveRequest request) {
        Routine locked = lockWithWeekdays(userId, routineId);
        validateException(locked, request);

        RoutineException exception = toEntity(routineId, request);
        try {
            routineExceptionMapper.insert(exception);
        } catch (DuplicateKeyException ex) {
            // uq_routine_exceptions. 한 날짜에 규칙은 하나여야 한다 — "쉬면서 동시에 옮긴다"는
            // 없다. 덮어쓰지 않고 거부해, 기존 예외를 사용자가 직접 고치게 한다.
            throw new ConflictException(ErrorCode.ROUTINE_EXCEPTION_DATE_TAKEN);
        }
        log.info("반복 일정 예외 추가: userId={}, routineId={}, date={}, type={}",
                userId, routineId, request.getExceptionDate(), request.getType());
        return RoutineExceptionResponse.of(exception);
    }

    @Transactional
    public RoutineExceptionResponse updateException(Long userId, Long routineId, Long routineExceptionId,
                                                    RoutineExceptionSaveRequest request) {
        Routine locked = lockWithWeekdays(userId, routineId);
        RoutineException existing = routineExceptionMapper.findByIdAndUserId(routineExceptionId, userId);
        if (existing == null || !existing.getRoutineId().equals(routineId)) {
            throw new NotFoundException(ErrorCode.ROUTINE_EXCEPTION_NOT_FOUND);
        }
        validateException(locked, request);

        RoutineException updated = toEntity(routineId, request);
        updated.setRoutineExceptionId(routineExceptionId);
        try {
            routineExceptionMapper.update(routineExceptionId, updated);
        } catch (DuplicateKeyException ex) {
            throw new ConflictException(ErrorCode.ROUTINE_EXCEPTION_DATE_TAKEN);
        }
        log.info("반복 일정 예외 수정: userId={}, routineId={}, exceptionId={}",
                userId, routineId, routineExceptionId);
        return RoutineExceptionResponse.of(updated);
    }

    /**
     * 삭제는 부모를 잠그지 않는다. 예외를 지우는 것은 무효 상태를 만들지 않기 때문이다 —
     * 잠금이 지키려는 것은 "새로 저장한 값이 곧바로 무효가 되는" 경우뿐이다.
     */
    @Transactional
    public void deleteException(Long userId, Long routineId, Long routineExceptionId) {
        RoutineException existing = routineExceptionMapper.findByIdAndUserId(routineExceptionId, userId);
        if (existing == null || !existing.getRoutineId().equals(routineId)) {
            throw new NotFoundException(ErrorCode.ROUTINE_EXCEPTION_NOT_FOUND);
        }
        routineExceptionMapper.deleteByIdAndRoutineId(routineExceptionId, routineId);
        log.info("반복 일정 예외 삭제: userId={}, routineId={}, exceptionId={}",
                userId, routineId, routineExceptionId);
    }

    // ===== 검증 =====

    private Set<DayOfWeek> validate(Long userId, RoutineSaveRequest request) {
        if (request.getDaysOfWeek() == null || request.getDaysOfWeek().isEmpty()) {
            throw new BadRequestException(ErrorCode.ROUTINE_WEEKDAYS_REQUIRED);
        }
        requireMinuteRange(request.getStartTime(), request.getEndTime());
        if (request.getEffectiveUntil() != null
                && request.getEffectiveUntil().isBefore(request.getEffectiveFrom())) {
            throw new BadRequestException(ErrorCode.ROUTINE_RANGE_INVALID);
        }
        if (request.getCourseId() != null) {
            // getOwned는 보관된 프로젝트도 돌려준다. ACTIVE 검사는 따로 해야 한다.
            Course course = courseService.getOwned(userId, request.getCourseId());
            if (course.getStatus() != CourseStatus.ACTIVE) {
                throw new ConflictException(ErrorCode.COURSE_ARCHIVED);
            }
        }
        // 정렬해 담는다. 저장 순서가 조회 순서를 좌우하지는 않지만, 응답의 요일이 요청마다
        // 뒤바뀌면 화면 토글이 흔들려 보인다.
        return new LinkedHashSet<>(new TreeSet<>(request.getDaysOfWeek()));
    }

    private void validateException(Routine routine, RoutineExceptionSaveRequest request) {
        if (!isValidExceptionDate(request.getExceptionDate(), routine.getDaysOfWeek(),
                routine.getEffectiveFrom(), routine.getEffectiveUntil())) {
            throw new BadRequestException(ErrorCode.ROUTINE_EXCEPTION_DATE_INVALID);
        }
        boolean hasStart = request.getMovedStartTime() != null;
        boolean hasEnd = request.getMovedEndTime() != null;
        if (request.getType() == RoutineExceptionType.MOVED) {
            if (request.getMovedDate() == null || hasStart != hasEnd) {
                throw new BadRequestException(ErrorCode.ROUTINE_EXCEPTION_MOVED_INVALID);
            }
            if (hasStart) {
                requireMinuteRange(request.getMovedStartTime(), request.getMovedEndTime());
            }
        } else if (request.getMovedDate() != null || hasStart || hasEnd
                || request.getMovedLocation() != null) {
            // SKIP은 "그날 없음"이다. 이동 정보가 붙어 있으면 어느 쪽을 의도한 것인지 알 수
            // 없으므로 조용히 버리지 않고 거부한다.
            throw new BadRequestException(ErrorCode.ROUTINE_EXCEPTION_MOVED_INVALID);
        }
    }

    /**
     * exceptionDate는 "실제로 발생했을 날"이어야 한다 — 기간 안이고, 그 날짜의 요일이 이
     * 루틴의 요일이어야 한다. 이 검사가 루틴 수정 시의 재검증과 같은 함수를 쓴다는 것이
     * 요점이다. 두 벌이면 한쪽만 고쳐져 "추가는 되는데 수정은 막히는" 상태가 생긴다.
     */
    private boolean isValidExceptionDate(LocalDate date, Set<DayOfWeek> daysOfWeek,
                                         LocalDate effectiveFrom, LocalDate effectiveUntil) {
        if (date == null || date.isBefore(effectiveFrom)) {
            return false;
        }
        if (effectiveUntil != null && date.isAfter(effectiveUntil)) {
            return false;
        }
        return daysOfWeek.contains(date.getDayOfWeek());
    }

    /**
     * 시작 == 종료는 길이 0 또는 24시간이라 둘 다 의도가 아니다. 자정 넘김(종료 &lt; 시작)은
     * 정상값이라 여기서 막지 않는다.
     */
    private void requireMinuteRange(LocalTime start, LocalTime end) {
        if (start.equals(end)
                || !MinutePrecision.isMinutePrecision(start)
                || !MinutePrecision.isMinutePrecision(end)) {
            throw new BadRequestException(ErrorCode.ROUTINE_TIME_INVALID);
        }
    }

    // ===== 보조 =====

    private Routine lock(Long userId, Long routineId) {
        Routine routine = routineMapper.findByIdAndUserIdForUpdate(routineId, userId);
        if (routine == null) {
            throw new NotFoundException(ErrorCode.ROUTINE_NOT_FOUND);
        }
        return routine;
    }

    private Routine lockWithWeekdays(Long userId, Long routineId) {
        Routine routine = lock(userId, routineId);
        routineReader.attachWeekdays(routine);
        return routine;
    }

    private RoutineException toEntity(Long routineId, RoutineExceptionSaveRequest request) {
        boolean moved = request.getType() == RoutineExceptionType.MOVED;
        return RoutineException.builder()
                .routineId(routineId)
                .exceptionDate(request.getExceptionDate())
                .type(request.getType())
                .movedDate(moved ? request.getMovedDate() : null)
                .movedStartTime(moved ? request.getMovedStartTime() : null)
                .movedEndTime(moved ? request.getMovedEndTime() : null)
                .movedLocation(moved ? blankToNull(request.getMovedLocation()) : null)
                .note(blankToNull(request.getNote()))
                .build();
    }

    private List<String> names(Set<DayOfWeek> daysOfWeek) {
        List<String> names = new ArrayList<>();
        for (DayOfWeek day : daysOfWeek) {
            names.add(day.name());
        }
        return names;
    }

    private LocalDate today() {
        return ZonedDateTime.now(clock).withZoneSameInstant(ZoneId.of(defaultTimeZoneId)).toLocalDate();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
