package com.jungwoo.project.memo.ai.dto;

import java.util.List;

/**
 * 모델이 낸 ROUTINE_LEAD 후보의 payload — <b>의도만</b> 담는다.
 *
 * <p>모델은 대상을 id 또는 힌트로만 가리키고, 어느 루틴인지는 서버가 결정적으로 해석한다
 * ({@code RoutineService.resolveLeadTargets}). 둘 다 있으면 ids가 우선이다. 시간이 명시되지
 * 않았으면 leadMinutes는 null이고, 그 후보는 승인 카드에서 값을 고르게 한다.
 *
 * <p>저장되는 모양은 이것이 아니라 {@link LeadMinutesSuggestionPayload}다. 해석 결과를
 * 저장해야 승인 시점에 다시 해석하지 않는다 — 그 사이 루틴이 늘면 대상이 바뀐다.
 */
public record LeadMinutesIntent(
        List<Long> targetRoutineIds,
        String targetHint,
        Integer leadMinutes
) {
}
