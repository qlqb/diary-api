package com.jungwoo.project.memo.material.zip;

import com.jungwoo.project.memo.material.CourseMaterialMapper;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.material.domain.ExtractionStatus;
import com.jungwoo.project.memo.material.domain.MaterialStatus;
import com.jungwoo.project.memo.material.domain.MaterialTextUnit;
import com.jungwoo.project.memo.material.domain.TextUnitType;
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
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 가져오기의 중복 생성 방지와 복구를 실제 DB에서 본다. Mockito로는 증명할 수 없는 것들이다 —
 * 여기서 확인하는 것은 전부 SQL 제약과 UPDATE 조건이 하는 일이다.
 *
 * <ol>
 *   <li>확정을 두 번 눌러도(동시에도) 같은 항목이 대기열에 두 번 들어가지 않는다.</li>
 *   <li>작업자 둘이 같은 항목을 집으려 하면 하나만 선점한다.</li>
 *   <li>자료 생성과 항목 완료가 같은 트랜잭션이라 "자료는 있는데 항목은 미완료"가 남지 않고,
 *       한 항목이 자료를 두 개 만들 수 없다(UNIQUE material_id).</li>
 *   <li>서버가 죽어 IMPORTING으로 남은 항목은 다시 큐에 들어가되, 이미 자료가 된 항목은 제외된다.</li>
 *   <li>사용자가 자료를 지워도 그 항목을 다시 큐에 넣지 않는다.</li>
 * </ol>
 *
 * 로컬 memo DB 필요(docs/sql/2026-09-16-material-file-types.sql 적용). CI 제외.
 */
@SpringBootTest
class MaterialZipImportDbTest {

    private static final long USER = 999_000_601L;

    @Autowired
    private ZipImportMapper importMapper;
    @Autowired
    private ZipImportEntryMapper entryMapper;
    @Autowired
    private ZipImportTxService txService;
    @Autowired
    private CourseMaterialMapper courseMaterialMapper;
    @Autowired
    private DataSource dataSource;

    private Long importId;

