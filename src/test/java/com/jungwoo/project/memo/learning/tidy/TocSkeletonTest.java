package com.jungwoo.project.memo.learning.tidy;

import com.jungwoo.project.memo.course.textbook.TextbookExtractor;
import com.jungwoo.project.memo.course.textbook.TextbookService;
import com.jungwoo.project.memo.learning.structure.TopicChangeOp;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 목차 → 첫 골격. 장 하나가 변경 하나이고, 절은 그 아래 자식이다. 목차가 짧으면(3개 미만) 만들지 않는다. */
class TocSkeletonTest {

    private static TextbookExtractor.TocEntry e(int level, String number, String title, Integer page) {
        return new TextbookExtractor.TocEntry(level, number, title, page, 3);
    }

    @Test
    void 장마다_변경_하나이고_절은_자식이며_출처_자료와_쪽이_붙는다() {
        TextbookService.TocSnapshot toc = new TextbookService.TocSnapshot(31L, "자료구조_목차.pdf", "h", 3, 4, List.of(
                e(1, "CHAPTER 01", "자료구조와 알고리즘", 13), e(2, "1.1", "자료와 정보", 14),
                e(2, "1.2", "자료구조의 분류", 17), e(1, "CHAPTER 02", "배열", 41), e(2, "2.1", "배열의 개념", 42)));

        List<TopicChangeOp> ops = TocSkeleton.build(toc);

        assertThat(ops).hasSize(2);
        assertThat(ops).allSatisfy(op -> {
            assertThat(op.op()).isEqualTo("ADD");
            assertThat(op.sourceType()).isEqualTo("SOURCE");
            assertThat(op.materialId()).isEqualTo(31L);
            assertThat(op.by()).isEqualTo("TOC");
            assertThat(op.reason()).contains("자료구조_목차.pdf").contains("수업에서 다뤘는지는 따로");
        });
        assertThat(ops.get(0).title()).isEqualTo("CHAPTER 01 자료구조와 알고리즘");
        assertThat(ops.get(0).locator()).isEqualTo("교재 p.13");
        assertThat(ops.get(0).children()).extracting(TopicChangeOp::title).containsExactly("1.1 자료와 정보", "1.2 자료구조의 분류");
        assertThat(ops.get(1).children()).extracting(TopicChangeOp::tempId).containsExactly("t5");
    }

    @Test
    void 목차가_없거나_짧으면_골격을_만들지_않는다() {
        assertThat(TocSkeleton.build(null)).isEmpty();
        assertThat(TocSkeleton.build(new TextbookService.TocSnapshot(1L, "a", "h", 1, 1,
                List.of(e(1, "1", "하나", 1), e(1, "2", "둘", 2))))).isEmpty();
    }

    @Test
    void 번호와_기호를_뺀_제목으로_골격과_같은_장을_알아본다() {
        assertThat(ProjectTidyWorker.bare("CHAPTER 02 배열")).isEqualTo(ProjectTidyWorker.bare("배열"));
        assertThat(ProjectTidyWorker.bare("제3장 연결 리스트")).isEqualTo(ProjectTidyWorker.bare("연결리스트"));
    }
}
