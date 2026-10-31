package com.jungwoo.project.memo.learning.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jungwoo.project.memo.learning.events.EventVocabulary.ObjectKind;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 한 판의 출력을 정규화한다 — 같은 뜻이면 같은 값이 되게(계획 §2.1).
 *
 * <ul>
 *   <li>시각은 밀리초, confidence는 소수 둘째 자리(DB 정밀도와 같게).</li>
 *   <li>payload는 키를 정렬한 JSON 문자열(키 순서 무관).</li>
 *   <li>같은 출력은 하나로, 순서는 모든 의미 칸을 이은 키로 정렬 — 다시 돌려도 output_no가 같다.</li>
 * </ul>
 */
public final class EventOutputs {

    private static final ObjectMapper CANONICAL = new ObjectMapper();

    private EventOutputs() {
    }

    /**
     * 정규화한 출력. {@link #key()}가 같으면 같은 뜻이다.
     *
     * @param payloadSources payload 안의 원천(검증용, 비교에는 payloadJson으로 이미 들어 있다)
     */
    public record Output(String actor, String verb, String objectKind, String objectRef, Integer from, Integer to,
                         Long topicId, String payloadJson, String evidence, BigDecimal confidence,
                         LocalDateTime claimAt, Long claimSeq, LocalDateTime occurredAt,
                         List<SourceRef> payloadSources, List<Long> eventRefs, List<Long> liveStuckRefs) {

        public String key() {
            return String.join("\u0001", actor, verb, objectKind, objectRef, str(from), str(to), str(topicId),
                    evidence, confidence == null ? "" : confidence.toPlainString(), str(claimAt), str(claimSeq),
                    str(occurredAt), payloadJson);
        }

        public SourceRef object() {
            return new SourceRef(ObjectKind.valueOf(objectKind), objectRef, from, to);
        }

        private static String str(Object o) {
            return o == null ? "" : o.toString();
        }
    }

    public static List<Output> normalize(List<EventDraft> drafts, ObjectMapper mapper) {
        Map<String, Output> unique = new LinkedHashMap<>();
        for (EventDraft d : drafts) {
            JsonNode tree = mapper.valueToTree(d.payload());
            Output o = new Output(d.actor().name(), d.verb().name(), d.object().kind().name(), d.object().ref(),
                    d.object().from(), d.object().to(), d.topicId(), canonical(tree), d.evidence().name(),
                    scale(d.confidence()), millis(d.claimAt()), d.claimSeq(), millis(d.occurredAt()),
                    d.payload().referencedSources(), d.payload().referencedEvents(), d.payload().liveStuckRefs());
            unique.putIfAbsent(o.key(), o);
        }
        List<Output> out = new ArrayList<>(unique.values());
        out.sort(Comparator.comparing(Output::key));
        return out;
    }

    /** 저장된 행을 같은 모양으로. 행은 output_no 순서 그대로 둔다. */
    public static List<Output> fromRows(List<LearningEvent> rows, ObjectMapper mapper) {
        List<Output> out = new ArrayList<>();
        for (LearningEvent e : rows) {
            JsonNode tree = parse(e.getPayload(), mapper);
            out.add(new Output(e.getActor(), e.getVerb(), e.getObjectKind(), e.getObjectRef(), e.getObjectFrom(),
                    e.getObjectTo(), e.getTopicId(), canonical(tree), e.getEvidence(), scale(e.getConfidence()),
                    millis(e.getClaimAt()), e.getClaimSeq(), millis(e.getOccurredAt()), sourcesIn(tree), List.of(),
                    List.of()));
        }
        return out;
    }

    public static boolean sameOutputs(List<Output> a, List<Output> b) {
        if (a.size() != b.size()) {
            return false;
        }
        List<String> ka = a.stream().map(Output::key).sorted().toList();
        List<String> kb = b.stream().map(Output::key).sorted().toList();
        return ka.equals(kb);
    }

    /** 키를 정렬한 JSON 문자열. */
    static String canonical(JsonNode node) {
        try {
            return CANONICAL.writeValueAsString(sorted(node));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("payload 직렬화 실패", e);
        }
    }

    private static JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            TreeMap<String, JsonNode> fields = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> f = it.next();
                if (!f.getValue().isNull()) {
                    fields.put(f.getKey(), sorted(f.getValue()));
                }
            }
            ObjectNode o = JsonNodeFactory.instance.objectNode();
            fields.forEach(o::set);
            return o;
        }
        if (node.isArray()) {
            ArrayNode a = JsonNodeFactory.instance.arrayNode();
            node.forEach(n -> a.add(sorted(n)));
            return a;
        }
        if (node.isNumber() && node.isIntegralNumber()) {
            return JsonNodeFactory.instance.numberNode(node.longValue());
        }
        return node;
    }

    private static JsonNode parse(String json, ObjectMapper mapper) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("저장된 payload를 읽지 못했다", e);
        }
    }

    /** payload 안에서 {kind, ref} 모양의 원천을 모두 찾는다. */
    static List<SourceRef> sourcesIn(JsonNode node) {
        List<SourceRef> out = new ArrayList<>();
        collect(node, out);
        return out;
    }

    private static void collect(JsonNode node, List<SourceRef> out) {
        if (node.isObject()) {
            JsonNode kind = node.get("kind");
            JsonNode ref = node.get("ref");
            if (kind != null && kind.isTextual() && ref != null && ref.isTextual()) {
                try {
                    out.add(new SourceRef(ObjectKind.valueOf(kind.asText()), ref.asText(), intOrNull(node.get("from")),
                            intOrNull(node.get("to"))));
                } catch (IllegalArgumentException ignored) {
                    // 원천 모양이 아닌 객체
                }
                return;
            }
            node.forEach(n -> collect(n, out));
        } else if (node.isArray()) {
            node.forEach(n -> collect(n, out));
        }
    }

    private static Integer intOrNull(JsonNode n) {
        return n == null || !n.isNumber() ? null : n.intValue();
    }

    static LocalDateTime millis(LocalDateTime t) {
        return t == null ? null : t.truncatedTo(ChronoUnit.MILLIS);
    }

    static BigDecimal scale(BigDecimal c) {
        return c == null ? null : c.setScale(2, RoundingMode.HALF_UP);
    }

    static boolean sameKey(Output a, Output b) {
        return Objects.equals(a.key(), b.key());
    }
}
