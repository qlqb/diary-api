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
import com.jungwoo.project.memo.learning.tidy.domain.ProjectTidyEdits;
import com.jungwoo.project.memo.learning.tidy.domain.ProjectTidyProposal;
import com.jungwoo.project.memo.learning.tidy.domain.ProjectTidyProposalMaterial;
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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 적용·폐기·교체가 서로를 덮어쓰지 않는다.
 *
 * <p>고치는 문제: 적용은 정리안 행을 {@code FOR UPDATE}로 잠그고 상태 조건이 붙은 UPDATE로
 * 전이했지만, 폐기는 <b>잠그지 않고 읽은 뒤 조건 없는 UPDATE</b>를 했다. 그래서 이 순서가
 * 가능했다 — 폐기가 PROPOSED를 읽는다 → 적용이 커밋한다(트리가 바뀐다) → 폐기가 그 행을
 * DISMISSED로 덮는다. 남는 것은 <b>트리는 적용됐는데 이력은 "버림"인 상태</b>다. 사용자가
 * 나중에 이력을 보면 자기가 적용한 적 없는 변경이 트리에 있다.
 *
 * <p>겹치는 시점을 실제로 만든다. 적용이 근거 자료 행을 잠그는 단계에서 멈추도록 다른
 * 연결이 그 행을 먼저 잡고, 적용이 <b>정리안 행을 이미 잠근 채</b> 대기하는 것을
 * {@code innodb_trx}로 확인한 뒤 폐기를 띄운다. 임의의 sleep으로 "아마 겹쳤겠지"를 하지
 * 않는다 — 잠금 대기 상태를 직접 관찰한다.
 *
 * <p>로컬 memo DB 필요. CI 제외.
 */
@SpringBootTest
class ProjectTidyResolveRaceDbTest {

    private static final long USER = 999_000_405L;
    private static final long MATERIAL = 999_400_501L;
    private static final String HASH = "a".repeat(64);
    private static final long WAIT_MS = 8000;

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
    private long sectionId;
    private ExecutorService pool;

    @BeforeEach
    void setUp() throws Exception {
        cleanUp();
        pool = Executors.newFixedThreadPool(3);
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO courses (user_id, title, status) VALUES (?, '전이 경합 과목', 'ACTIVE')",
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
        sectionId = insertSection(3);
    }

    // ===== 적용 vs 폐기 =====

    @Test
    void 적용이_먼저_커밋하면_뒤늦은_폐기가_그것을_덮지_않는다() throws Exception {
        long proposalId = saveProposal(List.of(link(stack, List.of(sectionId))));
        String change = firstChangeId(proposalId);
        long before = treeVersion();

        /*
         * 만드는 순서:
         *   1. 다른 연결이 근거 자료 행을 잡는다. 적용은 그 앞에서 멈춘다.
         *   2. 적용을 띄운다. 정리안 행을 이미 잠근 채 자료 행을 기다린다.
         *   3. 폐기를 띄운다. 잠그지 않고 읽으면 PROPOSED가 보인다(수정 전 동작).
         *   4. 자료 행을 놓아 준다. 적용이 끝나고, 그 뒤 폐기가 쓴다.
         * 수정 전에는 4번에서 APPLIED가 DISMISSED로 덮였다.
         */
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (PreparedStatement ps = blocker.prepareStatement(
                    "SELECT material_id FROM course_materials WHERE material_id = ? AND user_id = ? FOR UPDATE")) {
                ps.setLong(1, MATERIAL);
                ps.setLong(2, USER);
                ps.executeQuery();
            }

            CountDownLatch applyStarted = new CountDownLatch(1);
            Thread[] applyThread = new Thread[1];
            Future<String> applying = pool.submit(() -> {
                applyThread[0] = Thread.currentThread();
                applyStarted.countDown();
                return runApply(proposalId, change);
            });
            assertThat(applyStarted.await(WAIT_MS, TimeUnit.MILLISECONDS)).isTrue();
            // 적용이 자료 행 잠금을 <실제로> 기다리기 시작할 때까지 기다린다.
            awaitLockWait(applying, applyThread[0]);

            Future<String> dismissing = pool.submit(this::runDismiss);
            // 폐기도 제 차례에서 막히거나(수정 후) 이미 읽고 쓰기를 기다린다(수정 전).
            Thread.sleep(150);
            blocker.commit();

            assertThat(applying.get(WAIT_MS, TimeUnit.MILLISECONDS)).isEqualTo("OK");
            assertThat(dismissing.get(WAIT_MS, TimeUnit.MILLISECONDS)).isIn("ALREADY", "OK");
        }

