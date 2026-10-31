package com.jungwoo.project.memo.ai.scheduleimport;

import com.jungwoo.project.memo.ai.scheduleimport.dto.RawScheduleTable;
import com.jungwoo.project.memo.ai.scheduleimport.dto.ScheduleExtractionResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 표를 날짜로 옮기는 규칙. 연도 보정과 요일 정합성이 여기서 결정적으로 정해진다 —
 * 모델에게 맡기면 같은 이미지에 매번 다른 주가 나오고, 사용자는 "지난번엔 맞았는데"라고만
 * 말할 수 있다.
 */
class ScheduleTableInterpreterTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 6);
    private static final List<String> MON_TO_SUN = List.of("월", "화", "수", "목", "금", "토", "일");
    private static final Map<String, String> LEGEND = Map.of("OP", "14~23", "CL", "15~00");

    @Test
    @DisplayName("연도가 없으면 오늘과 가장 가까운 해를 고른다")
    void resolvesTheYearNearestToday() {
        ScheduleExtractionResponse result = interpret(period(9, 7, 9, 13, null), MON_TO_SUN, row());

        assertThat(result.resolvedPeriod().startDate()).isEqualTo(LocalDate.of(2026, 9, 7));
        assertThat(result.resolvedPeriod().endDate()).isEqualTo(LocalDate.of(2026, 9, 13));
        assertThat(result.periodMissing()).isFalse();
        assertThat(result.periodWeekdayMismatch()).isFalse();
    }

    @Test
    @DisplayName("연말에 받은 1월 표는 다음 해다 — 올해로 고정하면 한 해 어긋난다")
    void yearRollsForwardAtTheEndOfTheYear() {
        ScheduleExtractionResponse result = ScheduleTableInterpreter.interpret(
                table(period(1, 4, 1, 10, null), MON_TO_SUN, List.of(row())),
                LocalDate.of(2026, 12, 30), null);

        assertThat(result.resolvedPeriod().startDate()).isEqualTo(LocalDate.of(2027, 1, 4));
    }

    @Test
    @DisplayName("표에 연도가 적혀 있으면 그대로 쓴다")
    void explicitYearWins() {
        assertThat(interpret(period(9, 7, 9, 13, 2024), MON_TO_SUN, row())
                .resolvedPeriod().startDate()).isEqualTo(LocalDate.of(2024, 9, 7));
    }

    @Test
    @DisplayName("보정한 시작일의 요일이 표의 첫 열과 다르면 표시한다 — 조용히 밀어 넣지 않는다")
    void weekdayMismatchIsFlagged() {
        // 2026-09-07은 월요일인데 표의 첫 열이 화요일이다.
        List<String> tueFirst = List.of("화", "수", "목", "금", "토", "일", "월");

        ScheduleExtractionResponse result = interpret(period(9, 7, 9, 13, null), tueFirst, row());

        assertThat(result.periodWeekdayMismatch()).isTrue();
        assertThat(result.resolvedPeriod()).as("후보는 만들되 화면이 강조한다").isNotNull();
    }

    @Test
    @DisplayName("날짜가 아예 없는 표는 사용자가 주를 골라야 한다")
    void periodMissing() {
        ScheduleExtractionResponse result = interpret(
                new RawScheduleTable.Period(null, null, null, null, null), MON_TO_SUN, row());

        assertThat(result.periodMissing()).isTrue();
        assertThat(result.resolvedPeriod()).isNull();
        assertThat(result.rows().get(0).cells().get(0).date())
                .as("날짜를 모르면 셀에도 날짜가 없다")
                .isNull();
    }

    @Test
    @DisplayName("종료일이 없으면 열 수만큼 이어진다")
    void endDateFromColumnCount() {
        assertThat(interpret(period(9, 7, null, null, null), MON_TO_SUN, row())
                .resolvedPeriod().endDate()).isEqualTo(LocalDate.of(2026, 9, 13));
    }

    @Test
    @DisplayName("해를 넘기는 표도 이어서 읽는다")
    void periodCrossingTheYearBoundary() {
        ScheduleExtractionResponse result = ScheduleTableInterpreter.interpret(
                table(period(12, 28, 1, 3, null), MON_TO_SUN, List.of(row())),
                LocalDate.of(2026, 12, 27), null);

        assertThat(result.resolvedPeriod().startDate()).isEqualTo(LocalDate.of(2026, 12, 28));
        assertThat(result.resolvedPeriod().endDate()).isEqualTo(LocalDate.of(2027, 1, 3));
    }

    // ===== 본인 행 =====

    @Test
    @DisplayName("이름이 하나만 일치하면 미리 골라 둔다")
    void matchesASingleName() {
        ScheduleExtractionResponse result = ScheduleTableInterpreter.interpret(
                table(period(9, 7, 9, 13, null), MON_TO_SUN,
                        List.of(named("사원A"), named("이정우"), named("김하나"))),
                TODAY, "이정우");

        assertThat(result.matchedRowIndex()).isEqualTo(1);
    }

    @Test
    @DisplayName("동명이인이면 고르지 않는다 — 잘못 고르면 남의 근무가 내 달력에 들어온다")
    void ambiguousNameMatchesNothing() {
        ScheduleExtractionResponse result = ScheduleTableInterpreter.interpret(
                table(period(9, 7, 9, 13, null), MON_TO_SUN, List.of(named("이정우"), named("이정우"))),
                TODAY, "이정우");

        assertThat(result.matchedRowIndex()).isNull();
    }

    @Test
    @DisplayName("이름이 없거나 표에 없으면 고르지 않는다")
    void noNameMatchesNothing() {
        assertThat(ScheduleTableInterpreter.interpret(
                table(period(9, 7, 9, 13, null), MON_TO_SUN, List.of(named("사원A"))), TODAY, "이정우")
                .matchedRowIndex()).isNull();
        assertThat(ScheduleTableInterpreter.interpret(
                table(period(9, 7, 9, 13, null), MON_TO_SUN, List.of(named("사원A"))), TODAY, null)
                .matchedRowIndex()).isNull();
    }

    // ===== 행·열의 흠 =====

    @Test
    @DisplayName("셀 수가 요일 수와 다른 행만 빼고 나머지는 살린다")
    void aBrokenRowDoesNotKillTheTable() {
        RawScheduleTable raw = table(period(9, 7, 9, 13, null), MON_TO_SUN, List.of(
                new RawScheduleTable.Row("짧은 행", null, List.of("17~23", "D/O")),
                named("이정우")));

        ScheduleExtractionResponse result = ScheduleTableInterpreter.interpret(raw, TODAY, null);

        assertThat(result.rows().get(0).rowInvalid()).isTrue();
        assertThat(result.rows().get(0).cells()).isEmpty();
        assertThat(result.rows().get(1).rowInvalid()).isFalse();
        assertThat(result.rows().get(1).cells()).hasSize(7);
    }

    @Test
    @DisplayName("요일로 읽지 못한 열이 있으면 알린다")
    void unrecognizedColumns() {
        assertThat(interpret(period(9, 7, 9, 13, null),
                List.of("월", "화", "??", "목", "금", "토", "일"), row()).columnsUnrecognized()).isTrue();
    }

    @Test
    @DisplayName("요일 표기가 섞여도 읽는다")
    void weekdayNotations() {
        assertThat(ScheduleTableInterpreter.weekdayOf("월요일")).isEqualTo(DayOfWeek.MONDAY);
        assertThat(ScheduleTableInterpreter.weekdayOf("(화)")).isEqualTo(DayOfWeek.TUESDAY);
        assertThat(ScheduleTableInterpreter.weekdayOf("Mon")).isEqualTo(DayOfWeek.MONDAY);
        assertThat(ScheduleTableInterpreter.weekdayOf("SUNDAY")).isEqualTo(DayOfWeek.SUNDAY);
        assertThat(ScheduleTableInterpreter.weekdayOf("Sat.")).isEqualTo(DayOfWeek.SATURDAY);
        assertThat(ScheduleTableInterpreter.weekdayOf("??")).isNull();
    }

    // ===== 셀 해석이 응답에 실린다 =====

    @Test
    @DisplayName("각 셀에 날짜와 해석이 함께 실린다 — 화면이 다시 요청하지 않고 미리보기를 그린다")
    void cellsCarryDatesAndParses() {
        ScheduleExtractionResponse.RowView row = interpret(
                period(9, 7, 9, 13, null), MON_TO_SUN,
                new RawScheduleTable.Row("이정우", "PT",
                        Arrays.asList("17~23", "OP", "D/O", "A", "CL", "18~23", "18~23")))
                .rows().get(0);

        assertThat(row.cells().get(0).date()).isEqualTo(LocalDate.of(2026, 9, 7));
        assertThat(row.cells().get(0).kind()).isEqualTo("WORK");
        assertThat(row.cells().get(1).code()).isEqualTo("OP");
        assertThat(row.cells().get(2).kind()).isEqualTo("OFF");
        assertThat(row.cells().get(3).kind()).isEqualTo("UNRESOLVED");
        assertThat(row.cells().get(4).crossesMidnight()).as("CL은 15~00이라 자정을 넘는다").isTrue();
        assertThat(row.cells().get(6).date()).isEqualTo(LocalDate.of(2026, 9, 13));
    }

    @Test
    @DisplayName("모르는 코드는 모아서 알린다 — 사용자가 그것만 채우면 된다")
    void unresolvedCodesAreCollected() {
        ScheduleExtractionResponse result = interpret(period(9, 7, 9, 13, null), MON_TO_SUN,
                new RawScheduleTable.Row("이정우", "PT",
                        Arrays.asList("A", "A", "B", "D/O", "17~23", "OP", "D/O")));

        assertThat(result.unresolvedCodes())
                .as("같은 코드를 두 번 묻지 않는다")
                .containsExactly("A", "B");
    }

    // ===== fixture =====

    private static ScheduleExtractionResponse interpret(
            RawScheduleTable.Period period, List<String> columns, RawScheduleTable.Row row) {
        return ScheduleTableInterpreter.interpret(table(period, columns, List.of(row)), TODAY, null);
    }

    private static RawScheduleTable table(
            RawScheduleTable.Period period, List<String> columns, List<RawScheduleTable.Row> rows) {
        return new RawScheduleTable(true, "근무표", period, LEGEND, columns, rows);
    }

    private static RawScheduleTable.Row row() {
        return named("이정우");
    }

    private static RawScheduleTable.Row named(String name) {
        return new RawScheduleTable.Row(name, "PT",
                Arrays.asList("17~23", "18~23", "18~23", "17~22", "18~23", "D/O", "D/O"));
    }

    private static RawScheduleTable.Period period(
            Integer startMonth, Integer startDay, Integer endMonth, Integer endDay, Integer year) {
        return new RawScheduleTable.Period(startMonth, startDay, endMonth, endDay, year);
    }
}
