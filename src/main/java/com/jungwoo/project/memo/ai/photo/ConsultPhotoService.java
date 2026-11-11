package com.jungwoo.project.memo.ai.photo;

import com.jungwoo.project.memo.ai.AiConversationMapper;
import com.jungwoo.project.memo.ai.AiUsageLimitService;
import com.jungwoo.project.memo.ai.domain.AiConversation;
import com.jungwoo.project.memo.ai.domain.ConversationStatus;
import com.jungwoo.project.memo.ai.photo.dto.ConsultPhotoResponse;
import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.common.exception.TooManyRequestsException;
import com.jungwoo.project.memo.course.CourseMapper;
import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.course.domain.CourseStatus;
import com.jungwoo.project.memo.learning.CourseTopicMapper;
import com.jungwoo.project.memo.learning.TopicMaterialLinkMapper;
import com.jungwoo.project.memo.learning.domain.CourseTopic;
import com.jungwoo.project.memo.learning.domain.TopicLinkOrigin;
import com.jungwoo.project.memo.learning.domain.TopicMaterialLink;
import com.jungwoo.project.memo.material.CourseMaterialMapper;
import com.jungwoo.project.memo.material.FileStorageService;
import com.jungwoo.project.memo.material.ImageFormat;
import com.jungwoo.project.memo.material.ImageMetadataStripper;
import com.jungwoo.project.memo.material.MaterialTextUnitMapper;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.material.domain.MaterialSection;
import com.jungwoo.project.memo.material.domain.MaterialTextUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 상담 교재 사진 한 장 올리기: 선점 → 사용량 예약 → 메타데이터 제거·저장 → 글자 읽기 → 자료·구간·추정 연결 저장.
 *
 * <p>순서의 이유:
 * <ul>
 *   <li>업로드 행 선점이 맨 앞이다 — 같은 키의 동시 재전송이 사용량·비전을 두 번 쓰지 않게(나중 요청은 409 또는 저장된 결과).</li>
 *   <li>사용량은 비전 호출 전에 사용자 잠금 아래 차감한다 — 동시 요청이 하루 상한을 넘지 못한다.</li>
 *   <li>파일을 쓰자마자 업로드 행에 경로를 남긴다 — 자료가 되지 못하고 끝나도(실패·서버 종료) 정리 작업이 그 파일을 찾는다.</li>
 * </ul>
 * <b>업로드 ≠ 학습 완료</b>: 단원 진도·표식·기억을 바꾸지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsultPhotoService {

    public static final String FEATURE = "CONSULT_PHOTO_READ";
    public static final long MAX_BYTES = 8L * 1024 * 1024;
    /** 이 시간보다 오래 처리 중인 업로드는 서버가 처리 도중 멈춘 것으로 본다. */
    static final int STALE_MINUTES = 10;
    private static final Pattern UPLOAD_KEY = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    private final AiConversationMapper conversationMapper;
    private final CourseMapper courseMapper;
    private final CourseTopicMapper courseTopicMapper;
    private final CourseMaterialMapper courseMaterialMapper;
    private final MaterialTextUnitMapper textUnitMapper;
    private final TopicMaterialLinkMapper topicLinkMapper;
    private final ConsultPhotoUploadMapper uploadMapper;
    private final ConsultPhotoTxService txService;
    private final AiUsageLimitService usageLimitService;
    private final FileStorageService fileStorageService;
    private final TextbookPhotoReader reader;

    @Value("${ai.consult-photo.daily-limit:30}")
    private int dailyLimit = 30;

    /** 사진 읽기 전용 스레드(서버 전체 동시 4개). 사진 읽기는 느리고 비싸다. */
    private final ExecutorService readers = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "consult-photo-read");
        t.setDaemon(true);
        return t;
    });

    /** 사진 한 장 읽기의 전체 제한(대기 포함). 넘으면 실패로 끝낸다 — 요청이 비전 호출에 붙잡히지 않는다. */
    @Value("${ai.consult-photo.read-timeout-seconds:45}")
    private int readTimeoutSeconds = 45;

    public ConsultPhotoResponse upload(Long userId, Long conversationId, String uploadKey, String filename,
                                       String declaredType, byte[] bytes) {
        if (uploadKey == null || !UPLOAD_KEY.matcher(uploadKey).matches()) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }
        AiConversation conversation = conversationMapper.findByIdAndUserId(conversationId, userId);
        if (conversation == null) {
            throw new NotFoundException(ErrorCode.ENTITY_NOT_FOUND);
        }
        if (conversation.getCourseId() == null) {
            throw new BadRequestException(ErrorCode.PHOTO_NEEDS_COURSE);
        }
        if (conversation.getStatus() != ConversationStatus.ACTIVE) {
            throw new ConflictException(ErrorCode.PHOTO_CONVERSATION_CHANGED);
        }
        Course course = courseMapper.findByIdAndUserId(conversation.getCourseId(), userId);
        if (course == null || course.getStatus() != CourseStatus.ACTIVE) {
            throw new ConflictException(ErrorCode.PHOTO_CONVERSATION_CHANGED);
        }

        ConsultPhotoUpload claim = ConsultPhotoUpload.builder().userId(userId).conversationId(conversationId)
                .uploadKey(uploadKey).build();
        if (uploadMapper.insertIgnore(claim) == 0) {
            return existing(userId, conversationId, uploadKey);
        }
        Long uploadId = claim.getUploadId();
        try {
            ImageFormat format = validate(filename, declaredType, bytes);
            if (!usageLimitService.reserveDaily(userId, conversationId, FEATURE, dailyLimit, modelLabel())) {
                uploadMapper.finish(uploadId, ConsultPhotoUpload.FAILED, null, null);
                throw new TooManyRequestsException(ErrorCode.PHOTO_DAILY_LIMIT);
            }
            byte[] clean = ImageMetadataStripper.strip(bytes, format);
            // 경로를 먼저 남기고 쓴다 — 쓰는 도중·직후에 실패해도 정리 작업이 이 파일을 찾는다.
            FileStorageService.StoredFile planned = fileStorageService.planImage(userId, format);
            uploadMapper.recordStoragePath(uploadId, planned.storagePath());
            FileStorageService.StoredFile stored = fileStorageService.writeImage(planned, clean);

            TextbookPhotoReader.Raw raw = read(clean, format);
            if (raw == null) {
                return finishWithoutMaterial(uploadId, uploadKey, ConsultPhotoUpload.FAILED, stored);
            }
            PhotoTextSanitizer.Clean text = PhotoTextSanitizer.clean(raw);
            if (!raw.studyMaterial() || text.text().isBlank()) {
                return finishWithoutMaterial(uploadId, uploadKey, ConsultPhotoUpload.UNREADABLE, stored);
            }
            Long materialId;
            try {
                materialId = txService.save(userId, conversationId, course.getCourseId(), uploadId, stored, clean.length,
                        text, LocalDateTime.now());
            } catch (RuntimeException e) {
                uploadMapper.finish(uploadId, ConsultPhotoUpload.FAILED, null, null);
                deleteUploadFile(uploadId, stored.storagePath());
                throw e;
            }
            return view(userId, materialId, uploadKey);
        } catch (BadRequestException | TooManyRequestsException e) {
            uploadMapper.finish(uploadId, ConsultPhotoUpload.FAILED, null, null);
            throw e;
        } catch (ImageMetadataStripper.StripException e) {
            log.info("상담 사진 구조 이상(저장하지 않음): userId={}, {}", userId, e.getMessage());
            uploadMapper.finish(uploadId, ConsultPhotoUpload.FAILED, null, null);
            throw new BadRequestException(ErrorCode.PHOTO_INVALID);
        }
    }

    /** 재전송 복구: 같은 키의 결과. 처리 중이면 409(오래 처리 중이면 실패로 내린다). */
    public ConsultPhotoResponse existing(Long userId, Long conversationId, String uploadKey) {
        ConsultPhotoUpload row = uploadMapper.findByKey(userId, uploadKey);
        if (row == null || !Objects.equals(row.getConversationId(), conversationId)) {
            throw new NotFoundException(ErrorCode.COURSE_MATERIAL_NOT_FOUND);
        }
        if (ConsultPhotoUpload.PROCESSING.equals(row.getStatus())) {
            if (uploadMapper.failStale(row.getUploadId(), LocalDateTime.now().minusMinutes(STALE_MINUTES)) == 0) {
                throw new ConflictException(ErrorCode.PHOTO_UPLOAD_IN_PROGRESS);
            }
            return ConsultPhotoResponse.notSaved(uploadKey, ConsultPhotoResponse.FAILED);
        }
        if (ConsultPhotoUpload.READ.equals(row.getStatus()) && row.getMaterialId() != null) {
            CourseMaterial m = courseMaterialMapper.findByIdAndUserId(row.getMaterialId(), userId);
            return m == null ? ConsultPhotoResponse.notSaved(uploadKey, ConsultPhotoResponse.FAILED)
                    : view(userId, row.getMaterialId(), uploadKey);
        }
        return ConsultPhotoResponse.notSaved(uploadKey,
                ConsultPhotoUpload.UNREADABLE.equals(row.getStatus()) ? ConsultPhotoResponse.UNREADABLE : ConsultPhotoResponse.FAILED);
    }

    /** 이 대화에 올린 사진들(기록 복원·트레이). */
    public List<ConsultPhotoResponse> listForConversation(Long userId, Long conversationId) {
        AiConversation conversation = conversationMapper.findByIdAndUserId(conversationId, userId);
        if (conversation == null) {
            throw new NotFoundException(ErrorCode.ENTITY_NOT_FOUND);
        }
        return courseMaterialMapper.findPhotosByConversation(userId, conversationId).stream()
                .map(m -> view(userId, m.getMaterialId(), null)).toList();
    }

    public ConsultPhotoResponse setTopic(Long userId, Long materialId, Long topicId) {
        txService.setTopic(userId, materialId, topicId);
        return view(userId, materialId, null);
    }

    /** 원본만 지운다(읽은 글·연결은 남는다). 차단을 먼저 커밋하고 그 자리에서 파일을 지운다 — 실패하면 정리 작업이 다시. */
    public ConsultPhotoResponse deleteOriginal(Long userId, Long materialId) {
        CourseMaterial material = courseMaterialMapper.findByIdAndUserId(materialId, userId);
        if (material == null) {
            throw new NotFoundException(ErrorCode.COURSE_MATERIAL_NOT_FOUND);
        }
        if (!material.isConsultPhoto()) {
            throw new BadRequestException(ErrorCode.NOT_CONSULT_PHOTO);
        }
        LocalDateTime now = LocalDateTime.now();
        courseMaterialMapper.markOriginalRemoved(materialId, userId, "USER", now);
        if (material.getOriginalPurgedAt() == null && fileStorageService.deleteForPurge(materialId, material.getStoragePath())) {
            courseMaterialMapper.markOriginalPurged(materialId, now);
        }
        log.info("상담 사진 원본 삭제: userId={}, materialId={}", userId, materialId);
        return view(userId, materialId, null);
    }

    public ConsultPhotoResponse view(Long userId, Long materialId, String uploadKey) {
        CourseMaterial m = courseMaterialMapper.findByIdAndUserId(materialId, userId);
        if (m == null || !m.isConsultPhoto()) {
            throw new NotFoundException(ErrorCode.COURSE_MATERIAL_NOT_FOUND);
        }
        Long courseId = txService.courseOf(userId, m);
        Course course = courseId == null ? null : courseMapper.findByIdAndUserId(courseId, userId);
        List<CourseTopic> candidates = course == null ? List.of() : txService.candidates(userId, course);
        Map<Long, CourseTopic> active = courseId == null ? Map.of()
                : courseTopicMapper.findActiveByCourseIdAndUserId(courseId, userId).stream()
                .collect(Collectors.toMap(CourseTopic::getTopicId, Function.identity(), (a, b) -> a));
        MaterialSection section = txService.photoSection(m);
        TopicMaterialLink link = section == null ? null : topicLinkMapper.findActiveByMaterialId(materialId, userId).stream()
                .filter(l -> Objects.equals(l.getSectionId(), section.getSectionId()) && active.containsKey(l.getTopicId()))
                .findFirst().orElse(null);
        String text = textUnitMapper.findByMaterialIdAndHash(materialId, m.getFileHash()).stream()
                .map(MaterialTextUnit::getText).filter(Objects::nonNull).findFirst().orElse("");
        List<String> headings = section == null || section.getSectionLabel() == null ? List.of() : List.of(section.getSectionLabel());
        return new ConsultPhotoResponse(materialId, uploadKey, ConsultPhotoResponse.READ, m.getOriginalFilename(),
                section == null ? null : section.getPrintedPageStart(), headings, text,
                link == null ? null : ref(active.get(link.getTopicId())),
                link == null ? ConsultPhotoResponse.LINK_NONE
                        : link.getOrigin() == TopicLinkOrigin.PHOTO_GUESS ? ConsultPhotoResponse.LINK_GUESSED
                        : ConsultPhotoResponse.LINK_CONFIRMED,
                candidates.stream().map(ConsultPhotoService::ref).toList(),
                m.originalAvailable(LocalDateTime.now()), m.getOriginalExpiresAt(), m.getCreatedAt());
    }

    private String modelLabel() {
        String name = reader.modelName();
        return name == null || name.isBlank() ? "unknown" : name;
    }

    static ConsultPhotoResponse.TopicRef ref(CourseTopic t) {
        return t == null ? null : new ConsultPhotoResponse.TopicRef(t.getTopicId(), t.getTitle(), t.getSourceTocSeq());
    }

    static ImageFormat validate(String filename, String declaredType, byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES || !ImageFormat.claimedImage(filename, declaredType)) {
            throw new BadRequestException(ErrorCode.PHOTO_INVALID);
        }
        return ImageFormat.detect(Arrays.copyOf(bytes, Math.min(bytes.length, ImageFormat.HEADER_BYTES)))
                .orElseThrow(() -> new BadRequestException(ErrorCode.PHOTO_INVALID));
    }

    private TextbookPhotoReader.Raw read(byte[] image, ImageFormat format) {
        Future<TextbookPhotoReader.Raw> call = readers.submit(() -> reader.read(image, format.contentType()));
        try {
            return call.get(readTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            call.cancel(true);
            log.warn("상담 사진 읽기: {}초 안에 끝나지 않아 실패로 끝낸다", readTimeoutSeconds);
            return null;
        } catch (ExecutionException e) {
            log.warn("상담 사진 읽기 실패: {}", e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            call.cancel(true);
            return null;
        }
    }

    private ConsultPhotoResponse finishWithoutMaterial(Long uploadId, String uploadKey, String status,
                                                       FileStorageService.StoredFile stored) {
        uploadMapper.finish(uploadId, status, null, null);
        deleteUploadFile(uploadId, stored.storagePath());
        return ConsultPhotoResponse.notSaved(uploadKey,
                ConsultPhotoUpload.UNREADABLE.equals(status) ? ConsultPhotoResponse.UNREADABLE : ConsultPhotoResponse.FAILED);
    }

    private void deleteUploadFile(Long uploadId, String storagePath) {
        if (fileStorageService.deleteForPurge(null, storagePath)) {
            uploadMapper.markFileRemoved(uploadId, LocalDateTime.now());
        }
    }
}
