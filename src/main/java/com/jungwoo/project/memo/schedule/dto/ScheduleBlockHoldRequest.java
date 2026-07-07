package com.jungwoo.project.memo.schedule.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 보류 요청. (body 생략 가능)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScheduleBlockHoldRequest {

    private String memo;
}
