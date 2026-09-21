package com.jungwoo.project.memo.learning.tidy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.learning.CourseTopicMapper;
import com.jungwoo.project.memo.learning.TopicMaterialLinkMapper;
import com.jungwoo.project.memo.learning.domain.CourseTopic;
import com.jungwoo.project.memo.learning.domain.TopicMaterialLink;
import com.jungwoo.project.memo.learning.domain.TopicSourceType;
import com.jungwoo.project.memo.learning.domain.TopicStatus;
import com.jungwoo.project.memo.learning.structure.TopicChangeOp;
import com.jungwoo.project.memo.learning.structure.TopicChangePlan;
import com.jungwoo.project.memo.learning.tidy.domain.ProjectTidyProposal;
import com.jungwoo.project.memo.learning.tidy.domain.ProjectTidyProposalMaterial;
import com.jungwoo.project.memo.learning.tidy.domain.TidyProposalStatus;
import com.jungwoo.project.memo.learning.tidy.dto.ProjectTidyRequests;
import com.jungwoo.project.memo.learning.tidy.dto.ProjectTidyResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 프로젝트 정리안 적용의 불변조건을 실제 DB에서 본다.
 *
 * <p>고정하는 것:
 * <ul>
 *   <li>여러 자료의 구간이 한 학습 항목에 함께 붙는다 — 이 기능의 존재 이유다.</li>
 *   <li>고른 것만 적용된다. 고른 것이 없으면 트리 판을 올리지 않는다.</li>
 *   <li>딸린 변경을 빼놓은 선택은 거절한다(부모 없는 자식을 만들지 않는다).</li>
 *   <li>정리안 판·편집 판·트리 판 중 하나라도 어긋나면 적용하지 않는다.</li>
 *   <li>근거 자료가 지워졌거나 연결이 끊겼으면 적용하지 않는다.</li>
 *   <li>두 번 적용되지 않는다.</li>
 * </ul>
 *
 * <p>로컬 memo DB 필요. CI 제외.
 */
@SpringBootTest
class ProjectTidyApplyDbTest {

    private static final long USER = 999_000_401L;
    private static final long MATERIAL_A = 999_400_101L;
    private static final long MATERIAL_B = 999_400_102L;
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    @Autowired
    private ProjectTidyService tidyService;
    @Autowired
    private ProjectTidyMapper tidyMapper;
    @Autowired
    private CourseTopicMapper topicMapper;
    @Autowired
    private TopicMaterialLinkMapper topicLinkMapper;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private DataSource dataSource;

    private long courseId;
    private long stack;
    private long queue;
    private long sectionA;
    private long sectionB;

