package com.jungwoo.project.memo.ai.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * ai_schedule_suggestions에 저장되는 ROUTINE_LEAD payload. 승인 시 그대로
 * {@code RoutineService.updateLeadMinutes}의 입력이 된다(routineId마다 같은 leadMinutes).
 *
 * <p>leadMinutes는 저장 시점에는 null일 수 있다("시간을 말하지 않았다"). 승인 시점에는
 * 있어야 한다 — 카드에서 고른 값이 editedPayload로 온다.
 *
 * <p>targetSummary는 표시용이다("자료구조 외 4개"). 확인 문구와 카드가 쓴다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LeadMinutesSuggestionPayload {

    @NotEmpty
    private List<Long> routineIds;

    @Min(0)
    @Max(480)
    private Integer leadMinutes;

    @Size(max = 200)
    private String targetSummary;
}
