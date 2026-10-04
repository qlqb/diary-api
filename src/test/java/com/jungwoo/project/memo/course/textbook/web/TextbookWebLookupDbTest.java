package com.jungwoo.project.memo.course.textbook.web;

import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.course.CourseMapper;
import com.jungwoo.project.memo.course.CourseService;
import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.course.dto.CourseUpdateRequest;
import com.jungwoo.project.memo.course.textbook.TextbookReview;
import com.jungwoo.project.memo.course.textbook.TextbookService;
import com.jungwoo.project.memo.learning.CourseTopicMapper;
import com.jungwoo.project.memo.learning.domain.CourseTopic;
import com.jungwoo.project.memo.learning.structure.TopicChangeOp;
import com.jungwoo.project.memo.learning.tidy.ProjectTidyAnalyzer;
import com.jungwoo.project.memo.learning.tidy.ProjectTidyMapper;
import com.jungwoo.project.memo.learning.tidy.ProjectTidyService;
import com.jungwoo.project.memo.learning.tidy.ProjectTidyWorker;
import com.jungwoo.project.memo.learning.tidy.domain.ProjectTidyJob;
import com.jungwoo.project.memo.learning.tidy.domain.ProjectTidyProposal;
import com.jungwoo.project.memo.learning.tidy.dto.ProjectTidyRequests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 강의계획서의 교재 이름 → 웹에서 책·판·목차 → 교재 확인 → 학습 구조 변경안 → 적용. 외부 검색(web_search)과 페이지 수집만
 * 가짜이고(실제 서점 페이지를 축약한 HTML을 돌려준다) 나머지는 실제 DB에서 돈다.
 *
 * <p>고정하는 것:
 * <ul>
 *   <li>칸이 흩어진 강의계획서 표에서 교재 단서를 읽어 조회가 등록되고, 같은 제목의 두 판은 고르게 한다(가장 비슷한 것을 고르지 않음).</li>
 *   <li>판 선택은 교재 판을 대조한다(409). 그 조회의 후보가 아니면 409. 고르면 교재가 WEB으로 바뀌고 그 판의 목차를 쓴다.</li>
 *   <li>늦게 끝난 옛 교재 검색은 사용자가 고친 교재를 덮지 않는다(SUPERSEDED).</li>
 *   <li>사용자 링크는 사용자마다 따로 저장되고, 다른 사용자는 그 리비전을 읽지 못한다.</li>
 *   <li>목차를 확보하면 빈 트리에 정리안이 자동으로 만들어지고, 적용하면 항목에 웹 출처가 남고 비어 있던 교재 칸이 그 책으로 기록된다.</li>
 *   <li>정리안을 만든 뒤 교재가 바뀌면 목차에서 온 변경은 적용되지 않는다(409).</li>
 *   <li>트리가 있고 수업 자료가 없으면 목차만 비교해 빠진 장만 제안한다 — 없는 목차 번호·구조 변경은 서버가 버린다.</li>
 * </ul>
 * 로컬 memo_test DB 필요. CI 제외.
 */
@SpringBootTest
class TextbookWebLookupDbTest {

    private static final long USER = 999_000_951L;
    private static final long OTHER_USER = 999_000_952L;
    private static final long SYLLABUS = 999_495_101L;
    private static final String HASH_SYLLABUS = "5".repeat(64);
    private static final String NEW_URL = "https://www.yes24.com/product/goods/175899340";
    private static final String OLD_URL = "https://www.yes24.com/product/goods/89873002";

    /** 실제 강의계획서 PDF 1쪽에서 뽑힌 텍스트의 모양(개인 정보 줄은 합성 값). */
    private static final String SYLLABUS_TEXT = """
            교과목명 영어회화교양
            담당 교수   + 전 화  : 02-0000-0000
              + E-MAIL : prof@example.invalid
            과목 개요
            도서명 저자 출판사 비고
             Michael
             주교재  NEW English Conversation Arts 1  형설출판사
            Putlack, 이현호
            수업시
            사용도구
            성적평가 비율
            """;

    @MockitoBean
    private BookWebSearchClient searchClient;
    @MockitoBean
    private SafePageFetcher fetcher;
    @MockitoBean
    private ProjectTidyAnalyzer analyzer;
    @MockitoBean
    private TextbookModelAssist modelAssist;

    @Autowired
    private TextbookService textbookService;
    @Autowired
    private TextbookLookupService lookupService;
    @Autowired
    private TextbookLookupWorker worker;
    @Autowired
    private TextbookLookupMapper lookupMapper;
    @Autowired
    private TextbookWebMapper webMapper;
    @Autowired
    private WebEvidenceStore store;
    @Autowired
    private TextbookAutoTidy autoTidy;
    @Autowired
    private CourseService courseService;
    @Autowired
    private CourseMapper courseMapper;
    @Autowired
    private ProjectTidyService tidyService;
    @Autowired
    private ProjectTidyWorker tidyWorker;
    @Autowired
    private ProjectTidyMapper tidyMapper;
    @Autowired
    private CourseTopicMapper topicMapper;
    @Autowired
    private com.jungwoo.project.memo.plan.PlanMaterialContextService planContext;
    @Autowired
    private com.jungwoo.project.memo.learning.LearningMapService learningMapService;
    @Autowired
    private DataSource dataSource;

    private long courseId;

    @BeforeEach
    void setUp() throws Exception {
        cleanUp();
        for (long u : new long[]{USER, OTHER_USER}) {
            exec("INSERT INTO users (user_id, email, password_hash, nickname, role, status) "
                    + "VALUES (?, ?, '!no-login', '교재 테스트', 'USER', 'ACTIVE')", u, "tb-" + u + "@memo-test.invalid");
        }
        courseId = insertCourse(USER, "영어회화");
        when(searchClient.isConfigured()).thenReturn(true);
        when(fetcher.fetch(eq(NEW_URL), any())).thenReturn(page(NEW_URL, "yes24-175899340.html"));
        when(fetcher.fetch(eq(OLD_URL), any())).thenReturn(page(OLD_URL, "yes24-89873002.html"));
    }

