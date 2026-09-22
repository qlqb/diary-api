package com.jungwoo.project.memo.material.batch;

import com.jungwoo.project.memo.material.analysis.MaterialAnalysisJobService;
import com.jungwoo.project.memo.material.analysis.MaterialAnalysisStatusService;
import com.jungwoo.project.memo.material.batch.dto.BatchRequests;
import com.jungwoo.project.memo.material.batch.dto.BatchResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 열린 묶음은 전부 보이고, 다시 시도하면 다시 열린다.
 *
 * <p>고치는 문제 셋.
 * <ol>
 *   <li><b>여섯 번째 묶음이 앞 묶음을 "끝냈다".</b> 열린 묶음 목록이 5개로 잘렸고, 화면은
 *       목록에서 빠진 묶음을 끝난 것으로 바꿨다. 40% 분석 중이던 묶음이 새 묶음 하나를 더
 *       만들었다는 이유로 "처리 종료"가 됐다. 목록에 없다는 것은 끝났다는 증거가 아니다.</li>
 *   <li><b>다시 시도해도 묶음이 다시 열리지 않았다.</b> 묶음 상태는 조회할 때 FINISHED로만
 *       옮겨졌고 되돌아가는 길이 없었다. 열린 목록은 저장된 상태로 거르므로, 한 번 끝난
 *       묶음은 실패 항목을 다시 돌려도 목록에 다시 나타나지 않았다.</li>
 *   <li><b>화면이 닫혀 올리지 못한 자리가 영원히 "대기"였다.</b> 파일 셋 중 둘을 올리고
 *       창을 닫으면 셋째 자리는 STAGED로 남아 묶음이 끝나지 않았다. 브라우저가 파일을
 *       잃었으므로 그 자리는 다시 올 수 없다.</li>
 * </ol>
 *
 * <p>로컬 memo DB 필요. CI 제외.
 */
@SpringBootTest
class MaterialAnalysisBatchReopenDbTest {

    private static final long USER = 999_000_407L;
    private static final long MATERIAL_BASE = 999_400_700L;
    private static final String HASH = "d".repeat(64);