    @BeforeEach
    void setUp() {
        cleanUp();
        ZipImport zipImport = ZipImport.builder()
                .userId(USER).originalFilename("3주차.zip").storagePath("zip-imports/" + USER + "/t.zip")
                .sizeBytes(1024L).fileHash("a".repeat(64))
                .status(ZipImportStatus.READY).entryCount(0).selectableCount(0)
                .expiresAt(LocalDateTime.now().plusHours(24))
                .build();
        importMapper.insert(zipImport);
        importId = zipImport.getImportId();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private ZipImportEntry newEntry(int index, String path) {
        ZipImportEntry entry = ZipImportEntry.builder()
                .importId(importId).userId(USER).entryIndex(index).entryPath(path)
                .displayName(path.substring(path.lastIndexOf('/') + 1)).extension("pdf")
                .sizeBytes(100L).supported(true).status(ZipEntryStatus.PENDING).attempt(0)
                .build();
        entryMapper.insert(entry);
        return entry;
    }

    @Test
    void 확정을_두_번_눌러도_대기열에_한_번만_들어간다() {
        ZipImportEntry entry = newEntry(0, "3주차/강의.pdf");

        int first = entryMapper.queueSelected(importId, USER, List.of(entry.getEntryId()));
        int second = entryMapper.queueSelected(importId, USER, List.of(entry.getEntryId()));

        assertThat(first).isEqualTo(1);
        // 이미 QUEUED라 두 번째 확정은 아무 행도 바꾸지 않는다 — 대기열에 같은 항목이 두 번 들어가지 않는다.
        assertThat(second).isZero();
        assertThat(entryMapper.findByIdAndUserId(entry.getEntryId(), USER).getStatus())
                .isEqualTo(ZipEntryStatus.QUEUED);
    }

    @Test
    void 동시에_확정해도_작업자는_한_번만_선점한다() throws Exception {
        ZipImportEntry entry = newEntry(0, "3주차/강의.pdf");
        entryMapper.queueSelected(importId, USER, List.of(entry.getEntryId()));

        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<Integer> claim = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return entryMapper.claim(entry.getEntryId());
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = pool.submit(claim);
            Future<Integer> b = pool.submit(claim);
            assertThat(a.get(10, TimeUnit.SECONDS) + b.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 자료_생성과_항목_완료는_함께_커밋되고_한_항목은_자료를_하나만_만든다() {
        ZipImportEntry entry = newEntry(0, "3주차/강의.pdf");
        entryMapper.queueSelected(importId, USER, List.of(entry.getEntryId()));
        entryMapper.claim(entry.getEntryId());

        CourseMaterial material = txService.completeEntry(entry, material("강의.pdf"), null, null,
                List.of(unit()));

        ZipImportEntry saved = entryMapper.findByIdAndUserId(entry.getEntryId(), USER);
        assertThat(saved.getStatus()).isEqualTo(ZipEntryStatus.DONE);
        assertThat(saved.getMaterialId()).isEqualTo(material.getMaterialId());

        // 같은 항목으로 또 만들려 하면 markDone이 0행이라 통째로 롤백된다 — 자료가 두 개가 되지 않는다.
        CourseMaterial second = material("강의.pdf");
        assertThatThrownBy(() -> txService.completeEntry(entry, second, null, null, List.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(courseMaterialMapper.findByIdAndUserId(second.getMaterialId(), USER)).isNull();
    }

    @Test
    void 멈춘_항목은_다시_큐에_들어가지만_이미_자료가_된_항목은_아니다() {
        ZipImportEntry stuck = newEntry(0, "3주차/멈춤.pdf");
        ZipImportEntry done = newEntry(1, "3주차/완료.pdf");
        entryMapper.queueSelected(importId, USER, List.of(stuck.getEntryId(), done.getEntryId()));
        entryMapper.claim(stuck.getEntryId());
        entryMapper.claim(done.getEntryId());
        txService.completeEntry(done, material("완료.pdf"), null, null, List.of());

        int requeued = entryMapper.requeueStale(LocalDateTime.now().plusMinutes(1), 3);

        assertThat(requeued).isEqualTo(1);
        assertThat(entryMapper.findByIdAndUserId(stuck.getEntryId(), USER).getStatus())
                .isEqualTo(ZipEntryStatus.QUEUED);
        assertThat(entryMapper.findByIdAndUserId(done.getEntryId(), USER).getStatus())
                .isEqualTo(ZipEntryStatus.DONE);
    }

    @Test
    void 가져온_자료를_지워도_그_항목은_다시_가져오지_않는다() {
        ZipImportEntry entry = newEntry(0, "3주차/강의.pdf");
        entryMapper.queueSelected(importId, USER, List.of(entry.getEntryId()));
        entryMapper.claim(entry.getEntryId());
        CourseMaterial material = txService.completeEntry(entry, material("강의.pdf"), null, null, List.of());
        courseMaterialMapper.markDeleted(material.getMaterialId(), USER);

        int queued = entryMapper.queueSelected(importId, USER, List.of(entry.getEntryId()));

        assertThat(queued).isZero();
        assertThat(entryMapper.findByIdAndUserId(entry.getEntryId(), USER).getStatus())
                .isEqualTo(ZipEntryStatus.DONE);
    }

    @Test
    void 실패한_항목만_다시_큐에_들어간다_그리고_결과는_일부_성공이다() {
        ZipImportEntry ok = newEntry(0, "3주차/성공.pdf");
        ZipImportEntry bad = newEntry(1, "3주차/실패.pdf");
        entryMapper.queueSelected(importId, USER, List.of(ok.getEntryId(), bad.getEntryId()));
        entryMapper.claim(ok.getEntryId());
        entryMapper.claim(bad.getEntryId());
        txService.completeEntry(ok, material("성공.pdf"), null, null, List.of());
        txService.failEntry(bad.getEntryId(), "E400_014", "읽을 수 없는 파일이에요");

        assertThat(txService.finishIfDone(importId)).isEqualTo(ZipImportStatus.PARTIAL);

        int queued = entryMapper.queueSelected(importId, USER, List.of(ok.getEntryId(), bad.getEntryId()));
        assertThat(queued).isEqualTo(1); // 실패한 것만
        assertThat(entryMapper.findByIdAndUserId(bad.getEntryId(), USER).getStatus())
                .isEqualTo(ZipEntryStatus.QUEUED);
    }

    @Test
    void 남은_항목이_있으면_마무리하지_않는다() {
        ZipImportEntry waiting = newEntry(0, "3주차/대기.pdf");
        entryMapper.queueSelected(importId, USER, List.of(waiting.getEntryId()));

        assertThat(txService.finishIfDone(importId)).isNull();
        assertThat(importMapper.findByIdAndUserId(importId, USER).getStatus())
                .isEqualTo(ZipImportStatus.READY);
    }

    @Test
    void 남의_가져오기는_보이지_않는다() {
        ZipImportEntry entry = newEntry(0, "3주차/강의.pdf");
        long otherUser = USER + 1;

        assertThat(importMapper.findByIdAndUserId(importId, otherUser)).isNull();
        assertThat(entryMapper.findByIdAndUserId(entry.getEntryId(), otherUser)).isNull();
        assertThat(entryMapper.findByImportId(importId, otherUser)).isEmpty();
        // 남의 entryId로 확정해도 아무 행도 바뀌지 않는다.
        assertThat(entryMapper.queueSelected(importId, otherUser, List.of(entry.getEntryId()))).isZero();
    }

    private CourseMaterial material(String name) {
        return CourseMaterial.builder()
                .userId(USER).originalFilename(name).storedFilename("uuid-" + name)
                .storagePath(USER + "/uuid-" + name).contentType("application/pdf")
                .sizeBytes(10L).fileHash("b".repeat(64))
                .extractionStatus(ExtractionStatus.SUCCESS).extractedText("본문")
                .sourceArchiveName("3주차.zip").sourceEntryPath("3주차/" + name)
                .status(MaterialStatus.ACTIVE)
                .build();
    }

    private MaterialTextUnit unit() {
        return MaterialTextUnit.builder()
                .userId(USER).fileHash("b".repeat(64)).unitIndex(0)
                .unitType(TextUnitType.TEXT_BLOCK).unitNo(1).charCount(2).text("본문")
                .build();
    }

    private void cleanUp() {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM material_text_units WHERE user_id = " + USER);
            statement.executeUpdate("DELETE FROM material_zip_import_entries WHERE user_id = " + USER);
            statement.executeUpdate("DELETE FROM material_zip_imports WHERE user_id = " + USER);
            statement.executeUpdate("DELETE FROM course_materials WHERE user_id = " + USER);
        } catch (Exception e) {
            throw new IllegalStateException("테스트 데이터 정리 실패", e);
        }
    }
}