    // ===== 강의계획서 → 판 확인 =====

    @Test
    void 강의계획서_교재_단서로_찾은_두_판은_고르게_하고_교재를_조용히_정하지_않는다() throws Exception {
        syllabus();
        searchReturns(NEW_URL, OLD_URL);

        TextbookReview before = textbookService.review(USER, courseId);
        assertThat(before.syllabusClues()).singleElement().satisfies(c -> {
            assertThat(c.title()).isEqualTo("NEW English Conversation Arts 1");
            assertThat(c.author()).isEqualTo("Michael Putlack, 이현호");
            assertThat(c.role()).isEqualTo("MAIN");
        });
        assertThat(before.lookup().status()).isEqualTo("QUEUED");
        assertThat(before.current().title()).as("강의계획서의 교재는 후보일 뿐").isNull();

        runLookup();

        TextbookReview after = textbookService.review(USER, courseId);
        assertThat(after.lookup().status()).isEqualTo("NEEDS_CHOICE");
        assertThat(after.lookup().editions()).extracting(LookupResult.Edition::isbn13)
                .containsExactly("9788947288132", "9788947281980");
        assertThat(after.lookup().editions().get(0).sameTocAs()).containsExactly("9788947281980");
        assertThat(after.lookup().query().title()).isEqualTo("NEW English Conversation Arts 1");
        assertThat(after.current().title()).isNull();
        assertThat(after.toc().status()).as("판을 고르기 전에는 목차를 쓰지 않는다").isEqualTo("NOT_FOUND");
        assertThat(after.nextAction()).contains("판").contains("계획과 학습은 그대로");
    }

