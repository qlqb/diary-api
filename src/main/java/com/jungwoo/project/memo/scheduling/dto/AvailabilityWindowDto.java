package com.jungwoo.project.memo.scheduling.dto;

import com.jungwoo.project.memo.scheduling.domain.AvailabilityConfidence;
import com.jungwoo.project.memo.scheduling.domain.AvailabilitySource;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 화면에 보여줄 가용시간 후보 하나. "왜 이 시간이 후보가 됐는지" 설명용 필드를 그대로 노출한다. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AvailabilityWindowDto {
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private AvailabilitySource source;
    private AvailabilityConfidence confidence;
    private String reason;
}
