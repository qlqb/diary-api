package com.jungwoo.project.memo.learning.structure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 변경 목록을 "사용자가 하나씩 고를 수 있는 것"으로 만든다. 순수 함수.
 *
 * <p>세 가지를 한다.
 *
 * <h2>1. 실행 순서 정하기</h2>
 * 모델이 준 순서를 그대로 쓰지 않는다. 병합이 앞에 오면 그 뒤의 연결·이름 변경이 이미 보관된
 * 항목을 가리키게 되고, 사용자가 일부만 골랐을 때 남는 순서가 달라져 "고른 것에 따라 되기도
 * 하고 안 되기도 하는" 적용이 된다. 그래서 언제나 같은 순서로 세운다:
 *
 * <pre>이름 변경 → 이동 → 추가 → 분할 → 연결 → 병합</pre>
 *
 * 병합을 맨 뒤에 두는 이유가 핵심이다. 흡수되는 항목이 이번에 얻을 연결을 다 받은 뒤에
 * 병합해야 그 연결이 살아남는 항목으로 함께 옮겨 간다.
 *
 * <h2>2. 변경마다 안정적인 이름(changeId) 붙이기</h2>
 * 사용자가 고친 제목과 빼 둔 선택은 이 id로 저장된다. 배열 순번으로 저장하면 정리안이 새 판으로
 * 바뀌는 순간 편집이 엉뚱한 변경에 붙는다. id는 "무엇을 어떻게 바꾸는가"에서 만들기 때문에
 * 같은 뜻의 변경이면 판이 달라도 같은 값이다 — 그래서 새 판으로 갈아탈 때 편집을 승계할 수 있다.
 * (제목 자체는 id에 넣지 않는다. 넣으면 제목을 고치는 순간 id가 바뀌어 편집이 자기 자신을
 * 잃는다.)
 *
 * <h2>3. 딸린 변경 찾기(dependsOn)</h2>
 * 새로 만드는 항목 아래에 또 새 항목을 두는 경우, 부모를 빼면 자식은 갈 곳이 없다. 화면이
 * 체크박스 하나만 풀어도 딸린 것을 함께 풀 수 있게 관계를 같이 준다. 서버도 적용 직전에 이
 * 관계가 지켜졌는지 확인하고, 아니면 무엇을 함께 빼야 하는지 말하며 거절한다.
 */
public final class TopicChangePlan {

    private TopicChangePlan() {
    }

    /** 실행 순서. 낮은 것이 먼저다. */
    private static final Map<String, Integer> ORDER = Map.of(
            TopicChangeOp.RENAME, 0,
            TopicChangeOp.MOVE, 1,
            TopicChangeOp.ADD, 2,
            TopicChangeOp.SPLIT, 3,
            TopicChangeOp.LINK, 4,
            TopicChangeOp.MERGE, 5);

    /**
     * 순서를 세우고 id를 붙인 결과.
     *
     * @param ops       실행 순서로 정렬되고 changeId가 붙은 변경들
     * @param dependsOn changeId → 이것을 고르려면 함께 골라야 하는 changeId들
     */
    public record Plan(List<TopicChangeOp> ops, Map<String, List<String>> dependsOn) {

        public TopicChangeOp byId(String changeId) {
            return ops.stream().filter(op -> changeId.equals(op.changeId())).findFirst().orElse(null);
        }
    }

    public static Plan of(List<TopicChangeOp> raw) {
        List<TopicChangeOp> ordered = order(raw);
        List<TopicChangeOp> withIds = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (TopicChangeOp op : ordered) {
            withIds.add(op.withChangeId(uniqueId(signature(op), used)));
        }
        return new Plan(List.copyOf(withIds), dependencies(withIds));
    }

    /**
     * 실행 순서. 같은 종류 안에서는 모델이 준 순서를 지키되, 새 항목은 부모가 먼저 오도록
     * 끌어올린다(tempId로 서로를 가리키기 때문이다).
     */
    public static List<TopicChangeOp> order(List<TopicChangeOp> raw) {
        List<TopicChangeOp> input = raw == null ? List.of() : raw;
        List<TopicChangeOp> sorted = new ArrayList<>(input);
        Map<TopicChangeOp, Integer> position = new HashMap<>();
        for (int i = 0; i < input.size(); i++) {
            position.putIfAbsent(input.get(i), i);
        }
        sorted.sort(Comparator
                .comparingInt((TopicChangeOp op) -> ORDER.getOrDefault(op.op(), ORDER.size()))
                .thenComparingInt(op -> position.getOrDefault(op, 0)));
        return liftParentsFirst(sorted);
    }

