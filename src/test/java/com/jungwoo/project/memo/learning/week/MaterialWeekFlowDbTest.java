package com.jungwoo.project.memo.learning.week;

import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.learning.CourseTopicMapper;
import com.jungwoo.project.memo.learning.LearningMapService;
import com.jungwoo.project.memo.learning.domain.CourseTopic;
import com.jungwoo.project.memo.learning.domain.TopicSourceType;
import com.jungwoo.project.memo.learning.domain.TopicStatus;
import com.jungwoo.project.memo.learning.dto.LearningMapResponse;
import com.jungwoo.project.memo.learning.week.dto.MaterialWeekRequests;
import com.jungwoo.project.memo.learning.week.dto.MaterialWeekReviewResponse;
import com.jungwoo.project.memo.material.FileStorageService;
import com.jungwoo.project.memo.material.analysis.MaterialAnalysisJobService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 추천 → 사용자 확인 → 확정 자료-주차 → 학습 지도.
 *
 * <p>고정하는 것:
 * <ul>
 *   <li>추천만 나온 상태에서는 학습 지도에 주차가 없다. 사용자가 적용해야 생긴다.</li>
 *   <li>강의계획서는 "전체 참고"이고, 그 예정 진도("1~3주차 …")에 연결된 항목은 실제 주차 칸에 들어가지 않는다.</li>
 *   <li>드래그·선택으로 옮긴 주차는 다시 읽어도 그대로다.</li>
 *   <li>재분석이 다른 주차를 추천해도 확정은 바뀌지 않고, 다르다는 사실만 알린다.</li>
 *   <li>화면이 본 추천과 지금 추천이 다르면 "확인됨"으로 남기지 않는다.</li>
 *   <li>상위 항목이라는 이유만으로 하위 항목의 주차에 복제되지 않는다.</li>
 * </ul>
 *
 * <p>로컬 memo_test DB 필요. CI 제외.
 */
@SpringBootTest
class MaterialWeekFlowDbTest {

    private static final long USER = 999_000_701L;
    private static final long INTRO = 999_470_101L;
    private static final long LINUX = 999_470_102L;
    private static final long AWS = 999_470_103L;
    private static final long SYLLABUS = 999_470_104L;
    private static final long CONFLICTED = 999_470_105L;
    private static final long OLD_PDF = 999_470_106L;
    private static final long UNLINKED = 999_470_107L;
    private static final String HASH = "a".repeat(64);

    @Autowired
    private MaterialWeekService weekService;
    @Autowired
    private LearningMapService learningMapService;
    @Autowired
    private CourseTopicMapper topicMapper;
    @Autowired
    private FileStorageService fileStorageService;
    @Autowired
    private DataSource dataSource;

    private long courseId;
    private long awsMysqlSection;
    private long syllabusSection;
    private long parentTopic;
    private long childTopic;
    private long socketTopic;

