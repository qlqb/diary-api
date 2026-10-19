package com.jungwoo.project.memo.material.zip;

import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.course.CourseService;
import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.course.domain.CourseStatus;
import com.jungwoo.project.memo.material.FileStorageService;
import com.jungwoo.project.memo.material.zip.domain.ZipEntryStatus;
import com.jungwoo.project.memo.material.zip.domain.ZipImport;
import com.jungwoo.project.memo.material.zip.domain.ZipImportEntry;
import com.jungwoo.project.memo.material.zip.domain.ZipImportStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 가져오기 사용자 경로의 규칙: 소유권, 확정할 수 있는 상태, 선택 개수, 프로젝트 상태.
 * 실제 DB가 하는 일(중복 방지·선점)은 MaterialZipImportDbTest가 본다.
 */
@ExtendWith(MockitoExtension.class)
class MaterialZipImportServiceTest {

    private static final Long USER = 7L;
    private static final Long IMPORT = 50L;

    @Mock private ZipImportMapper importMapper;
    @Mock private ZipImportEntryMapper entryMapper;
    @Mock private ZipArchiveReader archiveReader;
    @Mock private ZipImportTxService txService;
    @Mock private FileStorageService fileStorageService;
    @Mock private CourseService courseService;
    @Mock private com.jungwoo.project.memo.material.batch.MaterialAnalysisBatchService batchService;

    @InjectMocks
    private MaterialZipImportService service;

    private ZipImport zipImport(ZipImportStatus status, Long courseId) {
        return ZipImport.builder()
                .importId(IMPORT).userId(USER).courseId(courseId).originalFilename("3주차.zip")
                .storagePath("zip-imports/7/a.zip").sizeBytes(100L).status(status)
                .entryCount(2).selectableCount(1)
                .build();
    }

    private ZipImportEntry entry(ZipEntryStatus status, Long materialId) {
        return ZipImportEntry.builder()
                .entryId(9L).importId(IMPORT).userId(USER).entryIndex(0).entryPath("3주차/강의.pdf")
                .displayName("강의.pdf").extension("pdf").sizeBytes(10L).supported(true)
                .status(status).materialId(materialId).attempt(0)
                .build();
    }

    @Test
    void 남의_가져오기는_찾을_수_없다() {
        when(importMapper.findByIdAndUserId(IMPORT, USER)).thenReturn(null);

        assertThatThrownBy(() -> service.get(USER, IMPORT))
                .isInstanceOf(NotFoundException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ZIP_IMPORT_NOT_FOUND);
    }

    @Test
    void 취소한_가져오기에는_확정할_수_없다() {
        when(importMapper.findByIdAndUserId(IMPORT, USER)).thenReturn(zipImport(ZipImportStatus.CANCELLED, null));

        assertThatThrownBy(() -> service.confirm(USER, IMPORT, List.of(9L)))
                .isInstanceOf(ConflictException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ZIP_IMPORT_NOT_READY);
        verify(entryMapper, never()).queueSelected(anyLong(), anyLong(), any());
    }

    /**
     * 한 번 확정한 뒤 남겨 둔 파일을 나중에 더 가져오는 경우. 상태가 COMPLETED여도 원본 압축이 남아
     * 있으면 고를 수 있어야 한다 — 그러지 않으면 같은 압축을 처음부터 다시 올려야 한다.
     */
    @Test
    void 완료된_가져오기라도_원본이_남아_있으면_남은_파일을_더_가져올_수_있다() {
        when(importMapper.findByIdAndUserId(IMPORT, USER)).thenReturn(zipImport(ZipImportStatus.COMPLETED, null));
        when(entryMapper.queueSelected(IMPORT, USER, List.of(9L))).thenReturn(1);
        when(entryMapper.findByImportId(IMPORT, USER)).thenReturn(List.of(entry(ZipEntryStatus.QUEUED, null)));

        service.confirm(USER, IMPORT, List.of(9L));

        verify(entryMapper).queueSelected(IMPORT, USER, List.of(9L));
    }

    @Test
    void 원본_압축이_없으면_더_가져올_수_없다() {
        ZipImport expired = zipImport(ZipImportStatus.COMPLETED, null);
        expired.setStoragePath(null);
        when(importMapper.findByIdAndUserId(IMPORT, USER)).thenReturn(expired);

        assertThatThrownBy(() -> service.confirm(USER, IMPORT, List.of(9L)))
                .isInstanceOf(ConflictException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ZIP_IMPORT_ARCHIVE_EXPIRED);
        verify(entryMapper, never()).queueSelected(anyLong(), anyLong(), any());
    }

    @Test
    void 아무것도_고르지_않으면_확정하지_않는다() {
        when(importMapper.findByIdAndUserId(IMPORT, USER)).thenReturn(zipImport(ZipImportStatus.READY, null));

        assertThatThrownBy(() -> service.confirm(USER, IMPORT, List.of()))
                .isInstanceOf(BadRequestException.class);
        verify(entryMapper, never()).queueSelected(anyLong(), anyLong(), any());
    }

