package com.jungwoo.project.memo.course.textbook.web;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 찾은 페이지가 단서의 그 책인가. 가장 비슷한 것을 고르는 것이 아니라 <b>맞지 않는 근거가 있는지</b>를 본다.
 *
 * <ul>
 *   <li>제목: 띄어쓰기·대소문자·문장부호·판 표기를 빼고 같아야 한다. 권·숫자가 다르면 다른 책이다("1" ≠ "2").
 *       한쪽이 다른 쪽을 품는 경우(부제 차이)는 저자·출판사·ISBN이 받쳐 줄 때만 맞다고 본다.</li>
 *   <li>ISBN: 단서에 있으면 정확히 같아야 한다. 페이지에 없으면 확인하지 못한 것이다.</li>
 *   <li>저자: 단서의 이름 하나라도 페이지의 저자(또는 저자 소개의 다른 표기)와 맞아야 한다. 번역·음역 차이를 위해
 *       로마자는 성(마지막 낱말), 한글은 이름 전체로 맞춘다. 페이지에 저자 정보가 없으면 판단하지 않는다.</li>
 *   <li>출판사: 정규화 뒤 같거나 한쪽이 다른 쪽을 품으면 맞다. 다르면 다른 책이다.</li>
 *   <li>판: 둘 다 적혀 있고 다르면 다른 판이다. 한쪽만 있으면 판단하지 않는다(발행일로 판을 짐작하지 않는다).</li>
 * </ul>
 */
public final class BookMatcher {

    private BookMatcher() {
    }

    public enum Verdict {
        /** 맞지 않는 근거가 없고, 제목이 같으며(또는 부제 차이를 다른 칸이 받쳐 주며) 확인한 칸이 있다. */
        MATCH,
        /** 맞지 않는 근거가 있다. */
        MISMATCH,
        /** 페이지에 식별 정보가 모자라 판단하지 못했다. */
        UNVERIFIED
    }

    public record Clue(String title, String author, String publisher, String isbn, String edition) {
    }

    public record Result(Verdict verdict, List<String> reasons) {
    }

    public static Result match(Clue clue, BookPageParser.Parsed page) {
        List<String> reasons = new ArrayList<>();
        if (page == null || !page.hasIdentity()) {
            return new Result(Verdict.UNVERIFIED, List.of("페이지에서 책 정보를 읽지 못했어요"));
        }
        boolean mismatch = false;
        int corroborated = 0;

        // ISBN
        String clueIsbn = BookPageParser.toIsbn13(BookPageParser.isbnOrNull(clue.isbn()));
        if (clueIsbn != null) {
            if (page.isbn13() == null) {
                reasons.add("페이지에 ISBN이 없어 확인하지 못했어요");
            } else if (clueIsbn.equals(page.isbn13())) {
                corroborated++;
                reasons.add("ISBN 일치");
            } else {
                mismatch = true;
                reasons.add("ISBN이 달라요(" + page.isbn13() + ")");
            }
        }

        // 제목
        TitleMatch title = titleMatch(clue.title(), page.title());
        switch (title) {
            case SAME -> reasons.add("제목 일치");
            case CONTAINS -> reasons.add("제목이 부제만큼 달라요");
            case DIFFERENT_VOLUME -> {
                mismatch = true;
                reasons.add("권·번호가 달라요(「" + page.title() + "」)");
            }
            case DIFFERENT -> {
                if (clue.title() != null && page.title() != null) {
                    mismatch = true;
                    reasons.add("제목이 달라요(「" + page.title() + "」)");
                }
            }
            case UNKNOWN -> reasons.add("제목을 확인하지 못했어요");
        }

        // 저자
        if (clue.author() != null && !clue.author().isBlank()) {
            Set<String> pageNames = nameKeys(String.join(", ", page.authors()));
            for (String note : page.authorNotes()) {
                pageNames.addAll(nameKeys(note));
            }
            if (pageNames.isEmpty()) {
                reasons.add("페이지에 저자 정보가 없어요");
            } else {
                Set<String> clueNames = nameKeys(clue.author());
                boolean any = clueNames.stream().anyMatch(pageNames::contains);
                if (any) {
                    corroborated++;
                    reasons.add("저자 일치");
                } else if (!clueNames.isEmpty()) {
                    mismatch = true;
                    reasons.add("저자가 달라요(" + String.join(", ", page.authors()) + ")");
                }
            }
        }

        // 출판사
        if (clue.publisher() != null && page.publisher() != null) {
            String a = publisherKey(clue.publisher());
            String b = publisherKey(page.publisher());
            if (!a.isEmpty() && !b.isEmpty()) {
                if (a.equals(b) || a.contains(b) || b.contains(a)) {
                    corroborated++;
                    reasons.add("출판사 일치");
                } else {
                    mismatch = true;
                    reasons.add("출판사가 달라요(" + page.publisher() + ")");
                }
            }
        }

        // 판
        if (clue.edition() != null && page.edition() != null) {
            if (!editionKey(clue.edition()).equals(editionKey(page.edition()))) {
                mismatch = true;
                reasons.add("판이 달라요(" + page.edition() + ")");
            }
        }

        if (mismatch) {
            return new Result(Verdict.MISMATCH, reasons);
        }
        boolean isbnMatched = clueIsbn != null && clueIsbn.equals(page.isbn13());
        if (clueIsbn != null && !isbnMatched) {
            // 단서에 ISBN이 있으면 그 ISBN을 확인한 페이지만 같은 책이다(같은 제목의 다른 판일 수 있다).
            return new Result(Verdict.UNVERIFIED, reasons);
        }
        if (title == TitleMatch.SAME || isbnMatched) {
            return new Result(Verdict.MATCH, reasons);
        }
        if (title == TitleMatch.CONTAINS && corroborated > 0) {
            return new Result(Verdict.MATCH, reasons);
        }
        return new Result(Verdict.UNVERIFIED, reasons);
    }

