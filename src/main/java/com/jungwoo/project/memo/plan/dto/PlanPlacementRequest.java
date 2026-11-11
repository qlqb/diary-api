package com.jungwoo.project.memo.plan.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 롤링 배치 요청. 창 끝은 서버가 정한다(windowStart + 6일, 계획 종료일로 자름) —
 * 클라이언트가 창 길이를 정하면 솔버의 7일 지평과 어긋날 수 있다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlanPlacementRequest {

    /** 없으면 오늘부터. */
    private LocalDate windowStart;
}
