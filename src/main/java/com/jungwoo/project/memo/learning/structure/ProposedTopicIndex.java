package com.jungwoo.project.memo.learning.structure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.learning.tidy.ProjectTidyMapper;
import com.jungwoo.project.memo.learning.tidy.domain.ProjectTidyProposal;
import com.jungwoo.project.memo.learning.tidy.domain.ProjectTidyProposalMaterial;
import com.jungwoo.project.memo.material.MaterialSectionMapper;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.material.domain.MaterialSection;
import com.jungwoo.project.memo.material.domain.MaterialStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 승인 전(검토 중) 구조 제안을 <b>읽기 전용 색인</b>으로 읽는다.
 *
 * <p>왜: 자료를 올리기만 한 사용자는 학습 항목이 0개다. 구조 제안을 승인해야만 자료가 주제로 묶여 보이면, 지도를 정리하는 일이
 * 상담·계획의 선행 조건이 된다. 그래서 검토 중인 정리안의 변경을 "승인 전"이라는 상태 그대로 색인으로만 쓴다 —
 * 자료 선택 목록에서 구간을 주제로 묶어 보여 주고, 학습 지도에서 전체 구조 안의 미리보기로 보여 준다.
 *
 * <p>(2026-09-21) 읽는 원본이 바뀌었다. 예전에는 자료마다 하나씩 있던 변경안을 모아 <b>여기서</b> 같은 제목끼리
 * 합쳤다. 이제는 프로젝트 단위 정리안 하나가 원본이라 합칠 것이 없다 — 같은 개념을 하나로 모으는 판단은
 * 모델이 정리안을 만들 때 이미 했고, 그것이 이 전환의 요점이다. 이 클래스는 그 결과를 노드로 펴 놓기만 한다.
 *
 * <p>하지 않는 것:
 * <ul>
 *   <li>아무것도 쓰지 않는다. course_topics·topic_material_links·topic_progress를 만들거나 바꾸지 않는다.</li>
 *   <li>제안 노드를 실제 학습 항목 id로 위장하지 않는다 — 식별자는 "p{proposalId}:{changeId}" 문자열이다.</li>
 *   <li>적용·폐기·대체된 정리안, 삭제된 자료, 그 사이 해시가 바뀐 자료의 구간은 읽지 않는다.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProposedTopicIndex {

    private final ProjectTidyMapper tidyMapper;
    private final MaterialSectionMapper sectionMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @param nodeId        "p{proposalId}:{changeId}" — 이 색인 안에서만 유효한 임시 식별자
     * @param parentNodeId  부모가 같은 색인의 제안 노드일 때
     * @param parentTopicId 부모가 이미 있는 학습 항목일 때(LINK면 연결 대상 학습 항목)
     * @param op            ADD / LINK / RENAME / MOVE / MERGE / SPLIT
     * @param proposalIds   이 노드가 나온 정리안. 이제 언제나 하나다(프로젝트당 검토 중인 안이 하나이므로)
     * @param materialIds   이 노드의 근거 구간이 속한 자료들. 여럿일 수 있다 — 그것이 프로젝트 단위 정리의 결과다
     */
    public record Node(String nodeId, String title, String parentNodeId, Long parentTopicId, String op,
                       List<Long> sectionIds, List<Long> proposalIds, List<Long> materialIds, String reason) {
    }

    /** {@code proposals}는 화면이 "무엇을 근거로 만든 제안인가"를 쓰기 위한 것이다. 지금은 0개나 1개다. */
    public record Index(List<Node> nodes, List<ProjectTidyProposal> proposals) {
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
     * @param activeMaterials 이 프로젝트에 지금 연결된 자료(materialId → 자료). 여기 없거나 해시가 다른 자료의
     *                        구간은 근거에서 빠진다
     */
    public Index forCourse(Long userId, Long courseId, Map<Long, CourseMaterial> activeMaterials) {
        ProjectTidyProposal proposal;
        try {
            proposal = tidyMapper.findOpenProposalByCourse(courseId, userId);
        } catch (Exception e) {
            log.warn("검토 중인 정리안을 읽지 못했다 — 색인 없이 진행한다. courseId={}, {}",
                    courseId, e.getClass().getSimpleName());
            return Index.empty();
        }
        if (proposal == null) {
            return Index.empty();
        }
        List<TopicChangeOp> ops;
        try {
            ops = objectMapper.readValue(proposal.getOpsJson(), new TypeReference<List<TopicChangeOp>>() {
            });
        } catch (Exception e) {
            log.debug("정리안의 ops를 읽지 못했다. proposalId={}", proposal.getProposalId());
            return Index.empty();
        }
        if (ops == null || ops.isEmpty()) {
            return Index.empty();
        }

        // 구간 → 자료. 노드마다 "어느 자료를 보고 나온 것인가"를 붙이려면 이 지도가 필요하다.
        Map<Long, Long> materialOfSection = materialOfSection(userId, proposal, activeMaterials);

        /*
         * tempId → nodeId를 먼저 만든다. 새 항목이 같은 정리안의 다른 새 항목을 부모로 가리킬 수
         * 있는데(parentTempId), 노드를 만들면서 찾으면 부모가 아직 안 나온 경우를 놓친다.
         * 정리안의 작업 순서는 부모가 앞에 오도록 서버가 세워 두지만, 여기서는 순서에 기대지 않는다.
         */
        Map<String, String> nodeIdOfTempId = new HashMap<>();
        for (TopicChangeOp op : ops) {
            mapTempIds(op, proposal, nodeIdOfTempId);
        }
        Map<String, Node> nodes = new LinkedHashMap<>();
        for (TopicChangeOp op : ops) {
            collect(op, proposal, null, materialOfSection, nodeIdOfTempId, nodes);
        }
        return new Index(new ArrayList<>(nodes.values()), List.of(proposal));
    }

    /**
     * 이 정리안이 근거로 삼은 자료의 구간들. 그 사이 지워졌거나 파일이 바뀐 자료는 빼고 읽는다 —
     * 사용자에게 "지금은 없는 근거"를 보여 주지 않기 위해서다.
     */
    private Map<Long, Long> materialOfSection(Long userId, ProjectTidyProposal proposal,
                                              Map<Long, CourseMaterial> activeMaterials) {
        List<Long> materialIds = new ArrayList<>();
        for (ProjectTidyProposalMaterial member : tidyMapper.findProposalMaterials(
                proposal.getProposalId(), userId)) {
            if (!member.isIncluded()) {
                continue;
            }
            CourseMaterial material = activeMaterials.get(member.getMaterialId());
            if (material == null || material.getStatus() == MaterialStatus.DELETED
                    || !Objects.equals(material.getFileHash(), member.getFileHash())) {
                continue;
            }
            materialIds.add(member.getMaterialId());
        }
        if (materialIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> out = new HashMap<>();
        for (MaterialSection section : sectionMapper.findActiveByMaterialIds(materialIds, userId)) {
            out.put(section.getSectionId(), section.getMaterialId());
        }
        return out;
    }

    private static void mapTempIds(TopicChangeOp op, ProjectTidyProposal proposal, Map<String, String> out) {
        if (op == null) {
            return;
        }
        if (op.tempId() != null) {
            out.putIfAbsent(op.tempId(), nodeIdOf(op, proposal, out.size()));
        }
        for (TopicChangeOp child : op.children() == null ? List.<TopicChangeOp>of() : op.children()) {
            mapTempIds(child, proposal, out);
        }
    }

    private static String nodeIdOf(TopicChangeOp op, ProjectTidyProposal proposal, int fallbackSeq) {
        String changeId = op.changeId() != null ? op.changeId()
                : op.op() == null ? "op-" + fallbackSeq
                : op.op().toLowerCase(Locale.ROOT) + "-" + (op.tempId() != null ? op.tempId() : fallbackSeq);
        return "p" + proposal.getProposalId() + ":" + changeId;
    }

    private static void collect(TopicChangeOp op, ProjectTidyProposal proposal, String parentNodeId,
                                Map<Long, Long> materialOfSection, Map<String, String> nodeIdOfTempId,
                                Map<String, Node> nodes) {
        if (op == null || op.op() == null) {
            return;
        }
        String nodeId = nodeIdOf(op, proposal, nodes.size());
        String effectiveParentNode = parentNodeId != null ? parentNodeId
                : op.parentTempId() == null ? null : nodeIdOfTempId.get(op.parentTempId());
        Long parentTopicId = TopicChangeOp.LINK.equals(op.op()) ? op.topicId() : op.parentTopicId();
        List<Long> sections = new ArrayList<>();
        List<Long> materials = new ArrayList<>();
        for (Long sectionId : op.sectionIds() == null ? List.<Long>of() : op.sectionIds()) {
            Long materialId = materialOfSection.get(sectionId);
            if (materialId == null) {
                continue; // 지워졌거나 다시 분석되어 지금은 없는 구간.
            }
            sections.add(sectionId);
            if (!materials.contains(materialId)) {
                materials.add(materialId);
            }
        }
        nodes.put(nodeId, new Node(nodeId, op.title(), effectiveParentNode, parentTopicId, op.op(),
                sections, new ArrayList<>(List.of(proposal.getProposalId())), materials, op.reason()));
        for (TopicChangeOp child : op.children() == null ? List.<TopicChangeOp>of() : op.children()) {
            collect(child, proposal, nodeId, materialOfSection, nodeIdOfTempId, nodes);
        }
    }
}
