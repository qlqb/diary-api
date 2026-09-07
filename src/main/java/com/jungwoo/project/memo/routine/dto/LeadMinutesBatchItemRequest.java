package com.jungwoo.project.memo.routine.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * PATCH /api/routines/lead-minutes 의 원소. 여러 일정에 이동시간을 한 번에 넣는다.
 *
 * <p>지금은 대상이 반복 일정(routineId)뿐이지만 이름에 그것을 박지 않는다 — 약속에도
 * 이동시간이 붙을 수 있고, 그때 같은 묶음 요청이 대상 종류만 늘리면 된다.
 *
 * <p>leadMinutes에 null이 없다. 이 경로는 "답했다"를 저장하는 경로라, "아직 모름"(null)으로
 * 되돌리는 값이 있을 수 없다. "없음"은 0이다.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class LeadMinutesBatchItemRequest {

    @NotNull
    private Long routineId;

    @NotNull
    @Min(0)
    @Max(480)
    private Integer leadMinutes;
}
