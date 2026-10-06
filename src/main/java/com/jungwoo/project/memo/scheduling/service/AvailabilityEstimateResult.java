package com.jungwoo.project.memo.scheduling.service;

import com.jungwoo.project.memo.scheduling.domain.AvailabilityWindow;
import com.jungwoo.project.memo.scheduling.domain.BusyWindow;

import java.util.List;

/**
 * AvailabilityEstimateService.estimate()의 산출물. windows는 화면에 보여줄 후보 시간
 * 요약이고, busyWindows는 Timefold 계산에 넘길 "이미 막힌 시간"이다(기존 TIME_FIXED 조각 +
 * AI/사용자가 명시한 사용 불가 시간, 사용자가 다시 허용한 구간은 제외된 상태).
 */
public record AvailabilityEstimateResult(
        List<AvailabilityWindow> windows,
        List<BusyWindow> busyWindows
) {
}
