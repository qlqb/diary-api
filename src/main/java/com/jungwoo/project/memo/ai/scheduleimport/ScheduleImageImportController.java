package com.jungwoo.project.memo.ai.scheduleimport;

import com.jungwoo.project.memo.ai.scheduleimport.dto.ScheduleExtractionResponse;
import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/**
 * 근무표 같은 일정표 이미지를 일정 후보로 가져오는 경로.
 *
 * <p>이미지 바이트는 요청 처리 중 메모리에만 있고 저장하지 않는다. 로그에도 남기지 않는다 —
 * 근무표에는 본인 외 동료의 이름과 근무 시간이 함께 들어 있다.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/ai/conversations/{conversationId}/schedule-image")
public class ScheduleImageImportController {

    /**
     * 이 기능만의 크기 상한. 전역 multipart 상한(25MB)보다 작게 잡는다 — 근무표 사진은 이보다
     * 클 이유가 없고, 큰 이미지는 모델 호출만 느려진다.
     */
    private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;

    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private final ScheduleImageImportService scheduleImageImportService;

    @PostMapping("/extract")
    public ResponseEntity<ScheduleExtractionResponse> extract(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long conversationId,
            @RequestParam("image") MultipartFile image
    ) {
        // 크기와 형식만 적고 파일 이름은 남기지 않는다 — 이름에 사업장·사람 이름이 들어 있곤 한다.
        log.info("POST /api/ai/conversations/{}/schedule-image/extract - userId={}, {} bytes, {}",
                conversationId, principal.getUserId(), image.getSize(), image.getContentType());

        return ResponseEntity.ok(scheduleImageImportService.extract(
                principal.getUserId(), conversationId, bytesOf(image), image.getContentType()));
    }

    private byte[] bytesOf(MultipartFile image) {
        if (image == null || image.isEmpty()) {
            throw new BadRequestException(ErrorCode.INVALID_IMAGE);
        }
        if (image.getSize() > MAX_IMAGE_BYTES) {
            throw new BadRequestException(ErrorCode.INVALID_IMAGE);
        }
        String contentType = image.getContentType();
        if (contentType == null
                || !ALLOWED_TYPES.contains(contentType.toLowerCase(Locale.ROOT).split(";")[0].trim())) {
            throw new BadRequestException(ErrorCode.INVALID_IMAGE);
        }
        try {
            return image.getBytes();
        } catch (IOException e) {
            log.warn("업로드된 이미지를 읽지 못했습니다", e);
            throw new BadRequestException(ErrorCode.INVALID_IMAGE);
        }
    }
}
