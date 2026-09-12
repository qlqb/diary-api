package com.jungwoo.project.memo.material.linkproposal;

import com.jungwoo.project.memo.material.domain.EvidenceSource;
import com.jungwoo.project.memo.material.domain.MaterialType;
import com.jungwoo.project.memo.material.domain.ProposalAction;
import com.jungwoo.project.memo.material.dto.LinkProposalPayload;
import com.jungwoo.project.memo.material.dto.ProjectRef;
import com.jungwoo.project.memo.material.dto.ProposalGroupResponse;
import com.jungwoo.project.memo.material.dto.ProposalMemberResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 응답 보정 규칙. DB도 모델도 부르지 않으므로 @SpringBootTest가 필요 없다.
 *
 * 이 테스트는 모델 호출 경로가 스트리밍이든 단발 호출이든 그대로 통과해야 한다 — 형식은
 * 스키마가, 의미는 서버가 맡는다는 분담이 호출 방식과 무관하기 때문이다.
 */
class ProposalNormalizerTest {

    private final ProposalNormalizer normalizer = new ProposalNormalizer();

    private static final ProjectRef OS = new ProjectRef(10L, "운영체제");
    private static final ProjectRef DS = new ProjectRef(11L, "자료구조");

    private static ProposalCandidate candidate(long materialId, String filename, String excerpt) {
        return new ProposalCandidate(materialId, filename, excerpt);
    }

    private static LinkProposalPayload.ProposalMember member(long materialId, String evidence,
                                                             EvidenceSource source) {
        return new LinkProposalPayload.ProposalMember(materialId, MaterialType.SYLLABUS, evidence, source);
    }

    private static LinkProposalPayload.ProposalGroup linkExisting(Long courseId,
                                                                  LinkProposalPayload.ProposalMember... members) {
        return new LinkProposalPayload.ProposalGroup(
                ProposalAction.LINK_EXISTING, courseId, null, "근거를 확인했어요", List.of(members));
    }

    private static LinkProposalPayload.ProposalGroup createAndLink(String title,
                                                                    LinkProposalPayload.ProposalMember... members) {
        return new LinkProposalPayload.ProposalGroup(
                ProposalAction.CREATE_AND_LINK, null, title, "근거를 확인했어요", List.of(members));
    }

    private static LinkProposalPayload.ProposalGroup leave(LinkProposalPayload.ProposalMember... members) {
        return new LinkProposalPayload.ProposalGroup(
                ProposalAction.LEAVE, null, null, "판단할 근거가 없어요", List.of(members));
    }

    private static LinkProposalPayload payload(LinkProposalPayload.ProposalGroup... groups) {
        return new LinkProposalPayload(List.of(groups));
    }

    private static ProposalGroupResponse leaveGroup(List<ProposalGroupResponse> groups) {
        return groups.stream()
                .filter(g -> g.getAction() == ProposalAction.LEAVE)
                .findFirst()
                .orElse(null);
    }

    private static List<Long> materialIds(ProposalGroupResponse group) {
        return group.getMembers().stream().map(ProposalMemberResponse::getMaterialId).toList();
    }

