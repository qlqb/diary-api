package com.jungwoo.project.memo.learning.tidy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.learning.CourseTopicMapper;
import com.jungwoo.project.memo.learning.domain.CourseTopic;
import com.jungwoo.project.memo.learning.domain.TopicSourceType;
import com.jungwoo.project.memo.learning.domain.TopicStatus;
import com.jungwoo.project.memo.learning.structure.TopicChangeOp;
import com.jungwoo.project.memo.learning.structure.TopicChangePlan;
import com.jungwoo.project.memo.learning.tidy.domain.ProjectTidyEdits;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 판이 바뀔 때 사용자의 편집을 옮기는 규칙.
 *
 * <p>고치는 문제: 예전에는 changeId가 같으면 "같은 변경"으로 보고 그대로 옮겼다. 그런데
 * changeId는 <b>대상 중심의 좁은 이름표</b>다 — RENAME은 바꿀 항목만, SPLIT은 쪼갤 항목만
 * 보고 만든다. 그래서 같은 항목을 전혀 다른 이름으로 바꾸자는 새 제안, 자식 구성이 완전히
 * 달라진 분할, 근거가 바뀐 연결이 모두 앞 판과 같은 이름표를 받는다. 거기에 옛 판단을 말없이
 * 붙이면 사용자는 <b>보지도 않은 제안을 자기가 승인한 것으로</b> 적용하게 된다.
 *
 * <p>그리고 "확인 필요"는 표시만으로는 아무것도 막지 못했다. saveEdits가 요청에 실린 모든
 * 편집의 needsConfirm을 false로 덮어써서, 아무 제목이나 한 글자 고치면 표시가 사라졌다.
 *
 * <p>로컬 memo DB 필요. CI 제외.
 */
@SpringBootTest
class ProjectTidyCarryOverDbTest {

    private static final long USER = 999_000_406L;
    private static final long MATERIAL = 999_400_601L;
    private static final String HASH = "c".repeat(64);

    @Autowired
    private ProjectTidyService tidyService;
    @Autowired
    private ProjectTidyMapper tidyMapper;
    @Autowired
    private ProjectTidyWorker worker;
    @Autowired
    private CourseTopicMapper topicMapper;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private DataSource dataSource;

    private long courseId;
    private long stack;
    private long sectionA;
    private long sectionB;

