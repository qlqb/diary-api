package com.jungwoo.project.memo.material.zip;

import com.jungwoo.project.memo.material.FileStorageService;
import com.jungwoo.project.memo.material.batch.MaterialAnalysisBatchService;
import com.jungwoo.project.memo.material.batch.dto.BatchResponse;
import com.jungwoo.project.memo.material.domain.MaterialType;
import com.jungwoo.project.memo.material.zip.domain.ZipEntryStatus;
import com.jungwoo.project.memo.material.zip.domain.ZipImport;
import com.jungwoo.project.memo.material.zip.domain.ZipImportEntry;
import com.jungwoo.project.memo.material.zip.domain.ZipImportStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 압축으로 가져온 자료도 같은 분석 진행 흐름에 들어간다.
 *
 * <p>2026-09-21 판의 빈자리: 파일을 골라 올리면 분석 묶음(예상 시간·진행 카드)이 생겼지만,
 * 압축으로 들여온 자료는 각자 분석될 뿐 어느 묶음에도 없었다. 사용자는 압축 안의 파일이 지금
 * 분석 중인지, 무엇이 실패했는지를 한곳에서 볼 수 없었다.
 *
 * <p>여기서 보는 것:
 * <ul>
 *   <li>탐색 중(무엇이 들었는지 모를 때)에는 묶음이 없다 — 억지 퍼센트를 만들지 않는다.</li>
 *   <li>확정한 순간 확정한 항목만으로 분모가 고정된 묶음이 생긴다.</li>
 *   <li>같은 이름의 파일은 압축 안의 경로로 구분된다. 결과가 엇갈려 붙지 않는다.</li>
 *   <li>일부 실패는 그 자리만 실패다. 나중에 더 확정하면 새 묶음이다.</li>
 *   <li>다시 가져오면 자리가 대기로 돌아가고 끝난 묶음이 다시 열린다.</li>
 *   <li>취소하면 남은 자리를 거둔다.</li>
 *   <li>프로젝트에 가져온 자료는 일반 업로드와 같이 프로젝트에 연결된다(정리 대상이 된다).</li>
 * </ul>
 *
 * <p>실제 ZIP 파일을 만들어 실제 가져오기 작업자로 푼다. 추출 경로 검사·크기 한도는 그대로다.
 *
 * <p>로컬 memo DB 필요. CI 제외.
 */
@SpringBootTest
class MaterialZipBatchDbTest {

    private static final long USER = 999_000_602L;

    @Autowired
    private MaterialZipImportService importService;
    @Autowired
    private MaterialZipImportWorker worker;
    @Autowired
    private MaterialAnalysisBatchService batchService;
    @Autowired
    private ZipImportMapper importMapper;
    @Autowired
    private ZipImportEntryMapper entryMapper;
    @Autowired
    private FileStorageService fileStorageService;
    @Autowired
    private DataSource dataSource;

    private Long importId;
    private Long courseId;

    @BeforeEach
    void setUp() throws Exception {
        cleanUp();
        courseId = insertCourse();
        // 압축에는 run.sh가 두 개 있다(폴더가 다르다). 과제2/run.sh는 목록에만 있고 실제로는 빠져 있다 —
        // 그 항목의 가져오기는 실패한다.
        String storagePath = "zip-imports/" + USER + "/t.zip";
        writeZip(fileStorageService.resolve(storagePath), List.of(
                new String[]{"과제1/run.sh", "echo 과제1"},
                new String[]{"notes/readme.sh", "echo 노트"}));
        ZipImport zipImport = ZipImport.builder()
                .userId(USER).courseId(courseId).materialType(MaterialType.PROFESSOR_SLIDE)
                .originalFilename("과제모음.zip").storagePath(storagePath)
                .sizeBytes(1024L).fileHash("c".repeat(64))
                .status(ZipImportStatus.READY).entryCount(3).selectableCount(3)
                .expiresAt(LocalDateTime.now().plusHours(24))
                .build();
        importMapper.insert(zipImport);
        importId = zipImport.getImportId();
    }

    @Test
    void 무엇이_들었는지_확정하기_전에는_분석_묶음이_없다() {
        entry(0, "과제1/run.sh");

        assertThat(openBatches()).isEmpty();
    }

