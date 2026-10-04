package com.jungwoo.project.memo.course.textbook.web;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "가장 비슷한 책"을 고르지 않는다. 맞지 않는 근거가 하나라도 있으면 그 책이 아니고, 확인할 정보가 없으면 보류다.
 */
class BookMatcherTest {

    private static BookPageParser.Parsed page(String title, List<String> authors, List<String> notes, String publisher,
                                              String isbn, String edition) {
        return new BookPageParser.Parsed("yes24", title, authors, notes, publisher, isbn, "2026-01-30", edition, null,
                false);
    }

    private static final BookPageParser.Parsed ARTS_1 = page("New English Conversation Arts 1",
            List.of("마이클 A. 푸틀랙", "이현호", "Stephen Poirier"), List.of("Michael A. Putlack"), "형설출판사",
            "9788947288132", null);

    @Test
    void 강의계획서_단서와_음역된_저자_표기도_맞춘다() {
        BookMatcher.Result r = BookMatcher.match(new BookMatcher.Clue("NEW English Conversation Arts 1",
                "Michael Putlack, 이현호", "형설출판사", null, null), ARTS_1);

        assertThat(r.verdict()).isEqualTo(BookMatcher.Verdict.MATCH);
        assertThat(r.reasons()).contains("제목 일치", "저자 일치", "출판사 일치");
    }

    @Test
    void 다른_권은_제목이_거의_같아도_다른_책이다() {
        BookPageParser.Parsed arts2 = page("New English Conversation Arts 2", ARTS_1.authors(), ARTS_1.authorNotes(),
                "형설출판사", "9788947288149", null);

        BookMatcher.Result r = BookMatcher.match(new BookMatcher.Clue("NEW English Conversation Arts 1",
                "Michael Putlack", "형설출판사", null, null), arts2);

        assertThat(r.verdict()).isEqualTo(BookMatcher.Verdict.MISMATCH);
        assertThat(r.reasons()).anyMatch(s -> s.startsWith("권·번호가 달라요"));
    }

    @Test
    void 단서의_ISBN과_다르면_같은_제목이어도_다른_판이다() {
        BookMatcher.Result r = BookMatcher.match(new BookMatcher.Clue("NEW English Conversation Arts 1", null, null,
                "9788947281980", null), ARTS_1);

        assertThat(r.verdict()).isEqualTo(BookMatcher.Verdict.MISMATCH);
    }

    @Test
    void 저자나_출판사가_다르면_다른_책이다() {
        BookMatcher.Result otherPublisher = BookMatcher.match(new BookMatcher.Clue("NEW English Conversation Arts 1",
                null, "한빛아카데미", null, null), ARTS_1);
        BookMatcher.Result otherAuthor = BookMatcher.match(new BookMatcher.Clue("NEW English Conversation Arts 1",
                "김철수", null, null, null), ARTS_1);

        assertThat(otherPublisher.verdict()).isEqualTo(BookMatcher.Verdict.MISMATCH);
        assertThat(otherAuthor.verdict()).isEqualTo(BookMatcher.Verdict.MISMATCH);
    }

    @Test
    void 부제_차이는_다른_칸이_받쳐_줄_때만_맞다() {
        BookPageParser.Parsed withSubtitle = page("C로 배우는 쉬운 자료구조 - 개념부터 구현까지", List.of("이지영"), List.of(),
                "한빛아카데미", null, null);

        BookMatcher.Result titleOnly = BookMatcher.match(new BookMatcher.Clue("C로 배우는 쉬운 자료구조", null, null, null,
                null), withSubtitle);
        BookMatcher.Result withAuthor = BookMatcher.match(new BookMatcher.Clue("C로 배우는 쉬운 자료구조", "이지영", null,
                null, null), withSubtitle);

        assertThat(titleOnly.verdict()).isEqualTo(BookMatcher.Verdict.UNVERIFIED);
        assertThat(withAuthor.verdict()).isEqualTo(BookMatcher.Verdict.MATCH);
    }

    @Test
    void 둘_다_판을_적었는데_다르면_다른_판이다() {
        BookPageParser.Parsed third = page("C로 배우는 쉬운 자료구조(개정 3판)", List.of("이지영"), List.of(), "한빛아카데미",
                null, "개정 3판");

        BookMatcher.Result r = BookMatcher.match(new BookMatcher.Clue("C로 배우는 쉬운 자료구조", "이지영", null, null,
                "개정 4판"), third);

        assertThat(r.verdict()).isEqualTo(BookMatcher.Verdict.MISMATCH);
        assertThat(r.reasons()).anyMatch(s -> s.startsWith("판이 달라요"));
    }

    @Test
    void 단서에_ISBN이_있으면_ISBN을_확인한_페이지만_같은_책이다() {
        BookPageParser.Parsed noIsbn = page("New English Conversation Arts 1", ARTS_1.authors(), ARTS_1.authorNotes(),
                "형설출판사", null, null);

        BookMatcher.Result r = BookMatcher.match(new BookMatcher.Clue("NEW English Conversation Arts 1", "Michael Putlack",
                "형설출판사", "9788947288132", null), noIsbn);

        assertThat(r.verdict()).isEqualTo(BookMatcher.Verdict.UNVERIFIED);
    }

    @Test
    void 페이지에_식별_정보가_없으면_보류다() {
        BookPageParser.Parsed empty = new BookPageParser.Parsed("other", null, List.of(), List.of(), null, null, null,
                null, null, false);

        assertThat(BookMatcher.match(new BookMatcher.Clue("아무 책", null, null, null, null), empty).verdict())
                .isEqualTo(BookMatcher.Verdict.UNVERIFIED);
    }

    @Test
    void 이름_열쇠는_로마자_성과_한글_전체_이름이다() {
        assertThat(BookMatcher.nameKeys("Michael Putlack, 이현호")).containsExactly("putlack", "이현호");
        assertThat(BookMatcher.publisherKey("(주)한빛아카데미")).isEqualTo(BookMatcher.publisherKey("한빛아카데미"));
        assertThat(BookMatcher.titleKey("C로 배우는 쉬운 자료구조(개정 4판)")).isEqualTo(BookMatcher.titleKey("C로 배우는 쉬운 자료구조"));
    }
}