    @BeforeEach
    void setUp() throws Exception {
        cleanUp();
        courseId = insertCourse();

        insertMaterial(INTRO, "01.수업소개_네트워크프로그래밍.pdf", null, true);
        section(INTRO, 1, "네트워크프로그래밍 강의 정보", null, "[\"ADMIN\"]", null);
        section(INTRO, 10, "다음 강의 내용", null, "[\"SCHEDULE\"]", "다음 강의 10 - 환경설정 - 네트워크와 소켓 프로그래밍");

        insertMaterial(LINUX, "2.리눅스_개요와_실습환경_구축_WSL2추가_v2.pdf", null, true);
        section(LINUX, 1, "리눅스 개요와 실습환경 구축", null, "[\"REFERENCE\",\"SCHEDULE\"]", null);
        section(LINUX, 36, "다음 시간 안내", null, "[\"SCHEDULE\"]", "다음 시간 • 리눅스 사용 기초 및 AWS");

        insertMaterial(AWS, "3.AWS_구성하기_SSH실습.pdf", "AWS 구성하기 + SSH 실습 (네트워크프로그래밍 3주차)", true);
        section(AWS, 1, "AWS 구성하기 수업 개요", null, "[\"REFERENCE\",\"SCHEDULE\"]", null);
        section(AWS, 35, "수업 서버 SSH 접속", null, "[\"EXAMPLE\"]", "2주차에 배포한 ~/w02 폴더");
        awsMysqlSection = section(AWS, 75, "EC2 내 MySQL 설치와 조회", null, "[\"CONCEPT\",\"EXAMPLE\"]", "실습 MySQL");

        insertMaterial(SYLLABUS, "네트워크프로그래밍.pdf", null, true);
        section(SYLLABUS, 1, "네트워크프로그래밍 과목 개요", null, "[\"CONCEPT\"]", null);
        syllabusSection = section(SYLLABUS, 2, "오리엔테이션과 기초 소켓 프로그래밍", "1~3주차",
                "[\"SCHEDULE\",\"CONCEPT\"]", "1~3주차");
        section(SYLLABUS, 3, "TCP 서버와 멀티스레드 실습", "4~7주차", "[\"SCHEDULE\"]", null);
        section(SYLLABUS, 4, "중간고사", "8주차", "[\"SCHEDULE\"]", null);
        section(SYLLABUS, 5, "입출력 모델·기말고사", "12~15주차", "[\"SCHEDULE\"]", null);

        for (long id : new long[]{INTRO, LINUX, AWS, SYLLABUS}) {
            link(id, id == SYLLABUS || id == INTRO ? "SYLLABUS" : "PROFESSOR_SLIDE");
            contentJob(id, null);
        }

        // 같은 구간이 상위·하위 항목에 함께 연결돼 있다. 강의계획서의 예정 진도에만 연결된 항목도 있다.
        parentTopic = topic("AWS 구성", null);
        childTopic = topic("EC2 내 MySQL", parentTopic);
        socketTopic = topic("소켓 처리하기", null);
        topicLink(parentTopic, AWS, awsMysqlSection);
        topicLink(childTopic, AWS, awsMysqlSection);
        topicLink(socketTopic, SYLLABUS, syllabusSection);
    }

    @Test
    void 추천만으로는_지도에_주차가_없고_적용하면_확정_자료_기준으로_생긴다() {
        MaterialWeekReviewResponse review = weekService.review(USER, courseId);

        assertSuggested(review, AWS, "WEEK", 3, "HIGH");
        assertSuggested(review, LINUX, "WEEK", 2, "MEDIUM");
        assertSuggested(review, INTRO, "WEEK", 1, "MEDIUM");
        assertSuggested(review, SYLLABUS, "COURSE_WIDE", null, "HIGH");
        assertThat(review.needsReview()).isEqualTo(4);

        LearningMapResponse before = learningMapService.map(USER, courseId);
        assertThat(before.weeks()).as("추천(확인 전)은 지도에 넣지 않는다").isEmpty();
        assertThat(before.weekReview().needsReview()).isEqualTo(4);

        MaterialWeekRequests.ApplyResult result = weekService.applySuggestions(USER, courseId,
                new MaterialWeekRequests.ApplySuggestions(expectedFrom(review)));
        assertThat(result.applied()).isEqualTo(4);
        assertThat(result.skipped()).isEmpty();

        LearningMapResponse after = learningMapService.map(USER, courseId);
        assertThat(after.weeks()).extracting(LearningMapResponse.Week::label).containsExactly("1주차", "2주차", "3주차");
        LearningMapResponse.Week third = after.weeks().get(2);
        assertThat(third.confirmed()).isTrue();
        assertThat(third.basis()).isEqualTo("CONFIRMED_MATERIAL");
        assertThat(third.materialIds()).containsExactly(AWS);
        // 하위 항목이 같은 구간을 갖고 있으니 상위 항목은 3주차에 따로 싣지 않는다.
        assertThat(third.topicIds()).containsExactly(childTopic);
        // 강의계획서의 "1~3주차 …"에 연결된 항목은 어느 실제 주차에도 없다.
        assertThat(after.weeks()).allSatisfy(w -> assertThat(w.topicIds()).doesNotContain(socketTopic));
        assertThat(after.weekReview().courseWide()).extracting(LearningMapResponse.WeekMaterial::materialId)
                .containsExactly(SYLLABUS);
        assertThat(third.materials()).extracting(LearningMapResponse.WeekMaterial::filename)
                .containsExactly("3.AWS_구성하기_SSH실습.pdf");
        assertThat(after.weekReview().needsReview()).isZero();

        MaterialWeekReviewResponse reviewed = weekService.review(USER, courseId);
        assertThat(item(reviewed, AWS).assignment().source()).isEqualTo("SUGGESTION");
        assertThat(item(reviewed, AWS).assignment().weeks()).containsExactly(3);
    }

