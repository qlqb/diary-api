package com.jungwoo.project.memo.learning.structure;

import com.jungwoo.project.memo.learning.domain.CourseTopic;
import com.jungwoo.project.memo.learning.domain.TopicStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 변경안 검증: 없는 id·순환·자기 병합·모르는 op는 거부하고, 적용 직전(strict)에는 하나라도 나쁘면 전체를
 * 거부한다(부분 적용 없음). 11번 표 11("잘못된 부모·순환·타 사용자·오래된 diff")의 결정적 부분이다.
 */
class TopicChangeOpsValidatorTest {

    private static CourseTopic topic(long id, Long parent) {
        return CourseTopic.builder().topicId(id).parentTopicId(parent).title("t" + id).orderIndex(0)
                .status(TopicStatus.ACTIVE).build();
    }

    // 1 ─ 2 ─ 3  (3은 2의 자식, 2는 1의 자식), 4 루트
    private final List<CourseTopic> tree = List.of(topic(1, null), topic(2, 1L), topic(3, 2L), topic(4, null));
    private final Set<Long> sections = Set.of(10L, 11L);

    private static TopicChangeOp op(String kind, Long topicId, Long parent, String title, List<Long> sectionIds) {
        return new TopicChangeOp(kind, null, topicId, parent, null, title, "SOURCE", null, sectionIds, "EXERCISE",
                null, null, null, null);
    }

    @Test
    void moveUnderOwnDescendant_isRejectedAsACycle() {
        TopicChangeOp cycle = op("MOVE", 1L, 3L, null, null);
        TopicChangeOpsValidator.Result lenient = TopicChangeOpsValidator.validate(List.of(cycle), tree, sections, false);
        assertThat(lenient.ops()).isEmpty();
        assertThat(lenient.rejected()).singleElement().asString().contains("순환");

        TopicChangeOp self = op("MOVE", 2L, 2L, null, null);
        assertThat(TopicChangeOpsValidator.validate(List.of(self), tree, sections, false).ops()).isEmpty();
    }

    @Test
    void moveToRoot_andMoveToAnotherBranch_areAccepted() {
        TopicChangeOp toRoot = op("MOVE", 3L, null, null, null);
        TopicChangeOp toOther = op("MOVE", 2L, 4L, null, null);
        TopicChangeOpsValidator.Result result = TopicChangeOpsValidator.validate(List.of(toRoot, toOther), tree, sections, false);
        assertThat(result.ops()).hasSize(2);
        assertThat(result.summary().move()).isEqualTo(2);
        assertThat(result.summary().structural()).isTrue();
    }

    @Test
    void unknownTopic_unknownSection_andUnknownOp_areDropped() {
        List<TopicChangeOp> raw = List.of(
                op("LINK", 99L, null, null, List.of(10L)),
                op("LINK", 1L, null, null, List.of(77L)),
                op("RENAME", 1L, null, "  ", null),
                op("DELETE", 1L, null, null, null),
                op("LINK", 1L, null, null, List.of(10L, 10L, 11L)));
        TopicChangeOpsValidator.Result result = TopicChangeOpsValidator.validate(raw, tree, sections, false);
        assertThat(result.ops()).hasSize(1);
        assertThat(result.ops().get(0).sectionIds()).containsExactly(10L, 11L);
        assertThat(result.rejected()).hasSize(4);
        assertThat(result.summary().structural()).isFalse();
    }

    @Test
    void mergeWithSelf_orAbsorbingASurvivor_isRejected() {
        TopicChangeOp selfMerge = new TopicChangeOp("MERGE", null, null, null, null, null, null, null, null, null,
                1L, List.of(1L), null, "dup");
        assertThat(TopicChangeOpsValidator.validate(List.of(selfMerge), tree, sections, false).ops()).isEmpty();

        TopicChangeOp first = new TopicChangeOp("MERGE", null, null, null, null, null, null, null, null, null,
                4L, List.of(3L), null, "dup");
        TopicChangeOp second = new TopicChangeOp("MERGE", null, null, null, null, null, null, null, null, null,
                1L, List.of(4L), null, "dup");
        TopicChangeOpsValidator.Result result = TopicChangeOpsValidator.validate(List.of(first, second), tree, sections, false);
        assertThat(result.ops()).hasSize(1);
        assertThat(result.rejected()).singleElement().asString().contains("살아남기로");
    }

    @Test
    void addUnderATempParent_resolvesOnlyWhenTheTempIdWasDeclaredEarlier() {
        TopicChangeOp parent = new TopicChangeOp("ADD", "n1", null, 1L, null, "새 항목", "SOURCE", "p.3",
                List.of(10L), "CONCEPT", null, null, null, null);
        TopicChangeOp child = new TopicChangeOp("ADD", "n2", null, null, "n1", "하위", "AI_DERIVED", null,
                List.of(), null, null, null, null, null);
        TopicChangeOp orphan = new TopicChangeOp("ADD", "n3", null, null, "nope", "고아", "SOURCE", null,
                List.of(), null, null, null, null, null);
        TopicChangeOpsValidator.Result result = TopicChangeOpsValidator.validate(List.of(parent, child, orphan), tree, sections, false);
        assertThat(result.ops()).hasSize(2);
        assertThat(result.summary().add()).isEqualTo(2);
        assertThat(result.rejected()).singleElement().asString().contains("tempId");
    }

    @Test
    void strictMode_rejectsEverythingWhenOneOpIsBad() {
        List<TopicChangeOp> raw = List.of(op("LINK", 1L, null, null, List.of(10L)), op("MOVE", 1L, 3L, null, null));
        TopicChangeOpsValidator.Result strict = TopicChangeOpsValidator.validate(raw, tree, sections, true);
        assertThat(strict.ops()).isEmpty();
        assertThat(strict.rejected()).isNotEmpty();
    }

    @Test
    void splitNeedsAtLeastTwoChildren() {
        TopicChangeOp one = new TopicChangeOp("SPLIT", null, 2L, null, null, null, null, null, null, null, null, null,
                List.of(new TopicChangeOp("ADD", "a", null, null, null, "하나", "SOURCE", null, List.of(10L), null,
                        null, null, null, null)), "r");
        assertThat(TopicChangeOpsValidator.validate(List.of(one), tree, sections, false).ops()).isEmpty();
        TopicChangeOp two = new TopicChangeOp("SPLIT", null, 2L, null, null, null, null, null, null, null, null, null,
                List.of(new TopicChangeOp("ADD", "a", null, null, null, "하나", "SOURCE", null, List.of(10L), null,
                                null, null, null, null),
                        new TopicChangeOp("ADD", "b", null, null, null, "둘", "SOURCE", null, List.of(11L), null,
                                null, null, null, null)), "r");
        TopicChangeOpsValidator.Result result = TopicChangeOpsValidator.validate(List.of(two), tree, sections, false);
        assertThat(result.ops()).hasSize(1);
        assertThat(result.summary().split()).isEqualTo(1);
    }
}
