package com.jungwoo.project.memo.material.zip;

import com.jungwoo.project.memo.material.MaterialTxService;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.material.domain.MaterialTextUnit;
import com.jungwoo.project.memo.material.domain.MaterialType;
import com.jungwoo.project.memo.material.zip.domain.ZipEntryStatus;
import com.jungwoo.project.memo.material.zip.domain.ZipImport;
import com.jungwoo.project.memo.material.zip.domain.ZipImportEntry;
import com.jungwoo.project.memo.material.zip.domain.ZipImportStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 가져오기의 트랜잭션 경계. 별도 빈인 이유는 MaterialTxService와 같다 — 같은 클래스 안에서 부르면
 * 프록시를 타지 않아 @Transactional이 아예 걸리지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ZipImportTxService {

    private final ZipImportMapper importMapper;
    private final ZipImportEntryMapper entryMapper;
    private final MaterialTxService materialTxService;

    @Transactional
    public void saveListing(ZipImport zipImport, List<ZipImportEntry> entries, int selectableCount, String warning) {
        for (ZipImportEntry entry : entries) {
            entryMapper.insert(entry);
        }
        importMapper.updateListing(zipImport.getImportId(), ZipImportStatus.READY, entries.size(), selectableCount);
        if (warning != null) {
            importMapper.updateStatus(zipImport.getImportId(), ZipImportStatus.READY, null, warning);
        }
    }

    /**
     * 자료 생성과 항목 완료를 한 트랜잭션에 묶는다.
     *
     * <p>이게 중복 생성을 막는 핵심이다. 자료 INSERT와 "이 항목은 이 자료가 됐다"는 기록이 함께
     * 커밋되므로, 커밋 뒤에 응답이 유실되거나 서버가 내려가도 항목에는 material_id가 남는다 —
     * 재시작 복구(requeueStale)는 material_id가 있는 항목을 건너뛰고, 확정(queueSelected)도
     * material_id가 있으면 다시 큐에 넣지 않는다.
     */
    @Transactional
    public CourseMaterial completeEntry(ZipImportEntry entry, CourseMaterial material, Long courseId,
                                        MaterialType materialType, List<MaterialTextUnit> units) {
        materialTxService.createWithLink(material, courseId, materialType, units);
        int updated = entryMapper.markDone(entry.getEntryId(), material.getMaterialId());
        if (updated != 1) {
            // 이미 다른 실행이 자료를 만든 항목이다. 이 트랜잭션을 통째로 되돌려 자료가 두 개가 되지 않게 한다.
            throw new IllegalStateException("이미 완료된 가져오기 항목입니다: entryId=" + entry.getEntryId());
        }
        return material;
    }

    @Transactional
    public void failEntry(Long entryId, String errorCode, String errorMessage) {
        entryMapper.markFailed(entryId, errorCode, errorMessage);
    }

    /**
     * 남은 항목이 없으면 가져오기를 마무리한다. 결과는 항목 상태로 정한다 —
     * 실패가 없으면 COMPLETED, 자료가 하나라도 있으면 PARTIAL, 하나도 없으면 FAILED.
     */
    @Transactional
    public ZipImportStatus finishIfDone(Long importId) {
        if (entryMapper.countRemaining(importId) > 0) {
            return null;
        }
        int done = entryMapper.countByStatus(importId, ZipEntryStatus.DONE);
        int failed = entryMapper.countByStatus(importId, ZipEntryStatus.FAILED);
        ZipImportStatus status = failed == 0 ? ZipImportStatus.COMPLETED
                : done > 0 ? ZipImportStatus.PARTIAL : ZipImportStatus.FAILED;
        importMapper.updateStatus(importId, status, null, null);
        return status;
    }

    @Transactional
    public void expire(Long importId) {
        importMapper.updateStatus(importId, ZipImportStatus.EXPIRED, null, null);
        importMapper.clearStoragePath(importId);
    }

    @Transactional
    public void clearStoragePath(Long importId) {
        importMapper.clearStoragePath(importId);
    }
}
