package com.jungwoo.project.memo.routine.dto;

import com.jungwoo.project.memo.routine.domain.Routine;
import com.jungwoo.project.memo.routine.domain.RoutineException;
import com.jungwoo.project.memo.routine.domain.RoutineExceptionType;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 반복 일정 한 건.
 *
 * @param ended                 종료됐는가. 저장하지 않고 effectiveUntil을 오늘과 비교해 계산한다 —
 *                              상태 컬럼을 두면 같은 사실이 두 곳에 남아 어긋난다
 * @param crossesMidnight       종료가 시작보다 이르거나 같아 다음 날로 넘어가는가. 화면이
 *                              "다음 날"을 표시할지 정하는 데 쓴다. endTime과 startTime으로
 *                              계산되는 값이라 저장하지 않는다
 * @param hasFutureMovedDate    종료된 뒤에도 남아 있는 미래 보강이 있는가. 조용히 지우지
 *                              않으므로, 남아 있다는 사실을 화면이 보여줘야 한다
 */
public record RoutineResponse(
        Long routineId,
        Long courseId,
        String title,
        String location,
        Set<DayOfWeek> daysOfWeek,
        LocalTime startTime,
        LocalTime endTime,
        LocalDate effectiveFrom,
        LocalDate effectiveUntil,
        boolean ended,
        boolean crossesMidnight,
        boolean hasFutureMovedDate,
        List<RoutineExceptionResponse> exceptions
) {

    public static RoutineResponse of(Routine routine, List<RoutineException> exceptions, LocalDate today) {
        List<RoutineExceptionResponse> exceptionResponses = new ArrayList<>();
        boolean hasFutureMovedDate = false;
        for (RoutineException exception : exceptions) {
            exceptionResponses.add(RoutineExceptionResponse.of(exception));
            if (exception.getType() == RoutineExceptionType.MOVED
                    && exception.getMovedDate() != null
                    && !exception.getMovedDate().isBefore(today)) {
                hasFutureMovedDate = true;
            }
        }
        return new RoutineResponse(
                routine.getRoutineId(),
                routine.getCourseId(),
                routine.getTitle(),
                routine.getLocation(),
                routine.getDaysOfWeek(),
                routine.getStartTime(),
                routine.getEndTime(),
                routine.getEffectiveFrom(),
                routine.getEffectiveUntil(),
                routine.isEndedAsOf(today),
                !routine.getEndTime().isAfter(routine.getStartTime()),
                hasFutureMovedDate,
                exceptionResponses);
    }
}
