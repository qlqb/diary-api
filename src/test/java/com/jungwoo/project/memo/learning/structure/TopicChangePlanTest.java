package com.jungwoo.project.memo.learning.structure;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 정리안의 실행 순서·변경 이름(changeId)·딸린 변경.
 *
 * <p>여기서 고정하는 것 셋:
 * <ol>
 *   <li>병합은 언제나 맨 뒤다. 그래야 흡수되는 항목이 이번에 얻을 연결을 다 받은 뒤 병합되어
 *       그 연결이 살아남는 항목으로 옮겨 간다. 모델이 준 순서에 기대지 않는다.</li>
 *   <li>changeId는 "무엇을 어떻게 바꾸는가"에서 나온다 — 제목을 고쳐도, 판이 바뀌어도 같다.
 *       그래야 사용자가 고친 제목·빼 둔 선택을 새 판으로 옮길 수 있다.</li>
 *   <li>새 항목 아래 새 항목은 부모를 빼면 자식도 빠져야 한다. 반쪽 적용을 만들지 않는다.</li>
 * </ol>
 */
class TopicChangePlanTest {

    private static TopicChangeOp op(String kind, Long topicId, String title) {
        return new TopicChangeOp(kind, null, topicId, null, null, title, "SOURCE", null, null, null,
                null, null, null, null);
    }

    private static TopicChangeOp link(long topicId, List<Long> sectionIds) {
        return new TopicChangeOp("LINK", null, topicId, null, null, null, null, null, sectionIds, null,
                null, null, null, null);
    }

    private static TopicChangeOp merge(long surviving, List<Long> absorbed) {
        return new TopicChangeOp("MERGE", null, null, null, null, null, null, null, null, null,
                surviving, absorbed, null, "중복");
    }

    private static TopicChangeOp add(String tempId, Long parentTopicId, String parentTempId, String title) {
        return new TopicChangeOp("ADD", tempId, null, parentTopicId, parentTempId, title, "SOURCE", null,
                List.of(), null, null, null, null, null);
    }

    @Test
    void 병합은_맨_뒤로_간다_흡수될_항목이_이번_연결을_다_받은_뒤에_합쳐지도록() {
        // 모델이 병합을 먼저 냈다. 그대로 실행하면 LINK가 이미 보관된 항목을 가리킨다.
        List<TopicChangeOp> ordered = TopicChangePlan.order(List.of(
                merge(1, List.of(2L)),
                link(2, List.of(10L)),
                op("RENAME", 3L, "새 이름")));

        assertThat(ordered).extracting(TopicChangeOp::op).containsExactly("RENAME", "LINK", "MERGE");
    }

    @Test
    void 같은_종류_안에서는_모델이_준_순서를_지킨다() {
        List<TopicChangeOp> ordered = TopicChangePlan.order(List.of(
                op("RENAME", 1L, "가"), op("RENAME", 2L, "나"), op("RENAME", 3L, "다")));

        assertThat(ordered).extracting(TopicChangeOp::topicId).containsExactly(1L, 2L, 3L);
    }

    @Test
    void 새_항목의_부모가_같은_정리안의_새_항목이면_부모가_앞에_선다() {
        // 모델이 자식을 먼저 냈다. tempId로 부모를 가리키므로 부모가 먼저 만들어져야 한다.
        List<TopicChangeOp> ordered = TopicChangePlan.order(List.of(
                add("n2", null, "n1", "자식"),
                add("n1", 5L, null, "부모")));

        assertThat(ordered).extracting(TopicChangeOp::tempId).containsExactly("n1", "n2");
    }

    @Test
    void changeId는_제목을_고쳐도_같다_사용자_편집이_자기_자신을_잃지_않도록() {
        String before = TopicChangePlan.signature(link(7, List.of(3L, 1L)));
        // 구간 순서가 달라져도 같은 변경이다.
        String after = TopicChangePlan.signature(link(7, List.of(1L, 3L)));

        assertThat(before).isEqualTo(after);
    }

    @Test
    void 새_항목의_changeId는_부모와_처음_제목으로_정해진다() {
        assertThat(TopicChangePlan.signature(add("n1", 5L, null, "스택")))
                .isEqualTo(TopicChangePlan.signature(add("n9", 5L, null, " 스택 ")));
        // 부모가 다르면 다른 변경이다.
        assertThat(TopicChangePlan.signature(add("n1", 5L, null, "스택")))
                .isNotEqualTo(TopicChangePlan.signature(add("n1", 6L, null, "스택")));
    }

    @Test
    void 뜻이_같은_변경이_둘이면_뒤엣것에_번호가_붙어_id가_겹치지_않는다() {
        TopicChangePlan.Plan plan = TopicChangePlan.of(List.of(
                add("n1", 5L, null, "스택"), add("n2", 5L, null, "스택")));

        assertThat(plan.ops()).extracting(TopicChangeOp::changeId).doesNotHaveDuplicates();
    }

    @Test
    void 새_항목_아래_새_항목은_부모에_딸린다() {
        TopicChangePlan.Plan plan = TopicChangePlan.of(List.of(
                add("n1", 5L, null, "부모"), add("n2", null, "n1", "자식")));
        String parent = plan.ops().get(0).changeId();
        String child = plan.ops().get(1).changeId();

        assertThat(plan.dependsOn()).containsEntry(child, List.of(parent));
        assertThat(plan.dependsOn()).doesNotContainKey(parent);
    }

    @Test
    void 부모를_빼고_자식만_고르면_무엇이_빠졌는지_말한다() {
        TopicChangePlan.Plan plan = TopicChangePlan.of(List.of(
                add("n1", 5L, null, "부모"), add("n2", null, "n1", "자식")));
        String parent = plan.ops().get(0).changeId();
        String child = plan.ops().get(1).changeId();

        Map<String, List<String>> missing = TopicChangePlan.missingDependencies(Set.of(child), plan.dependsOn());
        assertThat(missing).containsEntry(child, List.of(parent));

        // 둘 다 골랐으면 빠진 것이 없다. 부모만 골라도 마찬가지다(자식은 안 만들면 그만이다).
        assertThat(TopicChangePlan.missingDependencies(Set.of(parent, child), plan.dependsOn())).isEmpty();
        assertThat(TopicChangePlan.missingDependencies(Set.of(parent), plan.dependsOn())).isEmpty();
    }

    @Test
    void 순환하는_부모_지정이_있어도_멈추지_않는다() {
        // 검증이 따로 거르지만, 순서를 세우다 무한 재귀에 빠지면 거기까지 가지도 못한다.
        TopicChangeOp a = add("a", null, "b", "가");
        TopicChangeOp b = add("b", null, "a", "나");

        assertThat(TopicChangePlan.order(List.of(a, b))).hasSize(2);
    }
}
