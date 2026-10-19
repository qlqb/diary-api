package com.jungwoo.project.memo.learning.events.backfill;

import com.jungwoo.project.memo.learning.events.EventCutover;
import com.jungwoo.project.memo.learning.events.LearningEvent;
import com.jungwoo.project.memo.learning.events.LearningEventLog;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 백필: 전환 전 원본만, 직접 가리킨 원천만(근사뿐이면 미대응으로 센다), 막힘 → 해결 연결 복원, 다시 돌려도 더 생기지 않음, 실시간이 먼저
 * 쓴 origin은 건너뜀, 다른 서버가 lease를 쥐고 있으면 건드리지 않음. 합성 사용자. 로컬 memo_test DB 필요. CI 제외.
 */
@SpringBootTest
class LearningEventBackfillDbTest {

    private static final long USER = 999_000_995L;
    private static final String OLD = "2020-01-01 00:00:00";
    private static final String HASH = "a1".repeat(32);

    @Autowired
    private LearningEventBackfill backfill;
    @Autowired
    private EventCutover cutover;
    @Autowired
    private LearningEventLog eventLog;
    @Autowired
    private DataSource dataSource;

    private long courseId;
    private long tocTopic;
    private long plainTopic;
    private long linkId;

    @BeforeEach
    void setUp() throws Exception {
        cleanUp();
        exec("INSERT INTO users (user_id, email, password_hash, nickname, role, status) "
                + "VALUES (?, ?, '!no-login', '백필 테스트', 'USER', 'ACTIVE')", USER, "bf-" + USER + "@memo-test.invalid");
        courseId = insert("INSERT INTO courses (user_id, title, status) VALUES (?, '하늘', 'ACTIVE')", USER);
        long material = insert("INSERT INTO course_materials (user_id, original_filename, stored_filename, storage_path, size_bytes, "
                + "extraction_status, status) VALUES (?, '합성.pdf', 'x.pdf', 'none/x.pdf', 1, 'SUCCESS', 'ACTIVE')", USER);
        linkId = insert("INSERT INTO material_links (user_id, material_id, course_id, material_type, linked_at) "
                + "VALUES (?, ?, ?, 'PROFESSOR_SLIDE', ?)", USER, material, courseId, OLD);
        tocTopic = insert("INSERT INTO course_topics (user_id, course_id, title, order_index, source_type, source_locator, "
                + "source_textbook_key, toc_key_kind, toc_key_hash, toc_key_line, toc_key_state, status, created_at) "
                + "VALUES (?, ?, '01 하늘 개요', 0, 'SOURCE', '교재 목차', 'title:하늘교재', 'WEB', ?, 3, 'SET', 'ACTIVE', ?)",
                USER, courseId, HASH, OLD);
        plainTopic = insert("INSERT INTO course_topics (user_id, course_id, title, order_index, source_type, status, created_at) "
                + "VALUES (?, ?, '자료 토픽', 1, 'SOURCE', 'ACTIVE', ?)", USER, courseId, OLD);
        exec("INSERT INTO topic_progress (user_id, topic_id, status, created_at, updated_at) VALUES (?, ?, 'LEARNED', ?, ?)",
                USER, tocTopic, OLD, OLD);
        // 계획 인용 없는 옛 실행 기록 — 토픽으로만 근사할 수 있다(백필은 만들지 않는다)
        long item = insert("INSERT INTO execution_items (user_id, topic_id, course_id, title, placement_type, scheduled_date, "
                + "expected_minutes, status, priority, order_index, origin_type, version, is_deleted) "
                + "VALUES (?, ?, ?, '옛 활동', 'DATE_ONLY', CURDATE(), 30, 'DONE', 'SHOULD', 0, 'MANUAL', 1, 0)", USER, plainTopic, courseId);
        exec("INSERT INTO execution_records (user_id, execution_item_id, outcome, completion_percent, recorded_at, created_at) "
                + "VALUES (?, ?, 'COMPLETED', 100, ?, ?)", USER, item, OLD, OLD);
        // 막힘 → 해결(옛 자동 저장 모양: 막힘은 대체됨, 해결이 막힘을 가리킴)
        long difficulty = insert("INSERT INTO user_contexts (user_id, content, status, source_type, evidence_type, fact_kind, "
                + "course_id, topic_id, said_at, created_at) VALUES (?, '합성 막힘', 'SUPERSEDED', 'CONSULT_AUTO', 'STATED', "
                + "'DIFFICULTY', ?, ?, ?, ?)", USER, courseId, tocTopic, OLD, OLD);
        insert("INSERT INTO user_contexts (user_id, content, status, source_type, evidence_type, fact_kind, course_id, topic_id, "
                + "supersedes_context_id, help_level, said_at, created_at) VALUES (?, '합성 해결', 'ACTIVE', 'CONSULT_AUTO', 'STATED', "
                + "'RESOLVED', ?, ?, ?, 'GUIDED', ?, ?)", USER, courseId, tocTopic, difficulty, OLD, OLD);
        resetProgress();
    }

