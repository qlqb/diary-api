package com.jungwoo.project.memo.ai.brief;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 사용자 원문 문장에서 읽히는 시간 값(지속 시간·시작·끝·적용 단위). AI가 정리한 "쓸 수 있는 시간" 합의의 값이 원문과 같은지
 * 대조하는 데만 쓴다 — 원문을 해석해 새 값을 만들지 않는다. <b>읽지 못하거나 애매하면 일치가 아니다</b>(그 항목은 시간 제약으로
 * 쓰이지 않고, 원문과 함께 계획 입력에만 실린다).
 */
final class SaidTime {

    private SaidTime() {
    }

    private static final Map<String, Integer> KOREAN = Map.ofEntries(
            Map.entry("한", 1), Map.entry("두", 2), Map.entry("세", 3), Map.entry("네", 4), Map.entry("다섯", 5),
            Map.entry("여섯", 6), Map.entry("일곱", 7), Map.entry("여덟", 8), Map.entry("아홉", 9), Map.entry("열", 10),
            Map.entry("열한", 11), Map.entry("열두", 12));
    /** 수사를 앞에서 잇는 글자(합성 수사: 열두, 스물두, 서른…). 이 뒤의 수사는 다른 수의 안쪽이다. */
    static final String NUMERAL_JOINERS = "열물른흔쉰순든백천만";
    /**
     * 수: 숫자 또는 수사 한 덩어리. 숫자는 앞이 숫자·소수점·분수·범위 기호면 다른 수의 안쪽("15"의 "5", "1/2"의 "2"),
     * 수사는 앞이 합성 수사를 잇는 글자면 안쪽("열세"의 "세", "스물두"의 "두"). 그 밖의 한글 뒤는 붙여 쓴 말이라 읽는다("부터11시").
     */
    private static final String NUM = "(?:(?<![\\d./~-])(\\d{1,2}(?:\\.5)?)(?![\\d/])"
            + "|(?<![" + NUMERAL_JOINERS + "])(열두|열한|한|두|세|네|다섯|여섯|일곱|여덟|아홉|열))";

    /** 지속 시간: "2시간", "1.5시간", "한 시간 30분", "두 시간 반", 또는 시각에 붙지 않은 "90분". 왼쪽부터 한 덩어리씩 읽는다. */
    private static final Pattern DURATION = Pattern.compile(
            NUM + "\\s*시간(?:\\s*(\\d{1,2})\\s*분)?(\\s*반)?"
                    + "|(?<![\\d.:시/]\\s?)(?<![\\d./])(\\d{1,3})\\s*분");

    /** 시각: (오전·오후·…)? 시 (분|반)? 또는 HH:mm 또는 자정. 뒤에 붙은 말(부터·까지·~)로 역할을 정한다. */
    private static final Pattern CLOCK = Pattern.compile(
            "(오전|아침|새벽|오후|저녁|밤)?\\s*(?:" + NUM + "\\s*시(?!간)(?:\\s*(\\d{1,2})\\s*분|\\s*(반))?"
                    + "|(?<![\\d./])(\\d{1,2}):(\\d{2})|(자정))\\s*(부터|에서|까지|~|-)?");

    private static final Pattern PER_DAY = Pattern.compile("하루|매일|날마다|오늘|평일|주말|저녁마다|밤마다");
    private static final Pattern PER_PLAN = Pattern.compile("총|전체|합쳐|통틀어|모두 합|이번 주|이번 계획|기간 동안");

    /** 원문에서 읽힌 지속 시간(분), 나온 순서대로. */
    static List<Integer> durations(String sentence) {
        List<Integer> out = new ArrayList<>();
        Matcher m = DURATION.matcher(sentence);
        while (m.find()) {
            String n = m.group(1) != null ? m.group(1) : m.group(2);
            if (n != null) {
                double hours = KOREAN.containsKey(n) ? KOREAN.get(n) : Double.parseDouble(n);
                out.add((int) Math.round(hours * 60) + (m.group(3) == null ? 0 : Integer.parseInt(m.group(3)))
                        + (m.group(4) == null ? 0 : 30));
            } else {
                out.add(Integer.parseInt(m.group(5)));
            }
        }
        return out;
    }

    /**
     * 원문의 시각 하나: 가능한 시각(오전·오후를 말하지 않았으면 둘 다), 뒤에 붙은 말(부터·까지·~ 또는 null), 원문 위치.
     * 24:00(자정)은 LocalTime.MAX로 나타낸다.
     */
    record Clock(Set<LocalTime> candidates, String tail, int start, int end, boolean meridiemSaid) {
        boolean opensRange() {
            return "부터".equals(tail) || "에서".equals(tail) || "~".equals(tail) || "-".equals(tail);
        }
    }

