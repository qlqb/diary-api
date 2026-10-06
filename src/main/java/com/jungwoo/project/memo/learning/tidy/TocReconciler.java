package com.jungwoo.project.memo.learning.tidy;

import com.jungwoo.project.memo.course.textbook.TextbookExtractor;
import com.jungwoo.project.memo.course.textbook.TextbookService;
import com.jungwoo.project.memo.learning.domain.CourseTopic;
import com.jungwoo.project.memo.learning.structure.TopicChangeOp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 트리가 이미 있을 때 교재 목차와 기존 토픽을 서버가 맞춰 본다. 모델을 부르지 않고, 기존 토픽을 바꾸지 않는다 — 낼 수 있는 것은
 * <b>확실히 새 항목</b>의 ADD뿐이다.
 *
 * <p>토픽 → 목차 항목: 목차에서 만든 토픽은 만들 때의 열쇠(원문 해시 + 원문 줄)를 갖고 있다. 해시가 지금 목차와 같으면 그 줄의 항목이다
 * (사용자가 제목을 고쳤어도). 해시가 다르거나(원문이 바뀜) 그 줄이 없거나 한 줄에 둘이 걸리면 "모호", 목차에서 왔지만 열쇠를 확정하지
 * 못한 토픽은 "출처 확인 못 함"이다. 다른 책의 목차 토픽·자료·사용자 토픽은 목차 토픽이 아니다(PLAIN).
 *
 * <p>목차 항목의 상태(원문 순서대로, 부모가 먼저 정해진다):
 * <ul>
 *   <li>MATCHED — 대응된 토픽이 있다.</li>
 *   <li>COVERED — 대응 토픽은 없지만 같은 부모 토픽 아래 같은 제목의 토픽이 하나 있다(직접 만든 같은 항목). 그 토픽을 부모로 삼아
 *       아래를 본다.</li>
 *   <li>NEW — 확실히 새 항목. 부모 항목이 MATCHED·COVERED이고 그 토픽 아래 같은 제목이 없거나, 부모가 NEW이거나, 최상위인데 트리 전체가
 *       이 목차의 토픽뿐이다. <b>서버는 NEW만 ADD한다.</b></li>
 *   <li>BLOCKED — 모호한 토픽이 걸린 항목과 그 아래. 아무도(모델도) 추가하지 않는다.</li>
 *   <li>OPEN — 정할 근거가 없다(자료 기반 트리의 최상위 등). 서버는 추가하지 않고, 모델이 판단할 수 있다(지금 동작).</li>
 * </ul>
 * 트리에 모호·출처 확인 못 함 토픽이 하나라도 있으면 모델의 목차 ADD를 모두 막는다(그 토픽이 어느 항목인지 모른다).
 */
final class TocReconciler {

    enum State { MATCHED, COVERED, NEW, BLOCKED, OPEN }

