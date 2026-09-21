package com.jungwoo.project.memo.material.analysis;

import com.jungwoo.project.memo.ai.AiConsultationClient;
import com.jungwoo.project.memo.ai.AiStreamParser;
import com.jungwoo.project.memo.learning.structure.TopicChangeProposalService;
import com.jungwoo.project.memo.material.MaterialAnalysisJobMapper;
import com.jungwoo.project.memo.material.MaterialLinkMapper;
import com.jungwoo.project.memo.material.MaterialSectionMapper;
import com.jungwoo.project.memo.material.MaterialService;
import com.jungwoo.project.memo.material.domain.AnalysisJobStatus;
import com.jungwoo.project.memo.material.domain.MaterialAnalysisJob;
import com.jungwoo.project.memo.material.domain.MaterialLink;
import com.jungwoo.project.memo.material.domain.MaterialSection;
import com.jungwoo.project.memo.material.domain.MaterialType;
import com.jungwoo.project.memo.material.domain.TextUnitType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import reactor.core.publisher.Flux;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * 모델 응답을 기다리는 동안 자료가 지워지거나 임대가 다른 worker로 넘어간 뒤 응답이 돌아오는 경합.
 * 결과(구간·과제 후보·변경안)가 임대 확인과 같은 트랜잭션에서, 작업 행을 잠근 채 쓰이는지 실제 DB에서 본다.
 *
 * <p>수정 전 코드에서는 CONTENT 두 시나리오가 실패한다(구간 저장이 임대 확인보다 먼저였다). LINK 두 시나리오는
 * "임대 확인 → 변경안 저장" 사이의 틈을 재현하기 위해 변경안 저장 직전에 삭제·재선점을 끼워 넣는다(spy) —
 * 이 틈은 수정 전에도 renew로 확인은 했지만 저장까지 잠그지 않아 새 PROPOSED 변경안이 생겼다.
 *
 * <p>검증 기준: 삭제 뒤에는 늦은 응답으로 결과가 "다시 생기지" 않아야 한다(기존 데이터가 전부 없어야 한다는 뜻이 아니다).
 * 임대 교체에서는 새 worker의 정상 결과만 열린 상태로 남아야 한다. 로컬 memo DB 필요. CI 제외.
 *
 * <p>(2026-09-21) LINK 시나리오 둘은 레거시 경로를 켠 채 돈다 — 그 경로는 기본적으로 꺼져 있지만
 * (프로젝트 단위 정리로 옮겼다) 임대·결과 저장의 경합 불변식은 여기서 계속 지킨다. 같은 불변식을
 * 새 경로에서 보는 것은 {@code ProjectTidyRaceDbTest}다.
 */
@SpringBootTest
@TestPropertySource(properties = "material.analysis.link-jobs.enabled=true")
class MaterialAnalysisResultRaceDbTest {

    private static final long USER = 999_000_304L;
    private static final long MATERIAL = 999_300_301L;
    private static final String HASH = "d".repeat(64);
    private static final long WAIT_MS = 1500;

    @Autowired
    private MaterialAnalysisJobService jobService;
    @Autowired
    private MaterialAnalysisJobMapper jobMapper;
    @Autowired
    private MaterialAnalysisJobRunner runner;
    @Autowired
    private MaterialContentAnalyzer contentAnalyzer;
    @Autowired
    private TopicLinkAnalyzer linkAnalyzer;
    @Autowired
    private MaterialService materialService;
    @Autowired
    private MaterialLinkMapper materialLinkMapper;
    @Autowired
    private MaterialSectionMapper sectionMapper;
    @Autowired
    private DataSource dataSource;

    @MockitoBean
    private AiConsultationClient aiClient;
    @MockitoSpyBean
    private TopicChangeProposalService proposalService;

    private ExecutorService other;

    @BeforeEach
    void setUp() throws Exception {
        other = Executors.newSingleThreadExecutor();
        clearRows();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO course_materials (material_id, user_id, original_filename, stored_filename, storage_path, size_bytes, "
                             + "file_hash, extraction_status, extracted_text, status) VALUES (?, ?, 'race.pdf', 'race.pdf', ?, 1, ?, 'SUCCESS', ?, 'ACTIVE')")) {
            ps.setLong(1, MATERIAL);
            ps.setLong(2, USER);
            ps.setString(3, USER + "/missing-" + MATERIAL + ".pdf");
            ps.setString(4, HASH);
            ps.setString(5, "연결 리스트 삭제 구현을 설명한다.\n\n실습: 삭제 함수를 구현하고 실행 결과를 제출하세요.");
            ps.executeUpdate();
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        other.shutdownNow();
        clearRows();
    }

