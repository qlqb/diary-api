package com.jungwoo.project.memo.ai.draft.resolver;

import com.jungwoo.project.memo.routine.dto.RoutineResponse;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 활성 프로젝트 시간표 → 요일별 가장 이른 수업 시작 시각. 수업 없는 요일은 키 없음. 순수 함수.
 *
 * <p>입력은 {@code AiWorkspaceContextBuilder}가 [프로젝트] 블록을 만들 때 쓰는 원천과 같은
 * {@link RoutineResponse}다(문자열 파싱 금지). 프로젝트에 묶인 루틴(courseId != null)만 수업으로
 * 본다 — 알바·운동 루틴은 courseId가 없다.
 *
 * <p>"첫 수업 1시간 전"을 서버가 요일별 고정 시각으로 펼치는 근거가 이 맵이다. 다른 일정에
 * 상대적인 시각을 저장하는 새 ROUTINE 엔티티는 만들지 않는다.
 */
public final class FirstClassPerWeekdayResolver {

    private FirstClassPerWeekdayResolver() {
    }

    /**
     * @param routines 사용자의 루틴 전부(요일 포함)
     * @param asOf     기준일. 이미 끝난 루틴(effectiveUntil &lt; asOf)은 제외한다
     */
    public static Map<DayOfWeek, LocalTime> resolve(List<RoutineResponse> routines, LocalDate asOf) {
        Map<DayOfWeek, LocalTime> result = new EnumMap<>(DayOfWeek.class);
        if (routines == null) {
            return result;
        }
        for (RoutineResponse routine : routines) {
            if (!isClass(routine, asOf)) {
                continue;
            }
            LocalTime start = routine.startTime().truncatedTo(ChronoUnit.MINUTES);
            for (DayOfWeek day : routine.daysOfWeek()) {
                LocalTime current = result.get(day);
                if (current == null || start.isBefore(current)) {
                    result.put(day, start);
                }
            }
        }
        return result;
    }

    static boolean isClass(RoutineResponse routine, LocalDate asOf) {
        if (routine == null || routine.courseId() == null || routine.startTime() == null
                || routine.daysOfWeek() == null || routine.daysOfWeek().isEmpty()) {
            return false;
        }
        LocalDate until = routine.effectiveUntil();
        return until == null || asOf == null || !until.isBefore(asOf);
    }
}
