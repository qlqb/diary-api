package com.jungwoo.project.memo.course.textbook;

import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.course.textbook.web.BookMatcher;
import com.jungwoo.project.memo.course.textbook.web.BookPageParser;

/**
 * 어느 책인가를 한 줄로 — ISBN이 있으면 ISBN, 없으면 정규화한 제목(+판).
 *
 * <p>교재가 바뀌었는지(목차 연결을 풀어야 하는지), 목차에서 온 항목이 지금 교재의 것인지 비교할 때 쓴다.
 * 저자·출판사 표기만 고친 것은 같은 책이다.
 */
public final class BookKey {

    private BookKey() {
    }

    public static String of(String title, String isbn, String edition) {
        String isbn13 = BookPageParser.toIsbn13(isbnOrNull(isbn));
        if (isbn13 != null) {
            return "isbn:" + isbn13;
        }
        String t = BookMatcher.titleKey(title);
        if (t.isEmpty()) {
            return null;
        }
        String e = edition == null || edition.isBlank() ? "" : "|" + edition.replaceAll("\\s+", "");
        String key = "title:" + t + e;
        return key.length() > 400 ? key.substring(0, 400) : key;
    }

    public static String of(Course course) {
        return course == null ? null : of(course.getTextbookTitle(), course.getTextbookIsbn(), course.getTextbookEdition());
    }

    /**
     * 같은 책인가. 한쪽만 ISBN이 있으면 제목으로 비교한다(ISBN을 나중에 채운 경우를 같은 책으로 본다).
     */
    public static boolean sameBook(String titleA, String isbnA, String editionA, String titleB, String isbnB, String editionB) {
        String ia = BookPageParser.toIsbn13(isbnOrNull(isbnA));
        String ib = BookPageParser.toIsbn13(isbnOrNull(isbnB));
        if (ia != null && ib != null) {
            return ia.equals(ib);
        }
        String ta = BookMatcher.titleKey(titleA);
        String tb = BookMatcher.titleKey(titleB);
        if (ta.isEmpty() && tb.isEmpty()) {
            return true;
        }
        if (!ta.equals(tb)) {
            return false;
        }
        boolean hasA = editionA != null && !editionA.isBlank();
        boolean hasB = editionB != null && !editionB.isBlank();
        return !hasA || !hasB || editionA.replaceAll("\\s+", "").equalsIgnoreCase(editionB.replaceAll("\\s+", ""));
    }

    private static String isbnOrNull(String isbn) {
        if (isbn == null) {
            return null;
        }
        String digits = isbn.replaceAll("[\\s\\-]", "").toUpperCase(java.util.Locale.ROOT);
        return TextbookExtractor.isValidIsbn(digits) ? digits : null;
    }
}
