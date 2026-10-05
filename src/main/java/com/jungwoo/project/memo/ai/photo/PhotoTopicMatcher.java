package com.jungwoo.project.memo.ai.photo;

import com.jungwoo.project.memo.ai.StudyFactRules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 사진의 쪽 번호·단원 제목으로 목차 단원을 추정한다(서버 규칙, 모델 아님). 세 규칙을 따로 판정하고 결과를 모은다:
 * <ol>
 *   <li>번호 — heading에 단원 표기와 번호("Unit 3", "3과")가 있으면 같은 표기·번호의 단원("Unit 1"은 Unit 10이 아니다)</li>
 *   <li>제목 — 번호 없이도 heading이 단원 제목(표기·번호를 뗀 나머지)과 같거나 핵심어 2/3 이상을 담는다</li>
 *   <li>쪽 — 인쇄 쪽이 목차 항목 쪽 범위(그 항목 쪽 ~ 다음 항목 쪽-1)에 든다</li>
 * </ol>
 * 결과가 있는 규칙끼리 교집합을 내고, 하나로 모이면 추정 연결, 비거나 여럿이면 연결하지 않는다(화면이 한 번 묻는다).
 * 후보는 호출부가 현재 교재 목차의 살아 있는 단원으로 좁혀 넘긴다(목차가 없으면 살아 있는 단원 전체).
 */
public final class PhotoTopicMatcher {

    /** 목차 마지막 항목의 쪽 범위를 열어 두는 길이. */
    static final int LAST_RANGE_PAGES = 30;

    private static final Pattern TOC_PAGE = Pattern.compile("교재 p\\.(\\d{1,4})");
    private static final Pattern MARKER = Pattern.compile(
            "(?i)^\\s*(?:(?:unit|lesson|chapter|part|section)\\s*\\d{1,3}|제?\\s*\\d{1,3}\\s*(?:과|단원|장)|\\d{1,3}[.)])\\s*[:.\\-–—]?\\s*");

    public record Candidate(Long topicId, String title, String locator, Integer tocSeq) {
    }

    private PhotoTopicMatcher() {
    }

    /** @return 하나로 모인 단원 id, 아니면 null */
    public static Long match(List<Candidate> topics, List<String> headings, Integer printedPage) {
        if (topics == null || topics.isEmpty()) {
            return null;
        }
        List<Set<Long>> rules = new ArrayList<>();
        Set<Long> byNumber = byNumber(topics, headings);
        Set<Long> byTitle = byTitle(topics, headings);
        Set<Long> byPage = byPage(topics, printedPage);
        for (Set<Long> r : List.of(byNumber, byTitle, byPage)) {
            if (!r.isEmpty()) {
                rules.add(r);
            }
        }
        if (rules.isEmpty()) {
            return null;
        }
        Set<Long> out = new LinkedHashSet<>(rules.get(0));
        for (Set<Long> r : rules.subList(1, rules.size())) {
            out.retainAll(r);
        }
        return out.size() == 1 ? out.iterator().next() : null;
    }

    static Set<Long> byNumber(List<Candidate> topics, List<String> headings) {
        Set<Integer> said = new LinkedHashSet<>();
        for (String h : headings == null ? List.<String>of() : headings) {
            said.addAll(StudyFactRules.markedUnitNumbers(h));
        }
        Set<Long> out = new LinkedHashSet<>();
        if (said.isEmpty()) {
            return out;
        }
        for (Candidate t : topics) {
            Set<Integer> own = StudyFactRules.markedUnitNumbers(t.title());
            Integer number = own.isEmpty() ? StudyFactRules.unitNumberOf(t.title()) : own.iterator().next();
            if (number != null && said.contains(number)) {
                out.add(t.topicId());
            }
        }
        return out;
    }

    static Set<Long> byTitle(List<Candidate> topics, List<String> headings) {
        Set<Long> out = new LinkedHashSet<>();
        for (String h : headings == null ? List.<String>of() : headings) {
            String heading = norm(strip(h));
            if (heading.length() < 3) {
                continue;
            }
            for (Candidate t : topics) {
                String title = norm(strip(t.title()));
                if (title.length() < 3) {
                    continue;
                }
                if (heading.equals(title) || covers(heading, title)) {
                    out.add(t.topicId());
                }
            }
        }
        return out;
    }

    /** 단원 제목의 핵심어(3자 이상)가 2개 이상이고 그 2/3 이상이 heading에 있다. */
    private static boolean covers(String heading, String title) {
        List<String> words = new ArrayList<>();
        for (String w : title.split(" ")) {
            if (w.length() >= 3) {
                words.add(w);
            }
        }
        if (words.size() < 2) {
            return false;
        }
        Set<String> headingWords = new java.util.HashSet<>(List.of(heading.split(" "))); // 같은 낱말이 두 번 나와도 된다
        long hit = words.stream().filter(headingWords::contains).count();
        return hit * 3 >= words.size() * 2L;
    }

    static Set<Long> byPage(List<Candidate> topics, Integer page) {
        Set<Long> out = new LinkedHashSet<>();
        if (page == null) {
            return out;
        }
        record Start(Long topicId, int page, int order) {
        }
        List<Start> starts = new ArrayList<>();
        for (int i = 0; i < topics.size(); i++) {
            Candidate t = topics.get(i);
            Integer p = tocPage(t.locator());
            if (p != null) {
                starts.add(new Start(t.topicId(), p, t.tocSeq() == null ? i : t.tocSeq()));
            }
        }
        if (starts.isEmpty()) {
            return out;
        }
        starts.sort(Comparator.comparingInt(Start::page).thenComparingInt(Start::order));
        int best = Integer.MIN_VALUE;
        for (Start s : starts) {
            if (s.page() <= page) {
                best = s.page();
            }
        }
        if (best == Integer.MIN_VALUE) {
            return out;
        }
        int last = starts.get(starts.size() - 1).page();
        if (best == last && page > last + LAST_RANGE_PAGES) {
            return out;
        }
        for (Start s : starts) {
            if (s.page() == best) {
                out.add(s.topicId());
            }
        }
        return out;
    }

    static Integer tocPage(String locator) {
        if (locator == null) {
            return null;
        }
        Matcher m = TOC_PAGE.matcher(locator);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    private static String strip(String s) {
        return s == null ? "" : MARKER.matcher(s).replaceFirst("");
    }

    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }
}
