package com.jungwoo.project.memo.ai.scheduleimport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 셀 읽기. 예시는 실제 근무표에서 보이는 표기를 모아 온 것이다 — 지어낸 입력으로 만든
 * 파서는 사람이 손으로 쓴 표에서 조용히 빗나간다.
 */
class CellParserTest {

    private static final Map<String, String> LEGEND = Map.of("OP", "14~23", "CL", "15~00");

    @Test
    @DisplayName("가장 흔한 모양: 17~23")
    void plainRange() {
        ParsedCell cell = CellParser.parse("17~23", Map.of());

        assertThat(cell.kind()).isEqualTo(ParsedCell.Kind.WORK);
        assertThat(cell.start()).isEqualTo(LocalTime.of(17, 0));
        assertThat(cell.end()).isEqualTo(LocalTime.of(23, 0));
        assertThat(cell.crossesMidnight()).isFalse();
        assertThat(cell.code()).isNull();
    }

    @Test
    @DisplayName("종료가 자정이면 다음 날이다 — 00:00 하나만 남기면 그날 새벽과 구분되지 않는다")
    void endsAtMidnight() {
        ParsedCell cell = CellParser.parse("18~00", Map.of());

        assertThat(cell.start()).isEqualTo(LocalTime.of(18, 0));
        assertThat(cell.end()).isEqualTo(LocalTime.MIDNIGHT);
        assertThat(cell.crossesMidnight()).isTrue();
    }

    @Test
    @DisplayName("종료가 시작보다 이르면 야간 근무다")
    void overnightShift() {
        ParsedCell cell = CellParser.parse("22~02", Map.of());

        assertThat(cell.start()).isEqualTo(LocalTime.of(22, 0));
        assertThat(cell.end()).isEqualTo(LocalTime.of(2, 0));
        assertThat(cell.crossesMidnight()).isTrue();
    }

    @Test
    @DisplayName("분·시 표기·앞자리 0이 섞여도 읽는다 — 한 표 안에서 표기가 갈린다")
    void variousNotations() {
        assertThat(CellParser.parse("9:30-13:00", Map.of()).start()).isEqualTo(LocalTime.of(9, 30));
        assertThat(CellParser.parse("9:30-13:00", Map.of()).end()).isEqualTo(LocalTime.of(13, 0));
        assertThat(CellParser.parse("9시~13시", Map.of()).start()).isEqualTo(LocalTime.of(9, 0));
        assertThat(CellParser.parse("09~13", Map.of()).end()).isEqualTo(LocalTime.of(13, 0));
        assertThat(CellParser.parse("10–18", Map.of()).end()).isEqualTo(LocalTime.of(18, 0));
    }

    @Test
    @DisplayName("범례에 있는 코드는 그 시간으로 읽고, 코드를 기억해 둔다")
    void legendCode() {
        ParsedCell cell = CellParser.parse("OP", LEGEND);

        assertThat(cell.kind()).isEqualTo(ParsedCell.Kind.WORK);
        assertThat(cell.start()).isEqualTo(LocalTime.of(14, 0));
        assertThat(cell.end()).isEqualTo(LocalTime.of(23, 0));
        assertThat(cell.code())
                .as("일정 제목이 '근무 (OP)'가 되려면 코드가 남아야 한다")
                .isEqualTo("OP");
        assertThat(cell.raw()).isEqualTo("OP");
    }

    @Test
    @DisplayName("대소문자를 가리지 않는다 — 같은 표에 OP와 op가 섞여 나온다")
    void legendIsCaseInsensitive() {
        assertThat(CellParser.parse("op", LEGEND).start()).isEqualTo(LocalTime.of(14, 0));
    }

    @Test
    @DisplayName("범례의 자정 넘김도 그대로 읽는다")
    void legendCrossingMidnight() {
        ParsedCell cell = CellParser.parse("CL", LEGEND);

        assertThat(cell.end()).isEqualTo(LocalTime.MIDNIGHT);
        assertThat(cell.crossesMidnight()).isTrue();
        assertThat(cell.code()).isEqualTo("CL");
    }

    @Test
    @DisplayName("범례에 없는 코드는 추측하지 않는다")
    void unknownCodeStaysUnresolved() {
        ParsedCell cell = CellParser.parse("A", LEGEND);

        assertThat(cell.kind()).isEqualTo(ParsedCell.Kind.UNRESOLVED);
        assertThat(cell.raw()).isEqualTo("A");
    }

    @Test
    @DisplayName("범례가 설명만 적어 뒀으면 시간을 모르는 것이다")
    void legendWithoutATimeIsStillUnknown() {
        assertThat(CellParser.parse("OP", Map.of("OP", "오픈 근무")).kind())
                .isEqualTo(ParsedCell.Kind.UNRESOLVED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"D/O", "DO", "OFF", "off", "휴", "휴무", "X", "-", "", "   "})
    @DisplayName("쉬는 날은 후보를 만들지 않는다 — 빈 시간이 곧 휴무다")
    void offTokens(String raw) {
        assertThat(CellParser.parse(raw, LEGEND).kind()).isEqualTo(ParsedCell.Kind.OFF);
    }

    @Test
    @DisplayName("시각이 범위를 벗어나거나 길이가 0이면 모르는 칸이다")
    void invalidRanges() {
        assertThat(CellParser.parse("25~26", Map.of()).kind()).isEqualTo(ParsedCell.Kind.UNRESOLVED);
        assertThat(CellParser.parse("17~17", Map.of()).kind())
                .as("길이 0은 근무가 아니다 — 표를 잘못 읽었을 가능성이 높다")
                .isEqualTo(ParsedCell.Kind.UNRESOLVED);
        assertThat(CellParser.parse("9:70-13:00", Map.of()).kind()).isEqualTo(ParsedCell.Kind.UNRESOLVED);
        assertThat(CellParser.parse("24~08", Map.of())
                .kind()).as("시작이 24시일 수는 없다").isEqualTo(ParsedCell.Kind.UNRESOLVED);
    }

    @Test
    @DisplayName("합계 행의 숫자 같은 것은 모르는 칸으로 빠진다 — 전체를 실패시키지 않는다")
    void aggregateNumbersFallThrough() {
        assertThat(CellParser.parse("40", Map.of()).kind()).isEqualTo(ParsedCell.Kind.UNRESOLVED);
        assertThat(CellParser.parse("합계", Map.of()).kind()).isEqualTo(ParsedCell.Kind.UNRESOLVED);
    }

    @Test
    @DisplayName("null 셀도 쉬는 날로 읽는다 — 모델이 빈 칸을 null로 낼 수 있다")
    void nullCell() {
        assertThat(CellParser.parse(null, Map.of()).kind()).isEqualTo(ParsedCell.Kind.OFF);
    }
}