    /** ADD가 다른 ADD의 tempId를 부모로 가리키면 그 부모를 앞에 세운다. 순환은 그대로 둔다(검증이 거른다). */
    private static List<TopicChangeOp> liftParentsFirst(List<TopicChangeOp> ops) {
        Map<String, TopicChangeOp> byTempId = new HashMap<>();
        for (TopicChangeOp op : ops) {
            if (op.tempId() != null) {
                byTempId.putIfAbsent(op.tempId(), op);
            }
        }
        List<TopicChangeOp> out = new ArrayList<>();
        Set<TopicChangeOp> placed = new LinkedHashSet<>();
        Set<TopicChangeOp> visiting = new HashSet<>();
        for (TopicChangeOp op : ops) {
            place(op, byTempId, placed, visiting, out);
        }
        return out;
    }

    private static void place(TopicChangeOp op, Map<String, TopicChangeOp> byTempId, Set<TopicChangeOp> placed,
                              Set<TopicChangeOp> visiting, List<TopicChangeOp> out) {
        if (placed.contains(op) || !visiting.add(op)) {
            return;
        }
        TopicChangeOp parent = op.parentTempId() == null ? null : byTempId.get(op.parentTempId());
        if (parent != null && parent != op) {
            place(parent, byTempId, placed, visiting, out);
        }
        visiting.remove(op);
        if (placed.add(op)) {
            out.add(op);
        }
    }

    /**
     * 변경의 뜻. 제목·이유처럼 사용자가 고칠 수 있는 값은 넣지 않는다 — 제목을 고쳤다고 다른
     * 변경이 되는 것은 아니다.
     *
     * <p>새 항목(ADD)만은 제목을 넣는다. 부모와 종류만으로는 형제 둘을 구분할 수 없기 때문이다.
     * 대신 <b>처음 제안된 제목</b>이고, 사용자가 고친 값은 여기 들어가지 않는다(고친 값은 편집으로
     * 따로 저장된다).
     */
    static String signature(TopicChangeOp op) {
        String kind = op.op() == null ? "?" : op.op();
        return switch (kind) {
            case TopicChangeOp.LINK -> "LINK|" + op.topicId() + "|" + sortedIds(op.sectionIds());
            case TopicChangeOp.ADD -> "ADD|" + (op.parentTopicId() != null ? "t" + op.parentTopicId()
                    : op.parentTempId() != null ? "n" + op.parentTempId() : "root")
                    + "|" + normalizeTitle(op.title());
            case TopicChangeOp.RENAME -> "RENAME|" + op.topicId();
            case TopicChangeOp.MOVE -> "MOVE|" + op.topicId() + "|" + op.parentTopicId();
            case TopicChangeOp.MERGE -> "MERGE|" + op.survivingTopicId() + "|" + sortedIds(op.absorbedTopicIds());
            case TopicChangeOp.SPLIT -> "SPLIT|" + op.topicId();
            default -> kind + "|" + op.topicId() + "|" + normalizeTitle(op.title());
        };
    }

