package com.jungwoo.project.memo.learning.structure;

import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.learning.CourseTopicMapper;
import com.jungwoo.project.memo.learning.TopicMaterialLinkMapper;
import com.jungwoo.project.memo.learning.TopicProgressMapper;
import com.jungwoo.project.memo.learning.domain.CourseTopic;
import com.jungwoo.project.memo.learning.domain.TopicChangeProposal;
import com.jungwoo.project.memo.learning.domain.TopicChangeProposalStatus;
import com.jungwoo.project.memo.learning.domain.TopicMaterialLink;
import com.jungwoo.project.memo.learning.domain.TopicProgress;
import com.jungwoo.project.memo.learning.domain.TopicProgressStatus;
import com.jungwoo.project.memo.learning.domain.TopicStatus;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 변경안 적용을 실제 DB에서 본다(표 8·10·11·12): id 보존, 병합의 기록 처리, 오래된 트리 버전 거부,
 * 재적용 거부, 잘못된 부모의 전체 거부(부분 적용 없음). 로컬 memo DB 필요. CI 제외.
 */
@SpringBootTest
class TopicChangeProposalApplyDbTest {

    private static final long USER = 999_000_303L;
    private static final long MATERIAL = 999_300_201L;
    private static final String HASH = "e".repeat(64);

    @Autowired
    private TopicChangeProposalService proposalService;
    @Autowired
    private TopicTreeEditor treeEditor;
    @Autowired
    private CourseTopicMapper topicMapper;
    @Autowired
    private TopicMaterialLinkMapper linkMapper;
    @Autowired
    private TopicProgressMapper progressMapper;
    @Autowired
    private DataSource dataSource;

    private long courseId;
    private long root;
    private long child;
    private long other;

