package com.jungwoo.project.memo.learning.events;

import com.jungwoo.project.memo.course.textbook.TextbookRefService;
import com.jungwoo.project.memo.learning.events.EventVocabulary.Actor;
import com.jungwoo.project.memo.learning.events.EventVocabulary.Evidence;
import com.jungwoo.project.memo.learning.events.EventVocabulary.OriginKind;
import com.jungwoo.project.memo.learning.events.EventVocabulary.Verb;
import com.jungwoo.project.memo.learning.events.LearningEventValidator.InvalidEventException;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 학습 이벤트 기록기: 판(같은 뜻이면 그대로, 다르면 +1, 출력 0 판), 참조 검증(다른 사용자·연결 없음·범위·목차·살아 있는 막힘),
 * 원본과 함께 롤백, 같은 origin 동시 쓰기. 합성 사용자·합성 자료. 로컬 memo_test DB 필요. CI 제외.
 */
@SpringBootTest
class LearningEventWriterDbTest {

    private static final long USER = 999_000_971L;
    private static final long OTHER = 999_000_972L;
    private static final String HASH = "b".repeat(64);
    private static final String BOOK_KEY = "title:가람교재";

    @Autowired
    private LearningEventWriter writer;
    @Autowired
    private LearningEventLog eventLog;
    @Autowired
    private LearningEventMapper mapper;
    @Autowired
    private TextbookRefService refService;
    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private DataSource dataSource;

    private long courseId;
    private long otherCourseId;
    private long materialId;
    private long sectionId;
    private long otherSectionId;
    private long topicId;
    private long bookRef;
    private long linkId;

    @BeforeEach
    void setUp() throws Exception {
        cleanUp();
        for (long u : List.of(USER, OTHER)) {
            exec("INSERT INTO users (user_id, email, password_hash, nickname, role, status) "
                    + "VALUES (?, ?, '!no-login', '이벤트 테스트', 'USER', 'ACTIVE')", u, "ev-" + u + "@memo-test.invalid");
        }
        courseId = insert("INSERT INTO courses (user_id, title, status) VALUES (?, '가람', 'ACTIVE')", USER);
        long secondCourse = insert("INSERT INTO courses (user_id, title, status) VALUES (?, '나래', 'ACTIVE')", USER);
        otherCourseId = insert("INSERT INTO courses (user_id, title, status) VALUES (?, '남의 과목', 'ACTIVE')", OTHER);
        materialId = material(USER);
        linkId = insert("INSERT INTO material_links (user_id, material_id, course_id, material_type) VALUES (?, ?, ?, 'PROFESSOR_SLIDE')",
                USER, materialId, courseId);
        sectionId = section(USER, materialId, 10, 20);
        long otherMaterial = material(OTHER);
        otherSectionId = section(OTHER, otherMaterial, 1, 5);
        topicId = insert("INSERT INTO course_topics (user_id, course_id, title, order_index, source_type, source_locator, "
                + "source_textbook_key, toc_key_kind, toc_key_hash, toc_key_line, toc_key_state, status) "
                + "VALUES (?, ?, '01 가람 개요', 0, 'SOURCE', '교재 목차', ?, 'WEB', ?, 2, 'SET', 'ACTIVE')",
                USER, courseId, BOOK_KEY, HASH);
        bookRef = tx.execute(s -> refService.refFor(USER, BOOK_KEY));
        assertThat(secondCourse).isPositive();
    }

    private EventOrigin origin(long id) {
        return new EventOrigin(USER, courseId, OriginKind.TEST, id);
    }

    private static EventDraft stuck(SourceRef ref) {
        return EventDraft.of(Actor.ME, Verb.STUCK, ref, new Payloads.Stuck(), Evidence.STATED);
    }

    private LearningEventWriter.Result write(EventOrigin o, EventDraft... drafts) {
        return tx.execute(s -> writer.write(o, List.of(drafts)));
    }

    private <T> T inTx(Supplier<T> body) {
        return tx.execute(s -> body.get());
    }

