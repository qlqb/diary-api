package com.jungwoo.project.memo.learning.tidy;

import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.course.CourseMapper;
import com.jungwoo.project.memo.course.textbook.BookKey;
import com.jungwoo.project.memo.course.textbook.TextbookService;
import com.jungwoo.project.memo.course.textbook.web.BookPageParser;
import com.jungwoo.project.memo.course.textbook.web.TextbookWebMapper;
import com.jungwoo.project.memo.course.textbook.web.TextbookWebRevision;
import com.jungwoo.project.memo.course.textbook.web.WebEvidenceStore;
import com.jungwoo.project.memo.course.textbook.web.WebTocRefresher;
import com.jungwoo.project.memo.course.textbook.web.WebTocStructurer;
import com.jungwoo.project.memo.learning.CourseTopicMapper;
import com.jungwoo.project.memo.learning.domain.CourseTopic;
import com.jungwoo.project.memo.learning.structure.TopicChangeOp;
import com.jungwoo.project.memo.learning.tidy.domain.ProjectTidyJob;
import com.jungwoo.project.memo.learning.tidy.dto.ProjectTidyRequests;
import com.jungwoo.project.memo.learning.tidy.dto.ProjectTidyResponse;
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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 옛 규칙으로 14줄만 읽었던 웹 목차 → 저장된 원문으로 다시 구조화 → 기존 목차 토픽에 열쇠 → 정리에서 빠진 하위항목만 ADD → 적용.
 * 기존 토픽 ID·고친 제목·진도·막힘 기억·자료 연결은 그대로이고, 다시 돌려도 중복이 생기지 않는다. 목차는 합성이다.
 * 로컬 memo_test DB 필요. CI 제외.
 */
@SpringBootTest
class TocSubItemsFlowDbTest {

    private static final long USER = 999_000_961L;
    private static final String URL = "https://www.yes24.com/product/goods/990000961";
    private static final String RAW = String.join("\n",
            "Chapter 01 첫째 장",
            "01 가람 개요",
            "02 나래 구조",
            "실습 1-1 가람 프로그램",
            "요약",
            "연습문제",
            "Chapter 02 둘째 장",
            "01 다솜 처리",
            "요약/연습문제",
            "부록 A. 설치하기");
    /** 옛 규칙의 결과: Chapter 줄만 읽었다. unit = 원문 줄 번호. */
    private static final String OLD_TOC_JSON = "{\"entries\":[{\"level\":1,\"number\":\"Chapter 01\",\"title\":\"첫째 장\","
            + "\"page\":null,\"unit\":1},{\"level\":1,\"number\":\"Chapter 02\",\"title\":\"둘째 장\",\"page\":null,\"unit\":7}],"
            + "\"method\":\"RULE\",\"lines\":10,\"readLines\":2}";

    @MockitoBean
    private ProjectTidyAnalyzer analyzer;
    @MockitoBean
    private com.jungwoo.project.memo.course.textbook.web.SafePageFetcher fetcher;
    @MockitoBean
    private com.jungwoo.project.memo.course.textbook.web.TextbookModelAssist modelAssist;
    @Autowired
    private com.jungwoo.project.memo.course.textbook.web.TextbookLookupService lookupService;
    @Autowired
    private com.jungwoo.project.memo.course.textbook.web.TextbookLookupWorker lookupWorker;
    @Autowired
    private com.jungwoo.project.memo.course.textbook.web.TextbookLookupMapper lookupMapper;

    @Autowired
    private TocKeyBackfill backfill;
    @Autowired
    private WebTocRefresher refresher;
    @Autowired
    private TextbookService textbookService;
    @Autowired
    private TextbookWebMapper webMapper;
    @Autowired
    private ProjectTidyService tidyService;
    @Autowired
    private ProjectTidyWorker worker;
    @Autowired
    private ProjectTidyMapper tidyMapper;
    @Autowired
    private CourseTopicMapper topicMapper;
    @Autowired
    private CourseMapper courseMapper;
    @Autowired
    private DataSource dataSource;

    private long courseId;
    private long oldRevision;
    private long ch1;
    private long ch2;
    private long materialId;

