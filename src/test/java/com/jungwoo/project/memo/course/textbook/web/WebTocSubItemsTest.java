package com.jungwoo.project.memo.course.textbook.web;

import com.jungwoo.project.memo.course.textbook.TextbookExtractor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 웹 목차의 하위항목(쪽 번호 없는 절·실습·요약·연습문제·부록)을 버리지 않고, 부모 관계와 원문 순서를 지킨다.
 * 목차는 합성이다(실제 교재 원문을 넣지 않는다).
 */
class WebTocSubItemsTest {

    /** 항목을 "깊이:번호 제목"으로. */
    private static List<String> shape(WebTocStructurer.Structured s) {
        List<String> out = new ArrayList<>();
        for (TextbookExtractor.TocEntry e : s.entries()) {
            out.add(e.level() + ":" + (e.number() == null ? "" : e.number() + " ") + e.title());
        }
        return out;
    }

    @Test
    void 장_아래_쪽_번호_없는_절과_실습_요약_연습문제를_장의_자식으로_순서대로_읽는다() {
        String raw = """
                Chapter 01 가나 다라
                01 마바 개요
                02 사아의 개념
                실습 1-1 자차 프로그램 작성과 테스트
                요약
                연습문제
                Chapter 02 카타 시작하기
                01 파하 처리
                요약/연습문제
                부록 A. 기기에서 실습하기
                부록 B. 설치하기
                """;

        WebTocStructurer.Structured s = WebTocStructurer.byRules(raw, false);

        assertThat(shape(s)).containsExactly(
                "1:Chapter 01 가나 다라", "2:01 마바 개요", "2:02 사아의 개념", "2:실습 1-1 자차 프로그램 작성과 테스트",
                "2:요약", "2:연습문제",
                "1:Chapter 02 카타 시작하기", "2:01 파하 처리", "2:요약/연습문제",
                "1:부록 A 기기에서 실습하기", "1:부록 B 설치하기");
        // 항목 열쇠는 원문 줄 번호다.
        assertThat(s.entries()).extracting(TextbookExtractor.TocEntry::unit)
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
        assertThat(s.unread()).isZero();
        assertThat(s.coverage()).isEqualTo("PAGE_FULL");
    }

    @Test
    void 절_사이에_낀_실습도_원문_순서대로_장의_자식이다() {
        String raw = """
                Chapter 13 통신
                01 직렬 통신
                실습 13-1 직렬 통신 프로그램
                02 무선 통신
                실습 13-2 장치 검색 프로그램
                """;

        assertThat(shape(WebTocStructurer.byRules(raw, false))).containsExactly(
                "1:Chapter 13 통신", "2:01 직렬 통신", "2:실습 13-1 직렬 통신 프로그램", "2:02 무선 통신",
                "2:실습 13-2 장치 검색 프로그램");
    }

    @Test
    void 장이_없는_목차는_가장_얕은_번호가_장이고_요약은_그_자식이다() {
        String raw = """
                1 리스트 ..... 10
                1.1 배열 리스트 ..... 11
                1.1.1 삽입 ..... 12
                1.1.1.1 맨 앞 삽입 ..... 12
                연습문제 ..... 20
                2 스택 ..... 21
                """;

        assertThat(shape(WebTocStructurer.byRules(raw, false))).containsExactly(
                "1:1 리스트", "2:1.1 배열 리스트", "3:1.1.1 삽입", "4:1.1.1.1 맨 앞 삽입", "2:연습문제", "1:2 스택");
    }

    @Test
    void 다섯_단계_깊이도_자르지_않는다() {
        String raw = """
                제1부 기초
                Chapter 1 자료
                1.1 배열
                1.1.1 정적 배열
                1.1.1.1 선언
                """;

        WebTocStructurer.Structured s = WebTocStructurer.byRules(raw, false);

        assertThat(shape(s)).containsExactly("0:제1부 기초", "1:Chapter 1 자료", "2:1.1 배열", "3:1.1.1 정적 배열",
                "4:1.1.1.1 선언");
    }

