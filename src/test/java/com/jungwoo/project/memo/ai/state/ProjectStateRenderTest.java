package com.jungwoo.project.memo.ai.state;

import com.jungwoo.project.memo.ai.domain.ContextEvidenceType;
import com.jungwoo.project.memo.ai.domain.ContextSourceType;
import com.jungwoo.project.memo.ai.domain.FactKind;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 프로젝트 상태 블록 — 상담·자료 선택·계획이 같은 문장을 받는다. 출처 라벨은 서버가 붙이고, 넘치면 줄 단위로 줄인다.
 */
class ProjectStateRenderTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 5, 20, 0);

    private static ProjectStateService.Fact fact(long id, FactKind kind, String text, Long topicId, String topicTitle,
                                                 Integer seq, ContextEvidenceType type, ContextSourceType source, String help) {
        return new ProjectStateService.Fact(id, kind, null, text, topicId, topicTitle, seq, type, source, help, AT, AT,
                "ACTIVE", null);
    }

    private static ProjectStateService.State state(List<ProjectStateService.Fact> facts) {
        return new ProjectStateService.State(940L, "영어회화",
                "「NEW English Conversation Arts 1」 · 형설출판사 — 사용자가 정함", facts,
                List.of(new ProjectStateService.ClassLine(501L, "Unit 1 What's your name?", 1, 2)),
                List.of(new ProjectStateService.Exclusion(512L, "Unit 12 Review", "중간고사")), "fp");
    }

    @Test
    void 순서는_교재_진도_정정_시험범위_막힘_해결_목표_추정이고_라벨은_서버가_붙인다() {
        ProjectStateService.Rendered r = ProjectStateService.render(state(List.of(
                fact(1, FactKind.PROGRESS, "수업은 Unit 4까지 나갔다", null, null, null, ContextEvidenceType.STATED,
                        ContextSourceType.CONSULT_AUTO, null),
                fact(2, FactKind.DIFFICULTY, "have to 의문문이 헷갈린다", 503L, "Unit 3 I have to make hotel reservations", 3,
                        ContextEvidenceType.STATED, ContextSourceType.CONSULT_AUTO, null),
                fact(3, FactKind.RESOLVED, "have to 문장을 만들 수 있게 됐다", 503L, "Unit 3 I have to make hotel reservations", 3,
                        ContextEvidenceType.SELF_REPORT, ContextSourceType.CONSULT_AUTO, "GUIDED"),
                fact(4, FactKind.PREFERENCE, "발음 연습을 원한다", null, null, null, ContextEvidenceType.INFERRED,
                        ContextSourceType.CONSULT_AUTO, null),
                fact(5, FactKind.EXAM_SCOPE, "Unit 1~6", null, null, null, ContextEvidenceType.STATED,
                        ContextSourceType.USER_EDITED, null))), ProjectStateService.MAX_LINES,
                ProjectStateService.LineMarker.PLAIN);

        List<String> lines = List.of(r.text().split("\n"));
        assertThat(lines.get(0)).startsWith("- 교재: 「NEW English Conversation Arts 1」");
        assertThat(lines.get(1)).isEqualTo("- 수업 진도: 수업은 Unit 4까지 나갔다 (10/5 사용자가 말함)");
        assertThat(lines.get(2)).startsWith("- 실제 수업 정정: #501");
        assertThat(lines.get(3)).isEqualTo("- 시험 범위: Unit 1~6 (10/5 사용자가 고침)");
        assertThat(lines.get(4)).startsWith("- 범위에서 뺀 것: #512");
        assertThat(lines.get(5)).startsWith("- 막힌 곳 m2 [#503 Unit 3").contains("교재 목차 3번째")
                .endsWith("have to 의문문이 헷갈린다 (10/5 사용자가 말함)");
        assertThat(lines.get(6)).startsWith("- 도움받아 해결 [#503").contains("자기평가");
        assertThat(lines.get(7)).startsWith("- AI 추정(확인 전) 선호").doesNotContain("사용자가 말함");
        assertThat(r.contextIds()).containsExactly(1L, 5L, 2L, 3L, 4L);
        assertThat(ProjectStateService.HEADER).contains("다시 묻지 않는다").contains("학습을 마쳤다는 뜻이 아니다");
    }

    @Test
    void 넘치면_추정부터_줄_단위로_빼고_뺀_기억의_id는_돌려주지_않는다() {
        List<ProjectStateService.Fact> facts = new ArrayList<>();
        facts.add(fact(1, FactKind.PROGRESS, "수업은 Unit 4까지 나갔다", null, null, null, ContextEvidenceType.STATED,
                ContextSourceType.CONSULT_AUTO, null));
        for (int i = 0; i < 5; i++) {
            facts.add(fact(10 + i, FactKind.DIFFICULTY, "막힘 " + i, null, null, null, ContextEvidenceType.STATED,
                    ContextSourceType.CONSULT_AUTO, null));
            facts.add(fact(20 + i, FactKind.OTHER, "추정 " + i, null, null, null, ContextEvidenceType.INFERRED,
                    ContextSourceType.CONSULT_AUTO, null));
        }
        ProjectStateService.Rendered r = ProjectStateService.render(state(facts), 8, ProjectStateService.LineMarker.PLAIN);

        assertThat(r.text().split("\n")).hasSize(8);
        assertThat(r.text()).contains("수업 진도").contains("막힘 4").doesNotContain("추정");
        assertThat(r.contextIds()).noneMatch(id -> id >= 20);
    }

    @Test
    void 긴_줄은_자르되_줄_길이_상한을_넘지_않는다() {
        ProjectStateService.Rendered r = ProjectStateService.render(state(List.of(
                fact(1, FactKind.DIFFICULTY, "가".repeat(400), null, null, null, ContextEvidenceType.STATED,
                        ContextSourceType.CONSULT_AUTO, null))), ProjectStateService.MAX_LINES,
                ProjectStateService.LineMarker.PLAIN);
        for (String line : r.text().split("\n")) {
            assertThat(line.length()).isLessThanOrEqualTo(ProjectStateService.MAX_LINE_CHARS + 2);
        }
    }

    @Test
    void 비어_있으면_아무것도_싣지_않는다() {
        assertThat(ProjectStateService.render(null, 14, ProjectStateService.LineMarker.PLAIN).text()).isEmpty();
        assertThat(ProjectStateService.render(new ProjectStateService.State(1L, "x", null, List.of(), List.of(), List.of(), "f"),
                14, ProjectStateService.LineMarker.PLAIN).contextIds()).isEmpty();
    }
}