    @BeforeEach
    void setUp() throws Exception {
        cleanUp();
        exec("INSERT INTO users (user_id, email, password_hash, nickname, role, status) "
                + "VALUES (?, ?, '!no-login', '목차 테스트', 'USER', 'ACTIVE')", USER, "toc-" + USER + "@memo-test.invalid");
        courseId = insert("INSERT INTO courses (user_id, title, status) VALUES (?, '네트워크', 'ACTIVE')", USER);
        exec("INSERT INTO textbook_web_pages (cache_scope_key, url_hash, url, site, last_fetch_status, last_fetched_at) "
                + "VALUES ('SHARED', SHA2(?, 256), ?, 'yes24', 'OK', NOW())", URL, URL);
        long pageId = queryLong("SELECT page_id FROM textbook_web_pages WHERE cache_scope_key = 'SHARED' AND url = ?", URL);
        // 옛 리비전: 마이그레이션처럼 toc_raw_hash는 SQL SHA2로 채운다(서버 해시와 같아야 한다).
        oldRevision = insert("INSERT INTO textbook_web_revisions (page_id, content_hash, parser_version, fetched_at, http_status, "
                        + "isbn13, title, toc_raw, toc_json, toc_entry_count, toc_coverage, toc_version, toc_raw_hash) "
                        + "VALUES (?, ?, ?, NOW(), 200, '9790000009619', '합성 네트워크 교재', ?, ?, 2, 'PARTIAL', 1, SHA2(?, 256))",
                pageId, "c".repeat(64), BookPageParser.VERSION, RAW, OLD_TOC_JSON, RAW);
        exec("UPDATE textbook_web_pages SET latest_revision_id = ? WHERE page_id = ?", oldRevision, pageId);
        exec("UPDATE courses SET textbook_title = '합성 네트워크 교재', textbook_isbn = '9790000009619', textbook_version = 1, "
                + "textbook_info_source = 'WEB', textbook_web_revision_id = ? WHERE course_id = ?", oldRevision, courseId);
        String bookKey = BookKey.of(courseMapper.findByIdAndUserId(courseId, USER));

        // 옛 골격으로 만든 장 두 개: 하나는 사용자가 제목을 고쳤고(순번 있음), 하나는 순번 칸이 생기기 전 토픽(순번 없음).
        ch1 = insert("INSERT INTO course_topics (user_id, course_id, title, order_index, source_type, source_locator, "
                + "source_web_revision_id, source_textbook_key, source_toc_seq, status) "
                + "VALUES (?, ?, '1장 — 내가 고친 이름', 0, 'SOURCE', '교재 목차', ?, ?, 1, 'ACTIVE')", USER, courseId, oldRevision, bookKey);
        ch2 = insert("INSERT INTO course_topics (user_id, course_id, title, order_index, source_type, source_locator, "
                + "source_web_revision_id, source_textbook_key, source_toc_seq, status) "
                + "VALUES (?, ?, 'Chapter 02 둘째 장', 1, 'SOURCE', '교재 목차', ?, ?, NULL, 'ACTIVE')", USER, courseId, oldRevision, bookKey);
        exec("UPDATE courses SET topic_tree_version = 3 WHERE course_id = ?", courseId);

        // 기록: 진도·막힘 기억·자료 연결
        exec("INSERT INTO topic_progress (user_id, topic_id, status) VALUES (?, ?, 'IN_PROGRESS')", USER, ch1);
        exec("INSERT INTO user_contexts (user_id, content, status, source_type, evidence_type, fact_kind, course_id, topic_id) "
                + "VALUES (?, '1장 소켓 부분이 막힘', 'ACTIVE', 'CONSULT_AUTO', 'STATED', 'DIFFICULTY', ?, ?)", USER, courseId, ch1);
        materialId = insert("INSERT INTO course_materials (user_id, original_filename, stored_filename, storage_path, size_bytes, "
                + "extraction_status, status) VALUES (?, '강의노트.pdf', 'x.pdf', 'none/x.pdf', 1, 'SUCCESS', 'ACTIVE')", USER);
        exec("INSERT INTO topic_material_links (user_id, course_id, topic_id, material_id, section_id, role, origin, status) "
                + "VALUES (?, ?, ?, ?, 0, 'CONCEPT', 'USER', 'ACTIVE')", USER, courseId, ch1, materialId);
    }

