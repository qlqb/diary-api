package com.jungwoo.project.memo.learning.events;

import com.jungwoo.project.memo.ai.UserContextService;
import com.jungwoo.project.memo.course.CourseMapper;
import com.jungwoo.project.memo.execution.ExecutionItemService;
import com.jungwoo.project.memo.execution.dto.ExecutionItemCompleteRequest;
import com.jungwoo.project.memo.execution.dto.ExecutionRecordReflectionRequest;
import com.jungwoo.project.memo.learning.TopicService;
import com.jungwoo.project.memo.learning.correction.CourseCorrectionService;
import com.jungwoo.project.memo.learning.domain.TopicProgressStatus;
import com.jungwoo.project.memo.learning.domain.TopicUserMark;
import com.jungwoo.project.memo.learning.structure.TopicChangeOp;
import com.jungwoo.project.memo.material.MaterialService;
import com.jungwoo.project.memo.material.domain.MaterialType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기존 쓰기 경로 → 학습 이벤트(1단계 B): 실행 완료·회고 수정, 기억 저장·철회·해결, 자기 점검, 토픽 진도·표식, 범위 제외·CLASS,
 * 자료 연결, 사진 턴. 합성 사용자·합성 자료. 로컬 memo_test DB 필요. CI 제외.
 */
@SpringBootTest
class LearningEventsFlowDbTest {

    private static final long USER = 999_000_981L;
    private static final String HASH = "d".repeat(64);
    private static final String BOOK_KEY = "title:다솜교재";

    @Autowired
    private ExecutionItemService executionItemService;
    @Autowired
    private UserContextService userContextService;
    @Autowired
    private TopicService topicService;
    @Autowired
    private CourseCorrectionService correctionService;
    @Autowired
    private MaterialService materialService;
    @Autowired
    private MemoryEventRecorder memoryEvents;
    @Autowired
    private PhotoTurnEventRecorder photoTurnEvents;
    @Autowired
    private LearningEventLog eventLog;
    @Autowired
    private CourseMapper courseMapper;
    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private DataSource dataSource;

    private long courseId;
    private long otherCourseId;
    private long materialId;
    private long sectionId;
    private long tocTopic;
    private long plainTopic;

    @BeforeEach
    void setUp() throws Exception {
        cleanUp();
        exec("INSERT INTO users (user_id, email, password_hash, nickname, role, status) "
                + "VALUES (?, ?, '!no-login', '이벤트 흐름 테스트', 'USER', 'ACTIVE')", USER, "flow-" + USER + "@memo-test.invalid");
        courseId = insert("INSERT INTO courses (user_id, title, status) VALUES (?, '다솜', 'ACTIVE')", USER);
        otherCourseId = insert("INSERT INTO courses (user_id, title, status) VALUES (?, '라온', 'ACTIVE')", USER);
        materialId = insert("INSERT INTO course_materials (user_id, original_filename, stored_filename, storage_path, size_bytes, "
                + "extraction_status, status) VALUES (?, '합성.pdf', 'x.pdf', 'none/x.pdf', 1, 'SUCCESS', 'ACTIVE')", USER);
        exec("INSERT INTO material_links (user_id, material_id, course_id, material_type) VALUES (?, ?, ?, 'PROFESSOR_SLIDE')",
                USER, materialId, courseId);
        sectionId = insert("INSERT INTO material_sections (user_id, material_id, file_hash, analysis_version, chunk_index, unit_type, "
                + "unit_start, unit_end, display_title, roles_json, dedupe_key) "
                + "VALUES (?, ?, ?, 1, 0, 'PAGE', 1, 3, '합성 구간', '[]', 'k1')", USER, materialId, "e".repeat(64));
        tocTopic = insert("INSERT INTO course_topics (user_id, course_id, title, order_index, source_type, source_locator, "
                + "source_textbook_key, toc_key_kind, toc_key_hash, toc_key_line, toc_key_state, status) "
                + "VALUES (?, ?, '01 다솜 개요', 0, 'SOURCE', '교재 목차', ?, 'WEB', ?, 4, 'SET', 'ACTIVE')", USER, courseId, BOOK_KEY, HASH);
        plainTopic = insert("INSERT INTO course_topics (user_id, course_id, title, order_index, source_type, status) "
                + "VALUES (?, ?, '자료 토픽', 1, 'SOURCE', 'ACTIVE')", USER, courseId);
        exec("INSERT INTO topic_material_links (user_id, course_id, topic_id, material_id, section_id, role, origin, status) "
                + "VALUES (?, ?, ?, ?, ?, 'CONCEPT', 'USER', 'ACTIVE')", USER, courseId, plainTopic, materialId, sectionId);
    }

