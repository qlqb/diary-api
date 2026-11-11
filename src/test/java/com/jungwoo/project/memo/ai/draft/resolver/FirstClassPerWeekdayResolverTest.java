package com.jungwoo.project.memo.ai.draft.resolver;

import com.jungwoo.project.memo.commitment.domain.Commitment;
import com.jungwoo.project.memo.commitment.domain.DerivedTravelRelation;
import com.jungwoo.project.memo.routine.dto.RoutineResponse;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** B-8 #10 + 나머지 resolver. 입력은 AiWorkspaceContextBuilder가 쓰는 RoutineResponse 그대로(문자열 파싱 없음). */
class FirstClassPerWeekdayResolverTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 8);

    private static RoutineResponse routine(Long courseId, Set<DayOfWeek> days, String start, String end, LocalDate until) {
        return new RoutineResponse(1L, courseId, "수업", null, days, LocalTime.parse(start), LocalTime.parse(end),
                LocalDate.of(2026, 8, 25), until, false, false, false, List.of());
    }

    /** 화 14:00 · 수·목·금 10:00(같은 요일에 더 늦은 수업이 있어도 가장 이른 것) → 4건, 월 없음. */
    @Test
    void earliestClassPerWeekday_fourDays_noMonday() {
        List<RoutineResponse> routines = List.of(
                routine(36L, Set.of(DayOfWeek.TUESDAY), "14:00", "17:00", LocalDate.of(2026, 12, 11)),
                routine(32L, Set.of(DayOfWeek.WEDNESDAY), "10:00", "13:00", LocalDate.of(2026, 12, 11)),
                routine(34L, Set.of(DayOfWeek.WEDNESDAY), "14:00", "17:00", LocalDate.of(2026, 12, 11)),
                routine(31L, Set.of(DayOfWeek.THURSDAY), "10:00", "13:00", LocalDate.of(2026, 12, 11)),
                routine(190L, Set.of(DayOfWeek.THURSDAY), "13:00", "15:00", LocalDate.of(2026, 12, 11)),
                routine(35L, Set.of(DayOfWeek.FRIDAY), "10:00", "13:00", LocalDate.of(2026, 12, 11)),
                // 알바 루틴(courseId 없음)은 수업이 아니다 — 월요일이 생기면 안 된다.
                routine(null, Set.of(DayOfWeek.MONDAY), "09:00", "12:00", null),
                // 이미 끝난 수업은 제외
                routine(99L, Set.of(DayOfWeek.MONDAY), "09:00", "12:00", LocalDate.of(2026, 6, 20)));

        Map<DayOfWeek, LocalTime> result = FirstClassPerWeekdayResolver.resolve(routines, TODAY);

        assertThat(result).hasSize(4)
                .containsEntry(DayOfWeek.TUESDAY, LocalTime.of(14, 0))
                .containsEntry(DayOfWeek.WEDNESDAY, LocalTime.of(10, 0))
                .containsEntry(DayOfWeek.THURSDAY, LocalTime.of(10, 0))
                .containsEntry(DayOfWeek.FRIDAY, LocalTime.of(10, 0))
                .doesNotContainKey(DayOfWeek.MONDAY);
    }

    @Test
    void semesterEnd_isLatestUntilOfClassRoutines_orEmpty() {
        List<RoutineResponse> routines = List.of(
                routine(36L, Set.of(DayOfWeek.TUESDAY), "14:00", "17:00", LocalDate.of(2026, 12, 11)),
                routine(32L, Set.of(DayOfWeek.WEDNESDAY), "10:00", "13:00", LocalDate.of(2026, 12, 18)),
                routine(null, Set.of(DayOfWeek.MONDAY), "09:00", "12:00", LocalDate.of(2027, 1, 1)));
        assertThat(SemesterEndResolver.resolve(routines, TODAY)).contains(LocalDate.of(2026, 12, 18));

        assertThat(SemesterEndResolver.resolve(List.of(
                routine(36L, Set.of(DayOfWeek.TUESDAY), "14:00", "17:00", null)), TODAY)).isEmpty();
        assertThat(SemesterEndResolver.resolve(List.of(), TODAY)).isEmpty();
    }

    @Test
    void workShifts_byTitleKeyword_withinRange_sorted() {
        List<Commitment> commitments = List.of(
                Commitment.builder().commitmentId(2L).title("근무").startAt(LocalDateTime.of(2026, 9, 10, 17, 0)).endAt(LocalDateTime.of(2026, 9, 10, 22, 0)).build(),
                Commitment.builder().commitmentId(1L).title("쿠팡 알바").startAt(LocalDateTime.of(2026, 9, 8, 18, 0)).endAt(LocalDateTime.of(2026, 9, 8, 23, 0)).build(),
                Commitment.builder().commitmentId(3L).title("친구 약속").startAt(LocalDateTime.of(2026, 9, 9, 19, 0)).endAt(LocalDateTime.of(2026, 9, 9, 21, 0)).build(),
                Commitment.builder().commitmentId(4L).title("근무").startAt(LocalDateTime.of(2026, 10, 1, 18, 0)).endAt(LocalDateTime.of(2026, 10, 1, 23, 0)).build());

        var shifts = UpcomingWorkShiftsResolver.resolve(commitments, TODAY, TODAY.plusDays(14));

        assertThat(shifts.usable()).extracting(UpcomingWorkShiftsResolver.WorkShift::commitmentId)
                .containsExactly(1L, 2L);
        assertThat(shifts.incomplete()).isEmpty();
    }

    /**
     * 파생 이동은 원본 근무가 아니다. 제목에 "근무"가 남아 있어도, 사용자가 "퇴근길"로 바꿔도
     * derived_from_commitment_id가 있으면 근무 후보에서 빠진다 — 이동 뒤에 또 이동이 붙는 것을
     * 제목 규칙만으로는 막을 수 없다.
     */
    @Test
    void derivedTravel_isNeverPickedAsOriginShift() {
        List<Commitment> commitments = List.of(
                Commitment.builder().commitmentId(1L).title("근무")
                        .startAt(LocalDateTime.of(2026, 9, 8, 18, 0)).endAt(LocalDateTime.of(2026, 9, 8, 23, 0)).build(),
                // 컬럼으로 파생임이 남아 있다. 제목은 사용자가 바꾼 뒤다.
                Commitment.builder().commitmentId(2L).title("퇴근길")
                        .derivedFromCommitmentId(1L).derivedRelation(DerivedTravelRelation.AFTER_WORK)
                        .startAt(LocalDateTime.of(2026, 9, 8, 23, 0)).endAt(LocalDateTime.of(2026, 9, 9, 0, 0)).build(),
                // 컬럼이 없던 시절의 레거시 이동. 제목 규칙이 걸러낸다.
                Commitment.builder().commitmentId(3L).title("근무 전 이동")
                        .startAt(LocalDateTime.of(2026, 9, 9, 17, 0)).endAt(LocalDateTime.of(2026, 9, 9, 18, 0)).build());

        var shifts = UpcomingWorkShiftsResolver.resolve(commitments, TODAY, TODAY.plusDays(14));

        assertThat(shifts.usable()).extracting(UpcomingWorkShiftsResolver.WorkShift::commitmentId)
                .containsExactly(1L);
    }

    /** 종료 시각이 없거나 역전된 근무는 usable이 아니라 incomplete다. 시작 시각으로 지어내지 않는다. */
    @Test
    void shiftsWithUnusableEnd_areSeparated_notDropped() {
        List<Commitment> commitments = List.of(
                Commitment.builder().commitmentId(1L).title("근무")
                        .startAt(LocalDateTime.of(2026, 9, 8, 18, 0)).endAt(null).build(),
                Commitment.builder().commitmentId(2L).title("근무")
                        .startAt(LocalDateTime.of(2026, 9, 9, 18, 0)).endAt(LocalDateTime.of(2026, 9, 9, 17, 0)).build(),
                Commitment.builder().commitmentId(3L).title("근무")
                        .startAt(LocalDateTime.of(2026, 9, 10, 17, 0)).endAt(LocalDateTime.of(2026, 9, 10, 22, 0)).build());

        var shifts = UpcomingWorkShiftsResolver.resolve(commitments, TODAY, TODAY.plusDays(14));

        assertThat(shifts.usable()).extracting(UpcomingWorkShiftsResolver.WorkShift::commitmentId).containsExactly(3L);
        assertThat(shifts.incomplete()).extracting(UpcomingWorkShiftsResolver.WorkShift::commitmentId)
                .containsExactly(1L, 2L);
    }
}