    @Test
    void 옛_목차를_다시_구조화하고_기존_장_아래_빠진_하위항목만_추가해도_기록이_그대로다() throws Exception {
        // 1. 열쇠 일회 변환: 고친 제목은 순번으로, 순번 없는 토픽은 만든 리비전의 같은 제목으로.
        assertThat(backfill.runOnce()).containsExactly(2, 0);
        assertThat(backfill.runOnce()).containsExactly(0, 0);
        CourseTopic t1 = topic(ch1);
        assertThat(t1.getTocKeyState()).isEqualTo("SET");
        assertThat(t1.getTocKeyLine()).isEqualTo(1);
        assertThat(t1.getTocKeyHash()).isEqualTo(WebEvidenceStore.rawHash(RAW)); // SHA2 = 서버 해시
        assertThat(topic(ch2).getTocKeyLine()).isEqualTo(7);

        // 2. 저장된 원문으로 다시 구조화(페이지를 다시 받지 않는다). 두 번째는 할 일이 없다.
        assertThat(refresher.refreshOutdated()).isEqualTo(1);
        assertThat(refresher.refreshOutdated()).isZero();
        TextbookService.TocSnapshot toc = textbookService.tocOf(USER, courseId);
        assertThat(toc.basis().revisionId()).as("교재 칸은 옛 리비전을 가리켜도 새 구조를 본다").isNotEqualTo(oldRevision);
        assertThat(toc.entries()).hasSize(10);
        assertThat(toc.unread()).isZero();
        TextbookWebRevision fresh = webMapper.findRevision(toc.basis().revisionId(), USER);
        assertThat(fresh.getTocVersion()).isEqualTo(WebTocStructurer.TOC_VERSION);
        assertThat(fresh.getTocRawHash()).isEqualTo(t1.getTocKeyHash());

        // 3. 정리: 목차만 비교(자료 구간 없음) — 서버가 모두 판단했으므로 모델을 부르지 않는다.
        ProjectTidyResponse view = tidy();
        verify(analyzer, never()).analyzeTocOnly(anyLong(), any(), any());
        Map<Integer, TopicChangeOp> byLine = ops(view).stream()
                .collect(Collectors.toMap(TopicChangeOp::tocLine, op -> op));
        assertThat(byLine.keySet()).containsExactlyInAnyOrder(2, 3, 4, 5, 6, 8, 9, 10);
        for (int line : List.of(2, 3, 4, 5, 6)) {
            assertThat(byLine.get(line).parentTopicId()).as("원문 %d행의 부모", line).isEqualTo(ch1);
        }
        assertThat(byLine.get(8).parentTopicId()).isEqualTo(ch2);
        assertThat(byLine.get(9).parentTopicId()).isEqualTo(ch2);
        assertThat(byLine.get(10).parentTopicId()).isNull(); // 부록은 장과 같은 깊이
        assertThat(byLine.get(4).title()).isEqualTo("실습 1-1 가람 프로그램");

        // 4. 적용: 기존 토픽·기록은 그대로, 새 토픽은 올바른 부모 아래 + 열쇠.
        apply(view);
        assertThat(topic(ch1).getTitle()).isEqualTo("1장 — 내가 고친 이름");
        assertThat(topic(ch1).getParentTopicId()).isNull();
        assertThat(queryLong("SELECT COUNT(*) FROM topic_progress WHERE topic_id = ? AND status = 'IN_PROGRESS'", ch1)).isEqualTo(1);
        assertThat(queryLong("SELECT COUNT(*) FROM user_contexts WHERE topic_id = ? AND fact_kind = 'DIFFICULTY' "
                + "AND status = 'ACTIVE'", ch1)).isEqualTo(1);
        assertThat(queryLong("SELECT COUNT(*) FROM topic_material_links WHERE topic_id = ? AND material_id = ? "
                + "AND status = 'ACTIVE'", ch1, materialId)).isEqualTo(1);
        List<CourseTopic> all = topicMapper.findActiveByCourseIdAndUserId(courseId, USER);
        assertThat(all).hasSize(2 + 8);
        CourseTopic lab = all.stream().filter(t -> Integer.valueOf(4).equals(t.getTocKeyLine())).findFirst().orElseThrow();
        assertThat(lab.getParentTopicId()).isEqualTo(ch1);
        assertThat(lab.getTocKeyHash()).isEqualTo(t1.getTocKeyHash());
        assertThat(lab.getTocKeyState()).isEqualTo("SET");
        assertThat(lab.getSourceTocSeq()).as("표시 순번 = 새 목차 안 위치").isEqualTo(4);
        List<String> ch1Children = all.stream().filter(t -> Long.valueOf(ch1).equals(t.getParentTopicId()))
                .sorted(java.util.Comparator.comparing(CourseTopic::getOrderIndex)).map(CourseTopic::getTitle).toList();
        assertThat(ch1Children).containsExactly("01 가람 개요", "02 나래 구조", "실습 1-1 가람 프로그램", "요약", "연습문제");

        // 5. 다시 정리해도 목차 ADD가 없다(중복 없음).
        ProjectTidyResponse again = tidy();
        assertThat(ops(again)).noneMatch(TopicChangeOp::isFromToc);
    }