    private List<LearningEvent> live() {
        return eventLog.liveEvents(USER, courseId);
    }

    private List<LearningEvent> live(String verb) {
        return live().stream().filter(e -> e.getVerb().equals(verb)).toList();
    }

    private long item(Long topic, Long course) throws Exception {
        return insert("INSERT INTO execution_items (user_id, topic_id, course_id, title, placement_type, scheduled_date, "
                + "expected_minutes, status, priority, order_index, origin_type, version, is_deleted) "
                + "VALUES (?, ?, ?, '합성 활동', 'DATE_ONLY', CURDATE(), 30, 'PLANNED', 'SHOULD', 0, 'MANUAL', 0, 0)",
                USER, topic, course);
    }

    @Test
    void 실행_완료는_시도이고_막힌_단계를_지운_회고는_막힘을_내리며_원천은_고정된다() throws Exception {
        long item = item(tocTopic, courseId);
        executionItemService.complete(item, USER, ExecutionItemCompleteRequest.builder().version(0L)
                .stuckStep("포인터 연결").build());

        List<LearningEvent> attempted = live("ATTEMPTED");
        assertThat(attempted).hasSize(1);
        assertThat(attempted.get(0).getObjectKind()).isEqualTo("TOC_ENTRY");
        assertThat(attempted.get(0).getEvidence()).isEqualTo("APPROX");   // 계획 인용 없음 — 토픽으로 근사
        assertThat(attempted.get(0).getPayload()).contains("\"outcome\":\"DONE_UNGRADED\"").contains("\"supportLevel\":\"UNKNOWN\"")
                .doesNotContain("포인터");
        assertThat(live("STUCK")).hasSize(1);

        // 그 사이 항목의 토픽이 바뀌어도 회고 수정은 원천을 다시 찾지 않는다
        exec("UPDATE execution_items SET topic_id = ? WHERE execution_item_id = ?", plainTopic, item);
        long record = queryLong("SELECT execution_record_id FROM execution_records WHERE execution_item_id = ?", item);
        executionItemService.updateReflection(USER, record, ExecutionRecordReflectionRequest.builder().supportLevel("SOLO").build());

        assertThat(live("STUCK")).isEmpty();
        assertThat(live("ATTEMPTED")).singleElement()
                .satisfies(e -> assertThat(e.getObjectKind()).isEqualTo("TOC_ENTRY"))
                .satisfies(e -> assertThat(e.getPayload()).contains("\"supportLevel\":\"SOLO\""));
    }

    @Test
    void 과목_없는_실행은_대상_밖이고_완료는_그대로_된다() throws Exception {
        long item = item(null, null);
        executionItemService.complete(item, USER, ExecutionItemCompleteRequest.builder().version(0L).build());

        assertThat(queryLong("SELECT COUNT(*) FROM execution_records WHERE execution_item_id = ?", item)).isEqualTo(1);
        assertThat(queryLong("SELECT COUNT(*) FROM learning_events WHERE user_id = ?", USER)).isZero();
    }

    @Test
    void 전환_전_기록은_근사_원천이면_만들지_않는다() throws Exception {
        long item = item(plainTopic, courseId);
        executionItemService.complete(item, USER, ExecutionItemCompleteRequest.builder().version(0L).build());
        long record = queryLong("SELECT execution_record_id FROM execution_records WHERE execution_item_id = ?", item);
        // 전환 전 기록처럼: 이벤트를 지우고 생성 시각을 과거로
        exec("DELETE FROM learning_events WHERE user_id = ?", USER);
        exec("DELETE FROM learning_event_origins WHERE user_id = ?", USER);
        exec("UPDATE execution_records SET created_at = '2020-01-01 00:00:00' WHERE execution_record_id = ?", record);

        executionItemService.updateReflection(USER, record, ExecutionRecordReflectionRequest.builder().supportLevel("SOLO").build());

        assertThat(queryLong("SELECT COUNT(*) FROM learning_event_origins WHERE user_id = ? AND current_revision > 0", USER))
                .isZero();
    }