    @Test
    void 같은_뜻이면_판이_그대로이고_다르면_오르며_출력_0판은_이전_판을_내린다() throws Exception {
        EventOrigin o = origin(1);
        EventDraft d = stuck(SourceRef.section(sectionId)).withClaim(LocalDateTime.of(2026, 10, 6, 9, 0), 5L);

        assertThat(write(o, d)).isEqualTo(LearningEventWriter.Result.WRITTEN);
        assertThat(write(o, d)).isEqualTo(LearningEventWriter.Result.UNCHANGED);
        assertThat(revision(o)).isEqualTo(1);

        assertThat(write(o, d.withClaim(LocalDateTime.of(2026, 10, 6, 9, 1), 5L))).isEqualTo(LearningEventWriter.Result.WRITTEN);
        assertThat(revision(o)).isEqualTo(2);
        assertThat(eventLog.liveEvents(USER, courseId)).hasSize(1);

        assertThat(write(o)).isEqualTo(LearningEventWriter.Result.WRITTEN);
        assertThat(revision(o)).isEqualTo(3);
        assertThat(eventLog.liveEvents(USER, courseId)).isEmpty();
        assertThat(queryLong("SELECT COUNT(*) FROM learning_events WHERE user_id = ?", USER)).isEqualTo(2);
    }

    @Test
    void 처음_쓰는_출력이_0개면_판을_올리지_않는다() throws Exception {
        assertThat(write(origin(2))).isEqualTo(LearningEventWriter.Result.SKIPPED_EMPTY);
        assertThat(queryLong("SELECT COUNT(*) FROM learning_event_origins WHERE user_id = ? AND current_revision > 0", USER))
                .isZero();
        assertThat(write(origin(2), stuck(SourceRef.course()))).isEqualTo(LearningEventWriter.Result.WRITTEN);
        assertThat(revision(origin(2))).isEqualTo(1);
    }

    @Test
    void 없는_origin을_0출력으로_본_두_트랜잭션이_새_origin을_만들어도_교착하지_않는다() throws Exception {
        CountDownLatch bothRead = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            long base = 100 + i * 10L;
            futures.add(pool.submit(() -> tx.execute(s -> {
                writer.write(origin(base), List.of());
                bothRead.countDown();
                try {
                    bothRead.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                return writer.write(origin(base + 1), List.of(stuck(SourceRef.course())));
            })));
        }
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(revision(origin(101))).isEqualTo(1);
        assertThat(revision(origin(111))).isEqualTo(1);
    }

    @Test
    void 철회된_막힘은_해결의_대상이_될_수_없고_조회_쪽_나눔은_숨긴_이벤트_전에_한다() throws Exception {
        write(origin(20), stuck(SourceRef.section(sectionId)));
        long stuckId = queryLong("SELECT event_id FROM learning_events WHERE user_id = ? AND origin_id = 20", USER);
        write(origin(21), EventDraft.of(Actor.ME, Verb.RETRACTED, SourceRef.course(), new Payloads.Retracted(stuckId),
                Evidence.INPUT));
        write(origin(22), stuck(SourceRef.course()));

        assertThatThrownBy(() -> write(origin(23), EventDraft.of(Actor.ME, Verb.RESOLVED, SourceRef.section(sectionId),
                new Payloads.Resolved(stuckId, false), Evidence.STATED))).isInstanceOf(InvalidEventException.class);
        List<LearningEventLog.EventView> first = eventLog.live(USER, courseId, 0, 1);
        assertThat(first).hasSize(1);
        assertThat(first.get(0).originId()).isEqualTo(22L);
        assertThat(first.get(0).payload()).containsEntry("v", 1);
    }