    static List<Clock> clocks(String sentence) {
        List<Clock> out = new ArrayList<>();
        Matcher m = CLOCK.matcher(sentence);
        while (m.find()) {
            String meridiem = m.group(1);
            Set<LocalTime> cands = new HashSet<>();
            if (m.group(8) != null) {
                cands.add(LocalTime.MAX); // 자정
            } else {
                boolean colon = m.group(6) != null;
                String n = colon ? m.group(6) : m.group(2) != null ? m.group(2) : m.group(3);
                if (n.contains(".")) {
                    continue; // "1.5시"는 시각이 아니다
                }
                int h = KOREAN.containsKey(n) ? KOREAN.get(n) : Integer.parseInt(n);
                int min = colon ? Integer.parseInt(m.group(7))
                        : m.group(4) != null ? Integer.parseInt(m.group(4)) : m.group(5) != null ? 30 : 0;
                if (h > 24 || min > 59) {
                    continue;
                }
                // "20:30"처럼 접두어 없는 HH:mm은 24시간 표기로 본다. 접두어가 있으면 HH:mm에도 오전·오후를 적용한다.
                List<Integer> hours = colon && meridiem == null ? List.of(h) : hours(h, meridiem);
                for (int hour : hours) {
                    if (hour == 24) {
                        if (min == 0) {
                            cands.add(LocalTime.MAX);
                        }
                    } else if (hour >= 0 && hour < 24) {
                        cands.add(LocalTime.of(hour, min));
                    }
                }
            }
            if (!cands.isEmpty()) {
                out.add(new Clock(cands, m.group(9), m.start(), m.end(), meridiem != null || m.group(8) != null));
            }
        }
        return out;
    }

    private static List<Integer> hours(int h, String meridiem) {
        if (meridiem == null) {
            return h >= 13 ? List.of(h) : h == 12 ? List.of(12, 24) : List.of(h, h + 12);
        }
        boolean pm = meridiem.equals("오후") || meridiem.equals("저녁") || meridiem.equals("밤");
        if (h >= 13) {
            return pm ? List.of(h) : List.of();
        }
        if (h == 12) {
            return List.of(pm ? (meridiem.equals("밤") ? 24 : 12) : 0);
        }
        return List.of(pm ? h + 12 : h);
    }

    /** 원문의 시간 구간: 시작(없으면 null)과 끝(없으면 null). "8시부터 11시까지", "8시~11시", "11시까지", "8시부터". */
    record Span(Set<LocalTime> from, Set<LocalTime> until) {
    }

    /**
     * 시각들을 구간으로 묶는다. 범위를 여는 시각 바로 뒤(사이에 공백만)의 시각이 그 끝이다. 묶이지 않은 "까지"는 끝만 있는 구간.
     * 범위를 열었는데 뒤에 "까지"가 있으면서 끝을 읽지 못했으면 애매하다 — null(일치 아님).
     */
    static List<Span> spans(String sentence) {
        List<Clock> clocks = clocks(sentence);
        List<Span> out = new ArrayList<>();
        for (int i = 0; i < clocks.size(); i++) {
            Clock c = clocks.get(i);
            if (c.opensRange()) {
                Clock next = i + 1 < clocks.size() ? clocks.get(i + 1) : null;
                boolean adjacent = next != null && sentence.substring(c.end(), next.start()).isBlank()
                        && (next.tail() == null || "까지".equals(next.tail()));
                if (adjacent) {
                    out.add(new Span(c.candidates(), endAfter(c, next)));
                    i++;
                } else if (sentence.indexOf("까지", c.end()) >= 0) {
                    return null; // 끝을 말했는데 읽지 못했다 — 시작만 있는 구간으로 보지 않는다
                } else {
                    out.add(new Span(c.candidates(), null));
                }
            } else if ("까지".equals(c.tail())) {
                out.add(new Span(null, c.candidates()));
            }
        }
        return out;
    }

    /**
     * 구간의 끝 후보. 끝에 오전·오후를 말하지 않았으면 시작 뒤의 가장 이른 시각 하나로 정한다("오전 8시부터 11시까지"의 끝은
     * 11:00이지 23:00이 아니다). 시작도 오전·오후가 없으면("8시부터 11시까지") 시작 후보마다 그 뒤의 가장 이른 끝을 모은다.
     */
    private static Set<LocalTime> endAfter(Clock from, Clock until) {
        if (until.meridiemSaid()) {
            return until.candidates();
        }
        Set<LocalTime> out = new HashSet<>();
        for (LocalTime start : from.candidates()) {
            until.candidates().stream().filter(t -> t.isAfter(start)).min(LocalTime::compareTo).ifPresent(out::add);
        }
        return out;
    }