    @Test
    void 확정하면_고른_항목만으로_분모가_고정된_묶음이_생기고_같은_이름은_경로로_구분된다() {
        ZipImportEntry a = entry(0, "과제1/run.sh");
        ZipImportEntry b = entry(1, "과제2/run.sh");
        entry(2, "notes/readme.sh");

        importService.confirm(USER, importId, List.of(a.getEntryId(), b.getEntryId()));

        List<BatchResponse> open = openBatches();
        assertThat(open).hasSize(1);
        BatchResponse batch = open.get(0);
        assertThat(batch.getItemCount()).isEqualTo(2);
        assertThat(batch.getZipImportId()).isEqualTo(importId);
        assertThat(batch.getSourceArchiveName()).isEqualTo("과제모음.zip");
        assertThat(batch.getCourseId()).isEqualTo(courseId);
        assertThat(batch.getItems()).extracting(BatchResponse.Item::getFilename)
                .containsExactly("run.sh", "run.sh");
        assertThat(batch.getItems()).extracting(BatchResponse.Item::getSourcePath)
                .containsExactly("과제1/run.sh", "과제2/run.sh");
    }

    @Test
    void 일부만_실패해도_그_자리만_실패이고_결과가_이름이_같은_다른_자리에_붙지_않는다() {
        ZipImportEntry a = entry(0, "과제1/run.sh");
        ZipImportEntry b = entry(1, "과제2/run.sh");
        importService.confirm(USER, importId, List.of(a.getEntryId(), b.getEntryId()));

        runWorker(a);
        runWorker(b);

        BatchResponse batch = openOrGet();
        BatchResponse.Item first = itemAt(batch, "과제1/run.sh");
        BatchResponse.Item second = itemAt(batch, "과제2/run.sh");
        assertThat(first.getMaterialId()).as("과제1의 자료는 과제1 자리에").isNotNull();
        assertThat(first.getUploadState()).isEqualTo("UPLOADED");
        assertThat(second.getMaterialId()).as("같은 이름의 과제2 자리에 붙지 않는다").isNull();
        assertThat(second.getUploadState()).isEqualTo("UPLOAD_FAILED");
        assertThat(second.getMessage()).isNotBlank();
        assertThat(batch.getFailedCount()).isEqualTo(1);
    }

    @Test
    void 나중에_더_확정하면_새_묶음이고_앞_묶음의_분모는_그대로다() {
        ZipImportEntry a = entry(0, "과제1/run.sh");
        ZipImportEntry c = entry(2, "notes/readme.sh");
        importService.confirm(USER, importId, List.of(a.getEntryId()));
        Long firstBatch = openBatches().get(0).getBatchId();

        importService.confirm(USER, importId, List.of(c.getEntryId()));

        List<BatchResponse> open = openBatches();
        assertThat(open).hasSize(2);
        assertThat(open).filteredOn(b -> b.getBatchId().equals(firstBatch))
                .singleElement().extracting(BatchResponse::getItemCount).isEqualTo(1);
    }

    @Test
    void 실패한_항목을_다시_가져오면_자리가_대기로_돌아가고_끝난_묶음이_다시_열린다() {
        ZipImportEntry b = entry(1, "과제2/run.sh");
        importService.confirm(USER, importId, List.of(b.getEntryId()));
        runWorker(b);
        Long batchId = openOrGet().getBatchId();
        assertThat(batchService.get(USER, batchId).getStatus()).isEqualTo("FINISHED");

        importService.retryEntry(USER, importId, b.getEntryId());

        BatchResponse reopened = batchService.get(USER, batchId);
        assertThat(reopened.getStatus()).isNotEqualTo("FINISHED");
        assertThat(reopened.getItems().get(0).getUploadState()).isEqualTo("STAGED");
        assertThat(openBatches()).extracting(BatchResponse::getBatchId).contains(batchId);
    }

