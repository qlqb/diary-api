package com.jungwoo.project.memo.routine;

import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.ForbiddenException;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.common.time.MinutePrecision;
import com.jungwoo.project.memo.course.CourseService;
import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.course.domain.CourseStatus;
import com.jungwoo.project.memo.routine.domain.Routine;
import com.jungwoo.project.memo.routine.domain.RoutineException;
import com.jungwoo.project.memo.routine.domain.RoutineOccurrence;
import com.jungwoo.project.memo.routine.domain.RoutineExceptionConflictReason;
import com.jungwoo.project.memo.routine.domain.RoutineExceptionType;
import com.jungwoo.project.memo.routine.dto.RoutineExceptionResponse;
import com.jungwoo.project.memo.routine.dto.RoutineExceptionSaveRequest;
import com.jungwoo.project.memo.routine.dto.RoutineExceptionsConflictDetails;
import com.jungwoo.project.memo.routine.dto.LeadMinutesBatchItemRequest;
import com.jungwoo.project.memo.routine.dto.LeadMinutesPendingGroup;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    /** 이동시간 질문 대상은 "요청 기간에 실제로 발생하는" 수업이다. 발생 여부는 전개가 안다. */
    private final RoutineOccurrenceService routineOccurrenceService;

    /** 이동시간 상한(분). 8시간 — 그보다 길면 이동이 아니라 일정이다. RoutineSaveRequest의 @Max와 같다. */
    public static final int MAX_LEAD_MINUTES = 480;

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
                .leadMinutes(request.getLeadMinutes())
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
        List<RoutineExceptionsConflictDetails.Conflict> conflicts = new ArrayList<>();
        for (RoutineException exception : existing) {
            // movedDate는 검증 대상이 아니다 — 기간 밖 보강은 정상이다.
            List<RoutineExceptionConflictReason> reasons = conflictReasons(
                    exception.getExceptionDate(), daysOfWeek,
                    request.getEffectiveFrom(), request.getEffectiveUntil());
            if (!reasons.isEmpty()) {
                conflicts.add(new RoutineExceptionsConflictDetails.Conflict(
                        exception.getRoutineExceptionId(), exception.getExceptionDate(), reasons));
            }
        }
        if (!conflicts.isEmpty()) {
            log.info("반복 일정 수정 거부(기존 예외 무효화): userId={}, routineId={}, 걸린 예외={}",
                    userId, routineId, conflicts);
            throw new ConflictException(ErrorCode.ROUTINE_EXCEPTIONS_CONFLICT,
                    new RoutineExceptionsConflictDetails(conflicts));
        }

        routineMapper.updateAll(routineId, userId, request.getCourseId(), request.getTitle().trim(),
                blankToNull(request.getLocation()), request.getStartTime(), request.getEndTime(),
                request.getLeadMinutes(), request.getEffectiveFrom(), request.getEffectiveUntil());
        routineMapper.deleteWeekdays(routineId);
        routineMapper.insertWeekdays(routineId, names(daysOfWeek));

        locked.setCourseId(request.getCourseId());
        locked.setTitle(request.getTitle().trim());
        locked.setLocation(blankToNull(request.getLocation()));
        locked.setStartTime(request.getStartTime());
        locked.setEndTime(request.getEndTime());
        if (request.getLeadMinutes() != null) {
            // null은 "건드리지 않음"이다(RoutineSaveRequest 참고). 저장된 값이 응답에 남는다.
            locked.setLeadMinutes(request.getLeadMinutes());
        }
        locked.setEffectiveFrom(request.getEffectiveFrom());
        locked.setEffectiveUntil(request.getEffectiveUntil());
        locked.setDaysOfWeek(daysOfWeek);

        log.info("반복 일정 수정: userId={}, routineId={}", userId, routineId);
        return RoutineResponse.of(locked, existing, today());
    }

    /** 질문 카드에 보여줄 샘플 시각 수. 세 개면 "화 14:00, 수 09:00, 목 10:00 …"로 충분하다. */
    private static final int LEAD_PENDING_SAMPLE_LIMIT = 3;
    /** 질문 카드의 수업 묶음. 통학은 수업마다 다르지 않아 한 줄로 묻는다. */
    static final String LEAD_PENDING_CLASS_GROUP_KEY = "class";
    static final String LEAD_PENDING_CLASS_LABEL = "수업";

    /**
     * 아직 이동시간을 정하지 않은 수업을 한 묶음으로 돌려준다. 계획 초안을 만들기 전에 이
     * 목록이 비어 있지 않으면 화면이 먼저 묻는다.
     *
     * <p>조건: lead_minutes IS NULL, 삭제되지 않음(매퍼), courseId != null(수업), 그리고
     * <b>요청 기간 안에 발생분이 있음</b>. 기간에 돌지 않는 수업의 이동시간은 물어도 이번
     * 계획에 아무 영향이 없다. 0(없음)과 양수는 이미 답한 것이라 다시 묻지 않는다.
     *
     * <p>수업이 아닌 루틴(알바·운동)은 묻지 않는다 — 통학 정책은 수업의 것이고, 다른
     * 일정의 이동시간은 폼이나 대화로 넣는다.
     */
    @Transactional(readOnly = true)
    public List<LeadMinutesPendingGroup> pendingLeadMinutes(Long userId, LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null || endDate.isBefore(startDate)) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }
        Map<Long, Routine> pending = new LinkedHashMap<>();
        for (Routine routine : routineReader.findAllWithWeekdays(userId)) {
            if (routine.getLeadMinutes() == null && routine.getCourseId() != null) {
                pending.put(routine.getRoutineId(), routine);
            }
        }
        if (pending.isEmpty()) {
            return List.of();
        }
        List<Long> routineIds = new ArrayList<>();
        List<LeadMinutesPendingGroup.Sample> sample = new ArrayList<>();
        for (RoutineOccurrence occurrence : routineOccurrenceService.expand(userId, startDate, endDate)) {
            if (occurrence.lead() || !pending.containsKey(occurrence.routineId())) {
                continue;
            }
            if (!routineIds.contains(occurrence.routineId())) {
                routineIds.add(occurrence.routineId());
            }
            if (sample.size() < LEAD_PENDING_SAMPLE_LIMIT) {
                sample.add(new LeadMinutesPendingGroup.Sample(
                        occurrence.startAt().getDayOfWeek(), occurrence.startAt().toLocalTime()));
            }
        }
        if (routineIds.isEmpty()) {
            return List.of();
        }
        routineIds.sort(null);
        return List.of(new LeadMinutesPendingGroup(
                LEAD_PENDING_CLASS_GROUP_KEY, LEAD_PENDING_CLASS_LABEL, routineIds, sample));
    }

    /** 공백·특수문자를 빼고 소문자로. "자료구조 (월)"과 "자료구조(월)"이 같은 묶음이 된다. */
    static String leadGroupKey(String title) {
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < title.length(); i++) {
            char c = title.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                key.append(c);
            }
        }
        String normalized = key.toString().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? title.trim().toLowerCase(Locale.ROOT) : normalized;
    }

    /**
     * 여러 루틴의 이동시간을 한 번에. <b>전부 되거나 전부 안 된다.</b>
     *
     * <p>본인 소유가 아니거나 없는(삭제된) routineId가 하나라도 섞이면 아무것도 저장하지
     * 않고 403이다. 부분 적용을 하면 "수업 5개 중 4개만 이동시간이 생긴" 상태를 사용자가
     * 카드만 보고는 알 수 없다. 쓰기 전에 전부 잠그고(FOR UPDATE) 확인한 뒤에야 쓴다.
     * 같은 id가 두 번 오면 뒤의 값이 이긴다.
     *
     * <p>값은 0~480이고 null은 받지 않는다 — 이 경로는 "답했다"를 저장하는 경로라 "아직
     * 모름"으로 되돌리는 값이 있을 수 없다. 0이 "없음"이다.
     */
    @Transactional
    public List<RoutineResponse> updateLeadMinutes(Long userId, List<LeadMinutesBatchItemRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }
        Map<Long, Integer> byRoutine = new LinkedHashMap<>();
        for (LeadMinutesBatchItemRequest request : requests) {
            if (request == null || request.getRoutineId() == null || request.getLeadMinutes() == null
                    || request.getLeadMinutes() < 0 || request.getLeadMinutes() > MAX_LEAD_MINUTES) {
                throw new BadRequestException(ErrorCode.ROUTINE_LEAD_MINUTES_INVALID);
            }
            byRoutine.put(request.getRoutineId(), request.getLeadMinutes());
        }

        // 잠금은 id 오름차순 — 두 요청이 겹치는 집합을 다른 순서로 잠그면 교착이 난다.
        Map<Long, Routine> locked = new LinkedHashMap<>();
        for (Long routineId : new TreeSet<>(byRoutine.keySet())) {
            Routine routine = routineMapper.findByIdAndUserIdForUpdate(routineId, userId);
            if (routine == null) {
                log.info("이동시간 일괄 저장 거부(소유 아님 또는 없음): userId={}, routineId={}", userId, routineId);
                throw new ForbiddenException(ErrorCode.NOT_RESOURCE_OWNER);
            }
            locked.put(routineId, routine);
        }

        LocalDate today = today();
        List<RoutineResponse> responses = new ArrayList<>();
        for (Map.Entry<Long, Routine> entry : locked.entrySet()) {
            Integer leadMinutes = byRoutine.get(entry.getKey());
            routineMapper.updateLeadMinutes(entry.getKey(), userId, leadMinutes);
            Routine routine = entry.getValue();
            routine.setLeadMinutes(leadMinutes);
            routineReader.attachWeekdays(routine);
            responses.add(RoutineResponse.of(routine,
                    routineExceptionMapper.findByRoutineId(entry.getKey()), today));
        }
        log.info("이동시간 일괄 저장: userId={}, count={}, values={}", userId, responses.size(), byRoutine);
        return responses;
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
        if (request.getExceptionDate() == null
                || !conflictReasons(request.getExceptionDate(), routine.getDaysOfWeek(),
                        routine.getEffectiveFrom(), routine.getEffectiveUntil()).isEmpty()) {
            // 저장 경로는 400 하나로 거절한다. 사유 목록이 필요한 곳은 루틴 수정(409)뿐이다 —
            // 거기서는 이미 저장된 여러 예외가 한꺼번에 걸리고, 화면이 그 목록을 보여줘야 한다.
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
     * exceptionDate가 "실제로 발생했을 날"인지 보고, 걸린 사유를 전부 돌려준다. 빈 목록이면
     * 유효하다.
     *
     * <p>이 검사가 예외 저장과 루틴 수정 시의 재검증에서 같은 함수를 쓴다는 것이 요점이다.
     * 두 벌이면 한쪽만 고쳐져 "추가는 되는데 수정은 막히는" 상태가 생긴다.
     *
     * <p><b>두 조건을 각각 독립적으로 검사하고 첫 위반에서 빠져나가지 않는다.</b> 요일과
     * 기간을 한 번에 바꾸면 한 예외가 둘 다 위반할 수 있는데, 먼저 걸린 하나만 돌려주면
     * 화면은 "기간을 고치면 되겠다"고 알려주고 사용자가 기간을 고친 뒤에야 요일도 안 맞는다는
     * 것을 알게 된다. 그건 boolean이던 때와 같다.
     */
    private List<RoutineExceptionConflictReason> conflictReasons(
            LocalDate date, Set<DayOfWeek> daysOfWeek,
            LocalDate effectiveFrom, LocalDate effectiveUntil) {
        List<RoutineExceptionConflictReason> reasons = new ArrayList<>();
        if (!daysOfWeek.contains(date.getDayOfWeek())) {
            reasons.add(RoutineExceptionConflictReason.DAY_OF_WEEK_MISMATCH);
        }
        if (date.isBefore(effectiveFrom)
                || (effectiveUntil != null && date.isAfter(effectiveUntil))) {
            reasons.add(RoutineExceptionConflictReason.OUTSIDE_EFFECTIVE_RANGE);
        }
        return reasons;
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