    @Test
    void 막힘과_해결은_원천에_남고_해결_철회는_막힘을_다시_연다() throws Exception {
        long difficulty = context("DIFFICULTY", "ACTIVE", tocTopic, null);
        sync();
        long stuck = live("STUCK").get(0).getEventId();

        // 해결: 막힘은 대체되고 해결 기억이 생긴다(18번 자동 저장과 같은 모양) + 해결 연결
        exec("UPDATE user_contexts SET status = 'SUPERSEDED' WHERE context_id = ?", difficulty);
        long resolver = context("RESOLVED", "ACTIVE", tocTopic, difficulty);
        tx.executeWithoutResult(s -> {
            courseMapper.findByIdAndUserIdForUpdate(courseId, USER);
            memoryEvents.linkResolution(USER, difficulty, resolver);
            memoryEvents.syncCourse(USER, courseId);
        });

        assertThat(live("STUCK")).extracting(LearningEvent::getEventId).containsExactly(stuck);
        assertThat(live("RESOLVED")).singleElement().satisfies(e -> assertThat(e.getPayload()).contains("\"resolves\":" + stuck));

        userContextService.withdraw(USER, resolver);

        assertThat(live("RESOLVED")).isEmpty();
        assertThat(live("STUCK")).hasSize(1);
    }

    @Test
    void 최신_진도를_철회해도_옛_진도가_되살아나지_않는다() throws Exception {
        context("PROGRESS", "SUPERSEDED", tocTopic, null);
        long latest = context("PROGRESS", "ACTIVE", tocTopic, null);
        sync();
        assertThat(live("STATED")).hasSize(1);

        userContextService.withdraw(USER, latest);

        assertThat(live("STATED")).isEmpty();
    }

    @Test
    void 자기_점검은_같은_대상의_새_점검이_옛_것을_내린다() {
        userContextService.saveSelfChecks(USER, courseId, List.of(new UserContextService.SelfCheck("01 다솜 개요", tocTopic, null,
                "KNOW", null)));
        userContextService.saveSelfChecks(USER, courseId, List.of(new UserContextService.SelfCheck("01 다솜 개요", tocTopic, null,
                "UNSURE", null)));

        assertThat(live("SELF_ASSESSED")).singleElement()
                .satisfies(e -> assertThat(e.getPayload()).contains("\"level\":\"UNSURE\""));
    }

    @Test
    void 토픽_진도_익힘과_이미_알아요만_자기_평가이고_되돌리면_내려간다() {
        topicService.updateProgressStatus(USER, tocTopic, TopicProgressStatus.LEARNED);
        topicService.updateUserMark(USER, plainTopic, TopicUserMark.KNOWN);
        assertThat(live("SELF_ASSESSED")).hasSize(2);

        topicService.updateProgressStatus(USER, tocTopic, TopicProgressStatus.IN_PROGRESS);
        topicService.updateUserMark(USER, plainTopic, TopicUserMark.DEFER);
        assertThat(live("SELF_ASSESSED")).isEmpty();
    }

    @Test
    void 범위_제외와_실제_수업_정정() throws Exception {
        tx.executeWithoutResult(s -> {
            courseMapper.findByIdAndUserIdForUpdate(courseId, USER);
            correctionService.apply(USER, courseId, List.of(op("SCOPE_EXCLUDE", tocTopic, null, "중간"),
                    op("CLASS", plainTopic, 3, null)));
        });
        assertThat(live("SCOPE_EXCLUDED")).singleElement()
                .satisfies(e -> assertThat(e.getPayload()).contains("\"examKey\":\"중간\""));
        assertThat(live("STATED")).singleElement()
                .satisfies(e -> assertThat(e.getPayload()).contains("COVERED_IN_WEEK").contains("\"week\":3")
                        .contains("s:" + sectionId));

        long exclusion = queryLong("SELECT exclusion_id FROM course_scope_exclusions WHERE user_id = ?", USER);
        correctionService.removeExclusion(USER, courseId, exclusion);
        assertThat(live("SCOPE_EXCLUDED")).isEmpty();
    }

    @Test
    void 자료_연결은_공개_이벤트이고_연결을_끊어도_남는다() throws Exception {
        materialService.addLink(USER, materialId, otherCourseId, MaterialType.OTHER);
        Predicate<LearningEvent> released = e -> e.getVerb().equals("RELEASED");
        assertThat(eventLog.liveEvents(USER, otherCourseId).stream().filter(released)).singleElement()
                .satisfies(e -> assertThat(e.getObjectRef()).isEqualTo("m:" + materialId));

        materialService.removeLink(USER, materialId, otherCourseId);
        assertThat(eventLog.liveEvents(USER, otherCourseId).stream().filter(released)).hasSize(1);
    }