    private void clearRows() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            for (String sql : List.of(
                    "DELETE FROM material_analysis_jobs WHERE user_id = ?",
                    "DELETE FROM material_text_units WHERE user_id = ?",
                    "DELETE FROM course_assignments WHERE user_id = ?",
                    "DELETE FROM topic_change_proposals WHERE user_id = ?",
                    "DELETE FROM topic_material_links WHERE user_id = ?",
                    "DELETE FROM material_sections WHERE user_id = ?",
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

    // ===== CONTENT =====

    @Test
    void 응답_대기_중_자료가_지워지면_구간과_과제_후보가_다시_생기지_않는다() {
        MaterialAnalysisJob jobA = claimContent("A");
        when(aiClient.streamTurn(any(), any(), any())).thenAnswer(inv -> {
            materialService.delete(USER, MATERIAL); // 모델이 답하는 동안 사용자가 자료를 지웠다
            return Flux.just(response(contentPayload("A-실습 구간")));
        });

        AnalysisOutcome outcome = contentAnalyzer.analyze(jobA);

        assertThat(outcome.leaseLost()).isTrue();
        assertThat(count("SELECT COUNT(*) FROM material_sections WHERE material_id = ?", MATERIAL)).isZero();
        assertThat(count("SELECT COUNT(*) FROM course_assignments WHERE material_id = ?", MATERIAL)).isZero();
        assertThat(jobMapper.findById(jobA.getJobId()).getStatus()).isEqualTo(AnalysisJobStatus.CANCELLED);
    }

    @Test
    void 응답_대기_중_임대가_넘어가면_이전_worker의_구간은_막히고_새_worker의_결과만_남는다() {
        MaterialAnalysisJob jobA = claimContent("A");
        AtomicInteger calls = new AtomicInteger();
        when(aiClient.streamTurn(any(), any(), any())).thenAnswer(inv -> {
            if (calls.getAndIncrement() == 0) {
                // A가 멈춘 사이 임대가 만료되고 B가 같은 작업을 잡아 끝까지 처리한다.
                expireLease(jobA.getJobId());
                MaterialAnalysisJob jobB = claimContent("B");
                assertThat(jobB.getLeaseToken()).isNotEqualTo(jobA.getLeaseToken());
                assertThat(runner.run(jobB)).isEqualTo(MaterialAnalysisJobRunner.Result.COMPLETED);
                return Flux.just(response(contentPayload("A-실습 구간"))); // 그 뒤에 A의 응답이 돌아온다
            }
            return Flux.just(response(contentPayload("B-실습 구간")));
        });

        AnalysisOutcome outcome = contentAnalyzer.analyze(jobA);

        assertThat(outcome.leaseLost()).isTrue();
        assertThat(strings("SELECT display_title FROM material_sections WHERE material_id = ?", MATERIAL))
                .containsExactly("B-실습 구간");
        assertThat(strings("SELECT title FROM course_assignments WHERE material_id = ?", MATERIAL))
                .containsExactly("B-실습 구간");
        assertThat(jobMapper.findById(jobA.getJobId()).getStatus()).isEqualTo(AnalysisJobStatus.DONE);
    }

    // ===== LINK =====

    @Test
    void 변경안_저장_직전에_자료가_지워지면_열린_변경안이_남지_않는다() throws Exception {
        long courseId = linkedCourse();
        long sectionId = section("연결 리스트 삭제");
        MaterialAnalysisJob jobA = claimLink(courseId);
        when(aiClient.streamTurn(any(), any(), any()))
                .thenAnswer(inv -> Flux.just(response(linkPayload("A-새 항목", sectionId))));
        List<Future<?>> pending = new ArrayList<>();
        doAnswer(inv -> {
            // 임대 확인은 지났고 저장만 남은 순간에 삭제가 들어온다. 수정 뒤에는 작업 행 잠금 때문에 삭제가 기다린다.
            pending.add(waitBriefly(other.submit(() -> materialService.delete(USER, MATERIAL))));
            return inv.callRealMethod();
        }).when(proposalService).create(any(), any(), any(), any(), any(), any(), any(), any());

        linkAnalyzer.analyze(jobA);
        for (Future<?> f : pending) {
            f.get(30, TimeUnit.SECONDS);
        }

        assertThat(count("SELECT COUNT(*) FROM topic_change_proposals WHERE material_id = ? AND status = 'PROPOSED'", MATERIAL))
                .isZero();
        assertThat(jobMapper.findById(jobA.getJobId()).getStatus()).isEqualTo(AnalysisJobStatus.CANCELLED);
    }

    @Test
    void 변경안_저장_직전에_임대가_넘어가면_새_worker의_변경안만_열린_상태로_남는다() throws Exception {
        long courseId = linkedCourse();
        long sectionId = section("연결 리스트 삭제");
        MaterialAnalysisJob jobA = claimLink(courseId);
        AtomicInteger calls = new AtomicInteger();
        when(aiClient.streamTurn(any(), any(), any())).thenAnswer(inv ->
                Flux.just(response(linkPayload(calls.getAndIncrement() == 0 ? "A-새 항목" : "B-새 항목", sectionId))));
        AtomicBoolean first = new AtomicBoolean(true);
        List<Future<?>> pending = new ArrayList<>();
        doAnswer(inv -> {
            if (first.getAndSet(false)) {
                pending.add(waitBriefly(other.submit(() -> {
                    expireLease(jobA.getJobId());
                    MaterialAnalysisJob jobB = claimLinkAs("B", courseId);
                    assertThat(runner.run(jobB)).isEqualTo(MaterialAnalysisJobRunner.Result.COMPLETED);
                    return null;
                })));
            }
            return inv.callRealMethod();
        }).when(proposalService).create(any(), any(), any(), any(), any(), any(), any(), any());

        linkAnalyzer.analyze(jobA);
        for (Future<?> f : pending) {
            f.get(30, TimeUnit.SECONDS);
        }

        List<String> open = strings("SELECT ops_json FROM topic_change_proposals WHERE material_id = ? AND status = 'PROPOSED'", MATERIAL);
        assertThat(open).hasSize(1);
        assertThat(open.get(0)).contains("B-새 항목").doesNotContain("A-새 항목");
        assertThat(jobMapper.findById(jobA.getJobId()).getStatus()).isEqualTo(AnalysisJobStatus.DONE);
    }

    // ===== 준비 =====

    private MaterialAnalysisJob claimContent(String owner) {
        jobService.enqueueContent(com.jungwoo.project.memo.material.domain.CourseMaterial.builder()
                .materialId(MATERIAL).userId(USER).fileHash(HASH).extractedText("x").build(), 0);
        return claimOne(owner);
    }

    private MaterialAnalysisJob claimLink(long courseId) {
        jobService.enqueueLink(USER, MATERIAL, courseId, HASH, 0);
        return claimOne("A");
    }

    private MaterialAnalysisJob claimLinkAs(String owner, long courseId) {
        return claimOne(owner);
    }

    private MaterialAnalysisJob claimOne(String owner) {
        List<MaterialAnalysisJob> claimed = jobService.claimNext(owner, 1);
        assertThat(claimed).as("선점된 작업").hasSize(1);
        return claimed.get(0);
    }

    private long linkedCourse() throws Exception {
        long courseId;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO courses (user_id, title, status) VALUES (?, '경합 테스트 과목', 'ACTIVE')",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, USER);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                courseId = rs.getLong(1);
            }
        }
        materialLinkMapper.insert(MaterialLink.builder().userId(USER).materialId(MATERIAL).courseId(courseId)
                .materialType(MaterialType.PROFESSOR_SLIDE).build());
        return courseId;
    }

