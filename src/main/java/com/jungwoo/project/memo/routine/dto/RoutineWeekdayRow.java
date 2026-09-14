package com.jungwoo.project.memo.routine.dto;

import lombok.Data;

/**
 * routine_weekdays 한 행. 여러 루틴의 요일을 한 번에 읽어 와 붙이기 위한 운반 타입이다.
 */
@Data
public class RoutineWeekdayRow {

    private Long routineId;

    private String dayOfWeek;
}
