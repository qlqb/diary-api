package com.jungwoo.project.memo.learning.structure;

import com.jungwoo.project.memo.learning.domain.CourseTopic;
import com.jungwoo.project.memo.material.domain.SectionRole;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 변경안 작업 목록을 현재 트리와 자료 구간에 대고 검증한다. 순수 함수.
 *
 * <p>거부하는 것: 모르는 op, 이 프로젝트에 없는(또는 ARCHIVED) topicId, 이 자료에 없는 sectionId,
 * 자기 자신·자손 아래로의 이동(순환), 자기 자신과의 병합, 두 번 흡수되는 항목, 해석되지 않는
 * tempId, 빈 제목. 모델 출력을 정리할 때는 나쁜 작업만 버리고(lenient), 적용 직전에는 하나라도
 * 나쁘면 전체를 거부한다(strict) — 부분 적용을 만들지 않는다.
 */
public final class TopicChangeOpsValidator {

    private TopicChangeOpsValidator() {
    }

    public record Result(List<TopicChangeOp> ops, List<String> rejected, Summary summary) {
    }

    /** 화면 요약용 개수. structural이 true면 기본 요약에서도 드러낸다. */
    public record Summary(int link, int add, int rename, int move, int merge, int split, boolean structural,
                          int rejected) {
        public int total() {
            return link + add + rename + move + merge + split;
        }
    }

    public static Result validate(List<TopicChangeOp> raw, List<CourseTopic> activeTopics, Set<Long> sectionIds,
                                  boolean strict) {
        Map<Long, CourseTopic> topics = new HashMap<>();
        for (CourseTopic topic : activeTopics) {
            topics.put(topic.getTopicId(), topic);
        }
        // 이동을 순서대로 반영한 부모 지도. 순환 검사는 이 지도로 한다.
        Map<Long, Long> parentOf = new HashMap<>();
        for (CourseTopic topic : activeTopics) {
            parentOf.put(topic.getTopicId(), topic.getParentTopicId());
        }
        Set<String> tempIds = new HashSet<>();
        Set<Long> absorbed = new HashSet<>();
        Set<Long> survivors = new HashSet<>();
        List<TopicChangeOp> accepted = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        int link = 0, add = 0, rename = 0, move = 0, merge = 0, split = 0;

        for (TopicChangeOp op : raw == null ? List.<TopicChangeOp>of() : raw) {
            String reason = reject(op, topics, parentOf, sectionIds, tempIds, absorbed, survivors);
            if (reason != null) {
                rejected.add(reason);
                if (strict) {
                    return new Result(List.of(), rejected,
                            new Summary(0, 0, 0, 0, 0, 0, false, rejected.size()));
                }
                continue;
            }
            TopicChangeOp clean = normalize(op);
            accepted.add(clean);
            switch (clean.op()) {
                case TopicChangeOp.LINK -> link++;
                case TopicChangeOp.ADD -> {
                    add++;
                    collectTempIds(clean, tempIds);
                }
                case TopicChangeOp.RENAME -> rename++;
                case TopicChangeOp.MOVE -> {
                    move++;
                    parentOf.put(clean.topicId(), clean.parentTopicId());
                }
                case TopicChangeOp.MERGE -> {
                    merge++;
                    absorbed.addAll(clean.absorbedTopicIds());
                    survivors.add(clean.survivingTopicId());
                }
                case TopicChangeOp.SPLIT -> {
                    split++;
                    collectTempIds(clean, tempIds);
                }
                default -> {
                }
            }
        }
        boolean structural = move + merge + split > 0;
        return new Result(accepted, rejected,
                new Summary(link, add, rename, move, merge, split, structural, rejected.size()));
    }