    @Test
    void 이름표로_시작하는_다른_말과_부록_뒤_영어_단어를_잘못_읽지_않는다() {
        assertThat(WebTocStructurer.readLine("정리하기 좋은 습관", 1)).isNull();
        WebTocStructurer.Read appendix = WebTocStructurer.readLine("Appendix Basics of C", 1);
        // "B"를 부록 기호로 떼어 내지 않는다.
        assertThat(appendix.number()).isEqualTo("Appendix");
        assertThat(appendix.title()).isEqualTo("Basics of C");
    }

    @Test
    void 규칙이_칠할을_읽어도_못_읽은_줄을_모델로_보완하고_규칙_항목은_그대로다() {
        String raw = """
                Chapter 01 하나
                01 가람
                02 나래
                손으로 해 보기
                손으로 더 해 보기
                Chapter 02 둘째
                01 다솜
                02 라온
                생각해 볼 거리
                Chapter 03 셋째
                """;
        WebTocStructurer.Structured ruled = WebTocStructurer.byRules(raw, false);
        assertThat(ruled.entries()).hasSize(7);
        assertThat(WebTocStructurer.unreadLines(raw, ruled)).containsExactly(4, 5, 9);

        // 모델: 4·5·9를 고른다. 규칙 줄(2)을 고치려 하고, 깊이를 건너뛰고, 범위 밖 줄을 고른 것은 버리거나 자른다.
        List<WebTocStructurer.Pick> picks = List.of(new WebTocStructurer.Pick(2, 0), new WebTocStructurer.Pick(4, 2),
                new WebTocStructurer.Pick(5, 6), new WebTocStructurer.Pick(9, 1), new WebTocStructurer.Pick(42, 2));

        WebTocStructurer.Structured s = WebTocStructurer.withModelPicks(raw, ruled, picks, false);

        assertThat(shape(s)).containsExactly(
                "1:Chapter 01 하나", "2:01 가람", "2:02 나래", "2:손으로 해 보기", "3:손으로 더 해 보기",
                "1:Chapter 02 둘째", "2:01 다솜", "2:02 라온",
                // 모델이 장 깊이(1)로 고른 줄은 장의 형제다 — 다음 규칙 항목(Chapter 03)의 부모를 바꾸지 않으므로 그대로 둔다.
                "1:생각해 볼 거리",
                "1:Chapter 03 셋째");
        // 규칙 항목은 제목·깊이·순서가 그대로다.
        List<TextbookExtractor.TocEntry> kept = s.entries().stream()
                .filter(e -> ruled.entries().stream().anyMatch(r -> r.unit() == e.unit())).toList();
        assertThat(kept).containsExactlyElementsOf(ruled.entries());
        assertThat(s.method()).isEqualTo("RULE_MODEL");
        assertThat(s.unread()).isZero();
        assertThat(s.coverage()).isNotEqualTo("PAGE_FULL");
    }