    @Test
    void 취소하면_아직_가져오지_않은_자리를_거두고_가져온_자료는_남는다() {
        ZipImportEntry a = entry(0, "과제1/run.sh");
        ZipImportEntry c = entry(2, "notes/readme.sh");
        importService.confirm(USER, importId, List.of(a.getEntryId(), c.getEntryId()));
        runWorker(a);

        importService.cancel(USER, importId);

        BatchResponse batch = openOrGet();
        assertThat(itemAt(batch, "과제1/run.sh").getUploadState()).isEqualTo("UPLOADED");
        BatchResponse.Item left = itemAt(batch, "notes/readme.sh");
        assertThat(left.getUploadState()).isEqualTo("ABANDONED");
        assertThat(left.getMessage()).contains("취소");
        assertThat(left.isSettled()).isTrue();
    }

    @Test
    void 프로젝트로_가져온_자료는_일반_업로드처럼_프로젝트에_연결된다() throws Exception {
        ZipImportEntry a = entry(0, "과제1/run.sh");
        importService.confirm(USER, importId, List.of(a.getEntryId()));

        runWorker(a);

        Long materialId = entryMapper.findByIdAndUserId(a.getEntryId(), USER).getMaterialId();
        assertThat(materialId).isNotNull();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM material_links WHERE material_id = ? AND course_id = ? AND user_id = ?")) {
            ps.setLong(1, materialId);
            ps.setLong(2, courseId);
            ps.setLong(3, USER);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                // 프로젝트 연결이 있으면 정리 스냅샷은 이 자료를 일반 업로드와 같은 기준으로 다룬다
                // (분석이 끝나면 대상, 아니면 사유와 함께 제외).
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
    }

    // ===== 준비 도구 =====

    private void runWorker(ZipImportEntry entry) {
        assertThat(entryMapper.claim(entry.getEntryId())).isEqualTo(1);
        worker.importEntry(entryMapper.findByIdAndUserId(entry.getEntryId(), USER));
    }

    private ZipImportEntry entry(int index, String path) {
        ZipImportEntry entry = ZipImportEntry.builder()
                .importId(importId).userId(USER).entryIndex(index).entryPath(path)
                .displayName(path.substring(path.lastIndexOf('/') + 1)).extension("sh")
                .sizeBytes(20L).supported(true).status(ZipEntryStatus.PENDING).attempt(0)
                .build();
        entryMapper.insert(entry);
        return entry;
    }

    private List<BatchResponse> openBatches() {
        List<BatchResponse> out = new ArrayList<>();
        Long cursor = null;
        do {
            BatchResponse.Page page = batchService.listOpenPage(USER, null, cursor, 50);
            out.addAll(page.getBatches());
            cursor = page.getNextCursor();
        } while (cursor != null);
        return out;
    }

    /** 끝난 묶음은 열린 목록에서 빠지므로, 이 가져오기의 가장 최근 묶음을 직접 읽는다. */
    private BatchResponse openOrGet() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT batch_id FROM material_analysis_batches WHERE zip_import_id = ? AND user_id = ? "
                             + "ORDER BY batch_id DESC LIMIT 1")) {
            ps.setLong(1, importId);
            ps.setLong(2, USER);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return batchService.get(USER, rs.getLong(1));
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static BatchResponse.Item itemAt(BatchResponse batch, String path) {
        return batch.getItems().stream().filter(i -> path.equals(i.getSourcePath())).findFirst().orElseThrow();
    }

    private static void writeZip(Path target, List<String[]> files) throws Exception {
        Files.createDirectories(target.getParent());
        try (OutputStream out = Files.newOutputStream(target); ZipOutputStream zip = new ZipOutputStream(out,
                StandardCharsets.UTF_8)) {
            for (String[] file : files) {
                zip.putNextEntry(new ZipEntry(file[0]));
                zip.write(file[1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }

    private long insertCourse() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO courses (user_id, title, status) VALUES (?, '압축 묶음 테스트 과목', 'ACTIVE')",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, USER);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            for (String table : List.of("material_analysis_timings", "material_analysis_batch_items",
                    "material_analysis_batches", "material_analysis_jobs", "material_text_units",
                    "material_zip_import_entries", "material_zip_imports", "material_links", "course_materials",
                    "courses")) {
                st.executeUpdate("DELETE FROM " + table + " WHERE user_id = " + USER);
            }
        }
    }
}
