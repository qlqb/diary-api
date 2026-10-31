package com.jungwoo.project.memo.material.linkproposal;

import com.jungwoo.project.memo.material.domain.EvidenceSource;
import com.jungwoo.project.memo.material.domain.MaterialType;
import com.jungwoo.project.memo.material.domain.ProposalAction;
import com.jungwoo.project.memo.material.dto.LinkProposalPayload;
import com.jungwoo.project.memo.material.dto.ProjectRef;
import com.jungwoo.project.memo.material.dto.ProposalGroupResponse;
import com.jungwoo.project.memo.material.dto.ProposalMemberResponse;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 모델이 뱉은 제안을 화면에 내보낼 수 있는 형태로 바로잡는다.
 *
 * 원칙: <b>누락과 형식은 서버가 바로잡는다. 의미가 충돌하면 임의로 정답을 고르지 않고
 * 사용자에게 넘긴다.</b> LEAVE로 옮기거나 체크를 푸는 것은 "판단하지 않음"이지
 * "다르게 판단함"이 아니다.
 *
 * DB도 모델도 부르지 않는 순수 함수다 — 그래야 이 로직을 @SpringBootTest 없이 단위로
 * 검증할 수 있고, 나중에 모델 호출 경로가 바뀌어도 이 테스트가 그대로 남는다.
 */
@Component
public class ProposalNormalizer {

    static final String NOTICE_SAME_TITLE_AS_EXISTING =
            "같은 이름의 프로젝트가 이미 있어요. 새로 만들지, 기존 것에 붙일지 골라 주세요.";
    static final String NOTICE_DUPLICATE_PROPOSED_TITLE =
            "같은 이름을 제안한 묶음이 둘이에요. 이름을 바꾸거나 한쪽만 골라 주세요.";

    static final String LEAVE_GROUP_ID = "leave";

    List<ProposalGroupResponse> normalize(List<ProposalCandidate> candidates,
                                          LinkProposalPayload payload,
                                          List<ProjectRef> activeProjects) {
        Map<Long, ProposalCandidate> candidateById = new LinkedHashMap<>();
        for (ProposalCandidate candidate : nullSafe(candidates)) {
            if (candidate != null) {
                candidateById.put(candidate.materialId(), candidate);
            }
        }

        List<ProjectRef> projects = nullSafe(activeProjects).stream()
                .filter(p -> p != null && p.courseId() != null)
                .toList();
        Map<Long, ProjectRef> projectById = new LinkedHashMap<>();
        projects.forEach(p -> projectById.put(p.courseId(), p));

        List<WorkGroup> groups = toWorkGroups(payload, candidateById, projectById);
        Map<Long, WorkMember> leaveMembers = new LinkedHashMap<>();

        resolveDuplicateMembers(groups, leaveMembers);
        collectLeaveGroups(groups, leaveMembers);
        collectMissingMembers(candidateById, groups, leaveMembers);

        return toResponses(groups, leaveMembers, candidateById, projects, projectById);
    }

    /**
     * 형식 보정: 후보에 없는 멤버 제거, materialType 기본값, 남의(또는 보관된) 프로젝트 지정과
     * 빈 제목을 LEAVE로 강등. 조용히 고쳐도 사용자가 잃는 것이 없는 것들만 여기서 처리한다.
     */
    private List<WorkGroup> toWorkGroups(LinkProposalPayload payload,
                                         Map<Long, ProposalCandidate> candidateById,
                                         Map<Long, ProjectRef> projectById) {
        List<WorkGroup> groups = new ArrayList<>();
        List<LinkProposalPayload.ProposalGroup> raw =
                payload == null ? List.of() : nullSafe(payload.groups());

        for (LinkProposalPayload.ProposalGroup group : raw) {
            if (group == null) {
                continue;
            }
            List<WorkMember> members = new ArrayList<>();
            for (LinkProposalPayload.ProposalMember member : nullSafe(group.members())) {
                if (member == null || member.materialId() == null
                        || !candidateById.containsKey(member.materialId())) {
                    continue;
                }
                members.add(new WorkMember(
                        member.materialId(),
                        member.materialType() == null ? MaterialType.OTHER : member.materialType(),
                        member.evidence() == null ? "" : member.evidence(),
                        member.evidenceSource()));
            }
            if (members.isEmpty()) {
                continue;
            }

            ProposalAction action = group.action() == null ? ProposalAction.LEAVE : group.action();
            if (action == ProposalAction.LINK_EXISTING
                    && (group.existingCourseId() == null || !projectById.containsKey(group.existingCourseId()))) {
                action = ProposalAction.LEAVE;
            }
            if (action == ProposalAction.CREATE_AND_LINK
                    && (group.proposedTitle() == null || group.proposedTitle().isBlank())) {
                action = ProposalAction.LEAVE;
            }

            WorkGroup work = new WorkGroup();
            work.action = action;
            work.existingCourseId = action == ProposalAction.LINK_EXISTING ? group.existingCourseId() : null;
            work.proposedTitle = action == ProposalAction.CREATE_AND_LINK ? group.proposedTitle().trim() : null;
            work.reason = group.reason() == null ? "" : group.reason().trim();
            work.members = members;
            groups.add(work);
        }
        return groups;
    }

