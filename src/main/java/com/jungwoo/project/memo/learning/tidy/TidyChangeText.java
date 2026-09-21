package com.jungwoo.project.memo.learning.tidy;

import com.jungwoo.project.memo.learning.domain.CourseTopic;
import com.jungwoo.project.memo.learning.structure.TopicChangeOp;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.material.domain.MaterialSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 변경 하나를 사람이 읽는 한 줄로. 서버가 만드는 이유는 근거 자료 이름·위치가 서버에만 있고,
 * 같은 문장을 화면과 기록(applied_result_json)이 함께 써야 하기 때문이다.
 *
 * <p>자료 이름을 함께 쓴다 — 프로젝트 정리는 "어느 자료를 보고 이렇게 판단했는가"가 핵심이라
 * "구간 3곳"만으로는 사용자가 판단할 수 없다.
 */
final class TidyChangeText {

    private TidyChangeText() {
    }

    private static final int MAX_MATERIALS_IN_TEXT = 3;

    static String describe(TopicChangeOp op, Map<Long, CourseTopic> topics, Map<Long, MaterialSection> sections,
                           Map<Long, CourseMaterial> materials) {
        String kind = op.op() == null ? "" : op.op();
        return switch (kind) {
            case TopicChangeOp.LINK -> "「" + titleOf(op.topicId(), topics) + "」에 "
                    + evidence(op, sections, materials) + "을(를) 연결해요";
            case TopicChangeOp.ADD -> {
                String where = op.parentTopicId() != null ? "「" + titleOf(op.parentTopicId(), topics) + "」 아래에 "
                        : op.parentTempId() != null ? "새로 만드는 항목 아래에 " : "맨 위에 ";
                String source = evidence(op, sections, materials);
                yield where + "「" + nullSafe(op.title()) + "」을(를) 새로 만들어요"
                        + (source.isEmpty() ? "" : " (" + source + ")")
                        + childrenSuffix(op);
            }
            case TopicChangeOp.RENAME -> "「" + titleOf(op.topicId(), topics) + "」의 이름을 「"
                    + nullSafe(op.title()) + "」으(로) 바꿔요";
            case TopicChangeOp.MOVE -> "「" + titleOf(op.topicId(), topics) + "」을(를) "
                    + (op.parentTopicId() == null ? "맨 위로" : "「" + titleOf(op.parentTopicId(), topics) + "」 아래로")
                    + " 옮겨요";
            case TopicChangeOp.MERGE -> mergeText(op, topics);
            case TopicChangeOp.SPLIT -> "「" + titleOf(op.topicId(), topics) + "」을(를) "
                    + (op.children() == null ? 0 : op.children().size()) + "개로 나눠요"
                    + childrenSuffix(op);
            default -> "학습 구조를 바꿔요";
        };
    }

    /** 학습 기록 승계가 애매할 수 있는 변경에 미리 붙이는 말. 적용 뒤에야 알게 하지 않는다. */
    static String caution(TopicChangeOp op) {
        return switch (op.op() == null ? "" : op.op()) {
            case TopicChangeOp.MERGE ->
                    "흡수되는 항목은 보관돼요. 학습 기록은 복제하지 않고, 승계가 애매하면 안내가 남아요";
            case TopicChangeOp.SPLIT ->
                    "기존 학습 기록은 원래 항목에 남고, 나눈 하위 항목은 새로 시작이에요";
            case TopicChangeOp.MOVE ->
                    "위치만 바뀌고 학습 기록·진도는 그대로 따라가요";
            default -> null;
        };
    }

    private static String mergeText(TopicChangeOp op, Map<Long, CourseTopic> topics) {
        List<String> absorbed = new ArrayList<>();
        for (Long id : op.absorbedTopicIds() == null ? List.<Long>of() : op.absorbedTopicIds()) {
            absorbed.add("「" + titleOf(id, topics) + "」");
        }
        return String.join(", ", absorbed) + "을(를) 「" + titleOf(op.survivingTopicId(), topics) + "」에 합쳐요";
    }

    private static String childrenSuffix(TopicChangeOp op) {
        if (op.children() == null || op.children().isEmpty()) {
            return "";
        }
        List<String> titles = new ArrayList<>();
        for (TopicChangeOp child : op.children()) {
            titles.add(nullSafe(child.title()));
        }
        return " — 하위: " + String.join(", ", titles);
    }

    /**
     * 근거를 "자료 이름 + 위치"로 적는다. 자료가 여럿이면 그것이 이 기능의 요점이므로 이름을
     * 다 보여주되, 너무 길어지면 나머지는 수로 접는다.
     */
    private static String evidence(TopicChangeOp op, Map<Long, MaterialSection> sections,
                                   Map<Long, CourseMaterial> materials) {
        Map<Long, List<String>> byMaterial = new LinkedHashMap<>();
        for (Long sectionId : op.sectionIds() == null ? List.<Long>of() : op.sectionIds()) {
            MaterialSection section = sections.get(sectionId);
            if (section == null) {
                continue;
            }
            byMaterial.computeIfAbsent(section.getMaterialId(), k -> new ArrayList<>()).add(section.locator());
        }
        if (byMaterial.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        int shown = 0;
        for (Map.Entry<Long, List<String>> entry : byMaterial.entrySet()) {
            if (shown++ >= MAX_MATERIALS_IN_TEXT) {
                parts.add("자료 " + (byMaterial.size() - MAX_MATERIALS_IN_TEXT) + "개 더");
                break;
            }
            CourseMaterial material = materials.get(entry.getKey());
            String name = material == null ? "자료" : material.getOriginalFilename();
            parts.add(name + " " + String.join(", ", entry.getValue()));
        }
        return String.join(" · ", parts);
    }

    private static String titleOf(Long topicId, Map<Long, CourseTopic> topics) {
        CourseTopic topic = topicId == null ? null : topics.get(topicId);
        return topic == null ? "항목 " + topicId : topic.getTitle();
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
