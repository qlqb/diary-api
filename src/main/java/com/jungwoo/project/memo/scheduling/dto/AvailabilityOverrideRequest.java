package com.jungwoo.project.memo.scheduling.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 사용자가 미리보기 화면에서 직접 고친 가용시간 예외 하나("이 시간은 안 돼요" 또는 다시 허용).
 * available=false면 그 구간을 사용 불가로 추가하고, available=true면 그 구간과 겹치는
 * AI/기본 추정 사용 불가 블록을 제거해 다시 가용시간으로 되돌린다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AvailabilityOverrideRequest {
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private boolean available;
    private String reason;
}
