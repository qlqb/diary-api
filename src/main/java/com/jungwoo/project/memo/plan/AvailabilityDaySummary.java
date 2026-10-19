package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.scheduling.domain.AvailabilityConfidence;
import com.jungwoo.project.memo.scheduling.service.AvailabilityEstimateResult;
import com.jungwoo.project.memo.scheduling.domain.AvailabilityWindow;
import com.jungwoo.project.memo.scheduling.domain.BusyWindow;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 가용시간 추정 결과를 하루 한 줄짜리 텍스트로 접는다.
 *
 * <p>{@link AvailabilityEstimateResult}는 날짜를 모르는 연속 구간 목록이다. 그 자체로도
 * 프롬프트에 실을 수 있지만(기존 PeriodPlanDraftGenerator가 그렇게 한다), 31일 계획이면
 * 구간이 백 개를 넘어 상한에 잘리고 "이 요일에는 원래 시간이 없다" 같은 규칙성이 보이지
 * 않는다. 요일로 접으면 같은 정보가 하루 한 줄로 들어간다.
 *
 * <p>★ 여기서 가용시간을 새로 계산하지 않는다. 접기만 한다 — 추정의 유일한 원본은
 * AvailabilityEstimateService이고, 두 곳에서 계산하면 화면과 배치가 조용히 달라진다.
 *
 * <p>자정을 넘는 구간은 날짜 경계에서 자른다. 실제 데이터에 토·일 17:00~02:00 근무가 있고,
 * 자르지 않으면 그 근무가 토요일에만 보이고 일요일 새벽은 비어 있는 것처럼 읽힌다.
 */
public final class AvailabilityDaySummary {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    /** 하루에 실을 구간 줄 수 상한. 넘으면 "외 N건"으로 접는다. */
    private static final int MAX_SPANS_PER_DAY = 6;

    private AvailabilityDaySummary() {
    }

    /**
     * start~end의 각 날짜에 대해 한 줄씩. 날짜에 아무것도 없어도 줄을 뺀다 —
     * "이 날은 통째로 비어 있다"는 사실도 정보다.
     *
     * @return 날짜 순으로 정렬된 줄 목록. 예:
     *         {@code "9/6(토) 남는 시간 3시간 20분 · 일정 17:00~24:00 쿠팡알바 근무 · 가능 09:00~11:30, 13:00~16:30 (일부 추정)"}
     */
    public static List<String> format(LocalDate start, LocalDate end, AvailabilityEstimateResult availability) {
        Map<LocalDate, List<Span>> busyByDay = splitByDay(
                availability.busyWindows().stream()
                        .map(b -> new Span(b.startAt(), b.endAt(), b.label(), null))
                        .toList());
        Map<LocalDate, List<Span>> freeByDay = splitByDay(
                availability.windows().stream()
                        .map(w -> new Span(w.startAt(), w.endAt(), null, w.confidence()))
                        .toList());

        List<String> lines = new ArrayList<>();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            lines.add(line(date, busyByDay.getOrDefault(date, List.of()),
                    freeByDay.getOrDefault(date, List.of())));
        }
        return lines;
    }

    private static String line(LocalDate date, List<Span> busy, List<Span> free) {
        StringBuilder sb = new StringBuilder();
        sb.append(date.getMonthValue()).append('/').append(date.getDayOfMonth())
                .append('(').append(date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN)).append(") ");

        long freeMinutes = free.stream().mapToLong(Span::minutes).sum();
        sb.append(freeMinutes > 0 ? "남는 시간 " + duration(freeMinutes) : "남는 시간 없음");

        if (!busy.isEmpty()) {
            sb.append(" · 일정 ");
            appendSpans(sb, busy, true);
        }
        if (!free.isEmpty()) {
            sb.append(" · 가능 ");
            appendSpans(sb, free, false);
            AvailabilityConfidence lowest = free.stream()
                    .map(Span::confidence)
                    .max(java.util.Comparator.comparingInt(Enum::ordinal))
                    .orElse(null);
            // ordinal이 클수록 낮은 신뢰도다(HIGH=0, LOW=2). 하루 안에서 가장 약한 근거를
            // 말한다 — 한 구간이라도 추정이면 그날 계획 전체가 그만큼 흔들린다.
            if (lowest == AvailabilityConfidence.LOW) {
                sb.append(" (추정)");
            } else if (lowest == AvailabilityConfidence.MEDIUM) {
                sb.append(" (일부 추정)");
            }
        }
        return sb.toString();
    }

    private static void appendSpans(StringBuilder sb, List<Span> spans, boolean withLabel) {
        int shown = 0;
        for (Span span : spans) {
            if (shown >= MAX_SPANS_PER_DAY) {
                sb.append(" 외 ").append(spans.size() - MAX_SPANS_PER_DAY).append("건");
                return;
            }
            if (shown > 0) {
                sb.append(", ");
            }
            sb.append(span.render(withLabel));
            shown++;
        }
    }

    /**
     * 날짜를 넘는 구간을 날짜별 조각으로 자른다. 자정에 끝나는 조각은 24:00으로 적는다 —
     * 00:00으로 적으면 "0시에 끝난다"가 "0시에 시작한다"처럼 읽힌다.
     */
    private static Map<LocalDate, List<Span>> splitByDay(List<Span> spans) {
        Map<LocalDate, List<Span>> byDay = new LinkedHashMap<>();
        for (Span span : spans) {
            LocalDate date = span.start.toLocalDate();
            LocalDateTime cursor = span.start;
            while (cursor.isBefore(span.end)) {
                LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();
                LocalDateTime sliceEnd = span.end.isBefore(dayEnd) ? span.end : dayEnd;
                byDay.computeIfAbsent(date, d -> new ArrayList<>())
                        .add(new Span(cursor, sliceEnd, span.label, span.confidence));
                cursor = sliceEnd;
                date = date.plusDays(1);
            }
        }
        byDay.values().forEach(list -> list.sort(java.util.Comparator.comparing(s -> s.start)));
        return byDay;
    }

    private static String duration(long minutes) {
        long hours = minutes / 60;
        long rest = minutes % 60;
        if (hours == 0) {
            return rest + "분";
        }
        return rest == 0 ? hours + "시간" : hours + "시간 " + rest + "분";
    }

    private record Span(LocalDateTime start, LocalDateTime end, String label, AvailabilityConfidence confidence) {

        long minutes() {
            return Duration.between(start, end).toMinutes();
        }

        String render(boolean withLabel) {
            String from = start.toLocalTime().format(TIME);
            // 자정에 끝나는 조각. LocalTime.MIDNIGHT은 하루의 시작이라 끝으로 쓰면 안 된다.
            String to = end.toLocalTime().equals(LocalTime.MIDNIGHT) && end.toLocalDate().isAfter(start.toLocalDate())
                    ? "24:00"
                    : end.toLocalTime().format(TIME);
            String span = from + "~" + to;
            return withLabel && label != null ? span + " " + label : span;
        }
    }
}
