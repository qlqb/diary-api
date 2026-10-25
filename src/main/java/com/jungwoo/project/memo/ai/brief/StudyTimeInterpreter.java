package com.jungwoo.project.memo.ai.brief;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 모델이 정리한 시간 조건(구간·제외·분량)을 사용자 원문으로 확인해 {@link StudyTimeCondition}으로 만든다.
 *
 * <p>원문 검증을 없애지 않는다. 다만 "문장 하나에 시간 표현이 정확히 하나"를 요구하던 것을 <b>부분별 검증</b>으로 바꾼다 — 부분마다 모델이
 * 인용한 구절을 소유가 확인된 사용자 발언에서 찾고, 그 구절에서 값이 읽히는지 본다. 그래서 "2시부터 8시까지 공부하고 6시엔 저녁 먹어"는
 * 구간(2시부터 8시까지)과 제외(6시엔 저녁)로 각각 확인된다.
 *
 * <p>오전·오후는 "그 말을 한 시각"과 계획 날짜로 고른다. 생성 시각으로 고르지 않는다(생성이 1분 걸려 2시를 넘겼다고 2시부터가 사라지면
 * 안 된다). 시작이 이미 지났으면 조건은 그대로 두고 메모만 남긴다 — 실제 배치가 지금 이후로 제한될 뿐이다. 확인하지 못한 부분은 버리지
 * 않고 issues에 남겨 화면이 알린다.
 */
public final class StudyTimeInterpreter {

    /** 오전·오후를 말하지 않은 시각이 공부 시간으로 그럴듯한 시작 범위의 아래 끝. */
    static final LocalTime PLAUSIBLE_FROM = LocalTime.of(6, 0);
    /** 끝만 말한 구간("4시까지")의 기본 시작 — 하루 기본 창 시작과 같다. */
    public static final LocalTime DEFAULT_START = LocalTime.of(9, 0);
    /** 식사처럼 시작만 말한 제외 구간의 기본 길이(분). 가정이므로 메모로 알린다. */
    static final int DEFAULT_EXCLUSION_MINUTES = 60;

    private StudyTimeInterpreter() {
    }

    /** 모델이 낸 조건의 한 부분. role: WINDOW / EXCLUSION / AMOUNT. 값은 HH:mm(자정은 24:00), 날짜는 YYYY-MM-DD. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Part(String role, String quote, String from, String until, String date, Integer minutes, String per,
                       String label) {
    }

    /** 소유가 확인된 사용자 발언. saidAt은 그 말을 한 시각. */
    public record Utterance(Long messageId, String text, LocalDateTime saidAt) {
    }