    @Test
    @DisplayName("남의(또는 보관된) 프로젝트를 가리키면 그룹 전체를 LEAVE로 강등한다")
    void demotesUnknownExistingCourse() {
        List<ProposalCandidate> candidates = List.of(candidate(101L, "os.pdf", "운영체제 강의계획서"));

        List<ProposalGroupResponse> groups = normalizer.normalize(candidates,
                payload(linkExisting(999L, member(101L, "운영체제", EvidenceSource.CONTENT))),
                List.of(OS));

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).getAction()).isEqualTo(ProposalAction.LEAVE);
        assertThat(groups.get(0).getExistingCourseId()).isNull();
        assertThat(materialIds(groups.get(0))).containsExactly(101L);
    }

    @Test
    @DisplayName("입력에 없는 materialId는 멤버에서 빼고, 그룹이 비면 그룹째 없앤다")
    void dropsUnknownMembers() {
        List<ProposalCandidate> candidates = List.of(candidate(101L, "os.pdf", "운영체제 강의계획서"));

        List<ProposalGroupResponse> groups = normalizer.normalize(candidates,
                payload(
                        linkExisting(10L, member(101L, "운영체제", EvidenceSource.CONTENT)),
                        createAndLink("네트워크", member(777L, "네트워크", EvidenceSource.CONTENT))),
                List.of(OS));

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).getExistingCourseId()).isEqualTo(10L);
        assertThat(materialIds(groups.get(0))).containsExactly(101L);
    }

    @Test
    @DisplayName("입력으로 준 자료가 출력에 아예 없으면 LEAVE로 되살린다")
    void recoversMissingMembers() {
        List<ProposalCandidate> candidates = List.of(
                candidate(101L, "os.pdf", "운영체제 강의계획서"),
                candidate(102L, "unknown.pdf", "알 수 없는 내용"));

        List<ProposalGroupResponse> groups = normalizer.normalize(candidates,
                payload(linkExisting(10L, member(101L, "운영체제", EvidenceSource.CONTENT))),
                List.of(OS));

        ProposalGroupResponse leaveGroup = leaveGroup(groups);
        assertThat(leaveGroup).isNotNull();
        assertThat(materialIds(leaveGroup)).containsExactly(102L);
        assertThat(leaveGroup.getMembers().get(0).getMaterialType()).isEqualTo(MaterialType.OTHER);
    }

    @Test
    @DisplayName("같은 자료가 같은 자리로 두 번 나오면 단순 중복이므로 첫 번째만 남긴다")
    void keepsFirstOfIdenticalDuplicates() {
        List<ProposalCandidate> candidates = List.of(candidate(101L, "os.pdf", "운영체제 강의계획서"));

        List<ProposalGroupResponse> groups = normalizer.normalize(candidates,
                payload(
                        linkExisting(10L, member(101L, "운영체제", EvidenceSource.CONTENT)),
                        linkExisting(10L, member(101L, "운영체제", EvidenceSource.CONTENT))),
                List.of(OS));

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).getAction()).isEqualTo(ProposalAction.LINK_EXISTING);
        assertThat(materialIds(groups.get(0))).containsExactly(101L);
    }

    @Test
    @DisplayName("같은 자료가 서로 다른 목적지로 나오면 양쪽에서 빼고 LEAVE로 옮긴다")
    void movesConflictingDuplicatesToLeave() {
        List<ProposalCandidate> candidates = List.of(
                candidate(101L, "os.pdf", "운영체제 강의계획서"),
                candidate(102L, "ds.pdf", "자료구조 강의계획서"));

        List<ProposalGroupResponse> groups = normalizer.normalize(candidates,
                payload(
                        linkExisting(10L, member(101L, "운영체제", EvidenceSource.CONTENT),
                                member(102L, "자료구조", EvidenceSource.CONTENT)),
                        createAndLink("네트워크", member(101L, "운영체제", EvidenceSource.CONTENT))),
                List.of(OS));

        ProposalGroupResponse leaveGroup = leaveGroup(groups);
        assertThat(leaveGroup).isNotNull();
        assertThat(materialIds(leaveGroup)).containsExactly(101L);

        assertThat(groups).filteredOn(g -> g.getAction() != ProposalAction.LEAVE)
                .singleElement()
                .satisfies(g -> assertThat(materialIds(g)).containsExactly(102L));
    }

    @Test
    @DisplayName("기존 프로젝트와 이름이 같아도 LINK_EXISTING으로 바꾸지 않는다 — 후보를 실어 사용자가 고르게 한다")
    void keepsCreateOnTitleCollision() {
        List<ProposalCandidate> candidates = List.of(candidate(101L, "ds.pdf", "자료구조 강의계획서"));

        List<ProposalGroupResponse> groups = normalizer.normalize(candidates,
                payload(createAndLink("자료구조", member(101L, "자료구조", EvidenceSource.CONTENT))),
                List.of(DS));

        assertThat(groups).hasSize(1);
        ProposalGroupResponse group = groups.get(0);
        assertThat(group.getAction()).isEqualTo(ProposalAction.CREATE_AND_LINK);
        assertThat(group.getExistingCourseId()).isNull();
        assertThat(group.isDefaultSelected()).isFalse();
        assertThat(group.getNotices()).contains(ProposalNormalizer.NOTICE_SAME_TITLE_AS_EXISTING);
        assertThat(group.getMatchingProjects()).containsExactly(DS);
    }

    @Test
    @DisplayName("동명 프로젝트가 둘이면 둘 다 후보로 싣는다")
    void carriesEveryMatchingProject() {
        ProjectRef otherDs = new ProjectRef(12L, "자료 구조");
        List<ProposalCandidate> candidates = List.of(candidate(101L, "ds.pdf", "자료구조 강의계획서"));

        List<ProposalGroupResponse> groups = normalizer.normalize(candidates,
                payload(createAndLink("자료구조", member(101L, "자료구조", EvidenceSource.CONTENT))),
                List.of(DS, otherDs));

        assertThat(groups.get(0).getMatchingProjects()).containsExactly(DS, otherDs);
    }

    @Test
    @DisplayName("서로 다른 묶음이 같은 이름을 제안해도 병합하지 않고 양쪽 체크를 푼다")
    void doesNotMergeGroupsWithSameProposedTitle() {
        List<ProposalCandidate> candidates = List.of(
                candidate(101L, "net1.pdf", "네트워크 강의계획서"),
                candidate(102L, "net2.pdf", "네트워크 교재 목차"));

        List<ProposalGroupResponse> groups = normalizer.normalize(candidates,
                payload(
                        createAndLink("네트워크", member(101L, "네트워크", EvidenceSource.CONTENT)),
                        createAndLink("네트워크", member(102L, "네트워크", EvidenceSource.CONTENT))),
                List.of());

        assertThat(groups).hasSize(2);
        assertThat(groups).allSatisfy(g -> {
            assertThat(g.getAction()).isEqualTo(ProposalAction.CREATE_AND_LINK);
            assertThat(g.isDefaultSelected()).isFalse();
            assertThat(g.getNotices()).contains(ProposalNormalizer.NOTICE_DUPLICATE_PROPOSED_TITLE);
        });
    }

    @Test
    @DisplayName("LEAVE 묶음이 여럿이면 하나로 병합한다")
    void mergesLeaveGroups() {
        List<ProposalCandidate> candidates = List.of(
                candidate(101L, "a.pdf", "알 수 없음"),
                candidate(102L, "b.pdf", "알 수 없음"));

        List<ProposalGroupResponse> groups = normalizer.normalize(candidates,
                payload(
                        leave(member(101L, "a.pdf", EvidenceSource.FILENAME_ONLY)),
                        leave(member(102L, "b.pdf", EvidenceSource.FILENAME_ONLY))),
                List.of());

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).getAction()).isEqualTo(ProposalAction.LEAVE);
        assertThat(materialIds(groups.get(0))).containsExactly(101L, 102L);
    }

    @Test
    @DisplayName("본문 근거라고 신고했는데 발췌에 없으면 evidenceVerified만 false다 — 신고는 덮어쓰지 않는다")
    void marksFabricatedEvidenceUnverified() {
        List<ProposalCandidate> candidates = List.of(candidate(101L, "os.pdf", "운영체제 강의계획서"));

        List<ProposalGroupResponse> groups = normalizer.normalize(candidates,
                payload(linkExisting(10L, member(101L, "이 문장은 발췌에 없다", EvidenceSource.CONTENT))),
                List.of(OS));

        ProposalMemberResponse member = groups.get(0).getMembers().get(0);
        assertThat(member.getEvidenceSource()).isEqualTo(EvidenceSource.CONTENT);
        assertThat(member.isEvidenceVerified()).isFalse();
        assertThat(groups.get(0).isDefaultSelected()).isFalse();
    }

    @Test
    @DisplayName("발췌가 빈 자료에 CONTENT를 신고하면 대조할 대상이 없으므로 미검증이다")
    void treatsEmptyExcerptAsUnverified() {
        List<ProposalCandidate> candidates = List.of(candidate(101L, "scan.pdf", "   "));

        List<ProposalGroupResponse> groups = normalizer.normalize(candidates,
                payload(linkExisting(10L, member(101L, "운영체제", EvidenceSource.CONTENT))),
                List.of(OS));

        assertThat(groups.get(0).getMembers().get(0).isEvidenceVerified()).isFalse();
        assertThat(groups.get(0).isDefaultSelected()).isFalse();
    }

    @Test
    @DisplayName("evidenceSource가 null이면 켜지지 않는다 — 판정은 화이트리스트다")
    void nullEvidenceSourceIsNotSelectable() {
        List<ProposalCandidate> candidates = List.of(candidate(101L, "os.pdf", "운영체제 강의계획서"));

        List<ProposalGroupResponse> groups = normalizer.normalize(candidates,
                payload(linkExisting(10L, member(101L, "운영체제", null))),
                List.of(OS));

        assertThat(groups.get(0).getMembers().get(0).getEvidenceSource()).isNull();
        assertThat(groups.get(0).isDefaultSelected()).isFalse();
    }

    @Test
    @DisplayName("멤버 하나만 미검증이어도 그룹 전체가 꺼진 채로 뜬다")
    void oneUnverifiedMemberTurnsGroupOff() {
        List<ProposalCandidate> candidates = List.of(
                candidate(101L, "os1.pdf", "운영체제 강의계획서"),
                candidate(102L, "os2.pdf", "운영체제 교재 목차"));

        List<ProposalGroupResponse> groups = normalizer.normalize(candidates,
                payload(linkExisting(10L,
                        member(101L, "운영체제 강의계획서", EvidenceSource.CONTENT),
                        member(102L, "발췌에 없는 문장", EvidenceSource.CONTENT))),
                List.of(OS));

        assertThat(groups.get(0).getMembers()).extracting(ProposalMemberResponse::isEvidenceVerified)
                .containsExactly(true, false);
        assertThat(groups.get(0).isDefaultSelected()).isFalse();
    }

    @Test
    @DisplayName("모든 멤버가 본문 근거로 검증되면 켜진 채로 뜬다")
    void selectableWhenEveryMemberIsVerified() {
        List<ProposalCandidate> candidates = List.of(
                candidate(101L, "os1.pdf", "운영체제 강의계획서"),
                candidate(102L, "os2.pdf", "운영체제 교재 목차"));

        List<ProposalGroupResponse> groups = normalizer.normalize(candidates,
                payload(linkExisting(10L,
                        member(101L, "운영체제 강의계획서", EvidenceSource.CONTENT),
                        member(102L, "운영체제 교재 목차", EvidenceSource.CONTENT))),
                List.of(OS));

        assertThat(groups.get(0).isDefaultSelected()).isTrue();
        assertThat(groups.get(0).getExistingCourseTitle()).isEqualTo("운영체제");
    }

    @Test
    @DisplayName("전부 LEAVE거나 전부 미검증이면 켤 그룹이 하나도 없다 — 자동 표시 게이트가 이 결과에 의존한다")
    void nothingSelectableWhenEverythingIsUncertain() {
        List<ProposalCandidate> candidates = List.of(
                candidate(101L, "a.pdf", "알 수 없음"),
                candidate(102L, "net.pdf", "네트워크 개론"));

        List<ProposalGroupResponse> groups = normalizer.normalize(candidates,
                payload(
                        leave(member(101L, "a.pdf", EvidenceSource.FILENAME_ONLY)),
                        createAndLink("네트워크", member(102L, "net.pdf", EvidenceSource.FILENAME_ONLY))),
                List.of());

        assertThat(groups).isNotEmpty();
        assertThat(groups).noneMatch(ProposalGroupResponse::isDefaultSelected);
    }

    @Test
    @DisplayName("빈 제목의 CREATE_AND_LINK와 null materialType은 조용히 바로잡는다")
    void fixesFormatDefects() {
        List<ProposalCandidate> candidates = List.of(candidate(101L, "a.pdf", "내용"));
        LinkProposalPayload.ProposalMember typeless =
                new LinkProposalPayload.ProposalMember(101L, null, "a.pdf", EvidenceSource.FILENAME_ONLY);

        List<ProposalGroupResponse> groups = normalizer.normalize(candidates,
                payload(new LinkProposalPayload.ProposalGroup(
                        ProposalAction.CREATE_AND_LINK, null, "   ", null, List.of(typeless))),
                List.of());

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).getAction()).isEqualTo(ProposalAction.LEAVE);
        assertThat(groups.get(0).getMembers().get(0).getMaterialType()).isEqualTo(MaterialType.OTHER);
        assertThat(groups.get(0).getReason()).isEmpty();
    }
}
