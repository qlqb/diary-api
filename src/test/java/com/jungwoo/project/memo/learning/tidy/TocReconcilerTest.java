package com.jungwoo.project.memo.learning.tidy;

import com.jungwoo.project.memo.course.textbook.TextbookExtractor;
import com.jungwoo.project.memo.course.textbook.TextbookService;
import com.jungwoo.project.memo.course.textbook.TocResolver;
import com.jungwoo.project.memo.learning.domain.CourseTopic;
import com.jungwoo.project.memo.learning.structure.TopicChangeOp;
import com.jungwoo.project.memo.learning.structure.TopicChangePlan;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 트리가 있을 때 목차와 기존 토픽 맞추기. 열쇠(원문 해시 + 원문 줄)로 대응하고, 확실히 새 항목만 ADD한다. 모호하면 아무도 추가하지 않는다.
 * 목차는 합성이다.
 */
class TocReconcilerTest {

    static final String HASH = "a".repeat(64);
    static final String OTHER_HASH = "b".repeat(64);
    static final String BOOK = "isbn:9790000000001";

    /** 원문 줄: 2 Chapter 01, 7 01 가람, 9 실습 1-1, 11 연습문제, 15 Chapter 02, 16 01 나래, 18 연습문제 */
    static TextbookService.TocSnapshot toc() {
        return toc(List.of(
                new TextbookExtractor.TocEntry(1, "Chapter 01", "첫째 장", null, 2),
                new TextbookExtractor.TocEntry(2, "01", "가람 개요", null, 7),
                new TextbookExtractor.TocEntry(2, "실습 1-1", "가람 프로그램", null, 9),
                new TextbookExtractor.TocEntry(2, null, "연습문제", null, 11),
                new TextbookExtractor.TocEntry(1, "Chapter 02", "둘째 장", null, 15),
                new TextbookExtractor.TocEntry(2, "01", "나래 개요", null, 16),
                new TextbookExtractor.TocEntry(2, null, "연습문제", null, 18)));
    }

    static TextbookService.TocSnapshot toc(List<TextbookExtractor.TocEntry> entries) {
        return new TextbookService.TocSnapshot(null, null, null, null, null, entries,
                new TocResolver.Basis("WEB", null, null, 30L, 1, BOOK), "웹 목차", "WEB", null, null, "PAGE_FULL", HASH, 0);
    }

    static CourseTopic topic(long id, Long parent, String title, Integer line, String hash, String state) {
        return CourseTopic.builder().topicId(id).userId(1L).courseId(1L).parentTopicId(parent).title(title).orderIndex(0)
                .sourceTextbookKey(state == null ? null : BOOK)
                .tocKeyKind(line == null ? null : "WEB").tocKeyHash(hash).tocKeyLine(line).tocKeyState(state).build();
    }

    /** 골격으로 만든 장·절만(사용자가 장 제목을 고침). */
    static List<CourseTopic> skeletonTree() {
        List<CourseTopic> t = new ArrayList<>();
        t.add(topic(100, null, "1장 — 내가 고친 제목", 2, HASH, "SET"));
        t.add(topic(101, 100L, "01 가람 개요", 7, HASH, "SET"));
        t.add(topic(200, null, "Chapter 02 둘째 장", 15, HASH, "SET"));
        t.add(topic(201, 200L, "01 나래 개요", 16, HASH, "SET"));
        return t;
    }

