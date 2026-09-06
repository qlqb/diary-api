package com.jungwoo.project.memo.ai.scheduleimport.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * 이미지에서 모델이 <b>받아쓴</b> 표. 해석은 하나도 들어 있지 않다.
 *
 * <p>★ 셀은 원문 그대로다. {@code "OP"}, {@code "17~23"}, {@code "D/O"}가 그대로 들어온다.
 * 모델에게 시간으로 바꾸게 하지 않는 이유는, 바꿔 온 값이 표에 적힌 것을 옮긴 것인지
 * 그럴듯하게 지어낸 것인지 서버가 구분할 방법이 없기 때문이다. 시간 변환·날짜 계산은 전부
 * 서버 코드({@code CellParser}, {@code ScheduleTableInterpreter})다.
 *
 * <p>이 JSON은 클라이언트를 한 번 거쳐 {@code confirm}으로 되돌아온다(임시 테이블을 두지
 * 않기 위해서다). 그래서 서버는 돌아온 값을 <b>신뢰하지 않고 처음부터 다시 파싱한다</b> —
 * 여기 담긴 것이 원문뿐이라 다시 파싱하는 것이 가능하고, 해석 결과를 담았다면 불가능했다.
 *
 * @param isScheduleTable 일정표가 아니면 false. 이때 rows는 빈 배열이다
 * @param period          표에 적힌 기간. 적혀 있지 않은 필드는 null이고, <b>연도는 거의 항상
 *                        null</b>이다 — 모델이 연도를 추측하면 안 된다
 * @param legend          표 안이나 아래의 범례. 없으면 빈 맵
 * @param columns         요일 원문(월/Mon/MON). 정규화는 서버가 한다
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RawScheduleTable(
        boolean isScheduleTable,
        String title,
        Period period,
        Map<String, String> legend,
        List<String> columns,
        List<Row> rows
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Period(Integer startMonth, Integer startDay, Integer endMonth, Integer endDay, Integer year) {

        public boolean isEmpty() {
            return startMonth == null || startDay == null;
        }
    }

    /**
     * 표의 한 줄.
     *
     * @param tag   직급·구분 표기(SR, PT). 화면이 행을 고를 때 이름만으로 헷갈리지 않게 돕는다
     * @param cells 요일 수와 길이가 같아야 한다. 다르면 그 행만 제외하고 나머지는 살린다
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Row(String name, String tag, List<String> cells) {
    }

    public List<Row> safeRows() {
        return rows == null ? List.of() : rows;
    }

    public List<String> safeColumns() {
        return columns == null ? List.of() : columns;
    }

    public Map<String, String> safeLegend() {
        return legend == null ? Map.of() : legend;
    }
}
