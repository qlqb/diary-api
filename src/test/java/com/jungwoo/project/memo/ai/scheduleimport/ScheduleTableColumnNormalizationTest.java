package com.jungwoo.project.memo.ai.scheduleimport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.ai.scheduleimport.dto.RawScheduleTable;
import com.jungwoo.project.memo.ai.scheduleimport.dto.ScheduleExtractionResponse;
import com.jungwoo.project.memo.ai.scheduleimport.dto.ScheduleExtractionResponse.CellView;
import com.jungwoo.project.memo.ai.scheduleimport.dto.ScheduleExtractionResponse.RowView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 같은 이미지에서 모델이 낸 두 가지 머리글을 실제 원문 그대로 통과시킨다.
 *
 * <p>이 픽스처는 만든 것이 아니라 <b>실제로 실패한 입력</b>이다. 같은 근무표 사진을 3회
 * 받아쓰게 했더니 2회는 머리글에 "이름"·"세부"가 함께 들어와 9개가 되었고, 셀은 7개였다.
 * 그 어긋남 하나로 9개 행이 전부 무효가 되어 후보가 0건 나왔다 — 3회 중 2회.
 *
 * <p>모델이 지시를 어긴 게 아니다. 표의 머리글 줄에는 그 칸들이 정말로 있다. 그래서 프롬프트만
 * 고치고 끝내지 않는다. 프롬프트는 다음 모델 교체 때 다시 어긋날 수 있고, 그때 조용히 같은
 * 자리로 돌아온다. 해석기가 흡수할 수 있으면 흡수한다.
 *
 * <p>사람 이름은 별칭으로 바꿨다(이 저장소는 공개다). 그 외 셀·범례·기간·열 구성은 받아쓴
 * 원문 그대로이고, 이 버그는 이름과 무관하게 열과 셀의 개수에서 생긴다.
 */
class ScheduleTableColumnNormalizationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 7);
    private static final String ME = "본인";

    /** 두 픽스처 모두에서 나와야 하는 본인 행의 최종 해석. */
    private static final List<String> EXPECTED_ME = List.of(
            "2026-09-07 WORK 17:00-23:00",
            "2026-09-08 WORK 18:00-23:00",
            "2026-09-09 WORK 18:00-23:00",
            "2026-09-10 WORK 17:00-22:00",
            "2026-09-11 WORK 18:00-23:00",
            "2026-09-12 OFF",
            "2026-09-13 OFF");

    @Test
    @DisplayName("머리글에 이름·세부가 섞여 와도 일정 열만 골라 쓴다 — 전 행이 죽지 않는다")
    void nineColumnHeaderIsNormalized() {
        ScheduleExtractionResponse result = interpret("run1-9columns.json");

        assertThat(result.raw().safeColumns()).as("원문은 손대지 않는다").hasSize(9);
        assertThat(result.columnsNormalized()).isTrue();
        assertThat(result.columnsUnrecognized()).isFalse();
        assertThat(result.rows()).extracting(RowView::rowInvalid).containsOnly(false);
    }

    @Test
    @DisplayName("머리글이 요일 7개로 오면 보정할 것이 없다")
    void sevenColumnHeaderNeedsNoNormalization() {
        ScheduleExtractionResponse result = interpret("run3-7columns.json");

        assertThat(result.columnsNormalized()).isFalse();
        assertThat(result.columnsUnrecognized()).isFalse();
        assertThat(result.rows()).extracting(RowView::rowInvalid).containsOnly(false);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"run1-9columns.json", "run3-7columns.json"})
    @DisplayName("두 머리글에서 같은 일정이 나온다 — 보정이 값을 바꾸지 않는다")
    void bothHeadersYieldTheSameSchedule(String fixture) {
        ScheduleExtractionResponse result = interpret(fixture);

        assertThat(result.matchedRowIndex()).isEqualTo(7);
        assertThat(result.resolvedPeriod().startDate()).isEqualTo(LocalDate.of(2026, 9, 7));
        assertThat(result.resolvedPeriod().endDate()).isEqualTo(LocalDate.of(2026, 9, 13));
        assertThat(result.periodMissing()).isFalse();
        assertThat(result.periodWeekdayMismatch()).isFalse();
        assertThat(result.unresolvedCodes()).isEmpty();
        assertThat(describe(result.rows().get(7))).isEqualTo(EXPECTED_ME);
    }

    // ===== 일정 열 판정 =====

    @Test
    @DisplayName("행 식별·요약 열은 일정 열이 아니다")
    void labelColumnsAreNotScheduleColumns() {
        assertThat(ScheduleTableInterpreter.isScheduleColumn("이름")).isFalse();
        assertThat(ScheduleTableInterpreter.isScheduleColumn("세부")).isFalse();
        assertThat(ScheduleTableInterpreter.isScheduleColumn("직급")).isFalse();
        assertThat(ScheduleTableInterpreter.isScheduleColumn("총 근무시간")).isFalse();
        assertThat(ScheduleTableInterpreter.isScheduleColumn("비고")).isFalse();
        assertThat(ScheduleTableInterpreter.isScheduleColumn("")).isFalse();
        assertThat(ScheduleTableInterpreter.isScheduleColumn(null)).isFalse();
    }

    @Test
    @DisplayName("휴일·총일수는 끝글자가 요일이어도 일정 열이 아니다")
    void wordsEndingInAWeekdayCharacterAreNotDates() {
        assertThat(ScheduleTableInterpreter.isScheduleColumn("휴일")).isFalse();
        assertThat(ScheduleTableInterpreter.isScheduleColumn("총일수")).isFalse();
    }

    @Test
    @DisplayName("요일과 날짜 표기는 일정 열이다")
    void weekdaysAndDatesAreScheduleColumns() {
        assertThat(ScheduleTableInterpreter.isScheduleColumn("월")).isTrue();
        assertThat(ScheduleTableInterpreter.isScheduleColumn("화요일")).isTrue();
        assertThat(ScheduleTableInterpreter.isScheduleColumn("(수)")).isTrue();
        assertThat(ScheduleTableInterpreter.isScheduleColumn("Sat.")).isTrue();
        assertThat(ScheduleTableInterpreter.isScheduleColumn("9/7")).isTrue();
        assertThat(ScheduleTableInterpreter.isScheduleColumn("09-07")).isTrue();
        assertThat(ScheduleTableInterpreter.isScheduleColumn("9.7")).isTrue();
        assertThat(ScheduleTableInterpreter.isScheduleColumn("7일")).isTrue();
        assertThat(ScheduleTableInterpreter.isScheduleColumn("7(월)")).isTrue();
        assertThat(ScheduleTableInterpreter.isScheduleColumn("9/7(월)")).isTrue();
    }

    @Test
    @DisplayName("맨 숫자 하나는 일정 열이 아니다 — 근무일수 합계 열이 그렇게 생겼다")
    void bareNumberIsNotAScheduleColumn() {
        assertThat(ScheduleTableInterpreter.isScheduleColumn("7")).isFalse();
        assertThat(ScheduleTableInterpreter.isScheduleColumn("40")).isFalse();
    }

    @Test
    @DisplayName("날짜 열의 요일은 괄호 안에서 읽는다")
    void weekdayInsideADateColumn() {
        assertThat(ScheduleTableInterpreter.weekdayOf("7(월)")).isEqualTo(DayOfWeek.MONDAY);
        assertThat(ScheduleTableInterpreter.weekdayOf("9/7(월)")).isEqualTo(DayOfWeek.MONDAY);
        assertThat(ScheduleTableInterpreter.weekdayOf("휴일")).isNull();
        assertThat(ScheduleTableInterpreter.weekdayOf("총일수")).isNull();
    }

    // ===== 정규화를 채택하지 않는 경우 =====

    @Test
    @DisplayName("2주짜리 표는 같은 요일이 두 번 나온다 — 중복을 지우면 개수가 어긋난다")
    void duplicateWeekdaysSurviveNormalization() {
        List<String> twoWeeks = List.of("이름", "월", "화", "수", "목", "금", "토", "일",
                "월", "화", "수", "목", "금", "토", "일");
        List<String> cells = Arrays.asList("17~23", "18~23", "D/O", "17~22", "18~23", "D/O", "D/O",
                "17~23", "18~23", "D/O", "17~22", "18~23", "D/O", "D/O");

        ScheduleExtractionResponse result = ScheduleTableInterpreter.interpret(
                new RawScheduleTable(true, "2주 근무표",
                        new RawScheduleTable.Period(9, 7, 9, 20, null),
                        Map.of("OP", "14~23"), twoWeeks,
                        List.of(new RawScheduleTable.Row(ME, "PT", cells))),
                TODAY, ME);

        assertThat(result.columnsNormalized()).isTrue();
        assertThat(result.rows().get(0).rowInvalid()).isFalse();
        assertThat(result.rows().get(0).cells()).hasSize(14);
        assertThat(result.rows().get(0).cells().get(13).date()).isEqualTo(LocalDate.of(2026, 9, 20));
    }

    @Test
    @DisplayName("골라낸 수가 셀 수와 안 맞으면 보정하지 않는다 — 맞을 때까지 자르지 않는다")
    void normalizationIsRejectedWhenTheCountStillDisagrees() {
        // "??"는 일정 열이 아니라 6개만 남는데 셀은 7개다. 자르면 하루씩 밀린다.
        ScheduleExtractionResponse result = ScheduleTableInterpreter.interpret(
                new RawScheduleTable(true, "근무표",
                        new RawScheduleTable.Period(9, 7, 9, 13, null),
                        Map.of(), List.of("월", "화", "??", "목", "금", "토", "일"),
                        List.of(new RawScheduleTable.Row(ME, "PT", Arrays.asList(
                                "17~23", "18~23", "18~23", "17~22", "18~23", "D/O", "D/O")))),
                TODAY, ME);

        assertThat(result.columnsNormalized()).isFalse();
        assertThat(result.columnsUnrecognized()).isTrue();
        assertThat(result.rows().get(0).rowInvalid())
                .as("열을 못 읽어도 개수는 맞으니 행 자체는 살아 있다")
                .isFalse();
    }

    @Test
    @DisplayName("깨진 행 하나가 표 전체의 열 배치를 정하지 않는다")
    void aSingleBrokenRowDoesNotDecideTheLayout() {
        ScheduleExtractionResponse result = ScheduleTableInterpreter.interpret(
                new RawScheduleTable(true, "근무표",
                        new RawScheduleTable.Period(9, 7, 9, 13, null),
                        Map.of(), List.of("이름", "월", "화", "수", "목", "금", "토", "일"),
                        List.of(
                                new RawScheduleTable.Row("깨진 행", null, List.of("17~23", "D/O")),
                                new RawScheduleTable.Row(ME, "PT", Arrays.asList(
                                        "17~23", "18~23", "18~23", "17~22", "18~23", "D/O", "D/O")),
                                new RawScheduleTable.Row("사원B", "JR", Arrays.asList(
                                        "OP", "OP", "D/O", "OP", "D/O", "OP", "OP")))),
                TODAY, ME);

        assertThat(result.columnsNormalized()).as("최빈 셀 수 7을 기준으로 삼는다").isTrue();
        assertThat(result.rows().get(0).rowInvalid()).isTrue();
        assertThat(result.rows().get(1).rowInvalid()).isFalse();
        assertThat(result.rows().get(2).rowInvalid()).isFalse();
    }

    // ===== fixture =====

    private static ScheduleExtractionResponse interpret(String fixture) {
        return ScheduleTableInterpreter.interpret(read(fixture), TODAY, ME);
    }

    private static RawScheduleTable read(String fixture) {
        try (InputStream in = ScheduleTableColumnNormalizationTest.class
                .getResourceAsStream("/schedule-import-raw/" + fixture)) {
            return new ObjectMapper().findAndRegisterModules().readValue(in, RawScheduleTable.class);
        } catch (Exception e) {
            throw new IllegalStateException("픽스처를 읽지 못했습니다: " + fixture, e);
        }
    }

    /** 셀을 사람이 읽을 한 줄로 줄인다 — 두 픽스처의 결과가 같은지 눈으로 비교할 수 있게. */
    private static List<String> describe(RowView row) {
        return row.cells().stream().map(ScheduleTableColumnNormalizationTest::describe).toList();
    }

    private static String describe(CellView cell) {
        if (cell.start() == null) {
            return cell.date() + " " + cell.kind();
        }
        return "%s %s %s-%s".formatted(cell.date(), cell.kind(),
                cell.start(), cell.end() == LocalTime.MIDNIGHT ? "24:00" : cell.end());
    }
}
