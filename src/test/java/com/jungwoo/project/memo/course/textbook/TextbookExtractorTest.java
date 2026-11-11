package com.jungwoo.project.memo.course.textbook;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 규칙 추출의 경계. 원문에 이름표가 붙은 값과 목차 모양의 줄만 읽고, 없는 것은 비워 둔다.
 */
class TextbookExtractorTest {

    private static final String COVER = "C로 배우는 쉬운 자료구조\n개정 4판\n이지영 지음";
    private static final String IMPRINT = """
            C로 배우는 쉬운 자료구조(개정 4판)
            개정 4판 1쇄 발행 2022년 1월 5일
            지은이 이지영
            펴낸곳 한빛아카데미(주)
            ISBN 979-11-5664-567-2 93000
            """;
    private static final String TOC_1 = """
            목차
            CHAPTER 01 자료구조와 알고리즘 ........ 13
            1.1 자료와 정보 ........ 14
            1.2 자료구조의 분류 ........ 17
            CHAPTER 02 배열 ........ 41
            2.1 배열의 개념 ........ 42
            2.2 희소 행렬 ........ 50
            """;
    private static final String TOC_2 = """
            제3장 연결 리스트 ........ 71
            3.1 단순 연결 리스트 ........ 72
            3.2 원형 연결 리스트 ........ 88
            3.3 이중 연결 리스트 ........ 95
            """;
    private static final String BODY = """
            1장에서 우리는 자료구조의 필요성을 살펴보았다. 이번 장에서는 배열을 다룬다.
            배열은 같은 형의 원소를 연속된 메모리에 저장한다. 판단 기준은 다음과 같다.
            """;

    @Test
    void 판권면의_이름표_값과_체크섬이_맞는_ISBN만_읽고_제목은_추측하지_않는다() {
        TextbookExtractor.Result result = TextbookExtractor.extract(List.of(
                new TextbookExtractor.Unit(1, COVER), new TextbookExtractor.Unit(2, IMPRINT),
                new TextbookExtractor.Unit(3, TOC_1), new TextbookExtractor.Unit(4, TOC_2),
                new TextbookExtractor.Unit(5, BODY)));

        assertThat(result.book().get("isbn").value()).isEqualTo("9791156645672");
        assertThat(result.book().get("isbn").unit()).isEqualTo(2);
        assertThat(result.book().get("author").value()).isEqualTo("이지영");
        assertThat(result.book().get("publisher").value()).isEqualTo("한빛아카데미(주)");
        assertThat(result.book().get("edition").value()).isEqualTo("개정 4판");
        // 근거는 원문 줄 그대로다(표지의 "개정 4판" 줄).
        assertThat(result.book().get("edition").quote()).isEqualTo("개정 4판");
        // 이름표 없는 큰 글씨 제목은 읽지 않는다 — 사용자가 확인·입력한다.
        assertThat(result.book()).doesNotContainKey("title");
    }

    @Test
    void 목차_머리와_이어지는_쪽에서_번호_제목_쪽수를_계층으로_읽고_본문에서_멈춘다() {
        TextbookExtractor.Result result = TextbookExtractor.extract(List.of(
                new TextbookExtractor.Unit(3, TOC_1), new TextbookExtractor.Unit(4, TOC_2),
                new TextbookExtractor.Unit(5, BODY)));

        assertThat(result.hasToc()).isTrue();
        assertThat(result.tocFromUnit()).isEqualTo(3);
        assertThat(result.tocToUnit()).isEqualTo(4);
        assertThat(result.toc()).extracting(TextbookExtractor.TocEntry::title).containsExactly(
                "자료구조와 알고리즘", "자료와 정보", "자료구조의 분류", "배열", "배열의 개념", "희소 행렬",
                "연결 리스트", "단순 연결 리스트", "원형 연결 리스트", "이중 연결 리스트");
        assertThat(result.toc()).extracting(TextbookExtractor.TocEntry::level)
                .containsExactly(1, 2, 2, 1, 2, 2, 1, 2, 2, 2);
        assertThat(result.toc().get(0).page()).isEqualTo(13);
        assertThat(result.toc().get(6).number()).isEqualTo("제3장");
    }

    @Test
    void 책_이름만_있고_목차가_없으면_목차를_만들지_않는다() {
        TextbookExtractor.Result result = TextbookExtractor.extract(List.of(
                new TextbookExtractor.Unit(1, "강의계획서\n교재: C로 배우는 쉬운 자료구조(한빛아카데미)\n1주차 오리엔테이션"),
                new TextbookExtractor.Unit(2, BODY)));

        assertThat(result.hasToc()).isFalse();
        assertThat(result.toc()).isEmpty();
    }

    @Test
    void 잘못된_ISBN은_버린다() {
        assertThat(TextbookExtractor.validIsbn("9791156645672")).isTrue();
        assertThat(TextbookExtractor.validIsbn("9791156645675")).isFalse();
        assertThat(TextbookExtractor.validIsbn("0306406153")).isFalse();
        assertThat(TextbookExtractor.validIsbn("0306406152")).isTrue();
    }

    @Test
    void 본문의_번호_목록은_쪽수가_없으면_목차_항목이_아니다() {
        assertThat(TextbookExtractor.parseLine("1 먼저 배열을 선언한다", 1)).isNull();
        assertThat(TextbookExtractor.parseLine("1 배열의 이해 ····· 23", 1)).isNotNull();
        assertThat(TextbookExtractor.parseLine("2.3 스택의 응용", 1).level()).isEqualTo(2);
    }
}
