package com.jungwoo.project.memo.course.textbook;

import com.jungwoo.project.memo.learning.events.LearningEventMetaMapper;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 교재 식별자: 같은 책의 ISBN 키·제목 키는 같은 ref, 운영 중 충돌은 합치지 않고 센다, 시드는 이벤트가 가리키지 않은 ref만 합친다.
 * 합성 사용자. 로컬 memo_test DB 필요. CI 제외.
 */
@SpringBootTest
class TextbookRefDbTest {

    private static final long USER = 999_000_973L;
    private static final String ISBN_A = "9791156645672";
    private static final String ISBN_B = "9791173400667";

    @Autowired
    private TextbookRefService refService;
    @Autowired
    private TextbookRefSeeder seeder;
    @Autowired
    private LearningEventMetaMapper metaMapper;
    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        cleanUp();
        exec("INSERT INTO users (user_id, email, password_hash, nickname, role, status) "
                + "VALUES (?, ?, '!no-login', '교재 식별 테스트', 'USER', 'ACTIVE')", USER, "ref-" + USER + "@memo-test.invalid");
    }

    private Long ensure(String title, String isbn) {
        return tx.execute(s -> refService.ensureBook(USER, title, isbn, null));
    }

    private Long ref(String key) {
        return tx.execute(s -> refService.refFor(USER, key));
    }

    @Test
    void 같은_책의_ISBN_키와_제목_키는_같은_식별자다() {
        Long r = ensure("가람 교재", ISBN_A);

        assertThat(ref(BookKey.of("가람 교재", ISBN_A, null))).isEqualTo(r);
        assertThat(ref(BookKey.of("가람 교재", null, null))).isEqualTo(r);
        assertThat(ensure("가람 교재", ISBN_A)).isEqualTo(r);
        assertThat(ref(BookKey.of("가람 교재", null, null))).isEqualTo(ref(BookKey.of("가람 교재", null, null)));
    }

    @Test
    void 운영_중_다른_식별자에_이미_있는_키는_합치지_않고_센다() throws Exception {
        Long byTitle = ref(BookKey.of("나래 교재", null, null));
        Long byIsbn = ref(BookKey.of("나래 교재", ISBN_B, null));
        long before = counter();

        Long r = ensure("나래 교재", ISBN_B);

        assertThat(byTitle).isNotEqualTo(byIsbn);
        assertThat(r).isEqualTo(byIsbn);
        assertThat(ref(BookKey.of("나래 교재", null, null))).isEqualTo(byTitle);
        assertThat(counter()).isEqualTo(before + 1);
    }

    @Test
    void 시드는_같은_책의_두_식별자를_합치되_이벤트가_가리킨_식별자는_두다() throws Exception {
        long c1 = insert("INSERT INTO courses (user_id, title, textbook_title, textbook_isbn, status) VALUES (?, '다솜', '다솜 교재', ?, 'ACTIVE')",
                USER, ISBN_A);
        Long byTitle = ref(BookKey.of("다솜 교재", null, null));
        Long byIsbn = ref(BookKey.of("다솜 교재", ISBN_A, null));
        assertThat(byTitle).isNotEqualTo(byIsbn);

        long c2 = insert("INSERT INTO courses (user_id, title, textbook_title, textbook_isbn, status) VALUES (?, '라온', '라온 교재', ?, 'ACTIVE')",
                USER, ISBN_B);
        Long usedTitle = ref(BookKey.of("라온 교재", null, null));
        Long usedIsbn = ref(BookKey.of("라온 교재", ISBN_B, null));
        exec("INSERT INTO learning_events (user_id, course_id, origin_kind, origin_id, origin_revision, output_no, actor, verb, "
                + "object_kind, object_ref, payload, evidence) VALUES (?, ?, 'TEST', 1, 1, 1, 'ME', 'STUCK', 'TOC_ENTRY', ?, '{\"v\":1}', 'STATED')",
                USER, c2, "t:" + usedTitle + ":" + "a".repeat(64) + ":1");

        String seeded = metaMapper.find(LearningEventMetaMapper.TEXTBOOK_REFS_SEEDED);
        exec("DELETE FROM learning_event_meta WHERE meta_key = ?", LearningEventMetaMapper.TEXTBOOK_REFS_SEEDED);
        try {
            seeder.runOnce();
        } finally {
            if (seeded != null) {
                exec("REPLACE INTO learning_event_meta (meta_key, meta_value) VALUES (?, ?)",
                        LearningEventMetaMapper.TEXTBOOK_REFS_SEEDED, seeded);
            }
        }

        assertThat(ref(BookKey.of("다솜 교재", null, null))).isEqualTo(ref(BookKey.of("다솜 교재", ISBN_A, null)));
        // 이벤트가 가리킨 제목 쪽 식별자는 그대로 남는다
        assertThat(ref(BookKey.of("라온 교재", null, null))).isEqualTo(usedTitle);
        assertThat(usedIsbn).isNotNull();
        assertThat(c1).isPositive();
    }

    @Test
    void 같은_책에_판을_채워_제목_키가_바뀌어도_이전_식별자를_잇는다() {
        Long before = ensure("마루 교재", null);
        Long after = tx.execute(s -> refService.continueBook(USER, TextbookRefService.keysOf("마루 교재", null, null),
                "마루 교재", null, "2판"));

        assertThat(after).isEqualTo(before);
        assertThat(ref(BookKey.of("마루 교재", null, "2판"))).isEqualTo(before);
    }

    @Test
    void 다른_트랜잭션이_먼저_만든_별칭도_찾는다() throws Exception {
        String key = BookKey.of("바다 교재", null, null);
        java.util.concurrent.CountDownLatch snapshot = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch committed = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.Future<Long> late = pool.submit(() -> tx.execute(s -> {
            // 이 트랜잭션의 스냅샷을 먼저 만든다(아직 별칭 없음)
            assertThat(refService.find(USER, key)).isNull();
            snapshot.countDown();
            try {
                committed.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return refService.refFor(USER, key);
        }));
        snapshot.await(10, java.util.concurrent.TimeUnit.SECONDS);
        Long early = ref(key);
        committed.countDown();

        assertThat(late.get(30, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(early);
        pool.shutdown();
    }

    @Test
    void 다른_트랜잭션이_먼저_만든_책도_ensureBook이_끝난다() throws Exception {
        java.util.concurrent.CountDownLatch snapshot = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch committed = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.Future<Long> late = pool.submit(() -> tx.execute(s -> {
            assertThat(refService.find(USER, BookKey.of("사랑 교재", ISBN_B, null))).isNull();
            snapshot.countDown();
            try {
                committed.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return refService.ensureBook(USER, "사랑 교재", ISBN_B, null);
        }));
        snapshot.await(10, java.util.concurrent.TimeUnit.SECONDS);
        Long early = ensure("사랑 교재", ISBN_B);
        committed.countDown();

        assertThat(late.get(30, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(early);
        pool.shutdown();
    }

    private long counter() {
        String v = metaMapper.find(LearningEventMetaMapper.TEXTBOOK_REF_CONFLICTS);
        return v == null ? 0 : Long.parseLong(v);
    }

    private long insert(String sql, Object... args) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS)) {
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
        for (String table : List.of("learning_events", "textbook_ref_aliases", "textbook_refs", "courses", "users")) {
            exec("DELETE FROM " + table + " WHERE user_id = ?", USER);
        }
    }
}