    /**
     * @param intent    FILL / AVAILABLE / null
     * @param planStart 계획 첫날
     * @param days      계획 일수
     * @return 부분이 하나도 없으면 null(시간 조건 없음)
     */
    public static StudyTimeCondition interpret(List<Part> parts, String intent, List<Utterance> utterances,
                                               LocalDate planStart, int days) {
        if (parts == null || parts.isEmpty()) {
            return null;
        }
        List<StudyTimeCondition.Range> windows = new ArrayList<>();
        List<StudyTimeCondition.Range> exclusions = new ArrayList<>();
        List<StudyTimeCondition.Basis> basis = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        List<StudyTimeCondition.Issue> issues = new ArrayList<>();
        Integer minutes = null;
        String per = null;
        boolean ambiguous = false;

        // 구간을 먼저 확인한다 — 제외 구간의 오전·오후를 구간 안에서 고르기 위해서다.
        List<Part> ordered = new ArrayList<>(parts);
        ordered.sort(Comparator.comparingInt(p -> "WINDOW".equalsIgnoreCase(p.role()) ? 0 : 1));
        for (Part part : ordered) {
            if (part == null || part.role() == null) {
                continue;
            }
            String role = part.role().trim().toUpperCase(Locale.ROOT);
            Utterance said = locate(part.quote(), utterances);
            if (said == null) {
                issues.add(new StudyTimeCondition.Issue("NOT_FOUND", "사용자 말에서 이 구절을 찾지 못했어요", part.quote()));
                continue;
            }
            String quote = part.quote().strip();
            LocalDate date = parseDate(part.date());
            if (date == null && days == 1) {
                date = planStart;
            }
            switch (role) {
                case "WINDOW" -> {
                    StudyTimeCondition.Range range = window(part, quote, date, said, notes, issues);
                    if (range != null) {
                        windows.add(range);
                        basis.add(new StudyTimeCondition.Basis("WINDOW", said.messageId(), quote));
                    }
                }
                case "EXCLUSION" -> {
                    StudyTimeCondition.Range range = exclusion(part, quote, date, windows, said, notes, issues);
                    if (range != null) {
                        exclusions.add(range);
                        basis.add(new StudyTimeCondition.Basis("EXCLUSION", said.messageId(), quote));
                    }
                }
                case "AMOUNT" -> {
                    List<Integer> durations = SaidTime.durations(quote);
                    if (part.minutes() == null || durations.size() != 1 || !durations.get(0).equals(part.minutes())) {
                        issues.add(new StudyTimeCondition.Issue("VALUE_MISMATCH", "분량을 이 구절에서 읽지 못했어요", quote));
                        continue;
                    }
                    minutes = part.minutes();
                    basis.add(new StudyTimeCondition.Basis("AMOUNT", said.messageId(), quote));
                    if (days <= 1) {
                        per = "DAY"; // 하루짜리 계획은 하루 몫과 전체 몫이 같다 — 단위 말이 없어도 묻지 않는다
                    } else {
                        boolean day = SaidTime.PER_DAY.matcher(said.text()).find();
                        boolean plan = SaidTime.PER_PLAN.matcher(said.text()).find();
                        if (day == plan) {
                            ambiguous = true;
                            per = part.per() == null ? "DAY" : part.per().toUpperCase(Locale.ROOT);
                            issues.add(new StudyTimeCondition.Issue("AMBIGUOUS_UNIT",
                                    "여러 날 계획이라 " + minutes + "분이 하루 몫인지 전체 몫인지 확인이 필요해요", quote));
                        } else {
                            per = day ? "DAY" : "PLAN";
                        }
                    }
                }
                default -> issues.add(new StudyTimeCondition.Issue("UNREADABLE", "알 수 없는 조건 종류: " + role, quote));
            }
        }

        // 나중 발언의 시간 표현이 어느 부분에도 쓰이지 않았으면 알린다(조건을 버리지는 않는다).
        if (utterances != null && !basis.isEmpty()) {
            Long latestUsed = basis.stream().map(StudyTimeCondition.Basis::messageId).max(Long::compare).orElse(null);
            for (Utterance u : utterances) {
                if (u.messageId() != null && latestUsed != null && u.messageId() > latestUsed && SaidTime.mentionsTime(u.text())
                        && basis.stream().noneMatch(b -> b.messageId().equals(u.messageId()))) {
                    issues.add(new StudyTimeCondition.Issue("LATER_TIME_UNUSED",
                            "나중에 말한 시간 표현은 이번 조건에 반영하지 않았어요", u.text()));
                }
            }
        }

        String status = basis.isEmpty() ? StudyTimeCondition.VERIFY_FAILED
                : ambiguous ? StudyTimeCondition.AMBIGUOUS : StudyTimeCondition.ADOPTED;
        String cleanIntent = intent == null ? null : switch (intent.trim().toUpperCase(Locale.ROOT)) {
            case "FILL", "AVAILABLE" -> intent.trim().toUpperCase(Locale.ROOT);
            default -> null;
        };
        return new StudyTimeCondition(status, windows, exclusions, minutes, minutes == null ? null : per, cleanIntent, basis,
                notes, issues);
    }