    /**
     * @param adds            서버가 내는 ADD(최상위 하나 또는 대응된 토픽 아래 묶음 하나가 변경 하나)
     * @param stateByKey      목차 항목 열쇠 → 상태
     * @param topicByKey      MATCHED·COVERED 항목 → 토픽
     * @param ambiguous       모호한 토픽 수
     * @param missingSource   목차에서 왔지만 열쇠를 확정 못 한 토픽 수
     * @param blockModelAdds  모델의 목차 ADD를 모두 막는다
     */
    record Result(List<TopicChangeOp> adds, Map<Integer, State> stateByKey, Map<Integer, Long> topicByKey,
                  Map<Integer, Integer> parentKey, int ambiguous, int missingSource, boolean blockModelAdds,
                  int excludedByDepth, int excludedByCount) {

        static final Result NONE = new Result(List.of(), Map.of(), Map.of(), Map.of(), 0, 0, false, 0, 0);

        boolean isNone() {
            return stateByKey.isEmpty();
        }

        /** 모델에게 맡길 목차 항목이 있나(OPEN이고 막히지 않음). */
        boolean anyForModel() {
            return !blockModelAdds && stateByKey.values().stream().anyMatch(s -> s == State.OPEN);
        }

        /** 이 열쇠 항목의 목차상 부모가 대응된 토픽이면 그 토픽(모델 ADD의 부모를 목차 구조로 바로잡는다). */
        Long parentTopicOf(int key) {
            Integer p = parentKey.get(key);
            return p == null ? null : topicByKey.get(p);
        }

        int newCount() {
            return (int) stateByKey.values().stream().filter(s -> s == State.NEW).count();
        }

        int blockedCount() {
            return (int) stateByKey.values().stream().filter(s -> s == State.BLOCKED).count();
        }

        /** 모델이 이 열쇠의 ADD를 낼 수 있나. */
        boolean modelMayAdd(int key) {
            return !blockModelAdds && stateByKey.get(key) == State.OPEN;
        }

        /** 정리안 요약에 붙일 문장(없으면 빈 문자열). */
        String note() {
            List<String> parts = new ArrayList<>();
            int added = newCount();
            if (added > 0) {
                parts.add("교재 목차에서 빠진 항목 " + added + "개를 추가하자고 제안해요");
            }
            int unsure = ambiguous + missingSource;
            if (unsure > 0) {
                parts.add("목차 항목과 짝을 확정하지 못한 토픽 " + unsure + "개가 있어 그 부분의 목차 추가는 보류했어요(토픽은 그대로예요)");
            }
            String excluded = TocSkeleton.excludedNote(excludedByDepth, excludedByCount);
            if (!excluded.isEmpty()) {
                parts.add(excluded.substring(2));
            }
            return String.join(". ", parts);
        }
    }

    private TocReconciler() {
    }