    /**
     * 정리된 값이 원문에서 그대로 읽히는가.
     * <ul>
     *   <li>분: 원문의 지속 시간이 정확히 하나이고 그 값과 같으며, 원문의 단위 말이 한쪽(하루 또는 전체)뿐이고 정리된 단위와 같다.</li>
     *   <li>시작·끝: 원문의 구간이 정확히 하나이고, 정리된 시작·끝이 그 구간의 시작·끝과 각각 같다(말하지 않은 쪽은 비어 있어야 한다).</li>
     * </ul>
     * 지속 시간이나 구간이 여럿이면 어느 것인지 서버가 정할 수 없으므로 일치가 아니다.
     */
    static boolean matches(PlanBriefService.TimeBudget budget, String sentence) {
        if (budget == null || sentence == null) {
            return false;
        }
        if (budget.minutes() != null) {
            List<Integer> said = durations(sentence);
            if (said.size() != 1 || !said.get(0).equals(budget.minutes())) {
                return false;
            }
            boolean day = PER_DAY.matcher(sentence).find();
            boolean plan = PER_PLAN.matcher(sentence).find();
            if (day == plan || day != budget.perDay()) {
                return false; // 단위가 없거나 둘 다 있다 — 하루 몫인지 전체 몫인지 정할 수 없다
            }
        }
        if (budget.hasWindow()) {
            List<Span> spans = spans(sentence);
            if (spans == null || spans.size() != 1) {
                return false;
            }
            Span span = spans.get(0);
            LocalTime until = budget.until() == null ? null
                    : "24:00".equals(budget.until()) ? LocalTime.MAX : LocalTime.parse(budget.until());
            if (!sameEnd(span.from(), budget.from()) || !sameEnd(span.until(), until)) {
                return false;
            }
        }
        return true;
    }

    /** {@link #spans}가 구간으로 묶은 시각의 수. 같은 규칙으로 센다. */
    private static int spannedClocks(String sentence) {
        List<Clock> clocks = clocks(sentence);
        int used = 0;
        for (int i = 0; i < clocks.size(); i++) {
            Clock c = clocks.get(i);
            if (c.opensRange()) {
                Clock next = i + 1 < clocks.size() ? clocks.get(i + 1) : null;
                boolean adjacent = next != null && sentence.substring(c.end(), next.start()).isBlank()
                        && (next.tail() == null || "까지".equals(next.tail()));
                used += adjacent ? 2 : 1;
                if (adjacent) {
                    i++;
                }
            } else if ("까지".equals(c.tail())) {
                used++;
            }
        }
        return used;
    }

    /** 이 글에 시간 표현(지속 시간·시각)이 하나라도 있는가. */
    static boolean mentionsTime(String sentence) {
        return sentence != null && (!durations(sentence).isEmpty() || !clocks(sentence).isEmpty());
    }

    /**
     * {@link #matches}에 더해, 원문에서 읽히는 시간 조건을 빠뜨리지 않았고(지속 시간을 말했으면 분이, 시간대를 말했으면 시작·끝이
     * 있다), 오전·오후를 말하지 않아 후보가 둘인 시각은 서버 규칙으로 정한 하나와 같다.
     *
     * <p>서버 규칙: 시작은 기준 시각 이후의 가장 이른 후보, 끝은 (시작이 있으면 시작, 없으면 기준 시각) 뒤의 가장 이른 후보.
     * 기준 시각이 없으면 애매한 시각은 일치가 아니다.
     */
    static boolean matchesFully(PlanBriefService.TimeBudget budget, String sentence, LocalTime reference) {
        if (!matches(budget, sentence)) {
            return false;
        }
        if (!durations(sentence).isEmpty() && budget.minutes() == null) {
            return false; // "4시까지 한 시간만"에서 한 시간을 뺐다
        }
        List<Span> spans = spans(sentence);
        if (spans == null || (!spans.isEmpty() && !budget.hasWindow())) {
            return false; // 시간대를 말했는데 빠졌거나 읽지 못했다
        }
        if (spannedClocks(sentence) < clocks(sentence).size()) {
            return false; // "오후4시 전에 끝내야 해"처럼 읽은 시각을 시간대로 만들지 못했다 — 빠졌는지 서버가 알 수 없다
        }
        if (!budget.hasWindow()) {
            return true;
        }
        Span span = spans.get(0); // matches가 구간이 정확히 하나임을 확인했다
        LocalTime from = budget.from();
        if (span.from() != null && span.from().size() > 1) {
            LocalTime pick = reference == null ? null
                    : span.from().stream().filter(t -> !t.isBefore(reference)).min(LocalTime::compareTo).orElse(null);
            if (pick == null || !pick.equals(from)) {
                return false;
            }
        }
        if (span.until() != null && span.until().size() > 1) {
            LocalTime base = from != null ? from : reference;
            LocalTime until = "24:00".equals(budget.until()) ? LocalTime.MAX : LocalTime.parse(budget.until());
            LocalTime pick = base == null ? null
                    : span.until().stream().filter(t -> t.isAfter(base)).min(LocalTime::compareTo).orElse(null);
            if (pick == null || !pick.equals(until)) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameEnd(Set<LocalTime> said, LocalTime value) {
        return said == null ? value == null : value != null && said.contains(value);
    }

    /** 이 위치의 글자가 수의 안쪽인가(앞 글자가 숫자·소수점·분수·범위 기호이거나, 합성 수사를 잇는 글자). */
    static boolean insideNumber(String s, int at) {
        if (at <= 0) {
            return false;
        }
        char before = s.charAt(at - 1);
        char first = s.charAt(at);
        if (Character.isDigit(first)) {
            return Character.isDigit(before) || before == '.' || before == '/' || before == '~' || before == '-';
        }
        return NUMERAL_JOINERS.indexOf(before) >= 0 && "한두세네다섯여섯일곱여덟아홉열".indexOf(first) >= 0;
    }
}
