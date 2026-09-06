package com.jungwoo.project.memo.ai.scheduleimport;

import com.jungwoo.project.memo.ai.scheduleimport.dto.RawScheduleTable;
import com.jungwoo.project.memo.ai.scheduleimport.dto.ScheduleExtractionResponse;
import com.jungwoo.project.memo.ai.scheduleimport.dto.ScheduleExtractionResponse.CellView;
import com.jungwoo.project.memo.ai.scheduleimport.dto.ScheduleExtractionResponse.ResolvedPeriod;
import com.jungwoo.project.memo.ai.scheduleimport.dto.ScheduleExtractionResponse.RowView;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 받아쓴 표를 날짜와 시간으로 옮긴다. 순수 함수이고 LLM에 의존하지 않는다.
 *
 * <p>여기가 결정적이라는 것이 이 기능의 핵심이다. 같은 이미지에 늘 같은 일정이 나오고,
 * 틀렸을 때 어느 규칙이 틀렸는지 짚을 수 있다. 연도 보정·요일 정합성·이름 매칭을 모델에게
 * 맡기면 그 셋이 매번 조금씩 달라지고, 사용자는 "지난번엔 맞았는데"라고만 말할 수 있다.
 */
public final class ScheduleTableInterpreter {

    /** 요일 표기. 한 표 안에서 한글·영문·대소문자가 섞여 나온다. */
    private static final Map<String, DayOfWeek> WEEKDAYS = Map.ofEntries(
            Map.entry("월", DayOfWeek.MONDAY), Map.entry("화", DayOfWeek.TUESDAY),
            Map.entry("수", DayOfWeek.WEDNESDAY), Map.entry("목", DayOfWeek.THURSDAY),
            Map.entry("금", DayOfWeek.FRIDAY), Map.entry("토", DayOfWeek.SATURDAY),
            Map.entry("일", DayOfWeek.SUNDAY),
            Map.entry("MON", DayOfWeek.MONDAY), Map.entry("TUE", DayOfWeek.TUESDAY),
            Map.entry("WED", DayOfWeek.WEDNESDAY), Map.entry("THU", DayOfWeek.THURSDAY),
            Map.entry("FRI", DayOfWeek.FRIDAY), Map.entry("SAT", DayOfWeek.SATURDAY),
            Map.entry("SUN", DayOfWeek.SUNDAY),
            Map.entry("MONDAY", DayOfWeek.MONDAY), Map.entry("TUESDAY", DayOfWeek.TUESDAY),
            Map.entry("WEDNESDAY", DayOfWeek.WEDNESDAY), Map.entry("THURSDAY", DayOfWeek.THURSDAY),
            Map.entry("FRIDAY", DayOfWeek.FRIDAY), Map.entry("SATURDAY", DayOfWeek.SATURDAY),
            Map.entry("SUNDAY", DayOfWeek.SUNDAY));

    private ScheduleTableInterpreter() {
    }

    /**
     * @param today       연도 보정의 기준. 호출부가 Clock에서 얻어 넘긴다 — 여기서 시계를
     *                    읽으면 이 함수가 더 이상 순수하지 않고 테스트가 오늘 날짜에 묶인다
     * @param displayName 본인 행을 미리 골라 볼 이름. 없으면 null
     */
    public static ScheduleExtractionResponse interpret(RawScheduleTable raw, LocalDate today, String displayName) {
        List<String> columns = raw.safeColumns();
        boolean columnsUnrecognized = columns.isEmpty()
                || columns.stream().anyMatch(column -> weekdayOf(column) == null);

        LocalDate startDate = resolveStartDate(raw.period(), today);
        boolean periodMissing = startDate == null;
        LocalDate endDate = resolveEndDate(raw.period(), startDate, columns.size());

        /*
         * 보정한 시작일의 요일이 표의 첫 열과 다르면 우리가 고른 주가 틀렸을 가능성이 크다.
         * 후보는 만들되 화면이 강조하고 사용자가 주를 다시 고르게 한다 — 조용히 밀어 넣으면
         * 사용자는 일주일 어긋난 일정을 나중에 달력에서 발견한다.
         */
        boolean mismatch = false;
        if (startDate != null && !columns.isEmpty()) {
            DayOfWeek firstColumn = weekdayOf(columns.get(0));
            mismatch = firstColumn != null && startDate.getDayOfWeek() != firstColumn;
        }

        List<RowView> rows = new ArrayList<>();
        Set<String> unresolved = new LinkedHashSet<>();
        List<RawScheduleTable.Row> rawRows = raw.safeRows();
        for (int index = 0; index < rawRows.size(); index++) {
            RawScheduleTable.Row row = rawRows.get(index);
            List<String> cells = row.cells() == null ? List.of() : row.cells();
            // 셀 수가 요일 수와 다르면 어느 날인지 셀 수 없다. 그 행만 빼고 나머지는 살린다.
            boolean rowInvalid = cells.size() != columns.size() || columns.isEmpty();

            List<CellView> views = new ArrayList<>();
            if (!rowInvalid) {
                for (int day = 0; day < cells.size(); day++) {
                    ParsedCell parsed = CellParser.parse(cells.get(day), raw.safeLegend());
                    if (parsed.kind() == ParsedCell.Kind.UNRESOLVED && !parsed.raw().isBlank()) {
                        unresolved.add(parsed.raw());
                    }
                    views.add(new CellView(
                            startDate == null ? null : startDate.plusDays(day),
                            parsed.raw(), parsed.kind().name(),
                            parsed.start(), parsed.end(), parsed.crossesMidnight(), parsed.code()));
                }
            }
            rows.add(new RowView(index, row.name(), row.tag(), rowInvalid, views));
        }

        return new ScheduleExtractionResponse(
                raw,
                startDate == null ? null : new ResolvedPeriod(startDate, endDate),
                periodMissing, mismatch, columnsUnrecognized,
                matchRow(rawRows, displayName),
                rows,
                new ArrayList<>(unresolved));
    }

