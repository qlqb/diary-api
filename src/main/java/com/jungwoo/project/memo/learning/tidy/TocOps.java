package com.jungwoo.project.memo.learning.tidy;

import com.jungwoo.project.memo.course.textbook.TextbookExtractor;
import com.jungwoo.project.memo.course.textbook.TextbookService;
import com.jungwoo.project.memo.learning.domain.CourseTopic;
import com.jungwoo.project.memo.learning.structure.TopicChangeOp;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 모델이 낸 변경 중 교재 목차에 기대는 것을 서버가 확인하고 표시한다. 모델이 붙인 표시를 믿지 않는다.
 *
 * <ul>
 *   <li>tocLine이 붙은 ADD: 그 번호의 목차 항목이 실제로 있어야 한다. 제목·쪽·출처는 목차에서 다시 채운다(모델이 고친 제목을
 *       쓰지 않는다). 없는 번호면 버린다.</li>
 *   <li>목차만 비교하는 모드(트리가 있고 읽을 자료 구간이 없음): 목차 항목 ADD만 남긴다.</li>
 *   <li>혼합 모드(자료 구간도 있음): 자료 근거 변경은 기존 규칙 그대로, 목차 근거 ADD에만 tocLine을 요구한다.</li>
 *   <li>교재가 바뀐 직후(트리에 다른 책의 목차 항목이 있음): 모델의 이름 바꾸기·이동·합치기·나누기를 버린다 — 이전 교재 항목을
 *       새 책에 맞춰 고치거나 강제로 합치지 않는다. 사용자가 직접 낸 변경은 이 검사를 거치지 않는다.</li>
 * </ul>
 */
final class TocOps {

    enum Mode { NONE, SKELETON, TOC_ONLY, MIXED }

    private TocOps() {
    }

    static Mode modeOf(ProjectTidyInputBuilder.Input input, TextbookService.TocSnapshot toc) {
        if (toc == null || toc.entries() == null || toc.entries().isEmpty()) {
            return Mode.NONE;
        }
        if (input.topics().isEmpty()) {
            return Mode.SKELETON;
        }
        return input.sections().isEmpty() ? Mode.TOC_ONLY : Mode.MIXED;
    }

    /** 트리에 지금 목차와 다른 책의 목차 항목이 있으면 그 책 열쇠(교재가 바뀌었다), 없으면 null. */
    static String switchedFrom(ProjectTidyInputBuilder.Input input, TextbookService.TocSnapshot toc) {
        if (toc == null || toc.basis() == null || toc.basis().bookKey() == null) {
            return null;
        }
        for (CourseTopic topic : input.topics()) {
            if (topic.getSourceTextbookKey() != null && !Objects.equals(topic.getSourceTextbookKey(), toc.basis().bookKey())) {
                return topic.getSourceTextbookKey();
            }
        }
        return null;
    }

    static List<TopicChangeOp> apply(List<TopicChangeOp> ops, TextbookService.TocSnapshot toc, Mode mode,
                                     boolean switched) {
        List<TopicChangeOp> out = new ArrayList<>();
        for (TopicChangeOp op : ops == null ? List.<TopicChangeOp>of() : ops) {
            String kind = op.op() == null ? TopicChangeOp.ADD : op.op().toUpperCase(java.util.Locale.ROOT);
            if (switched && (TopicChangeOp.RENAME.equals(kind) || TopicChangeOp.MOVE.equals(kind)
                    || TopicChangeOp.MERGE.equals(kind) || TopicChangeOp.SPLIT.equals(kind))) {
                continue;
            }
            if (TopicChangeOp.ADD.equals(kind)) {
                TopicChangeOp checked = checkAdd(op, toc, mode);
                if (checked != null) {
                    out.add(checked);
                }
                continue;
            }
            if (mode == Mode.TOC_ONLY) {
                continue; // 목차만 비교할 때는 목차 항목 ADD 말고 낼 것이 없다(연결할 자료 구간이 없다).
            }
            out.add(op);
        }
        return out;
    }

    private static TopicChangeOp checkAdd(TopicChangeOp op, TextbookService.TocSnapshot toc, Mode mode) {
        List<TopicChangeOp> children = new ArrayList<>();
        for (TopicChangeOp child : op.children() == null ? List.<TopicChangeOp>of() : op.children()) {
            TopicChangeOp c = checkAdd(child, toc, mode);
            if (c != null) {
                children.add(c);
            }
        }
        TopicChangeOp base = op.withChildren(children.isEmpty() ? null : children);
        if (op.tocLine() != null) {
            List<TextbookExtractor.TocEntry> entries = toc == null ? List.of() : toc.entries();
            int line = op.tocLine();
            if (line < 1 || line > entries.size()) {
                return null; // 없는 목차 항목
            }
            TextbookExtractor.TocEntry e = entries.get(line - 1);
            String title = ((e.number() == null || e.number().isBlank() ? "" : e.number() + " ") + e.title()).trim();
            return base.fromToc(line, title, e.page() == null ? "교재 목차" : "교재 p." + e.page());
        }
        if (mode == Mode.TOC_ONLY) {
            return null; // 목차에 없는 새 항목은 근거가 없다
        }
        return base;
    }
}