    enum TitleMatch { SAME, CONTAINS, DIFFERENT_VOLUME, DIFFERENT, UNKNOWN }

    private static final Pattern EDITION_WORDS = Pattern.compile(
            "(개정\\s*\\d{0,2}\\s*판|제\\s*\\d{1,2}\\s*판|\\d{1,2}\\s*판|초판|\\d{1,2}(st|nd|rd|th)\\s+edition|edition\\s*\\d{1,2}"
                    + "|revised\\s+edition|[(\\[]\\s*[)\\]])", Pattern.CASE_INSENSITIVE);

    static TitleMatch titleMatch(String clueTitle, String pageTitle) {
        if (clueTitle == null || pageTitle == null) {
            return TitleMatch.UNKNOWN;
        }
        String a = titleKey(clueTitle);
        String b = titleKey(pageTitle);
        if (a.isEmpty() || b.isEmpty()) {
            return TitleMatch.UNKNOWN;
        }
        boolean sameNumbers = numbers(clueTitle).equals(numbers(pageTitle));
        if (a.equals(b)) {
            return TitleMatch.SAME;
        }
        String letA = a.replaceAll("\\d", "");
        String letB = b.replaceAll("\\d", "");
        if (letA.equals(letB) && !sameNumbers) {
            return TitleMatch.DIFFERENT_VOLUME;
        }
        String shorter = a.length() <= b.length() ? a : b;
        String longer = a.length() <= b.length() ? b : a;
        if (shorter.length() >= 6 && longer.startsWith(shorter)) {
            return sameNumbers ? TitleMatch.CONTAINS : TitleMatch.DIFFERENT_VOLUME;
        }
        return TitleMatch.DIFFERENT;
    }

    /** 비교용 제목: NFKC, 소문자, 판 표기·괄호·문장부호·공백 제거. */
    public static String titleKey(String title) {
        if (title == null) {
            return "";
        }
        String t = Normalizer.normalize(title, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        t = EDITION_WORDS.matcher(t).replaceAll(" ");
        return t.replaceAll("[\\p{Punct}\\p{IsPunctuation}\\s’‘“”·]", "");
    }

    /** 권·숫자 표기(아라비아·로마 숫자 I~X). 판 표기 안의 숫자는 뺀다. */
    static List<String> numbers(String title) {
        String t = EDITION_WORDS.matcher(Normalizer.normalize(title, Normalizer.Form.NFKC)).replaceAll(" ");
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\\d+|\\b(?:I{1,3}|IV|V|VI{0,3}|IX|X)\\b").matcher(t);
        while (m.find()) {
            String g = m.group();
            out.add(g.chars().allMatch(Character::isDigit) ? String.valueOf(Integer.parseInt(g)) : roman(g));
        }
        return out;
    }

    private static String roman(String r) {
        return switch (r) {
            case "I" -> "1";
            case "II" -> "2";
            case "III" -> "3";
            case "IV" -> "4";
            case "V" -> "5";
            case "VI" -> "6";
            case "VII" -> "7";
            case "VIII" -> "8";
            case "IX" -> "9";
            case "X" -> "10";
            default -> r;
        };
    }

    /**
     * 이름 열쇠: 로마자 이름은 성(마지막 낱말, 3자 이상) 소문자, 한글 이름은 공백 없는 전체.
     * "Michael Putlack, 이현호" → {putlack, 이현호}. "마이클 A. 푸틀랙"처럼 음역만 있으면 한글 전체 이름이 된다.
     */
    static Set<String> nameKeys(String names) {
        Set<String> out = new LinkedHashSet<>();
        if (names == null) {
            return out;
        }
        for (String raw : names.split("\\s*[,;/·&]\\s*|\\s+(?:and|및|외|공저|지음|저|역|옮김)\\b\\s*")) {
            String name = Normalizer.normalize(raw, Normalizer.Form.NFKC).replaceAll("\\(.*?\\)", " ").strip();
            if (name.isEmpty()) {
                continue;
            }
            if (name.matches(".*[A-Za-z].*")) {
                String[] words = name.replaceAll("[^A-Za-z\\s\\-']", " ").strip().split("\\s+");
                String last = words[words.length - 1].toLowerCase(Locale.ROOT);
                if (last.length() >= 3) {
                    out.add(last);
                }
            } else {
                String hangul = name.replaceAll("\\s+", "");
                if (hangul.length() >= 2) {
                    out.add(hangul);
                }
            }
        }
        return out;
    }

    static String publisherKey(String publisher) {
        return Normalizer.normalize(publisher, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("\\(주\\)|㈜|주식회사|\\binc\\.?|\\bltd\\.?|\\bco\\.?", "")
                .replaceAll("출판사$|출판부$|출판$", "")
                .replaceAll("[\\p{Punct}\\s]", "");
    }

    static String editionKey(String edition) {
        String e = Normalizer.normalize(edition, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        Matcher n = Pattern.compile("(\\d{1,2})").matcher(e);
        if (e.contains("개정") && n.find()) {
            return "rev" + n.group(1);
        }
        if (e.contains("개정")) {
            return "rev";
        }
        if (n.find(0)) {
            return "ed" + n.group(1);
        }
        return e;
    }
}