        // 적용이 이겼다. 트리가 바뀌었으므로 이력도 APPLIED여야 한다.
        assertThat(treeVersion()).isEqualTo(before + 1);
        assertThat(topicLinkMapper.findActiveByTopicId(stack, USER)).hasSize(1);
        assertThat(tidyMapper.findProposalById(proposalId, USER).getStatus())
                .as("트리는 적용됐는데 이력이 DISMISSED면 사용자는 적용한 적 없는 변경을 보게 된다")
                .isEqualTo(TidyProposalStatus.APPLIED);
    }

    @Test
    void 폐기가_먼저_커밋하면_적용은_트리를_바꾸지_못한다() throws Exception {
        long proposalId = saveProposal(List.of(link(stack, List.of(sectionId))));
        String change = firstChangeId(proposalId);
        long before = treeVersion();

        assertThat(runDismiss()).isEqualTo("OK");
        assertThat(runApply(proposalId, change)).isEqualTo("CONFLICT");

        assertThat(treeVersion()).isEqualTo(before);
        assertThat(topicLinkMapper.findActiveByTopicId(stack, USER)).isEmpty();
        assertThat(tidyMapper.findProposalById(proposalId, USER).getStatus())
                .isEqualTo(TidyProposalStatus.DISMISSED);
    }

    @Test
    void 두_탭이_동시에_버려도_상태는_한_번만_바뀐다() throws Exception {
        long proposalId = saveProposal(List.of(link(stack, List.of(sectionId))));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Future<String> a = pool.submit(() -> gatedDismiss(ready, go));
        Future<String> b = pool.submit(() -> gatedDismiss(ready, go));
        assertThat(ready.await(WAIT_MS, TimeUnit.MILLISECONDS)).isTrue();
        go.countDown();

        // 둘 다 사용자에게는 성공으로 보여도 된다 — 원하는 결과(안이 없다)가 이뤄졌으니까.
        // 중요한 것은 상태가 두 번 바뀌지 않는 것이다.
        assertThat(List.of(a.get(WAIT_MS, TimeUnit.MILLISECONDS), b.get(WAIT_MS, TimeUnit.MILLISECONDS)))
                .allMatch(r -> r.equals("OK") || r.equals("ALREADY"));
        ProjectTidyProposal after = tidyMapper.findProposalById(proposalId, USER);
        assertThat(after.getStatus()).isEqualTo(TidyProposalStatus.DISMISSED);
        assertThat(tidyMapper.findOpenProposalByCourse(courseId, USER)).isNull();
    }

    // ===== 교체(갱신 worker) vs 적용·폐기 =====

    @Test
    void 이미_적용된_안은_새_판이_물러나게_만들지_못한다() throws Exception {
        long proposalId = saveProposal(List.of(link(stack, List.of(sectionId))));
        assertThat(runApply(proposalId, firstChangeId(proposalId))).isEqualTo("OK");

        // 생성 중이던 worker가 이제야 끝나 "앞 판을 물러나게" 하려 한다.
        int moved = tidyMapper.supersedeProposalIfOpen(proposalId, USER, null, now());

        assertThat(moved).as("APPLIED를 SUPERSEDED로 덮으면 적용 이력이 사라진다").isZero();
        assertThat(tidyMapper.findProposalById(proposalId, USER).getStatus())
                .isEqualTo(TidyProposalStatus.APPLIED);
    }

    @Test
    void 이미_버린_안도_새_판이_물러나게_만들지_못한다() throws Exception {
        long proposalId = saveProposal(List.of(link(stack, List.of(sectionId))));
        assertThat(runDismiss()).isEqualTo("OK");

        assertThat(tidyMapper.supersedeProposalIfOpen(proposalId, USER, null, now())).isZero();
        assertThat(tidyMapper.findProposalById(proposalId, USER).getStatus())
                .isEqualTo(TidyProposalStatus.DISMISSED);
    }

    @Test
    void 열려_있는_안만_물러난다() throws Exception {
        long proposalId = saveProposal(List.of(link(stack, List.of(sectionId))));

        assertThat(tidyMapper.supersedeProposalIfOpen(proposalId, USER, null, now())).isEqualTo(1);
        assertThat(tidyMapper.findProposalById(proposalId, USER).getStatus())
                .isEqualTo(TidyProposalStatus.SUPERSEDED);
    }

    // ===== 종료된 안에 대한 편집 =====

    @Test
    void 끝난_정리안에는_편집을_저장할_수_없다() throws Exception {
        long proposalId = saveProposal(List.of(link(stack, List.of(sectionId))));
        assertThat(runApply(proposalId, firstChangeId(proposalId))).isEqualTo("OK");

        ProjectTidyRequests.SaveEdits request = new ProjectTidyRequests.SaveEdits();
        request.setEditRevision(0L);
        request.setEdits(java.util.Map.of());

        assertThatThrownBy(() -> tidyService.saveEdits(USER, proposalId, request))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 교체된_안에_남아_있던_편집은_지워지지_않는다() throws Exception {
        long proposalId = saveProposal(List.of(link(stack, List.of(sectionId))));
        tidyMapper.insertEditsIgnore(ProjectTidyEdits.builder()
                .proposalId(proposalId).userId(USER)
                .editsJson("{\"x\":{\"excluded\":true,\"title\":null,\"needsConfirm\":false}}").build());

        assertThat(tidyMapper.supersedeProposalIfOpen(proposalId, USER, null, now())).isEqualTo(1);

        // 승계는 여기서 읽어 간다. 전이가 지워 버리면 옮길 것이 없어진다.
        assertThat(tidyMapper.findEdits(proposalId, USER).getEditsJson()).contains("\"excluded\":true");
    }

    // ===== 준비 도구 =====

    private java.time.LocalDateTime now() {
        return java.time.LocalDateTime.now();
    }

    private String runApply(long proposalId, String change) {
        try {
            tidyService.apply(USER, proposalId, applyRequest(proposalId, List.of(change)));
            return "OK";
        } catch (ConflictException e) {
            return "CONFLICT";
        }
    }

    private String runDismiss() {
        try {
            var response = tidyService.dismiss(USER, courseId);
            return response != null && Boolean.TRUE.equals(response.getAlreadyResolved()) ? "ALREADY" : "OK";
        } catch (ConflictException e) {
            return "CONFLICT";
        }
    }

    private String gatedDismiss(CountDownLatch ready, CountDownLatch go) {
        ready.countDown();
        try {
            if (!go.await(WAIT_MS, TimeUnit.MILLISECONDS)) return "TIMEOUT";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "INTERRUPTED";
        }
        return runDismiss();
    }

    /**
     * 적용이 근거 자료 행의 잠금을 <실제로> 기다리기 시작할 때까지 기다린다.
     *
     * <p>sleep으로 "그쯤이면 겹쳤겠지"를 하지 않으려고 둔 것이다. 겹침은 시간이 아니라
     * 상태이고, 그 상태는 DB가 직접 보여준다.
     *
     * <p><b>왜 innodb_trx가 아니라 processlist인가.</b> 처음에는 innodb_trx에서
     * trx_state = 'LOCK WAIT'를 셌다. 그런데 MariaDB 10.4는 <b>아직 쓰기를 하지 않은
     * 트랜잭션을 innodb_trx에 올리지 않는다</b>. 적용은 잠금 읽기(FOR UPDATE)만 한 채 자료
     * 행에서 멈추므로 거기에 나타나지 않는다. 게다가 전역 COUNT였기 때문에, 다른 연결의
     * 잠금 대기가 우연히 있으면 "겹쳤다"고 잘못 통과했다 — 한 번 통과한 것이 운이었다.
     * 이제는 <b>이 적용의 그 문장</b>이 서버에서 실행 중인지를 본다. 잠금이 없으면 즉시
     * 끝나는 문장이므로, 실행 중으로 보인다는 것은 잠금을 기다린다는 뜻이다.
     */
    private void awaitLockWait(Future<?> waiter, Thread thread) throws Exception {
        long deadline = System.currentTimeMillis() + WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (waiter.isDone()) {
                // 기다려야 할 쪽이 먼저 끝났다. 무엇으로 끝났는지 그대로 드러낸다.
                throw new IllegalStateException("잠금을 기다리기 전에 끝났다: " + waiter.get());
            }
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT COUNT(*) FROM information_schema.processlist "
                                 + "WHERE id <> CONNECTION_ID() AND info LIKE '%course_materials%' "
                                 + "AND info LIKE '%FOR UPDATE%' AND info LIKE ?")) {
                ps.setString(1, "%" + MATERIAL + "%");
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) > 0) return;
                }
            }
            Thread.sleep(20);
        }
        StringBuilder where = new StringBuilder();
        if (thread != null) {
            for (StackTraceElement frame : thread.getStackTrace()) {
                if (frame.getClassName().contains("jungwoo")) {
                    where.append(" <- ").append(frame);
                }
            }
        }
        throw new IllegalStateException("적용이 자료 행 잠금을 기다리는 것이 보이지 않았다. 적용 스레드 위치:" + where);
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

    private long insertSection(int page) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO material_sections (user_id, material_id, file_hash, analysis_version, chunk_index, "
                             + "unit_type, unit_start, unit_end, display_title, roles_json, dedupe_key, status) "
                             + "VALUES (?, ?, ?, 1, 0, 'PDF_PAGE', ?, ?, '스택 강의', '[\"CONCEPT\"]', ?, 'ACTIVE')",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, USER);
            ps.setLong(2, MATERIAL);
            ps.setString(3, HASH);
            ps.setInt(4, page);
            ps.setInt(5, page);
            ps.setString(6, "resolve-race-" + page);
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
        if (pool != null) pool.shutdownNow();
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