    @BeforeEach
    void setUp() throws Exception {
        cleanUp();
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO courses (user_id, title, status) VALUES (?, '승계 테스트 과목', 'ACTIVE')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, USER);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    courseId = rs.getLong(1);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO course_materials (material_id, user_id, original_filename, stored_filename, "
                            + "storage_path, size_bytes, file_hash, extraction_status, extracted_text, status) "
                            + "VALUES (?, ?, 'ds.pdf', 'x.pdf', ?, 1, ?, 'SUCCESS', 'text', 'ACTIVE')")) {
                ps.setLong(1, MATERIAL);
                ps.setLong(2, USER);
                ps.setString(3, USER + "/x.pdf");
                ps.setString(4, HASH);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO material_links (user_id, material_id, course_id, material_type) "
                            + "VALUES (?, ?, ?, 'PROFESSOR_SLIDE')")) {
                ps.setLong(1, USER);
                ps.setLong(2, MATERIAL);
                ps.setLong(3, courseId);
                ps.executeUpdate();
            }
        }
        stack = insertTopic("스택");
        sectionA = insertSection(3);
        sectionB = insertSection(9);
    }

    // ===== 무엇을 옮기고 무엇을 묻는가 =====

    @Test
    void 내용까지_같으면_묻지_않고_그대로_옮긴다() {
        TopicChangeOp op = link(stack, List.of(sectionA));
        long previous = saveProposal(List.of(op));
        String changeId = changeIdOf(previous, 0);
        saveEdits(previous, Map.of(changeId, edit(true, null)));

        long next = supersedeWith(previous, List.of(link(stack, List.of(sectionA))));

        Map<String, ProjectTidyService.EditValue> carried = readEdits(next);
        assertThat(carried).containsKey(changeId);
        assertThat(carried.get(changeId).excluded()).isTrue();
        assertThat(carried.get(changeId).needsConfirm()).isFalse();
    }

    @Test
    void 같은_항목의_이름_보완이라도_제안한_이름이_바뀌면_확인을_받는다() {
        // RENAME의 changeId는 대상 항목만 보고 만든다 — 제안한 이름이 달라도 같은 이름표다.
        long previous = saveProposal(List.of(rename(stack, "스택(LIFO)")));
        String changeId = changeIdOf(previous, 0);
        saveEdits(previous, Map.of(changeId, edit(false, "내가 고친 이름")));

        long next = supersedeWith(previous, List.of(rename(stack, "스택 자료구조")));

        Map<String, ProjectTidyService.EditValue> carried = readEdits(next);
        assertThat(carried.get(changeId).title()).isEqualTo("내가 고친 이름");
        assertThat(carried.get(changeId).needsConfirm())
                .as("같은 이름표라도 제안 내용이 바뀌었으면 사용자가 확인해야 한다").isTrue();
        assertThat(carried.get(changeId).carriedFrom().reason()).contains("제안한 이름");
    }

    @Test
    void 근거_구간이_바뀐_연결은_확인을_받는다() {
        long previous = saveProposal(List.of(link(stack, List.of(sectionA))));
        String changeId = changeIdOf(previous, 0);
        saveEdits(previous, Map.of(changeId, edit(true, null)));

        // LINK의 changeId는 구간까지 보므로 id가 달라진다 → 느슨한 짝짓기로만 이어진다.
        long next = supersedeWith(previous, List.of(link(stack, List.of(sectionB))));

        Map<String, ProjectTidyService.EditValue> carried = readEdits(next);
        assertThat(carried).hasSize(1);
        assertThat(carried.values().iterator().next().needsConfirm()).isTrue();
    }

    @Test
    void 같은_항목의_분할이라도_나눌_구성이_바뀌면_확인을_받는다() {
        long previous = saveProposal(List.of(split(stack, List.of("배열 스택", "연결 스택"))));
        String changeId = changeIdOf(previous, 0);
        saveEdits(previous, Map.of(changeId, edit(true, null)));

        long next = supersedeWith(previous, List.of(split(stack, List.of("배열 스택", "연결 스택", "스택 응용"))));

        Map<String, ProjectTidyService.EditValue> carried = readEdits(next);
        assertThat(carried.get(changeId).needsConfirm()).isTrue();
        assertThat(carried.get(changeId).carriedFrom().reason()).contains("나눌 구성");
    }

    @Test
    void 짝이_여럿이면_아무_곳에도_붙이지_않는다() {
        // 앞 판: 임시 부모 아래 새 항목 하나. 새 판: 같은 이름의 후보가 둘(부모가 다르다).
        long previous = saveProposal(List.of(add(null, "큐")));
        String changeId = changeIdOf(previous, 0);
        saveEdits(previous, Map.of(changeId, edit(true, null)));

        long other = insertTopic("자료구조");
        long next = supersedeWith(previous, List.of(add(null, "큐"), add(other, "큐")));

        Map<String, ProjectTidyService.EditValue> carried = readEdits(next);
        // 첫 후보(루트의 「큐」)는 changeId가 같으므로 확실한 승계다. 둘째에는 붙지 않는다.
        assertThat(carried).hasSize(1);
        assertThat(carried).containsKey(changeId);
    }

    @Test
    void 대응이_없으면_옮기지_않는다() {
        long previous = saveProposal(List.of(link(stack, List.of(sectionA))));
        saveEdits(previous, Map.of(changeIdOf(previous, 0), edit(true, null)));

        long next = supersedeWith(previous, List.of(add(null, "전혀 다른 항목")));

        assertThat(readEdits(next)).isEmpty();
    }

    // ===== 확인 표시는 사라지지 않는다 =====

    @Test
    void 다른_제목을_저장해도_확인_표시가_풀리지_않는다() {
        long next = twoUncertain();
        Map<String, ProjectTidyService.EditValue> carried = readEdits(next);
        List<String> ids = List.copyOf(carried.keySet());

        // 사용자가 <다른> 항목의 제목만 고쳐 저장한다. 확인과는 무관한 동작이다.
        ProjectTidyRequests.SaveEdits request = new ProjectTidyRequests.SaveEdits();
        request.setEditRevision(editRevision(next));
        Map<String, ProjectTidyRequests.SaveEdits.Edit> edits = new HashMap<>();
        ids.forEach(id -> edits.put(id, requestEdit(false, "아무 제목")));
        request.setEdits(edits);
        tidyService.saveEdits(USER, next, request);

        Map<String, ProjectTidyService.EditValue> after = readEdits(next);
        assertThat(after.values()).allMatch(ProjectTidyService.EditValue::needsConfirm);
    }

    @Test
    void 확인은_정리안_판과_변경을_맞춰야만_받아들인다() {
        long next = twoUncertain();
        String first = List.copyOf(readEdits(next).keySet()).get(0);

        // 판을 틀리게 보낸다 — 사용자가 확인한 것은 그때 본 제안이므로 받지 않는다.
        ProjectTidyRequests.SaveEdits wrong = new ProjectTidyRequests.SaveEdits();
        wrong.setEditRevision(editRevision(next));
        wrong.setRevision(999L);
        wrong.setResolveCarried(Map.of(first, "KEEP"));
        wrong.setEdits(passThrough(next));
        tidyService.saveEdits(USER, next, wrong);

        assertThat(readEdits(next).get(first).needsConfirm()).isTrue();
    }

    @Test
    void 하나만_해결하면_나머지_때문에_적용이_막힌다() {
        long next = twoUncertain();
        List<String> ids = List.copyOf(readEdits(next).keySet());

        resolve(next, ids.get(0), "KEEP");

        Map<String, ProjectTidyService.EditValue> after = readEdits(next);
        assertThat(after.get(ids.get(0)).needsConfirm()).isFalse();
        assertThat(after.get(ids.get(1)).needsConfirm()).isTrue();

        assertThatThrownBy(() -> tidyService.apply(USER, next, applyAll(next)))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).getDetails()).asString()
                .contains("확인하지 않았어요");
    }

    @Test
    void 둘_다_해결하면_적용된다() {
        long next = twoUncertain();
        List<String> ids = List.copyOf(readEdits(next).keySet());

        resolve(next, ids.get(0), "KEEP");
        resolve(next, ids.get(1), "DROP");

        // DROP은 "내 판단을 버리고 새 제안 그대로" — 편집 자체가 없어진다.
        assertThat(readEdits(next)).doesNotContainKey(ids.get(1));
        ProjectTidyResponse applied = tidyService.apply(USER, next, applyAll(next));
        assertThat(applied.getStatus()).isEqualTo(TidyProposalStatus.APPLIED.name());
    }

    @Test
    void 확인하지_않은_제외는_직접_API로도_적용되지_않는다() {
        long next = twoUncertain();

        // 화면을 거치지 않고 바로 적용을 부른다. 서버가 막아야 한다.
        assertThatThrownBy(() -> tidyService.apply(USER, next, applyAll(next)))
                .isInstanceOf(ConflictException.class);
        assertThat(tidyMapper.findProposalById(next, USER).getStatus())
                .isEqualTo(TidyProposalStatus.PROPOSED);
    }

    // ===== 준비 도구 =====

    /** 확인이 필요한 승계 편집 둘을 만든 새 판의 id. */
    private long twoUncertain() {
        long previous = saveProposal(List.of(rename(stack, "스택(LIFO)"), link(stack, List.of(sectionA))));
        Map<String, ProjectTidyRequests.SaveEdits.Edit> edits = new HashMap<>();
        List<TopicChangeOp> ops = tidyService.readOps(tidyMapper.findProposalById(previous, USER).getOpsJson());
        ops.forEach(op -> edits.put(op.changeId(), requestEdit(true, "내 판단")));
        ProjectTidyRequests.SaveEdits request = new ProjectTidyRequests.SaveEdits();
        request.setEditRevision(0L);
        request.setEdits(edits);
        tidyService.saveEdits(USER, previous, request);

        /*
         * 둘 다 "대응은 찾았지만 같다고 말할 수 없는" 경우로 만든다.
         *  - RENAME: 같은 항목이라 changeId는 같은데 제안한 이름이 달라졌다.
         *  - LINK: 근거 구간이 달라져 changeId는 바뀌었고, 느슨한 짝짓기로만 이어진다.
         */
        return supersedeWith(previous, List.of(rename(stack, "스택 자료구조"), link(stack, List.of(sectionB))));
    }

    private void resolve(long proposalId, String changeId, String decision) {
        ProjectTidyRequests.SaveEdits request = new ProjectTidyRequests.SaveEdits();
        request.setEditRevision(editRevision(proposalId));
        request.setRevision(tidyMapper.findProposalById(proposalId, USER).getRevision());
        request.setResolveCarried(Map.of(changeId, decision));
        request.setEdits(passThrough(proposalId));
        tidyService.saveEdits(USER, proposalId, request);
    }

    /** 지금 저장된 편집을 그대로 다시 보낸다(화면이 전체를 보내는 방식). */
    private Map<String, ProjectTidyRequests.SaveEdits.Edit> passThrough(long proposalId) {
        Map<String, ProjectTidyRequests.SaveEdits.Edit> out = new HashMap<>();
        readEdits(proposalId).forEach((id, value) -> out.put(id, requestEdit(value.excluded(), value.title())));
        return out;
    }

    private ProjectTidyRequests.Apply applyAll(long proposalId) {
        ProjectTidyProposal proposal = tidyMapper.findProposalById(proposalId, USER);
        List<TopicChangeOp> ops = tidyService.readOps(proposal.getOpsJson());
        Map<String, ProjectTidyService.EditValue> edits = readEdits(proposalId);
        ProjectTidyRequests.Apply request = new ProjectTidyRequests.Apply();
        request.setRevision(proposal.getRevision());
        request.setEditRevision(editRevision(proposalId));
        request.setBaseTreeVersion(proposal.getBaseTreeVersion());
        request.setSelectedChangeIds(ops.stream().map(TopicChangeOp::changeId)
                .filter(id -> !(edits.containsKey(id) && edits.get(id).excluded())).toList());
        return request;
    }

    /** 앞 판을 물러나게 하고 새 판을 저장한다 — worker의 저장 경로와 같은 순서. */
    private long supersedeWith(long previousId, List<TopicChangeOp> ops) {
        ProjectTidyProposal previous = tidyMapper.findProposalById(previousId, USER);
        assertThat(tidyMapper.supersedeProposalIfOpen(previousId, USER, null,
                java.time.LocalDateTime.now())).isEqualTo(1);
        long next = saveProposal(ops);
        tidyMapper.updateProposalStatus(previousId, USER, TidyProposalStatus.SUPERSEDED.name(),
                null, next, java.time.LocalDateTime.now());
        worker.carryOverEditsForTest(USER, previous, tidyMapper.findProposalById(next, USER),
                tidyService.readOps(tidyMapper.findProposalById(next, USER).getOpsJson()));
        return next;
    }

    private long saveProposal(List<TopicChangeOp> ops) {
        TopicChangePlan.Plan plan = TopicChangePlan.of(ops);
        ProjectTidyScope scope = new ProjectTidyScope(courseId, 0L, 1, 1,
                List.of(new ProjectTidyScope.Member(MATERIAL, "ds.pdf", HASH, 1, 2, 2)),
                List.of(), false, 2, 2);
        ProjectTidyProposal proposal = ProjectTidyProposal.builder()
                .userId(USER).courseId(courseId).generation(1L).revision(1L)
                .baseTreeVersion(treeVersionQuiet()).status(TidyProposalStatus.PROPOSED)
                .summaryJson("{}").opsJson(write(plan.ops())).scopeJson(write(scope)).model("test")
                .build();
        tidyMapper.insertProposal(proposal);
        tidyMapper.insertProposalMaterial(ProjectTidyProposalMaterial.builder()
                .proposalId(proposal.getProposalId()).userId(USER).materialId(MATERIAL)
                .fileHash(HASH).analysisVersion(1).sectionCount(2).reviewedCount(2).included(true).build());
        return proposal.getProposalId();
    }

    private void saveEdits(long proposalId, Map<String, ProjectTidyRequests.SaveEdits.Edit> edits) {
        ProjectTidyRequests.SaveEdits request = new ProjectTidyRequests.SaveEdits();
        request.setEditRevision(0L);
        request.setEdits(edits);
        tidyService.saveEdits(USER, proposalId, request);
    }

    private static ProjectTidyRequests.SaveEdits.Edit edit(boolean excluded, String title) {
        return requestEdit(excluded, title);
    }

    private static ProjectTidyRequests.SaveEdits.Edit requestEdit(boolean excluded, String title) {
        ProjectTidyRequests.SaveEdits.Edit e = new ProjectTidyRequests.SaveEdits.Edit();
        e.setExcluded(excluded);
        e.setTitle(title);
        return e;
    }

    private Map<String, ProjectTidyService.EditValue> readEdits(long proposalId) {
        ProjectTidyEdits row = tidyMapper.findEdits(proposalId, USER);
        if (row == null || row.getEditsJson() == null) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(row.getEditsJson(),
                    new com.fasterxml.jackson.core.type.TypeReference<
                            Map<String, ProjectTidyService.EditValue>>() {
                    });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private long editRevision(long proposalId) {
        ProjectTidyEdits row = tidyMapper.findEdits(proposalId, USER);
        return row == null ? 0 : row.getEditRevision();
    }

    private String changeIdOf(long proposalId, int index) {
        return tidyService.readOps(tidyMapper.findProposalById(proposalId, USER).getOpsJson())
                .get(index).changeId();
    }

    private static TopicChangeOp link(long topicId, List<Long> sectionIds) {
        return new TopicChangeOp("LINK", null, topicId, null, null, null, null, null, sectionIds,
                "CONCEPT", null, null, null, null);
    }

    private static TopicChangeOp rename(long topicId, String title) {
        return new TopicChangeOp("RENAME", null, topicId, null, null, title, null, null, List.of(),
                "CONCEPT", null, null, null, null);
    }

    private static TopicChangeOp add(Long parentTopicId, String title) {
        return new TopicChangeOp("ADD", null, null, parentTopicId, null, title, "SOURCE", null, List.of(),
                "CONCEPT", null, null, null, null);
    }

    private static TopicChangeOp split(long topicId, List<String> childTitles) {
        List<TopicChangeOp> children = childTitles.stream()
                .map(t -> new TopicChangeOp("ADD", null, null, null, null, t, "SOURCE", null, List.of(),
                        "CONCEPT", null, null, null, null))
                .toList();
        return new TopicChangeOp("SPLIT", null, topicId, null, null, null, null, null, List.of(),
                "CONCEPT", null, null, children, null);
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private long insertTopic(String title) {
        CourseTopic topic = CourseTopic.builder().userId(USER).courseId(courseId).title(title)
                .orderIndex(0).sourceType(TopicSourceType.SOURCE).status(TopicStatus.ACTIVE).build();
        topicMapper.insert(topic);
        return topic.getTopicId();
    }

    private long insertSection(int page) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO material_sections (user_id, material_id, file_hash, analysis_version, chunk_index, "
                             + "unit_type, unit_start, unit_end, display_title, roles_json, dedupe_key, status) "
                             + "VALUES (?, ?, ?, 1, 0, 'PDF_PAGE', ?, ?, '구간', '[\"CONCEPT\"]', ?, 'ACTIVE')",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, USER);
            ps.setLong(2, MATERIAL);
            ps.setString(3, HASH);
            ps.setInt(4, page);
            ps.setInt(5, page);
            ps.setString(6, "carry-" + page);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private long treeVersionQuiet() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT topic_tree_version FROM courses WHERE course_id = ?")) {
            ps.setLong(1, courseId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            for (String sql : List.of(
                    "DELETE FROM material_analysis_timings WHERE user_id = ?",
                    "DELETE FROM material_analysis_jobs WHERE user_id = ?",
                    "DELETE FROM project_tidy_edits WHERE user_id = ?",
                    "DELETE FROM project_tidy_proposal_materials WHERE user_id = ?",
                    "DELETE FROM project_tidy_proposals WHERE user_id = ?",
                    "DELETE FROM project_tidy_jobs WHERE user_id = ?",
                    "DELETE FROM material_sections WHERE user_id = ?",
                    "DELETE FROM topic_material_links WHERE user_id = ?",
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
