package com.jungwoo.project.memo.ai.draft;

import com.jungwoo.project.memo.ai.draft.resolver.UpcomingWorkShiftsResolver.WorkShift;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 한 턴에서 resolver들이 결정적으로 계산한 "관련 실제 데이터". 프롬프트 블록과 서버 판정이
 * 같은 값을 본다.
 *
 * @param today            사용자 시간대 기준 오늘
 * @param firstClassByDay  요일별 첫 수업 시작 시각(수업 없는 요일은 키 없음)
 * @param semesterEnd      수업 루틴의 종강일. 없으면 empty
 * @param workShifts       오늘~+14일의 근무(제목 키워드 기준)
 * @param workRangeFrom    workShifts를 조회한 기간 시작(오늘)
 * @param workRangeTo      workShifts를 조회한 기간 끝(오늘+14일)
 */
public record DraftFacts(
        LocalDate today,
        Map<DayOfWeek, LocalTime> firstClassByDay,
        Optional<LocalDate> semesterEnd,
        List<WorkShift> workShifts,
        LocalDate workRangeFrom,
        LocalDate workRangeTo
) {
    public static DraftFacts empty(LocalDate today) {
        return new DraftFacts(today, Map.of(), Optional.empty(), List.of(), today,
                today.plusDays(DraftSlotRegistry.SCHEDULE_DEFAULT_RANGE_DAYS));
    }

    public DraftSlotRegistry.AnchorFacts anchorFacts() {
        return new DraftSlotRegistry.AnchorFacts(!firstClassByDay.isEmpty(), !workShifts.isEmpty());
    }
}
