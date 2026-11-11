package com.jungwoo.project.memo.plan.generation;

import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.plan.dto.PlanDraftAiResult;
import com.jungwoo.project.memo.plan.provenance.PlanItemEvidence;
import com.jungwoo.project.memo.plan.provenance.ProvenanceCollector;
import com.jungwoo.project.memo.plan.provenance.ProvenanceRepresentation;
import com.jungwoo.project.memo.plan.provenance.ProvenanceSourceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 계획 항목의 학습 목표와 그 근거(계획 v3 §2.1·§8.5). 근거 종류는 모델이 아니라 서버가 인용으로 정한다.
 * 목차 제목만 아는 단원의 목표는 "목차에서 추론", 그 단원으로 만든 연습은 "AI가 만든 연습"이다.
 */
class PlanItemGoalTest {

    private static final LocalDate START = LocalDate.of(2026, 10, 6);
    private static final LocalDate END = LocalDate.of(2026, 10, 12);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 5, 21, 0);
    private static final List<Course> COURSES = List.of(Course.builder().courseId(940L).title("영어회화").build());

    private final ProvenanceCollector collector = new ProvenanceCollector("gen-1", NOW, "Asia/Seoul", START, END, "AI", "m");

    private String tocTopic(long id, String title, int seq) {
        return collector.mark(ProvenanceSourceType.TOPIC, id, null, null, ProvenanceRepresentation.SELECTED_FIELDS,
                ProvenanceCollector.value("courseId", 940L, "title", title, "sourceLocator", "교재 p." + (seq * 6),
                        "tocSeq", seq), title).refId();
    }

    private String section(long id) {
        return collector.mark(ProvenanceSourceType.MATERIAL_SECTION, id, null, null, ProvenanceRepresentation.EXCERPT,
                ProvenanceCollector.value("courseId", 940L), "본문 구간").refId();
    }

    private String acceptedGoal(String text) {
        return collector.mark(ProvenanceSourceType.PLAN_BRIEF, 1L, 1L, null, ProvenanceRepresentation.EXCERPT,
                ProvenanceCollector.value("kind", "GOAL", "speaker", "USER", "accepted", true, "text", text), text).refId();
    }

    private static PlanDraftAiResult.PlanDraftAiItem item(String title, String action, String origin, String goal,
                                                          List<String> refs) {
        return new PlanDraftAiResult.PlanDraftAiItem(title, "말하기 · 완료: 5문장", null, action, 20, "SHOULD", 940L, null,
                "막힌 단원", refs, null, null, null, origin, goal);
    }

    private PlanResultNormalizer.Normalized run(PlanDraftAiResult.PlanDraftAiItem... items) {
        PlanDraftAiResult ai = new PlanDraftAiResult("t", null, null, null, null, List.of(items), null, null);
        return PlanResultNormalizer.normalize(ai, START, END, NOW, COURSES, collector.build(), Map.of(), Map.of(),
                List.of(), List.of());
    }

    @Test
    void 목차_항목만_인용한_목표는_목차에서_추론이고_그_연습은_AI가_만든_연습이다() {
        String unit1 = tocTopic(501L, "Unit 1 I'm doing my homework right now", 1);

        PlanResultNormalizer.Normalized out = run(
                item("현재진행형으로 지금 하는 일 5문장 말하기", "PRACTICE", null, "현재진행형으로 지금 하는 일 말하기", List.of(unit1)),
                // 모델이 교재 문제(SOURCE_TASK)라고 해도 원문을 인용하지 않았으면 AI 연습이다.
                item("Unit 1 대화 따라 말하기", "PRACTICE", "SOURCE_TASK", null, List.of(unit1)));

        PlanItemEvidence first = out.evidence().get(0);
        assertThat(first.goal()).isEqualTo("현재진행형으로 지금 하는 일 말하기");
        assertThat(first.goalBasis()).isEqualTo(PlanResultNormalizer.GOAL_TOC_AI);
        assertThat(first.origin()).isEqualTo(PlanResultNormalizer.ORIGIN_AI_PRACTICE);
        assertThat(out.evidence().get(1).origin()).isEqualTo(PlanResultNormalizer.ORIGIN_AI_PRACTICE);
        assertThat(out.items().get(0).topicId()).isEqualTo(501L);
    }

    @Test
    void 자료_구간을_인용하면_자료_기반_AI_목표이고_원문_과제는_그대로_둔다() {
        String unit3 = tocTopic(503L, "Unit 3 I have to make hotel reservations", 3);
        String text = section(77L);

        PlanResultNormalizer.Normalized out = run(item("예약 대화 연습", "PRACTICE", "SOURCE_TASK",
                "have to로 예약 요청하기", List.of(unit3, text)));

        assertThat(out.evidence().get(0).goalBasis()).isEqualTo(PlanResultNormalizer.GOAL_MATERIAL_AI);
        assertThat(out.evidence().get(0).origin()).isEqualTo(PlanResultNormalizer.ORIGIN_SOURCE_TASK);
    }

    @Test
    void 사용자가_정한_목표는_그_목표를_그대로_옮겼을_때만이고_바꾼_목표는_AI_목표다() {
        String unit4 = tocTopic(504L, "Unit 4 Did you have a good weekend?", 4);
        String goal = acceptedGoal("과거시제로 주말 질문하기");

        PlanResultNormalizer.Normalized out = run(
                item("주말 질문 연습", "PRACTICE", null, "과거시제로 주말 질문하기", List.of(goal, unit4)),
                // 수락된 목표를 인용했지만 다른 목표를 냈다 — 사용자 목표가 아니다(목차 근거로 판정).
                item("현재진행형 설명", "PRACTICE", null, "현재진행형으로 설명하기", List.of(goal, unit4)),
                // 근거가 사용자 목표뿐인데 다른 목표 — 근거가 없으니 버린다.
                item("엉뚱한 목표", "READ", null, "현재진행형으로 설명하기", List.of(goal)),
                // 확인된 목표의 일부만 떼어 낸 것은 사용자 목표가 아니다.
                item("일부만", "PRACTICE", null, "질문하기", List.of(goal, unit4)));

        assertThat(out.evidence().get(0).goalBasis()).isEqualTo(PlanResultNormalizer.GOAL_USER);
        assertThat(out.evidence().get(1).goalBasis()).isEqualTo(PlanResultNormalizer.GOAL_TOC_AI);
        assertThat(out.evidence().get(2).goal()).isNull();
        assertThat(out.evidence().get(2).goalBasis()).isNull();
        assertThat(out.evidence().get(3).goalBasis()).isEqualTo(PlanResultNormalizer.GOAL_TOC_AI);
    }

    @Test
    void 근거_없는_목표나_다른_단원의_목차를_인용한_목표는_버리고_길면_자른다() {
        String unit1 = tocTopic(501L, "Unit 1 What's your name?", 1);
        String unit10 = tocTopic(510L, "Unit 10 What's your name?", 10);

        PlanResultNormalizer.Normalized out = run(
                item("근거 없음", "READ", null, "아무 목표", List.of()),
                item("긴 목표", "PRACTICE", null, "가".repeat(100), List.of(unit10)));

        assertThat(out.evidence().get(0).goal()).isNull();
        assertThat(out.evidence().get(1).goal()).hasSize(PlanResultNormalizer.MAX_GOAL_CHARS);
        // 같은 제목의 Unit 1은 이 항목의 근거가 아니다(인용하지 않았다).
        assertThat(out.items().get(1).topicId()).isEqualTo(510L);
        assertThat(unit1).isNotNull();
    }

    @Test
    void 목표는_상태가_바뀌어도_근거_JSON에서_그대로_다시_읽힌다() throws Exception {
        PlanItemEvidence evidence = PlanItemEvidence.of("gen-1", List.of("s1"), "r", List.of(), List.of(), 0)
                .withOrigin("AI_PRACTICE").withGoal("현재진행형으로 말하기", PlanResultNormalizer.GOAL_TOC_AI)
                .withStatus(com.jungwoo.project.memo.plan.provenance.EvidenceStatus.CURRENT, List.of());
        ObjectMapper om = new ObjectMapper().findAndRegisterModules();

        PlanItemEvidence back = om.readValue(om.writeValueAsString(evidence), PlanItemEvidence.class);

        assertThat(back.goal()).isEqualTo("현재진행형으로 말하기");
        assertThat(back.goalBasis()).isEqualTo("TOC_AI");
        assertThat(back.origin()).isEqualTo("AI_PRACTICE");
        // 예전 근거(목표 칸 없음)도 읽힌다.
        assertThat(om.readValue("{\"generationId\":\"g\",\"refIds\":[],\"origin\":\"SOURCE_TASK\"}", PlanItemEvidence.class)
                .goal()).isNull();
    }
}
