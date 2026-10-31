package com.jungwoo.project.memo.learning.week;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 주차 읽기. 범위는 범위다 — "1~3주차"를 3 하나로도 1 하나로도 줄이지 않는다.
 */
class WeekExpressionsTest {

    @Test
    void 범위는_모든_주차로_펼친다() {
        assertThat(WeekExpressions.weeksIn("1~3주차")).containsExactly(1, 2, 3);
        assertThat(WeekExpressions.weeksIn("9~12주차")).containsExactly(9, 10, 11, 12);
        assertThat(WeekExpressions.weeksIn("14-15주차")).containsExactly(14, 15);
        assertThat(WeekExpressions.weeksIn("12~15주차")).containsExactly(12, 13, 14, 15);
    }

    @Test
    void 범위_표기의_여러_모양을_같게_읽는다() {
        for (String text : List.of("1~3주차", "1-3주차", "1–3주차", "1∼3주차", "1 ~ 3 주차", "1주차~3주차", "제1~3주차")) {
            assertThat(WeekExpressions.weeksIn(text)).as(text).containsExactly(1, 2, 3);
        }
    }

    @Test
    void 단일_주차와_여러_표기() {
        assertThat(WeekExpressions.weeksIn("3주차 소켓 처리하기")).containsExactly(3);
        assertThat(WeekExpressions.weeksIn("제3주차")).containsExactly(3);
        assertThat(WeekExpressions.weeksIn("9주차, 12주차")).containsExactly(9, 12);
        assertThat(WeekExpressions.weeksIn("AWS 구성하기 + SSH 실습 (네트워크프로그래밍 3주차)")).containsExactly(3);
    }

    @Test
    void 주차가_아닌_숫자는_읽지_않는다() {
        assertThat(WeekExpressions.weeksIn("Ch03-Lesson 04")).isEmpty();
        assertThat(WeekExpressions.weeksIn("중간고사")).isEmpty();
        assertThat(WeekExpressions.weeksIn(null)).isEmpty();
        // 뒤집힌 범위는 무엇인지 알 수 없어 버린다
        assertThat(WeekExpressions.weeksIn("5~3주차")).isEmpty();
        // 학기 범위를 넘는 값
        assertThat(WeekExpressions.weeksIn("45주차")).isEmpty();
    }

    @Test
    void 파일명의_명시적_주차() {
        assertThat(WeekExpressions.filenameWeeks("3주차.pdf")).containsExactly(3);
        assertThat(WeekExpressions.filenameWeeks("1주차 .pdf")).containsExactly(1);
        assertThat(WeekExpressions.filenameWeeks("2주차_1 .pdf")).containsExactly(2);
        assertThat(WeekExpressions.filenameWeeks("2주차_2 .pptx")).containsExactly(2);
        assertThat(WeekExpressions.filenameWeeks("2주차 (1).pdf")).containsExactly(2);
        assertThat(WeekExpressions.filenameWeeks("2주차  (1).pdf")).containsExactly(2);
        assertThat(WeekExpressions.filenameWeeks("3주_2.pdf")).containsExactly(3);
        assertThat(WeekExpressions.filenameWeeks("3주__1.pdf")).containsExactly(3);
        assertThat(WeekExpressions.filenameWeeks("1~4주차 총정리.pdf")).containsExactly(1, 2, 3, 4);
    }

    @Test
    void 다운로드_중복_꼬리는_주차가_아니다() {
        assertThat(WeekExpressions.filenameWeeks("네트워크프로그래밍(4).pdf")).isEmpty();
        assertThat(WeekExpressions.leadingNumber("네트워크프로그래밍(4).pdf")).isNull();
        assertThat(WeekExpressions.filenameWeeks("4장_2 문제_2026 (1).hwp")).isEmpty();
        assertThat(WeekExpressions.stem("01.수업소개_네트워크프로그래밍(1).pdf")).isEqualTo("01.수업소개_네트워크프로그래밍");
    }

    @Test
    void 이어지는_한글이_있으면_주차가_아니다() {
        assertThat(WeekExpressions.filenameWeeks("창립 10주년 기념.pdf")).isEmpty();
        assertThat(WeekExpressions.filenameWeeks("3주기 보고서.pdf")).isEmpty();
    }

    @Test
    void 파일명_순번은_약한_단서로만_읽는다() {
        assertThat(WeekExpressions.leadingNumber("3.AWS_구성하기_SSH실습.pdf")).isEqualTo(3);
        assertThat(WeekExpressions.leadingNumber("3.AWS.pdf")).isEqualTo(3);
        assertThat(WeekExpressions.leadingNumber("01.수업소개_네트워크프로그래밍.pdf")).isEqualTo(1);
        assertThat(WeekExpressions.leadingNumber("2.리눅스_개요와_실습환경_구축_WSL2추가_v2.pdf")).isEqualTo(2);
        assertThat(WeekExpressions.leadingNumber("1.1과목개요.pdf")).isEqualTo(1);
        assertThat(WeekExpressions.leadingNumber("3_넘파이_S.ipynb")).isEqualTo(3);
        // 다른 단위(장·과)가 붙으면 주차 단서가 아니다
        assertThat(WeekExpressions.leadingNumber("4장_1 문제_학생 _2026 (1).hwp")).isNull();
        assertThat(WeekExpressions.leadingNumber("0과 Orientation_1.pdf")).isNull();
        assertThat(WeekExpressions.leadingNumber("ch02_Part 1_배열_포인터_구조체(2026.09).pptx")).isNull();
        assertThat(WeekExpressions.leadingNumber("2026-2 강의계획서.pdf")).isNull();
        // 명시적 주차는 순번이 아니다(따로 읽는다)
        assertThat(WeekExpressions.leadingNumber("3주차.pdf")).isNull();
    }

    @Test
    void 주차_목록을_사람이_읽는_말로() {
        assertThat(WeekExpressions.describe(List.of(1, 2, 3))).isEqualTo("1~3주차");
        assertThat(WeekExpressions.describe(List.of(8))).isEqualTo("8주차");
        assertThat(WeekExpressions.describe(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15)))
                .isEqualTo("1~15주차");
        assertThat(WeekExpressions.describe(List.of(1, 2, 5))).isEqualTo("1~2, 5주차");
    }
}
