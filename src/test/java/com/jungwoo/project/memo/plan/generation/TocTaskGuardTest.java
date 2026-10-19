package com.jungwoo.project.memo.plan.generation;

import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.course.textbook.TocItemKind;
import com.jungwoo.project.memo.plan.domain.DoneCriteriaSource;
import com.jungwoo.project.memo.plan.dto.PlanDraftAiResult;
import com.jungwoo.project.memo.plan.provenance.ProvenanceCollector;
import com.jungwoo.project.memo.plan.provenance.ProvenanceRepresentation;
import com.jungwoo.project.memo.plan.provenance.ProvenanceSourceType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 목차 제목만 있는 실습·연습문제로 문제 내용을 지어낸 모델 출력은 저장되지 않는다. 그 토픽의 본문을 인용했을 때만 모델 문구를 쓴다.
 */
class TocTaskGuardTest {

    private static final LocalDate START = LocalDate.of(2026, 10, 5);
    private static final LocalDate END = LocalDate.of(2026, 10, 11);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 6, 9, 0);
    private static final List<Course> COURSES = List.of(Course.builder().courseId(1L).title("네트워크").build());
    private static final String FABRICATED = "1번 문제: TCP 3-way handshake를 설명하라";

    private final ProvenanceCollector collector = new ProvenanceCollector("gen-1", NOW, "Asia/Seoul", START, END, "AI", "m");

    private String chapterRef() {
        return collector.mark(ProvenanceSourceType.TOPIC, 10L, null, null, ProvenanceRepresentation.SELECTED_FIELDS,
                Map.of("title", "Chapter 01 첫째 장", "sourceLocator", "교재 목차"), "장", null, null).refId();
    }

    private String exerciseRef() {
        return collector.mark(ProvenanceSourceType.TOPIC, 11L, null, null, ProvenanceRepresentation.SELECTED_FIELDS,
                Map.of("title", "Chapter 01 첫째 장 › 연습문제", "sourceLocator", "교재 p.40"), "연습문제", 10L, null).refId();
    }

    private static PlanDraftAiResult.PlanDraftAiItem item(List<String> refs) {
        return new PlanDraftAiResult.PlanDraftAiItem(FABRICATED, FABRICATED + " · 완료: 정답 SYN-ACK 확인", null, "PRACTICE",
                30, "MUST", 1L, null, FABRICATED, refs, null, null, null);
    }

    private PlanResultNormalizer.Normalized run(List<String> refs) {
        PlanDraftAiResult ai = new PlanDraftAiResult("t", null, null, null, null, List.of(item(refs)), null, null);
        return PlanResultNormalizer.normalize(ai, START, END, NOW, COURSES, collector.build(), Map.of(), Map.of(),
                List.of(), List.of());
    }

    @Test
    void 목차_연습문제만_인용하고_지어낸_문제는_교재를_가리키는_서버_문구로_바뀐다() {
        chapterRef();
        PlanResultNormalizer.Normalized out = run(List.of(exerciseRef()));

        var saved = out.items().get(0);
        assertThat(saved.title()).isEqualTo("「Chapter 01 첫째 장 › 연습문제」 풀기").doesNotContain("handshake");
        assertThat(saved.description()).doesNotContain("handshake").contains("교재에서 직접 풀어요").contains("교재 p.40");
        assertThat(saved.doneCriteria()).isEqualTo("교재 「Chapter 01 첫째 장 › 연습문제」 문제를 모두 풀고 답을 확인한다");
        assertThat(saved.doneCriteriaSource()).isEqualTo(DoneCriteriaSource.DEFAULT);
        assertThat(out.evidence().get(0).reason()).doesNotContain("handshake");
        assertThat(out.evidence().get(0).origin()).isNull();
    }

    @Test
    void 다른_장의_본문을_함께_인용해도_풀리지_않는다() {
        String exercise = exerciseRef();
        String otherBody = collector.mark(ProvenanceSourceType.MATERIAL_SECTION, 501L, null, null,
                ProvenanceRepresentation.EXCERPT, Map.of(), "다른 장 설명 구간", 77L, null).refId();

        PlanResultNormalizer.Normalized out = run(List.of(exercise, otherBody));

        assertThat(out.items().get(0).title()).doesNotContain("handshake");
        assertThat(out.items().get(0).description()).doesNotContain("handshake");
    }

    @Test
    void 그_연습문제_토픽의_본문을_인용하면_모델_문구를_쓴다() {
        String exercise = exerciseRef();
        String body = collector.mark(ProvenanceSourceType.MATERIAL_SECTION, 502L, null, null,
                ProvenanceRepresentation.EXCERPT, Map.of(), "연습문제 본문", 11L, null).refId();

        PlanResultNormalizer.Normalized out = run(List.of(exercise, body));

        assertThat(out.items().get(0).title()).isEqualTo(FABRICATED);
    }

    @Test
    void 목차_항목_종류를_제목으로_본다() {
        assertThat(TocItemKind.of("연습문제")).isEqualTo(TocItemKind.EXERCISE);
        assertThat(TocItemKind.of("요약/연습문제")).isEqualTo(TocItemKind.EXERCISE);
        assertThat(TocItemKind.of("실습 1-1 소켓 프로그램")).isEqualTo(TocItemKind.LAB);
        assertThat(TocItemKind.of("요약")).isEqualTo(TocItemKind.SUMMARY);
        assertThat(TocItemKind.of("01 TCP/IP 개요")).isNull();
        assertThat(TocItemKind.of("정리하기 좋은 습관")).isNull();
    }

    @Test
    void 본문이_여러_토픽에_연결돼_있으면_연결된_토픽_전부로_확인한다() {
        String exercise = exerciseRef();
        String body = collector.mark(ProvenanceSourceType.MATERIAL_SECTION, 503L, null, null,
                ProvenanceRepresentation.EXCERPT, Map.of("linkedTopicIds", List.of(10L, 11L)), "장과 연습문제에 걸린 본문",
                10L, null).refId();

        PlanResultNormalizer.Normalized out = run(List.of(exercise, body));

        assertThat(out.items().get(0).title()).isEqualTo(FABRICATED);
    }
}