    static Result reconcile(TextbookService.TocSnapshot toc, List<CourseTopic> topics) {
        if (toc == null || toc.entries() == null || toc.entries().isEmpty() || topics == null || topics.isEmpty()
                || toc.keyHash() == null) {
            return Result.NONE;
        }
        List<TextbookExtractor.TocEntry> entries = toc.entries();
        int n = entries.size();
        String bookKey = toc.basis() == null ? null : toc.basis().bookKey();

        // 1. 토픽 → 항목
        Map<Integer, List<CourseTopic>> claims = new HashMap<>();
        int ambiguous = 0;
        int missing = 0;
        int plain = 0;
        for (CourseTopic t : topics) {
            boolean otherBook = t.getSourceTextbookKey() != null && bookKey != null
                    && !Objects.equals(t.getSourceTextbookKey(), bookKey);
            if ("SET".equals(t.getTocKeyState()) && !otherBook) {
                boolean sameSource = Objects.equals(toc.keyKind(), t.getTocKeyKind())
                        && Objects.equals(toc.keyHash(), t.getTocKeyHash()) && t.getTocKeyLine() != null;
                int idx = sameSource ? toc.indexOfKey(t.getTocKeyLine()) : -1;
                if (idx < 0) {
                    ambiguous++;
                } else {
                    claims.computeIfAbsent(idx, k -> new ArrayList<>()).add(t);
                }
            } else if ("MISSING_SOURCE".equals(t.getTocKeyState()) && !otherBook) {
                missing++;
            } else {
                plain++;
            }
        }
        Map<Integer, CourseTopic> matched = new HashMap<>();
        Set<Integer> contested = new HashSet<>();
        for (Map.Entry<Integer, List<CourseTopic>> c : claims.entrySet()) {
            if (c.getValue().size() == 1) {
                matched.put(c.getKey(), c.getValue().get(0));
            } else {
                contested.add(c.getKey());
                ambiguous += c.getValue().size();
            }
        }
        boolean wholeTreeIsThisToc = ambiguous == 0 && missing == 0 && plain == 0;

        // 토픽 트리(부모 → 자식)와 깊이
        Map<Long, List<CourseTopic>> childrenOf = new HashMap<>();
        Map<Long, CourseTopic> byId = new HashMap<>();
        for (CourseTopic t : topics) {
            byId.put(t.getTopicId(), t);
            childrenOf.computeIfAbsent(t.getParentTopicId() == null ? 0L : t.getParentTopicId(), k -> new ArrayList<>()).add(t);
        }

        // 2. 항목 상태(원문 순서 = 부모가 먼저)
        int[] parent = parents(entries);
        State[] state = new State[n];
        Long[] topicOf = new Long[n];
        Set<Long> coveredTopics = new HashSet<>();
        for (int i = 0; i < n; i++) {
            int p = parent[i];
            if (matched.containsKey(i)) {
                state[i] = State.MATCHED;
                topicOf[i] = matched.get(i).getTopicId();
                continue;
            }
            if (contested.contains(i) || p >= 0 && state[p] == State.BLOCKED) {
                state[i] = State.BLOCKED;
                continue;
            }
            if (p >= 0 && state[p] == State.NEW) {
                state[i] = State.NEW;
                continue;
            }
            Long parentTopic;
            if (p < 0) {
                if (!wholeTreeIsThisToc) {
                    state[i] = State.OPEN;
                    continue;
                }
                parentTopic = 0L;
            } else if (state[p] == State.MATCHED || state[p] == State.COVERED) {
                parentTopic = topicOf[p];
            } else {
                state[i] = State.OPEN;
                continue;
            }
            List<CourseTopic> same = sameTitle(childrenOf.getOrDefault(parentTopic, List.of()), entries.get(i));
            if (same.isEmpty()) {
                state[i] = State.NEW;
            } else if (same.size() == 1 && same.get(0).getTocKeyState() == null
                    && coveredTopics.add(same.get(0).getTopicId())) {
                // 직접 만든 같은 항목(목차 출처 없음)만 부모로 삼는다. 목차 토픽(다른 항목에 대응·모호·확정 못 함)은 보류한다.
                state[i] = State.COVERED;
                topicOf[i] = same.get(0).getTopicId();
            } else {
                state[i] = State.BLOCKED;
            }
        }

        // 3. NEW 묶음 → ADD
        List<TopicChangeOp> adds = new ArrayList<>();
        int[] counter = {0};
        int[] excluded = {0, 0}; // 깊이, 개수
        for (int i = 0; i < n; i++) {
            if (state[i] != State.NEW || parent[i] >= 0 && state[parent[i]] == State.NEW) {
                continue;
            }
            Long parentTopic = parent[i] < 0 ? null : topicOf[parent[i]];
            int depth = parentTopic == null ? 0 : depthOf(parentTopic, byId) + 1;
            TocSkeleton.Node node = subtree(toc, entries, parent, state, i, depth, counter, excluded);
            if (node == null) {
                continue;
            }
            adds.add(TocSkeleton.toOp(node, toc.materialId(), null, true, parentTopic, "TOC"));
        }
        String reason = TocSkeleton.reasonOf(toc, excluded[0], excluded[1])
                .replace("에 있는 장·절이에요.", "에 있는데 지금 학습 구조에 없는 항목이에요.");
        List<TopicChangeOp> withReason = new ArrayList<>();
        for (TopicChangeOp op : adds) {
            withReason.add(new TopicChangeOp(op.op(), op.tempId(), op.topicId(), op.parentTopicId(), op.parentTempId(),
                    op.title(), op.sourceType(), op.locator(), op.sectionIds(), op.role(), op.survivingTopicId(),
                    op.absorbedTopicIds(), op.children(), reason, op.changeId(), op.afterTopicId(), op.week(),
                    op.materialId(), op.label(), op.by(), op.tocLine()));
        }

        Map<Integer, State> stateByKey = new HashMap<>();
        Map<Integer, Long> topicByKey = new HashMap<>();
        Map<Integer, Integer> parentKey = new HashMap<>();
        for (int i = 0; i < n; i++) {
            int key = toc.keyAt(i);
            stateByKey.put(key, state[i]);
            if (topicOf[i] != null) {
                topicByKey.put(key, topicOf[i]);
            }
            if (parent[i] >= 0) {
                parentKey.put(key, toc.keyAt(parent[i]));
            }
        }
        return new Result(withReason, stateByKey, topicByKey, parentKey, ambiguous, missing, ambiguous > 0 || missing > 0,
                excluded[0], excluded[1]);
    }

