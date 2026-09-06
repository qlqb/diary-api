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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

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

    /**
     * 날짜 표기 열. {@code 9/7}, {@code 09-07}, {@code 9.7}, {@code 7(월)}, {@code 9/7(월)},
     * {@code 7일}까지 본다. 맨 숫자 하나({@code 7})는 일부러 뺐다 — 근무일수·시간 합계 열이
     * 그렇게 생겼고, 그것을 일정 열로 세면 셀이 한 칸씩 밀린다.
     */
    private static final Pattern DATE_COLUMN = Pattern.compile(
            "^\\d{1,2}\\s*(?:[./\\-]\\s*\\d{1,2}|일)\\s*(?:[(\\[][^)\\]]*[)\\]])?$");

    /** {@code 7(월)}처럼 괄호 안에 요일을 단 날짜 열. */
    private static final Pattern DAY_WITH_WEEKDAY = Pattern.compile(
            "^\\d{1,2}\\s*[(\\[]\\s*\\S+\\s*[)\\]]$");

    private ScheduleTableInterpreter() {
    }

    /**
     * @param today       연도 보정의 기준. 호출부가 Clock에서 얻어 넘긴다 — 여기서 시계를
     *                    읽으면 이 함수가 더 이상 순수하지 않고 테스트가 오늘 날짜에 묶인다
     * @param displayName 본인 행을 미리 골라 볼 이름. 없으면 null
     */
    public static ScheduleExtractionResponse interpret(RawScheduleTable raw, LocalDate today, String displayName) {
        List<RawScheduleTable.Row> rawRows = raw.safeRows();
        List<String> rawColumns = raw.safeColumns();

        /*
         * 머리글에서 일정 열만 골라낸다. 실제로 받아쓴 표의 머리글 줄에는 "이름", "세부" 같은
         * 행 식별 칸이 함께 있고(모델이 지어낸 게 아니라 표에 정말 있다), 그러면 머리글 수와
         * 셀 수가 어긋나 전 행이 무효가 된다 — 같은 이미지 3회 중 2회가 그렇게 죽었다.
         *
         * 자를 때 앞뒤 위치를 가정하지 않는다. 합계 열이 가운데 끼는 표가 있고, 위치로 자르면
         * 그런 표에서 조용히 한 칸씩 밀린다. 순서는 보존하고 중복도 지운다 — 2주짜리 표는
         * "월"이 두 번 나오는 게 정상이고, 중복을 지우면 개수가 안 맞아 도로 무효가 된다.
         */
        List<String> scheduleColumns = rawColumns.stream()
                .filter(ScheduleTableInterpreter::isScheduleColumn)
                .toList();

        /*
         * 골라낸 개수가 셀 수와 정확히 같을 때만 채택한다. 이 조건이 없으면 머리글을 잘못
         * 읽은 표에서도 "맞을 때까지" 자르게 되고, 그건 어긋난 걸 어긋난 채로 밀어 넣는 것이다.
         * 기준이 되는 셀 수는 가장 흔한 행의 것이다 — 깨진 행 하나가 표 전체의 열 배치를
         * 정하면 안 된다.
         */
        int cellCount = modalCellCount(rawRows);
        boolean columnsNormalized = scheduleColumns.size() != rawColumns.size()
                && !scheduleColumns.isEmpty()
                && scheduleColumns.size() == cellCount;
        List<String> columns = columnsNormalized ? scheduleColumns : rawColumns;

        boolean columnsUnrecognized = columns.isEmpty()
                || columns.stream().anyMatch(column -> !isScheduleColumn(column));

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
                periodMissing, mismatch, columnsUnrecognized, columnsNormalized,
                matchRow(rawRows, displayName),
                rows,
                new ArrayList<>(unresolved));
    }

    /**
     * 이 머리글이 <b>일정 열</b>인가. 즉 그 아래 칸이 어느 하루의 근무를 담는 열인가.
     *
     * <p>인정하는 것은 요일 단독({@code 월}, {@code 화요일}, {@code (수)}, {@code Mon},
     * {@code Sat.})과 날짜 표기({@code 9/7}, {@code 09-07}, {@code 9.7}, {@code 7일},
     * {@code 7(월)}, {@code 9/7(월)})뿐이다. "이름"·"세부"·"총 근무시간"은 여기서 떨어진다.
     *
     * <p>맨 숫자 하나는 일부러 인정하지 않는다 — 근무일수 합계 열이 그렇게 생겼다.
     */
    public static boolean isScheduleColumn(String column) {
        if (column == null) {
            return false;
        }
        String trimmed = column.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        return weekdayOf(trimmed) != null
                || DATE_COLUMN.matcher(trimmed).matches()
                || DAY_WITH_WEEKDAY.matcher(trimmed).matches();
    }

    /**
     * 행들이 가진 가장 흔한 셀 수. 열 정규화를 채택할지 판단하는 기준이다.
     *
     * <p>첫 행이 아니라 최빈값을 쓰는 이유는 깨진 행이 맨 앞에 올 수 있기 때문이다. 그
     * 한 줄이 표 전체의 열 배치를 정하면, 정상인 나머지 행들이 그 줄에 맞춰 무효가 된다.
     */
    private static int modalCellCount(List<RawScheduleTable.Row> rows) {
        Map<Integer, Integer> counts = new HashMap<>();
        int best = 0;
        int bestCount = 0;
        for (RawScheduleTable.Row row : rows) {
            int size = row.cells() == null ? 0 : row.cells().size();
            if (size == 0) {
                continue;
            }
            int seen = counts.merge(size, 1, Integer::sum);
            if (seen > bestCount) {
                best = size;
                bestCount = seen;
            }
        }
        return best;
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
        /*
         * "7(월)"·"9/7(월)"은 괄호를 떼면 "7월"·"9/7월"이 되어 앞글자로는 읽히지 않는다.
         * 끝글자를 보되 숫자가 섞인 머리글에서만 그렇게 한다 — 조건 없이 끝글자를 보면
         * "휴일"이 일요일이 되고 "총일수"가 수요일이 된다.
         */
        if (cleaned.chars().anyMatch(Character::isDigit)) {
            DayOfWeek trailing = WEEKDAYS.get(cleaned.substring(cleaned.length() - 1));
            if (trailing != null) {
                return trailing;
            }
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
