package com.jungwoo.project.memo.ai.photo;

import com.jungwoo.project.memo.ai.AiConversationMapper;
import com.jungwoo.project.memo.ai.UserContextMapper;
import com.jungwoo.project.memo.ai.domain.AiConversation;
import com.jungwoo.project.memo.ai.domain.ConversationStatus;
import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.course.CourseMapper;
import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.course.domain.CourseStatus;
import com.jungwoo.project.memo.course.textbook.BookKey;
import com.jungwoo.project.memo.learning.CourseTopicMapper;
import com.jungwoo.project.memo.learning.TopicMaterialLinkMapper;
import com.jungwoo.project.memo.learning.domain.CourseTopic;
import com.jungwoo.project.memo.learning.domain.TopicLinkOrigin;
import com.jungwoo.project.memo.learning.domain.TopicMaterialLink;
import com.jungwoo.project.memo.material.CourseMaterialMapper;
import com.jungwoo.project.memo.material.FileStorageService;
import com.jungwoo.project.memo.material.MaterialLinkMapper;
import com.jungwoo.project.memo.material.MaterialSectionMapper;
import com.jungwoo.project.memo.material.MaterialTextUnitMapper;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.material.domain.ExtractionStatus;
import com.jungwoo.project.memo.material.domain.MaterialLink;
import com.jungwoo.project.memo.material.domain.MaterialOrigin;
import com.jungwoo.project.memo.material.domain.MaterialSection;
import com.jungwoo.project.memo.material.domain.MaterialStatus;
import com.jungwoo.project.memo.material.domain.MaterialTextUnit;
import com.jungwoo.project.memo.material.domain.MaterialType;
import com.jungwoo.project.memo.material.domain.TextUnitType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 상담 사진의 DB 쓰기(트랜잭션 경계). 파일 I/O·비전 호출은 바깥({@link ConsultPhotoService})에 둔다.
 *
 * <p>단원 연결과 그 사진에서 유도한 기억의 단원은 <b>과목 행 잠금</b> 아래에서 바꾼다 — 기억 저장(UserContextService)과 같은
 * 잠금이라, 연결 정정과 "이 문제 모르겠어" 저장이 엇갈려도 기억이 옛 단원에 남지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsultPhotoTxService {

    /** 원본 보관 기간(사용자 결정 2026-10-05). */
    public static final int ORIGINAL_RETENTION_DAYS = 30;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("M/d");
    private static final Pattern EXERCISE_CUE = Pattern.compile("(?m)^\\s*(?:\\d{1,2}[.)]|[A-E][.)]|\\(\\d{1,2}\\))|_{3,}");

    private final AiConversationMapper conversationMapper;
    private final CourseMapper courseMapper;
    private final CourseTopicMapper courseTopicMapper;
    private final CourseMaterialMapper courseMaterialMapper;
    private final MaterialLinkMapper materialLinkMapper;
    private final MaterialTextUnitMapper textUnitMapper;
    private final MaterialSectionMapper sectionMapper;
    private final TopicMaterialLinkMapper topicLinkMapper;
    private final UserContextMapper userContextMapper;
    private final ConsultPhotoUploadMapper uploadMapper;

    /**
     * 읽은 사진을 자료·연결·본문 단위·사진 구간·단원 추정 연결로 한 번에 저장하고 업로드 행을 READ로 끝낸다.
     * 대화가 보관됐거나 과목이 바뀌었으면 저장하지 않는다(호출자가 파일을 지운다).
     *
     * @return 새 자료 id
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Long save(Long userId, Long conversationId, Long courseId, Long uploadId, FileStorageService.StoredFile stored,
                     long sizeBytes, PhotoTextSanitizer.Clean clean, LocalDateTime now) {
        // 잠금 순서: 업로드 행 → 대화 → 과목. 늦게 끝난 처리가 이미 실패로 정리된 업로드를 되살리지 못하게 하고,
        // 확인 직후 대화가 보관돼도 사진이 그 대화에 저장되지 않게 한다(턴 준비도 대화 행을 잠근다).
        ConsultPhotoUpload upload = uploadMapper.lockById(uploadId);
        if (upload == null || !ConsultPhotoUpload.PROCESSING.equals(upload.getStatus())) {
            throw new ConflictException(ErrorCode.PHOTO_CONVERSATION_CHANGED);
        }
        AiConversation conversation = conversationMapper.findByIdAndUserIdForUpdate(conversationId, userId);
        Course course = courseMapper.findByIdAndUserIdForUpdate(courseId, userId);
        if (course == null || course.getStatus() != CourseStatus.ACTIVE || conversation == null
                || conversation.getStatus() != ConversationStatus.ACTIVE || !Objects.equals(conversation.getCourseId(), courseId)) {
            throw new ConflictException(ErrorCode.PHOTO_CONVERSATION_CHANGED);
        }
        List<CourseTopic> candidates = candidates(userId, course);
        Long guessed = PhotoTopicMatcher.match(candidates.stream().map(ConsultPhotoTxService::candidate).toList(),
                clean.headings(), clean.printedPage());

        String title = title(clean, now);
        String unitText = clean.unitText();
        CourseMaterial material = CourseMaterial.builder()
                .userId(userId)
                .originalFilename(cut(title, 255))
                .storedFilename(stored.storedFilename())
                .storagePath(stored.storagePath())
                .contentType(stored.contentType())
                .sizeBytes(sizeBytes)
                .pageCount(1)
                .fileHash(stored.fileHash())
                .extractionStatus(ExtractionStatus.SUCCESS)
                .extractedText(unitText)
                .extractionWarning(clean.truncated() ? "사진 글이 길어 앞부분만 저장했어요" : null)
                .documentTitleRead(true)
                .status(MaterialStatus.ACTIVE)
                .origin(MaterialOrigin.CONSULT_PHOTO)
                .sourceConversationId(conversationId)
                .originalExpiresAt(now.plusDays(ORIGINAL_RETENTION_DAYS))
                .build();
        courseMaterialMapper.insert(material);
        Long materialId = material.getMaterialId();
        materialLinkMapper.insert(MaterialLink.builder().userId(userId).materialId(materialId).courseId(courseId)
                .materialType(MaterialType.OTHER).build());
        textUnitMapper.insert(MaterialTextUnit.builder()
                .userId(userId).materialId(materialId).fileHash(stored.fileHash())
                .unitIndex(0).unitType(TextUnitType.IMAGE_PAGE).unitNo(1)
                .charCount(unitText.length()).text(unitText)
                .build());
        MaterialSection section = MaterialSection.builder()
                .userId(userId).materialId(materialId).fileHash(stored.fileHash())
                .analysisVersion(0).chunkIndex(0).unitType(TextUnitType.IMAGE_PAGE).unitStart(1).unitEnd(1)
                .printedPageStart(clean.printedPage()).printedPageEnd(clean.printedPage())
                .sectionLabel(clean.headings().isEmpty() ? null : cut(clean.headings().get(0), 100))
                .displayTitle(cut(title, 300))
                .rolesJson(EXERCISE_CUE.matcher(clean.text()).find() ? "[\"CONCEPT\",\"EXERCISE\"]" : "[\"CONCEPT\"]")
                .excerpt(cut(unitText, 400))
                .dedupeKey("photo")
                .build();
        sectionMapper.insertIgnore(section);
        if (guessed != null) {
            topicLinkMapper.upsert(TopicMaterialLink.builder()
                    .userId(userId).courseId(courseId).topicId(guessed).materialId(materialId)
                    .sectionId(section.getSectionId()).role("CONCEPT")
                    .locator(clean.printedPage() == null ? null : "p." + clean.printedPage())
                    .origin(TopicLinkOrigin.PHOTO_GUESS)
                    .build());
        }
        if (uploadMapper.finish(uploadId, ConsultPhotoUpload.READ, materialId, null) != 1) {
            throw new ConflictException(ErrorCode.PHOTO_CONVERSATION_CHANGED); // 롤백 — 자료를 남기지 않는다
        }
        log.info("상담 사진 저장: userId={}, conversationId={}, materialId={}, 쪽={}, 추정 단원={}, 글자={}",
                userId, conversationId, materialId, clean.printedPage(), guessed, unitText.length());
        return materialId;
    }

    /**
     * 사진의 단원을 확인·변경·해제한다. 불변식: 사진 구간당 ACTIVE 단원 연결은 최대 하나.
     * 그 사진 문맥으로 단원을 채운 기억(topic_photo_id)도 같은 단원으로 따라간다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void setTopic(Long userId, Long materialId, Long topicId) {
        CourseMaterial material = courseMaterialMapper.findByIdAndUserId(materialId, userId);
        if (material == null) {
            throw new NotFoundException(ErrorCode.COURSE_MATERIAL_NOT_FOUND);
        }
        if (!material.isConsultPhoto()) {
            throw new BadRequestException(ErrorCode.NOT_CONSULT_PHOTO);
        }
        Long courseId = courseOf(userId, material);
        Course course = courseId == null ? null : courseMapper.findByIdAndUserIdForUpdate(courseId, userId);
        if (course == null) {
            throw new NotFoundException(ErrorCode.MATERIAL_NOT_LINKED_TO_COURSE);
        }
        MaterialSection section = photoSection(material);
        if (section == null) {
            throw new NotFoundException(ErrorCode.MATERIAL_SECTION_NOT_FOUND);
        }
        if (topicId != null && courseTopicMapper.findActiveByCourseIdAndUserId(courseId, userId).stream()
                .noneMatch(t -> t.getTopicId().equals(topicId))) {
            throw new BadRequestException(ErrorCode.PHOTO_TOPIC_INVALID);
        }
        for (TopicMaterialLink link : topicLinkMapper.findActiveByMaterialId(materialId, userId)) {
            if (Objects.equals(link.getSectionId(), section.getSectionId()) && !Objects.equals(link.getTopicId(), topicId)) {
                topicLinkMapper.remove(link.getLinkId(), userId);
            }
        }
        if (topicId != null) {
            topicLinkMapper.upsert(TopicMaterialLink.builder()
                    .userId(userId).courseId(courseId).topicId(topicId).materialId(materialId)
                    .sectionId(section.getSectionId()).role("CONCEPT")
                    .locator(section.getPrintedPageStart() == null ? null : "p." + section.getPrintedPageStart())
                    .origin(TopicLinkOrigin.USER)
                    .build());
            // upsert는 되살린 행의 origin을 바꾸지 않는다(PHOTO_GUESS → USER, A→B→A의 옛 행).
            topicLinkMapper.activateAsUser(topicId, materialId, section.getSectionId(), userId);
        }
        int moved = userContextMapper.retargetPhotoTopic(userId, materialId, topicId);
        log.info("상담 사진 단원 정함: userId={}, materialId={}, topicId={}, 따라 바뀐 기억={}", userId, materialId, topicId, moved);
    }

    /**
     * 사진의 과목: 자료 연결이 있으면 그 과목, 연결을 끊었으면 올린 대화의 과목. 둘 다 없으면 null — 사진 자체의 조회·원본 삭제는
     * 과목 없이도 된다(단원 후보만 비어 있다).
     */
    public Long courseOf(Long userId, CourseMaterial material) {
        List<MaterialLink> links = materialLinkMapper.findByMaterialIdAndUserId(material.getMaterialId(), userId);
        if (!links.isEmpty()) {
            return links.get(0).getCourseId();
        }
        if (material.getSourceConversationId() == null) {
            return null;
        }
        AiConversation conversation = conversationMapper.findByIdAndUserId(material.getSourceConversationId(), userId);
        return conversation == null ? null : conversation.getCourseId();
    }

    public MaterialSection photoSection(CourseMaterial material) {
        return sectionMapper.findActiveByMaterialIdAndHash(material.getMaterialId(), material.getFileHash()).stream()
                .findFirst().orElse(null);
    }

    /**
     * 단원 후보: 이전 교재 목차의 단원을 뺀 살아 있는 단원 중 현재 교재 목차에서 온 것. 현재 목차 단원이 없으면 목차가 아닌
     * 단원(사용자가 만든 것 등)만 — 교재를 바꾼 직후 사진이 옛 교재 단원에 붙지 않는다.
     */
    public List<CourseTopic> candidates(Long userId, Course course) {
        String current = BookKey.of(course);
        List<CourseTopic> notPrior = courseTopicMapper.findActiveByCourseIdAndUserId(course.getCourseId(), userId).stream()
                .filter(t -> current == null || t.getSourceTextbookKey() == null || current.equals(t.getSourceTextbookKey()))
                .toList();
        List<CourseTopic> toc = notPrior.stream().filter(t -> t.getSourceTocSeq() != null).toList();
        return toc.isEmpty() ? notPrior : toc;
    }

    static PhotoTopicMatcher.Candidate candidate(CourseTopic t) {
        return new PhotoTopicMatcher.Candidate(t.getTopicId(), t.getTitle(), t.getSourceLocator(), t.getSourceTocSeq());
    }

    static String title(PhotoTextSanitizer.Clean clean, LocalDateTime now) {
        StringBuilder sb = new StringBuilder("교재 사진");
        if (clean.printedPage() != null) {
            sb.append(" p.").append(clean.printedPage());
        } else {
            sb.append(' ').append(now.format(DAY));
        }
        if (!clean.headings().isEmpty()) {
            sb.append(" · ").append(clean.headings().get(0));
        }
        return sb.toString();
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
