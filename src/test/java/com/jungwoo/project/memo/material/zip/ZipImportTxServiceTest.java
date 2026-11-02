package com.jungwoo.project.memo.material.zip;

import com.jungwoo.project.memo.material.MaterialTxService;
import com.jungwoo.project.memo.material.zip.domain.ZipEntryStatus;
import com.jungwoo.project.memo.material.zip.domain.ZipImportStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 가져오기 상태 전이는 매퍼 문장의 from 조건이 지킨다. 여기서는 각 경로가 어떤 from을 넘기는지 고정한다.
 */
@ExtendWith(MockitoExtension.class)
class ZipImportTxServiceTest {

    private static final Long IMPORT = 7L;

    @Mock
    private ZipImportMapper importMapper;
    @Mock
    private ZipImportEntryMapper entryMapper;
    @Mock
    private MaterialTxService materialTxService;

    @InjectMocks
    private ZipImportTxService txService;

    /**
     * 사용자가 취소하면 남은 대기 항목은 작업자가 "취소된 가져오기"로 실패 처리하고 finishIfDone을 부른다. 마무리는
     * IMPORTING에서만 하므로 CANCELLED가 PARTIAL·FAILED로 덮이지 않는다(문장이 0행 → null).
     */
    @Test
    void 취소된_가져오기를_완료로_마무리하지_않는다() {
        when(entryMapper.countRemaining(IMPORT)).thenReturn(0);
        when(entryMapper.countByStatus(IMPORT, ZipEntryStatus.DONE)).thenReturn(1);
        when(entryMapper.countByStatus(IMPORT, ZipEntryStatus.FAILED)).thenReturn(2);
        when(importMapper.updateStatus(IMPORT, ZipImportStatus.PARTIAL, null, null, EnumSet.of(ZipImportStatus.IMPORTING)))
                .thenReturn(0);

        assertThat(txService.finishIfDone(IMPORT)).isNull();
    }

    @Test
    void 가져오는_중이면_항목_결과로_마무리한다() {
        when(entryMapper.countRemaining(IMPORT)).thenReturn(0);
        when(entryMapper.countByStatus(IMPORT, ZipEntryStatus.DONE)).thenReturn(3);
        when(entryMapper.countByStatus(IMPORT, ZipEntryStatus.FAILED)).thenReturn(0);
        when(importMapper.updateStatus(IMPORT, ZipImportStatus.COMPLETED, null, null, EnumSet.of(ZipImportStatus.IMPORTING)))
                .thenReturn(1);

        assertThat(txService.finishIfDone(IMPORT)).isEqualTo(ZipImportStatus.COMPLETED);
    }

    @Test
    void 만료는_끝나지_않은_가져오기만_바꾸고_원본_경로는_비운다() {
        txService.expire(IMPORT);

        verify(importMapper).updateStatus(IMPORT, ZipImportStatus.EXPIRED, null, null, ZipImportStatus.OPEN);
        verify(importMapper).clearStoragePath(IMPORT);
    }

    /** 목록을 읽는 동안 취소됐다: READY 전이가 0행이면 경고 줄도 쓰지 않는다. */
    @Test
    void 목록_준비_중_취소되면_READY로_덮지_않는다() {
        com.jungwoo.project.memo.material.zip.domain.ZipImport zipImport =
                com.jungwoo.project.memo.material.zip.domain.ZipImport.builder().importId(IMPORT).build();
        when(importMapper.updateListing(eq(IMPORT), eq(ZipImportStatus.READY), anyInt(), anyInt())).thenReturn(0);

        txService.saveListing(zipImport, java.util.List.of(), 0, "경고");

        verify(importMapper, never()).updateStatus(anyLong(), any(), any(), any(), any());
    }
}