    /**
     * 같은 자료가 두 묶음에 등장한 경우.
     *
     * 두 등장의 (action, existingCourseId, proposedTitle, materialType)이 완전히 같으면 단순
     * 중복이므로 첫 번째만 남긴다. 하나라도 다르면 모델이 이 자료를 어디에 둘지 정하지 못한
     * 것이므로 양쪽에서 모두 빼고 LEAVE로 옮긴다 — 앞에 나왔다는 이유로 첫 번째를 고르면
     * 그건 서버가 판단을 지어내는 것이다.
     */
    private void resolveDuplicateMembers(List<WorkGroup> groups, Map<Long, WorkMember> leaveMembers) {
        Map<Long, List<Placement>> placementsById = new LinkedHashMap<>();
        Map<Long, WorkMember> firstOccurrence = new LinkedHashMap<>();
        for (WorkGroup group : groups) {
            for (WorkMember member : group.members) {
                placementsById.computeIfAbsent(member.materialId(), k -> new ArrayList<>())
                        .add(new Placement(group.action, group.existingCourseId, group.proposedTitle,
                                member.materialType()));
                firstOccurrence.putIfAbsent(member.materialId(), member);
            }
        }

        Set<Long> conflicted = new LinkedHashSet<>();
        placementsById.forEach((materialId, placements) -> {
            if (placements.size() > 1 && placements.stream().distinct().count() > 1) {
                conflicted.add(materialId);
            }
        });

        Set<Long> taken = new LinkedHashSet<>();
        for (WorkGroup group : groups) {
            List<WorkMember> kept = new ArrayList<>();
            for (WorkMember member : group.members) {
                // 같은 자리로 두 번 나온 것은 첫 번째만 남기고, 어디에 둘지 갈린 것은 전부 뺀다.
                if (conflicted.contains(member.materialId()) || !taken.add(member.materialId())) {
                    continue;
                }
                kept.add(member);
            }
            group.members = kept;
        }
        groups.removeIf(group -> group.members.isEmpty());

        conflicted.forEach(materialId -> leaveMembers.putIfAbsent(materialId, firstOccurrence.get(materialId)));
    }

    /** LEAVE 묶음이 여럿이면 하나로 병합한다 — 화면에 "그냥 둘 자료" 줄이 여러 개일 이유가 없다. */
    private void collectLeaveGroups(List<WorkGroup> groups, Map<Long, WorkMember> leaveMembers) {
        for (WorkGroup group : groups) {
            if (group.action == ProposalAction.LEAVE) {
                group.members.forEach(m -> leaveMembers.putIfAbsent(m.materialId(), m));
            }
        }
        groups.removeIf(group -> group.action == ProposalAction.LEAVE);
    }

    /**
     * 입력으로 준 자료가 출력에 아예 없으면 LEAVE로 되살린다.
     *
     * 판단하지 못한 자료를 숨기지 않는다 — 6개를 올렸는데 4개만 제안에 뜨면 사용자는 나머지
     * 2개가 어디로 갔는지 찾아야 한다.
     */
    private void collectMissingMembers(Map<Long, ProposalCandidate> candidateById,
                                       List<WorkGroup> groups,
                                       Map<Long, WorkMember> leaveMembers) {
        Set<Long> placed = new LinkedHashSet<>(leaveMembers.keySet());
        groups.forEach(group -> group.members.forEach(m -> placed.add(m.materialId())));
        candidateById.keySet().stream()
                .filter(materialId -> !placed.contains(materialId))
                .forEach(materialId -> leaveMembers.put(materialId,
                        new WorkMember(materialId, MaterialType.OTHER, "", null)));
    }

