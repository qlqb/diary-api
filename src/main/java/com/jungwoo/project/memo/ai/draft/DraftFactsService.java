package com.jungwoo.project.memo.ai.draft;

import com.jungwoo.project.memo.ai.draft.resolver.FirstClassPerWeekdayResolver;
import com.jungwoo.project.memo.ai.draft.resolver.SemesterEndResolver;
import com.jungwoo.project.memo.ai.draft.resolver.UpcomingWorkShiftsResolver;
import com.jungwoo.project.memo.ai.ScheduleSuggestionService;
import com.jungwoo.project.memo.commitment.CommitmentService;
import com.jungwoo.project.memo.commitment.domain.Commitment;
import com.jungwoo.project.memo.routine.RoutineService;
import com.jungwoo.project.memo.routine.dto.RoutineResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private final ScheduleSuggestionService scheduleSuggestionService;

    /** 기본 기간(오늘~+14일)으로 수집한다. 요청 기간을 아직 모르는 LLM 호출 전 경로가 쓴다. */
    public DraftFacts collect(Long userId, LocalDate today) {
        return collect(userId, today, today, today.plusDays(DraftSlotRegistry.SCHEDULE_DEFAULT_RANGE_DAYS));
    }

    /**
     * 요청 기간으로 수집한다.
     *
     * <p>근무 조회가 실패하면 빈 목록이 아니라 {@link DraftFacts.WorkLookup#FAILED}를 남긴다.
     * 예외를 0건으로 바꾸면 "일정을 못 읽었다"가 "근무가 없다"로 바뀌어, 사용자는 등록해 둔
     * 근무를 앱이 잃어버렸다고 생각하면서 같은 요청을 반복하게 된다.
     *
     * <p>시간표 조회는 실패해도 근무 판정을 막지 않는다 — 서로 다른 anchor가 쓰는 값이다.
     */
    public DraftFacts collect(Long userId, LocalDate today, LocalDate from, LocalDate to) {
        List<RoutineResponse> routines;
        try {
            routines = routineService.list(userId);
        } catch (RuntimeException e) {
            log.warn("draft 시간표 조회 생략: userId={}", userId, e);
            routines = List.of();
        }

        DraftFacts.WorkLookup lookup = DraftFacts.WorkLookup.OK;
        UpcomingWorkShiftsResolver.Result shifts = UpcomingWorkShiftsResolver.Result.EMPTY;
        Map<String, ExistingTravel> existing = Map.of();
        try {
            /*
             * 근무 선택은 겹침이 아니라 "시작 날짜가 기간 안"이다. 가용시간 계산용
             * findOverlapping을 쓰면 전날 밤에 시작한 근무가 이번 주 근무로 딸려 온다.
             */
            shifts = UpcomingWorkShiftsResolver.resolve(
                    commitmentService.findWorkShiftCandidates(userId, from, to), from, to);
            existing = collectExistingTravel(userId, shifts);
        } catch (RuntimeException e) {
            log.warn("draft 근무 조회 실패: userId={}, 기간={}~{}", userId, from, to, e);
            lookup = DraftFacts.WorkLookup.FAILED;
            shifts = UpcomingWorkShiftsResolver.Result.EMPTY;
            existing = Map.of();
        }

        log.info("draft 사실 수집: userId={}, 기간={}~{}, 근무조회={}, 근무={}건, 종료문제={}건, 기존이동={}건",
                userId, from, to, lookup, shifts.usable().size(), shifts.incomplete().size(), existing.size());

        return new DraftFacts(
                today,
                FirstClassPerWeekdayResolver.resolve(routines, today),
                SemesterEndResolver.resolve(routines, today),
                lookup,
                shifts.usable(),
                shifts.incomplete(),
                existing,
                from,
                to);
    }

    /**
     * 이 근무들에 이미 붙어 있는 이동 — 적용된 약속과 아직 검토 중인 후보를 함께 모은다.
     *
     * <p>기준은 원본 근무 id다. 이동의 날짜로 찾으면 자정을 넘어간 것과 사용자가 옮긴 것을
     * 놓친다. 미적용 후보까지 보는 이유는, 같은 요청을 다시 말했을 때 같은 근무에 카드가 두 장
     * 생기면 사용자가 어느 것을 눌러야 할지 알 수 없기 때문이다.
     *
     * <p>같은 (근무, 방향)에 적용된 것과 후보가 함께 있으면 <b>적용된 쪽</b>을 남긴다. 사용자가
     * 실제로 보고 있는 일정이 그것이고, 고쳐야 할 대상도 그것이다.
     */
    private Map<String, ExistingTravel> collectExistingTravel(
            Long userId, UpcomingWorkShiftsResolver.Result shifts) {
        List<Long> originIds = new ArrayList<>();
        for (UpcomingWorkShiftsResolver.WorkShift shift : shifts.usable()) {
            originIds.add(shift.commitmentId());
        }
        for (UpcomingWorkShiftsResolver.WorkShift shift : shifts.incomplete()) {
            originIds.add(shift.commitmentId());
        }
        if (originIds.isEmpty()) {
            return Map.of();
        }
        Map<String, ExistingTravel> byKey = new LinkedHashMap<>();
        for (ExistingTravel pending : scheduleSuggestionService.findPendingDerivedTravel(userId)) {
            if (originIds.contains(pending.originCommitmentId())) {
                byKey.put(pending.key(), pending);
            }
        }
        for (Commitment applied : commitmentService.findDerivedByOrigins(userId, originIds)) {
            if (applied.getDerivedFromCommitmentId() == null || applied.getDerivedRelation() == null) {
                continue;
            }
            ExistingTravel travel = new ExistingTravel(applied.getDerivedFromCommitmentId(),
                    applied.getDerivedRelation(), ExistingTravel.Source.APPLIED, applied.getCommitmentId(),
                    applied.getStartAt(), applied.getEndAt());
            byKey.put(travel.key(), travel);
        }
        return byKey;
    }

    /**
     * 한 턴 동안 기간별 사실을 재사용하는 조회기. 같은 기간을 두 번 조회하지 않는다.
     *
     * <p>턴 하나에 하나씩 만든다(스레드 안전하지 않다). 요청 기간은 모델 응답을 읽어야
     * 알 수 있는 경우가 있어, LLM 호출 전에 만든 기본 기간 사실을 미리 넣어 두고 필요할 때만
     * 추가로 조회한다.
     *
     * <p>{@code preloaded}가 null이면 기본 기간을 지금 조회한다 — OPEN draft가 없어 미리 읽지
     * 않은 턴에도 모델이 근무 기준 요청을 내면 서버가 후보 생성 여부를 판정할 수 있어야 한다.
     */
    public static final class TurnFacts implements DraftFactsSource {

        private final DraftFactsService service;
        private final Long userId;
        private final LocalDate today;
        private final Map<String, DraftFacts> byRange = new HashMap<>();
        private final DraftFacts fallback;

        public TurnFacts(DraftFactsService service, Long userId, LocalDate today, DraftFacts preloaded) {
            this.service = service;
            this.userId = userId;
            this.today = today;
            DraftFacts base = preloaded != null ? preloaded : service.collect(userId, today);
            this.fallback = base != null ? base : DraftFacts.empty(today);
            byRange.put(key(this.fallback.workRangeFrom(), this.fallback.workRangeTo()), this.fallback);
        }

        /** 기간을 모르는 자리(기간 계획 등)가 쓰는 기본 사실. */
        public DraftFacts base() {
            return fallback;
        }

        @Override
        public DraftFacts forRange(LocalDate from, LocalDate to) {
            if (from == null || to == null || to.isBefore(from)) {
                return fallback;
            }
            DraftFacts cached = byRange.get(key(from, to));
            if (cached != null) {
                return cached;
            }
            // 이미 더 넓은 기간을 조회해 뒀으면 그것을 걸러 쓴다 — 같은 턴에 같은 조회를 또 하지 않는다.
            if (fallback.covers(from, to) && !fallback.workLookupFailed()) {
                return fallback;
            }
            DraftFacts fresh = service.collect(userId, today, from, to);
            byRange.put(key(from, to), fresh);
            return fresh;
        }

        private static String key(LocalDate from, LocalDate to) {
            return from + "~" + to;
        }
    }
}
