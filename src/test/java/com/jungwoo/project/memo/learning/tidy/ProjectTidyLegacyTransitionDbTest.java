package com.jungwoo.project.memo.learning.tidy;

import com.jungwoo.project.memo.learning.TopicChangeProposalMapper;
import com.jungwoo.project.memo.learning.domain.CourseTopic;
import com.jungwoo.project.memo.learning.domain.TopicChangeProposal;
import com.jungwoo.project.memo.learning.domain.TopicSourceType;
import com.jungwoo.project.memo.learning.domain.TopicStatus;
import com.jungwoo.project.memo.learning.CourseTopicMapper;
import com.jungwoo.project.memo.learning.structure.TopicChangeOp;
import com.jungwoo.project.memo.learning.structure.TopicChangeOpsValidator;
import com.jungwoo.project.memo.learning.structure.TopicChangeProposalService;
import com.jungwoo.project.memo.material.analysis.AnalysisOutcome;
import com.jungwoo.project.memo.material.analysis.MaterialAnalysisJobService;
import com.jungwoo.project.memo.material.analysis.TopicLinkAnalyzer;
import com.jungwoo.project.memo.material.domain.AnalysisJobKind;
import com.jungwoo.project.memo.material.domain.AnalysisJobStatus;
import com.jungwoo.project.memo.material.domain.MaterialAnalysisJob;
import com.jungwoo.project.memo.material.MaterialAnalysisJobMapper;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 자료별 변경안에서 프로젝트 단위 정리로의 전환.
 *
 * <p>고치는 문제: 전환 <직전에> 선점된 LINK 작업이 뒤늦게 끝나면서 자료별 변경안을 다시 만들어
 * 놓으면, 사용자는 없앴다고 생각한 화면을 다시 보게 되고 그 변경안은 이제 아무 데서도 적용할 수
 * 없다. 그래서 <b>등록·실행·저장 세 곳 모두</b>에서 막는다.
 *
 * <p>로컬 memo DB 필요. CI 제외.
 */
@SpringBootTest
class ProjectTidyLegacyTransitionDbTest {

    private static final long USER = 999_000_404L;
    private static final long MATERIAL = 999_400_401L;
    private static final String HASH = "f".repeat(64);

    @Autowired
    private MaterialAnalysisJobService jobService;
    @Autowired
    private MaterialAnalysisJobMapper jobMapper;
    @Autowired
    private TopicChangeProposalService proposalService;
    @Autowired
    private TopicChangeProposalMapper proposalMapper;
    @Autowired
    private TopicLinkAnalyzer linkAnalyzer;
    @Autowired
    private CourseTopicMapper topicMapper;
    @Autowired
    private DataSource dataSource;

    private long courseId;