    @Test
    void 직접_옮긴_주차는_다시_읽어도_유지되고_지도에_반영된다() {
        weekService.place(USER, courseId, AWS, new MaterialWeekRequests.Place("WEEK", List.of(2), "USER"));

        MaterialWeekReviewResponse review = weekService.review(USER, courseId);
        MaterialWeekReviewResponse.Item aws = item(review, AWS);
        assertThat(aws.assignment().placement()).isEqualTo("WEEK");
        assertThat(aws.assignment().weeks()).containsExactly(2);
        assertThat(aws.assignment().source()).isEqualTo("USER");
        // 추천(3주차)과 다르다는 것을 알리기만 한다.
        assertThat(aws.suggestionDiffers()).isTrue();

        LearningMapResponse map = learningMapService.map(USER, courseId);
        assertThat(map.weeks()).singleElement().satisfies(w -> {
            assertThat(w.label()).isEqualTo("2주차");
            assertThat(w.materialIds()).containsExactly(AWS);
        });
    }

    @Test
    void 재분석이_다른_주차를_추천해도_확정은_그대로다() throws Exception {
        weekService.place(USER, courseId, AWS, new MaterialWeekRequests.Place("WEEK", List.of(3), "SUGGESTION"));

        // 파일이 다시 처리되고 새 분석이 "4주차"라고 읽었다(같은 파일의 재분석은 그 작업의 결과를 덮어쓴다).
        exec("UPDATE course_materials SET document_title = 'AWS 실습 (4주차)' WHERE material_id = ?", AWS);
        exec("UPDATE material_analysis_jobs SET checkpoint_json = '{\"completedChunks\":[0],\"weekLabel\":\"4주차\"}' "
                + "WHERE material_id = ? AND job_kind = 'CONTENT'", AWS);

        MaterialWeekReviewResponse review = weekService.review(USER, courseId);
        MaterialWeekReviewResponse.Item aws = item(review, AWS);
        assertThat(aws.suggestion().week()).isEqualTo(4);
        assertThat(aws.assignment().weeks()).containsExactly(3);
        assertThat(aws.assignment().source()).isEqualTo("SUGGESTION");
        assertThat(aws.suggestionDiffers()).isTrue();
        assertThat(count("SELECT COUNT(*) FROM material_week_assignments WHERE material_id = " + AWS
                + " AND placement = 'WEEK' AND week_no = 3")).isEqualTo(1);
        assertThat(learningMapService.map(USER, courseId).weeks()).extracting(LearningMapResponse.Week::label)
                .containsExactly("3주차");
    }

    @Test
    void 화면이_본_추천과_지금_추천이_다르면_확인으로_남기지_않는다() {
        assertThatThrownBy(() -> weekService.place(USER, courseId, AWS,
                new MaterialWeekRequests.Place("WEEK", List.of(5), "SUGGESTION")))
                .isInstanceOf(ConflictException.class);
        assertThat(count("SELECT COUNT(*) FROM material_week_assignments WHERE user_id = " + USER)).isZero();
    }

    @Test
    void 일괄_적용은_충돌과_약한_추천과_이미_확인한_자료를_건너뛴다() throws Exception {
        insertMaterial(CONFLICTED, "2주차_AWS.pdf", "AWS 실습 (3주차)", true);
        link(CONFLICTED, "PROFESSOR_SLIDE");
        weekService.place(USER, courseId, INTRO, new MaterialWeekRequests.Place("WEEK", List.of(1), "USER"));

        MaterialWeekRequests.ApplyResult result = weekService.applySuggestions(USER, courseId,
                new MaterialWeekRequests.ApplySuggestions(List.of(
                        new MaterialWeekRequests.Expected(CONFLICTED, "WEEK", 2),
                        new MaterialWeekRequests.Expected(INTRO, "WEEK", 1),
                        new MaterialWeekRequests.Expected(AWS, "WEEK", 4))));

        assertThat(result.applied()).isZero();
        assertThat(result.skipped()).extracting(MaterialWeekRequests.Skipped::reason)
                .containsExactly("NOT_BULK", "ALREADY_PLACED", "CHANGED");
        assertThat(item(result.review(), CONFLICTED).suggestion().confidence()).isEqualTo("CONFLICT");
    }