    @BeforeEach
    void setUp() throws Exception {
        cleanUp();
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO courses (user_id, title, status) VALUES (?, '변경안 테스트 과목', 'ACTIVE')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, USER);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    courseId = rs.getLong(1);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO course_materials (material_id, user_id, original_filename, stored_filename, storage_path, size_bytes, file_hash, extraction_status, extracted_text, status) "
                            + "VALUES (?, ?, 'ds.pdf', 'x.pdf', ?, 1, ?, 'SUCCESS', 'text', 'ACTIVE')")) {
                ps.setLong(1, MATERIAL);
                ps.setLong(2, USER);
                ps.setString(3, USER + "/x-" + MATERIAL + ".pdf");
                ps.setString(4, HASH);
                ps.executeUpdate();
            }
        }
        root = insertTopic(null, "연결 리스트", 0);
        child = insertTopic(root, "단순 연결 리스트", 0);
        other = insertTopic(null, "스택", 1);
    }

    private long insertTopic(Long parent, String title, int order) {
        CourseTopic topic = CourseTopic.builder().userId(USER).courseId(courseId).parentTopicId(parent).title(title)
                .orderIndex(order).sourceType(com.jungwoo.project.memo.learning.domain.TopicSourceType.SOURCE)
                .status(TopicStatus.ACTIVE).build();
        topicMapper.insert(topic);
        return topic.getTopicId();
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            for (String sql : List.of(
                    "DELETE FROM topic_change_proposals WHERE user_id = ?",
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

    private long treeVersion() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT topic_tree_version FROM courses WHERE course_id = ?")) {
            ps.setLong(1, courseId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static TopicChangeOp op(String kind, Long topicId, Long parent, String title, List<Long> sections) {
        return new TopicChangeOp(kind, null, topicId, parent, null, title, "SOURCE", null, sections, "EXERCISE",
                null, null, null, "test");
    }

    private static TopicChangeOp merge(long surviving, long absorbed) {
        return new TopicChangeOp("MERGE", null, null, null, null, null, null, null, null, null, surviving,
                List.of(absorbed), null, "중복");
    }

    @Test
    void 이동_이름변경_병합은_기존_id를_유지하고_흡수된_항목의_기록은_안내로_남는다() throws Exception {
        // 흡수될 항목(other=스택)에 학습 기록을 둔다. 살아남는 항목(root)에는 없다.
        progressMapper.insert(TopicProgress.builder().userId(USER).topicId(other)
                .status(TopicProgressStatus.LEARNED).reviewCount(0).build());
        linkMapper.upsert(TopicMaterialLink.builder().userId(USER).courseId(courseId).topicId(other)
                .materialId(MATERIAL).sectionId(0L).role("SOURCE").origin(com.jungwoo.project.memo.learning.domain.TopicLinkOrigin.USER).build());
        long before = treeVersion();

        TopicTreeEditor.Applied applied = treeEditor.apply(USER, courseId, MATERIAL, List.of(
                op("RENAME", child, null, "단순 연결 리스트 (삽입·삭제)", null),
                op("MOVE", child, null, null, null),
                merge(root, other)), before, Map.of(), com.jungwoo.project.memo.learning.domain.TopicLinkOrigin.PROPOSAL_APPLIED);

        assertThat(applied.renamed()).isEqualTo(1);
        assertThat(applied.moved()).isEqualTo(1);
        assertThat(applied.merged()).isEqualTo(1);
        CourseTopic renamed = topicMapper.findByIdAndUserId(child, USER);
        assertThat(renamed.getTitle()).isEqualTo("단순 연결 리스트 (삽입·삭제)");
        assertThat(renamed.getParentTopicId()).isNull();
        CourseTopic absorbed = topicMapper.findByIdAndUserId(other, USER);
        assertThat(absorbed.getStatus()).isEqualTo(TopicStatus.ARCHIVED);
        assertThat(absorbed.getMergedIntoTopicId()).isEqualTo(root);
        // 기록은 복제되지 않는다 — 살아남은 항목은 여전히 기록이 없고 review_note가 남는다.
        assertThat(progressMapper.findByUserIdAndTopicId(USER, root)).isNull();
        assertThat(progressMapper.findByUserIdAndTopicId(USER, other).getStatus()).isEqualTo(TopicProgressStatus.LEARNED);
        assertThat(topicMapper.findByIdAndUserId(root, USER).getReviewNote()).contains("학습 기록");
        // 흡수된 항목의 자료 연결은 살아남은 항목으로 옮겨진다.
        assertThat(linkMapper.findActiveByTopicId(root, USER)).extracting(TopicMaterialLink::getMaterialId).contains(MATERIAL);
        assertThat(treeVersion()).isEqualTo(before + 1);
    }

    @Test
    void 오래된_트리_버전의_변경안은_적용되지_않고_아무것도_바뀌지_않는다() throws Exception {
        long stale = treeVersion();
        treeEditor.apply(USER, courseId, MATERIAL, List.of(op("RENAME", root, null, "연결 리스트 A", null)),
                stale, Map.of(), com.jungwoo.project.memo.learning.domain.TopicLinkOrigin.PROPOSAL_APPLIED);

        assertThatThrownBy(() -> treeEditor.apply(USER, courseId, MATERIAL,
                List.of(op("RENAME", root, null, "연결 리스트 B", null)), stale, Map.of(),
                com.jungwoo.project.memo.learning.domain.TopicLinkOrigin.PROPOSAL_APPLIED))
                .isInstanceOf(ConflictException.class);
        assertThat(topicMapper.findByIdAndUserId(root, USER).getTitle()).isEqualTo("연결 리스트 A");
        assertThat(treeVersion()).isEqualTo(stale + 1);
    }

    @Test
    void 잘못된_부모가_하나라도_있으면_전체가_거부되고_부분_적용이_없다() throws Exception {
        long version = treeVersion();
        assertThatThrownBy(() -> treeEditor.apply(USER, courseId, MATERIAL, List.of(
                op("RENAME", root, null, "바뀐 제목", null),
                op("MOVE", root, child, null, null)), version, Map.of(),
                com.jungwoo.project.memo.learning.domain.TopicLinkOrigin.PROPOSAL_APPLIED))
                .isInstanceOf(BadRequestException.class);
        assertThat(topicMapper.findByIdAndUserId(root, USER).getTitle()).isEqualTo("연결 리스트");
        assertThat(treeVersion()).isEqualTo(version);
    }

    @Test
    void 변경안은_한_번만_적용되고_두_번째는_409다() throws Exception {
        TopicChangeOpsValidator.Result validated = TopicChangeOpsValidator.validate(
                List.of(op("ADD", null, root, "이중 연결 리스트", List.of())),
                topicMapper.findActiveByCourseIdAndUserId(courseId, USER), Set.of(), false);
        TopicChangeProposal proposal = proposalService.create(USER, courseId, MATERIAL, null, HASH, treeVersion(),
                validated, "test-model");
        assertThat(proposal.getStatus()).isEqualTo(TopicChangeProposalStatus.PROPOSED);
        // 적용에는 자료 연결이 있어야 한다.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO material_links (user_id, material_id, course_id, material_type) VALUES (?, ?, ?, 'PROFESSOR_SLIDE')")) {
            ps.setLong(1, USER);
            ps.setLong(2, MATERIAL);
            ps.setLong(3, courseId);
            ps.executeUpdate();
        }
        // 자료 행이 없으면 STALE 처리되므로 자료 행도 있어야 한다(setUp에서 넣었다). 해시도 같아야 한다.
        TopicChangeProposalResponse applied = proposalService.apply(USER, proposal.getProposalId(), null);
        assertThat(applied.getStatus()).isEqualTo(TopicChangeProposalStatus.APPLIED);
        assertThat(topicMapper.findActiveByCourseIdAndUserId(courseId, USER))
                .extracting(CourseTopic::getTitle).contains("이중 연결 리스트");

        assertThatThrownBy(() -> proposalService.apply(USER, proposal.getProposalId(), null))
                .isInstanceOf(ConflictException.class);
        assertThat(topicMapper.findActiveByCourseIdAndUserId(courseId, USER).stream()
                .filter(t -> t.getTitle().equals("이중 연결 리스트"))).hasSize(1);
    }

    @Test
    void 분할은_원본을_부모로_남기고_기록을_자식에_복제하지_않는다() throws Exception {
        progressMapper.insert(TopicProgress.builder().userId(USER).topicId(child)
                .status(TopicProgressStatus.LEARNED).reviewCount(0).build());
        TopicChangeOp split = new TopicChangeOp("SPLIT", null, child, null, null, null, null, null, null, null, null,
                null, List.of(
                new TopicChangeOp("ADD", "a", null, null, null, "삽입", "AI_DERIVED", null, List.of(), null, null, null, null, null),
                new TopicChangeOp("ADD", "b", null, null, null, "삭제", "AI_DERIVED", null, List.of(), null, null, null, null, null)),
                "범위가 섞임");
        TopicTreeEditor.Applied applied = treeEditor.apply(USER, courseId, MATERIAL, List.of(split), treeVersion(),
                Map.of(), com.jungwoo.project.memo.learning.domain.TopicLinkOrigin.PROPOSAL_APPLIED);

        assertThat(applied.split()).isEqualTo(2);
        List<CourseTopic> children = topicMapper.findActiveByCourseIdAndUserId(courseId, USER).stream()
                .filter(t -> Long.valueOf(child).equals(t.getParentTopicId())).toList();
        assertThat(children).extracting(CourseTopic::getTitle).containsExactlyInAnyOrder("삽입", "삭제");
        for (CourseTopic c : children) {
            assertThat(progressMapper.findByUserIdAndTopicId(USER, c.getTopicId())).isNull();
        }
        assertThat(progressMapper.findByUserIdAndTopicId(USER, child).getStatus()).isEqualTo(TopicProgressStatus.LEARNED);
        assertThat(topicMapper.findByIdAndUserId(child, USER).getReviewNote()).isNotBlank();
    }
}
