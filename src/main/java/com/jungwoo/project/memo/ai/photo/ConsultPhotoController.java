package com.jungwoo.project.memo.ai.photo;

import com.jungwoo.project.memo.ai.photo.dto.ConsultPhotoResponse;
import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 상담 교재 사진.
 *
 * <pre>
 * POST   /api/ai/conversations/{id}/photos              사진 한 장(multipart image, uploadKey) → 읽기·저장
 * GET    /api/ai/conversations/{id}/photos              이 대화에 올린 사진들
 * GET    /api/ai/conversations/{id}/photos?uploadKey=   같은 키의 결과(응답을 잃었을 때 복구)
 * PUT    /api/materials/{id}/photo-topic  {topicId}     단원 확인·변경(null이면 해제)
 * DELETE /api/materials/{id}/original                   원본 사진만 지운다(읽은 글은 남는다)
 * </pre>
 * 파일 이름·사진 내용은 로그에 남기지 않는다(사진에 이름 등이 찍힐 수 있다).
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ConsultPhotoController {

    private final ConsultPhotoService photoService;

    public record TopicRequest(Long topicId) {
    }

    @PostMapping("/api/ai/conversations/{conversationId}/photos")
    public ResponseEntity<ConsultPhotoResponse> upload(@AuthenticationPrincipal UserPrincipal principal,
                                                       @PathVariable Long conversationId,
                                                       @RequestParam("image") MultipartFile image,
                                                       @RequestParam("uploadKey") String uploadKey) {
        log.info("POST /api/ai/conversations/{}/photos - userId={}, {} bytes", conversationId, principal.getUserId(),
                image == null ? 0 : image.getSize());
        if (image == null || image.isEmpty() || image.getSize() > ConsultPhotoService.MAX_BYTES) {
            throw new BadRequestException(ErrorCode.PHOTO_INVALID);
        }
        byte[] bytes;
        try {
            bytes = image.getBytes();
        } catch (IOException e) {
            throw new BadRequestException(ErrorCode.PHOTO_INVALID);
        }
        return ResponseEntity.ok(photoService.upload(principal.getUserId(), conversationId, uploadKey,
                image.getOriginalFilename(), image.getContentType(), bytes));
    }

    @GetMapping("/api/ai/conversations/{conversationId}/photos")
    public ResponseEntity<?> list(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long conversationId,
                                  @RequestParam(value = "uploadKey", required = false) String uploadKey) {
        if (uploadKey != null) {
            return ResponseEntity.ok(photoService.existing(principal.getUserId(), conversationId, uploadKey));
        }
        List<ConsultPhotoResponse> photos = photoService.listForConversation(principal.getUserId(), conversationId);
        return ResponseEntity.ok(photos);
    }

    @PutMapping("/api/materials/{materialId}/photo-topic")
    public ResponseEntity<ConsultPhotoResponse> setTopic(@AuthenticationPrincipal UserPrincipal principal,
                                                         @PathVariable Long materialId, @RequestBody TopicRequest request) {
        return ResponseEntity.ok(photoService.setTopic(principal.getUserId(), materialId,
                request == null ? null : request.topicId()));
    }

    @DeleteMapping("/api/materials/{materialId}/original")
    public ResponseEntity<ConsultPhotoResponse> deleteOriginal(@AuthenticationPrincipal UserPrincipal principal,
                                                               @PathVariable Long materialId) {
        return ResponseEntity.ok(photoService.deleteOriginal(principal.getUserId(), materialId));
    }
}
