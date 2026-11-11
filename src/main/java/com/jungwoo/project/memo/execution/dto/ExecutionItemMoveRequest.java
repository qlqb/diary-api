package com.jungwoo.project.memo.execution.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 이동 = "언제 할지"를 바꾼다. 날짜만 바꾸는 것(내일로)과 같은 날 안에서 시각만 뒤로 미는
 * 것(오늘 뒤로)이 모두 이 하나의 액션이다 — 둘 다 "언제 할지 변경"이라는 같은 사건이고,
 * 같은 MOVED 이벤트로 남아야 하기 때문이다.
 *
 * startTime/endTime은 선택이다.
 * - 비워두면 기존 동작 그대로: 날짜만 옮기고 TIME_FIXED 항목의 시각은 날짜 차이만큼 평행 이동한다.
 * - 채우면 그 시각으로 다시 배치한다. TIME_FIXED 항목에서만 쓸 수 있다 —
 *   시각 없는 항목(DATE_ONLY)에 시각을 붙이는 것은 이동이 아니라 배치 형식 변경이라서
 *   이 액션이 하지 않는다.
 *
 * 보류(hold)와는 다른 액션이다. 이동은 "언제 할지 변경", 보류는 "당분간 실행 대상에서 제외"다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExecutionItemMoveRequest {

    @NotNull(message = "이동할 날짜는 필수입니다")
    private LocalDate toDate;

    /** 옮긴 뒤의 시작 시각(선택). endTime과 함께 있어야 한다. */
    private LocalTime startTime;

    /** 옮긴 뒤의 종료 시각(선택). startTime보다 이후여야 한다. */
    private LocalTime endTime;

    @NotNull(message = "version은 필수입니다")
    private Long version;

    private String reason;
}