    @Test
    void 여러_주차와_전체_참고와_주차_없음을_정할_수_있다() {
        weekService.place(USER, courseId, AWS, new MaterialWeekRequests.Place("WEEK", List.of(3, 1, 2), "USER"));
        weekService.place(USER, courseId, SYLLABUS, new MaterialWeekRequests.Place("COURSE_WIDE", List.of(), "USER"));
        MaterialWeekReviewResponse review = weekService.place(USER, courseId, LINUX,
                new MaterialWeekRequests.Place("UNASSIGNED", null, "USER"));

        assertThat(item(review, AWS).assignment().weeks()).containsExactly(1, 2, 3);
        assertThat(item(review, SYLLABUS).assignment().placement()).isEqualTo("COURSE_WIDE");
        assertThat(item(review, LINUX).assignment().placement()).isEqualTo("UNASSIGNED");
        // 주차 없음으로 확인한 자료는 다시 "확인 필요"로 세지 않는다.
        assertThat(review.needsReview()).isEqualTo(1);

        LearningMapResponse map = learningMapService.map(USER, courseId);
        assertThat(map.weeks()).extracting(LearningMapResponse.Week::label).containsExactly("1주차", "2주차", "3주차");
        assertThat(map.weeks()).allSatisfy(w -> assertThat(w.materialIds()).containsExactly(AWS));
    }

    @Test
    void 잘못된_자리와_연결되지_않은_자료는_거절한다() throws Exception {
        assertThatThrownBy(() -> weekService.place(USER, courseId, AWS,
                new MaterialWeekRequests.Place("WEEK", List.of(31), "USER"))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> weekService.place(USER, courseId, AWS,
                new MaterialWeekRequests.Place("WEEK", List.of(), "USER"))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> weekService.place(USER, courseId, AWS,
                new MaterialWeekRequests.Place("COURSE_WIDE", List.of(3), "USER"))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> weekService.place(USER, courseId, AWS,
                new MaterialWeekRequests.Place("SOMEWHERE", List.of(), "USER"))).isInstanceOf(BadRequestException.class);

        insertMaterial(UNLINKED, "다른 과목.pdf", null, true);
        assertThatThrownBy(() -> weekService.place(USER, courseId, UNLINKED,
                new MaterialWeekRequests.Place("WEEK", List.of(1), "USER"))).isInstanceOf(NotFoundException.class);
    }

    @Test
    void 속성_제목을_안_읽은_옛_자료는_확인_화면이_한_번_읽어_채운다() throws Exception {
        String storage = USER + "/old-" + OLD_PDF + ".pdf";
        Path file = fileStorageService.resolve(storage);
        Files.createDirectories(file.getParent());
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.getDocumentInformation().setTitle("리눅스 심화 (5주차)");
            document.save(file.toFile());
        }
        exec("INSERT INTO course_materials (material_id, user_id, original_filename, stored_filename, storage_path, "
                        + "size_bytes, file_hash, extraction_status, extracted_text, status) "
                        + "VALUES (?, ?, 'lecture.pdf', 'old.pdf', ?, 1, ?, 'SUCCESS', 'text', 'ACTIVE')",
                OLD_PDF, USER, storage, HASH);
        link(OLD_PDF, "PROFESSOR_SLIDE");

        MaterialWeekReviewResponse review = weekService.review(USER, courseId);

