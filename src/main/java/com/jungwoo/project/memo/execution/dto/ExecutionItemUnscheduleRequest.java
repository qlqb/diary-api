package com.jungwoo.project.memo.execution.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 날짜/시각을 뗀다. 배치의 역방향이다.
 *
 * ★ 기간을 서버가 추론하지 않고 요청으로 받는다(11-period-plan.md §2-5).
 * 같은 날짜에 계획이 여럿 걸릴 수 있어 서버는 "그 계획의 기간"을 고를 수 없다.
 * 어느 계획 맥락에서 날짜를 뗀 것인지는 화면이 알고 있으니 화면이 보낸다.
 *
 * 두 값이 있으면 (가) 계획 안에서 날짜만 해제 — 기간 조회에 계속 잡힌다.
 * 두 값이 null이면 (나) 계획에서 빼고 미분류로 — 기간 조회에서 사라진다.
 *
 * (가)가 기본이어야 한다. 아니면 사용자가 날짜만 뗐는데 항목이 화면에서 증발한다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExecutionItemUnscheduleRequest {

    private LocalDate planningStartDate;

    private LocalDate planningEndDate;

    /** 낙관적 락. */
    private Long version;

    private String reason;
}