    @Test
    void origin의_과목은_바꿀_수_없다() throws Exception {
        write(origin(3), stuck(SourceRef.course()));
        long second = queryLong("SELECT course_id FROM courses WHERE user_id = ? AND title = '나래'", USER);
        assertThatThrownBy(() -> write(new EventOrigin(USER, second, OriginKind.TEST, 3), stuck(SourceRef.course())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 다른_사용자의_구간_과목_토픽은_거부한다() {
        assertThatThrownBy(() -> write(origin(4), stuck(SourceRef.section(otherSectionId))))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> write(new EventOrigin(USER, otherCourseId, OriginKind.TEST, 4), stuck(SourceRef.course())))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> write(origin(4), stuck(SourceRef.course()).withTopic(Long.MAX_VALUE)))
                .isInstanceOf(InvalidEventException.class);
    }

    @Test
    void 새_참조는_연결이_있어야_하고_기존_참조는_연결을_끊은_뒤에도_다시_쓸_수_있다() throws Exception {
        EventOrigin o = origin(5);
        write(o, stuck(SourceRef.section(sectionId)));
        exec("DELETE FROM material_links WHERE link_id = ?", linkId);

        // 같은 원천, 수행 정보만 바뀜(회고 수정) — 허용
        assertThat(write(o, stuck(SourceRef.section(sectionId)).withConfidence(new java.math.BigDecimal("0.5"))))
                .isEqualTo(LearningEventWriter.Result.WRITTEN);
        // 새 origin이 끊긴 자료를 처음 가리킴 — 거부
        assertThatThrownBy(() -> write(origin(6), stuck(SourceRef.section(sectionId))))
                .isInstanceOf(InvalidEventException.class);
    }

    @Test
    void 구간의_쪽_범위_밖은_거부한다() {
        assertThat(write(origin(7), stuck(SourceRef.section(sectionId, 12, 18)))).isEqualTo(LearningEventWriter.Result.WRITTEN);
        assertThatThrownBy(() -> write(origin(8), stuck(SourceRef.section(sectionId, 9, 18))))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> write(origin(8), stuck(SourceRef.section(sectionId, 15, 12))))
                .isInstanceOf(InvalidEventException.class);
    }

    @Test
    void 목차_항목은_그_과목_토픽의_열쇠여야_한다() {
        assertThat(write(origin(9), stuck(SourceRef.tocEntry(bookRef, HASH, 2)))).isEqualTo(LearningEventWriter.Result.WRITTEN);
        assertThatThrownBy(() -> write(origin(10), stuck(SourceRef.tocEntry(bookRef, HASH, 3))))
                .isInstanceOf(InvalidEventException.class);
        long otherRef = inTx(() -> refService.refFor(OTHER, BOOK_KEY));
        assertThatThrownBy(() -> write(origin(10), stuck(SourceRef.tocEntry(otherRef, HASH, 2))))
                .isInstanceOf(InvalidEventException.class);
    }

    @Test
    void 해결은_살아_있는_막힘만_가리킨다() throws Exception {
        EventOrigin difficulty = origin(11);
        write(difficulty, stuck(SourceRef.section(sectionId)));
        long stuckId = queryLong("SELECT event_id FROM learning_events WHERE user_id = ? AND origin_id = 11", USER);

        EventDraft resolved = EventDraft.of(Actor.ME, Verb.RESOLVED, SourceRef.section(sectionId),
                new Payloads.Resolved(stuckId, false), Evidence.STATED);
        assertThat(write(origin(12), resolved)).isEqualTo(LearningEventWriter.Result.WRITTEN);

        write(difficulty);   // 막힘이 내려감
        assertThatThrownBy(() -> write(origin(13), resolved)).isInstanceOf(InvalidEventException.class);
    }

    @Test
    void 철회한_이벤트는_살아_있는_목록에서_빠진다() throws Exception {
        // RETRACTED 자신도 사실 목록에 나오지 않는다
        write(origin(14), stuck(SourceRef.course()));
        long id = queryLong("SELECT event_id FROM learning_events WHERE user_id = ? AND origin_id = 14", USER);
        write(origin(15), EventDraft.of(Actor.ME, Verb.RETRACTED, SourceRef.course(), new Payloads.Retracted(id), Evidence.INPUT));

        assertThat(eventLog.liveEvents(USER, courseId)).isEmpty();
    }

