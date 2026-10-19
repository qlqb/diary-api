package com.jungwoo.project.memo.learning.structure;

import com.jungwoo.project.memo.learning.domain.CourseTopic;
import com.jungwoo.project.memo.learning.domain.TopicStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 수업·범위 정정 작업과 위치를 정하는 이동의 검증·순서·이름표.
 *
 * <p>교재상의 위치(MOVE)·실제 수업 순서(CLASS)·계획 범위(SCOPE_EXCLUDE)는 다른 작업이다. 정정 작업은 트리를 건드리지 않고
 * 병합 뒤에 실행된다 — 병합으로 사라지는 항목을 가리키면 거절한다.
 */
class StructureCorrectionOpsTest {

    private static CourseTopic topic(long id, Long parent) {
        return CourseTopic.builder().topicId(id).parentTopicId(parent).title("t" + id).orderIndex(0)
                .status(TopicStatus.ACTIVE).build();
    }

    // 1(3장) · 2(5장) · 3(5.1, 2의 자식) · 4(4장)
    private final List<CourseTopic> tree = List.of(topic(1, null), topic(2, null), topic(3, 2L), topic(4, null));

    private static TopicChangeOp klass(Long topicId, Integer week, Long after) {
        return new TopicChangeOp("CLASS", null, topicId, null, null, null, null, null, null, null, null, null, null,
                "수업 순서 정정", null, after, week, null, null, "USER");
    }

    private static TopicChangeOp move(Long topicId, Long parent, Long after) {
        return new TopicChangeOp("MOVE", null, topicId, parent, null, null, null, null, null, null, null, null, null,
                "이동", null, after, null, null, null, "USER");
    }

    @Test
    void 실제_수업_순서와_주차_정정은_트리_작업이_아니고_주차_범위를_지킨다() {
        TopicChangeOpsValidator.Result result = TopicChangeOpsValidator.validate(List.of(
                klass(2L, 2, 0L), klass(1L, null, 2L), klass(4L, 31, null), klass(4L, null, null), klass(99L, 3, null)),
                tree, Set.of(), false);

        assertThat(result.ops()).extracting(TopicChangeOp::topicId).containsExactly(2L, 1L);
        assertThat(result.ops()).allSatisfy(op -> assertThat(op.isTreeOp()).isFalse());
        assertThat(result.rejected()).hasSize(3);
        assertThat(result.summary().structural()).as("정정은 구조 변경이 아니다").isFalse();
    }

    @Test
    void 병합으로_흡수되는_항목에_실제_수업_정정을_걸면_거절한다() {
        TopicChangeOp merge = new TopicChangeOp("MERGE", null, null, null, null, null, null, null, null, null,
                4L, List.of(1L), null, "같은 내용");
        List<TopicChangeOp> ordered = TopicChangePlan.order(List.of(klass(1L, 3, null), merge));

        assertThat(ordered).extracting(TopicChangeOp::op).containsExactly("MERGE", "CLASS");
        TopicChangeOpsValidator.Result strict = TopicChangeOpsValidator.validate(ordered, tree, Set.of(), true);
        assertThat(strict.ops()).isEmpty();
        assertThat(strict.rejected()).singleElement().asString().contains("CLASS");
    }

    @Test
    void 이동의_기준_항목은_새_부모의_하위여야_한다() {
        assertThat(TopicChangeOpsValidator.validate(List.of(move(4L, null, 1L)), tree, Set.of(), false).ops()).hasSize(1);
        assertThat(TopicChangeOpsValidator.validate(List.of(move(4L, null, 0L)), tree, Set.of(), false).ops()).hasSize(1);
        // 3은 2의 자식이지 루트가 아니다.
        assertThat(TopicChangeOpsValidator.validate(List.of(move(4L, null, 3L)), tree, Set.of(), false).rejected())
                .singleElement().asString().contains("기준 항목");
    }

    @Test
    void 위치가_다른_이동과_주차가_다른_정정은_서로_다른_변경이다() {
        String a = TopicChangePlan.signature(move(4L, null, 1L));
        String b = TopicChangePlan.signature(move(4L, null, 2L));
        String legacy = TopicChangePlan.signature(move(4L, null, null));
        assertThat(a).isNotEqualTo(b);
        assertThat(legacy).as("위치 없는 예전 이동은 이름표가 그대로다").isEqualTo("MOVE|4|null");
        assertThat(TopicChangePlan.signature(klass(1L, 2, null))).isNotEqualTo(TopicChangePlan.signature(klass(1L, 3, null)));
    }

    @Test
    void 새로_만드는_항목에_거는_연결은_그_항목을_함께_골라야_한다() {
        TopicChangeOp add = new TopicChangeOp("ADD", "t1", null, null, null, "1장 자료구조", "SOURCE", "교재 p.13",
                List.of(), null, null, null, null, "목차");
        TopicChangeOp link = new TopicChangeOp("LINK", "t1", null, null, null, null, null, null, List.of(10L),
                "CONCEPT", null, null, null, "강의 슬라이드");

        TopicChangeOpsValidator.Result checked = TopicChangeOpsValidator.validate(
                TopicChangePlan.order(List.of(link, add)), tree, Set.of(10L), true);
        assertThat(checked.rejected()).isEmpty();

        TopicChangePlan.Plan plan = TopicChangePlan.of(checked.ops());
        String addId = plan.ops().stream().filter(op -> "ADD".equals(op.op())).findFirst().orElseThrow().changeId();
        String linkId = plan.ops().stream().filter(op -> "LINK".equals(op.op())).findFirst().orElseThrow().changeId();
        assertThat(plan.dependsOn().get(linkId)).containsExactly(addId);
        assertThat(TopicChangePlan.missingDependencies(Set.of(linkId), plan.dependsOn())).containsKey(linkId);

        // 만들지 않는 항목(tempId 없음)을 가리키는 연결은 거절한다.
        TopicChangeOp dangling = new TopicChangeOp("LINK", "t9", null, null, null, null, null, null, List.of(10L),
                "CONCEPT", null, null, null, "없음");
        assertThat(TopicChangeOpsValidator.validate(List.of(dangling), tree, Set.of(10L), false).ops()).isEmpty();
    }
}
