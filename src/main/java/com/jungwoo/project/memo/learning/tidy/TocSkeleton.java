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
 *   <li>트리가 비어 있을 때만 쓴다. 이미 트리가 있으면 {@link TocReconciler}가 확실히 대응된 토픽 아래의 빠진 항목만 낸다.</li>
 *   <li>최상위 장(또는 부) 하나가 변경 하나다 — 사용자가 장 단위로 빼거나 제목을 고칠 수 있다. 절·실습·요약은 그 아래 자식이다.</li>
 *   <li>깊이는 목차 그대로(최상위 0부터 {@link #MAX_TREE_DEPTH}단계 미만까지). 고정된 "부·장·절"로 자르지 않는다. 상한에 걸린 항목은
 *       넣지 않고 그 수를 이유 문구에 밝힌다(교재 목차 화면에는 그대로 보인다).</li>
 *   <li>tocLine은 목차 항목 열쇠다(웹은 원문 줄 번호, 업로드는 순번 — {@link TextbookService.TocSnapshot#keyAt}).</li>
 *   <li>수업에서 다뤘는지는 모른다. 골격은 "교재가 다루는 범위"이지 학습 완료도, 실제 수업 순서도 아니다.</li>
 * </ul>
 */
final class TocSkeleton {

    /** 학습 구조에 넣는 깊이 상한(단계 수). 넘는 항목과 그 아래는 넣지 않고 센다. */
    static final int MAX_TREE_DEPTH = 8;
    /** 한 번에 제안하는 항목 수 상한(목차 구조화 상한과 같다). */
    static final int MAX_NODES = 400;

    private TocSkeleton() {
    }

    /** 골격과 상한 때문에 넣지 않은 항목 수. */
    record Built(List<TopicChangeOp> ops, int excludedByDepth, int excludedByCount) {
    }

    static List<TopicChangeOp> build(TextbookService.TocSnapshot toc) {
        return buildWithCounts(toc).ops();
    }

    static Built buildWithCounts(TextbookService.TocSnapshot toc) {
        // 업로드 목차는 규칙상 3항목 이상만 목차로 본다. 웹 목차는 페이지가 목차 칸에 실은 것이라 짧아도(1~2개) 그대로 쓴다.
        int min = toc == null || !toc.isWeb() ? 3 : 1;
        if (toc == null || toc.entries() == null || toc.entries().size() < min) {
            return new Built(List.of(), 0, 0);
        }
        List<TextbookExtractor.TocEntry> entries = toc.entries();
        int minLevel = entries.stream().mapToInt(TextbookExtractor.TocEntry::level).min().orElse(0);

        List<Node> roots = new ArrayList<>();
        Deque<Node> stack = new ArrayDeque<>();
        int count = 0;
        int byDepth = 0;
        int byCount = 0;
        // 넣지 않은 항목의 깊이. 그 아래 항목도 넣지 않는다(부모 없이 위로 올라붙지 않게).
        Integer skippedBelow = null;
        boolean skippedForDepth = false;
        for (int i = 0; i < entries.size(); i++) {
            TextbookExtractor.TocEntry entry = entries.get(i);
            int depth = entry.level() - minLevel;
            if (skippedBelow != null && depth > skippedBelow) {
                if (skippedForDepth) {
                    byDepth++;
                } else {
                    byCount++;
                }
                continue;
            }
            skippedBelow = null;
            while (!stack.isEmpty() && stack.peek().depth >= depth) {
                stack.pop();
            }
            // 깊이 점프(장 바로 아래 소절)는 실제 부모 깊이 + 1로 둔다.
            int treeDepth = stack.isEmpty() ? 0 : stack.peek().treeDepth + 1;
            if (treeDepth >= MAX_TREE_DEPTH) {
                byDepth++;
                skippedBelow = depth;
                skippedForDepth = true;
                continue;
            }
            if (count >= MAX_NODES) {
                byCount++;
                skippedBelow = depth;
                skippedForDepth = false;
                continue;
            }
            count++;
            Node node = new Node("t" + count, entry, toc.keyAt(i), depth, treeDepth);
            if (stack.isEmpty()) {
                roots.add(node);
            } else {
                stack.peek().children.add(node);
            }
            stack.push(node);
        }
        String reason = reasonOf(toc, byDepth, byCount);
        List<TopicChangeOp> ops = new ArrayList<>();
        for (Node root : roots) {
            ops.add(toOp(root, toc.materialId(), reason, true, null, null));
        }
        return new Built(ops, byDepth, byCount);
    }

    static String reasonOf(TextbookService.TocSnapshot toc, int byDepth, int byCount) {
        String where = toc.label() != null ? toc.label()
                : toc.filename() == null ? "교재 목차" : "「" + toc.filename() + "」 목차";
        String reason = where + (toc.fromUnit() == null ? "" : "(p." + toc.fromUnit()
                + (toc.toUnit() != null && !toc.toUnit().equals(toc.fromUnit()) ? "~" + toc.toUnit() : "") + ")")
                + "에 있는 장·절이에요. 교재가 다루는 범위의 골격이고, 수업에서 다뤘는지는 따로 정해요";
        return reason + excludedNote(byDepth, byCount);
    }

    /** 상한 때문에 넣지 않은 항목이 있으면 그 수. 조용히 버리지 않는다. */
    static String excludedNote(int byDepth, int byCount) {
        if (byDepth + byCount == 0) {
            return "";
        }
        return ". " + (byDepth + byCount) + "개 항목은 너무 깊거나 많아 학습 구조에 넣지 않았어요(교재 목차 화면에는 보여요)";
    }

    /**
     * @param parentTopicId 기존 토픽 아래에 붙일 때 그 토픽(최상위 변경만)
     */
    static TopicChangeOp toOp(Node node, Long materialId, String reason, boolean top, Long parentTopicId, String by) {
        List<TopicChangeOp> children = new ArrayList<>();
        for (Node child : node.children) {
            children.add(toOp(child, materialId, null, false, null, by));
        }
        TextbookExtractor.TocEntry e = node.entry;
        String title = titleOf(e);
        String locator = e.page() == null ? "교재 목차" : "교재 p." + e.page();
        // tocLine = 목차 항목 열쇠. 정리안 ADD(TocOps)와 같은 길로 저장돼 같은 제목의 단원이 구분된다.
        return new TopicChangeOp(top ? TopicChangeOp.ADD : null, node.tempId, null, top ? parentTopicId : null, null, title,
                "SOURCE", locator, List.of(), "SOURCE", null, null,
                children.isEmpty() ? null : children, reason, null, null, null, materialId, null, "TOC")
                .fromToc(node.key, title, locator);
    }

    static String titleOf(TextbookExtractor.TocEntry e) {
        return ((e.number() == null || e.number().isBlank() ? "" : e.number() + " ") + e.title()).trim();
    }

    static final class Node {
        final String tempId;
        final TextbookExtractor.TocEntry entry;
        final List<Node> children = new ArrayList<>();
        final int key;
        final int depth;
        final int treeDepth;

        Node(String tempId, TextbookExtractor.TocEntry entry, int key, int depth, int treeDepth) {
            this.tempId = tempId;
            this.entry = entry;
            this.key = key;
            this.depth = depth;
            this.treeDepth = treeDepth;
        }
    }
}
