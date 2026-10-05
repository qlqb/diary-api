package com.jungwoo.project.memo.learning.tidy;

import com.jungwoo.project.memo.course.textbook.TextbookExtractor;
import com.jungwoo.project.memo.course.textbook.TextbookService;
import com.jungwoo.project.memo.learning.structure.TopicChangeOp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 확보한 교재 목차를 학습 구조의 첫 골격(ADD 작업)으로 바꾼다. 모델을 부르지 않는다 — 목차에 적힌 장·절을 그대로 옮긴다.
 *
 * <ul>
 *   <li>트리가 비어 있을 때만 쓴다. 이미 트리가 있으면 서버가 골격을 밀어 넣지 않고, 모델이 목차를 보고 필요한 변경만 제안한다.</li>
 *   <li>최상위 장(또는 부) 하나가 변경 하나다 — 사용자가 장 단위로 빼거나 제목을 고칠 수 있다. 절은 그 아래 자식이다.</li>
 *   <li>깊이는 셋까지(부 → 장 → 절). 더 깊은 항목은 싣지 않는다(학습 구조가 목차의 모든 줄일 필요는 없다).</li>
 *   <li>수업에서 다뤘는지는 모른다. 골격은 "교재가 다루는 범위"이지 학습 완료도, 실제 수업 순서도 아니다.</li>
 * </ul>
 */
final class TocSkeleton {

    static final int MAX_DEPTH = 3;
    static final int MAX_NODES = 300;

    private TocSkeleton() {
    }

    static List<TopicChangeOp> build(TextbookService.TocSnapshot toc) {
        // 업로드 목차는 규칙상 3항목 이상만 목차로 본다. 웹 목차는 페이지가 목차 칸에 실은 것이라 짧아도(1~2개) 그대로 쓴다.
        int min = toc == null || !toc.isWeb() ? 3 : 1;
        if (toc == null || toc.entries() == null || toc.entries().size() < min) {
            return List.of();
        }
        int minLevel = toc.entries().stream().mapToInt(TextbookExtractor.TocEntry::level).min().orElse(0);
        String where = toc.label() != null ? toc.label()
                : toc.filename() == null ? "교재 목차" : "「" + toc.filename() + "」 목차";
        String reason = where + (toc.fromUnit() == null ? "" : "(p." + toc.fromUnit()
                + (toc.toUnit() != null && !toc.toUnit().equals(toc.fromUnit()) ? "~" + toc.toUnit() : "") + ")")
                + "에 있는 장·절이에요. 교재가 다루는 범위의 골격이고, 수업에서 다뤘는지는 따로 정해요";

        List<Node> roots = new ArrayList<>();
        Deque<Node> stack = new ArrayDeque<>();
        int count = 0;
        int seq = 0;
        for (TextbookExtractor.TocEntry entry : toc.entries()) {
            seq++;
            int depth = entry.level() - minLevel;
            if (depth >= MAX_DEPTH || count >= MAX_NODES) {
                continue;
            }
            count++;
            Node node = new Node("t" + count, entry, seq);
            while (!stack.isEmpty() && stack.peek().depth() >= depth) {
                stack.pop();
            }
            node.depth = depth;
            if (stack.isEmpty()) {
                roots.add(node);
            } else {
                stack.peek().children.add(node);
            }
            stack.push(node);
        }
        List<TopicChangeOp> ops = new ArrayList<>();
        for (Node root : roots) {
            ops.add(toOp(root, toc.materialId(), reason, true));
        }
        return ops;
    }

    private static TopicChangeOp toOp(Node node, Long materialId, String reason, boolean top) {
        List<TopicChangeOp> children = new ArrayList<>();
        for (Node child : node.children) {
            children.add(toOp(child, materialId, null, false));
        }
        TextbookExtractor.TocEntry e = node.entry;
        String title = (e.number() == null || e.number().isBlank() ? "" : e.number() + " ") + e.title();
        String locator = e.page() == null ? "교재 목차" : "교재 p." + e.page();
        // 원본 순번은 tocLine으로 남긴다 — 정리안 ADD(TocOps)와 같은 길로 저장돼 같은 제목의 단원이 구분된다.
        return new TopicChangeOp(top ? TopicChangeOp.ADD : null, node.tempId, null, null, null, title.trim(),
                "SOURCE", locator, List.of(), "SOURCE", null, null,
                children.isEmpty() ? null : children, reason, null, null, null, materialId, null, "TOC")
                .fromToc(node.seq, title.trim(), locator);
    }

    private static final class Node {
        final String tempId;
        final TextbookExtractor.TocEntry entry;
        final List<Node> children = new ArrayList<>();
        final int seq;
        int depth;

        Node(String tempId, TextbookExtractor.TocEntry entry, int seq) {
            this.tempId = tempId;
            this.entry = entry;
            this.seq = seq;
        }

        int depth() {
            return depth;
        }
    }
}