    private static StudyTimeCondition.Range window(Part part, String quote, LocalDate date, Utterance said,
                                                   List<String> notes, List<StudyTimeCondition.Issue> issues) {
        List<SaidTime.Span> spans = SaidTime.spans(quote);
        if (spans == null || spans.size() != 1) {
            issues.add(new StudyTimeCondition.Issue("UNREADABLE", spans == null || spans.isEmpty()
                    ? "이 구절에서 시간대를 읽지 못했어요" : "이 구절에 시간대가 여럿이에요", quote));
            return null;
        }
        SaidTime.Span span = spans.get(0);
        LocalTime modelFrom = clock(part.from());
        LocalTime modelUntil = clock(part.until());
        if ((span.from() == null) != (modelFrom == null) || (span.until() == null) != (modelUntil == null)
                || (modelFrom != null && !span.from().contains(modelFrom))
                || (modelUntil != null && !span.until().contains(modelUntil))) {
            issues.add(new StudyTimeCondition.Issue("VALUE_MISMATCH", "정리한 시각이 이 구절에서 읽히지 않아요", quote));
            return null;
        }
        // 후보 쌍 중 그럴듯한 것: 말한 날 이미 끝난 구간이 아니고, 오전·오후를 말하지 않은 시작이 새벽이 아닌 것.
        List<LocalTime[]> pairs = pairs(span);
        LocalTime[] chosen = new LocalTime[]{modelFrom, modelUntil};
        List<LocalTime[]> plausible = pairs.stream().filter(p -> plausible(p, date, said, span)).toList();
        boolean modelPlausible = plausible.stream().anyMatch(p -> same(p, chosen));
        if (!modelPlausible && plausible.size() == 1) {
            LocalTime[] fixed = plausible.get(0);
            notes.add("오전·오후를 말하지 않아 " + text(fixed) + "로 봤어요");
            chosen[0] = fixed[0];
            chosen[1] = fixed[1];
        } else if (plausible.size() > 1 && pairs.size() > 1) {
            notes.add("오전·오후를 말하지 않아 " + text(chosen) + "로 봤어요");
        }
        LocalTime start = chosen[0];
        LocalTime end = chosen[1];
        boolean endsNextDay = endsNextDay(start, end, date, said);
        LocalTime storedEnd = end;
        if (end != null && end.equals(LocalTime.MAX)) {
            storedEnd = LocalTime.MIDNIGHT;
            endsNextDay = true;
        }
        if (date != null && date.equals(said.saidAt().toLocalDate()) && start != null
                && date.atTime(start).isBefore(said.saidAt())) {
            notes.add(start + " 시작이 이미 지나 지금부터 배치해요");
        }
        if (endsNextDay && storedEnd != null && !storedEnd.equals(LocalTime.MIDNIGHT)) {
            notes.add("끝(" + storedEnd + ")은 다음 날로 봤어요");
        }
        return new StudyTimeCondition.Range(date, start, storedEnd, endsNextDay, null);
    }

    private static StudyTimeCondition.Range exclusion(Part part, String quote, LocalDate date,
                                                      List<StudyTimeCondition.Range> windows, Utterance said,
                                                      List<String> notes, List<StudyTimeCondition.Issue> issues) {
        List<SaidTime.Clock> clocks = SaidTime.clocks(quote);
        LocalTime modelFrom = clock(part.from());
        if (clocks.isEmpty() || modelFrom == null || clocks.stream().noneMatch(c -> c.candidates().contains(modelFrom))) {
            issues.add(new StudyTimeCondition.Issue("VALUE_MISMATCH", "뺄 시간을 이 구절에서 읽지 못했어요", quote));
            return null;
        }
        LocalTime from = modelFrom;
        SaidTime.Clock first = clocks.stream().filter(c -> c.candidates().contains(modelFrom)).findFirst().orElseThrow();
        if (!first.meridiemSaid() && first.candidates().size() > 1) {
            // 공부 구간 안에 드는 후보를 고른다("2시부터 8시까지 … 6시엔 저녁"의 6시는 18시).
            LocalTime inside = first.candidates().stream().filter(t -> windows.stream().anyMatch(w -> within(w, t)))
                    .min(LocalTime::compareTo).orElse(null);
            if (inside != null && !inside.equals(from)) {
                from = inside;
            }
            if (inside != null) {
                notes.add("오전·오후를 말하지 않은 " + quote + "의 시각을 공부 시간 안의 " + from + "로 봤어요");
            }
        }
        LocalTime until = clock(part.until());
        List<SaidTime.Span> spans = SaidTime.spans(quote);
        boolean untilSaid = spans != null && spans.size() == 1 && spans.get(0).until() != null;
        if (untilSaid) {
            if (until == null || !spans.get(0).until().contains(until)) {
                issues.add(new StudyTimeCondition.Issue("VALUE_MISMATCH", "뺄 시간의 끝이 이 구절에서 읽히지 않아요", quote));
                return null;
            }
        } else {
            until = from.plusMinutes(DEFAULT_EXCLUSION_MINUTES);
            notes.add((part.label() == null ? "제외 구간" : part.label()) + "은 끝을 말하지 않아 " + DEFAULT_EXCLUSION_MINUTES
                    + "분으로 봤어요");
        }
        return new StudyTimeCondition.Range(date, from, until, !until.isAfter(from), part.label());
    }