    @Test
    void 판_선택은_교재_판을_대조하고_그_조회의_후보만_받는다() throws Exception {
        syllabus();
        searchReturns(NEW_URL, OLD_URL);
        textbookService.review(USER, courseId);
        runLookup();
        TextbookReview review = textbookService.review(USER, courseId);
        Long lookupId = review.lookup().lookupId();
        Long newest = review.lookup().editions().get(0).bestRevisionId();

        assertThatThrownBy(() -> lookupService.choose(USER, courseId, lookupId, newest, 5))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TEXTBOOK_VERSION_CHANGED));
        assertThatThrownBy(() -> lookupService.choose(USER, courseId, lookupId, 1L, 0))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TEXTBOOK_CANDIDATE_NOT_ALLOWED));
        // 다른 사용자는 남의 조회로 고를 수 없다.
        long otherCourse = insertCourse(OTHER_USER, "남의 과목");
        assertThatThrownBy(() -> lookupService.choose(OTHER_USER, otherCourse, lookupId, newest, 0))
                .isInstanceOf(RuntimeException.class);

        lookupService.choose(USER, courseId, lookupId, newest, 0);

        TextbookReview chosen = textbookService.review(USER, courseId);
        assertThat(chosen.current().source()).isEqualTo("WEB");
        assertThat(chosen.current().isbn()).isEqualTo("9788947288132");
        assertThat(chosen.current().web().publishedDate()).isEqualTo("2026-01-30");
        assertThat(chosen.lookup().status()).isEqualTo("FOUND");
        assertThat(chosen.toc().status()).isEqualTo("FOUND");
        assertThat(chosen.toc().kind()).isEqualTo("WEB");
        assertThat(chosen.toc().coverage()).isEqualTo("PAGE_FULL");
        assertThat(chosen.toc().entries()).hasSize(12);
        assertThat(chosen.toc().entries().get(9).title()).isEqualTo("What’s your name?");
        assertThat(chosen.toc().entries().get(9).number()).isEqualTo("Unit 10");
        // 고른 판은 다시 검색하지 않는다.
        verify(searchClient, org.mockito.Mockito.times(1)).search(any());
    }

    @Test
    void 옛_파서로_읽은_캐시는_다시_받는다() throws Exception {
        BookPageParser.Parsed parsed = BookPageParser.parse(NEW_URL, fixture("yes24-175899340.html"));
        WebEvidenceStore.Target target = store.target(NEW_URL, USER);
        TextbookWebRevision saved = store.save(target, parsed, WebTocStructurer.byRules(parsed.tocRaw(), false), 200,
                LocalDateTime.now(), USER);
        LocalDateTime since = LocalDateTime.now().minusDays(1);
        assertThat(store.cached(target, USER, since)).isNotNull();
        assertThat(store.byIsbn("9788947288132", USER, since)).isNotEmpty();

        exec("UPDATE textbook_web_revisions SET parser_version = ? WHERE revision_id = ?", BookPageParser.VERSION - 1,
                saved.getRevisionId());

        assertThat(store.cached(target, USER, since)).isNull();
        assertThat(store.byIsbn("9788947288132", USER, since)).isEmpty();
    }

    @Test
    void 판을_고른_뒤의_조회가_못_찾음으로_끝나도_고른_판의_목차는_그대로다() throws Exception {
        syllabus();
        searchReturns(NEW_URL, OLD_URL);
        textbookService.review(USER, courseId);
        runLookup();
        TextbookReview review = textbookService.review(USER, courseId);
        Long newest = review.lookup().editions().get(0).bestRevisionId();
        lookupService.choose(USER, courseId, review.lookup().lookupId(), newest, 0);

        // 책 페이지가 아닌 링크로 다시 찾는다 — 조회는 못 찾음으로 끝난다.
        String plain = "https://example.com/";
        when(fetcher.fetch(eq(plain))).thenReturn(new SafePageFetcher.Page(SafePageFetcher.Status.OK, plain, plain, 200,
                "<html><head><title>Example Domain</title></head><body><p>example</p></body></html>", null));
        lookupService.link(USER, courseId, plain);
        runLookup();

        TextbookReview after = textbookService.review(USER, courseId);
        assertThat(after.lookup().status()).isEqualTo("NOT_FOUND");
        assertThat(after.current().isbn()).isEqualTo("9788947288132");
        assertThat(after.toc().status()).isEqualTo("FOUND");
        assertThat(after.toc().kind()).isEqualTo("WEB");
        assertThat(after.toc().entries()).hasSize(12);
        assertThat(textbookService.tocOf(USER, courseId).basis().revisionId()).isEqualTo(newest);
    }

    @Test
    void 고른_판의_목차를_다시_찾아_바뀌었으면_새_목차를_쓰고_판을_고치면_옛_판의_근거를_버린다() throws Exception {
        syllabus();
        searchReturns(NEW_URL, OLD_URL);
        textbookService.review(USER, courseId);
        runLookup();
        TextbookReview review = textbookService.review(USER, courseId);
        Long newest = review.lookup().editions().get(0).bestRevisionId();
        lookupService.choose(USER, courseId, review.lookup().lookupId(), newest, 0);

        // 같은 판(ISBN)의 페이지 목차가 바뀌었다 — 다시 찾으면 새 목차가 지금 목차다.
        String changed = fixture("yes24-175899340.html").replace("/ 102", "/ 102<br/>Unit 13 Review / 110");
        when(fetcher.fetch(eq(NEW_URL), any())).thenReturn(
                new SafePageFetcher.Page(SafePageFetcher.Status.OK, NEW_URL, NEW_URL, 200, changed, null));
        // 다시 찾기는 캐시를 쓰지 않고 ISBN으로 서점 페이지를 먼저 연다(여기서는 목차 없는 응답).
        when(fetcher.fetch(org.mockito.ArgumentMatchers.startsWith("https://www.aladin.co.kr/"), any())).thenReturn(
                new SafePageFetcher.Page(SafePageFetcher.Status.HTTP_ERROR, "aladin", null, 404, null, "없음"));
        lookupService.retry(USER, courseId);
        runLookup();
        TextbookService.TocSnapshot refreshed = textbookService.tocOf(USER, courseId);
        assertThat(refreshed.basis().revisionId()).isNotEqualTo(newest);
        assertThat(refreshed.entries()).hasSize(13);
        assertThat(courseMapper.findByIdAndUserId(courseId, USER).getTextbookWebRevisionId())
                .isEqualTo(refreshed.basis().revisionId());

        // 그 뒤의 조회가 못 찾음으로 끝나도 새 목차(R2)를 지킨다 — 옛 리비전(R1)으로 되돌아가지 않는다.
        String plain = "https://example.com/";
        when(fetcher.fetch(eq(plain))).thenReturn(new SafePageFetcher.Page(SafePageFetcher.Status.OK, plain, plain, 200,
                "<html><head><title>Example Domain</title></head><body><p>example</p></body></html>", null));
        lookupService.link(USER, courseId, plain);
        runLookup();
        assertThat(textbookService.review(USER, courseId).lookup().status()).isEqualTo("NOT_FOUND");
        assertThat(textbookService.tocOf(USER, courseId).basis().revisionId()).isEqualTo(refreshed.basis().revisionId());

        // 판 표기를 바꾸고 ISBN을 지우면 다른 판일 수 있다 — 웹에서 확인한 판 근거를 남기지 않는다.
        CourseUpdateRequest edit = new CourseUpdateRequest();
        edit.setTextbookIsbn(null);
        edit.setTextbookEdition("개정 2판");
        edit.setExpectedTextbookVersion(courseMapper.findByIdAndUserId(courseId, USER).getTextbookVersion());
        courseService.update(USER, courseId, edit);
        Course course = courseMapper.findByIdAndUserId(courseId, USER);
        assertThat(course.getTextbookWebRevisionId()).isNull();
        assertThat(textbookService.review(USER, courseId).current().web()).isNull();
    }

    @Test
    void 늦게_끝난_옛_교재_검색은_사용자가_고친_교재를_덮지_않는다() throws Exception {
        syllabus();
        searchReturns(NEW_URL, OLD_URL);
        textbookService.review(USER, courseId);
        TextbookLookup claimed = claim();

        // 검색이 도는 사이 사용자가 "계획서에는 A지만 실제로는 B책"이라고 고친다.
        CourseUpdateRequest edit = new CourseUpdateRequest();
        edit.setTextbookTitle("실제로 쓰는 B책");
        edit.setExpectedTextbookVersion(0);
        courseService.update(USER, courseId, edit);

        TextbookLookupWorker.Result result = worker.run(claimed);

        assertThat(result).isIn(TextbookLookupWorker.Result.SUPERSEDED);
        assertThat(lookupMapper.findById(claimed.getLookupId()).getStatus()).isEqualTo("SUPERSEDED");
        Course course = courseMapper.findByIdAndUserId(courseId, USER);
        assertThat(course.getTextbookTitle()).isEqualTo("실제로 쓰는 B책");
        assertThat(course.getTextbookInfoSource()).isEqualTo("USER");
        // 재분석·재조회에서도 B가 유지된다 — 조회 대상도 B다.
        TextbookReview again = textbookService.review(USER, courseId);
        assertThat(again.lookup().query().title()).isEqualTo("실제로 쓰는 B책");
        assertThat(again.current().title()).isEqualTo("실제로 쓰는 B책");
    }

    @Test
    void 사용자_링크는_사용자마다_따로_저장되고_남의_리비전은_읽지_못한다() throws Exception {
        String personal = "https://blog.example.com/my-book?token=abc";
        BookPageParser.Parsed parsed = BookPageParser.parse(NEW_URL, fixture("yes24-175899340.html"));
        WebTocStructurer.Structured toc = WebTocStructurer.byRules(parsed.tocRaw(), false);

        TextbookWebRevision mine = store.save(store.target(personal, USER), parsed, toc, 200, LocalDateTime.now(), USER);
        TextbookWebRevision theirs = store.save(store.target(personal, OTHER_USER), parsed, toc, 200, LocalDateTime.now(),
                OTHER_USER);

        assertThat(mine.getCacheScopeKey()).isEqualTo("USER:" + USER);
        assertThat(theirs.getCacheScopeKey()).isEqualTo("USER:" + OTHER_USER);
        assertThat(mine.getPageId()).isNotEqualTo(theirs.getPageId());
        assertThat(webMapper.findRevision(mine.getRevisionId(), OTHER_USER)).isNull();
        assertThat(webMapper.findRevision(mine.getRevisionId(), USER)).isNotNull();
        // 지원 서점의 상품 주소는 공유한다(공개 도서 정보).
        assertThat(store.target(NEW_URL + "?Acode=1", USER).cacheScopeKey()).isEqualTo("SHARED");
        assertThat(store.target(NEW_URL + "?Acode=1", USER).url()).isEqualTo(NEW_URL);
    }

    // ===== 목차 → 정리안 → 적용 =====

    @Test
    void 목차를_확보하면_빈_트리에_정리안이_자동으로_생기고_적용하면_웹_출처와_교재가_남는다() throws Exception {
        syllabus();
        searchReturns(NEW_URL);
        textbookService.review(USER, courseId);
        runLookup();
        assertThat(textbookService.review(USER, courseId).lookup().status()).isEqualTo("FOUND");

        autoTidy.evaluate();
        ProjectTidyJob job = tidyMapper.findOpenJobByCourse(courseId, USER);
        assertThat(job).as("수업 파일이 없어도 목차로 정리가 시작된다").isNotNull();
        runTidy(job);

        ProjectTidyProposal proposal = tidyMapper.findOpenProposalByCourse(courseId, USER);
        assertThat(proposal).isNotNull();
        assertThat(proposal.getTocBasisJson()).contains("\"kind\":\"WEB\"");
        verify(analyzer, never()).analyze(anyLong(), any());
        var view = tidyService.view(USER, courseId);
        List<String> all = allChangeIds(view);
        assertThat(all).hasSize(12);

        // 같은 목차로 다시 평가해도 정리안을 또 만들지 않는다.
        autoTidy.evaluate();
        assertThat(tidyMapper.findOpenJobByCourse(courseId, USER)).isNull();

        tidyService.apply(USER, proposal.getProposalId(), applyRequest(view, all));

        List<CourseTopic> topics = topicMapper.findActiveByCourseIdAndUserId(courseId, USER);
        assertThat(topics).hasSize(12);
        assertThat(topics).allSatisfy(t -> {
            assertThat(t.getSourceMaterialId()).as("웹 근거를 업로드 자료 출처처럼 저장하지 않는다").isNull();
            assertThat(t.getSourceWebRevisionId()).isNotNull();
            assertThat(t.getSourceTextbookKey()).isEqualTo("isbn:9788947288132");
        });
        assertThat(topics).extracting(CourseTopic::getTitle).contains("Unit 1 What’s your name?", "Unit 10 What’s your name?");
        Course course = courseMapper.findByIdAndUserId(courseId, USER);
        assertThat(course.getTextbookInfoSource()).as("검토한 책이 지금 교재로 남는다").isEqualTo("WEB");
        assertThat(course.getTextbookIsbn()).isEqualTo("9788947288132");
    }

    @Test
    void 목차_트리는_계획에서_범위일_뿐이고_복습_목적이면_1단원부터_시작하지_않으며_교재를_바꾸면_이전_교재_항목이_된다() throws Exception {
        syllabus();
        searchReturns(NEW_URL);
        textbookService.review(USER, courseId);
        runLookup();
        autoTidy.evaluate();
        runTidy(tidyMapper.findOpenJobByCourse(courseId, USER));
        var view = tidyService.view(USER, courseId);
        tidyService.apply(USER, tidyMapper.findOpenProposalByCourse(courseId, USER).getProposalId(),
                applyRequest(view, allChangeIds(view)));

        var preview = planContext.build(USER, courseId, "영어회화", java.util.Set.of(), List.of(), List.of(), true);
        var review = planContext.build(USER, courseId, "영어회화", java.util.Set.of(), List.of(), List.of(), false);
        assertThat(preview.topics()).filteredOn(com.jungwoo.project.memo.plan.PlanMaterialContextService.TopicLine::firstUnlearned)
                .extracting(com.jungwoo.project.memo.plan.PlanMaterialContextService.TopicLine::title)
                .containsExactly("Unit 1 What’s your name?");
        assertThat(review.topics()).noneMatch(com.jungwoo.project.memo.plan.PlanMaterialContextService.TopicLine::firstUnlearned);
        assertThat(review.requiredTopics()).as("기록 없는 목차 항목은 판단 필수 사실(밀린 일)이 아니다").isEmpty();

        // "계획서에는 A지만 실제로는 B책" — 이전 교재의 목차 항목은 지우지 않고, 새 교재 범위로 세지 않는다.
        CourseUpdateRequest edit = new CourseUpdateRequest();
        edit.setTextbookTitle("실제로 쓰는 B책");
        // 다른 책으로 고칠 때 화면은 다섯 칸을 모두 보낸다(이전 책의 ISBN·판이 남지 않게).
        edit.setTextbookAuthor(null);
        edit.setTextbookPublisher(null);
        edit.setTextbookIsbn(null);
        edit.setTextbookEdition(null);
        edit.setExpectedTextbookVersion(courseMapper.findByIdAndUserId(courseId, USER).getTextbookVersion());
        courseService.update(USER, courseId, edit);

        var after = planContext.build(USER, courseId, "영어회화", java.util.Set.of(), List.of(), List.of(), true);
        assertThat(after.topics()).hasSize(12).allMatch(com.jungwoo.project.memo.plan.PlanMaterialContextService.TopicLine::priorTextbook);
        assertThat(after.topics()).noneMatch(com.jungwoo.project.memo.plan.PlanMaterialContextService.TopicLine::firstUnlearned);
        var map = learningMapService.map(USER, courseId);
        assertThat(map.textbook()).contains("실제로 쓰는 B책");
        assertThat(map.topics()).allSatisfy(t -> {
            assertThat(t.tocOrigin()).isEqualTo("WEB");
            assertThat(t.priorTextbook()).isTrue();
        });
    }

    @Test
    void 정리안을_만든_뒤_교재가_바뀌면_목차에서_온_변경은_적용되지_않는다() throws Exception {
        syllabus();
        searchReturns(NEW_URL);
        textbookService.review(USER, courseId);
        runLookup();
        autoTidy.evaluate();
        runTidy(tidyMapper.findOpenJobByCourse(courseId, USER));
        ProjectTidyProposal proposal = tidyMapper.findOpenProposalByCourse(courseId, USER);
        var view = tidyService.view(USER, courseId);

        CourseUpdateRequest edit = new CourseUpdateRequest();
        edit.setTextbookTitle("다른 책");
        edit.setExpectedTextbookVersion(0);
        courseService.update(USER, courseId, edit);

        assertThatThrownBy(() -> tidyService.apply(USER, proposal.getProposalId(), applyRequest(view, allChangeIds(view))))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TEXTBOOK_TOC_CHANGED));
        assertThat(topicMapper.findActiveByCourseIdAndUserId(courseId, USER)).isEmpty();
    }

    @Test
    void 트리가_있고_수업_자료가_없으면_목차와_비교해_빠진_장만_제안한다() throws Exception {
        syllabus();
        searchReturns(NEW_URL);
        textbookService.review(USER, courseId);
        runLookup();
        long unit1 = insertTopic("Unit 1 What’s your name?", null);
        when(analyzer.analyzeTocOnly(anyLong(), any(), any())).thenReturn(new ProjectTidyAnalyzer.Draft(List.of(
                tocAdd("x1", 2, "모델이 고친 제목"),
                tocAdd("x2", 99, "없는 목차 번호"),
                new TopicChangeOp("RENAME", null, unit1, null, null, "이름 바꾸기", null, null, null, null, null, null,
                        null, "이름을 목차에 맞춤"),
                new TopicChangeOp("ADD", "x3", null, null, null, "목차에 없는 새 항목", "AI_DERIVED", null, null, null,
                        null, null, null, "근거 없음")), "빠진 단원", "test-model"));

        autoTidy.evaluate();
        runTidy(tidyMapper.findOpenJobByCourse(courseId, USER));

        var view = tidyService.view(USER, courseId);
        List<TopicChangeOp> ops = opsOf(tidyMapper.findOpenProposalByCourse(courseId, USER));
        assertThat(ops).singleElement().satisfies(op -> {
            assertThat(op.op()).isEqualTo("ADD");
            assertThat(op.tocLine()).isEqualTo(2);
            assertThat(op.title()).as("제목은 목차에서 다시 채운다").isEqualTo("Unit 2 I’m doing my homework right now");
            assertThat(op.locator()).isEqualTo("교재 p.14");
        });
        assertThat(view).isNotNull();
    }

    @Test
    void 어느_책인지_적히지_않은_업로드_목차는_사용자가_이어야_쓰고_교재가_바뀌면_풀린다() throws Exception {
        CourseUpdateRequest edit = new CourseUpdateRequest();
        edit.setTextbookTitle("A책");
        edit.setExpectedTextbookVersion(0);
        courseService.update(USER, courseId, edit);
        long tocMaterial = 999_495_102L;
        exec("INSERT INTO course_materials (material_id, user_id, original_filename, stored_filename, storage_path, "
                        + "size_bytes, file_hash, extraction_status, extracted_text, status) "
                        + "VALUES (?, ?, '목차.pdf', 'x.pdf', ?, 1, ?, 'SUCCESS', 'text', 'ACTIVE')",
                tocMaterial, USER, USER + "/x-" + tocMaterial + ".pdf", "6".repeat(64));
        exec("INSERT INTO material_links (user_id, material_id, course_id, material_type) VALUES (?, ?, ?, 'TEXTBOOK_TOC')",
                USER, tocMaterial, courseId);
        String toc = "목차\n1장 배열 ........ 3\n2장 연결 리스트 ........ 20\n3장 스택 ........ 41\n";
        exec("INSERT INTO material_text_units (user_id, material_id, file_hash, unit_index, unit_type, unit_no, "
                + "char_count, text) VALUES (?, ?, ?, 0, 'PDF_PAGE', 1, ?, ?)", USER, tocMaterial, "6".repeat(64),
                toc.length(), toc);

        TextbookReview before = textbookService.review(USER, courseId);
        assertThat(before.toc().status()).as("이전 교재의 목차일 수 있다 — 저절로 쓰지 않는다").isEqualTo("NOT_FOUND");
        assertThat(before.unlinkedTocs()).extracting(com.jungwoo.project.memo.course.textbook.TocResolver.Unlinked::materialId)
                .containsExactly(tocMaterial);

        textbookService.linkToc(USER, courseId, tocMaterial, before.current().version());
        assertThat(textbookService.tocOf(USER, courseId).materialId()).isEqualTo(tocMaterial);

        CourseUpdateRequest switchBook = new CourseUpdateRequest();
        switchBook.setTextbookTitle("B책");
        switchBook.setExpectedTextbookVersion(courseMapper.findByIdAndUserId(courseId, USER).getTextbookVersion());
        courseService.update(USER, courseId, switchBook);

        assertThat(textbookService.tocOf(USER, courseId)).as("A책에 이었던 목차를 B책에 쓰지 않는다").isNull();
        assertThat(courseMapper.findByIdAndUserId(courseId, USER).getTextbookTocMaterialId()).isNull();
    }

    @Test
    void 교재를_고치면_화면을_열지_않아도_커밋_뒤_새_교재의_조회가_등록된다() {
        CourseUpdateRequest edit = new CourseUpdateRequest();
        edit.setTextbookTitle("실제로 쓰는 B책");
        edit.setExpectedTextbookVersion(0);
        courseService.update(USER, courseId, edit);

        TextbookLookup registered = lookupMapper.findLatestByCourse(courseId, USER);
        assertThat(registered).isNotNull();
        assertThat(registered.getStatus()).isEqualTo("QUEUED");
        assertThat(registered.getClueOrigin()).isEqualTo("CURRENT_TEXTBOOK");
        assertThat(registered.getQueryJson()).contains("실제로 쓰는 B책");
    }

    @Test
    void 규칙이_못_읽은_교재_표는_모델이_짚은_줄로_읽고_같은_작업이_그_책을_이어서_찾는다() throws Exception {
        String scrambled = "도서명 저자 출판사\nNEW English Conversation Arts 1 / Michael Putlack 외 / 형설출판사\n수업시\n";
        syllabusWith(scrambled);
        searchReturns(NEW_URL);
        when(modelAssist.isConfigured()).thenReturn(true);
        when(modelAssist.readClues(anyLong(), any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(
                new com.jungwoo.project.memo.course.textbook.TextbookExtractor.BookClue("MAIN",
                        "NEW English Conversation Arts 1", "Michael Putlack", "형설출판사", null, null, 1,
                        "NEW English Conversation Arts 1 / Michael Putlack 외 / 형설출판사")));

        TextbookReview before = textbookService.review(USER, courseId);
        assertThat(before.syllabusClues()).as("규칙은 못 읽었다").isEmpty();
        assertThat(before.lookup().query().needsClue()).isTrue();

        runLookup();

        TextbookReview after = textbookService.review(USER, courseId);
        assertThat(after.lookup().status()).isEqualTo("FOUND");
        assertThat(after.lookup().query().title()).isEqualTo("NEW English Conversation Arts 1");
        assertThat(after.syllabusClues()).singleElement().satisfies(c -> assertThat(c.source()).isEqualTo("MODEL"));
        // 보낸 원문 창에는 연락처 줄이 없다(모델 보조 전 지운다).
        org.mockito.ArgumentCaptor<List<String>> sent = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(modelAssist).readClues(anyLong(), sent.capture(), org.mockito.ArgumentMatchers.anyInt());
        assertThat(String.join("\n", sent.getValue())).doesNotContain("E-MAIL").doesNotContain("02-0000");
    }

    @Test
    void 모델_보조로도_책을_못_찾으면_못_찾음이고_주교재가_여럿이면_고르게_한다() throws Exception {
        String scrambled = "도서명 저자 출판사\n(표가 이미지라 글자가 없다)\n수업시\n";
        syllabusWith(scrambled);
        when(modelAssist.isConfigured()).thenReturn(true);
        when(modelAssist.readClues(anyLong(), any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());
        textbookService.review(USER, courseId);

        runLookup();

        TextbookReview none = textbookService.review(USER, courseId);
        assertThat(none.lookup().status()).isEqualTo("NOT_FOUND");
        assertThat(none.lookup().note()).contains("교재 칸에서 책 이름을 읽지 못했어요");
        verify(searchClient, never()).search(any());
    }

    @Test
    void 모델_보조_단서에_서로_다른_주교재가_여럿이면_검색하지_않고_고르게_한다() throws Exception {
        syllabusWith("도서명 저자 출판사\nA책과 B책을 함께 쓴다\n수업시\n");
        when(modelAssist.isConfigured()).thenReturn(true);
        when(modelAssist.readClues(anyLong(), any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(
                new com.jungwoo.project.memo.course.textbook.TextbookExtractor.BookClue("MAIN", "A책", null, null, null,
                        null, 1, "A책"),
                new com.jungwoo.project.memo.course.textbook.TextbookExtractor.BookClue("MAIN", "B책", null, null, null,
                        null, 1, "B책")));
        textbookService.review(USER, courseId);

        runLookup();

        TextbookReview review = textbookService.review(USER, courseId);
        assertThat(review.lookup().status()).isEqualTo("CLUE_CONFLICT");
        assertThat(review.lookup().clueOptions()).extracting(LookupResult.SyllabusOption::title).containsExactly("A책", "B책");
        verify(searchClient, never()).search(any());
    }

    @Test
    void 모델_보조_응답이_형식이_아니면_책_없음으로_확정하지_않고_다시_시도한다() throws Exception {
        syllabusWith("도서명 저자 출판사\n흩어진 표\n수업시\n");
        when(modelAssist.isConfigured()).thenReturn(true);
        when(modelAssist.readClues(anyLong(), any(), org.mockito.ArgumentMatchers.anyInt())).thenThrow(
                new com.jungwoo.project.memo.material.analysis.AnalysisFailure(
                        com.jungwoo.project.memo.material.analysis.AnalysisFailureClassifier.Kind.BAD_OUTPUT, "형식 아님"));
        textbookService.review(USER, courseId);

        assertThat(worker.run(claim())).isEqualTo(TextbookLookupWorker.Result.RESCHEDULED);

        TextbookReview after = textbookService.review(USER, courseId);
        assertThat(after.lookup().status()).isEqualTo("QUEUED");
        assertThat(after.lookup().query().needsClue()).as("단서 칸은 그대로(SIGNAL) — 다음 시도에서 다시 본다").isTrue();
    }

    @Test
    void 같은_책에_ISBN을_채우면_그_책의_목차_항목은_이전_교재가_되지_않는다() throws Exception {
        CourseUpdateRequest first = new CourseUpdateRequest();
        first.setTextbookTitle("C로 배우는 쉬운 자료구조");
        first.setExpectedTextbookVersion(0);
        courseService.update(USER, courseId, first);
        long topic = insertTopic("1장 자료구조와 알고리즘", null);
        exec("UPDATE course_topics SET source_textbook_key = ?, source_locator = '교재 p.13' WHERE topic_id = ?",
                com.jungwoo.project.memo.course.textbook.BookKey.of("C로 배우는 쉬운 자료구조", null, null), topic);

        CourseUpdateRequest isbn = new CourseUpdateRequest();
        isbn.setTextbookIsbn("979-11-5664-567-2");
        isbn.setExpectedTextbookVersion(courseMapper.findByIdAndUserId(courseId, USER).getTextbookVersion());
        courseService.update(USER, courseId, isbn);

        var catalog = planContext.build(USER, courseId, "자료구조", java.util.Set.of(), List.of(), List.of(), true);
        assertThat(catalog.topics()).singleElement().satisfies(t -> {
            assertThat(t.priorTextbook()).isFalse();
            assertThat(t.firstUnlearned()).isTrue();
        });
    }

    @Test
    void 목차_근거를_남기기_전의_옛_정리안도_같은_목차_자료면_낡았다고_하지_않고_적용된다() throws Exception {
        long tocMaterial = 999_495_103L;
        exec("INSERT INTO course_materials (material_id, user_id, original_filename, stored_filename, storage_path, "
                        + "size_bytes, file_hash, extraction_status, extracted_text, status) "
                        + "VALUES (?, ?, '목차.pdf', 'x.pdf', ?, 1, ?, 'SUCCESS', 'text', 'ACTIVE')",
                tocMaterial, USER, USER + "/x-" + tocMaterial + ".pdf", "4".repeat(64));
        exec("INSERT INTO material_links (user_id, material_id, course_id, material_type) VALUES (?, ?, ?, 'TEXTBOOK_TOC')",
                USER, tocMaterial, courseId);
        String toc = "목차\n1장 배열 ........ 3\n2장 연결 리스트 ........ 20\n3장 스택 ........ 41\n";
        exec("INSERT INTO material_text_units (user_id, material_id, file_hash, unit_index, unit_type, unit_no, "
                + "char_count, text) VALUES (?, ?, ?, 0, 'PDF_PAGE', 1, ?, ?)", USER, tocMaterial, "4".repeat(64),
                toc.length(), toc);
        textbookService.review(USER, courseId);
        tidyService.request(USER, courseId, false);
        runTidy(tidyMapper.findOpenJobByCourse(courseId, USER));
        ProjectTidyProposal proposal = tidyMapper.findOpenProposalByCourse(courseId, USER);
        // 근거 칸이 생기기 전에 만들어진 안처럼.
        exec("UPDATE project_tidy_proposals SET toc_basis_json = NULL WHERE proposal_id = ?", proposal.getProposalId());

        var view = tidyService.view(USER, courseId);
        assertThat(view.isTocStale()).isFalse();
        assertThat(view.isNewTocAvailable()).isFalse();
        tidyService.apply(USER, proposal.getProposalId(), applyRequest(view, allChangeIds(view)));
        assertThat(topicMapper.findActiveByCourseIdAndUserId(courseId, USER)).hasSize(3);
    }

    @Test
    void 임대를_잃은_worker는_외부_호출을_하지_않는다() throws Exception {
        syllabus();
        searchReturns(NEW_URL);
        textbookService.review(USER, courseId);
        TextbookLookup claimed = claim();
        // 다른 worker가 임대 만료 뒤 다시 집었다.
        exec("UPDATE textbook_lookups SET lease_token = lease_token + 1 WHERE lookup_id = ?", claimed.getLookupId());

        assertThat(worker.run(claimed)).isEqualTo(TextbookLookupWorker.Result.SUPERSEDED);
        verify(searchClient, never()).search(any());
    }

    @Test
    void 웹_검색을_꺼_두면_링크로도_찾지_않는다() {
        lookupService.setEnabled(USER, courseId, false);

        assertThatThrownBy(() -> lookupService.link(USER, courseId, NEW_URL))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TEXTBOOK_WEB_DISABLED));
        assertThat(textbookService.review(USER, courseId).webLookupEnabled()).isFalse();
    }

    // ===== 도움 =====

    private void syllabusWith(String text) throws Exception {
        String withContacts = "담당 교수 + 전 화 : 02-0000-0000\n + E-MAIL : prof@example.invalid\n" + text;
        exec("INSERT INTO course_materials (material_id, user_id, original_filename, stored_filename, storage_path, "
                        + "size_bytes, file_hash, extraction_status, extracted_text, status) "
                        + "VALUES (?, ?, '강의계획서.pdf', 'x.pdf', ?, 1, ?, 'SUCCESS', 'text', 'ACTIVE')",
                SYLLABUS, USER, USER + "/x-" + SYLLABUS + ".pdf", HASH_SYLLABUS);
        exec("INSERT INTO material_links (user_id, material_id, course_id, material_type) VALUES (?, ?, ?, 'SYLLABUS')",
                USER, SYLLABUS, courseId);
        exec("INSERT INTO material_text_units (user_id, material_id, file_hash, unit_index, unit_type, unit_no, "
                + "char_count, text) VALUES (?, ?, ?, 0, 'PDF_PAGE', 1, ?, ?)", USER, SYLLABUS, HASH_SYLLABUS,
                withContacts.length(), withContacts);
    }

    private void syllabus() throws Exception {
        exec("INSERT INTO course_materials (material_id, user_id, original_filename, stored_filename, storage_path, "
                        + "size_bytes, file_hash, extraction_status, extracted_text, status) "
                        + "VALUES (?, ?, '강의계획서.pdf', 'x.pdf', ?, 1, ?, 'SUCCESS', 'text', 'ACTIVE')",
                SYLLABUS, USER, USER + "/x-" + SYLLABUS + ".pdf", HASH_SYLLABUS);
        exec("INSERT INTO material_links (user_id, material_id, course_id, material_type) VALUES (?, ?, ?, 'SYLLABUS')",
                USER, SYLLABUS, courseId);
        exec("INSERT INTO material_text_units (user_id, material_id, file_hash, unit_index, unit_type, unit_no, "
                + "char_count, text) VALUES (?, ?, ?, 0, 'PDF_PAGE', 1, ?, ?)", USER, SYLLABUS, HASH_SYLLABUS,
                SYLLABUS_TEXT.length(), SYLLABUS_TEXT);
    }

    private void searchReturns(String... urls) {
        List<BookWebSearchClient.PageHint> hints = new ArrayList<>();
        for (String u : urls) {
            hints.add(new BookWebSearchClient.PageHint(u, "BOOKSTORE"));
        }
        when(searchClient.search(any())).thenReturn(new BookWebSearchClient.SearchResult(hints, 100, 10, null));
    }

    private TextbookLookup claim() {
        LocalDateTime now = LocalDateTime.now().plusSeconds(1);
        TextbookLookup open = lookupMapper.findLatestByCourse(courseId, USER);
        assertThat(lookupMapper.claim(open.getLookupId(), "test", now, now.plusMinutes(3))).isEqualTo(1);
        return lookupMapper.findById(open.getLookupId());
    }

    private void runLookup() {
        assertThat(worker.run(claim())).isEqualTo(TextbookLookupWorker.Result.COMPLETED);
    }

    private void runTidy(ProjectTidyJob job) {
        LocalDateTime now = LocalDateTime.now().plusSeconds(1);
        assertThat(tidyMapper.claimJob(job.getJobId(), "test", now, now.plusMinutes(3))).isEqualTo(1);
        tidyWorker.run(tidyMapper.findJobById(job.getJobId()));
    }

    private static TopicChangeOp tocAdd(String tempId, int line, String title) {
        return new TopicChangeOp("ADD", tempId, null, null, null, title, "SOURCE", null, null, null, null, null, null,
                "목차에 있지만 트리에 없음", null, null, null, null, null, null, line);
    }

    private List<TopicChangeOp> opsOf(ProjectTidyProposal proposal) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readValue(proposal.getOpsJson(),
                new com.fasterxml.jackson.core.type.TypeReference<List<TopicChangeOp>>() {
                });
    }

    private static List<String> allChangeIds(com.jungwoo.project.memo.learning.tidy.dto.ProjectTidyResponse view) {
        List<String> ids = new ArrayList<>();
        view.getChanges().forEach(c -> ids.add(c.getChangeId()));
        return ids;
    }

    private static ProjectTidyRequests.Apply applyRequest(com.jungwoo.project.memo.learning.tidy.dto.ProjectTidyResponse view,
                                                          List<String> ids) {
        ProjectTidyRequests.Apply apply = new ProjectTidyRequests.Apply();
        apply.setRevision(view.getRevision());
        apply.setEditRevision(view.getEditRevision() == null ? 0L : view.getEditRevision());
        apply.setBaseTreeVersion(view.getBaseTreeVersion());
        apply.setSelectedChangeIds(ids);
        return apply;
    }

    private SafePageFetcher.Page page(String url, String fixture) throws Exception {
        return new SafePageFetcher.Page(SafePageFetcher.Status.OK, url, url, 200, fixture(fixture), null);
    }

    private static String fixture(String name) throws Exception {
        try (InputStream in = TextbookWebLookupDbTest.class.getResourceAsStream("/textbook/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private long insertCourse(long user, String title) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO courses (user_id, title, status) VALUES (?, ?, 'ACTIVE')", Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, user);
            ps.setString(2, title);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long insertTopic(String title, Long parent) throws Exception {
        CourseTopic topic = CourseTopic.builder().userId(USER).courseId(courseId).parentTopicId(parent).title(title)
                .orderIndex(0).sourceType(com.jungwoo.project.memo.learning.domain.TopicSourceType.SOURCE)
                .status(com.jungwoo.project.memo.learning.domain.TopicStatus.ACTIVE).build();
        topicMapper.insert(topic);
        exec("UPDATE courses SET topic_tree_version = topic_tree_version + 1 WHERE course_id = ?", courseId);
        return topic.getTopicId();
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
        for (long u : new long[]{USER, OTHER_USER}) {
            exec("DELETE FROM project_tidy_edits WHERE proposal_id IN (SELECT proposal_id FROM project_tidy_proposals WHERE user_id = ?)", u);
            for (String table : List.of("project_tidy_proposal_materials", "project_tidy_proposals", "project_tidy_jobs",
                    "textbook_lookups", "textbook_lookup_usage", "material_textbook_extracts", "topic_material_links",
                    "course_topics", "material_text_units", "material_links", "course_materials", "ai_usage_logs",
                    "courses", "users")) {
                exec("DELETE FROM " + table + " WHERE user_id = ?", u);
            }
            exec("DELETE r FROM textbook_web_revisions r JOIN textbook_web_pages p ON p.page_id = r.page_id "
                    + "WHERE p.cache_scope_key = ?", "USER:" + u);
            exec("DELETE FROM textbook_web_pages WHERE cache_scope_key = ?", "USER:" + u);
        }
        // 공유 페이지는 이 테스트가 쓰는 두 상품 주소만 지운다.
        for (String url : List.of(NEW_URL, OLD_URL)) {
            String hash = TextbookLookupPlanner.hash(url);
            exec("DELETE r FROM textbook_web_revisions r JOIN textbook_web_pages p ON p.page_id = r.page_id "
                    + "WHERE p.cache_scope_key = 'SHARED' AND p.url_hash = ?", hash);
            exec("DELETE FROM textbook_web_pages WHERE cache_scope_key = 'SHARED' AND url_hash = ?", hash);
        }
    }
}
