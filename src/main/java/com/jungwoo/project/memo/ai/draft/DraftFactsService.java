package com.jungwoo.project.memo.ai.draft;

import com.jungwoo.project.memo.ai.draft.resolver.FirstClassPerWeekdayResolver;
import com.jungwoo.project.memo.ai.draft.resolver.SemesterEndResolver;
import com.jungwoo.project.memo.ai.draft.resolver.UpcomingWorkShiftsResolver;
import com.jungwoo.project.memo.commitment.CommitmentService;
import com.jungwoo.project.memo.routine.RoutineService;
import com.jungwoo.project.memo.routine.dto.RoutineResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * resolver들의 입력을 DB에서 읽어 {@link DraftFacts}를 만든다. 계산 자체는 순수 resolver가 한다.
 * 트랜잭션 밖(LLM 호출 전후)에서 불러도 되는 읽기 전용 조회다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DraftFactsService {

    private final RoutineService routineService;
    private final CommitmentService commitmentService;

    public DraftFacts collect(Long userId, LocalDate today) {
        List<RoutineResponse> routines;
        try {
            routines = routineService.list(userId);
        } catch (RuntimeException e) {
            log.warn("draft 시간표 조회 생략: userId={}", userId, e);
            routines = List.of();
        }
        LocalDate workTo = today.plusDays(DraftSlotRegistry.SCHEDULE_DEFAULT_RANGE_DAYS);
        List<UpcomingWorkShiftsResolver.WorkShift> shifts;
        try {
            shifts = UpcomingWorkShiftsResolver.resolve(
                    commitmentService.findOverlapping(userId, today, workTo), today, workTo);
        } catch (RuntimeException e) {
            log.warn("draft 근무 조회 생략: userId={}", userId, e);
            shifts = List.of();
        }
        return new DraftFacts(
                today,
                FirstClassPerWeekdayResolver.resolve(routines, today),
                SemesterEndResolver.resolve(routines, today),
                shifts,
                today,
                workTo);
    }
}