    @Test
    void 열쇠_판이_바뀌기_전의_목차_정리안은_적용하지_않는다() throws Exception {
        backfill.runOnce();
        refresher.refreshOutdated();
        ProjectTidyResponse view = tidy();
        exec("UPDATE project_tidy_proposals SET toc_key_version = NULL WHERE proposal_id = ?", view.getProposalId());

        assertThatThrownBy(() -> apply(view))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TEXTBOOK_TOC_CHANGED));
        assertThat(topicMapper.findActiveByCourseIdAndUserId(courseId, USER)).hasSize(2);
    }

    @Test
    void 조회_작업이_캐시의_옛_리비전을_만나면_페이지를_다시_받지_않고_저장된_원문으로_읽는다() throws Exception {
        lookupService.link(USER, courseId, URL);
        var open = lookupMapper.findLatestByCourse(courseId, USER);
        LocalDateTime now = LocalDateTime.now().plusSeconds(1);
        assertThat(lookupMapper.claim(open.getLookupId(), "test", now, now.plusMinutes(3))).isEqualTo(1);

        lookupWorker.run(lookupMapper.findById(open.getLookupId()));

        verify(fetcher, never()).fetch(org.mockito.ArgumentMatchers.anyString());
        verify(fetcher, never()).fetch(org.mockito.ArgumentMatchers.anyString(), any());
        long pageId = queryLong("SELECT page_id FROM textbook_web_pages WHERE cache_scope_key = 'SHARED' AND url = ?", URL);
        TextbookWebRevision fresh = webMapper.findRestructured(pageId, WebEvidenceStore.rawHash(RAW),
                WebTocStructurer.TOC_VERSION, USER);
        assertThat(fresh).isNotNull();
        assertThat(fresh.getTocEntryCount()).isEqualTo(10);
        assertThat(queryLong("SELECT latest_revision_id FROM textbook_web_pages WHERE page_id = ?", pageId))
                .isEqualTo(fresh.getRevisionId());
        // 모델 보완은 못 읽은 줄이 없으면 부르지 않는다.
        verify(modelAssist, never()).pickTocLines(anyLong(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    // ===== 도움 =====

    private ProjectTidyResponse tidy() {
        tidyService.request(USER, courseId, false);
        ProjectTidyJob queued = tidyMapper.findLatestJobByCourse(courseId, USER);
        LocalDateTime now = LocalDateTime.now();
        assertThat(tidyMapper.claimJob(queued.getJobId(), "test", now, now.plusMinutes(5))).isEqualTo(1);
        worker.run(tidyMapper.findJobById(queued.getJobId()));
        assertThat(tidyMapper.findJobById(queued.getJobId()).getStatus().name()).isEqualTo("DONE");
        return tidyService.view(USER, courseId);
    }

    private List<TopicChangeOp> ops(ProjectTidyResponse view) {
        return view.getProposalId() == null ? List.of()
                : tidyService.readOps(tidyMapper.findProposalById(view.getProposalId(), USER).getOpsJson());
    }

    private void apply(ProjectTidyResponse view) {
        ProjectTidyRequests.Apply request = new ProjectTidyRequests.Apply();
        request.setRevision(view.getRevision());
        request.setEditRevision(view.getEditRevision() == null ? 0L : view.getEditRevision());
        request.setBaseTreeVersion(view.getBaseTreeVersion());
        request.setSelectedChangeIds(view.getChanges().stream().map(ProjectTidyResponse.Change::getChangeId).toList());
        tidyService.apply(USER, view.getProposalId(), request);
    }

    private CourseTopic topic(long id) {
        return topicMapper.findActiveByCourseIdAndUserId(courseId, USER).stream()
                .filter(t -> t.getTopicId() == id).findFirst().orElseThrow();
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
        exec("DELETE FROM project_tidy_edits WHERE proposal_id IN (SELECT proposal_id FROM project_tidy_proposals WHERE user_id = ?)", USER);
        for (String table : List.of("project_tidy_proposal_materials", "project_tidy_proposals", "project_tidy_jobs",
                "textbook_lookups", "user_contexts", "topic_progress", "topic_material_links", "course_topics",
                "course_materials", "ai_usage_logs", "courses", "users")) {
            exec("DELETE FROM " + table + " WHERE user_id = ?", USER);
        }
        exec("DELETE r FROM textbook_web_revisions r JOIN textbook_web_pages p ON p.page_id = r.page_id "
                + "WHERE p.cache_scope_key = 'SHARED' AND p.url = ?", URL);
        exec("DELETE FROM textbook_web_pages WHERE cache_scope_key = 'SHARED' AND url = ?", URL);
    }
}
