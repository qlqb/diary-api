package com.jungwoo.project.memo.learning.structure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.learning.TopicChangeProposalMapper;
import com.jungwoo.project.memo.learning.domain.TopicChangeProposal;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.material.domain.MaterialStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 승인 전(PROPOSED) 구조 제안을 <b>읽기 전용 색인</b>으로 읽는다.
 *
 * <p>왜: 자료를 올리기만 한 사용자는 학습 항목이 0개다. 구조 제안을 승인해야만 자료가 주제로 묶여 보이면, 지도를 정리하는 일이
 * 상담·계획의 선행 조건이 된다. 그래서 유효한 제안의 노드를 "자동 분석 제안(승인 전)"이라는 상태 그대로 색인으로만 쓴다 —
 * 자료 선택 목록에서 구간을 주제로 묶어 보여 주고, 학습 지도에서 전체 구조 안의 미리보기로 보여 준다.
 *
 * <p>하지 않는 것:
 * <ul>
 *   <li>아무것도 쓰지 않는다. course_topics·topic_material_links·topic_progress를 만들거나 바꾸지 않는다.</li>
 *   <li>제안 노드를 실제 학습 항목 id로 위장하지 않는다 — 식별자는 "p{proposalId}:{tempId}" 문자열이다.</li>
 *   <li>거절(DISMISSED)·적용(APPLIED)·대체(STALE)·충돌(CONFLICT)·빈(EMPTY) 제안, 삭제된 자료, 자료가 바뀌어 해시가
 *       다른(옛 버전) 제안은 읽지 않는다.</li>
 * </ul>
 *
 * <p>같은 프로젝트에 자료마다 제안이 있으므로 같은 주제가 여러 제안에 나온다. 같은 부모 아래 같은 제목(공백·대소문자 무시)의
 * 추가 노드는 색인에서 한 묶음으로 합친다(구간은 합집합). 제목만 같고 부모가 다르면 합치지 않는다 — 실제 병합 여부는 적용할
 * 때 사용자가 정한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProposedTopicIndex {

    private final TopicChangeProposalMapper proposalMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @param nodeId        "p{proposalId}:{tempId}" — 이 색인 안에서만 유효한 임시 식별자
     * @param parentNodeId  부모가 같은 색인의 제안 노드일 때
     * @param parentTopicId 부모가 이미 있는 학습 항목일 때(LINK면 연결 대상 학습 항목)
     * @param op            ADD / LINK / RENAME / MOVE / MERGE / SPLIT
     * @param proposalIds   이 묶음에 기여한 제안들(중복을 합쳤으면 여러 개)
     */
    public record Node(String nodeId, String title, String parentNodeId, Long parentTopicId, String op,
                       List<Long> sectionIds, List<Long> proposalIds, List<Long> materialIds, String reason) {
    }

    public record Index(List<Node> nodes, List<TopicChangeProposal> proposals) {
        public static Index empty() {
            return new Index(List.of(), List.of());
        }

        /** 구간 → 그 구간을 묶는 제안 노드(추가 노드 우선). */
        public Map<Long, Node> bySection() {
            Map<Long, Node> out = new LinkedHashMap<>();
            for (Node node : nodes) {
                if (!TopicChangeOp.ADD.equals(node.op())) {
                    continue;
                }
                node.sectionIds().forEach(id -> out.putIfAbsent(id, node));
            }
            return out;
        }
    }

    /**
     * @param activeMaterials 이 프로젝트에 지금 연결된 자료(materialId → 자료). 여기 없거나 해시가 다른 제안은 읽지 않는다
     */
    public Index forCourse(Long userId, Long courseId, Map<Long, CourseMaterial> activeMaterials) {
        List<TopicChangeProposal> open;
        try {
            open = proposalMapper.findOpenByCourseId(courseId, userId);
        } catch (Exception e) {
            log.warn("승인 전 구조 제안을 읽지 못했다 — 색인 없이 진행한다. courseId={}, {}", courseId, e.getClass().getSimpleName());
            return Index.empty();
        }
        if (open == null || open.isEmpty()) {
            return Index.empty();
        }
        Map<String, Node> merged = new LinkedHashMap<>();
        List<TopicChangeProposal> used = new ArrayList<>();
        for (TopicChangeProposal proposal : open) {
            CourseMaterial material = activeMaterials.get(proposal.getMaterialId());
            if (material == null || material.getStatus() == MaterialStatus.DELETED
                    || !Objects.equals(material.getFileHash(), proposal.getFileHash())) {
                continue; // 지워졌거나 연결이 끊겼거나, 그 사이 자료가 바뀐(옛 버전) 제안.
            }
            List<TopicChangeOp> ops;
            try {
                ops = objectMapper.readValue(proposal.getOpsJson(), new TypeReference<List<TopicChangeOp>>() {
                });
            } catch (Exception e) {
                log.debug("구조 제안의 ops를 읽지 못했다. proposalId={}", proposal.getProposalId());
                continue;
            }
            used.add(proposal);
            for (TopicChangeOp op : ops == null ? List.<TopicChangeOp>of() : ops) {
                collect(op, proposal, null, merged);
            }
        }
        return new Index(new ArrayList<>(merged.values()), used);
    }

    private static void collect(TopicChangeOp op, TopicChangeProposal proposal, String parentNodeId, Map<String, Node> merged) {
        if (op == null || op.op() == null) {
            return;
        }
        String nodeId = "p" + proposal.getProposalId() + ":" + (op.tempId() != null ? op.tempId()
                : op.op().toLowerCase(Locale.ROOT) + "-" + (op.topicId() != null ? op.topicId() : merged.size()));
        String effectiveParentNode = parentNodeId != null ? parentNodeId
                : op.parentTempId() == null ? null : "p" + proposal.getProposalId() + ":" + op.parentTempId();
        Long parentTopicId = TopicChangeOp.LINK.equals(op.op()) ? op.topicId() : op.parentTopicId();
        String title = op.title();
        List<Long> sections = op.sectionIds() == null ? List.of() : op.sectionIds();

        String key = TopicChangeOp.ADD.equals(op.op()) && title != null
                ? "ADD|" + parentKey(effectiveParentNode, parentTopicId, merged) + "|" + normalize(title)
                : nodeId;
        Node existing = merged.get(key);
        String resolvedNodeId = nodeId;
        if (existing == null) {
            merged.put(key, new Node(nodeId, title, effectiveParentNode, parentTopicId, op.op(), new ArrayList<>(sections),
                    new ArrayList<>(List.of(proposal.getProposalId())), new ArrayList<>(List.of(proposal.getMaterialId())),
                    op.reason()));
        } else {
            resolvedNodeId = existing.nodeId();
            sections.stream().filter(id -> !existing.sectionIds().contains(id)).forEach(existing.sectionIds()::add);
            if (!existing.proposalIds().contains(proposal.getProposalId())) {
                existing.proposalIds().add(proposal.getProposalId());
            }
            if (!existing.materialIds().contains(proposal.getMaterialId())) {
                existing.materialIds().add(proposal.getMaterialId());
            }
        }
        for (TopicChangeOp child : op.children() == null ? List.<TopicChangeOp>of() : op.children()) {
            collect(child, proposal, resolvedNodeId, merged);
        }
    }

    /** 부모가 제안 노드면 그 노드가 합쳐진 묶음의 제목 경로로 비교한다 — 제안이 달라도 같은 자리면 같은 부모다. */
    private static String parentKey(String parentNodeId, Long parentTopicId, Map<String, Node> merged) {
        if (parentNodeId == null) {
            return "t" + parentTopicId;
        }
        for (Map.Entry<String, Node> e : merged.entrySet()) {
            if (e.getValue().nodeId().equals(parentNodeId)) {
                return e.getKey();
            }
        }
        return parentNodeId;
    }

    private static String normalize(String title) {
        return title.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }
}
