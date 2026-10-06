package com.jungwoo.project.memo.ai.evidence;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QueryTermsAndPassageTest {

    @Test
    void 시험을_물으면_중간고사_기말고사로도_찾고_조사를_뗀다() {
        QueryTerms q = QueryTerms.of("시험까지 이제 거의 2주정도밖에 안남았는데 무슨과목부터 하면 좋을까", List.of(), null, List.of());

        assertThat(q.texts()).contains("시험", "중간고사", "기말고사", "평가");
        assertThat(q.texts()).doesNotContain("시험까지", "이제", "거의");
        assertThat(q.asksSchedule()).isTrue();
    }

    @Test
    void 대상이_없는_후속_말은_앞선_발화의_대상을_낮은_가중치로_이어받는다() {
        QueryTerms q = QueryTerms.of("자료에 있는데", List.of("내 강의계획서 시간표 그대로 19일부터 시작해"),
                "강의계획서 시간표의 요일·시각을 보내주면", List.of());

        assertThat(q.texts()).contains("강의계획서", "시간표", "19일").doesNotContain("자료", "그대");
        QueryTerms.Term syllabus = q.terms().stream().filter(t -> t.text().equals("강의계획서")).findFirst().orElseThrow();
        assertThat(syllabus.weight()).isLessThan(1.0);
        assertThat(q.currentConcepts()).isEmpty();
        assertThat(q.concepts()).contains(QueryTerms.Concept.CLASS_TIME, QueryTerms.Concept.SYLLABUS);
    }

    @Test
    void 과목_이름은_범위어라_가중치가_낮아진다() {
        QueryTerms q = QueryTerms.of("자료구조 시험 범위", List.of(), null, List.of()).withScopeWords(List.of("자료구조"), 0.3);

        assertThat(q.terms()).filteredOn(t -> t.text().equals("자료구조")).singleElement()
                .satisfies(t -> assertThat(t.weight()).isEqualTo(0.3));
    }

    @Test
    void 띄어쓰기가_달라도_걸린다() {
        QueryTerms q = QueryTerms.of("시간표 알려줘", List.of(), null, List.of());

        assertThat(PassageExtractor.score("강의 시간: 화요일 10:00~11:50", q.terms())).isGreaterThanOrEqualTo(0.6);
    }

    @Test
    void 긴_단위는_걸린_표_행을_머리_행과_함께_잘라_낸다() {
        String text = "앞부분 ".repeat(800) + "\n[표 시작]\n| 주차 | 날짜 | 내용 |\n" + "| 1주차 | 9/1 | 소개 |\n".repeat(30)
                + "| 7주차 | 10/19~10/23 | 중간고사 |\n| 8주차 | 10/27 | 큐 |\n[표 끝]\n" + "뒷부분 ".repeat(800);
        QueryTerms q = QueryTerms.of("시험", List.of(), null, List.of());

        PassageExtractor.Passage p = PassageExtractor.extract(text, q.terms(), 1200);

        assertThat(p.full()).isFalse();
        assertThat(p.text()).contains("| 주차 | 날짜 | 내용 |").contains("| 7주차 | 10/19~10/23 | 중간고사 |");
        assertThat(p.text().length()).isLessThanOrEqualTo(1201);
    }

    @Test
    void 짧은_단위는_통째로_싣는다() {
        PassageExtractor.Passage p = PassageExtractor.extract("중간고사 10월 21일", QueryTerms.of("시험", List.of(), null,
                List.of()).terms(), 1000);

        assertThat(p.full()).isTrue();
        assertThat(p.text()).isEqualTo("중간고사 10월 21일");
    }
}
