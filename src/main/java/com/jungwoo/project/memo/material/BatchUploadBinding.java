package com.jungwoo.project.memo.material;

import com.jungwoo.project.memo.material.batch.MaterialAnalysisBatchService;
import com.jungwoo.project.memo.material.dto.MaterialResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Supplier;

/**
 * 업로드 결과를 묶음 자리에 적는다.
 *
 * <p>업로드 경로 자체({@link MaterialService})는 묶음을 모른다 — 묶음 서비스가 자료 상태를
 * 읽으려고 {@code MaterialService}를 거치므로, 반대로 부르면 순환이 된다. 그래서 두
 * 컨트롤러가 이 작은 껍데기를 공유한다.
 *
 * <p>실패도 자리에 적는다. 적지 않으면 그 자리가 영원히 "올리는 중"으로 남아 묶음이 끝나지
 * 않는다. 적는 데 실패해도 업로드 결과는 그대로 돌려준다 — 파일은 실제로 올라갔다.
 */
@Slf4j
final class BatchUploadBinding {

    private BatchUploadBinding() {
    }

    static MaterialResponse run(MaterialAnalysisBatchService batchService, Long userId, Long batchItemId,
                                Supplier<MaterialResponse> upload) {
        if (batchItemId == null) {
            return upload.get();
        }
        MaterialResponse response;
        try {
            response = upload.get();
        } catch (RuntimeException e) {
            record(() -> batchService.failUpload(userId, batchItemId, e.getMessage()));
            throw e;
        }
        record(() -> batchService.bindUpload(userId, batchItemId, response.getMaterialId()));
        return response;
    }

    private static void record(Runnable work) {
        try {
            work.run();
        } catch (Exception e) {
            log.warn("묶음 자리에 업로드 결과를 적지 못했다: {}", e.getClass().getSimpleName());
        }
    }
}
