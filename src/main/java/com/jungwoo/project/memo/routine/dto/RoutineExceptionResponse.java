package com.jungwoo.project.memo.routine.dto;

import com.jungwoo.project.memo.routine.domain.RoutineException;
import com.jungwoo.project.memo.routine.domain.RoutineExceptionType;

import java.time.LocalDate;
import java.time.LocalTime;

public record RoutineExceptionResponse(
        Long routineExceptionId,
        Long routineId,
        LocalDate exceptionDate,
        RoutineExceptionType type,
        LocalDate movedDate,
        LocalTime movedStartTime,
        LocalTime movedEndTime,
        String movedLocation,
        String note
) {
    public static RoutineExceptionResponse of(RoutineException exception) {
        return new RoutineExceptionResponse(
                exception.getRoutineExceptionId(),
                exception.getRoutineId(),
                exception.getExceptionDate(),
                exception.getType(),
                exception.getMovedDate(),
                exception.getMovedStartTime(),
                exception.getMovedEndTime(),
                exception.getMovedLocation(),
                exception.getNote());
    }
}
