package com.jungwoo.project.memo.learning.structure;

import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.course.CourseMapper;
import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.learning.CourseTopicMapper;
import com.jungwoo.project.memo.learning.TopicMaterialLinkMapper;
import com.jungwoo.project.memo.learning.TopicProgressMapper;
import com.jungwoo.project.memo.learning.domain.CourseTopic;
import com.jungwoo.project.memo.learning.domain.TopicLinkOrigin;
import com.jungwoo.project.memo.learning.domain.TopicMaterialLink;
import com.jungwoo.project.memo.learning.domain.TopicProgress;
import com.jungwoo.project.memo.learning.domain.TopicProgressStatus;
import com.jungwoo.project.memo.learning.domain.TopicSourceType;
import com.jungwoo.project.memo.learning.domain.TopicStatus;
import com.jungwoo.project.memo.material.domain.MaterialSection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 검증된 변경안 작업을 트리에 적용한다. 한 트랜잭션, 전부 아니면 전무.
 *
 * <p>불변식:
 * <ul>
 *   <li>이동·이름 변경은 topicId를 유지한다 — progress·user_mark·실행 기록·근거가 그대로 따라온다.</li>
 *   <li>병합은 살아남은 id를 남기고 흡수된 항목을 ARCHIVED + merged_into로 내린다. 흡수된 항목의 자료
 *       연결은 살아남은 항목으로 옮긴다. 학습 기록은 복제하지 않는다 — 흡수된 항목에 기록이 있고 살아남은
 *       항목에 없으면 review_note를 남겨 사람이 정한다.</li>
 *   <li>분할은 원본을 부모로 남기고 자식을 새로 만든다. 원본의 완료 하나를 자식 전부의 완료로 복제하지
 *       않는다. 원본에 기록이 있으면 review_note를 남긴다.</li>
 *   <li>적용 직전에 courses.topic_tree_version을 기대값과 대조해 올린다. 다르면 아무것도 바꾸지 않는다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TopicTreeEditor {

    private final CourseMapper courseMapper;
    private final CourseTopicMapper topicMapper;
    private final TopicMaterialLinkMapper linkMapper;
    private final TopicProgressMapper progressMapper;

    public record Applied(int linked, int added, int renamed, int moved, int merged, int split,
                          List<Long> createdTopicIds, List<String> reviewNotes) {
    }

    /**
     * @param expectedTreeVersion 변경안을 만들 때 본 버전. null이면 대조하지 않고 올리기만 한다(사용자 직접 편집).
     * @param sectionsById        이 자료의 구간(위치 문자열을 링크에 적기 위해)
     */
    @Transactional
    public Applied apply(Long userId, Long courseId, Long materialId, List<TopicChangeOp> ops,
                         Long expectedTreeVersion, Map<Long, MaterialSection> sectionsById, TopicLinkOrigin origin) {
        Course course = courseMapper.findByIdAndUserIdForUpdate(courseId, userId);
        if (course == null) {
            throw new BadRequestException(ErrorCode.COURSE_NOT_FOUND);
        }
        if (expectedTreeVersion != null) {
            if (courseMapper.bumpTopicTreeVersion(courseId, userId, expectedTreeVersion) != 1) {
                throw new ConflictException(ErrorCode.TOPIC_TREE_CONFLICT);
            }
        } else {
            courseMapper.incrementTopicTreeVersion(courseId, userId);
        }
        List<CourseTopic> active = topicMapper.findActiveByCourseIdAndUserIdForUpdate(courseId, userId);
        TopicChangeOpsValidator.Result checked = TopicChangeOpsValidator.validate(
                ops, active, sectionsById.keySet(), true);
        if (!checked.rejected().isEmpty()) {
            throw new BadRequestException(ErrorCode.TOPIC_CHANGE_INVALID, String.join("; ", checked.rejected()));
        }
        Map<Long, CourseTopic> topics = new HashMap<>();
        for (CourseTopic topic : active) {
            topics.put(topic.getTopicId(), topic);
        }
        Map<String, Long> tempIds = new HashMap<>();
        List<Long> created = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        int linked = 0, added = 0, renamed = 0, moved = 0, merged = 0, split = 0;

        for (TopicChangeOp op : checked.ops()) {
            switch (op.op()) {
                case TopicChangeOp.LINK -> linked += link(userId, courseId, materialId, op.topicId(), op.sectionIds(),
                        op.role(), sectionsById, origin);
                case TopicChangeOp.ADD -> {
                    Long parent = op.parentTopicId() != null ? op.parentTopicId()
                            : op.parentTempId() != null ? tempIds.get(op.parentTempId()) : null;
                    added += insertTree(userId, courseId, materialId, parent, op, tempIds, created, sectionsById, origin);
                }
                case TopicChangeOp.RENAME -> {
                    topicMapper.updateTitle(op.topicId(), userId, op.title());
                    renamed++;
                }
                case TopicChangeOp.MOVE -> {
                    Integer max = op.parentTopicId() == null
                            ? topicMapper.findMaxRootOrderIndex(courseId, userId)
                            : topicMapper.findMaxChildOrderIndex(courseId, userId, op.parentTopicId());
                    topicMapper.updateParent(op.topicId(), userId, op.parentTopicId(), max == null ? 0 : max + 1);
                    moved++;
                }
                case TopicChangeOp.MERGE -> {
                    merged += merge(userId, op.survivingTopicId(), op.absorbedTopicIds(), notes);
                }
                case TopicChangeOp.SPLIT -> {
                    split += splitTopic(userId, courseId, materialId, op, tempIds, created, sectionsById, origin, notes);
                }
                default -> throw new BadRequestException(ErrorCode.TOPIC_CHANGE_INVALID, op.op());
            }
        }
        log.info("학습 구조 변경 적용: userId={}, courseId={}, materialId={}, link={}, add={}, rename={}, move={}, merge={}, split={}",
                userId, courseId, materialId, linked, added, renamed, moved, merged, split);
        return new Applied(linked, added, renamed, moved, merged, split, created, notes);
    }

    private int link(Long userId, Long courseId, Long materialId, Long topicId, List<Long> sectionIds, String role,
                     Map<Long, MaterialSection> sectionsById, TopicLinkOrigin origin) {
        int n = 0;
        for (Long sectionId : sectionIds == null ? List.<Long>of() : sectionIds) {
            MaterialSection section = sectionsById.get(sectionId);
            linkMapper.upsert(TopicMaterialLink.builder()
                    .userId(userId).courseId(courseId).topicId(topicId).materialId(materialId)
                    .sectionId(sectionId)
                    .role(role != null ? role : firstRole(section))
                    .locator(section == null ? null : section.locator())
                    .origin(origin)
                    .build());
            n++;
        }
        return n;
    }

    private int insertTree(Long userId, Long courseId, Long materialId, Long parentId, TopicChangeOp op,
                           Map<String, Long> tempIds, List<Long> created, Map<Long, MaterialSection> sectionsById,
                           TopicLinkOrigin origin) {
        Integer max = parentId == null
                ? topicMapper.findMaxRootOrderIndex(courseId, userId)
                : topicMapper.findMaxChildOrderIndex(courseId, userId, parentId);
        MaterialSection first = op.sectionIds() == null || op.sectionIds().isEmpty() ? null
                : sectionsById.get(op.sectionIds().get(0));
        CourseTopic topic = CourseTopic.builder()
                .userId(userId).courseId(courseId).parentTopicId(parentId)
                .title(op.title()).orderIndex(max == null ? 0 : max + 1)
                .sourceType("SOURCE".equals(op.sourceType()) ? TopicSourceType.SOURCE : TopicSourceType.AI_DERIVED)
                .sourceMaterialId(materialId)
                .sourceLocator(op.locator() != null ? op.locator() : first == null ? null : first.locator())
                .status(TopicStatus.ACTIVE)
                .build();
        topicMapper.insert(topic);
        created.add(topic.getTopicId());
        if (op.tempId() != null) {
            tempIds.put(op.tempId(), topic.getTopicId());
        }
        int count = 1;
        if (op.sectionIds() != null && !op.sectionIds().isEmpty()) {
            link(userId, courseId, materialId, topic.getTopicId(), op.sectionIds(), op.role(), sectionsById, origin);
        } else {
            // 구간을 모르는 새 항목도 자료 전체와는 잇는다 — 최초 출처를 남기는 것이 source_material_id와 같은 뜻이다.
            linkMapper.upsert(TopicMaterialLink.builder()
                    .userId(userId).courseId(courseId).topicId(topic.getTopicId()).materialId(materialId)
                    .sectionId(TopicMaterialLink.WHOLE_MATERIAL).role("SOURCE").locator(op.locator())
                    .origin(origin).build());
        }
        for (TopicChangeOp child : op.children() == null ? List.<TopicChangeOp>of() : op.children()) {
            count += insertTree(userId, courseId, materialId, topic.getTopicId(), child, tempIds, created,
                    sectionsById, origin);
        }
        return count;
    }

    private int merge(Long userId, Long survivingId, List<Long> absorbedIds, List<String> notes) {
        TopicProgress survivingProgress = progressMapper.findByUserIdAndTopicId(userId, survivingId);
        boolean survivingHasRecord = survivingProgress != null
                && survivingProgress.getStatus() != TopicProgressStatus.NOT_STARTED;
        int n = 0;
        for (Long absorbedId : absorbedIds) {
            for (TopicMaterialLink link : linkMapper.findActiveByTopicId(absorbedId, userId)) {
                linkMapper.upsert(TopicMaterialLink.builder()
                        .userId(userId).courseId(link.getCourseId()).topicId(survivingId)
                        .materialId(link.getMaterialId()).sectionId(link.getSectionId())
                        .role(link.getRole()).locator(link.getLocator()).origin(link.getOrigin()).build());
            }
            TopicProgress absorbedProgress = progressMapper.findByUserIdAndTopicId(userId, absorbedId);
            boolean absorbedHasRecord = absorbedProgress != null
                    && absorbedProgress.getStatus() != TopicProgressStatus.NOT_STARTED;
            if (absorbedHasRecord && !survivingHasRecord) {
                String note = "병합된 항목에 학습 기록이 있어요. 이 항목의 상태를 확인해 주세요";
                topicMapper.updateReviewNote(survivingId, userId, note);
                notes.add(note);
            }
            topicMapper.archiveMerged(absorbedId, userId, survivingId);
            n++;
        }
        return n;
    }

    private int splitTopic(Long userId, Long courseId, Long materialId, TopicChangeOp op, Map<String, Long> tempIds,
                           List<Long> created, Map<Long, MaterialSection> sectionsById, TopicLinkOrigin origin,
                           List<String> notes) {
        int n = 0;
        for (TopicChangeOp child : op.children()) {
            n += insertTree(userId, courseId, materialId, op.topicId(), child, tempIds, created, sectionsById, origin);
        }
        TopicProgress progress = progressMapper.findByUserIdAndTopicId(userId, op.topicId());
        if (progress != null && progress.getStatus() != TopicProgressStatus.NOT_STARTED) {
            String note = "나눈 뒤에도 기존 학습 기록은 이 항목에 남아 있어요. 하위 항목은 새로 시작이에요";
            topicMapper.updateReviewNote(op.topicId(), userId, note);
            notes.add(note);
        }
        return n;
    }

    private static String firstRole(MaterialSection section) {
        if (section == null || section.getRolesJson() == null) {
            return "SOURCE";
        }
        String json = section.getRolesJson().replace("[", "").replace("]", "").replace("\"", "");
        String first = json.split(",")[0].trim();
        return first.isEmpty() ? "SOURCE" : first;
    }

    /** 활성 항목 id 집합(검증용 편의). */
    public Set<Long> activeIds(Long userId, Long courseId) {
        return topicMapper.findActiveByCourseIdAndUserId(courseId, userId).stream()
                .map(CourseTopic::getTopicId).collect(Collectors.toCollection(HashSet::new));
    }
}