    @Test
    void 같은_원문으로_대응된_토픽_아래의_빠진_하위항목만_올바른_부모로_추가한다() {
        TocReconciler.Result r = TocReconciler.reconcile(toc(), skeletonTree());

        assertThat(r.adds()).extracting(TopicChangeOp::parentTopicId, TopicChangeOp::tocLine)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(100L, 9), org.assertj.core.groups.Tuple.tuple(100L, 11),
                        org.assertj.core.groups.Tuple.tuple(200L, 18));
        assertThat(r.adds()).extracting(TopicChangeOp::title)
                .containsExactly("실습 1-1 가람 프로그램", "연습문제", "연습문제");
        assertThat(r.adds()).allSatisfy(op -> {
            assertThat(op.by()).isEqualTo("TOC");
            assertThat(op.isFromToc()).isTrue();
            assertThat(op.reason()).contains("지금 학습 구조에 없는 항목");
        });
        // 사용자가 장 제목을 고쳤어도 열쇠로 대응된다.
        assertThat(r.stateByKey().get(2)).isEqualTo(TocReconciler.State.MATCHED);
        assertThat(r.topicByKey().get(2)).isEqualTo(100L);
        assertThat(r.blockModelAdds()).isFalse();
        assertThat(r.note()).contains("빠진 항목 3개");
    }

    @Test
    void 다시_돌려도_이미_추가된_항목은_다시_내지_않는다() {
        List<CourseTopic> tree = skeletonTree();
        tree.add(topic(102, 100L, "실습 1-1 가람 프로그램", 9, HASH, "SET"));
        tree.add(topic(103, 100L, "연습문제", 11, HASH, "SET"));
        tree.add(topic(202, 200L, "연습문제", 18, HASH, "SET"));

        TocReconciler.Result r = TocReconciler.reconcile(toc(), tree);

        assertThat(r.adds()).isEmpty();
        assertThat(r.stateByKey().values()).containsOnly(TocReconciler.State.MATCHED);
    }

    @Test
    void 장이_통째로_없으면_트리가_이_목차의_토픽뿐일_때만_장째로_추가한다() {
        List<CourseTopic> tree = List.of(topic(100, null, "Chapter 01 첫째 장", 2, HASH, "SET"));

        TocReconciler.Result r = TocReconciler.reconcile(toc(), tree);

        TopicChangeOp chapter = r.adds().stream().filter(op -> op.tocLine() == 15).findFirst().orElseThrow();
        assertThat(chapter.parentTopicId()).isNull();
        assertThat(chapter.children()).extracting(TopicChangeOp::tocLine).containsExactly(16, 18);
    }

    @Test
    void 자료에서_만든_토픽이_섞이면_최상위는_정하지_않고_모델에게_남긴다() {
        List<CourseTopic> tree = new ArrayList<>(List.of(topic(100, null, "Chapter 01 첫째 장", 2, HASH, "SET")));
        tree.add(topic(900, null, "수업 자료에서 만든 토픽", null, null, null));

        TocReconciler.Result r = TocReconciler.reconcile(toc(), tree);

        // 대응된 장 아래 빠진 절은 서버가 추가한다.
        assertThat(r.adds()).extracting(TopicChangeOp::tocLine).containsExactly(7, 9, 11);
        assertThat(r.stateByKey().get(15)).isEqualTo(TocReconciler.State.OPEN);
        assertThat(r.modelMayAdd(15)).isTrue();
        assertThat(r.modelMayAdd(7)).isFalse(); // 서버가 추가함
        assertThat(r.modelMayAdd(2)).isFalse(); // 이미 있음
    }

    @Test
    void 같은_줄에_토픽_둘이면_그_항목과_아래는_보류하고_모델_추가도_모두_막는다() {
        List<CourseTopic> tree = new ArrayList<>(skeletonTree());
        tree.add(topic(300, null, "사용자가 바꾼 다른 이름", 2, HASH, "SET"));

        TocReconciler.Result r = TocReconciler.reconcile(toc(), tree);

        assertThat(r.stateByKey().get(2)).isEqualTo(TocReconciler.State.BLOCKED);
        assertThat(r.stateByKey().get(9)).isEqualTo(TocReconciler.State.BLOCKED);
        assertThat(r.adds()).extracting(TopicChangeOp::tocLine).containsExactly(18); // 무관한 장 아래는 그대로
        assertThat(r.blockModelAdds()).isTrue();
        assertThat(r.modelMayAdd(9)).isFalse();
        assertThat(r.note()).contains("짝을 확정하지 못한 토픽 2개");
    }

    @Test
    void 원문이_바뀐_열쇠나_확정_못_한_토픽이_있으면_그_토픽이_어느_항목인지_모르므로_모델_추가를_막는다() {
        List<CourseTopic> changed = new ArrayList<>(skeletonTree());
        changed.add(topic(400, null, "옛 원문의 장", 3, OTHER_HASH, "SET"));
        TocReconciler.Result r1 = TocReconciler.reconcile(toc(), changed);
        assertThat(r1.ambiguous()).isEqualTo(1);
        assertThat(r1.blockModelAdds()).isTrue();

        List<CourseTopic> missing = new ArrayList<>(skeletonTree());
        missing.add(topic(500, null, "열쇠 없는 옛 목차 토픽", null, null, "MISSING_SOURCE"));
        TocReconciler.Result r2 = TocReconciler.reconcile(toc(), missing);
        assertThat(r2.missingSource()).isEqualTo(1);
        assertThat(r2.blockModelAdds()).isTrue();
        // 서버 ADD는 확실히 대응된 토픽 아래만이라 그대로 낸다.
        assertThat(r2.adds()).extracting(TopicChangeOp::tocLine).containsExactly(9, 11, 18);
    }

    @Test
    void 같은_부모_아래_같은_제목의_토픽이_있으면_추가하지_않고_그_토픽을_부모로_삼는다() {
        List<CourseTopic> tree = new ArrayList<>(skeletonTree());
        // 사용자가 직접 만든 "실습 1-1 …"(열쇠 없음)
        tree.add(topic(600, 100L, "실습 1-1 가람 프로그램", null, null, null));

        TocReconciler.Result r = TocReconciler.reconcile(toc(), tree);

        assertThat(r.stateByKey().get(9)).isEqualTo(TocReconciler.State.COVERED);
        assertThat(r.adds()).extracting(TopicChangeOp::tocLine).containsExactly(11, 18);
    }

    @Test
    void 다른_책의_목차_토픽은_이_목차의_토픽이_아니다() {
        CourseTopic other = topic(700, null, "Unit 1 이전 교재", 1, OTHER_HASH, "SET");
        other.setSourceTextbookKey("isbn:9790000000099");
        List<CourseTopic> tree = new ArrayList<>(skeletonTree());
        tree.add(other);

        TocReconciler.Result r = TocReconciler.reconcile(toc(), tree);

        assertThat(r.ambiguous()).isZero();
        assertThat(r.blockModelAdds()).isFalse();
    }

    @Test
    void 같은_제목이라도_번호가_다르면_다른_항목이다() {
        assertThat(TocReconciler.normalize("Unit 1 What’s your name?"))
                .isNotEqualTo(TocReconciler.normalize("Unit 10 What’s your name?"));
        assertThat(TocReconciler.normalize("01 TCP/IP 개요")).isEqualTo(TocReconciler.normalize("1 TCP/IP 개요"));
    }

    @Test
    void 깊이_상한을_넘는_항목은_넣지_않고_수를_밝힌다() {
        List<TextbookExtractor.TocEntry> deep = new ArrayList<>();
        for (int level = 0; level < 10; level++) {
            deep.add(new TextbookExtractor.TocEntry(level, null, "깊이 " + level + " 항목", null, level + 1));
        }
        TextbookService.TocSnapshot toc = toc(deep);

        TocSkeleton.Built built = TocSkeleton.buildWithCounts(toc);

        int depth = 0;
        for (TopicChangeOp op = built.ops().get(0); op != null;
             op = op.children() == null ? null : op.children().get(0)) {
            depth++;
        }
        assertThat(depth).isEqualTo(TocSkeleton.MAX_TREE_DEPTH);
        assertThat(built.excludedByDepth()).isEqualTo(2);
        assertThat(built.ops().get(0).reason()).contains("2개 항목은 너무 깊거나 많아 학습 구조에 넣지 않았어요");
    }

    // ===== 열쇠 일관성: 원문 2·7·15행 =====

    @Test
    void 목차_항목_열쇠는_저장_정리안_확인_대응에서_모두_같은_원문_줄을_가리킨다() {
        TextbookService.TocSnapshot toc = toc(List.of(
                new TextbookExtractor.TocEntry(1, "Chapter 01", "첫째 장", null, 2),
                new TextbookExtractor.TocEntry(2, "01", "가람 개요", null, 7),
                new TextbookExtractor.TocEntry(1, "Chapter 02", "둘째 장", null, 15)));

        // 순번 2는 원문 7행이다 — 순번으로 찾으면 안 된다.
        assertThat(toc.keyAt(1)).isEqualTo(7);
        assertThat(toc.entryByKey(2).title()).isEqualTo("첫째 장");
        assertThat(toc.entryByKey(7).title()).isEqualTo("가람 개요");
        assertThat(toc.entryByKey(3)).isNull();
        assertThat(toc.ordinalByKey()).containsEntry(7, 2).containsEntry(15, 3);

        // 골격
        List<TopicChangeOp> skeleton = TocSkeleton.build(toc);
        assertThat(skeleton).extracting(TopicChangeOp::tocLine).containsExactly(2, 15);
        assertThat(skeleton.get(0).children()).extracting(TopicChangeOp::tocLine).containsExactly(7);

        // 모델 ADD tocLine 7 → 원문 7행 항목으로 제목을 다시 채운다. tocLine 2(순번 2라고 생각한 모델)는 원문 2행 = 장.
        TopicChangeOp add7 = new TopicChangeOp("ADD", "a", null, null, null, "모델 제목", "AI_DERIVED", null, null, null,
                null, null, null, "이유", null, null, null, null, null, null, 7);
        List<TopicChangeOp> checked = TocOps.apply(List.of(add7), toc, TocOps.Mode.TOC_ONLY, false);
        assertThat(checked).singleElement().satisfies(op -> {
            assertThat(op.tocLine()).isEqualTo(7);
            assertThat(op.title()).isEqualTo("01 가람 개요");
        });

        // 대응: 열쇠 7로 저장된 토픽은 원문 7행 항목과 짝이다.
        List<CourseTopic> tree = List.of(topic(1, null, "장", 2, HASH, "SET"), topic(2, 1L, "절", 7, HASH, "SET"),
                topic(3, null, "둘째", 15, HASH, "SET"));
        TocReconciler.Result r = TocReconciler.reconcile(toc, tree);
        assertThat(r.topicByKey()).containsEntry(7, 2L).containsEntry(2, 1L).containsEntry(15, 3L);
        assertThat(r.adds()).isEmpty();
    }

    @Test
    void 모델의_목차_ADD는_서버가_정하지_못한_항목에만_남고_부모는_목차_구조를_따른다() {
        List<CourseTopic> tree = new ArrayList<>(List.of(topic(100, null, "Chapter 01 첫째 장", 2, HASH, "SET")));
        tree.add(topic(900, null, "수업 자료 토픽", null, null, null));
        TocReconciler.Result r = TocReconciler.reconcile(toc(), tree);

        TopicChangeOp again = new TopicChangeOp("ADD", "a", null, null, null, "x", "AI_DERIVED", null, null, null,
                null, null, null, "이유", null, null, null, null, null, null, 9); // 서버가 이미 추가
        TopicChangeOp chapter2 = new TopicChangeOp("ADD", "b", null, null, null, "x", "AI_DERIVED", null, null, null,
                null, null, null, "이유", null, null, null, null, null, null, 15); // OPEN
        TopicChangeOp existing = new TopicChangeOp("ADD", "c", null, null, null, "x", "AI_DERIVED", null, null, null,
                null, null, null, "이유", null, null, null, null, null, null, 2); // 이미 있음

        List<TopicChangeOp> out = TocOps.apply(List.of(again, chapter2, existing), toc(), TocOps.Mode.TOC_ONLY, false, r,
                tree);

        assertThat(out).extracting(TopicChangeOp::tocLine).containsExactly(15);
    }

    @Test
    void 같은_최상위에_같은_제목_토픽이_있으면_모델_ADD를_버린다() {
        List<CourseTopic> tree = List.of(topic(900, null, "Chapter 02 둘째 장", null, null, null));
        TocReconciler.Result r = TocReconciler.reconcile(toc(), tree);
        TopicChangeOp chapter2 = new TopicChangeOp("ADD", "b", null, null, null, "x", "AI_DERIVED", null, null, null,
                null, null, null, "이유", null, null, null, null, null, null, 15);

        assertThat(TocOps.apply(List.of(chapter2), toc(), TocOps.Mode.TOC_ONLY, false, r, tree)).isEmpty();
    }

    // ===== 변경 식별 =====

    @Test
    void 장마다_반복되는_연습문제_ADD는_서로_다른_변경이고_자식이나_부모가_바뀌면_내용이_다르다() {
        TocReconciler.Result r = TocReconciler.reconcile(toc(), skeletonTree());
        TopicChangeOp ex1 = r.adds().stream().filter(op -> op.tocLine() == 11).findFirst().orElseThrow();
        TopicChangeOp ex2 = r.adds().stream().filter(op -> op.tocLine() == 18).findFirst().orElseThrow();

        assertThat(TopicChangePlan.of(r.adds()).ops()).extracting(TopicChangeOp::changeId).doesNotHaveDuplicates();
        assertThat(TopicChangePlan.contentKey(ex1)).isNotEqualTo(TopicChangePlan.contentKey(ex2));

        TopicChangeOp moved = new TopicChangeOp(ex1.op(), ex1.tempId(), null, 999L, null, ex1.title(), ex1.sourceType(),
                ex1.locator(), ex1.sectionIds(), ex1.role(), null, null, ex1.children(), ex1.reason(), null, null, null,
                ex1.materialId(), null, ex1.by(), ex1.tocLine());
        assertThat(TopicChangePlan.contentKey(moved)).isNotEqualTo(TopicChangePlan.contentKey(ex1));
    }

    @Test
    void 같은_제목의_형제가_목차_토픽이면_부모로_삼지_않고_보류한다() {
        List<CourseTopic> tree = new ArrayList<>(skeletonTree());
        tree.add(topic(610, 100L, "실습 1-1 가람 프로그램", null, null, "MISSING_SOURCE"));

        TocReconciler.Result r = TocReconciler.reconcile(toc(), tree);

        assertThat(r.stateByKey().get(9)).isEqualTo(TocReconciler.State.BLOCKED);
        assertThat(r.adds()).extracting(TopicChangeOp::tocLine).doesNotContain(9);
    }

    @Test
    void 모델이_서버_추가안과_같은_부모_같은_제목을_내면_빼고_그것을_가리키던_연결은_서버_항목으로_옮긴다() {
        TocReconciler.Result r = TocReconciler.reconcile(toc(), skeletonTree());
        TopicChangeOp modelAdd = new TopicChangeOp("ADD", "m1", null, 100L, null, "연습문제", "AI_DERIVED", null,
                List.of(5L), "PRACTICE", null, null, null, "자료에 문제가 있다");
        TopicChangeOp modelLink = new TopicChangeOp("LINK", "m1", null, null, null, null, null, null, List.of(6L),
                "PRACTICE", null, null, null, "같은 문제");

        List<TopicChangeOp> out = ProjectTidyWorker.withoutServerTocDuplicates(List.of(modelAdd, modelLink), r.adds());

        // 모델 ADD가 든 구간(5)도, 그것을 가리키던 LINK(6)도 서버 항목(toc11)으로 간다.
        assertThat(out).extracting(TopicChangeOp::op, TopicChangeOp::tempId, TopicChangeOp::sectionIds).containsExactly(
                org.assertj.core.groups.Tuple.tuple("LINK", "toc11", List.of(5L)),
                org.assertj.core.groups.Tuple.tuple("LINK", "toc11", List.of(6L)));
    }

    @Test
    void 열쇠가_같은_모델_ADD의_연결과_자식은_순서와_무관하게_서버_항목으로_옮겨진다() {
        TocReconciler.Result r = TocReconciler.reconcile(toc(), skeletonTree());
        // 자식이 부모보다 먼저 온다. 부모(m1)는 서버의 연습문제(열쇠 11)와 같고, 자식 m2의 제목은 서버에 없다.
        TopicChangeOp child = new TopicChangeOp("ADD", "m2", null, null, "m1", "모델이 붙인 하위 문제", "AI_DERIVED", null,
                List.of(7L), "PRACTICE", null, null, null, "자료에 있다");
        TopicChangeOp keyed = new TopicChangeOp("ADD", "m1", null, 100L, null, "모델 제목", "AI_DERIVED", null, null, null,
                null, null, List.of(new TopicChangeOp(null, "m3", null, null, null, "중첩 자식", "AI_DERIVED", null, null,
                null, null, null, null, null)), "이유", null, null, null, null, null, null, 11);
        TopicChangeOp link = new TopicChangeOp("LINK", "m1", null, null, null, null, null, null, List.of(8L), "PRACTICE",
                null, null, null, "같은 문제");

        List<TopicChangeOp> out = ProjectTidyWorker.withoutServerTocDuplicates(List.of(child, keyed, link), r.adds());

        assertThat(out).noneMatch(op -> "m1".equals(op.tempId()) && "ADD".equals(op.op()));
        assertThat(out).anySatisfy(op -> {
            assertThat(op.tempId()).isEqualTo("m2");
            assertThat(op.parentTempId()).isEqualTo("toc11");
        });
        assertThat(out).anySatisfy(op -> {
            assertThat(op.tempId()).isEqualTo("m3");
            assertThat(op.op()).isEqualTo("ADD");
            assertThat(op.parentTempId()).isEqualTo("toc11");
        });
        assertThat(out).anySatisfy(op -> {
            assertThat(op.op()).isEqualTo("LINK");
            assertThat(op.tempId()).isEqualTo("toc11");
            assertThat(op.sectionIds()).containsExactly(8L);
        });
    }
}