    @Test
    void 사진을_붙인_턴이_끝나면_사진_구간을_공부한_것으로_남는다() throws Exception {
        long conversation = insert("INSERT INTO ai_conversations (user_id, scope, course_id, status) VALUES (?, 'PLAN', ?, 'ACTIVE')",
                USER, courseId);
        long message = insert("INSERT INTO ai_messages (conversation_id, user_id, role, content, status) "
                + "VALUES (?, ?, 'USER', '합성 질문', 'COMPLETED')", conversation, USER);
        exec("INSERT INTO ai_message_photos (message_id, material_id, user_id, conversation_id) VALUES (?, ?, ?, ?)",
                message, materialId, USER, conversation);

        tx.executeWithoutResult(s -> photoTurnEvents.turnCompleted(USER, message));

        assertThat(live("STUDIED")).singleElement()
                .satisfies(e -> assertThat(e.getObjectRef()).isEqualTo("s:" + sectionId));
    }

    @Test
    void 자료_연결을_끊은_뒤에도_그_구간의_막힘을_해결할_수_있다() throws Exception {
        long difficulty = contextAt("DIFFICULTY", "ACTIVE", null, sectionId, null);
        sync();
        exec("DELETE FROM material_links WHERE user_id = ? AND course_id = ?", USER, courseId);

        exec("UPDATE user_contexts SET status = 'SUPERSEDED' WHERE context_id = ?", difficulty);
        long resolver = contextAt("RESOLVED", "ACTIVE", null, sectionId, difficulty);
        tx.executeWithoutResult(s -> {
            courseMapper.findByIdAndUserIdForUpdate(courseId, USER);
            memoryEvents.linkResolution(USER, difficulty, resolver);
            memoryEvents.syncCourse(USER, courseId);
        });

        assertThat(live("RESOLVED")).singleElement()
                .satisfies(e -> assertThat(e.getObjectRef()).isEqualTo("s:" + sectionId));
    }

    @Test
    void 해결_기억을_다른_종류로_고치면_해결만_내려가고_막힘은_남는다() throws Exception {
        long difficulty = context("DIFFICULTY", "ACTIVE", tocTopic, null);
        sync();
        exec("UPDATE user_contexts SET status = 'SUPERSEDED' WHERE context_id = ?", difficulty);
        long resolver = context("RESOLVED", "ACTIVE", tocTopic, difficulty);
        tx.executeWithoutResult(s -> {
            courseMapper.findByIdAndUserIdForUpdate(courseId, USER);
            memoryEvents.linkResolution(USER, difficulty, resolver);
            memoryEvents.syncCourse(USER, courseId);
        });
        assertThat(live("RESOLVED")).hasSize(1);

        var asProgress = userContextService.edit(USER, resolver, new UserContextService.Edit("합성 진도로 고침",
                com.jungwoo.project.memo.ai.domain.FactKind.PROGRESS, null, null, null));

        assertThat(live("RESOLVED")).isEmpty();
        assertThat(live("STUCK")).hasSize(1);

        // 다시 해결로 되돌리면 해결이 돌아온다(연결이 고친 행을 따라간다)
        userContextService.edit(USER, asProgress.context().getContextId(), new UserContextService.Edit("합성 해결로 되돌림",
                com.jungwoo.project.memo.ai.domain.FactKind.RESOLVED, null, null, null));
        assertThat(live("RESOLVED")).hasSize(1);
    }

