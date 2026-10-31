package com.jungwoo.project.memo.plan.generation;

import com.jungwoo.project.memo.ai.domain.ProposalOperation;
import com.jungwoo.project.memo.ai.dto.ProposalAdjustment;
import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.execution.domain.ExecutionItem;
import com.jungwoo.project.memo.execution.domain.ExecutionStatus;
import com.jungwoo.project.memo.execution.domain.PlacementType;
import com.jungwoo.project.memo.plan.domain.ActionType;
import com.jungwoo.project.memo.plan.domain.Treatment;
import com.jungwoo.project.memo.plan.dto.PlanDraftAiResult;
import com.jungwoo.project.memo.plan.provenance.PlanProvenance;
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
 * 모델 출력을 서버가 아는 사실과 대조한다. 마감은 수업·과제 사실을 가리킬 때만 그 시각을 얻고, 제안 완료 목표는 제안으로
 * 표시된다. 학습 항목 연결은 인용에서 온다. 기존 항목 결정은 이 기간의 계획 항목만 조정한다.
 */
class PlanResultNormalizerTest {

    private static final LocalDate START = LocalDate.of(2026, 9, 14);
    private static final LocalDate END = LocalDate.of(2026, 9, 20);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 14, 9, 0);
    private static final List<Course> COURSES = List.of(Course.builder().courseId(1L).title("자료구조").build());

    private final ProvenanceCollector collector = new ProvenanceCollector("gen-1", NOW, "Asia/Seoul", START, END, "AI", "m");

    @Test
    void 마감_참조는_수업_과제_사실에서_시각을_얻고_모르는_참조는_버린다() {
        String classRef = collector.mark(ProvenanceSourceType.NEXT_CLASS, 3L, null, null, ProvenanceRepresentation.SELECTED_FIELDS,
                Map.of(), "다음 수업").refId();
        String topicRef = collector.mark(ProvenanceSourceType.TOPIC, 44L, null, null, ProvenanceRepresentation.SELECTED_FIELDS,
                Map.of("title", "재귀"), "재귀").refId();
        String assignmentRef = collector.mark(ProvenanceSourceType.ASSIGNMENT, 9L, null, null,
                ProvenanceRepresentation.SELECTED_FIELDS, Map.of(), "과제").refId();
        Map<String, PlanResultNormalizer.DeadlineFact> facts = Map.of(
                classRef, new PlanResultNormalizer.DeadlineFact("CLASS", LocalDateTime.of(2026, 9, 16, 14, 0), null),
                assignmentRef, new PlanResultNormalizer.DeadlineFact("ASSIGNMENT", null, LocalDate.of(2026, 9, 18)));
        PlanDraftAiResult ai = new PlanDraftAiResult("t", null, null, null, null, List.of(
                item("수업 전 재귀", List.of(topicRef), classRef, null),
                item("과제 준비", List.of(topicRef), assignmentRef, null),
                item("제안 목표", List.of(), null, "2026-09-17T21:00"),
                item("지난 목표", List.of(), null, "2026-09-10T21:00"),
                item("없는 참조", List.of(), "s99", null)), null, null);

        PlanResultNormalizer.Normalized out = PlanResultNormalizer.normalize(ai, START, END, NOW, COURSES, collector.build(),
                facts, Map.of(), List.of(), List.of());

        assertThat(out.items().get(0).deadlineAt()).isEqualTo(LocalDateTime.of(2026, 9, 16, 14, 0));
        assertThat(out.items().get(0).deadlineSource()).isEqualTo("CLASS");
        assertThat(out.items().get(0).topicId()).isEqualTo(44L);
        assertThat(out.items().get(0).courseId()).isEqualTo(1L);
        assertThat(out.items().get(1).deadlineDate()).isEqualTo(LocalDate.of(2026, 9, 18));
        assertThat(out.items().get(1).deadlineAt()).isNull();
        assertThat(out.items().get(1).deadlineSource()).isEqualTo("ASSIGNMENT");
        assertThat(out.items().get(2).deadlineAt()).isEqualTo(LocalDateTime.of(2026, 9, 17, 21, 0));
        assertThat(out.items().get(2).deadlineSource()).isEqualTo("AI_PROPOSED");
        assertThat(out.evidence().get(2).aiEstimates()).anyMatch(s -> s.contains("완료 목표 시각을 제안함"));
        assertThat(out.items().get(3).deadlineAt()).isNull();
        assertThat(out.items().get(3).deadlineSource()).isNull();
        assertThat(out.items().get(4).deadlineAt()).isNull();
        assertThat(out.evidence().get(4).unknownRefCount()).isEqualTo(1);
    }

    @Test
    void 구간만_인용하면_그_구간이_실린_학습_항목으로_연결하고_완료_기준은_나눠_둔다() {
        String topicRef = collector.mark(ProvenanceSourceType.TOPIC, 44L, null, null, ProvenanceRepresentation.SELECTED_FIELDS,
                Map.of("title", "재귀"), "재귀").refId();
        String sectionRef = collector.mark(ProvenanceSourceType.MATERIAL_SECTION, 101L, null, null, ProvenanceRepresentation.EXCERPT,
                Map.of(), "구간", 44L, null).refId();
        PlanDraftAiResult ai = new PlanDraftAiResult("t", null, null, null, null, List.of(
                new PlanDraftAiResult.PlanDraftAiItem("재귀 문제", "문제 3개 풀기 · 완료: 3개 중 2개 설명", null, "practice", 30,
                        "MUST", 1L, null, "이유", List.of(sectionRef), null, null, "지난주 일부 수행 반영"),
                new PlanDraftAiResult.PlanDraftAiItem("재귀 개념", "설명 읽기", "핵심 3가지 말하기", "READ", 20, "SHOULD", 1L, null,
                        null, List.of(topicRef), null, null, null)), null, null);

        PlanResultNormalizer.Normalized out = PlanResultNormalizer.normalize(ai, START, END, NOW, COURSES, collector.build(),
                Map.of(), Map.of(), List.of(), List.of());

        assertThat(out.items().get(0).topicId()).isEqualTo(44L);
        assertThat(out.items().get(0).doneCriteria()).isEqualTo("3개 중 2개 설명");
        assertThat(out.items().get(0).description()).isEqualTo("문제 3개 풀기 · 완료: 3개 중 2개 설명");
        assertThat(out.items().get(0).actionType()).isEqualTo(ActionType.PRACTICE);
        assertThat(out.evidence().get(0).aiEstimates()).anyMatch(s -> s.contains("이전 실행 결과에서 반영"));
        assertThat(out.items().get(1).description()).isEqualTo("설명 읽기 · 완료: 핵심 3가지 말하기");
        assertThat(out.items().get(1).doneCriteria()).isEqualTo("핵심 3가지 말하기");
        assertThat(out.items().get(1).topicId()).isEqualTo(44L);
    }

    @Test
    void 기존_항목_결정은_계획_항목만_조정하고_전략에도_남는다() {
        ExecutionItem planned = ExecutionItem.builder().executionItemId(70L).title("연결 리스트").status(ExecutionStatus.PLANNED)
                .placementType(PlacementType.DATE_ONLY).scheduledDate(LocalDate.of(2026, 9, 15)).expectedMinutes(60).build();
        ExecutionItem done = ExecutionItem.builder().executionItemId(71L).title("배열").status(ExecutionStatus.DONE)
                .placementType(PlacementType.DATE_ONLY).scheduledDate(LocalDate.of(2026, 9, 15)).expectedMinutes(30).build();
        ExecutionItem unscheduled = ExecutionItem.builder().executionItemId(72L).title("스택").status(ExecutionStatus.PLANNED)
                .placementType(PlacementType.UNSCHEDULED).expectedMinutes(30).build();
        String r70 = collector.mark(ProvenanceSourceType.EXECUTION_ITEM_PLANNED, 70L, null, null,
                ProvenanceRepresentation.SELECTED_FIELDS, Map.of(), "70").refId();
        String r71 = collector.mark(ProvenanceSourceType.EXECUTION_ITEM_PLANNED, 71L, null, null,
                ProvenanceRepresentation.SELECTED_FIELDS, Map.of(), "71").refId();
        String r72 = collector.mark(ProvenanceSourceType.EXECUTION_ITEM_PLANNED, 72L, null, null,
                ProvenanceRepresentation.SELECTED_FIELDS, Map.of(), "72").refId();
        PlanDraftAiResult ai = new PlanDraftAiResult("t", null, null, null,
                new PlanDraftAiResult.StrategyOut("목표", "도달", "요약", List.of("결정"),
                        List.of(new PlanDraftAiResult.CourseOut(1L, 1, "집중", "이유"), new PlanDraftAiResult.CourseOut(9L, 2, "x", "y")),
                        List.of(new PlanDraftAiResult.DeferredOut("영어", "시간", List.of())),
                        List.of("가정"), List.of("질문 1", "질문 2"), List.of(), List.of()),
                List.of(item("새 항목", List.of(), null, null)),
                List.of(new PlanDraftAiResult.ExistingItemOut(r70, "REDUCE", 30, null, "겹침"),
                        new PlanDraftAiResult.ExistingItemOut(r71, "DROP", null, null, "끝남"),
                        new PlanDraftAiResult.ExistingItemOut(r72, "MOVE", null, "2026-09-18", "날짜 없음"),
                        new PlanDraftAiResult.ExistingItemOut(r70, "DROP", null, null, "중복 결정")), null);

        PlanResultNormalizer.Normalized out = PlanResultNormalizer.normalize(ai, START, END, NOW, COURSES, collector.build(),
                Map.of(), Map.of(r70, planned, r71, done, r72, unscheduled), List.of("서버 미읽음"), List.of("가용 시간이 달라졌다"));

        assertThat(out.adjustments()).hasSize(1);
        assertThat(out.adjustments().get(0).operation()).isEqualTo(ProposalOperation.REDUCE);
        assertThat(out.adjustments().get(0).expectedMinutes()).isEqualTo(30);
        assertThat(out.existingDecisions()).extracting(d -> d.executionItemId() + ":" + d.action()).containsExactly("70:REDUCE");
        assertThat(out.strategy().courses()).extracting(c -> c.courseId()).containsExactly(1L);
        assertThat(out.strategy().deferred()).extracting(d -> d.title()).containsExactly("영어");
        assertThat(out.strategy().openQuestions()).containsExactly("질문 1");
        assertThat(out.strategy().unreadNotes()).contains("서버 미읽음");
        assertThat(out.strategy().changes()).extracting(c -> c.what()).contains("가용 시간이 달라졌다");
        assertThat(out.strategy().existingDecisions()).hasSize(1);
    }

    @Test
    void 미룬_범위가_학습_항목을_가리키면_SKIP_취급으로도_남는다() {
        String topicRef = collector.mark(ProvenanceSourceType.TOPIC, 44L, null, null, ProvenanceRepresentation.SELECTED_FIELDS,
                Map.of("title", "재귀"), "재귀").refId();
        PlanDraftAiResult ai = new PlanDraftAiResult("t", "요약", null, null,
                new PlanDraftAiResult.StrategyOut(null, null, null, null, null,
                        List.of(new PlanDraftAiResult.DeferredOut("재귀는 다음 주", "시간이 적다", List.of(topicRef))),
                        null, null, null, null),
                List.of(item("항목", List.of(), null, null)), null, null);

        PlanResultNormalizer.Normalized out = PlanResultNormalizer.normalize(ai, START, END, NOW, COURSES, collector.build(),
                Map.of(), Map.of(), List.of(), List.of());

        assertThat(out.strategy().goal()).isEqualTo("요약");
        assertThat(out.strategy().topics()).hasSize(1);
        assertThat(out.strategy().topics().get(0).treatment()).isEqualTo(Treatment.SKIP);
        assertThat(out.strategy().topics().get(0).topicTitle()).isEqualTo("재귀");
        assertThat(out.strategy().deferred().get(0).topicId()).isEqualTo(44L);
    }

    @Test
    void 매일_반복은_날짜마다_따로_남고_같은_제목이라도_합쳐지지_않는다() {
        List<PlanDraftAiResult.PlanDraftAiItem> daily = new java.util.ArrayList<>();
        for (int d = 14; d <= 20; d++) {
            daily.add(new PlanDraftAiResult.PlanDraftAiItem("영어회화 · 15분 말하기", "한다 · 완료: 끝", null, "PRACTICE", 15,
                    "SHOULD", 1L, "2026-09-" + d, "매일 15분 합의", List.of(), null, null, null));
        }
        PlanDraftAiResult ai = new PlanDraftAiResult("t", null, null, null, null, daily, null, null);

        PlanResultNormalizer.Normalized out = PlanResultNormalizer.normalize(ai, START, END, NOW, COURSES, collector.build(),
                Map.of(), Map.of(), List.of(), List.of());

        assertThat(out.items()).hasSize(7);
        assertThat(out.items()).allSatisfy(i -> assertThat(i.placementType()).isEqualTo(PlacementType.DATE_ONLY));
        assertThat(out.items()).extracting(i -> i.earliestStartDate()).doesNotHaveDuplicates();
        assertThat(out.items()).extracting(i -> i.earliestStartDate())
                .containsExactly(LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 15), LocalDate.of(2026, 9, 16),
                        LocalDate.of(2026, 9, 17), LocalDate.of(2026, 9, 18), LocalDate.of(2026, 9, 19), LocalDate.of(2026, 9, 20));
    }

    private static PlanDraftAiResult.PlanDraftAiItem item(String title, List<String> refs, String deadlineRef, String target) {
        return new PlanDraftAiResult.PlanDraftAiItem(title, "한다 · 완료: 끝", null, null, 30, "SHOULD", 1L, null, "이유", refs,
                deadlineRef, target, null);
    }
}