    /** 요일 하나를 읽는다. 못 읽으면 null — 추측하지 않는다. */
    public static DayOfWeek weekdayOf(String column) {
        if (column == null) {
            return null;
        }
        // "월요일", "(월)", "Mon." 같은 표기에서 요일 글자만 남긴다.
        String cleaned = column.trim().replaceAll("[()\\[\\].·\\s]", "").toUpperCase(Locale.ROOT);
        if (cleaned.isEmpty()) {
            return null;
        }
        DayOfWeek exact = WEEKDAYS.get(cleaned);
        if (exact != null) {
            return exact;
        }
        if (cleaned.endsWith("요일")) {
            return WEEKDAYS.get(cleaned.substring(0, cleaned.length() - 2));
        }
        return WEEKDAYS.get(cleaned.substring(0, 1));
    }

    /**
     * 표에 연도가 없을 때 어느 해인지 고른다.
     *
     * <p>작년·올해·내년 중 오늘과 가장 가까운 것을 쓴다. 근무표는 대개 이번 주나 다음 주
     * 것이고, 12월 말에 1월 표를 받는 경우가 실제로 있어 "올해"로 고정하면 한 해 어긋난다.
     */
    private static LocalDate resolveStartDate(RawScheduleTable.Period period, LocalDate today) {
        if (period == null || period.isEmpty()) {
            return null;
        }
        if (period.year() != null) {
            return safeDate(period.year(), period.startMonth(), period.startDay());
        }
        LocalDate best = null;
        long bestDistance = Long.MAX_VALUE;
        for (int year : new int[]{today.getYear() - 1, today.getYear(), today.getYear() + 1}) {
            LocalDate candidate = safeDate(year, period.startMonth(), period.startDay());
            if (candidate == null) {
                continue;
            }
            long distance = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(today, candidate));
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** 종료일이 표에 없으면 열 수만큼 이어진다고 본다. */
    private static LocalDate resolveEndDate(RawScheduleTable.Period period, LocalDate startDate, int columnCount) {
        if (startDate == null) {
            return null;
        }
        if (period != null && period.endMonth() != null && period.endDay() != null) {
            LocalDate sameYear = safeDate(startDate.getYear(), period.endMonth(), period.endDay());
            if (sameYear != null) {
                // 12/30~1/5처럼 해를 넘기는 표. 종료가 시작보다 이르면 다음 해다.
                return sameYear.isBefore(startDate) ? sameYear.plusYears(1) : sameYear;
            }
        }
        return columnCount > 0 ? startDate.plusDays(columnCount - 1L) : startDate;
    }

    private static LocalDate safeDate(int year, Integer month, Integer day) {
        if (month == null || day == null) {
            return null;
        }
        try {
            return LocalDate.of(year, month, day);
        } catch (java.time.DateTimeException e) {
            // 모델이 2월 30일 같은 값을 낼 수 있다. 없는 날짜는 그냥 후보에서 빠진다.
            return null;
        }
    }

    /**
     * 이름이 <b>정확히 하나</b> 일치하는 행. 둘 이상이거나 없으면 null이다.
     *
     * <p>자동 매칭은 "미리 선택"일 뿐이고 화면은 늘 전체 목록을 보여준다. 본인 행을 모델에게
     * 고르게 하지 않는 이유와 같다 — 잘못 고르면 남의 근무가 내 달력에 들어온다.
     */
    private static Integer matchRow(List<RawScheduleTable.Row> rows, String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return null;
        }
        String target = displayName.trim();
        Integer found = null;
        for (int index = 0; index < rows.size(); index++) {
            String name = rows.get(index).name();
            if (name != null && name.trim().equals(target)) {
                if (found != null) {
                    return null;
                }
                found = index;
            }
        }
        return found;
    }
}