        assertSuggested(review, OLD_PDF, "WEEK", 5, "HIGH");
        assertThat(count("SELECT COUNT(*) FROM course_materials WHERE material_id = " + OLD_PDF
                + " AND document_title_read = 1 AND document_title = '리눅스 심화 (5주차)'")).isEqualTo(1);
    }

    // ===== 도우미 =====

    private static List<MaterialWeekRequests.Expected> expectedFrom(MaterialWeekReviewResponse review) {
        return review.items().stream()
                .filter(i -> i.assignment() == null && i.suggestion() != null && i.suggestion().bulkApplicable())
                .map(i -> new MaterialWeekRequests.Expected(i.materialId(), i.suggestion().placement(),
                        i.suggestion().week()))
                .toList();
    }

    private static void assertSuggested(MaterialWeekReviewResponse review, long materialId, String placement,
                                        Integer week, String confidence) {
        MaterialWeekReviewResponse.Suggestion s = item(review, materialId).suggestion();
        assertThat(s).as("자료 %d의 추천", materialId).isNotNull();
        assertThat(s.placement()).isEqualTo(placement);
        assertThat(s.week()).isEqualTo(week);
        assertThat(s.confidence()).isEqualTo(confidence);
    }

    private static MaterialWeekReviewResponse.Item item(MaterialWeekReviewResponse review, long materialId) {
        return review.items().stream().filter(i -> i.materialId() == materialId).findFirst().orElseThrow();
    }

    private long insertCourse() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO courses (user_id, title, status) VALUES (?, '네트워크프로그래밍', 'ACTIVE')",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, USER);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void insertMaterial(long id, String filename, String title, boolean titleRead) throws Exception {
        exec("INSERT INTO course_materials (material_id, user_id, original_filename, stored_filename, storage_path, "
                        + "size_bytes, file_hash, extraction_status, extracted_text, document_title, document_title_read, "
                        + "status) VALUES (?, ?, ?, 'x.pdf', ?, 1, ?, 'SUCCESS', 'text', ?, ?, 'ACTIVE')",
                id, USER, filename, USER + "/x-" + id + ".pdf", HASH, title, titleRead ? 1 : 0);
    }

    private void link(long materialId, String type) throws Exception {
        exec("INSERT INTO material_links (user_id, material_id, course_id, material_type) VALUES (?, ?, ?, ?)",
                USER, materialId, courseId, type);
    }

    private long section(long materialId, int page, String title, String label, String roles, String excerpt)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO material_sections (user_id, material_id, file_hash, analysis_version, chunk_index, "
                             + "unit_type, unit_start, unit_end, section_label, display_title, roles_json, excerpt, "
                             + "dedupe_key, status) VALUES (?, ?, ?, ?, 0, 'PDF_PAGE', ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')",
                     Statement.RETURN_GENERATED_KEYS)) {
            Object[] args = {USER, materialId, HASH, MaterialAnalysisJobService.ANALYSIS_VERSION, page, page, label,
                    title, roles, excerpt == null ? title : excerpt, "week-" + materialId + "-" + page};
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

    private void contentJob(long materialId, String weekLabel) throws Exception {
        String checkpoint = weekLabel == null ? "{\"completedChunks\":[0]}"
                : "{\"completedChunks\":[0],\"weekLabel\":\"" + weekLabel + "\"}";
        exec("INSERT INTO material_analysis_jobs (user_id, material_id, job_kind, file_hash, analysis_version, "
                        + "priority, status, attempt, max_attempts, total_chunks, completed_chunks, checkpoint_json, "
                        + "next_run_at, finished_at) VALUES (?, ?, 'CONTENT', ?, ?, 0, 'DONE', 1, 3, 1, 1, ?, NOW(), NOW())",
                USER, materialId, HASH, MaterialAnalysisJobService.ANALYSIS_VERSION, checkpoint);
    }

    private long topic(String title, Long parent) {
        CourseTopic topic = CourseTopic.builder().userId(USER).courseId(courseId).parentTopicId(parent).title(title)
                .orderIndex(0).sourceType(TopicSourceType.SOURCE).status(TopicStatus.ACTIVE).build();
        topicMapper.insert(topic);
        return topic.getTopicId();
    }

    private void topicLink(long topicId, long materialId, long sectionId) throws Exception {
        exec("INSERT INTO topic_material_links (user_id, course_id, topic_id, material_id, section_id, role, origin, status) "
                + "VALUES (?, ?, ?, ?, ?, 'CONCEPT', 'USER', 'ACTIVE')", USER, courseId, topicId, materialId, sectionId);
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
        for (String table : List.of("material_week_assignments", "material_analysis_jobs", "material_sections",
                "topic_material_links", "course_topics", "material_links", "course_materials", "courses")) {
            exec("DELETE FROM " + table + " WHERE user_id = ?", USER);
        }
    }
}
