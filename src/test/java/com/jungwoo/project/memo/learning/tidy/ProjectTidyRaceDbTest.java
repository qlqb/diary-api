package com.jungwoo.project.memo.learning.tidy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.learning.CourseTopicMapper;
import com.jungwoo.project.memo.learning.TopicMaterialLinkMapper;
import com.jungwoo.project.memo.learning.domain.CourseTopic;
import com.jungwoo.project.memo.learning.domain.TopicSourceType;
import com.jungwoo.project.memo.learning.domain.TopicStatus;
import com.jungwoo.project.memo.learning.structure.TopicChangeOp;
import com.jungwoo.project.memo.learning.structure.TopicChangePlan;
import com.jungwoo.project.memo.learning.tidy.domain.ProjectTidyJob;
import com.jungwoo.project.memo.learning.tidy.domain.ProjectTidyProposal;
import com.jungwoo.project.memo.learning.tidy.domain.ProjectTidyProposalMaterial;
import com.jungwoo.project.memo.learning.tidy.domain.TidyJobStatus;
import com.jungwoo.project.memo.learning.tidy.domain.TidyProposalStatus;
import com.jungwoo.project.memo.learning.tidy.dto.ProjectTidyRequests;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 정리안의 경합. 겹치는 시점을 실제로 만들어 본다 — 임의의 sleep으로 "아마 겹쳤겠지"를 하지 않는다.
 *
 * <p>보는 것 셋:
 * <ol>
 *   <li><b>버린 뒤 늦게 끝난 생성</b>: 사용자가 [버리기]를 누르면 작업의 임대 토큰이 오른다.
 *       그 뒤 도착한 결과는 아무것도 쓰지 못한다 — "버린 안이 되살아나지 않는다"의 근거다.</li>
 *   <li><b>두 탭 동시 적용</b>: 정확히 하나만 반영된다. 트리 판도 한 번만 오른다.</li>
 *   <li><b>검증과 쓰기 사이의 자료 삭제</b>: 적용이 자료 행을 잠그고 있으므로 삭제가 기다린다.
 *       반대로 삭제가 먼저면 적용이 그것을 보고 거절한다. 어느 쪽이든 <b>부분 반영은 없다</b>.</li>
 * </ol>
 *
 * <p>로컬 memo DB 필요. CI 제외.
 */
@SpringBootTest
class ProjectTidyRaceDbTest {

    private static final long USER = 999_000_402L;
    private static final long MATERIAL = 999_400_201L;
    private static final String HASH = "e".repeat(64);
    private static final long WAIT_MS = 5000;

    @Autowired
    private ProjectTidyService tidyService;
    @Autowired
    private ProjectTidyMapper tidyMapper;
    @Autowired
    private ProjectTidyResultWriter resultWriter;
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
    private long sectionId;

    @BeforeEach
    void setUp() throws Exception {
        cleanUp();
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO courses (user_id, title, status) VALUES (?, '경합 테스트 과목', 'ACTIVE')",
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
        sectionId = insertSection("스택 강의", 3);
    }

    @Test
    void 버린_뒤_늦게_끝난_생성은_정리안을_남기지_못한다() {
        ProjectTidyJob job = queueAndClaimJob();

        // 모델이 답하는 동안 사용자가 [버리기]를 눌렀다 — 작업이 취소되고 임대 토큰이 오른다.
        tidyService.dismiss(USER, courseId);

        // 늦게 돌아온 worker가 결과를 쓰려 한다. 들고 있는 토큰은 이미 낡았다.
        AtomicInteger wrote = new AtomicInteger();
        boolean written = resultWriter.writeIfLeased(job, wrote::incrementAndGet);

        assertThat(written).isFalse();
        assertThat(wrote.get()).isZero();
        assertThat(tidyService.view(USER, courseId).getProposalId()).isNull();
        assertThat(tidyMapper.findJobById(job.getJobId()).getStatus()).isEqualTo(TidyJobStatus.CANCELLED);
    }