    /** 항목마다 부모의 원문 줄(없으면 0). 부모 = 앞쪽에서 가장 가까운, 깊이가 더 얕은 항목. */
    private static java.util.Map<Integer, Integer> parents(List<TextbookExtractor.TocEntry> entries) {
        java.util.Map<Integer, Integer> out = new java.util.HashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            int parent = 0;
            for (int j = i - 1; j >= 0; j--) {
                if (entries.get(j).level() < entries.get(i).level()) {
                    parent = entries.get(j).unit();
                    break;
                }
            }
            out.put(entries.get(i).unit(), parent);
        }
        return out;
    }

    @Test
    void 규칙_깊이가_건너뛰는_자리와_연속_모델_항목에서도_규칙_항목의_부모는_그대로다() {
        String raw = """
                Chapter 01 하나
                참고 읽을거리
                덧붙임 자료
                1.1.1 깊은 절
                1.1.2 다음 절
                쉬어 가기
                Chapter 02 둘째
                """;
        WebTocStructurer.Structured ruled = WebTocStructurer.byRules(raw, false);
        java.util.Map<Integer, Integer> before = parents(ruled.entries());

        WebTocStructurer.Structured s = WebTocStructurer.withModelPicks(raw, ruled, List.of(
                new WebTocStructurer.Pick(2, 1), new WebTocStructurer.Pick(3, 7), new WebTocStructurer.Pick(6, 0)), false);

        java.util.Map<Integer, Integer> after = parents(s.entries());
        before.forEach((line, parent) -> assertThat(after.get(line)).as("원문 %d행의 부모", line).isEqualTo(parent));
        assertThat(s.entries()).hasSize(7);
    }

    @Test
    void 장마다_번호_체계가_달라도_각각_절이다() {
        String raw = """
                Chapter 1 첫째 장
                1.1 첫 절
                1.1.1 첫 소절
                Chapter 2 둘째 장
                01 둘째 절
                부록 A. 덧붙임
                1 부록 절
                """;

        assertThat(shape(WebTocStructurer.byRules(raw, false))).containsExactly(
                "1:Chapter 1 첫째 장", "2:1.1 첫 절", "3:1.1.1 첫 소절", "1:Chapter 2 둘째 장", "2:01 둘째 절",
                "1:부록 A 덧붙임", "2:1 부록 절");
    }

    @Test
    void 모델_항목이_뒤따르는_규칙_항목의_부모를_빼앗지_못한다() {
        String raw = """
                Chapter 01 하나
                보충 학습
                01 가람
                02 나래
                """;
        WebTocStructurer.Structured ruled = WebTocStructurer.byRules(raw, false);

        // 모델이 "보충 학습"을 장(1)으로 고르면 01·02가 그 아래로 들어간다 — 규칙 항목의 부모가 바뀐다. 절 깊이(2)로 올린다.
        WebTocStructurer.Structured s = WebTocStructurer.withModelPicks(raw, ruled,
                List.of(new WebTocStructurer.Pick(2, 1)), false);

        assertThat(shape(s)).containsExactly("1:Chapter 01 하나", "2:보충 학습", "2:01 가람", "2:02 나래");
    }

    @Test
    void 구분자_없이_숫자로_끝나는_제목은_쪽수로_떼지_않는다() {
        String raw = """
                Chapter 01 기초
                01 IPv6
                02 표준 C99
                03 윈도우 10
                04 버전 1.2
                05 개요 ..... 12
                06 정리 / 30
                """;

        WebTocStructurer.Structured s = WebTocStructurer.byRules(raw, false);

        assertThat(s.entries()).extracting(TextbookExtractor.TocEntry::title)
                .containsExactly("기초", "IPv6", "표준 C99", "윈도우 10", "버전 1.2", "개요", "정리");
        assertThat(s.entries()).extracting(TextbookExtractor.TocEntry::page)
                .containsExactly(null, null, null, null, null, 12, 30);
    }

    @Test
    void 상한에서_모델_항목이_규칙_항목을_밀어내지_못하고_못_읽은_줄은_최종_항목으로_센다() {
        StringBuilder raw = new StringBuilder("머리에 붙은 안내 줄\n");
        for (int i = 1; i <= WebTocStructurer.MAX_ENTRIES; i++) {
            raw.append(String.format("%d.%d 항목 제목%n", 1 + i / 90, i % 90 + 1));
        }
        WebTocStructurer.Structured ruled = WebTocStructurer.byRules(raw.toString(), false);
        assertThat(ruled.entries()).hasSize(WebTocStructurer.MAX_ENTRIES);

        WebTocStructurer.Structured s = WebTocStructurer.withModelPicks(raw.toString(), ruled,
                List.of(new WebTocStructurer.Pick(1, 1)), false);

        assertThat(s.entries()).containsExactlyElementsOf(ruled.entries());
        assertThat(s.unread()).isEqualTo(1);
    }
}
