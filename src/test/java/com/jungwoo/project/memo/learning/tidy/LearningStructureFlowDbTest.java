package com.jungwoo.project.memo.learning.tidy;

import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.course.CourseService;
import com.jungwoo.project.memo.course.dto.CourseUpdateRequest;
import com.jungwoo.project.memo.course.textbook.TextbookReview;
import com.jungwoo.project.memo.course.textbook.TextbookService;
import com.jungwoo.project.memo.learning.CourseTopicMapper;
import com.jungwoo.project.memo.learning.LearningMapService;
import com.jungwoo.project.memo.learning.domain.CourseTopic;
import com.jungwoo.project.memo.learning.domain.TopicSourceType;
import com.jungwoo.project.memo.learning.domain.TopicStatus;
import com.jungwoo.project.memo.learning.dto.LearningMapResponse;
import com.jungwoo.project.memo.learning.structure.TopicChangeOp;
import com.jungwoo.project.memo.learning.tidy.domain.ProjectTidyJob;
import com.jungwoo.project.memo.learning.tidy.domain.ProjectTidyProposal;
import com.jungwoo.project.memo.learning.tidy.dto.ProjectTidyRequests;
import com.jungwoo.project.memo.learning.tidy.dto.ProjectTidyResponse;
import com.jungwoo.project.memo.material.analysis.MaterialAnalysisJobService;
import com.jungwoo.project.memo.plan.PlanMaterialContextService;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * 교재 → 학습 구조의 첫 골격, 그리고 실제 수업에 맞춘 정정. 모델 호출만 가짜이고 나머지는 실제 DB에서 돈다.
 *
 * <p>고정하는 것:
 * <ul>
 *   <li>옛 [구조 분석] 버튼 없이, 프로젝트를 열면 연결 자료의 원문에서 교재 서지·목차를 읽어 후보로 보인다. 적용은 고른 칸만,
 *       그 사이 사용자가 고친 값은 덮지 않는다(409). 다시 읽어도 추출 행이 늘지 않는다.</li>
 *   <li>책 이름만 있으면 "목차 미확보"와 다음 행동을 말하고 목차를 만들지 않는다.</li>
 *   <li>트리가 비어 있으면 목차가 골격(장 = 변경 하나)이 되고, 강의 구간은 모델이 골격 tempId에 잇는다. 모델이 같은 장을
 *       또 만들면 뺀다.</li>
 *   <li>수업 순서 정정은 트리를 바꾸지 않고, 범위 제외는 완료가 아니라 계획 후보에서만 빠진다. 선택 적용이고, 제외한 변경은
 *       적용되지 않으며, 트리가 바뀐 뒤의 옛 안은 적용되지 않는다.</li>
 *   <li>사용자가 낸 변경은 AI가 정리안을 다시 만들어도 새 판으로 옮겨 간다. 정정은 재분석·재정리에서 유지된다.</li>
 *   <li>병합은 기록을 옮기지 않고, 살아남은 항목에 "병합된 항목의 기록"으로 알린다.</li>
 * </ul>
 *
 * <p>로컬 memo_test DB 필요. CI 제외.
 */
@SpringBootTest
class LearningStructureFlowDbTest {

    private static final long USER = 999_000_901L;
    private static final long TOC_MATERIAL = 999_490_101L;
    private static final long LECTURE = 999_490_102L;
    private static final long SYLLABUS = 999_490_103L;
    private static final String HASH_TOC = "7".repeat(64);
    private static final String HASH_LECTURE = "8".repeat(64);
    private static final String HASH_SYLLABUS = "9".repeat(64);

    @MockitoBean
    private ProjectTidyAnalyzer analyzer;

    @Autowired
    private TextbookService textbookService;
    @Autowired
    private CourseService courseService;
    @Autowired
    private ProjectTidyService tidyService;
    @Autowired
    private ProjectTidyWorker worker;
    @Autowired
    private ProjectTidyMapper tidyMapper;
    @Autowired
    private StructureRequestService structureService;
    @Autowired
    private LearningMapService learningMapService;
    @Autowired
    private PlanMaterialContextService planContext;
    @Autowired
    private CourseTopicMapper topicMapper;
    @Autowired
    private DataSource dataSource;

