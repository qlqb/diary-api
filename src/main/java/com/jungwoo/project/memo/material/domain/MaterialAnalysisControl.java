package com.jungwoo.project.memo.material.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 사용자별 자동 분석 일시중지. 행이 없으면 켜진 상태다. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MaterialAnalysisControl {
    private Long userId;
    private boolean paused;
    private LocalDateTime pausedAt;
    private LocalDateTime updatedAt;
}