    private static String reject(TopicChangeOp op, Map<Long, CourseTopic> topics, Map<Long, Long> parentOf,
                                 Set<Long> sectionIds, Set<String> tempIds, Set<Long> absorbed, Set<Long> survivors) {
        if (op == null || op.op() == null) {
            return "op 없음";
        }
        String kind = op.op().trim().toUpperCase(Locale.ROOT);
        switch (kind) {
            case TopicChangeOp.LINK -> {
                if (!isLive(op.topicId(), topics, absorbed)) {
                    return "LINK: 없는 항목 " + op.topicId();
                }
                if (op.sectionIds() == null || op.sectionIds().isEmpty()) {
                    return "LINK: 구간 없음";
                }
                for (Long sectionId : op.sectionIds()) {
                    if (sectionId == null || !sectionIds.contains(sectionId)) {
                        return "LINK: 이 자료에 없는 구간 " + sectionId;
                    }
                }
                return null;
            }
            case TopicChangeOp.ADD -> {
                return rejectAdd(op, topics, sectionIds, tempIds, absorbed, true);
            }
            case TopicChangeOp.RENAME -> {
                if (!isLive(op.topicId(), topics, absorbed)) {
                    return "RENAME: 없는 항목 " + op.topicId();
                }
                if (blank(op.title())) {
                    return "RENAME: 빈 제목";
                }
                return null;
            }
            case TopicChangeOp.MOVE -> {
                if (!isLive(op.topicId(), topics, absorbed)) {
                    return "MOVE: 없는 항목 " + op.topicId();
                }
                Long newParent = op.parentTopicId();
                if (newParent != null) {
                    if (!isLive(newParent, topics, absorbed)) {
                        return "MOVE: 없는 부모 " + newParent;
                    }
                    if (newParent.equals(op.topicId())) {
                        return "MOVE: 자기 자신 아래로";
                    }
                    Long cursor = newParent;
                    int guard = 0;
                    while (cursor != null && guard++ < 1000) {
                        if (cursor.equals(op.topicId())) {
                            return "MOVE: 자손 아래로(순환)";
                        }
                        cursor = parentOf.get(cursor);
                    }
                }
                return null;
            }
            case TopicChangeOp.MERGE -> {
                if (!isLive(op.survivingTopicId(), topics, absorbed)) {
                    return "MERGE: 없는 항목 " + op.survivingTopicId();
                }
                if (op.absorbedTopicIds() == null || op.absorbedTopicIds().isEmpty()) {
                    return "MERGE: 흡수할 항목 없음";
                }
                for (Long id : op.absorbedTopicIds()) {
                    if (!isLive(id, topics, absorbed)) {
                        return "MERGE: 없는 항목 " + id;
                    }
                    if (id.equals(op.survivingTopicId())) {
                        return "MERGE: 자기 자신과 병합";
                    }
                    if (survivors.contains(id)) {
                        return "MERGE: 살아남기로 한 항목을 흡수";
                    }
                }
                return null;
            }
            case TopicChangeOp.SPLIT -> {
                if (!isLive(op.topicId(), topics, absorbed)) {
                    return "SPLIT: 없는 항목 " + op.topicId();
                }
                if (op.children() == null || op.children().size() < 2) {
                    return "SPLIT: 자식이 둘 미만";
                }
                Set<String> localTemp = new HashSet<>(tempIds);
                for (TopicChangeOp child : op.children()) {
                    String reason = rejectAdd(child, topics, sectionIds, localTemp, absorbed, false);
                    if (reason != null) {
                        return "SPLIT: " + reason;
                    }
                    if (child.tempId() != null) {
                        localTemp.add(child.tempId());
                    }
                }
                return null;
            }
            default -> {
                return "모르는 op " + kind;
            }
        }
    }

    private static String rejectAdd(TopicChangeOp op, Map<Long, CourseTopic> topics, Set<Long> sectionIds,
                                    Set<String> tempIds, Set<Long> absorbed, boolean allowParent) {
        if (blank(op.title())) {
            return "ADD: 빈 제목";
        }
        if (allowParent) {
            if (op.parentTopicId() != null && !isLive(op.parentTopicId(), topics, absorbed)) {
                return "ADD: 없는 부모 " + op.parentTopicId();
            }
            if (op.parentTempId() != null && !tempIds.contains(op.parentTempId())) {
                return "ADD: 해석되지 않는 부모 tempId " + op.parentTempId();
            }
        }
        if (op.tempId() != null && tempIds.contains(op.tempId())) {
            return "ADD: 중복 tempId " + op.tempId();
        }
        for (Long sectionId : op.sectionIds() == null ? List.<Long>of() : op.sectionIds()) {
            if (sectionId == null || !sectionIds.contains(sectionId)) {
                return "ADD: 이 자료에 없는 구간 " + sectionId;
            }
        }
        if (op.children() != null) {
            Set<String> localTemp = new HashSet<>(tempIds);
            if (op.tempId() != null) {
                localTemp.add(op.tempId());
            }
            for (TopicChangeOp child : op.children()) {
                String reason = rejectAdd(child, topics, sectionIds, localTemp, absorbed, false);
                if (reason != null) {
                    return reason;
                }
                if (child.tempId() != null) {
                    localTemp.add(child.tempId());
                }
            }
        }
        return null;
    }

    private static void collectTempIds(TopicChangeOp op, Set<String> tempIds) {
        if (op.tempId() != null) {
            tempIds.add(op.tempId());
        }
        if (op.children() != null) {
            for (TopicChangeOp child : op.children()) {
                collectTempIds(child, tempIds);
            }
        }
    }

    private static boolean isLive(Long topicId, Map<Long, CourseTopic> topics, Set<Long> absorbed) {
        return topicId != null && topics.containsKey(topicId) && !absorbed.contains(topicId);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** op 대문자화, 제목 trim, role 닫힌 집합으로, 중복 sectionId 제거. */
    static TopicChangeOp normalize(TopicChangeOp op) {
        String kind = op.op().trim().toUpperCase(Locale.ROOT);
        List<Long> sections = op.sectionIds() == null ? null
                : op.sectionIds().stream().filter(java.util.Objects::nonNull).distinct().toList();
        String role = op.role() == null ? null : SectionRole.parseOne(op.role()) == null ? null
                : SectionRole.parseOne(op.role()).name();
        List<TopicChangeOp> children = op.children() == null ? null
                : op.children().stream().map(TopicChangeOpsValidator::normalize).toList();
        String sourceType = "SOURCE".equalsIgnoreCase(op.sourceType()) ? "SOURCE" : "AI_DERIVED";
        return new TopicChangeOp(kind, op.tempId(), op.topicId(), op.parentTopicId(), op.parentTempId(),
                op.title() == null ? null : op.title().trim(), sourceType, op.locator(), sections, role,
                op.survivingTopicId(), op.absorbedTopicIds(), children, op.reason());
    }
}