    @Test
    void 두_과목의_첫_기록을_동시에_해도_교착하지_않는다() throws Exception {
        long secondTopic = insert("INSERT INTO course_topics (user_id, course_id, title, order_index, source_type, status) "
                + "VALUES (?, ?, '라온 토픽', 0, 'SOURCE', 'ACTIVE')", USER, otherCourseId);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        for (int round = 0; round < 5; round++) {
            String level = round % 2 == 0 ? "KNOW" : "UNSURE";
            futures.add(pool.submit(() -> {
                start.await();
                return userContextService.saveSelfChecks(USER, courseId,
                        List.of(new UserContextService.SelfCheck("01 다솜 개요", tocTopic, null, level, null)));
            }));
            futures.add(pool.submit(() -> {
                start.await();
                return userContextService.saveSelfChecks(USER, otherCourseId,
                        List.of(new UserContextService.SelfCheck("라온 토픽", secondTopic, null, level, null)));
            }));
        }
        start.countDown();
        for (java.util.concurrent.Future<?> f : futures) {
            f.get(60, java.util.concurrent.TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(live("SELF_ASSESSED")).hasSize(1);
        assertThat(eventLog.liveEvents(USER, otherCourseId).stream().filter(e -> e.getVerb().equals("SELF_ASSESSED")))
                .hasSize(1);
    }

    @Test
    void 시험_범위의_원천은_연결을_끊어도_다른_기억을_바꿀_때_사라지지_않는다() throws Exception {
        contextAt("EXAM_SCOPE", "ACTIVE", null, sectionId, null);
        sync();
        exec("DELETE FROM material_links WHERE user_id = ? AND course_id = ?", USER, courseId);
        context("PROGRESS", "ACTIVE", tocTopic, null);
        sync();

        assertThat(live("SCOPE_ANNOUNCED")).singleElement()
                .satisfies(e -> assertThat(e.getPayload()).contains("s:" + sectionId));
    }

    @Test
    void 전환_전_시험_범위는_원천을_못_찾으면_만들지_않는다() throws Exception {
        long scope = context("EXAM_SCOPE", "ACTIVE", plainTopic, null);
        exec("UPDATE user_contexts SET created_at = '2020-01-01 00:00:00' WHERE context_id = ?", scope);
        exec("DELETE FROM topic_material_links WHERE user_id = ?", USER);
        sync();

        assertThat(live("SCOPE_ANNOUNCED")).isEmpty();
    }

    @Test
    void 자료를_지우면_구간의_제목도_비우고_이벤트는_남는다() throws Exception {
        materialService.addLink(USER, materialId, otherCourseId, MaterialType.OTHER);
        materialService.delete(USER, materialId);

        assertThat(queryString("SELECT display_title FROM material_sections WHERE section_id = ?", sectionId)).isEmpty();
        assertThat(eventLog.liveEvents(USER, otherCourseId)).extracting(LearningEvent::getVerb).contains("RELEASED");
    }

    // ===== 도움 =====

    private static TopicChangeOp op(String op, Long topicId, Integer week, String label) {
        return new TopicChangeOp(op, null, topicId, null, null, null, null, null, null, null, null, null, null, null, null,
                null, week, null, label, null, null);
    }

    private long context(String kind, String status, Long topicId, Long supersedes) throws Exception {
        return insert("INSERT INTO user_contexts (user_id, content, status, source_type, evidence_type, fact_kind, course_id, "
                + "topic_id, supersedes_context_id, said_at) VALUES (?, ?, ?, 'CONSULT_AUTO', 'STATED', ?, ?, ?, ?, NOW())",
                USER, "합성 기억 " + kind + System.nanoTime(), status, kind, courseId, topicId, supersedes);
    }

    private long contextAt(String kind, String status, Long topicId, Long sectionId, Long supersedes) throws Exception {
        return insert("INSERT INTO user_contexts (user_id, content, status, source_type, evidence_type, fact_kind, course_id, "
                + "topic_id, section_id, supersedes_context_id, said_at) VALUES (?, ?, ?, 'CONSULT_AUTO', 'STATED', ?, ?, ?, ?, ?, NOW())",
                USER, "합성 기억 " + kind + System.nanoTime(), status, kind, courseId, topicId, sectionId, supersedes);
    }

    private String queryString(String sql, Object... args) throws Exception {
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private void sync() {
        tx.executeWithoutResult(s -> {
            courseMapper.findByIdAndUserIdForUpdate(courseId, USER);
            memoryEvents.syncCourse(USER, courseId);
        });
    }

    private long insert(String sql, Object... args) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long queryLong(String sql, Object... args) throws Exception {
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
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
        exec("DELETE FROM learning_resolution_links WHERE user_id = ?", USER);
        exec("DELETE r FROM execution_records r WHERE r.user_id = ?", USER);
        for (String table : List.of("learning_events", "learning_event_origins", "textbook_ref_aliases", "textbook_refs",
                "ai_message_photos", "ai_messages", "ai_conversations", "execution_item_events", "execution_items",
                "course_scope_exclusions", "topic_class_progress", "topic_learning_events", "topic_progress", "user_contexts",
                "topic_material_links", "material_sections", "material_links", "course_topics", "course_materials", "courses",
                "users")) {
            exec("DELETE FROM " + table + " WHERE user_id = ?", USER);
        }
    }
}