    private long courseId;
    private long lectureSection;

    @BeforeEach
    void setUp() throws Exception {
        cleanUp();
        exec("INSERT INTO users (user_id, email, password_hash, nickname, role, status) "
                + "VALUES (?, 'structure-test@memo-test.invalid', '!no-login', '구조 테스트', 'USER', 'ACTIVE')", USER);
        courseId = insertCourse("자료구조");
    }

    // ===== 교재 =====

    @Test
    void 자동_분석_흐름에서_교재_서지와_목차를_검토하고_고른_칸만_적용하며_사용자_수정은_덮지_않는다() throws Exception {
        tocMaterial(courseId);

        TextbookReview review = textbookService.review(USER, courseId);

        assertThat(review.state()).isEqualTo("TOC_FOUND");
        assertThat(review.toc().entryCount()).isGreaterThanOrEqualTo(6);
        assertThat(review.toc().materialId()).isEqualTo(TOC_MATERIAL);
        TextbookReview.Candidate candidate = review.candidates().get(0);
        assertThat(candidate.fields()).extracting(TextbookReview.FieldCandidate::field)
                .contains("isbn", "author", "publisher", "edition");
        assertThat(field(candidate, "isbn").quote()).contains("ISBN");
        assertThat(field(candidate, "isbn").unit()).isEqualTo(2);

        // 고른 칸(ISBN·판)만. 화면이 본 지금 값(비어 있음)을 함께 보낸다.
        textbookService.apply(USER, courseId, TOC_MATERIAL,
                Map.of("isbn", "9791156645672", "edition", "개정 4판"), mapOfNulls("isbn", "edition"));
        TextbookReview after = textbookService.review(USER, courseId);
        assertThat(after.current().isbn()).isEqualTo("9791156645672");
        assertThat(after.current().source()).isEqualTo("MATERIAL");
        assertThat(after.current().author()).as("고르지 않은 칸은 그대로").isNull();

        // 사용자가 판을 직접 고친다 → 자료 후보가 다시 적용되려면 지금 값을 보고 골라야 한다.
        CourseUpdateRequest edit = new CourseUpdateRequest();
        org.springframework.test.util.ReflectionTestUtils.setField(edit, "title", "자료구조");
        org.springframework.test.util.ReflectionTestUtils.setField(edit, "textbookIsbn", "9791156645672");
        org.springframework.test.util.ReflectionTestUtils.setField(edit, "textbookEdition", "개정 5판");
        courseService.update(USER, courseId, edit);
        assertThatThrownBy(() -> textbookService.apply(USER, courseId, TOC_MATERIAL,
                Map.of("edition", "개정 4판"), Map.of("edition", "개정 4판")))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TEXTBOOK_INFO_CHANGED));
        TextbookReview edited = textbookService.review(USER, courseId);
        assertThat(edited.current().edition()).isEqualTo("개정 5판");
        assertThat(edited.current().source()).isEqualTo("USER");
        assertThat(field(edited.candidates().get(0), "edition").same()).isFalse();

        // 다시 읽어도(재분석·재시도·새 탭) 추출 행이 늘지 않는다.
        textbookService.review(USER, courseId);
        assertThat(count("SELECT COUNT(*) FROM material_textbook_extracts WHERE user_id = " + USER)).isEqualTo(1);
    }

    @Test
    void 책_이름만_있으면_목차를_만들지_않고_미확보와_다음_행동을_말한다() throws Exception {
        CourseUpdateRequest edit = new CourseUpdateRequest();
        org.springframework.test.util.ReflectionTestUtils.setField(edit, "title", "자료구조");
        org.springframework.test.util.ReflectionTestUtils.setField(edit, "textbookTitle", "C로 배우는 쉬운 자료구조");
        courseService.update(USER, courseId, edit);
        insertMaterial(SYLLABUS, "강의계획서.pdf", HASH_SYLLABUS);
        link(courseId, SYLLABUS, "SYLLABUS");
        unit(SYLLABUS, HASH_SYLLABUS, 1, "강의계획서\n교재: C로 배우는 쉬운 자료구조\n1주차 오리엔테이션\n2주차 배열");

        TextbookReview review = textbookService.review(USER, courseId);

        assertThat(review.state()).isEqualTo("TITLE_ONLY");
        assertThat(review.toc().status()).isEqualTo("NOT_FOUND");
        assertThat(review.toc().entries()).isEmpty();
        assertThat(review.nextAction()).contains("목차를 아직 확보하지 못했어요").contains("계획과 학습은 그대로");
        assertThat(textbookService.tocOf(USER, courseId)).isNull();
    }

    @Test
    void 빈_트리에서는_목차가_골격이_되고_강의_구간은_골격_항목에_이어지며_중복_장은_빠진다() throws Exception {
        tocMaterial(courseId);
        insertMaterial(LECTURE, "2주차_배열.pdf", HASH_LECTURE);
        link(courseId, LECTURE, "PROFESSOR_SLIDE");
        contentJob(LECTURE, HASH_LECTURE);
        lectureSection = section(LECTURE, HASH_LECTURE, "배열의 선언과 초기화", 3);
        when(analyzer.analyze(anyLong(), any())).thenAnswer(inv -> {
            ProjectTidyInputBuilder.Input input = inv.getArgument(1);
            // 골격이 입력에 실려 있다. 모델은 강의 구간을 "배열" 장(t4)에 잇고, 같은 장을 또 만들려 한다.
            TopicChangeOp chapter = input.guidance().skeleton().stream()
                    .filter(op -> op.title().contains("배열") && op.children() != null).findFirst().orElseThrow();
            return new ProjectTidyAnalyzer.Draft(List.of(
                    new TopicChangeOp("LINK", chapter.tempId(), null, null, null, null, null, null,
                            List.of(lectureSection), "CONCEPT", null, null, null, "강의가 배열 장을 다룬다"),
                    new TopicChangeOp("ADD", "n1", null, null, null, "배열", "AI_DERIVED", null,
                            List.of(lectureSection), "CONCEPT", null, null, null, "중복")), "요약", "fake-model");
        });

        tidyService.request(USER, courseId, false);
        runQueuedJob();
        ProjectTidyResponse view = tidyService.view(USER, courseId);

        List<ProjectTidyResponse.Change> adds = view.getChanges().stream().filter(c -> "ADD".equals(c.getOp())).toList();
        assertThat(adds).as("장마다 변경 하나, 모델의 중복 장은 빠진다").hasSize(2)
                .allSatisfy(c -> assertThat(c.getBy()).isEqualTo("TOC"));
        ProjectTidyResponse.Change link = view.getChanges().stream().filter(c -> "LINK".equals(c.getOp()))
                .findFirst().orElseThrow();
        String arrayChapter = adds.stream().filter(c -> c.getTitle().contains("배열")).findFirst().orElseThrow().getChangeId();
        assertThat(view.getDependsOn().get(link.getChangeId())).containsExactly(arrayChapter);

        apply(view, Set.of());

        List<CourseTopic> topics = topicMapper.findActiveByCourseIdAndUserId(courseId, USER);
        CourseTopic arrays = topics.stream().filter(t -> t.getTitle().equals("CHAPTER 02 배열")).findFirst().orElseThrow();
        assertThat(topics.stream().filter(t -> arrays.getTopicId().equals(t.getParentTopicId())))
                .extracting(CourseTopic::getTitle).containsExactly("2.1 배열의 개념", "2.2 희소 행렬");
        assertThat(arrays.getSourceMaterialId()).isEqualTo(TOC_MATERIAL);
        assertThat(count("SELECT COUNT(*) FROM topic_material_links WHERE topic_id = " + arrays.getTopicId()
                + " AND section_id = " + lectureSection)).isEqualTo(1);
    }

    // ===== 실제 수업 정정 =====

    @Test
    void 수업_순서_정정과_범위_제외는_트리를_바꾸지_않고_고른_것만_적용되며_계획과_지도에_남는다() throws Exception {
        long ch3 = topic("3장 스택", null, 0);
        long ch4 = topic("4장 큐", null, 1);
        long ch5 = topic("5장 연결 리스트", null, 2);
        long ch5a = topic("단순 연결 리스트", ch5, 0);

        structureService.manual(USER, courseId, List.of(
                klass(ch5, 2, 0L), klass(ch3, null, ch5),
                scope(ch4, "중간고사"),
                new TopicChangeOp("RENAME", null, ch5a, null, null, "5.1 단순 연결 리스트", null, null, null, null,
                        null, null, null, "이름")), "교재 5장을 3장보다 먼저 수업했어");
        // 같은 열린 안에 하나 더 — 판이 오르고 한 안에 모인다.
        structureService.manual(USER, courseId, List.of(new TopicChangeOp("MOVE", null, ch4, null, null, null, null,
                null, null, null, null, null, null, "위치", null, 0L, null, null, null, null)), null);

        ProjectTidyResponse view = tidyService.view(USER, courseId);
        assertThat(view.getOrigin()).isEqualTo("USER");
        assertThat(view.getRevision()).isEqualTo(2L);
        assertThat(view.getChanges()).hasSize(5);
        assertThat(view.getTree()).extracting(ProjectTidyResponse.TreeNode::getTopicId).contains(ch3, ch4, ch5, ch5a);
        long treeBefore = treeVersion();

        String rename = view.getChanges().stream().filter(c -> "RENAME".equals(c.getOp())).findFirst().orElseThrow()
                .getChangeId();
        apply(view, Set.of(rename));

        assertThat(topicMapper.findByIdAndUserId(ch5a, USER).getTitle()).as("뺀 변경은 적용되지 않는다")
                .isEqualTo("단순 연결 리스트");
        assertThat(topicMapper.findByIdAndUserId(ch4, USER).getOrderIndex()).as("맨 앞으로")
                .isLessThan(topicMapper.findByIdAndUserId(ch3, USER).getOrderIndex());
        assertThat(topicMapper.findByIdAndUserId(ch5, USER).getParentTopicId()).isNull();
        assertThat(treeVersion()).isEqualTo(treeBefore + 1);
        assertThat(count("SELECT class_seq FROM topic_class_progress WHERE topic_id = " + ch5)).isEqualTo(1);
        assertThat(count("SELECT class_seq FROM topic_class_progress WHERE topic_id = " + ch3)).isEqualTo(2);
        assertThat(count("SELECT week_no FROM topic_class_progress WHERE topic_id = " + ch5)).isEqualTo(2);

        LearningMapResponse map = learningMapService.map(USER, courseId);
        LearningMapResponse.TopicNode five = map.topics().stream().filter(t -> t.topicId() == ch5).findFirst().orElseThrow();
        assertThat(five.classWeek()).isEqualTo(2);
        assertThat(five.classSeq()).isEqualTo(1);
        assertThat(map.topics().stream().filter(t -> t.topicId() == ch4).findFirst().orElseThrow().scopeLabel())
                .isEqualTo("중간고사");
        assertThat(map.weeks()).singleElement().satisfies(w -> {
            assertThat(w.basis()).isEqualTo("CONFIRMED_CLASS");
            assertThat(w.topicIds()).containsExactly(ch5);
        });
        // 범위 제외는 계획 후보에서만 빠진다(진도는 그대로).
        PlanMaterialContextService.CourseCatalog catalog = planContext.build(USER, courseId, "자료구조", Set.of(),
                List.of(), List.of());
        assertThat(catalog.excluded()).anySatisfy(e -> {
            assertThat(e.topicId()).isEqualTo(ch4);
            assertThat(e.reason()).isEqualTo("SCOPE:중간고사");
        });
        assertThat(catalog.topics()).extracting(PlanMaterialContextService.TopicLine::topicId).doesNotContain(ch4);
    }

    @Test
    void 트리가_바뀐_뒤의_옛_안은_적용되지_않고_더하지도_않는다() throws Exception {
        long ch3 = topic("3장 스택", null, 0);
        structureService.manual(USER, courseId, List.of(klass(ch3, 3, null)), null);
        ProjectTidyResponse stale = tidyService.view(USER, courseId);
        exec("UPDATE courses SET topic_tree_version = topic_tree_version + 1 WHERE course_id = ?", courseId);

        assertThatThrownBy(() -> structureService.manual(USER, courseId, List.of(klass(ch3, 4, null)), null))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STRUCTURE_OPEN_PROPOSAL_EXISTS));
        assertThatThrownBy(() -> apply(stale, Set.of()))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TOPIC_TREE_CONFLICT));
        assertThat(count("SELECT COUNT(*) FROM topic_class_progress WHERE user_id = " + USER)).isZero();
    }

    @Test
    void 사용자가_낸_변경은_AI가_다시_정리해도_새_판으로_옮겨_가고_정정은_재정리에서_유지된다() throws Exception {
        long ch3 = topic("3장 스택", null, 0);
        readyLecture();
        when(analyzer.analyze(anyLong(), any())).thenReturn(new ProjectTidyAnalyzer.Draft(List.of(
                new TopicChangeOp("LINK", null, ch3, null, null, null, null, null, List.of(lectureSection), "CONCEPT",
                        null, null, null, "강의")), "요약", "fake-model"));

        structureService.manual(USER, courseId, List.of(scope(ch3, "기말고사")), "기말 범위 아님");
        tidyService.request(USER, courseId, true, "스택 구현 실습은 따로 나눠줘", List.of(ch3));
        ProjectTidyJob job = runQueuedJob();

        ProjectTidyResponse view = tidyService.view(USER, courseId);
        assertThat(job.getUserRequestJson()).contains("따로 나눠줘");
        assertThat(view.getUserRequest()).isEqualTo("스택 구현 실습은 따로 나눠줘");
        assertThat(view.getChanges()).extracting(ProjectTidyResponse.Change::getOp).contains("LINK", "SCOPE_EXCLUDE");
        apply(view, Set.of());

        // 다시 정리해도(모델이 아무것도 안 내도) 정정은 그대로다.
        when(analyzer.analyze(anyLong(), any())).thenReturn(new ProjectTidyAnalyzer.Draft(List.of(), "없음", "fake-model"));
        tidyService.request(USER, courseId, true);
        runQueuedJob();
        assertThat(count("SELECT COUNT(*) FROM course_scope_exclusions WHERE status = 'ACTIVE' AND topic_id = " + ch3))
                .isEqualTo(1);
    }

    @Test
    void 병합은_기록을_옮기지_않고_살아남은_항목에_알린다() throws Exception {
        long a = topic("스택 구현", null, 0);
        long b = topic("스택 자료구조", null, 1);
        exec("INSERT INTO execution_items (user_id, topic_id, course_id, title, placement_type, scheduled_date, "
                + "expected_minutes, status, priority, order_index, origin_type, version, is_deleted) "
                + "VALUES (?, ?, ?, '스택 push/pop', 'DATE_ONLY', CURDATE(), 30, 'DONE', 'SHOULD', 0, 'MANUAL', 1, 0)",
                USER, a, courseId);
        structureService.manual(USER, courseId, List.of(new TopicChangeOp("MERGE", null, null, null, null, null, null,
                null, null, null, b, List.of(a), null, "같은 내용")), null);
        ProjectTidyResponse view = tidyService.view(USER, courseId);
        assertThat(view.getChanges().get(0).getImpact()).anySatisfy(i -> {
            assertThat(i.getTopicId()).isEqualTo(a);
            assertThat(i.getDoneItems()).isEqualTo(1);
        });

        apply(view, Set.of());

        assertThat(count("SELECT COUNT(*) FROM execution_items WHERE topic_id = " + a)).as("기록은 그대로").isEqualTo(1);
        LearningMapResponse map = learningMapService.map(USER, courseId);
        assertThat(map.topics()).singleElement().satisfies(t -> {
            assertThat(t.topicId()).isEqualTo(b);
            assertThat(t.mergedDoneItems()).isEqualTo(1);
            assertThat(t.doneItems()).as("완료로 승계하지 않는다").isZero();
        });
    }

    // ===== 도구 =====

    private static TopicChangeOp klass(long topicId, Integer week, Long after) {
        return new TopicChangeOp("CLASS", null, topicId, null, null, null, null, null, null, null, null, null, null,
                null, null, after, week, null, null, null);
    }

    private static TopicChangeOp scope(long topicId, String label) {
        return new TopicChangeOp("SCOPE_EXCLUDE", null, topicId, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, label, null);
    }

    private static TextbookReview.FieldCandidate field(TextbookReview.Candidate c, String name) {
        return c.fields().stream().filter(f -> f.field().equals(name)).findFirst().orElseThrow();
    }

    private static Map<String, String> mapOfNulls(String... keys) {
        Map<String, String> out = new java.util.HashMap<>();
        for (String k : keys) {
            out.put(k, null);
        }
        return out;
    }

    private void apply(ProjectTidyResponse view, Set<String> excluded) {
        ProjectTidyRequests.Apply request = new ProjectTidyRequests.Apply();
        request.setRevision(view.getRevision());
        request.setEditRevision(view.getEditRevision() == null ? 0L : view.getEditRevision());
        request.setBaseTreeVersion(view.getBaseTreeVersion());
        request.setSelectedChangeIds(view.getChanges().stream().map(ProjectTidyResponse.Change::getChangeId)
                .filter(id -> !excluded.contains(id)).toList());
        tidyService.apply(USER, view.getProposalId(), request);
    }

    private ProjectTidyJob runQueuedJob() {
        ProjectTidyJob queued = tidyMapper.findLatestJobByCourse(courseId, USER);
        LocalDateTime now = LocalDateTime.now();
        assertThat(tidyMapper.claimJob(queued.getJobId(), "test", now, now.plusMinutes(5))).isEqualTo(1);
        worker.run(tidyMapper.findJobById(queued.getJobId()));
        ProjectTidyJob done = tidyMapper.findJobById(queued.getJobId());
        assertThat(done.getStatus().name()).isEqualTo("DONE");
        return done;
    }

    private void tocMaterial(long course) throws Exception {
        insertMaterial(TOC_MATERIAL, "자료구조_교재_앞부분.pdf", HASH_TOC);
        link(course, TOC_MATERIAL, "TEXTBOOK_TOC");
        unit(TOC_MATERIAL, HASH_TOC, 1, "C로 배우는 쉬운 자료구조\n개정 4판");
        unit(TOC_MATERIAL, HASH_TOC, 2, "개정 4판 1쇄 발행 2022년 1월 5일\n지은이 이지영\n펴낸곳 한빛아카데미(주)\n"
                + "ISBN 979-11-5664-567-2 93000");
        unit(TOC_MATERIAL, HASH_TOC, 3, "목차\nCHAPTER 01 자료구조와 알고리즘 ........ 13\n1.1 자료와 정보 ........ 14\n"
                + "1.2 자료구조의 분류 ........ 17\nCHAPTER 02 배열 ........ 41\n2.1 배열의 개념 ........ 42\n"
                + "2.2 희소 행렬 ........ 50");
        unit(TOC_MATERIAL, HASH_TOC, 4, "1장에서 우리는 자료구조의 필요성을 살펴보았다. 이번 장은 배열을 다룬다.");
        contentJob(TOC_MATERIAL, HASH_TOC);
        section(TOC_MATERIAL, HASH_TOC, "목차", 3);
    }

    private void readyLecture() throws Exception {
        insertMaterial(LECTURE, "3주차_스택.pdf", HASH_LECTURE);
        link(courseId, LECTURE, "PROFESSOR_SLIDE");
        contentJob(LECTURE, HASH_LECTURE);
        lectureSection = section(LECTURE, HASH_LECTURE, "스택 구현", 5);
    }

    private long topic(String title, Long parent, int order) {
        CourseTopic topic = CourseTopic.builder().userId(USER).courseId(courseId).parentTopicId(parent).title(title)
                .orderIndex(order).sourceType(TopicSourceType.SOURCE).status(TopicStatus.ACTIVE).build();
        topicMapper.insert(topic);
        return topic.getTopicId();
    }

    private long insertCourse(String title) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO courses (user_id, title, status) VALUES (?, ?, 'ACTIVE')",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, USER);
            ps.setString(2, title);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void insertMaterial(long materialId, String filename, String hash) throws Exception {
        exec("INSERT INTO course_materials (material_id, user_id, original_filename, stored_filename, "
                        + "storage_path, size_bytes, file_hash, extraction_status, extracted_text, status) "
                        + "VALUES (?, ?, ?, 'x.pdf', ?, 1, ?, 'SUCCESS', 'text', 'ACTIVE')",
                materialId, USER, filename, USER + "/x-" + materialId + ".pdf", hash);
    }

    private void link(long course, long materialId, String type) throws Exception {
        exec("INSERT INTO material_links (user_id, material_id, course_id, material_type) VALUES (?, ?, ?, ?)",
                USER, materialId, course, type);
    }

    private void unit(long materialId, String hash, int no, String text) throws Exception {
        exec("INSERT INTO material_text_units (user_id, material_id, file_hash, unit_index, unit_type, unit_no, "
                + "char_count, text) VALUES (?, ?, ?, ?, 'PDF_PAGE', ?, ?, ?)", USER, materialId, hash, no - 1, no,
                text.length(), text);
    }

    private void contentJob(long materialId, String hash) throws Exception {
        exec("INSERT INTO material_analysis_jobs (user_id, material_id, job_kind, file_hash, analysis_version, "
                        + "priority, status, attempt, max_attempts, total_chunks, completed_chunks, next_run_at, "
                        + "finished_at) VALUES (?, ?, 'CONTENT', ?, ?, 0, 'DONE', 1, 3, 1, 1, NOW(), NOW())",
                USER, materialId, hash, MaterialAnalysisJobService.ANALYSIS_VERSION);
    }

    private long section(long materialId, String hash, String title, int page) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO material_sections (user_id, material_id, file_hash, analysis_version, chunk_index, "
                             + "unit_type, unit_start, unit_end, display_title, roles_json, excerpt, dedupe_key, status) "
                             + "VALUES (?, ?, ?, ?, 0, 'PDF_PAGE', ?, ?, ?, '[\"CONCEPT\"]', ?, ?, 'ACTIVE')",
                     Statement.RETURN_GENERATED_KEYS)) {
            Object[] args = {USER, materialId, hash, MaterialAnalysisJobService.ANALYSIS_VERSION, page, page, title,
                    title + " 발췌", "flow-" + materialId + "-" + page};
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

    private long treeVersion() {
        return count("SELECT topic_tree_version FROM courses WHERE course_id = " + courseId);
    }

    private long count(String sql) {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        } catch (Exception e) {
            throw new IllegalStateException(e);
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
                "topic_class_progress", "course_scope_exclusions", "material_textbook_extracts",
                "execution_items", "topic_material_links", "course_topics", "material_analysis_jobs",
                "material_sections", "material_text_units", "material_week_assignments", "material_links",
                "course_materials", "courses", "users")) {
            exec("DELETE FROM " + table + " WHERE user_id = ?", USER);
        }
    }
}