    /** NEW 항목 i와 그 아래 NEW 항목들을 골격 노드로. 깊이·개수 상한에 걸리면 넣지 않고 센다. */
    private static TocSkeleton.Node subtree(TextbookService.TocSnapshot toc, List<TextbookExtractor.TocEntry> entries,
                                            int[] parent, State[] state, int i, int treeDepth, int[] counter, int[] excluded) {
        if (treeDepth >= TocSkeleton.MAX_TREE_DEPTH || counter[0] >= TocSkeleton.MAX_NODES) {
            int skipped = 1 + descendants(parent, i, entries.size());
            excluded[treeDepth >= TocSkeleton.MAX_TREE_DEPTH ? 0 : 1] += skipped;
            return null;
        }
        counter[0]++;
        TocSkeleton.Node node = new TocSkeleton.Node("toc" + toc.keyAt(i), entries.get(i), toc.keyAt(i), entries.get(i).level(),
                treeDepth);
        for (int j = i + 1; j < entries.size() && isDescendant(parent, j, i); j++) {
            if (parent[j] == i && state[j] == State.NEW) {
                TocSkeleton.Node child = subtree(toc, entries, parent, state, j, treeDepth + 1, counter, excluded);
                if (child != null) {
                    node.children.add(child);
                }
            }
        }
        return node;
    }

    private static int descendants(int[] parent, int i, int n) {
        int count = 0;
        for (int j = i + 1; j < n && isDescendant(parent, j, i); j++) {
            count++;
        }
        return count;
    }

    private static boolean isDescendant(int[] parent, int j, int ancestor) {
        for (int p = parent[j]; p >= 0; p = parent[p]) {
            if (p == ancestor) {
                return true;
            }
        }
        return false;
    }

    /** 항목마다 목차상 부모(앞쪽에서 가장 가까운, 깊이가 더 얕은 항목). 없으면 -1. */
    static int[] parents(List<TextbookExtractor.TocEntry> entries) {
        int[] parent = new int[entries.size()];
        java.util.Deque<Integer> stack = new java.util.ArrayDeque<>();
        for (int i = 0; i < entries.size(); i++) {
            int level = entries.get(i).level();
            while (!stack.isEmpty() && entries.get(stack.peek()).level() >= level) {
                stack.pop();
            }
            parent[i] = stack.isEmpty() ? -1 : stack.peek();
            stack.push(i);
        }
        return parent;
    }

    private static int depthOf(Long topicId, Map<Long, CourseTopic> byId) {
        int depth = 0;
        CourseTopic t = byId.get(topicId);
        Set<Long> seen = new HashSet<>();
        while (t != null && t.getParentTopicId() != null && seen.add(t.getTopicId())) {
            depth++;
            t = byId.get(t.getParentTopicId());
        }
        return depth;
    }

    private static List<CourseTopic> sameTitle(List<CourseTopic> siblings, TextbookExtractor.TocEntry entry) {
        String want = normalize(TocSkeleton.titleOf(entry));
        List<CourseTopic> out = new ArrayList<>();
        for (CourseTopic t : siblings) {
            if (normalize(t.getTitle()).equals(want)) {
                out.add(t);
            }
        }
        return out;
    }

    /** 제목 비교용: 대소문자·공백·구두점을 지우고, 앞 번호의 0을 뗀다("01 소켓" = "1 소켓"). 번호가 다르면 다르다. */
    static String normalize(String title) {
        if (title == null) {
            return "";
        }
        String t = title.toLowerCase(java.util.Locale.ROOT).replaceAll("(?<!\\d)0+(\\d)", "$1");
        return t.replaceAll("[\\p{Punct}\\s·…‥ㆍ’‘“”]", "");
    }
}