    private List<ProposalGroupResponse> toResponses(List<WorkGroup> groups,
                                                    Map<Long, WorkMember> leaveMembers,
                                                    Map<Long, ProposalCandidate> candidateById,
                                                    List<ProjectRef> projects,
                                                    Map<Long, ProjectRef> projectById) {
        Map<String, Long> proposedTitleCounts = new LinkedHashMap<>();
        for (WorkGroup group : groups) {
            if (group.action == ProposalAction.CREATE_AND_LINK) {
                proposedTitleCounts.merge(titleKey(group.proposedTitle), 1L, Long::sum);
            }
        }

        List<ProposalGroupResponse> responses = new ArrayList<>();
        int seq = 0;
        for (WorkGroup group : groups) {
            seq += 1;
            List<String> notices = new ArrayList<>();
            List<ProjectRef> matchingProjects = List.of();

            if (group.action == ProposalAction.CREATE_AND_LINK) {
                String key = titleKey(group.proposedTitle);
                // 이름이 같다는 것은 같은 대상이라는 근거가 아니다 — 다른 학기, 다른 목적일 수
                // 있다. LINK_EXISTING으로 전환하지 않고 후보를 실어 사용자가 고르게 한다.
                matchingProjects = projects.stream()
                        .filter(p -> titleKey(p.title()).equals(key))
                        .toList();
                if (!matchingProjects.isEmpty()) {
                    notices.add(NOTICE_SAME_TITLE_AS_EXISTING);
                }
                // 자동 병합하지 않는다. 병합은 "같은 대상"이라는 판단인데 그 근거가 없다.
                if (proposedTitleCounts.getOrDefault(key, 0L) > 1) {
                    notices.add(NOTICE_DUPLICATE_PROPOSED_TITLE);
                }
            }

            List<ProposalMemberResponse> members = group.members.stream()
                    .map(member -> toMemberResponse(member, candidateById))
                    .toList();
            ProjectRef existing = group.existingCourseId == null ? null : projectById.get(group.existingCourseId);

            responses.add(ProposalGroupResponse.builder()
                    .groupId("g" + seq)
                    .action(group.action)
                    .existingCourseId(group.existingCourseId)
                    .existingCourseTitle(existing == null ? null : existing.title())
                    .proposedTitle(group.proposedTitle)
                    .reason(group.reason)
                    .defaultSelected(isDefaultSelected(group.action, notices, members))
                    .notices(List.copyOf(notices))
                    .matchingProjects(matchingProjects)
                    .members(members)
                    .build());
        }

        if (!leaveMembers.isEmpty()) {
            responses.add(ProposalGroupResponse.builder()
                    .groupId(LEAVE_GROUP_ID)
                    .action(ProposalAction.LEAVE)
                    .reason("")
                    .defaultSelected(false)
                    .notices(List.of())
                    .matchingProjects(List.of())
                    .members(leaveMembers.values().stream()
                            .map(member -> toMemberResponse(member, candidateById))
                            .toList())
                    .build());
        }
        return responses;
    }

    private ProposalMemberResponse toMemberResponse(WorkMember member,
                                                    Map<Long, ProposalCandidate> candidateById) {
        ProposalCandidate candidate = candidateById.get(member.materialId());
        return ProposalMemberResponse.builder()
                .materialId(member.materialId())
                .originalFilename(candidate == null ? null : candidate.originalFilename())
                .materialType(member.materialType())
                .evidence(member.evidence())
                .evidenceSource(member.evidenceSource())
                .evidenceVerified(isEvidenceVerified(member, candidate))
                .build();
    }

    /**
     * 모델이 인용했다고 신고한 대목이 실제 발췌 안에 있는지 대조한다.
     *
     * 부분 문자열 대조는 완전한 검증이 아니다 — 모델이 발췌를 요약하거나 조사를 바꾸면
     * 실패한다. 그래도 이 방향의 오차는 안전하다: 잘못 걸리면 체크가 풀릴 뿐이고 사용자가
     * 켜면 된다. 반대 방향(근거 없는 제안이 기본 체크됨)이 훨씬 비싸다.
     */
    private boolean isEvidenceVerified(WorkMember member, ProposalCandidate candidate) {
        if (member.evidenceSource() != EvidenceSource.CONTENT) {
            return false;
        }
        if (member.evidence() == null || member.evidence().isBlank()) {
            return false;
        }
        if (candidate == null || !candidate.hasExcerpt()) {
            return false;
        }
        return squeeze(candidate.excerpt()).contains(squeeze(member.evidence()));
    }

    /**
     * 켜진 채로 뜨려면 모든 멤버가 명시적으로 검증을 통과해야 한다.
     *
     * "하나라도 X면 false" 형태로 쓰지 않는 것이 중요하다 — 그러면 evidenceSource가 null인
     * 것 같은 예상 못 한 값이 통과한다.
     */
    private boolean isDefaultSelected(ProposalAction action, List<String> notices,
                                      List<ProposalMemberResponse> members) {
        if (action == ProposalAction.LEAVE || !notices.isEmpty() || members.isEmpty()) {
            return false;
        }
        return members.stream().allMatch(m ->
                m.getEvidenceSource() == EvidenceSource.CONTENT && m.isEvidenceVerified());
    }

    private static String titleKey(String title) {
        return squeeze(title).toLowerCase(Locale.ROOT);
    }

    private static String squeeze(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "");
    }

    private static <T> List<T> nullSafe(List<T> values) {
        return values == null ? List.of() : values;
    }

    /** 중복 등장이 "같은 자리"인지 판단하는 키. */
    private record Placement(ProposalAction action, Long existingCourseId, String proposedTitle,
                             MaterialType materialType) {
    }

    private record WorkMember(Long materialId, MaterialType materialType, String evidence,
                              EvidenceSource evidenceSource) {
    }

    /** 보정 과정에서 action과 members가 계속 바뀌므로 가변 홀더를 쓴다. 이 클래스 밖으로 나가지 않는다. */
    private static final class WorkGroup {
        private ProposalAction action;
        private Long existingCourseId;
        private String proposedTitle;
        private String reason;
        private List<WorkMember> members;
    }
}