    @Test
    void 확정_시_프로젝트가_보관됐으면_잘못된_연결을_만들지_않는다() {
        when(importMapper.findByIdAndUserId(IMPORT, USER)).thenReturn(zipImport(ZipImportStatus.READY, 3L));
        when(courseService.getOwned(USER, 3L))
                .thenReturn(Course.builder().courseId(3L).status(CourseStatus.ARCHIVED).build());

        assertThatThrownBy(() -> service.confirm(USER, IMPORT, List.of(9L)))
                .isInstanceOf(ConflictException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.COURSE_ARCHIVED);
        verify(entryMapper, never()).queueSelected(anyLong(), anyLong(), any());
    }

    @Test
    void 확정하면_대기열에_넣고_상태를_가져오는_중으로_바꾼다() {
        when(importMapper.findByIdAndUserId(IMPORT, USER)).thenReturn(zipImport(ZipImportStatus.READY, null));
        when(entryMapper.queueSelected(IMPORT, USER, List.of(9L))).thenReturn(1);
        when(entryMapper.findByImportId(IMPORT, USER)).thenReturn(List.of(entry(ZipEntryStatus.QUEUED, null)));

        service.confirm(USER, IMPORT, List.of(9L));

        verify(entryMapper).queueSelected(IMPORT, USER, List.of(9L));
        verify(importMapper).updateStatus(eq(IMPORT), eq(ZipImportStatus.IMPORTING), eq(null), eq(null));
        // 가져올 파일이 정해진 순간 분석 묶음을 연다 — 대기열에 오른 항목만으로.
        verify(batchService).createForZip(eq(USER), any(), eq(IMPORT), argThat(items -> items.size() == 1
                && items.get(0).entryId().equals(9L)));
    }

    @Test
    void 이미_자료가_된_항목은_다시_시도할_수_없다() {
        when(importMapper.findByIdAndUserId(IMPORT, USER)).thenReturn(zipImport(ZipImportStatus.PARTIAL, null));
        when(entryMapper.findByIdAndUserId(9L, USER)).thenReturn(entry(ZipEntryStatus.DONE, 123L));

        assertThatThrownBy(() -> service.retryEntry(USER, IMPORT, 9L))
                .isInstanceOf(ConflictException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ZIP_IMPORT_ENTRY_NOT_RETRYABLE);
    }

    @Test
    void 원본_압축이_지워졌으면_재시도_대신_이유를_말한다() {
        ZipImport expired = zipImport(ZipImportStatus.PARTIAL, null);
        expired.setStoragePath(null);
        when(importMapper.findByIdAndUserId(IMPORT, USER)).thenReturn(expired);
        when(entryMapper.findByIdAndUserId(9L, USER)).thenReturn(entry(ZipEntryStatus.FAILED, null));

        assertThatThrownBy(() -> service.retryEntry(USER, IMPORT, 9L))
                .isInstanceOf(ConflictException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ZIP_IMPORT_ARCHIVE_EXPIRED);
    }

    @Test
    void 다른_가져오기의_항목_id로는_재시도할_수_없다() {
        when(importMapper.findByIdAndUserId(IMPORT, USER)).thenReturn(zipImport(ZipImportStatus.PARTIAL, null));
        ZipImportEntry other = entry(ZipEntryStatus.FAILED, null);
        other.setImportId(IMPORT + 1);
        when(entryMapper.findByIdAndUserId(9L, USER)).thenReturn(other);

        assertThatThrownBy(() -> service.retryEntry(USER, IMPORT, 9L))
                .isInstanceOf(NotFoundException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ZIP_IMPORT_ENTRY_NOT_FOUND);
    }

    @Test
    void 취소하면_원본을_지우고_만든_자료는_남긴다() {
        ZipImport ready = zipImport(ZipImportStatus.READY, null);
        when(importMapper.findByIdAndUserId(IMPORT, USER)).thenReturn(ready);
        lenient().when(entryMapper.findByImportId(IMPORT, USER))
                .thenReturn(List.of(entry(ZipEntryStatus.DONE, 123L)));

        service.cancel(USER, IMPORT);

        verify(importMapper).updateStatus(IMPORT, ZipImportStatus.CANCELLED, null, null);
        // 아직 가져오지 않은 분석 자리를 거둔다.
        verify(batchService).abandonZipImport(USER, IMPORT);
        verify(fileStorageService).deleteQuietly(null, "zip-imports/7/a.zip");
        verify(importMapper).clearStoragePath(IMPORT);
        // 자료를 지우는 호출은 어디에도 없다.
        verify(importMapper, never()).updateStatus(eq(IMPORT), eq(ZipImportStatus.FAILED), any(), any());
    }
}