    private LocalDateTime later() {
        return cutover.at().plusMinutes(LearningEventBackfill.SETTLE_MINUTES + 1);
    }

    private List<String> verbs() {
        return eventLog.liveEvents(USER, courseId).stream().map(LearningEvent::getVerb).sorted().toList();
    }

    @Test
    void 직접_가리킨_원천만_옮기고_막힘_해결을_복원하며_다시_돌려도_더_생기지_않는다() throws Exception {
        Map<LearningEventBackfill.Source, Map<String, Long>> report = backfill.runOnce("t1", later(), USER);

        assertThat(verbs()).containsExactly("RELEASED", "RESOLVED", "SELF_ASSESSED", "STUCK");
        assertThat(report.get(LearningEventBackfill.Source.EXECUTION_RECORD)).containsEntry("unmapped", 1L);
        // 해결 기억은 RESOLUTION에 쓰므로 미대응이 아니다
        assertThat(report.get(LearningEventBackfill.Source.USER_CONTEXT)).containsEntry("written", 1L).doesNotContainKey("unmapped");
        assertThat(eventLog.liveEvents(USER, courseId).stream().filter(e -> e.getVerb().equals("RESOLVED")))
                .singleElement().satisfies(e -> assertThat(e.getPayload()).contains("\"helped\":true"));

        long before = count();
        resetProgress();
        Map<LearningEventBackfill.Source, Map<String, Long>> again = backfill.runOnce("t2", later(), USER);
        assertThat(count()).isEqualTo(before);
        assertThat(again.get(LearningEventBackfill.Source.MATERIAL_LINK)).containsEntry("skipped", 1L);
    }

    @Test
    void 백필_전에_고친_해결_기억도_수정_사슬을_따라_해결로_이어진다() throws Exception {
        long resolver = queryLong("SELECT context_id FROM user_contexts WHERE user_id = ? AND fact_kind = 'RESOLVED'", USER);
        // 긴 수정 사슬(25번 고침)도 끝까지 따라간다
        for (int i = 0; i < 25; i++) {
            exec("UPDATE user_contexts SET status = 'SUPERSEDED' WHERE context_id = ?", resolver);
            resolver = insert("INSERT INTO user_contexts (user_id, content, status, source_type, evidence_type, fact_kind, course_id, "
                    + "topic_id, supersedes_context_id, said_at, created_at) VALUES (?, '합성 해결 고침', 'ACTIVE', 'USER_EDITED', "
                    + "'STATED', 'RESOLVED', ?, ?, ?, ?, ?)", USER, courseId, tocTopic, resolver, OLD, OLD);
        }

        backfill.runOnce("t5", later(), USER);

        assertThat(verbs()).contains("RESOLVED", "STUCK");
    }

