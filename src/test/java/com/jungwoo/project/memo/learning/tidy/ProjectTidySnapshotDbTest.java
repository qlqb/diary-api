package com.jungwoo.project.memo.learning.tidy;

import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.learning.CourseTopicMapper;
import com.jungwoo.project.memo.learning.domain.CourseTopic;
import com.jungwoo.project.memo.learning.domain.TopicSourceType;
import com.jungwoo.project.memo.learning.domain.TopicStatus;
import com.jungwoo.project.memo.learning.structure.TopicChangeOp;
import com.jungwoo.project.memo.learning.tidy.domain.ProjectTidyJob;
import com.jungwoo.project.memo.learning.tidy.domain.ProjectTidyProposal;
import com.jungwoo.project.memo.learning.tidy.domain.TidyJobStatus;
import com.jungwoo.project.memo.learning.tidy.dto.ProjectTidyRequests;
import com.jungwoo.project.memo.learning.tidy.dto.ProjectTidyResponse;
import com.jungwoo.project.memo.material.analysis.MaterialAnalysisJobService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 요청할 때 고정한 입력으로만 정리한다.
 *
 * <p>고치는 문제: 요청은 자료 id 목록만 저장하고, worker는 실행할 때 <b>그때의</b> 트리와 구간을
 * 읽었다. 요청 뒤 큐에서 기다리는 동안 자료가 다시 분석되거나 트리가 바뀌면, 사용자가 누를 때
 * 본 것과 다른 입력으로 정리안이 만들어졌다. 스냅샷을 읽지 못하면 null을 돌려 <b>프로젝트의
 * 최신 자료 전부</b>를 읽었다 — 실패가 범위 확대로 바뀌었다. 요청 때 제외된 자료의 사유도 최종
 * 범위에 남지 않았다.
 *
 * <p>모델 호출만 가짜로 둔다. 입력을 모으고 검증하고 저장하는 나머지는 실제 DB에서 돈다.
 * 가짜 모델은 받은 입력을 기록한다 — "무엇을 보고 판단했는가"를 확인하려면 그것이 필요하다.
 *
 * <p>로컬 memo DB 필요. CI 제외.
 */
@SpringBootTest
class ProjectTidySnapshotDbTest {

    private static final long USER = 999_000_408L;
    private static final long MATERIAL_A = 999_400_801L;
    private static final long MATERIAL_B = 999_400_802L;
    private static final long MATERIAL_LATE = 999_400_803L;
    private static final String HASH_A = "1".repeat(64);
    private static final String HASH_B = "2".repeat(64);
    private static final String HASH_LATE = "3".repeat(64);
    private static final String HASH_A_NEW = "4".repeat(64);

    @MockitoBean
    private ProjectTidyAnalyzer analyzer;

    @Autowired
    private ProjectTidyService tidyService;
    @Autowired
    private ProjectTidyWorker worker;
    @Autowired
    private ProjectTidyMapper tidyMapper;
    @Autowired
    private CourseTopicMapper topicMapper;
    @Autowired
    private DataSource dataSource;