    @BeforeEach
    void setUp() throws Exception {
        cleanUp();
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO courses (user_id, title, status) VALUES (?, '전환 테스트 과목', 'ACTIVE')",
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
                            + "VALUES (?, ?, 'legacy.pdf', 'x.pdf', ?, 1, ?, 'SUCCESS', 'text', 'ACTIVE')")) {
                ps.setLong(1, MATERIAL);
                ps.setLong(2, USER);
                ps.setString(3, USER + "/legacy.pdf");
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
    }

    @Test
    void 자료별_연결_작업은_더_이상_등록되지_않는다() {
        assertThat(jobService.isLinkJobsEnabled()).isFalse();
        assertThat(jobService.enqueueLink(USER, MATERIAL, courseId, HASH,
                MaterialAnalysisJobService.PRIORITY_NEW_UPLOAD)).isNull();
        assertThat(jobService.retryLink(USER, MATERIAL, courseId, HASH)).isNull();
        assertThat(jobService.registerLinkBacklog(20)).isZero();
        assertThat(jobMapper.findByScope(MATERIAL, courseId, AnalysisJobKind.LINK.name(), HASH,
                MaterialAnalysisJobService.ANALYSIS_VERSION)).isNull();
    }

    @Test
    void 전환_직전에_선점된_작업이_끝나도_변경안을_만들지_못한다() {
        // 전환 전에 등록·선점돼 있던 LINK 작업. 이제 실행 입구에서 취소된다.
        MaterialAnalysisJob job = insertRunningLinkJob();

        AnalysisOutcome outcome = linkAnalyzer.analyze(job);

        assertThat(outcome.status()).isEqualTo(AnalysisJobStatus.CANCELLED);
        assertThat(proposalMapper.findOpenByCourseId(courseId, USER)).isEmpty();
    }

    @Test
    void 저장_입구에서도_막는다_실행을_지나온_결과도_행을_남기지_않는다() {
        // 실행 입구를 우회해 직접 저장을 시도해도(늦게 도착한 옛 판의 결과) 행이 생기지 않는다.
        long topicId = insertTopic("스택");
        TopicChangeOpsValidator.Result validated = TopicChangeOpsValidator.validate(
                List.of(new TopicChangeOp("RENAME", null, topicId, null, null, "새 이름", null, null,
                        null, null, null, null, null, "테스트")),
                topicMapper.findActiveByCourseIdAndUserId(courseId, USER), java.util.Set.of(), false);

        TopicChangeProposal result = proposalService.create(USER, courseId, MATERIAL, null, HASH, 0L,
                validated, "test-model");

        // 메모리 객체는 돌려주지만 저장되지 않았다 — id가 없고 조회해도 없다.
        assertThat(result.getProposalId()).isNull();
        assertThat(proposalMapper.findOpenByCourseId(courseId, USER)).isEmpty();
    }

    @Test
    void 마이그레이션이_옮긴_옛_변경안은_지워지지_않고_이력으로_남는다() throws Exception {
        long proposalId = insertSupersededLegacyProposal();

        assertThat(proposalMapper.countSuperseded(courseId, USER)).isEqualTo(1);
        // 내용은 그대로다 — 되돌리려면 superseded_from을 status로 되쓰면 된다.
        TopicChangeProposal kept = proposalMapper.findByIdAndUserId(proposalId, USER);
        assertThat(kept.getOpsJson()).contains("RENAME");
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT superseded_from FROM topic_change_proposals WHERE proposal_id = ?")) {
            ps.setLong(1, proposalId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("PROPOSED");
            }
        }
    }

    // ===== 준비 도구 =====

    private MaterialAnalysisJob insertRunningLinkJob() {
        MaterialAnalysisJob job = MaterialAnalysisJob.builder()
                .userId(USER).materialId(MATERIAL).courseId(courseId).jobKind(AnalysisJobKind.LINK)
                .fileHash(HASH).analysisVersion(MaterialAnalysisJobService.ANALYSIS_VERSION)
                .priority(0).status(AnalysisJobStatus.QUEUED).maxAttempts(3)
                .nextRunAt(LocalDateTime.now())
                .build();
        jobMapper.insertIgnore(job);
        LocalDateTime now = LocalDateTime.now();
        jobMapper.claim(job.getJobId(), "test", now, now.plusMinutes(5));
        return jobMapper.findById(job.getJobId());
    }

    private long insertTopic(String title) {
        CourseTopic topic = CourseTopic.builder().userId(USER).courseId(courseId).title(title)
                .orderIndex(0).sourceType(TopicSourceType.SOURCE).status(TopicStatus.ACTIVE).build();
        topicMapper.insert(topic);
        return topic.getTopicId();
    }

    private long insertSupersededLegacyProposal() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO topic_change_proposals (user_id, course_id, material_id, file_hash, "
                             + "base_tree_version, status, superseded_from, summary_json, ops_json) "
                             + "VALUES (?, ?, ?, ?, 0, 'SUPERSEDED', 'PROPOSED', '{}', "
                             + "'[{\"op\":\"RENAME\",\"topicId\":1,\"title\":\"옛 제안\"}]')",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, USER);
            ps.setLong(2, courseId);
            ps.setLong(3, MATERIAL);
            ps.setString(4, HASH);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            for (String sql : List.of(
                    "DELETE FROM topic_change_proposals WHERE user_id = ?",
                    "DELETE FROM material_analysis_jobs WHERE user_id = ?",
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