    @Test
    void 원본_쓰기가_실패하면_이벤트도_남지_않는다() throws Exception {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            writer.write(origin(16), List.of(stuck(SourceRef.course())));
            throw new IllegalStateException("원본 저장 실패");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(queryLong("SELECT COUNT(*) FROM learning_event_origins WHERE user_id = ?", USER)).isZero();
        assertThat(queryLong("SELECT COUNT(*) FROM learning_events WHERE user_id = ?", USER)).isZero();
    }

    @Test
    void 원본의_사용자_과목이_다르면_거부한다() throws Exception {
        // material_links 행은 courseId 것 — 다른 과목으로 우기면 거부
        long second = queryLong("SELECT course_id FROM courses WHERE user_id = ? AND title = '나래'", USER);
        EventDraft released = EventDraft.of(Actor.CLASS, Verb.RELEASED, SourceRef.material(materialId),
                new Payloads.Released("2026-10-06T09:00", "PROFESSOR_SLIDE"), Evidence.LOGGED);
        assertThatThrownBy(() -> write(new EventOrigin(USER, second, OriginKind.MATERIAL_LINK, linkId), released))
                .isInstanceOf(InvalidEventException.class);
        assertThat(write(new EventOrigin(USER, courseId, OriginKind.MATERIAL_LINK, linkId), released))
                .isEqualTo(LearningEventWriter.Result.WRITTEN);
    }

    @Test
    void 같은_origin을_동시에_써도_판이_꼬이지_않는다() throws Exception {
        EventOrigin o = origin(17);
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            long seq = i;
            futures.add(pool.submit(() -> {
                start.await();
                return write(o, stuck(SourceRef.course()).withClaim(null, seq));
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        int rev = revision(o);
        assertThat(rev).isBetween(1, threads);
        assertThat(queryLong("SELECT COUNT(*) FROM learning_events WHERE user_id = ? AND origin_id = 17", USER)).isEqualTo(rev);
        assertThat(eventLog.liveEvents(USER, courseId)).hasSize(1);
        List<Integer> revs = new ArrayList<>();
        for (int r = 1; r <= rev; r++) {
            revs.add((int) queryLong("SELECT COUNT(*) FROM learning_events WHERE user_id = ? AND origin_id = 17 AND origin_revision = ?",
                    USER, r));
        }
        assertThat(revs).containsOnly(1);
        assertThat(Collections.max(revs)).isEqualTo(1);
    }

    // ===== 도움 =====

    private int revision(EventOrigin o) {
        LearningEventMapper.OriginRow row = mapper.findOrigin(o.userId(), o.kind().name(), o.id());
        return row == null ? 0 : row.currentRevision();
    }

    private long material(long user) throws Exception {
        return insert("INSERT INTO course_materials (user_id, original_filename, stored_filename, storage_path, size_bytes, "
                + "extraction_status, status) VALUES (?, '합성.pdf', 'x.pdf', 'none/x.pdf', 1, 'SUCCESS', 'ACTIVE')", user);
    }

    private long section(long user, long material, int from, int to) throws Exception {
        return insert("INSERT INTO material_sections (user_id, material_id, file_hash, analysis_version, chunk_index, unit_type, "
                + "unit_start, unit_end, printed_page_start, printed_page_end, display_title, roles_json, dedupe_key) "
                + "VALUES (?, ?, ?, 1, 0, 'PAGE', 1, 3, ?, ?, '합성 구간', '[]', ?)", user, material, "c".repeat(64), from, to,
                "k" + material);
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
        for (long u : List.of(USER, OTHER)) {
            for (String table : List.of("learning_events", "learning_event_origins", "textbook_ref_aliases", "textbook_refs",
                    "material_sections", "material_links", "course_topics", "course_materials", "courses", "users")) {
                exec("DELETE FROM " + table + " WHERE user_id = ?", u);
            }
        }
    }
}