    private long courseId;
    private long stack;
    private final AtomicReference<ProjectTidyInputBuilder.Input> seen = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        cleanUp();
        courseId = insertCourse();
        stack = insertTopic("스택");
        readyMaterial(MATERIAL_A, "강의.pdf", HASH_A, "스택 정의");
        readyMaterial(MATERIAL_B, "교재.pdf", HASH_B, "3장 스택");
        seen.set(null);
        when(analyzer.analyze(anyLong(), any())).thenAnswer(inv -> {
            ProjectTidyInputBuilder.Input input = inv.getArgument(1);
            seen.set(input);
            return new ProjectTidyAnalyzer.Draft(List.of(new TopicChangeOp("ADD", null, null, null, null,
                    "새 항목", "SOURCE", null, List.of(input.sections().get(0).getSectionId()), "CONCEPT",
                    null, null, null, "테스트")), "요약", "fake-model");
        });
    }

    // ===== 요청 뒤 입력이 바뀌면 =====

    @Test
    void 기다리는_동안_다시_분석된_자료가_있으면_바뀐_입력으로_몰래_만들지_않는다() throws Exception {
        tidyService.request(USER, courseId, false);
        // 큐에서 기다리는 동안 강의.pdf가 새 파일로 다시 분석됐다(구간이 교체됐다).
        reanalyze(MATERIAL_A, HASH_A_NEW, "스택 정의(개정)");

        ProjectTidyJob finished = runQueuedJob();

        assertThat(finished.getStatus()).isEqualTo(TidyJobStatus.FAILED);
        assertThat(finished.getErrorCode()).isEqualTo("STALE_INPUT");
        assertThat(finished.getErrorMessage()).contains("강의.pdf");
        verify(analyzer, never()).analyze(anyLong(), any());
        assertThat(tidyMapper.findOpenProposalByCourse(courseId, USER)).isNull();
    }

    @Test
    void 기다리는_동안_학습_구조가_바뀌면_실행하지_않고_새_요청을_안내한다() throws Exception {
        tidyService.request(USER, courseId, false);
        bumpTreeVersion();

        ProjectTidyJob finished = runQueuedJob();

        assertThat(finished.getErrorCode()).isEqualTo("STALE_INPUT");
        verify(analyzer, never()).analyze(anyLong(), any());
        ProjectTidyResponse view = tidyService.view(USER, courseId);
        // 같은 입력으로 다시 시도해도 또 같은 이유로 멈춘다. 화면은 "다시 시도"가 아니라 "새로 정리"를 줘야 한다.
        assertThat(view.getJob().isRetryable()).isFalse();
        assertThat(view.getJob().isNeedsNewRequest()).isTrue();
    }

    @Test
    void 기다리는_동안_연결이_끊긴_자료가_있어도_멈춘다() throws Exception {
        tidyService.request(USER, courseId, false);
        exec("DELETE FROM material_links WHERE material_id = ? AND user_id = ?", MATERIAL_B, USER);

        ProjectTidyJob finished = runQueuedJob();

        assertThat(finished.getErrorCode()).isEqualTo("STALE_INPUT");
        assertThat(finished.getErrorMessage()).contains("교재.pdf");
    }

    @Test
    void 요청_뒤에_분석이_끝난_자료는_이번_정리에_섞이지_않는다() throws Exception {
        tidyService.request(USER, courseId, false);
        readyMaterial(MATERIAL_LATE, "나중.pdf", HASH_LATE, "나중 구간");

        ProjectTidyJob finished = runQueuedJob();

        assertThat(finished.getStatus()).isEqualTo(TidyJobStatus.DONE);
        assertThat(seen.get().sections()).extracting(s -> s.getMaterialId())
                .doesNotContain(MATERIAL_LATE)
                .contains(MATERIAL_A, MATERIAL_B);
    }

    @Test
    void 요청_때_제외된_자료와_사유가_최종_범위에_남는다() throws Exception {
        // 분석 중이라 요청 때 빠진 자료.
        insertMaterial(MATERIAL_LATE, "분석중.pdf", HASH_LATE);
        link(MATERIAL_LATE);
        insertContentJob(MATERIAL_LATE, HASH_LATE, "RUNNING");

        tidyService.request(USER, courseId, false);
        runQueuedJob();

        ProjectTidyProposal proposal = tidyMapper.findOpenProposalByCourse(courseId, USER);
        assertThat(proposal).isNotNull();
        assertThat(proposal.getScopeJson()).contains("분석중.pdf").contains("ANALYZING");
    }

    // ===== 스냅샷을 믿을 수 없으면 =====

    @Test
    void 스냅샷이_깨졌으면_최신_자료_전부로_넓히지_않고_실패한다() throws Exception {
        tidyService.request(USER, courseId, false);
        /*
         * 문법이 깨진 JSON은 DB가 받지 않는다(CHECK JSON_VALID). 남는 위험은 <모양이 틀린> JSON이다 —
         * 읽기는 되는데 기대한 필드가 엉뚱한 형태다. 예전 worker는 이때 null을 돌려 최신 자료 전부를 읽었다.
         */
        exec("UPDATE project_tidy_jobs SET input_snapshot_json = '{\"version\":2,\"materials\":\"깨짐\"}' "
                + "WHERE course_id = ? AND user_id = ?", courseId, USER);

        ProjectTidyJob finished = runQueuedJob();

        assertThat(finished.getStatus()).isEqualTo(TidyJobStatus.FAILED);
        assertThat(finished.getErrorCode()).isEqualTo("SNAPSHOT_INVALID");
        verify(analyzer, never()).analyze(anyLong(), any());
    }

    @Test
    void 예전_방식으로_기록된_요청은_그대로_다시_쓰지_않고_새_요청을_안내한다() throws Exception {
        tidyService.request(USER, courseId, false);
        // 배포 전 요청: 자료 id 목록만 있다. 무엇을 봤는지(해시·구간)를 알 수 없다.
        exec("UPDATE project_tidy_jobs SET input_snapshot_json = ? WHERE course_id = ? AND user_id = ?",
                "{\"materialIds\":[" + MATERIAL_A + "," + MATERIAL_B + "],\"excludedAtRequest\":[]}",
                courseId, USER);

        ProjectTidyJob finished = runQueuedJob();

        assertThat(finished.getErrorCode()).isEqualTo("SNAPSHOT_OUTDATED");
        verify(analyzer, never()).analyze(anyLong(), any());
    }

    // ===== 다시 시도 =====

    @Test
    void 다시_시도는_요청_때의_입력을_그대로_쓴다() throws Exception {
        tidyService.request(USER, courseId, false);
        ProjectTidyJob first = tidyMapper.findLatestJobByCourse(courseId, USER);
        // 모델이 실패했다고 치고 끝낸다.
        exec("UPDATE project_tidy_jobs SET status = 'FAILED', error_code = 'PARSE', finished_at = NOW() "
                + "WHERE job_id = ?", first.getJobId());
        // 그 사이 새 자료가 끝났다. 다시 시도는 이것을 섞지 않는다 — 섞는 것은 새 요청이다.
        readyMaterial(MATERIAL_LATE, "나중.pdf", HASH_LATE, "나중 구간");

        tidyService.retry(USER, courseId);

        ProjectTidyJob again = tidyMapper.findLatestJobByCourse(courseId, USER);
        assertThat(again.getJobId()).isNotEqualTo(first.getJobId());
        assertThat(again.getInputSnapshotJson()).isEqualTo(first.getInputSnapshotJson());
        runQueuedJob();
        assertThat(seen.get().sections()).extracting(s -> s.getMaterialId()).doesNotContain(MATERIAL_LATE);
    }

    // ===== 적용할 때 =====

    @Test
    void 적용할_때_근거의_분석_판이_다르면_적용하지_않는다() throws Exception {
        tidyService.request(USER, courseId, false);
        runQueuedJob();
        ProjectTidyProposal proposal = tidyMapper.findOpenProposalByCourse(courseId, USER);

        // 같은 파일, 같은 해시인데 분석기 판이 올라 구간이 다시 만들어졌다(구간 id는 우연히 그대로).
        exec("UPDATE material_sections SET analysis_version = analysis_version + 1 "
                + "WHERE material_id = ? AND user_id = ?", MATERIAL_A, USER);

        ProjectTidyRequests.Apply request = new ProjectTidyRequests.Apply();
        request.setRevision(proposal.getRevision());
        request.setEditRevision(0L);
        request.setBaseTreeVersion(proposal.getBaseTreeVersion());
        request.setSelectedChangeIds(tidyService.readOps(proposal.getOpsJson()).stream()
                .map(TopicChangeOp::changeId).toList());

        assertThatThrownBy(() -> tidyService.apply(USER, proposal.getProposalId(), request))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).getDetails()).asString().contains("다시 분석");
    }

    // ===== 준비 도구 =====

    private ProjectTidyJob runQueuedJob() {
        ProjectTidyJob queued = tidyMapper.findLatestJobByCourse(courseId, USER);
        LocalDateTime now = LocalDateTime.now();
        assertThat(tidyMapper.claimJob(queued.getJobId(), "test", now, now.plusMinutes(5))).isEqualTo(1);
        worker.run(tidyMapper.findJobById(queued.getJobId()));
        return tidyMapper.findJobById(queued.getJobId());
    }

    private void readyMaterial(long materialId, String filename, String hash, String sectionTitle) throws Exception {
        insertMaterial(materialId, filename, hash);
        link(materialId);
        insertContentJob(materialId, hash, "DONE");
        insertSection(materialId, hash, sectionTitle, 1);
    }

    /** 새 파일로 다시 분석됐다: 옛 구간은 물러나고 새 해시의 구간이 생긴다. */
    private void reanalyze(long materialId, String newHash, String sectionTitle) throws Exception {
        exec("UPDATE course_materials SET file_hash = ? WHERE material_id = ? AND user_id = ?",
                newHash, materialId, USER);
        exec("UPDATE material_sections SET status = 'SUPERSEDED' WHERE material_id = ? AND user_id = ?",
                materialId, USER);
        insertContentJob(materialId, newHash, "DONE");
        insertSection(materialId, newHash, sectionTitle, 2);
    }

    private void bumpTreeVersion() throws Exception {
        exec("UPDATE courses SET topic_tree_version = topic_tree_version + 1 WHERE course_id = ?", courseId);
    }

    private long insertCourse() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO courses (user_id, title, status) VALUES (?, '스냅샷 테스트 과목', 'ACTIVE')",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, USER);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long insertTopic(String title) {
        CourseTopic topic = CourseTopic.builder().userId(USER).courseId(courseId).title(title)
                .orderIndex(0).sourceType(TopicSourceType.SOURCE).status(TopicStatus.ACTIVE).build();
        topicMapper.insert(topic);
        return topic.getTopicId();
    }

    private void insertMaterial(long materialId, String filename, String hash) throws Exception {
        exec("INSERT INTO course_materials (material_id, user_id, original_filename, stored_filename, "
                        + "storage_path, size_bytes, file_hash, extraction_status, extracted_text, status) "
                        + "VALUES (?, ?, ?, 'x.pdf', ?, 1, ?, 'SUCCESS', 'text', 'ACTIVE')",
                materialId, USER, filename, USER + "/x-" + materialId + ".pdf", hash);
    }

    private void link(long materialId) throws Exception {
        exec("INSERT INTO material_links (user_id, material_id, course_id, material_type) "
                + "VALUES (?, ?, ?, 'PROFESSOR_SLIDE')", USER, materialId, courseId);
    }

    private void insertContentJob(long materialId, String hash, String status) throws Exception {
        exec("INSERT INTO material_analysis_jobs (user_id, material_id, job_kind, file_hash, analysis_version, "
                        + "priority, status, attempt, max_attempts, total_chunks, completed_chunks, next_run_at, "
                        + "finished_at) VALUES (?, ?, 'CONTENT', ?, ?, 0, ?, 1, 3, 1, 1, NOW(), NOW())",
                USER, materialId, hash, MaterialAnalysisJobService.ANALYSIS_VERSION, status);
    }

    private void insertSection(long materialId, String hash, String title, int page) throws Exception {
        exec("INSERT INTO material_sections (user_id, material_id, file_hash, analysis_version, chunk_index, "
                        + "unit_type, unit_start, unit_end, display_title, roles_json, dedupe_key, status) "
                        + "VALUES (?, ?, ?, ?, 0, 'PDF_PAGE', ?, ?, ?, '[\"CONCEPT\"]', ?, 'ACTIVE')",
                USER, materialId, hash, MaterialAnalysisJobService.ANALYSIS_VERSION, page, page, title,
                "snap-" + materialId + "-" + hash.charAt(0) + "-" + page);
    }

    private void exec(String sql, Object... args) throws Exception {
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    @AfterEach
    void cleanUp() throws Exception {
        List<String> tables = new ArrayList<>(List.of(
                "material_analysis_timings", "project_tidy_edits", "project_tidy_proposal_materials",
                "project_tidy_proposals", "project_tidy_jobs", "material_analysis_jobs", "material_sections",
                "topic_material_links", "course_topics", "material_links", "course_materials", "courses"));
        for (String table : tables) {
            exec("DELETE FROM " + table + " WHERE user_id = ?", USER);
        }
    }
}
