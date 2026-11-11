package com.jungwoo.project.memo.ai.evidence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 원문 단위(페이지·슬라이드·구간) 하나에서 검색어가 걸린 줄과 그 주변을 잘라 낸다.
 *
 * <p>줄 단위로 자른다 — 문자 수로 자르면 강의계획서 주차표의 "5주차 | 10/19~10/23 | 중간고사" 같은 행이 반으로 갈려
 * 어느 날짜가 어느 행의 것인지 사라진다. 걸린 줄이 표 안이면 그 표의 머리 행을 함께 붙인다(열의 뜻이 머리 행에 있다).
 * 단위가 상한보다 짧으면 통째로 싣는다.
 */
public final class PassageExtractor {

    /** 걸린 줄 앞뒤로 함께 싣는 줄 수. */
    static final int CONTEXT_LINES = 2;
    /** 한 줄이 이보다 길면(줄바꿈 없는 PDF 문단) 걸린 위치 주변만 싣는다. */
    static final int MAX_LINE_CHARS = 500;

    private PassageExtractor() {
    }

    /**
     * @param text     원문 단위 전체
     * @param full     잘리지 않고 통째로 실었는가
     * @param score    검색어 가중치 합(같은 검색어는 한 번만 센다)
     * @param matched  걸린 검색어
     */
    public record Passage(String text, boolean full, double score, List<String> matched) {
    }

    public static double score(String text, List<QueryTerms.Term> terms) {
        if (text == null) {
            return 0;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        String compact = lower.replaceAll("\\s+", "");
        double s = 0;
        for (QueryTerms.Term t : terms) {
            if (lower.contains(t.text()) || compact.contains(t.text())) {
                s += t.weight();
            }
        }
        return s;
    }

    public static Passage extract(String text, List<QueryTerms.Term> terms, int maxChars) {
        if (text == null || text.isBlank()) {
            return new Passage("", true, 0, List.of());
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').strip();
        List<String> matched = new ArrayList<>();
        String lowerAll = normalized.toLowerCase(Locale.ROOT);
        String compactAll = lowerAll.replaceAll("\\s+", "");
        double total = 0;
        for (QueryTerms.Term t : terms) {
            if (lowerAll.contains(t.text()) || compactAll.contains(t.text())) {
                matched.add(t.text());
                total += t.weight();
            }
        }
        if (normalized.length() <= maxChars) {
            return new Passage(normalized, true, total, matched);
        }

        String[] lines = normalized.split("\n", -1);
        double[] lineScore = new double[lines.length];
        for (int i = 0; i < lines.length; i++) {
            lineScore[i] = score(lines[i], terms);
        }
        boolean[] keep = new boolean[lines.length];
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (lineScore[i] > 0) {
                order.add(i);
            }
        }
        order.sort((a, b) -> Double.compare(lineScore[b], lineScore[a]) != 0
                ? Double.compare(lineScore[b], lineScore[a]) : Integer.compare(a, b));
        if (order.isEmpty()) {
            // 단위 전체로는 걸렸는데 한 줄에 몰려 있지 않다(검색어가 줄을 넘어감). 앞부분을 싣는다.
            return new Passage(cutLine(normalized, terms, maxChars), false, total, matched);
        }

        /*
         * 걸린 줄이 먼저다. 주변 줄을 다 붙이면 넘칠 때는 주변을 줄여(앞뒤 2줄 → 1줄 → 0줄), 그래도 넘치면 표 머리 행을 뺀다.
         * 예전에는 첫 창을 크기와 상관없이 받고 마지막에 앞에서부터 잘라, 머리 행·앞줄이 자리를 차지하고 정작 걸린 시험 행이
         * 잘릴 수 있었다.
         */
        int used = 0;
        for (int hit : order) {
            int header = tableHeaderOf(lines, hit);
            boolean placed = false;
            for (int context = CONTEXT_LINES; context >= 0 && !placed; context--) {
                for (boolean withHeader : header >= 0 ? new boolean[]{true, false} : new boolean[]{false}) {
                    int from = Math.max(0, hit - context);
                    int to = Math.min(lines.length - 1, hit + context);
                    int cost = 2; // 앞뒤 생략 표시
                    for (int i = from; i <= to; i++) {
                        if (!keep[i]) {
                            cost += Math.min(lines[i].length(), MAX_LINE_CHARS) + 2;
                        }
                    }
                    if (withHeader && !keep[header] && (header < from || header > to)) {
                        cost += Math.min(lines[header].length(), MAX_LINE_CHARS) + 2;
                    }
                    if (used + cost > maxChars) {
                        continue;
                    }
                    for (int i = from; i <= to; i++) {
                        keep[i] = true;
                    }
                    if (withHeader) {
                        keep[header] = true;
                    }
                    used += cost;
                    placed = true;
                    break;
                }
            }
            if (!placed && used == 0) {
                // 걸린 줄 하나도 상한보다 길다. 그 줄의 걸린 위치 주변만 싣는다.
                return new Passage(cutLine(lines[hit], terms, Math.max(1, maxChars - 2)), false, total, matched);
            }
        }

        StringBuilder sb = new StringBuilder();
        boolean gap = false;
        for (int i = 0; i < lines.length; i++) {
            if (!keep[i]) {
                gap = true;
                continue;
            }
            if (gap && sb.length() > 0) {
                sb.append("…\n");
            }
            gap = false;
            sb.append(cutLine(lines[i], terms, MAX_LINE_CHARS)).append('\n');
        }
        String out = sb.toString().strip();
        if (out.length() > maxChars) {
            out = out.substring(0, maxChars) + "…";
        }
        return new Passage(out, false, total, matched);
    }

    /** 걸린 줄이 "[표 시작]" 안이면 그 표의 첫 행 위치. 표 밖이면 -1. */
    static int tableHeaderOf(String[] lines, int index) {
        for (int i = index; i >= 0; i--) {
            String l = lines[i].strip();
            if (l.equals("[표 끝]") && i != index) {
                return -1;
            }
            if (l.equals("[표 시작]")) {
                return i + 1 < lines.length && i + 1 != index ? i + 1 : -1;
            }
        }
        return -1;
    }

    /** 긴 줄은 첫 검색어 위치 주변만 남긴다. */
    static String cutLine(String line, List<QueryTerms.Term> terms, int max) {
        if (line.length() <= max) {
            return line;
        }
        String lower = line.toLowerCase(Locale.ROOT);
        // 띄어쓰기를 지운 본문과 그 글자가 원래 줄의 몇 번째였는지. "강의시간"이 "강의 시간"에 걸린 위치를 되찾는다.
        StringBuilder compact = new StringBuilder();
        List<Integer> origin = new ArrayList<>();
        for (int i = 0; i < lower.length(); i++) {
            if (!Character.isWhitespace(lower.charAt(i))) {
                compact.append(lower.charAt(i));
                origin.add(i);
            }
        }
        int at = -1;
        for (QueryTerms.Term t : terms) {
            int i = lower.indexOf(t.text());
            if (i < 0) {
                int c = compact.indexOf(t.text());
                i = c < 0 ? -1 : origin.get(c);
            }
            if (i >= 0 && (at < 0 || i < at)) {
                at = i;
            }
        }
        int start = at < 0 ? 0 : Math.max(0, at - max / 3);
        int end = Math.min(line.length(), start + max);
        return (start > 0 ? "…" : "") + line.substring(start, end) + (end < line.length() ? "…" : "");
    }
}
