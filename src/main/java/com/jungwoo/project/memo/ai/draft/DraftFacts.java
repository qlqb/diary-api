package com.jungwoo.project.memo.ai.draft;

import com.jungwoo.project.memo.ai.draft.resolver.UpcomingWorkShiftsResolver.WorkShift;
import com.jungwoo.project.memo.commitment.domain.DerivedTravelRelation;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 한 턴에서 resolver들이 결정적으로 계산한 "관련 실제 데이터". 프롬프트 블록과 서버 판정이
 * 같은 값을 본다.
 *
 * <p>근무는 <b>조회한 기간과 함께</b> 들고 다닌다. 기간을 빼고 목록만 보면 "이번 주에 근무가
 * 없다"와 "이번 주는 조회하지도 않았다"를 구분할 수 없고, 무관한 기간의 근무 때문에 요청
 * 기간에 근무가 있다고 판단하게 된다.
 *
 * @param today            사용자 시간대 기준 오늘
 * @param firstClassByDay  요일별 첫 수업 시작 시각(수업 없는 요일은 키 없음)
 * @param semesterEnd      수업 루틴의 종강일. 없으면 empty
 * @param workLookup       근무 조회가 성공했는가. 실패를 "근무 없음"으로 위장하지 않는다
 * @param workShifts       조회 기간의 원본 근무 중 시작·종료가 정상인 것
 * @param incompleteShifts 조회 기간의 원본 근무 중 종료 시각을 쓸 수 없는 것
 * @param coveredKeys      이미 이동 블록이 붙어 있는 (근무 id + 앞/뒤). 같은 것을 또 만들지 않는다
 * @param workRangeFrom    workShifts를 조회한 기간 시작(포함)
 * @param workRangeTo      workShifts를 조회한 기간 끝(포함)
 */
public record DraftFacts(
        LocalDate today,
        Map<DayOfWeek, LocalTime> firstClassByDay,
        Optional<LocalDate> semesterEnd,
        WorkLookup workLookup,
        List<WorkShift> workShifts,
        List<WorkShift> incompleteShifts,
        Set<String> coveredKeys,
        LocalDate workRangeFrom,
        LocalDate workRangeTo
) {

    /** 근무 조회가 어떻게 끝났는가. 실패와 "0건"은 사용자에게 다른 말을 해야 한다. */
    public enum WorkLookup { OK, FAILED }

    public static DraftFacts empty(LocalDate today) {
        return new DraftFacts(today, Map.of(), Optional.empty(), WorkLookup.OK, List.of(), List.of(), Set.of(),
                today, today.plusDays(DraftSlotRegistry.SCHEDULE_DEFAULT_RANGE_DAYS));
    }

    public boolean workLookupFailed() {
        return workLookup == WorkLookup.FAILED;
    }

    /**
     * 이 사실 묶음이 요청 기간을 덮는가. 아니면 그 기간으로 다시 조회해야 한다 —
     * 덮지 않는 기간을 "근무 없음"으로 답하면 거짓말이 된다.
     */
    public boolean covers(LocalDate from, LocalDate to) {
        if (from == null || to == null || workRangeFrom == null || workRangeTo == null) {
            return false;
        }
        return !from.isBefore(workRangeFrom) && !to.isAfter(workRangeTo);
    }

    /** 요청 기간 안의 정상 근무. 포함 기준은 근무 <b>시작 날짜</b>다. */
    public List<WorkShift> workShiftsIn(LocalDate from, LocalDate to) {
        return filter(workShifts, from, to);
    }

    /** 요청 기간 안에서 종료 시각을 쓸 수 없는 근무. */
    public List<WorkShift> incompleteShiftsIn(LocalDate from, LocalDate to) {
        return filter(incompleteShifts, from, to);
    }

    /** 이 근무의 이 방향 이동이 이미 있는가. */
    public boolean alreadyCovered(Long commitmentId, DerivedTravelRelation relation) {
        if (commitmentId == null || relation == null) {
            return false;
        }
        return coveredKeys.contains(commitmentId + ":" + relation.name());
    }

    private static List<WorkShift> filter(List<WorkShift> shifts, LocalDate from, LocalDate to) {
        if (shifts == null || shifts.isEmpty()) {
            return List.of();
        }
        if (from == null || to == null) {
            return List.copyOf(shifts);
        }
        List<WorkShift> result = new ArrayList<>();
        for (WorkShift shift : shifts) {
            LocalDate day = shift.startAt().toLocalDate();
            if (!day.isBefore(from) && !day.isAfter(to)) {
                result.add(shift);
            }
        }
        return result;
    }
}
