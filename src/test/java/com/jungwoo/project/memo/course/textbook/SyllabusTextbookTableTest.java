package com.jungwoo.project.memo.course.textbook;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 강의계획서 교재 칸(합성). 실제 PDF에서 뽑힌 텍스트 모양을 따랐다 — 표의 칸이 줄로 흩어진다.
 */
class SyllabusTextbookTableTest {

    private static List<TextbookExtractor.BookClue> read(String text) {
        return SyllabusTextbookTable.read(List.of(new TextbookExtractor.Unit(1, text)));
    }

    @Test
    void 칸이_줄로_흩어진_교재_표에서_주교재와_저자의_줄바꿈을_읽는다() {
        List<TextbookExtractor.BookClue> clues = read("""
                과목 개요
                학습목표 및
                성취수준
                도서명 저자 출판사 비고
                 Michael
                 주교재  NEW English Conversation Arts 1  형설출판사
                Putlack, 이현호
                수업시
                사용도구
                성적평가 비율
                """);

        assertThat(clues).hasSize(1);
        TextbookExtractor.BookClue c = clues.get(0);
        assertThat(c.role()).isEqualTo("MAIN");
        assertThat(c.title()).isEqualTo("NEW English Conversation Arts 1");
        assertThat(c.author()).isEqualTo("Michael Putlack, 이현호");
        assertThat(c.publisher()).isEqualTo("형설출판사");
        assertThat(c.quote()).contains("주교재", "Putlack");
    }

    @Test
    void 제목_아래로_저자_두_줄과_출판사_줄이_이어진_표도_읽는다() {
        // 실제 강의계획서 PDF에서 본 다른 모양(합성 재현): 저자 칸이 두 줄로, 출판사가 그 아래 줄로 흩어진다.
        List<TextbookExtractor.BookClue> clues = read("""
                도서명 저자 출판사 비고
                 주교재  NEW English Conversation Arts 1
                 Michael
                Putlack, 이현호
                 형설출판사
                수업시
                사용도구
                """);

        assertThat(clues).hasSize(1);
        TextbookExtractor.BookClue c = clues.get(0);
        assertThat(c.title()).isEqualTo("NEW English Conversation Arts 1");
        assertThat(c.author()).isEqualTo("Michael Putlack, 이현호");
        assertThat(c.publisher()).isEqualTo("형설출판사");
        assertThat(c.quote()).contains("Michael", "Putlack", "형설출판사");
    }

    @Test
    void 출판사_낱말이_들어간_사람_이름은_출판사로_보지_않는다() {
        List<TextbookExtractor.BookClue> clues = read("""
                도서명 저자 출판사 비고
                 주교재  Statistics Primer
                 Karl Pearson
                 Wiley
                수업시
                """);

        assertThat(clues).hasSize(1);
        assertThat(clues.get(0).author()).isEqualTo("Karl Pearson");
        assertThat(clues.get(0).publisher()).isEqualTo("Wiley");

        // 여러 낱말 출판사 이름은 출판사다.
        List<TextbookExtractor.BookClue> mit = read("""
                도서명 저자 출판사 비고
                 주교재  Introduction to Algorithms
                 Thomas H. Cormen
                 MIT Press
                수업시
                """);
        assertThat(mit.get(0).author()).isEqualTo("Thomas H. Cormen");
        assertThat(mit.get(0).publisher()).isEqualTo("MIT Press");
    }

    @Test
    void 칸이_한_줄에_있는_표와_부교재를_구분한다() {
        List<TextbookExtractor.BookClue> clues = read("""
                교재명    저자    출판사
                주교재  C로 배우는 쉬운 자료구조(개정 4판)  이지영  한빛아카데미
                부교재  알고리즘 문제 해결 전략  구종만  인사이트
                성적 평가
                """);

        assertThat(clues).extracting(TextbookExtractor.BookClue::role).containsExactly("MAIN", "SUPPLEMENT");
        assertThat(clues.get(0).title()).isEqualTo("C로 배우는 쉬운 자료구조");
        assertThat(clues.get(0).edition()).isEqualTo("개정 4판");
        assertThat(clues.get(0).author()).isEqualTo("이지영");
        assertThat(clues.get(0).publisher()).isEqualTo("한빛아카데미");
        assertThat(clues.get(1).title()).isEqualTo("알고리즘 문제 해결 전략");
    }

    @Test
    void 이름표로_적은_교재_줄도_읽는다() {
        List<TextbookExtractor.BookClue> clues = read("주교재: 운영체제 / 홍길동 / 어느출판사\n");

        assertThat(clues).singleElement().satisfies(c -> {
            assertThat(c.title()).isEqualTo("운영체제");
            assertThat(c.author()).isEqualTo("홍길동");
            assertThat(c.publisher()).isEqualTo("어느출판사");
        });
    }

    @Test
    void 교재_머리가_없으면_아무것도_만들지_않는다() {
        assertThat(read("이번 주에는 교재 3장을 읽는다.\n과제는 주교재 연습문제 2번이다.\n")).isEmpty();
    }

    @Test
    void 칸_사이가_한_칸_공백으로_뽑혀도_제목_끝의_출판사를_떼어_낸다() {
        List<TextbookExtractor.BookClue> clues = read("""
                도서명 저자 출판사 비고
                Michael
                주교재 NEW English Conversation Arts 1 형설출판사
                Putlack, 이현호
                성적평가 비율
                """);

        assertThat(clues).singleElement().satisfies(c -> {
            assertThat(c.title()).isEqualTo("NEW English Conversation Arts 1");
            assertThat(c.publisher()).isEqualTo("형설출판사");
            assertThat(c.author()).isEqualTo("Michael Putlack, 이현호");
        });
    }
}