    private long section(String title) {
        MaterialSection section = MaterialSection.builder().userId(USER).materialId(MATERIAL).fileHash(HASH)
                .analysisVersion(MaterialAnalysisJobService.ANALYSIS_VERSION).chunkIndex(0)
                .unitType(TextUnitType.TEXT_BLOCK).unitStart(1).unitEnd(1).displayTitle(title)
                .rolesJson("[\"CONCEPT\"]").dedupeKey("race:" + title).build();
        sectionMapper.insertIgnore(section);
        return section.getSectionId();
    }

    /** 다른 스레드의 작업을 잠깐 기다린다. 잠금에 걸려 못 끝나면(수정 뒤의 정상 동작) 그대로 두고 돌아온다. */
    private static Future<?> waitBriefly(Future<?> future) throws Exception {
        try {
            future.get(WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ignored) {
            // 잠금 대기 중 — 호출자가 커밋한 뒤에 풀린다.
        }
        return future;
    }

    private void expireLease(long jobId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE material_analysis_jobs SET lease_until = NOW() - INTERVAL 1 HOUR WHERE job_id = ?")) {
            ps.setLong(1, jobId);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ===== 모델 응답 =====

    private static ChatResponse response(String structuredJson) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(
                "읽었다.\n" + AiStreamParser.DELIMITER + "\n" + structuredJson))));
    }

    private static String contentPayload(String title) {
        return "{\"sections\":[{\"unitStart\":1,\"unitEnd\":1,\"title\":\"" + title + "\",\"roles\":[\"EXERCISE\"],"
                + "\"excerpt\":\"삭제 함수를 구현하고 실행 결과를 제출하세요\",\"assignmentCue\":true,"
                + "\"assignmentQuote\":\"실행 결과를 제출하세요\",\"dates\":[]}],\"docMeta\":{}}";
    }

    private static String linkPayload(String title, long sectionId) {
        return "{\"ops\":[{\"op\":\"ADD\",\"title\":\"" + title + "\",\"sourceType\":\"SOURCE\",\"sectionIds\":[" + sectionId
                + "],\"role\":\"CONCEPT\"}],\"assignments\":[],\"summary\":\"새 항목 하나\"}";
    }

    // ===== 조회 =====

    private long count(String sql, long id) {
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> strings(String sql, long id) {
        List<String> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return out;
    }
}