    @Test
    void 임대가_살아_있으면_결과를_쓴다() {
        ProjectTidyJob job = queueAndClaimJob();
        AtomicInteger wrote = new AtomicInteger();

        assertThat(resultWriter.writeIfLeased(job, wrote::incrementAndGet)).isTrue();
        assertThat(wrote.get()).isEqualTo(1);
    }

    @Test
    void 두_탭이_동시에_적용해도_한_번만_반영된다() throws Exception {
        long proposalId = saveProposal(List.of(add("새 항목")));
        String change = firstChangeId(proposalId);
        long before = treeVersion();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> a = pool.submit(() -> applyRace(proposalId, change, ready, go));
            Future<String> b = pool.submit(() -> applyRace(proposalId, change, ready, go));
            assertThat(ready.await(WAIT_MS, TimeUnit.MILLISECONDS)).isTrue();
            go.countDown();

            List<String> results = List.of(a.get(WAIT_MS, TimeUnit.MILLISECONDS),
                    b.get(WAIT_MS, TimeUnit.MILLISECONDS));
            assertThat(results).containsExactlyInAnyOrder("OK", "CONFLICT");
        } finally {
            pool.shutdownNow();
        }

        // 항목도 한 번만, 트리 판도 한 번만.
        assertThat(topicMapper.findActiveByCourseIdAndUserId(courseId, USER).stream()
                .filter(t -> "새 항목".equals(t.getTitle()))).hasSize(1);
        assertThat(treeVersion()).isEqualTo(before + 1);
        assertThat(tidyMapper.findProposalById(proposalId, USER).getStatus())
                .isEqualTo(TidyProposalStatus.APPLIED);
    }

    @Test
    void 검증_직전에_자료가_지워지면_거절하고_아무것도_반영하지_않는다() throws Exception {
        long proposalId = saveProposal(List.of(link(stack, List.of(sectionId))));
        String change = firstChangeId(proposalId);
        long before = treeVersion();

        /*
         * 적용이 자료 행을 잠그기 <직전에> 삭제가 끼어드는 순간을 만든다. 삭제 쪽이 먼저 그 행을
         * 잠근 채 커밋을 미루면, 적용은 거기서 기다렸다가 삭제된 결과를 보게 된다.
         */
        CountDownLatch deleting = new CountDownLatch(1);
        CountDownLatch applyTried = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection deleter = dataSource.getConnection()) {
            deleter.setAutoCommit(false);
            try (PreparedStatement ps = deleter.prepareStatement(
                    "UPDATE course_materials SET status = 'DELETED' WHERE material_id = ? AND user_id = ?")) {
                ps.setLong(1, MATERIAL);
                ps.setLong(2, USER);
                ps.executeUpdate();
            }
            deleting.countDown();

            Future<String> applying = pool.submit(() -> {
                applyTried.countDown();
                try {
                    tidyService.apply(USER, proposalId, applyRequest(proposalId, List.of(change)));
                    return "OK";
                } catch (ConflictException e) {
                    return "CONFLICT";
                }
            });
            assertThat(applyTried.await(WAIT_MS, TimeUnit.MILLISECONDS)).isTrue();
            // 적용은 잠긴 행을 기다린다. 삭제를 확정하면 그때부터 진행해 "지워졌다"를 본다.
            Thread.sleep(200);
            deleter.commit();

            assertThat(applying.get(WAIT_MS, TimeUnit.MILLISECONDS)).isEqualTo("CONFLICT");
        } finally {
            pool.shutdownNow();
        }

        assertThat(treeVersion()).isEqualTo(before);
        assertThat(topicLinkMapper.findActiveByTopicId(stack, USER)).isEmpty();
        assertThat(tidyMapper.findProposalById(proposalId, USER).getStatus())
                .isEqualTo(TidyProposalStatus.PROPOSED);
    }

    // ===== 준비 도구 =====

    private String applyRace(long proposalId, String change, CountDownLatch ready, CountDownLatch go) {
        ready.countDown();
        try {
            go.await(WAIT_MS, TimeUnit.MILLISECONDS);
            tidyService.apply(USER, proposalId, applyRequest(proposalId, List.of(change)));
            return "OK";
        } catch (ConflictException e) {
            return "CONFLICT";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "INTERRUPTED";
        }
    }

    private ProjectTidyJob queueAndClaimJob() {
        ProjectTidyJob job = ProjectTidyJob.builder()
                .userId(USER).courseId(courseId).generation(1L).status(TidyJobStatus.QUEUED)
                .baseTreeVersion(0L).maxAttempts(3).nextRunAt(LocalDateTime.now())
                .build();
        tidyMapper.insertJob(job);
        LocalDateTime now = LocalDateTime.now();
        assertThat(tidyMapper.claimJob(job.getJobId(), "test", now, now.plusMinutes(5))).isEqualTo(1);
        return tidyMapper.findJobById(job.getJobId());
    }

    private long saveProposal(List<TopicChangeOp> ops) {
        TopicChangePlan.Plan plan = TopicChangePlan.of(ops);
        ProjectTidyScope scope = new ProjectTidyScope(courseId, treeVersionQuiet(), 1, 1,
                List.of(new ProjectTidyScope.Member(MATERIAL, "ds.pdf", HASH, 1, 1, 1)),
                List.of(), false, 1, 1);
        ProjectTidyProposal proposal = ProjectTidyProposal.builder()
                .userId(USER).courseId(courseId).generation(1L).revision(1L)
                .baseTreeVersion(treeVersionQuiet()).status(TidyProposalStatus.PROPOSED)
                .summaryJson("{}").opsJson(write(plan.ops())).scopeJson(write(scope)).model("test")
                .build();
        tidyMapper.insertProposal(proposal);
        tidyMapper.insertProposalMaterial(ProjectTidyProposalMaterial.builder()
                .proposalId(proposal.getProposalId()).userId(USER).materialId(MATERIAL)
                .fileHash(HASH).analysisVersion(1).sectionCount(1).reviewedCount(1).included(true).build());
        return proposal.getProposalId();
    }

    private String firstChangeId(long proposalId) {
        return tidyService.readOps(tidyMapper.findProposalById(proposalId, USER).getOpsJson())
                .get(0).changeId();
    }

    private ProjectTidyRequests.Apply applyRequest(long proposalId, List<String> selected) {
        ProjectTidyProposal proposal = tidyMapper.findProposalById(proposalId, USER);
        ProjectTidyRequests.Apply request = new ProjectTidyRequests.Apply();
        request.setRevision(proposal.getRevision());
        request.setEditRevision(0L);
        request.setBaseTreeVersion(proposal.getBaseTreeVersion());
        request.setSelectedChangeIds(selected);
        return request;
    }

    private static TopicChangeOp add(String title) {
        return new TopicChangeOp("ADD", null, null, null, null, title, "SOURCE", null, List.of(),
                "CONCEPT", null, null, null, null);
    }

    private static TopicChangeOp link(long topicId, List<Long> sectionIds) {
        return new TopicChangeOp("LINK", null, topicId, null, null, null, null, null, sectionIds,
                "CONCEPT", null, null, null, null);
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

    private long insertSection(String title, int page) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO material_sections (user_id, material_id, file_hash, analysis_version, chunk_index, "
                             + "unit_type, unit_start, unit_end, display_title, roles_json, dedupe_key, status) "
                             + "VALUES (?, ?, ?, 1, 0, 'PDF_PAGE', ?, ?, ?, '[\"CONCEPT\"]', ?, 'ACTIVE')",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, USER);
            ps.setLong(2, MATERIAL);
            ps.setString(3, HASH);
            ps.setInt(4, page);
            ps.setInt(5, page);
            ps.setString(6, title);
            ps.setString(7, "race-" + page);
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

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            for (String sql : List.of(
                    "DELETE FROM material_analysis_timings WHERE user_id = ?",
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