    /**
     * 이 변경이 <실제로 무엇을 하는가>의 전부. 판이 바뀔 때 편집을 그대로 옮겨도 되는지 볼 때 쓴다.
     *
     * <p>{@link #signature}와 다른 목적이다. signature는 "같은 것을 가리키는가"를 보는 이름표라
     * 일부러 좁다 — RENAME은 대상 항목만, SPLIT은 쪼갤 항목만 본다. 그래서 <b>같은 changeId인데
     * 내용이 다를 수 있다</b>: 같은 항목을 전혀 다른 이름으로 바꾸자는 제안, 자식 구성이 완전히
     * 달라진 분할, 근거가 바뀐 연결이 모두 같은 이름표를 받는다.
     *
     * <p>사용자의 편집(제목 고침·제외)은 그 내용에 붙은 판단이다. 이름표만 같다고 옮겨 놓으면
     * "내가 확인하지 않은 제안"에 내 결정이 붙는다. 그래서 승계할 때는 이 키로 한 번 더 본다.
     */
    public static String contentKey(TopicChangeOp op) {
        if (op == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(op.op() == null ? "?" : op.op())
                .append("|t").append(op.topicId())
                .append("|p").append(op.parentTopicId())
                .append("|n").append(op.parentTempId())
                .append("|s").append(op.survivingTopicId())
                .append("|a").append(sortedIds(op.absorbedTopicIds()))
                .append("|title=").append(normalizeTitle(op.title()))
                .append("|src=").append(op.sourceType() == null ? "" : op.sourceType())
                .append("|role=").append(op.role() == null ? "" : op.role())
                // 근거가 달라지면 같은 제안이 아니다. 무엇을 보고 한 말인지가 바뀐 것이다.
                .append("|sec=").append(sortedIds(op.sectionIds()));
        if (op.children() != null && !op.children().isEmpty()) {
            // 분할의 자식 구성. 개수만 세지 않는다 — 이름이 바뀌면 다른 분할이다.
            sb.append("|children=[");
            for (TopicChangeOp child : op.children()) {
                sb.append(contentKey(child)).append(';');
            }
            sb.append(']');
        }
        return sb.toString();
    }

    private static String sortedIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        return ids.stream().filter(java.util.Objects::nonNull).sorted()
                .map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
    }

    private static String normalizeTitle(String title) {
        return title == null ? "" : title.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /** 같은 뜻의 변경이 둘이면(같은 항목을 두 번 가리키는 등) 뒤엣것에 번호를 붙인다. */
    private static String uniqueId(String signature, Set<String> used) {
        String base = shortHash(signature);
        String candidate = base;
        int n = 2;
        while (!used.add(candidate)) {
            candidate = base + "-" + n++;
        }
        return candidate;
    }

    static String shortHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 12);
        } catch (Exception e) {
            // SHA-256이 없는 JVM은 없다. 그래도 id 없이 두느니 사람이 읽을 수 있는 값을 쓴다.
            return Integer.toHexString(value.hashCode());
        }
    }

    /**
     * changeId → 함께 골라야 하는 changeId들.
     *
     * <p>지금은 관계가 하나다: 새 항목의 부모가 같은 정리안의 새 항목인 경우. 병합·분할은
     * 실행 순서로 풀리므로(위 참고) 선택 관계가 생기지 않는다.
     */
    public static Map<String, List<String>> dependencies(List<TopicChangeOp> ops) {
        Map<String, String> ownerOfTempId = new HashMap<>();
        for (TopicChangeOp op : ops) {
            collectTempIdOwners(op, op.changeId(), ownerOfTempId);
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (TopicChangeOp op : ops) {
            if (op.parentTempId() == null) {
                continue;
            }
            String owner = ownerOfTempId.get(op.parentTempId());
            if (owner != null && !owner.equals(op.changeId())) {
                out.computeIfAbsent(op.changeId(), k -> new ArrayList<>()).add(owner);
            }
        }
        return out;
    }

    private static void collectTempIdOwners(TopicChangeOp op, String changeId, Map<String, String> out) {
        if (op.tempId() != null) {
            out.putIfAbsent(op.tempId(), changeId);
        }
        if (op.children() != null) {
            for (TopicChangeOp child : op.children()) {
                collectTempIdOwners(child, changeId, out);
            }
        }
    }

    /**
     * 고른 것이 딸린 변경을 빼놓았는가.
     *
     * @return 빠진 것들. 비어 있으면 적용해도 된다. changeId → 함께 골랐어야 하는 것
     */
    public static Map<String, List<String>> missingDependencies(Set<String> selected,
                                                                Map<String, List<String>> dependsOn) {
        Map<String, List<String>> missing = new LinkedHashMap<>();
        for (String changeId : selected) {
            for (String required : dependsOn.getOrDefault(changeId, List.of())) {
                if (!selected.contains(required)) {
                    missing.computeIfAbsent(changeId, k -> new ArrayList<>()).add(required);
                }
            }
        }
        return missing;
    }
}
