package com.jungwoo.project.memo.material.batch;

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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 업로드·분석 묶음.
 *
 * <p>고정하는 것:
 * <ul>
 *   <li><b>구성원은 만들 때 정해진다.</b> 분석 중에 파일을 더 올리면 새 묶음이 생기고, 기존 묶음의
 *       분모는 움직이지 않는다 — 80%가 30%로 떨어지지 않는다.</li>
 *   <li>올릴 수 없는 파일도 자리를 차지한다. 5개 골랐는데 3개만 보이면 무엇이 빠졌는지 알 수 없다.</li>
 *   <li>처리 진행률 100%는 "전부 성공"이 아니다. 성공/실패/제외를 따로 센다.</li>
 *   <li>화면을 떠났다 돌아오면 도는 묶음이 복원된다.</li>
 * </ul>
 *
 * <p>로컬 memo DB 필요. CI 제외.
 */
@SpringBootTest
class MaterialAnalysisBatchDbTest {

    private static final long USER = 999_000_403L;
    private static final long MATERIAL_A = 999_400_301L;
    private static final long MATERIAL_B = 999_400_302L;

    @Autowired
    private MaterialAnalysisBatchService batchService;
    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        cleanUp();
        insertMaterial(MATERIAL_A, "1주차.pdf", "SUCCESS");
        insertMaterial(MATERIAL_B, "스캔본.pdf", "FAILED_NO_TEXT");
    }

    @Test
    void 올릴_수_없는_파일도_자리를_차지하고_사유가_붙는다() {
        BatchResponse batch = batchService.create(USER, create(null,
                file("1주차.pdf", 1_000_000L), file("사진.png", 100L)));

        assertThat(batch.getItemCount()).isEqualTo(2);
        assertThat(batch.getItems()).extracting(BatchResponse.Item::getFilename)
                .containsExactly("1주차.pdf", "사진.png");
        BatchResponse.Item rejected = batch.getItems().get(1);
        assertThat(rejected.getUploadState()).isEqualTo("UNSUPPORTED");
        assertThat(rejected.getMessage()).isNotBlank();
        assertThat(rejected.isSettled()).isTrue();
    }

    @Test
    void 분석_중에_더_올려도_기존_묶음의_분모는_움직이지_않는다() {
        BatchResponse first = batchService.create(USER, create(null,
                file("a.pdf", 1L), file("b.pdf", 1L), file("c.pdf", 1L)));
        assertThat(first.getItemCount()).isEqualTo(3);

        // 같은 화면에서 두 개를 더 올린다 — 새 묶음이 생긴다.
        BatchResponse second = batchService.create(USER, create(null, file("d.pdf", 1L), file("e.pdf", 1L)));

        assertThat(second.getBatchId()).isNotEqualTo(first.getBatchId());
        assertThat(second.getItemCount()).isEqualTo(2);
        assertThat(batchService.get(USER, first.getBatchId()).getItemCount()).isEqualTo(3);
    }

    @Test
    void 업로드가_자리를_채우고_본문을_못_읽은_파일은_실패가_아니라_제외로_센다() {
        BatchResponse batch = batchService.create(USER, create(null,
                file("1주차.pdf", 1L), file("스캔본.pdf", 1L)));
        Long slotA = batch.getItems().get(0).getItemId();
        Long slotB = batch.getItems().get(1).getItemId();

        batchService.bindUpload(USER, slotA, MATERIAL_A);
        batchService.bindUpload(USER, slotB, MATERIAL_B);

        BatchResponse after = batchService.get(USER, batch.getBatchId());
        assertThat(after.getItems().get(0).getUploadState()).isEqualTo("UPLOADED");
        // 파일은 저장됐고 자료 행도 있다. 다만 읽을 본문이 없어 분석 대상이 아니다.
        assertThat(after.getItems().get(1).getUploadState()).isEqualTo("NO_TEXT");
        assertThat(after.getSkippedCount()).isEqualTo(1);
        assertThat(after.getFailedCount()).isZero();
    }

    @Test
    void 업로드가_실패한_자리는_남고_이유가_적힌다() {
        BatchResponse batch = batchService.create(USER, create(null, file("1주차.pdf", 1L)));
        Long slot = batch.getItems().get(0).getItemId();

        batchService.failUpload(USER, slot, "네트워크가 끊겼어요");

        BatchResponse after = batchService.get(USER, batch.getBatchId());
        assertThat(after.getItems()).hasSize(1);
        assertThat(after.getItems().get(0).getUploadState()).isEqualTo("UPLOAD_FAILED");
        assertThat(after.getItems().get(0).getMessage()).isEqualTo("네트워크가 끊겼어요");
        assertThat(after.getFailedCount()).isEqualTo(1);
    }

    @Test
    void 처리가_끝나면_100퍼센트지만_전부_성공이라는_뜻이_아니다() {
        BatchResponse batch = batchService.create(USER, create(null,
                file("1주차.pdf", 1L), file("사진.png", 1L)));
        batchService.bindUpload(USER, batch.getItems().get(0).getItemId(), MATERIAL_A);
        batchService.failUpload(USER, batch.getItems().get(1).getItemId(), "형식이 안 맞아요");

        BatchResponse after = batchService.get(USER, batch.getBatchId());

        // 분석 작업이 아직 없으므로 첫 자리는 차례 대기다 — 100%가 아니다.
        assertThat(after.getProcessedPercent()).isLessThan(100);
        assertThat(after.getStatus()).isEqualTo("ANALYZING");
    }

    @Test
    void 두_번째_업로드가_같은_자리에_들어오면_무시한다() {
        BatchResponse batch = batchService.create(USER, create(null, file("1주차.pdf", 1L)));
        Long slot = batch.getItems().get(0).getItemId();

        batchService.bindUpload(USER, slot, MATERIAL_A);
        batchService.bindUpload(USER, slot, MATERIAL_B);

        assertThat(batchService.get(USER, batch.getBatchId()).getItems().get(0).getMaterialId())
                .isEqualTo(MATERIAL_A);
    }

    @Test
    void 화면에_다시_들어오면_도는_묶음이_복원된다() {
        BatchResponse batch = batchService.create(USER, create(null, file("1주차.pdf", 1L)));
        batchService.bindUpload(USER, batch.getItems().get(0).getItemId(), MATERIAL_A);

        List<BatchResponse> open = batchService.listOpen(USER, null);

        assertThat(open).extracting(BatchResponse::getBatchId).contains(batch.getBatchId());
    }

    @Test
    void 전부_끝난_묶음은_복원_대상이_아니다() {
        // 지원하지 않는 파일 하나짜리 묶음은 만들자마자 더 할 일이 없다.
        BatchResponse batch = batchService.create(USER, create(null, file("사진.png", 1L)));

        assertThat(batch.getProcessedPercent()).isEqualTo(100);
        assertThat(batch.getStatus()).isEqualTo("FINISHED");
        assertThat(batch.getDoneCount()).isZero();
        assertThat(batch.getSkippedCount()).isEqualTo(1);
        assertThat(batchService.listOpen(USER, null))
                .extracting(BatchResponse::getBatchId).doesNotContain(batch.getBatchId());
    }

    @Test
    void 예상_시간은_묶음을_만들지_않고도_볼_수_있다() {
        var estimate = batchService.estimate(List.of(file("1주차.pdf", 2_000_000L)));

        assertThat(estimate.getFiles()).hasSize(1);
        assertThat(estimate.getMinSeconds()).isPositive();
        // 전송 시간은 이 숫자에 없다 — 화면이 그 말을 해야 한다.
        assertThat(estimate.isUploadTimeExcluded()).isTrue();
        assertThat(batchService.listOpen(USER, null)).isEmpty();
    }

    // ===== 준비 도구 =====

    private static BatchRequests.Create.File file(String name, Long size) {
        BatchRequests.Create.File f = new BatchRequests.Create.File();
        f.setFilename(name);
        f.setSizeBytes(size);
        return f;
    }

    private static BatchRequests.Create create(Long courseId, BatchRequests.Create.File... files) {
        BatchRequests.Create request = new BatchRequests.Create();
        request.setCourseId(courseId);
        request.setFiles(List.of(files));
        return request;
    }

    private void insertMaterial(long materialId, String filename, String extraction) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO course_materials (material_id, user_id, original_filename, stored_filename, "
                             + "storage_path, size_bytes, file_hash, extraction_status, extracted_text, status) "
                             + "VALUES (?, ?, ?, 'x.pdf', ?, 1000, ?, ?, ?, 'ACTIVE')")) {
            ps.setLong(1, materialId);
            ps.setLong(2, USER);
            ps.setString(3, filename);
            ps.setString(4, USER + "/x-" + materialId + ".pdf");
            ps.setString(5, String.valueOf(materialId).repeat(8).substring(0, 64));
            ps.setString(6, extraction);
            ps.setString(7, "SUCCESS".equals(extraction) ? "본문" : null);
            ps.executeUpdate();
        }
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            for (String sql : List.of(
                    "DELETE FROM material_analysis_batch_items WHERE user_id = ?",
                    "DELETE FROM material_analysis_batches WHERE user_id = ?",
                    "DELETE FROM material_analysis_jobs WHERE user_id = ?",
                    "DELETE FROM course_materials WHERE user_id = ?")) {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setLong(1, USER);
                    ps.executeUpdate();
                }
            }
        }
    }
}
