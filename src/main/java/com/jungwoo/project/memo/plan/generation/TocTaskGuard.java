package com.jungwoo.project.memo.plan.generation;

import com.jungwoo.project.memo.course.textbook.TocItemKind;
import com.jungwoo.project.memo.plan.provenance.ProvenanceSourceType;
import com.jungwoo.project.memo.plan.provenance.ProvidedSource;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 목차 제목만 있는 실습·연습문제로 문제 내용을 지어내지 못하게 한다.
 *
 * <p>계획 항목이 인용한 목차 토픽 중 실습·연습문제가 있고, <b>그 토픽을 위해 전달한 자료 본문 구간</b>을 그 항목이 인용하지 않았으면
 * 모델이 쓴 제목·설명·완료 기준·이유를 쓰지 않고 교재를 가리키는 서버 문구로 바꾼다. 다른 장의 본문을 하나 인용한 것으로는 풀리지
 * 않는다(그 본문은 이 실습·연습문제의 문제가 아니다). 프롬프트 지시만으로는 막을 수 없어서 서버가 정한다.
 */
final class TocTaskGuard {

    record Replacement(String title, String description, String doneCriteria, String reason) {
    }

    private TocTaskGuard() {
    }

    /** 바꿔야 하면 서버 문구, 아니면 null. */
    static Replacement check(List<String> refs, Map<String, ProvidedSource> byRef) {
        for (String ref : refs) {
            ProvidedSource topic = byRef.get(ref);
            if (!PlanResultNormalizer.isTocTopic(topic)) {
                continue;
            }
            String title = text(topic.providedValue().get("title"));
            // 계획 입력은 묶음 제목에 부모 경로를 붙인다("Chapter 01 › 연습문제") — 종류는 마지막 마디로 본다.
            TocItemKind kind = TocItemKind.of(lastSegment(title));
            if (kind != TocItemKind.EXERCISE && kind != TocItemKind.LAB) {
                continue;
            }
            if (citesBodyFor(topic.sourceId(), refs, byRef)) {
                continue;
            }
            String path = pathOf(topic, byRef);
            Object locator = topic.providedValue().get("sourceLocator");
            String where = locator instanceof String l && l.startsWith("교재 p.") ? "(" + l + ")" : "";
            boolean lab = kind == TocItemKind.LAB;
            return new Replacement(
                    "「" + path + "」 " + (lab ? "해 보기" : "풀기"),
                    "교재 「" + path + "」" + where + (lab ? "의 실습을 교재를 보며 직접 해 봐요." : "의 문제를 교재에서 직접 풀어요.")
                            + " 문제·실습 내용은 교재에서 확인해요.",
                    "교재 「" + path + "」 " + (lab ? "실습을 끝까지 해 보고 결과를 확인한다" : "문제를 모두 풀고 답을 확인한다"),
                    "교재 목차의 " + (lab ? "실습" : "연습문제") + " 범위예요. 목차 제목만 있어 문제 내용은 교재에서 확인해요.");
        }
        return null;
    }

    /** 이 항목이 그 토픽을 위해 전달한 자료 본문 구간(MATERIAL_SECTION, 부모가 그 토픽)을 인용했나. */
    private static boolean citesBodyFor(Long topicId, List<String> refs, Map<String, ProvidedSource> byRef) {
        for (String ref : refs) {
            ProvidedSource s = byRef.get(ref);
            if (s == null || s.sourceType() != ProvenanceSourceType.MATERIAL_SECTION) {
                continue;
            }
            if (Objects.equals(s.parentSourceId(), topicId)) {
                return true;
            }
            // 구간이 여러 토픽에 연결돼 있으면 전부 본다(parentSourceId는 그중 하나뿐이다).
            Object linked = s.providedValue() == null ? null : s.providedValue().get("linkedTopicIds");
            if (linked instanceof java.util.Collection<?> ids
                    && ids.stream().anyMatch(id -> id != null && String.valueOf(id).equals(String.valueOf(topicId)))) {
                return true;
            }
        }
        return false;
    }

    /** "부모 › 토픽". 부모 토픽이 이번 입력에 있으면 그 제목을 앞에 붙인다(장마다 반복되는 "연습문제"를 구분). */
    private static String pathOf(ProvidedSource topic, Map<String, ProvidedSource> byRef) {
        String title = text(topic.providedValue().get("title"));
        if (topic.parentSourceId() == null || title.contains(" › ")) {
            return title;
        }
        for (ProvidedSource s : byRef.values()) {
            if (s.sourceType() == ProvenanceSourceType.TOPIC && Objects.equals(s.sourceId(), topic.parentSourceId())
                    && s.providedValue() != null) {
                String parent = text(s.providedValue().get("title"));
                if (!parent.isBlank()) {
                    return parent + " › " + title;
                }
            }
        }
        return title;
    }

    private static String lastSegment(String title) {
        int i = title.lastIndexOf(" › ");
        return i < 0 ? title : title.substring(i + 3);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).replaceAll("[\\r\\n\\t]", " ").trim();
    }
}
