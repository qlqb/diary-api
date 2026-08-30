package com.jungwoo.project.memo.routine.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 루틴의 특정 한 주만 쉬거나 옮기는 규칙.
 *
 * <p>userId가 없다. routineId가 이미 소유자를 가리키는데 여기에도 두면 두 값이 어긋날 수
 * 있다 — 소유권은 routines를 JOIN해서 확인한다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoutineException {

    private Long routineExceptionId;

    private Long routineId;

    /** 원래 발생했어야 할 날짜. 이동한 날이 아니다. */
    private LocalDate exceptionDate;

    private RoutineExceptionType type;

    /**
     * 옮겨 간 날. MOVED에만 있다.
     *
     * <p>이 값에는 effectiveFrom/Until을 적용하지 않는다. 마지막 수업 보강이 종강 다음 날로
     * 밀리는 것은 정상이고, 학기 밖으로 옮겼다고 무효 처리하면 안 된다. 반대로
     * effectiveUntil을 보강일까지 늘려서 해결하지도 않는다 — 그건 원본 발생분을 만드는
     * 범위라, 늘리면 그 사이의 정규 요일마다 있지도 않은 수업이 생긴다.
     */
    private LocalDate movedDate;

    /** null이면 원래 시각을 그대로 쓴다. */
    private LocalTime movedStartTime;

    /** null이면 원래 시각을 그대로 쓴다. */
    private LocalTime movedEndTime;

    /**
     * null이면 원래 장소를 쓴다. 보강은 강의실이 바뀌는 경우가 흔하지만 강의계획서에 안
     * 적혀 있어 대부분 null이 된다 — 사용자가 나중에 채울 자리다.
     */
    private String movedLocation;

    private String note;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
