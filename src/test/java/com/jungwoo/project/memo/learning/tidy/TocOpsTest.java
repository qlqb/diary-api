package com.jungwoo.project.memo.learning.tidy;

import com.jungwoo.project.memo.course.textbook.TextbookExtractor;
import com.jungwoo.project.memo.course.textbook.TextbookService;
import com.jungwoo.project.memo.course.textbook.TocResolver;
import com.jungwoo.project.memo.learning.structure.TopicChangeOp;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 모델이 낸 변경 중 목차에 기대는 것을 서버가 확인한다. 모드마다 허용하는 것이 다르다.
 */
class TocOpsTest {

    private static final TextbookService.TocSnapshot TOC = new TextbookService.TocSnapshot(null, null, null, null, null,
            List.of(new TextbookExtractor.TocEntry(1, "Unit 1", "What’s your name?", 6, 1),
                    new TextbookExtractor.TocEntry(1, "Unit 2", "I’m doing my homework right now", 14, 2)),
            new TocResolver.Basis("WEB", null, null, 7L, 1, "isbn:9788947288132"), "웹 목차", "WEB", null, null,
            "PAGE_FULL");

    private static TopicChangeOp add(String tempId, Integer line, List<Long> sections) {
        return new TopicChangeOp("ADD", tempId, null, null, null, "모델 제목", "AI_DERIVED", null, sections, null, null,
                null, null, "이유", null, null, null, null, null, null, line);
    }

    private static final TopicChangeOp RENAME = new TopicChangeOp("RENAME", null, 5L, null, null, "새 이름", null, null,
            null, null, null, null, null, "이유");
    private static final TopicChangeOp LINK = new TopicChangeOp("LINK", null, 5L, null, null, null, null, null,
            List.of(11L), "CONCEPT", null, null, null, "이유");

    @Test
    void 목차만_비교하는_모드는_목차_항목_ADD만_남기고_제목을_목차에서_다시_채운다() {
        List<TopicChangeOp> out = TocOps.apply(List.of(add("a", 2, null), add("b", 9, null), add("c", null, null),
                RENAME, LINK), TOC, TocOps.Mode.TOC_ONLY, false);

        assertThat(out).singleElement().satisfies(op -> {
            assertThat(op.tocLine()).isEqualTo(2);
            assertThat(op.title()).isEqualTo("Unit 2 I’m doing my homework right now");
            assertThat(op.locator()).isEqualTo("교재 p.14");
            assertThat(op.sourceType()).isEqualTo("SOURCE");
            assertThat(op.isFromToc()).isTrue();
        });
    }

    @Test
    void 혼합_모드는_자료_근거_변경을_그대로_두고_목차_근거_ADD만_확인한다() {
        List<TopicChangeOp> out = TocOps.apply(List.of(add("a", 1, null), add("s", null, List.of(11L)), RENAME, LINK),
                TOC, TocOps.Mode.MIXED, false);

        assertThat(out).extracting(TopicChangeOp::op).containsExactly("ADD", "ADD", "RENAME", "LINK");
        assertThat(out.get(1).tocLine()).isNull();
        assertThat(out.get(1).sectionIds()).containsExactly(11L);
    }

    @Test
    void 교재가_바뀐_직후에는_모델의_이름_바꾸기_이동_합치기_나누기를_버린다() {
        List<TopicChangeOp> out = TocOps.apply(List.of(RENAME, LINK, add("a", 1, null)), TOC, TocOps.Mode.MIXED, true);

        assertThat(out).extracting(TopicChangeOp::op).containsExactly("LINK", "ADD");
    }
}