    @Autowired
    private MaterialAnalysisBatchService batchService;
    @Autowired
    private MaterialAnalysisBatchMapper batchMapper;
    @Autowired
    private MaterialAnalysisStatusService statusService;
    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        cleanUp();
    }

    // ===== 1. 열린 묶음이 몇 개든 전부 =====

    @Test
    void 열린_묶음이_여섯_개여도_페이지를_넘기면_전부_보인다() throws Exception {
        Set<Long> created = new HashSet<>();
        for (int i = 0; i < 6; i++) {
            created.add(analyzingBatch(MATERIAL_BASE + i));
        }

        Set<Long> seen = new HashSet<>();
        Long cursor = null;
        int pages = 0;
        do {
            BatchResponse.Page page = batchService.listOpenPage(USER, null, cursor, 4);
            page.getBatches().forEach(b -> seen.add(b.getBatchId()));
            assertThat(page.getTotalOpen()).isEqualTo(6);
            cursor = page.getNextCursor();
            pages++;
        } while (cursor != null && pages < 5);

        assertThat(seen).as("목록에서 빠진 묶음은 화면이 끝났다고 추측하게 된다").containsAll(created);
        assertThat(pages).isEqualTo(2);
    }

    // ===== 2. 다시 시도하면 다시 열린다 =====

    @Test
    void 끝난_묶음의_실패_항목을_다시_시도하면_열린_묶음으로_돌아온다() throws Exception {
        long material = MATERIAL_BASE + 10;
        insertMaterial(material, "SUCCESS");
        insertContentJob(material, "FAILED");
        long batchId = boundBatch(material);

        // 실패로 끝났다. 조회가 FINISHED로 옮긴다.
        BatchResponse finished = batchService.get(USER, batchId);
        assertThat(finished.getStatus()).isEqualTo("FINISHED");
        assertThat(finished.getFailedCount()).isEqualTo(1);

        // 실패한 자료를 다시 돌린다.
        statusService.retry(USER, material);

        List<Long> open = openIds();
        assertThat(open).as("다시 돌리는 중인 묶음은 화면이 돌아왔을 때 복원돼야 한다").contains(batchId);
        BatchResponse reopened = batchService.get(USER, batchId);
        assertThat(reopened.getStatus()).isEqualTo("ANALYZING");
        assertThat(reopened.getFinishedAt()).as("다시 열린 묶음에 옛 종료 시각이 남으면 안 된다").isNull();
        assertThat(reopened.getProcessedPercent()).isLessThan(100);
    }

    @Test
    void 다시_끝나면_새_종료_시각을_받는다() throws Exception {
        long material = MATERIAL_BASE + 11;
        insertMaterial(material, "SUCCESS");
        insertContentJob(material, "FAILED");
        long batchId = boundBatch(material);
        java.time.LocalDateTime firstFinish = batchService.get(USER, batchId).getFinishedAt();

        statusService.retry(USER, material);
        batchService.get(USER, batchId);
        setJobStatus(material, "DONE");

        BatchResponse again = batchService.get(USER, batchId);
        assertThat(again.getStatus()).isEqualTo("FINISHED");
        assertThat(again.getDoneCount()).isEqualTo(1);
        assertThat(again.getFailedCount()).isZero();
        assertThat(again.getFinishedAt()).isNotNull();
        assertThat(again.getFinishedAt()).isAfterOrEqualTo(firstFinish);
        assertThat(batchMapper.findByIdAndUserId(batchId, USER).getFinishedAt()).isNotNull();
    }

    @Test
    void 성공한_항목은_다시_분석하지_않는다() throws Exception {
        long ok = MATERIAL_BASE + 12;
        long bad = MATERIAL_BASE + 13;
        insertMaterial(ok, "SUCCESS");
        insertMaterial(bad, "SUCCESS");
        insertContentJob(ok, "DONE");
        insertContentJob(bad, "FAILED");
        boundBatch(ok, bad);

        statusService.retry(USER, bad);

        assertThat(jobStatus(ok)).as("성공한 자료의 작업은 그대로다").isEqualTo("DONE");
        assertThat(jobStatus(bad)).isEqualTo("QUEUED");
    }

    @Test
    void 본문을_못_읽었던_자료를_다시_추출해_성공하면_더는_제외로_세지_않는다() throws Exception {
        long material = MATERIAL_BASE + 14;
        insertMaterial(material, "FAILED_NO_TEXT");
        long batchId = boundBatch(material);
        assertThat(batchService.get(USER, batchId).getSkippedCount()).isEqualTo(1);

        // 추출을 다시 해 성공했다(같은 파일, 이번엔 본문이 나왔다) → 내용 분석이 줄을 선다.
        setExtraction(material, "SUCCESS");
        insertContentJob(material, "QUEUED");
        batchMapper.reopenFinishedContaining(USER, material);

        BatchResponse after = batchService.get(USER, batchId);
        assertThat(after.getSkippedCount()).as("업로드 순간의 판정에 묶여 있으면 다시 추출해도 영영 제외다")
                .isZero();
        assertThat(after.getStatus()).isEqualTo("ANALYZING");
    }

    // ===== 3. 올리다 만 자리 =====

    @Test
    void 화면이_닫혀_올리지_못한_자리는_다시_올려야_한다고_표시되고_묶음은_끝난다() throws Exception {
        long material = MATERIAL_BASE + 20;
        insertMaterial(material, "SUCCESS");
        insertContentJob(material, "DONE");
        BatchResponse batch = batchService.create(USER, create(file("1주차.pdf"), file("2주차.pdf")));
        batchService.bindUpload(USER, batch.getItems().get(0).getItemId(), material);
        // 둘째 파일은 올라오지 않았다. 창이 닫혔다.
        ageItems(batch.getBatchId(), 3);

        batchService.abandonStale();

        BatchResponse after = batchService.get(USER, batch.getBatchId());
        BatchResponse.Item orphan = after.getItems().get(1);
        assertThat(orphan.isSettled()).isTrue();
        assertThat(orphan.getMessage()).contains("다시");
        // 서버가 받은 자료는 계속 분석된 결과로 남는다.
        assertThat(after.getItems().get(0).getStage()).isEqualTo("DONE");
        assertThat(after.getStatus()).isEqualTo("FINISHED");
        assertThat(after.getDoneCount()).isEqualTo(1);
    }

    @Test
    void 방금_만든_묶음의_대기_자리는_건드리지_않는다() throws Exception {
        BatchResponse batch = batchService.create(USER, create(file("1주차.pdf")));

        batchService.abandonStale();

        assertThat(batchService.get(USER, batch.getBatchId()).getItems().get(0).isSettled()).isFalse();
    }

    // ===== 준비 도구 =====

    private List<Long> openIds() {
        List<Long> out = new ArrayList<>();
        Long cursor = null;
        do {
            BatchResponse.Page page = batchService.listOpenPage(USER, null, cursor, 50);
            page.getBatches().forEach(b -> out.add(b.getBatchId()));
            cursor = page.getNextCursor();
        } while (cursor != null);
        return out;
    }

    private long analyzingBatch(long material) throws Exception {
        insertMaterial(material, "SUCCESS");
        insertContentJob(material, "RUNNING");
        return boundBatch(material);
    }

    private long boundBatch(long... materials) {
        BatchRequests.Create.File[] files = new BatchRequests.Create.File[materials.length];
        for (int i = 0; i < materials.length; i++) {
            files[i] = file("자료" + materials[i] + ".pdf");
        }
        BatchResponse batch = batchService.create(USER, create(files));
        for (int i = 0; i < materials.length; i++) {
            batchService.bindUpload(USER, batch.getItems().get(i).getItemId(), materials[i]);
        }
        return batch.getBatchId();
    }

    private static BatchRequests.Create.File file(String name) {
        BatchRequests.Create.File f = new BatchRequests.Create.File();
        f.setFilename(name);
        f.setSizeBytes(1_000L);
        return f;
    }

    private static BatchRequests.Create create(BatchRequests.Create.File... files) {
        BatchRequests.Create request = new BatchRequests.Create();
        request.setFiles(List.of(files));
        return request;
    }

    private void insertMaterial(long materialId, String extraction) throws Exception {
        exec("INSERT INTO course_materials (material_id, user_id, original_filename, stored_filename, "
                        + "storage_path, size_bytes, file_hash, extraction_status, extracted_text, status) "
                        + "VALUES (?, ?, ?, 'x.pdf', ?, 1000, ?, ?, ?, 'ACTIVE')",
                materialId, USER, "자료" + materialId + ".pdf", USER + "/x-" + materialId + ".pdf", HASH,
                extraction, "SUCCESS".equals(extraction) ? "본문" : null);
    }

    private void insertContentJob(long materialId, String status) throws Exception {
        exec("INSERT INTO material_analysis_jobs (user_id, material_id, job_kind, file_hash, analysis_version, "
                        + "priority, status, attempt, max_attempts, total_chunks, completed_chunks, next_run_at, "
                        + "finished_at) VALUES (?, ?, 'CONTENT', ?, ?, 0, ?, 1, 3, 2, ?, NOW(), "
                        + "IF(? IN ('DONE','FAILED'), NOW(), NULL))",
                USER, materialId, HASH, MaterialAnalysisJobService.ANALYSIS_VERSION, status,
                "RUNNING".equals(status) ? 1 : 0, status);
    }

    private void setJobStatus(long materialId, String status) throws Exception {
        exec("UPDATE material_analysis_jobs SET status = ?, finished_at = NOW() WHERE material_id = ? AND user_id = ?",
                status, materialId, USER);
    }

    private void setExtraction(long materialId, String status) throws Exception {
        exec("UPDATE course_materials SET extraction_status = ?, extracted_text = '본문' "
                + "WHERE material_id = ? AND user_id = ?", status, materialId, USER);
    }

    private String jobStatus(long materialId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT status FROM material_analysis_jobs WHERE material_id = ? AND user_id = ? "
                             + "AND job_kind = 'CONTENT'")) {
            ps.setLong(1, materialId);
            ps.setLong(2, USER);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    /** 자리를 hours시간 전에 만든 것으로 돌린다. 시간이 흐르는 것을 기다리지 않는다. */
    private void ageItems(long batchId, int hours) throws Exception {
        exec("UPDATE material_analysis_batch_items SET created_at = NOW() - INTERVAL ? HOUR, "
                + "updated_at = NOW() - INTERVAL ? HOUR WHERE batch_id = ? AND user_id = ?", hours, hours, batchId, USER);
        exec("UPDATE material_analysis_batches SET created_at = NOW() - INTERVAL ? HOUR "
                + "WHERE batch_id = ? AND user_id = ?", hours, batchId, USER);
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
        for (String sql : List.of(
                "DELETE FROM material_analysis_timings WHERE user_id = ?",
                "DELETE FROM material_analysis_batch_items WHERE user_id = ?",
                "DELETE FROM material_analysis_batches WHERE user_id = ?",
                "DELETE FROM material_analysis_jobs WHERE user_id = ?",
                "DELETE FROM course_materials WHERE user_id = ?")) {
            exec(sql, USER);
        }
    }
}
