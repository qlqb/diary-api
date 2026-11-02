package com.jungwoo.project.memo.ai.draft.resolver;

import com.jungwoo.project.memo.routine.dto.RoutineResponse;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 활성 프로젝트의 학기 종료일 → {@code DB / ACTIVE_SEMESTER_END}. 없으면 empty → endDate는
 * missing_required로 남아 사용자에게 묻는다. 기본값을 추측하지 않는다.
 *
 * <p>프로젝트(courses)에는 종료일 컬럼이 없다. 수업 루틴의 effective_until이 종강일이다
 * (docs/sql/2026-08-31-routines.sql: "수업은 만들 때 종강일을 채우고"). 아직 끝나지 않은 수업
 * 루틴 중 종강일이 있는 것의 <b>가장 늦은 날</b>을 쓴다 — 과목마다 다르면 가장 늦은 날까지
 * 이동 루틴이 있어야 어느 수업도 빠지지 않는다. 종강일이 하나도 없으면 empty.
 */
public final class SemesterEndResolver {

    private SemesterEndResolver() {
    }

    public static Optional<LocalDate> resolve(List<RoutineResponse> routines, LocalDate asOf) {
        if (routines == null) {
            return Optional.empty();
        }
        LocalDate latest = null;
        for (RoutineResponse routine : routines) {
            if (!FirstClassPerWeekdayResolver.isClass(routine, asOf) || routine.effectiveUntil() == null) {
                continue;
            }
            if (latest == null || routine.effectiveUntil().isAfter(latest)) {
                latest = routine.effectiveUntil();
            }
        }
        return Optional.ofNullable(latest);
    }
}