    private static boolean within(StudyTimeCondition.Range w, LocalTime t) {
        LocalTime start = w.start() == null ? DEFAULT_START : w.start();
        if (w.endsNextDay()) {
            return !t.isBefore(start) || t.isBefore(w.end());
        }
        return !t.isBefore(start) && (w.end() == null || t.isBefore(w.end()));
    }

    private static List<LocalTime[]> pairs(SaidTime.Span span) {
        List<LocalTime[]> out = new ArrayList<>();
        Set<LocalTime> froms = span.from();
        Set<LocalTime> untils = span.until();
        if (froms == null) {
            untils.forEach(u -> out.add(new LocalTime[]{null, u}));
        } else if (untils == null) {
            froms.forEach(f -> out.add(new LocalTime[]{f, null}));
        } else {
            // endAfter가 시작마다 끝 하나를 골랐다 — 시작과 같은 반(오전·오후)의 끝이 짝이다
            for (LocalTime f : froms) {
                untils.stream().filter(u -> u.isAfter(f)).min(LocalTime::compareTo)
                        .or(() -> untils.stream().min(LocalTime::compareTo))
                        .ifPresent(u -> out.add(new LocalTime[]{f, u}));
            }
        }
        return out;
    }

    private static boolean plausible(LocalTime[] pair, LocalDate date, Utterance said, SaidTime.Span span) {
        LocalTime start = pair[0];
        LocalTime end = pair[1];
        boolean startAmbiguous = span.from() != null && span.from().size() > 1;
        if (start != null && startAmbiguous && start.isBefore(PLAUSIBLE_FROM)) {
            return false;
        }
        if (date != null && date.equals(said.saidAt().toLocalDate()) && end != null) {
            LocalDateTime endAt = (endsNextDay(start, end, date, said) ? date.plusDays(1) : date)
                    .atTime(end.equals(LocalTime.MAX) ? LocalTime.MIDNIGHT : end);
            if (end.equals(LocalTime.MAX)) {
                endAt = date.plusDays(1).atStartOfDay();
            }
            return endAt.isAfter(said.saidAt());
        }
        return true;
    }

    /** 끝이 시작(시작이 없으면 말한 시각 또는 기본 시작)보다 이르거나 같으면 다음 날이다. */
    private static boolean endsNextDay(LocalTime start, LocalTime end, LocalDate date, Utterance said) {
        if (end == null) {
            return false;
        }
        if (end.equals(LocalTime.MAX)) {
            return true;
        }
        LocalTime base = start != null ? start
                : (date != null && date.equals(said.saidAt().toLocalDate()) ? said.saidAt().toLocalTime() : DEFAULT_START);
        return !end.isAfter(base);
    }

    private static boolean same(LocalTime[] a, LocalTime[] b) {
        return java.util.Objects.equals(a[0], b[0]) && java.util.Objects.equals(a[1], b[1]);
    }

    private static String text(LocalTime[] pair) {
        return (pair[0] == null ? "" : pair[0].toString()) + "~" + (pair[1] == null ? ""
                : pair[1].equals(LocalTime.MAX) ? "24:00" : pair[1].toString());
    }

    /** 구절을 가장 나중 발언에서 찾는다(공백 무시). */
    static Utterance locate(String quote, List<Utterance> utterances) {
        if (quote == null || quote.isBlank() || utterances == null) {
            return null;
        }
        String q = quote.replaceAll("\\s+", "");
        if (q.length() < 2) {
            return null;
        }
        for (int i = utterances.size() - 1; i >= 0; i--) {
            Utterance u = utterances.get(i);
            if (u.text() != null && u.text().replaceAll("\\s+", "").contains(q)) {
                return u;
            }
        }
        return null;
    }

    /** HH:mm, 24:00은 {@link LocalTime#MAX}. 형식이 틀리면 null. */
    static LocalTime clock(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim();
        if ("24:00".equals(v)) {
            return LocalTime.MAX;
        }
        try {
            return LocalTime.parse(v);
        } catch (Exception e) {
            return null;
        }
    }

    private static LocalDate parseDate(String raw) {
        try {
            return raw == null || raw.isBlank() ? null : LocalDate.parse(raw.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
