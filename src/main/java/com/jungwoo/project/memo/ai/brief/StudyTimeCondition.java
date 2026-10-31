package com.jungwoo.project.memo.ai.brief;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 사용자가 말한 공부 시간 조건 — 해석한 값이다. 사용자 원문 근거({@link Basis})와 화면 문구({@link #label()})와 따로 둔다.
 * 화면 문구는 이 값에서 만들 뿐 다시 해석하지 않는다(예전 꼬리표 문구가 원문 대조를 망가뜨린 것을 되풀이하지 않는다).
 *
 * <p>공부 범위(windows)와 그 안에서 뺄 구간(exclusions, 식사 등)과 총 공부량(minutes)은 서로 다른 값이다. 범위를 말했다고 범위를
 * 채우라는 뜻이 정해지지는 않는다 — 그것은 intent와 분량이 정한다({@code StudyTimeBudget}).
 *
 * @param status  ADOPTED(채택) / AMBIGUOUS(물어야 할 부분이 있음) / VERIFY_FAILED(원문에서 확인하지 못함)
 * @param per     minutes의 단위: DAY(하루) / PLAN(계획 전체)
 * @param intent  FILL(범위에 공부를 잡아 달라) / AVAILABLE(그 시간이 비어 있다는 정보) / null(모름 — 범위를 공부 범위로 본다)
 * @param notes   해석 메모(사용자에게 보인다): 오전·오후 선택, 이미 지난 시작, 식사 길이 가정 등
 * @param issues  채택하지 못했거나 물어야 할 부분
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StudyTimeCondition(String status, List<Range> windows, List<Range> exclusions, Integer minutes, String per,
                                 String intent, List<Basis> basis, List<String> notes, List<Issue> issues) {

    public static final String ADOPTED = "ADOPTED";
    public static final String AMBIGUOUS = "AMBIGUOUS";
    public static final String VERIFY_FAILED = "VERIFY_FAILED";

    public StudyTimeCondition {
        windows = windows == null ? List.of() : List.copyOf(windows);
        exclusions = exclusions == null ? List.of() : List.copyOf(exclusions);
        basis = basis == null ? List.of() : List.copyOf(basis);
        notes = notes == null ? List.of() : List.copyOf(notes);
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    /**
     * 하루 안의 구간. date가 null이면 계획의 모든 날에 같은 시각으로 적용한다. start가 null이면 "끝만 말했다"(그날 기본 시작부터).
     * endsNextDay면 end는 다음 날 시각이다("새벽 1시까지", "자정까지"=다음 날 00:00).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Range(LocalDate date, LocalTime start, LocalTime end, boolean endsNextDay, String label) {

        /** 그날의 실제 구간. 이 구간이 그날에 해당하지 않으면 null. */
        public LocalDateTime[] on(LocalDate day, LocalTime defaultStart) {
            if (date != null && !date.equals(day)) {
                return null;
            }
            LocalDateTime from = day.atTime(start == null ? defaultStart : start);
            LocalDateTime to = end == null ? day.plusDays(1).atStartOfDay()
                    : (endsNextDay ? day.plusDays(1) : day).atTime(end);
            return from.isBefore(to) ? new LocalDateTime[]{from, to} : null;
        }

        @JsonIgnore
        public String text() {
            String s = start == null ? "" : start.toString();
            String e = end == null ? "24:00" : (endsNextDay && end.equals(LocalTime.MIDNIGHT) ? "24:00"
                    : (endsNextDay ? "다음 날 " : "") + end);
            return (date == null ? "" : date.getMonthValue() + "/" + date.getDayOfMonth() + " ") + s + "~" + e;
        }
    }

    /** 사용자 원문 근거. role: WINDOW / EXCLUSION / AMOUNT. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Basis(String role, Long messageId, String quote) {
    }

    /**
     * code: NOT_FOUND(원문에서 구절을 찾지 못함) / VALUE_MISMATCH(값이 구절에서 읽히지 않음) / UNREADABLE(구절에서 시각을 읽지 못함)
     * / AMBIGUOUS_UNIT(여러 날 계획에서 하루 몫인지 전체 몫인지 모름) / LATER_TIME_UNUSED(나중 발언의 시간 표현이 반영되지 않음).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Issue(String code, String detail, String quote) {
    }

    @JsonIgnore
    public boolean adopted() {
        return ADOPTED.equals(status);
    }

    @JsonIgnore
    public boolean hasWindow() {
        return !windows.isEmpty();
    }

    /** 화면 문구 — 해석한 값에서 만든다. 저장된 문구를 다시 해석하지 않는다. */
    @JsonIgnore
    public String label() {
        List<String> parts = new ArrayList<>();
        if (!windows.isEmpty()) {
            parts.add(String.join(", ", windows.stream().map(Range::text).toList()));
        }
        if (!exclusions.isEmpty()) {
            parts.add("제외 " + String.join(", ", exclusions.stream().map(r -> (r.label() == null ? "" : r.label() + " ")
                    + r.text()).toList()));
        }
        if (minutes != null) {
            parts.add(("PLAN".equals(per) ? "총 " : "하루 ") + minutes + "분");
        }
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }

    /** 분량의 단위(하루·전체)가 확인됐는가. 여러 날 계획에서 단위를 모르면 분량을 예산에 쓰지 않는다(물어야 한다). */
    @JsonIgnore
    public boolean amountConfirmed() {
        return minutes != null && issues.stream().noneMatch(i -> "AMBIGUOUS_UNIT".equals(i.code()));
    }

    /** 이 조건의 공부 총량(분). 말하지 않았거나 단위를 확인하지 못했으면 null. */
    @JsonIgnore
    public Integer totalFor(int days) {
        if (!amountConfirmed()) {
            return null;
        }
        return "PLAN".equals(per) ? minutes : minutes * Math.max(1, days);
    }
}