    @Test
    void 원천_없는_막힘이_철회된_해결에_대체돼도_미대응으로_센다() throws Exception {
        // 토픽 없는 막힘 → 해결(나중에 철회). 연결은 복원되고 막힘은 기록 대상이지만 원천이 없어 이벤트가 없다
        long difficulty = insert("INSERT INTO user_contexts (user_id, content, status, source_type, evidence_type, fact_kind, "
                + "course_id, said_at, created_at) VALUES (?, '합성 막힘2', 'SUPERSEDED', 'CONSULT_AUTO', 'STATED', 'DIFFICULTY', "
                + "?, ?, ?)", USER, courseId, OLD, OLD);
        insert("INSERT INTO user_contexts (user_id, content, status, source_type, evidence_type, fact_kind, course_id, "
                + "supersedes_context_id, said_at, created_at) VALUES (?, '합성 해결2', 'WITHDRAWN', 'CONSULT_AUTO', 'STATED', "
                + "'RESOLVED', ?, ?, ?, ?)", USER, courseId, difficulty, OLD, OLD);

        Map<LearningEventBackfill.Source, Map<String, Long>> report = backfill.runOnce("t7", later(), USER);

        assertThat(report.get(LearningEventBackfill.Source.USER_CONTEXT)).containsEntry("unmapped", 1L);
        assertThat(queryLong("SELECT COUNT(*) FROM learning_event_origins WHERE user_id = ? AND origin_kind = 'USER_CONTEXT' "
                + "AND origin_id = ? AND current_revision > 0", USER, difficulty)).isZero();
    }

    @Test
    void 원천을_찾지_못한_기억은_미대응으로_센다() throws Exception {
        // 토픽도 원천도 없는 옛 진도 진술 — 직접 가리킨 원천이 없어 이벤트를 만들지 않는다
        insert("INSERT INTO user_contexts (user_id, content, status, source_type, evidence_type, fact_kind, course_id, "
                + "said_at, created_at) VALUES (?, '합성 진도', 'ACTIVE', 'CONSULT_AUTO', 'STATED', 'PROGRESS', ?, ?, ?)",
                USER, courseId, OLD, OLD);

        Map<LearningEventBackfill.Source, Map<String, Long>> report = backfill.runOnce("t6", later(), USER);

        assertThat(report.get(LearningEventBackfill.Source.USER_CONTEXT)).containsEntry("unmapped", 1L);
    }

    @Test
    void 전환_뒤_10분이_안_지났으면_시작하지_않고_다른_서버의_lease는_건드리지_않는다() throws Exception {
        assertThat(backfill.runOnce("t3", cutover.at().plusMinutes(1), USER)).isEmpty();

        for (LearningEventBackfill.Source s : LearningEventBackfill.Source.values()) {
            exec("UPDATE learning_event_backfill SET lease_owner = 'other', lease_until = DATE_ADD(NOW(), INTERVAL 5 MINUTE) "
                    + "WHERE source = ?", s.name());
        }
        assertThat(backfill.runOnce("t4", later(), USER)).isEmpty();
        assertThat(count()).isZero();
    }

    private long count() throws Exception {
        return queryLong("SELECT COUNT(*) FROM learning_events WHERE user_id = ?", USER);
    }

    private void resetProgress() throws Exception {
        exec("DELETE FROM learning_event_backfill");
        for (LearningEventBackfill.Source s : LearningEventBackfill.Source.values()) {
            exec("INSERT INTO learning_event_backfill (source) VALUES (?)", s.name());
        }
    }

    private long insert(String sql, Object... args) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : 0;
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
        exec("DELETE FROM learning_event_backfill");
        exec("DELETE FROM learning_resolution_links WHERE user_id = ?", USER);
        for (String table : List.of("learning_events", "learning_event_origins", "textbook_ref_aliases", "textbook_refs",
                "execution_records", "execution_items", "user_contexts", "topic_progress", "material_links", "course_topics",
                "course_materials", "courses", "users")) {
            exec("DELETE FROM " + table + " WHERE user_id = ?", USER);
        }
    }
}