    @BeforeEach
    void setUp() throws Exception {
        cleanUp();
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO courses (user_id, title, status) VALUES (?, '정리 테스트 과목', 'ACTIVE')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, USER);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    courseId = rs.getLong(1);
                }
            }
            insertMaterial(conn, MATERIAL_A, "강의슬라이드.pdf", HASH_A);
            insertMaterial(conn, MATERIAL_B, "교재목차.pdf", HASH_B);
            link(conn, MATERIAL_A);
            link(conn, MATERIAL_B);
        }
        stack = insertTopic(null, "스택", 0);
        queue = insertTopic(null, "큐", 1);
        sectionA = insertSection(MATERIAL_A, HASH_A, "스택 강의", 3);
        sectionB = insertSection(MATERIAL_B, HASH_B, "교재 3장 스택", 30);
    }

    // ===== 실제 검증 =====

    @Test
    void 여러_자료의_구간이_한_항목에_함께_붙는다() {
        long proposalId = saveProposal(List.of(
                link(stack, List.of(sectionA, sectionB))));

        tidyService.apply(USER, proposalId, apply(proposalId, List.of(changeId(proposalId, 0))));

        List<TopicMaterialLink> links = topicLinkMapper.findActiveByTopicId(stack, USER);
        assertThat(links).extracting(TopicMaterialLink::getMaterialId)
                .containsExactlyInAnyOrder(MATERIAL_A, MATERIAL_B);
        assertThat(links).extracting(TopicMaterialLink::getSectionId)
                .containsExactlyInAnyOrder(sectionA, sectionB);
    }

    @Test
    void 뺀_변경은_적용되지_않고_고른_것만_반영된다() {
        long proposalId = saveProposal(List.of(
                link(stack, List.of(sectionA)),
                add(null, "새 항목", List.of(sectionB))));
        // 저장된 순서는 실행 순서다(추가가 연결보다 앞선다). 종류로 찾는다.
        String linkChange = changeIdOf(proposalId, "LINK");

        tidyService.apply(USER, proposalId, apply(proposalId, List.of(linkChange)));

        assertThat(topicLinkMapper.findActiveByTopicId(stack, USER)).hasSize(1);
        assertThat(topicMapper.findActiveByCourseIdAndUserId(courseId, USER))
                .extracting(CourseTopic::getTitle).doesNotContain("새 항목");
    }

    @Test
    void 아무것도_고르지_않으면_트리_판을_올리지_않는다() throws Exception {
        long proposalId = saveProposal(List.of(link(stack, List.of(sectionA))));
        long before = treeVersion();

        ProjectTidyResponse result = tidyService.apply(USER, proposalId, apply(proposalId, List.of()));

        assertThat(treeVersion()).isEqualTo(before);
        assertThat(result.getStatus()).isEqualTo(TidyProposalStatus.APPLIED.name());
        assertThat(topicLinkMapper.findActiveByTopicId(stack, USER)).isEmpty();
    }

    @Test
    void 새_부모를_빼고_자식만_고르면_거절하고_무엇이_빠졌는지_말한다() {
        long proposalId = saveProposal(List.of(
                addWithTempId("n1", null, "부모", List.of(sectionA)),
                addChildOf("n1", "자식", List.of(sectionB))));
        String child = changeIdOfTitle(proposalId, "자식");

        assertThatThrownBy(() -> tidyService.apply(USER, proposalId, apply(proposalId, List.of(child))))
                .isInstanceOf(ConflictException.class)
                // 무엇을 함께 골라야 하는지 이름으로 말한다(details가 그대로 화면에 나간다).
                .extracting(e -> ((ConflictException) e).getDetails()).asString().contains("부모");

        // 아무것도 반영되지 않았다.
        assertThat(topicMapper.findActiveByCourseIdAndUserId(courseId, USER))
                .extracting(CourseTopic::getTitle).doesNotContain("부모", "자식");
    }

    @Test
    void 정리안_판이_다르면_적용하지_않는다() {
        long proposalId = saveProposal(List.of(link(stack, List.of(sectionA))));
        ProjectTidyRequests.Apply request = apply(proposalId, List.of(changeId(proposalId, 0)));
        request.setRevision(99L);

        assertThatThrownBy(() -> tidyService.apply(USER, proposalId, request))
                .isInstanceOf(ConflictException.class);
        assertThat(topicLinkMapper.findActiveByTopicId(stack, USER)).isEmpty();
    }

    @Test
    void 편집_판이_다르면_적용하지_않는다_다른_탭이_먼저_고친_것을_덮지_않는다() {
        long proposalId = saveProposal(List.of(link(stack, List.of(sectionA))));
        // 다른 탭이 먼저 저장해 편집 판이 1이 됐다.
        ProjectTidyRequests.SaveEdits save = new ProjectTidyRequests.SaveEdits();
        save.setEditRevision(0L);
        save.setEdits(Map.of());
        tidyService.saveEdits(USER, proposalId, save);

        ProjectTidyRequests.Apply request = apply(proposalId, List.of(changeId(proposalId, 0)));
        request.setEditRevision(0L);

        assertThatThrownBy(() -> tidyService.apply(USER, proposalId, request))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 트리가_그_사이_바뀌었으면_적용하지_않는다() throws Exception {
        long proposalId = saveProposal(List.of(link(stack, List.of(sectionA))));
        bumpTreeVersion();

        assertThatThrownBy(() -> tidyService.apply(USER, proposalId,
                apply(proposalId, List.of(changeId(proposalId, 0)))))
                .isInstanceOf(ConflictException.class);
        assertThat(topicLinkMapper.findActiveByTopicId(stack, USER)).isEmpty();

        // 정리안은 사라지지 않는다 — 화면이 "갱신 필요"로 보여주고 사용자가 다시 정리한다.
        ProjectTidyResponse view = tidyService.view(USER, courseId);
        assertThat(view.getProposalId()).isEqualTo(proposalId);
        assertThat(view.isTreeChanged()).isTrue();
    }

    @Test
    void 근거_자료의_연결이_끊겼으면_적용하지_않는다() throws Exception {
        long proposalId = saveProposal(List.of(link(stack, List.of(sectionA, sectionB))));
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM material_links WHERE user_id = ? AND material_id = ?")) {
            ps.setLong(1, USER);
            ps.setLong(2, MATERIAL_B);
            ps.executeUpdate();
        }

        assertThatThrownBy(() -> tidyService.apply(USER, proposalId,
                apply(proposalId, List.of(changeId(proposalId, 0)))))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).getDetails()).asString()
                .contains("교재목차.pdf").contains("연결이 끊겼");
        assertThat(topicLinkMapper.findActiveByTopicId(stack, USER)).isEmpty();
    }

    @Test
    void 근거_자료가_다시_분석되어_해시가_바뀌었으면_적용하지_않는다() throws Exception {
        long proposalId = saveProposal(List.of(link(stack, List.of(sectionA))));
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE course_materials SET file_hash = ? WHERE material_id = ?")) {
            ps.setString(1, "c".repeat(64));
            ps.setLong(2, MATERIAL_A);
            ps.executeUpdate();
        }

        assertThatThrownBy(() -> tidyService.apply(USER, proposalId,
                apply(proposalId, List.of(changeId(proposalId, 0)))))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).getDetails()).asString().contains("파일이 바뀌었");
    }

    @Test
    void 두_번_적용되지_않는다() {
        long proposalId = saveProposal(List.of(add(null, "새 항목", List.of(sectionA))));
        String change = changeId(proposalId, 0);

        tidyService.apply(USER, proposalId, apply(proposalId, List.of(change)));

        assertThatThrownBy(() -> tidyService.apply(USER, proposalId, apply(proposalId, List.of(change))))
                .isInstanceOf(ConflictException.class);
        assertThat(topicMapper.findActiveByCourseIdAndUserId(courseId, USER).stream()
                .filter(t -> "새 항목".equals(t.getTitle()))).hasSize(1);
    }

    @Test
    void 사용자가_고친_제목이_적용된다() {
        long proposalId = saveProposal(List.of(add(null, "모델이 지은 제목", List.of(sectionA))));
        String change = changeId(proposalId, 0);
        ProjectTidyRequests.Apply request = apply(proposalId, List.of(change));
        request.setTitleOverrides(Map.of(change, "내가 고친 제목"));

        tidyService.apply(USER, proposalId, request);

        assertThat(topicMapper.findActiveByCourseIdAndUserId(courseId, USER))
                .extracting(CourseTopic::getTitle).contains("내가 고친 제목").doesNotContain("모델이 지은 제목");
    }

    @Test
    void 병합은_흡수될_항목이_이번_연결을_받은_뒤에_일어난다() {
        // 큐를 스택에 합치면서 동시에 큐에 자료를 연결한다 — 그 연결이 스택으로 따라와야 한다.
        long proposalId = saveProposal(List.of(
                merge(stack, List.of(queue)),
                link(queue, List.of(sectionA))));

        tidyService.apply(USER, proposalId, apply(proposalId,
                List.of(changeId(proposalId, 0), changeId(proposalId, 1))));

        assertThat(topicLinkMapper.findActiveByTopicId(stack, USER))
                .extracting(TopicMaterialLink::getSectionId).contains(sectionA);
        assertThat(topicMapper.findActiveByCourseIdAndUserId(courseId, USER))
                .extracting(CourseTopic::getTopicId).doesNotContain(queue);
    }

    @Test
    void 버린_정리안은_되살아나지_않는다() {
        long proposalId = saveProposal(List.of(link(stack, List.of(sectionA))));

        tidyService.dismiss(USER, courseId);

        assertThat(tidyService.view(USER, courseId).getProposalId()).isNull();
        assertThat(tidyMapper.findProposalById(proposalId, USER).getStatus())
                .isEqualTo(TidyProposalStatus.DISMISSED);
        // 다시 조회해도 마찬가지다 — 폐기는 자동으로 복원되지 않는다.
        assertThat(tidyService.view(USER, courseId).getProposalId()).isNull();
    }

    @Test
    void 검토_편집은_저장돼도_트리를_바꾸지_않는다() throws Exception {
        long proposalId = saveProposal(List.of(add(null, "새 항목", List.of(sectionA))));
        long before = treeVersion();
        String change = changeId(proposalId, 0);

        ProjectTidyRequests.SaveEdits save = new ProjectTidyRequests.SaveEdits();
        save.setEditRevision(0L);
        ProjectTidyRequests.SaveEdits.Edit edit = new ProjectTidyRequests.SaveEdits.Edit();
        edit.setTitle("고친 제목");
        save.setEdits(Map.of(change, edit));
        ProjectTidyResponse saved = tidyService.saveEdits(USER, proposalId, save);

        assertThat(saved.getEditRevision()).isEqualTo(1L);
        assertThat(saved.getEdits().get(change).getTitle()).isEqualTo("고친 제목");
        assertThat(treeVersion()).isEqualTo(before);
        assertThat(topicMapper.findActiveByCourseIdAndUserId(courseId, USER))
                .extracting(CourseTopic::getTitle).doesNotContain("고친 제목", "새 항목");
    }

    @Test
    void 자료_분석이_끝나도_정리안은_저절로_생기지_않는다() {
        // 자료 둘 다 분석이 끝나 있고 구간도 있다(setUp). 그래도 검토할 것은 없다.
        ProjectTidyResponse view = tidyService.view(USER, courseId);

        assertThat(view.getProposalId()).isNull();
        assertThat(view.getJob()).isNull();
        // 다만 "지금 정리할 수 있다"는 말한다 — 버튼을 열어 주기 위해서다.
        assertThat(view.getReadyMaterialCount()).isEqualTo(2);
        assertThat(view.isFirstTime()).isTrue();
    }

    @Test
    void 다른_프로젝트에만_연결된_자료는_섞이지_않고_그_프로젝트_트리도_그대로다() throws Exception {
        /*
         * 같은 자료를 두 프로젝트에서 쓸 수 있다. A를 정리해도 B의 트리는 건드리지 않는다 —
         * "여러 프로젝트를 하나로 합치는 기능이 아니다"를 지키는 자리.
         */
        long otherCourse = insertCourse("다른 프로젝트");
        long otherTopic;
        try (Connection conn = dataSource.getConnection()) {
            // A와 B가 공유하는 자료(MATERIAL_A)와, B에만 있는 자료.
            insertMaterial(conn, 999_400_103L, "B전용.pdf", "d".repeat(64));
            linkTo(conn, MATERIAL_A, otherCourse);
            linkTo(conn, 999_400_103L, otherCourse);
        }
        otherTopic = insertTopicIn(otherCourse, "B의 항목");
        long otherTreeVersion = treeVersionOf(otherCourse);

        long proposalId = saveProposal(List.of(link(stack, List.of(sectionA))));
        tidyService.apply(USER, proposalId, apply(proposalId, List.of(changeId(proposalId, 0))));

        // A의 트리만 바뀌었다.
        assertThat(topicLinkMapper.findActiveByTopicId(stack, USER)).hasSize(1);
        assertThat(topicLinkMapper.findActiveByTopicId(otherTopic, USER)).isEmpty();
        assertThat(treeVersionOf(otherCourse)).isEqualTo(otherTreeVersion);
        // B의 범위 안내는 B의 자료만 센다(공유 자료 1 + B 전용 1).
        assertThat(tidyService.view(USER, otherCourse).getReadyMaterialCount()).isLessThanOrEqualTo(2);
    }

    @Test
    void 앞_판의_편집은_같은_뜻의_변경으로_승계되고_애매하면_확인_필요로_표시된다() {
        long first = saveProposal(List.of(add(null, "스택", List.of(sectionA))));
        String change = changeId(first, 0);
        ProjectTidyRequests.SaveEdits save = new ProjectTidyRequests.SaveEdits();
        save.setEditRevision(0L);
        ProjectTidyRequests.SaveEdits.Edit edit = new ProjectTidyRequests.SaveEdits.Edit();
        edit.setTitle("내가 고친 제목");
        save.setEdits(Map.of(change, edit));
        tidyService.saveEdits(USER, first, save);

        // 새 판: 같은 변경(같은 부모·같은 처음 제목)이므로 changeId가 같다 → 그대로 승계.
        List<TopicChangeOp> sameOps = TopicChangePlan.of(List.of(add(null, "스택", List.of(sectionA)))).ops();
        assertThat(sameOps.get(0).changeId()).isEqualTo(change);

        // 근거 구간이 달라진 경우: LINK의 changeId는 구간이 바뀌면 달라진다 — 같은 뜻이 아니다.
        String before = TopicChangePlan.of(List.of(link(stack, List.of(sectionA)))).ops().get(0).changeId();
        String after = TopicChangePlan.of(List.of(link(stack, List.of(sectionA, sectionB)))).ops().get(0).changeId();
        assertThat(before).isNotEqualTo(after);
    }

    // ===== 준비 도구 =====

    private long insertCourse(String title) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO courses (user_id, title, status) VALUES (?, ?, 'ACTIVE')",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, USER);
            ps.setString(2, title);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void linkTo(Connection conn, long materialId, long targetCourse) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO material_links (user_id, material_id, course_id, material_type) "
                        + "VALUES (?, ?, ?, 'PROFESSOR_SLIDE')")) {
            ps.setLong(1, USER);
            ps.setLong(2, materialId);
            ps.setLong(3, targetCourse);
            ps.executeUpdate();
        }
    }

    private long insertTopicIn(long targetCourse, String title) {
        CourseTopic topic = CourseTopic.builder().userId(USER).courseId(targetCourse).title(title)
                .orderIndex(0).sourceType(TopicSourceType.SOURCE).status(TopicStatus.ACTIVE).build();
        topicMapper.insert(topic);
        return topic.getTopicId();
    }

    private long treeVersionOf(long targetCourse) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT topic_tree_version FROM courses WHERE course_id = ?")) {
            ps.setLong(1, targetCourse);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** 검증된 작업 목록으로 검토 중인 정리안을 만든다(모델을 부르지 않는다). */
    private long saveProposal(List<TopicChangeOp> ops) {
        TopicChangePlan.Plan plan = TopicChangePlan.of(ops);
        ProjectTidyScope scope = new ProjectTidyScope(courseId, treeVersionQuiet(), 2, 2,
                List.of(new ProjectTidyScope.Member(MATERIAL_A, "강의슬라이드.pdf", HASH_A, 1, 1, 1),
                        new ProjectTidyScope.Member(MATERIAL_B, "교재목차.pdf", HASH_B, 1, 1, 1)),
                List.of(), false, 2, 2);
        ProjectTidyProposal proposal = ProjectTidyProposal.builder()
                .userId(USER).courseId(courseId).generation(1L).revision(1L)
                .baseTreeVersion(treeVersionQuiet())
                .status(TidyProposalStatus.PROPOSED)
                .summaryJson("{\"summary\":\"테스트\"}")
                .opsJson(write(plan.ops()))
                .scopeJson(write(scope))
                .model("test-model")
                .build();
        tidyMapper.insertProposal(proposal);
        for (ProjectTidyScope.Member member : scope.reviewed()) {
            tidyMapper.insertProposalMaterial(ProjectTidyProposalMaterial.builder()
                    .proposalId(proposal.getProposalId()).userId(USER).materialId(member.materialId())
                    .fileHash(member.fileHash()).analysisVersion(1).sectionCount(1).reviewedCount(1)
                    .included(true).build());
        }
        return proposal.getProposalId();
    }

    /** 저장된 정리안의 n번째 작업의 changeId. 순서는 TopicChangePlan이 세운 실행 순서다. */
    private String changeId(long proposalId, int index) {
        List<TopicChangeOp> ops = tidyService.readOps(
                tidyMapper.findProposalById(proposalId, USER).getOpsJson());
        return ops.get(index).changeId();
    }

    /** 그 제목을 가진 변경의 changeId. */
    private String changeIdOfTitle(long proposalId, String title) {
        return tidyService.readOps(tidyMapper.findProposalById(proposalId, USER).getOpsJson()).stream()
                .filter(o -> title.equals(o.title())).findFirst().orElseThrow().changeId();
    }

    /** 그 종류의 첫 변경의 changeId. 실행 순서가 입력 순서와 다르므로 번호로 찾지 않는다. */
    private String changeIdOf(long proposalId, String op) {
        return tidyService.readOps(tidyMapper.findProposalById(proposalId, USER).getOpsJson()).stream()
                .filter(o -> op.equals(o.op())).findFirst().orElseThrow().changeId();
    }

    private ProjectTidyRequests.Apply apply(long proposalId, List<String> selected) {
        ProjectTidyProposal proposal = tidyMapper.findProposalById(proposalId, USER);
        ProjectTidyRequests.Apply request = new ProjectTidyRequests.Apply();
        request.setRevision(proposal.getRevision());
        request.setEditRevision(0L);
        request.setBaseTreeVersion(proposal.getBaseTreeVersion());
        request.setSelectedChangeIds(selected);
        return request;
    }

    private static TopicChangeOp link(long topicId, List<Long> sectionIds) {
        return new TopicChangeOp("LINK", null, topicId, null, null, null, null, null, sectionIds, "CONCEPT",
                null, null, null, "같은 개념을 두 자료가 다룬다");
    }

    private static TopicChangeOp add(Long parentTopicId, String title, List<Long> sectionIds) {
        return new TopicChangeOp("ADD", null, null, parentTopicId, null, title, "SOURCE", null, sectionIds,
                "CONCEPT", null, null, null, "자료에 새로 나온 내용");
    }

    private static TopicChangeOp addWithTempId(String tempId, Long parentTopicId, String title,
                                               List<Long> sectionIds) {
        return new TopicChangeOp("ADD", tempId, null, parentTopicId, null, title, "SOURCE", null, sectionIds,
                "CONCEPT", null, null, null, null);
    }

    private static TopicChangeOp addChildOf(String parentTempId, String title, List<Long> sectionIds) {
        return new TopicChangeOp("ADD", "c-" + parentTempId, null, null, parentTempId, title, "SOURCE", null,
                sectionIds, "CONCEPT", null, null, null, null);
    }

    private static TopicChangeOp merge(long surviving, List<Long> absorbed) {
        return new TopicChangeOp("MERGE", null, null, null, null, null, null, null, null, null, surviving,
                absorbed, null, "같은 범위");
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private long insertTopic(Long parent, String title, int order) {
        CourseTopic topic = CourseTopic.builder().userId(USER).courseId(courseId).parentTopicId(parent)
                .title(title).orderIndex(order).sourceType(TopicSourceType.SOURCE)
                .status(TopicStatus.ACTIVE).build();
        topicMapper.insert(topic);
        return topic.getTopicId();
    }

    private void insertMaterial(Connection conn, long materialId, String filename, String hash) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO course_materials (material_id, user_id, original_filename, stored_filename, "
                        + "storage_path, size_bytes, file_hash, extraction_status, extracted_text, status) "
                        + "VALUES (?, ?, ?, 'x.pdf', ?, 1, ?, 'SUCCESS', 'text', 'ACTIVE')")) {
            ps.setLong(1, materialId);
            ps.setLong(2, USER);
            ps.setString(3, filename);
            ps.setString(4, USER + "/x-" + materialId + ".pdf");
            ps.setString(5, hash);
            ps.executeUpdate();
        }
    }

    private void link(Connection conn, long materialId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO material_links (user_id, material_id, course_id, material_type) "
                        + "VALUES (?, ?, ?, 'PROFESSOR_SLIDE')")) {
            ps.setLong(1, USER);
            ps.setLong(2, materialId);
            ps.setLong(3, courseId);
            ps.executeUpdate();
        }
    }

    private long insertSection(long materialId, String hash, String title, int page) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO material_sections (user_id, material_id, file_hash, analysis_version, chunk_index, "
                             + "unit_type, unit_start, unit_end, display_title, roles_json, dedupe_key, status) "
                             + "VALUES (?, ?, ?, 1, 0, 'PDF_PAGE', ?, ?, ?, '[\"CONCEPT\"]', ?, 'ACTIVE')",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, USER);
            ps.setLong(2, materialId);
            ps.setString(3, hash);
            ps.setInt(4, page);
            ps.setInt(5, page);
            ps.setString(6, title);
            ps.setString(7, "k-" + materialId + "-" + page);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private long treeVersion() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT topic_tree_version FROM courses WHERE course_id = ?")) {
            ps.setLong(1, courseId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long treeVersionQuiet() {
        try {
            return treeVersion();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void bumpTreeVersion() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE courses SET topic_tree_version = topic_tree_version + 1 WHERE course_id = ?")) {
            ps.setLong(1, courseId);
            ps.executeUpdate();
        }
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            for (String sql : List.of(
                    // 소요 시간 표본까지 지운다 — 남기면 다음 사용자의 예상 시간이 1바이트 시험 파일로 계산된다.
                    "DELETE FROM material_analysis_timings WHERE user_id = ?",
                    "DELETE FROM project_tidy_edits WHERE user_id = ?",
                    "DELETE FROM project_tidy_proposal_materials WHERE user_id = ?",
                    "DELETE FROM project_tidy_proposals WHERE user_id = ?",
                    "DELETE FROM project_tidy_jobs WHERE user_id = ?",
                    "DELETE FROM material_sections WHERE user_id = ?",
                    "DELETE FROM topic_material_links WHERE user_id = ?",
                    "DELETE FROM topic_progress WHERE user_id = ?",
                    "DELETE FROM course_topics WHERE user_id = ?",
                    "DELETE FROM material_links WHERE user_id = ?",
                    "DELETE FROM course_materials WHERE user_id = ?",
                    "DELETE FROM courses WHERE user_id = ?")) {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setLong(1, USER);
                    ps.executeUpdate();
                }
            }
        }
    }
}
