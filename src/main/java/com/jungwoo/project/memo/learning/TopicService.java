package com.jungwoo.project.memo.learning;

import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.course.CourseService;
import com.jungwoo.project.memo.learning.domain.CourseTopic;
import com.jungwoo.project.memo.learning.domain.LearningEventType;
import com.jungwoo.project.memo.learning.domain.TopicLearningEvent;
import com.jungwoo.project.memo.learning.domain.TopicProgress;
import com.jungwoo.project.memo.learning.domain.TopicProgressStatus;
import com.jungwoo.project.memo.learning.domain.TopicSourceType;
import com.jungwoo.project.memo.learning.domain.TopicStatus;
import com.jungwoo.project.memo.learning.dto.TopicDraft;
import com.jungwoo.project.memo.learning.dto.TopicResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 확정된 topic 트리 + topic별 학습 진행 상태를 다룬다.
 *
 * course_topics는 이 서비스의 applyAnalyzedTopics()를 통해서만(=Material Agent 분석의 Apply)
 * 생성된다. 사용자가 직접 topic을 만드는 CRUD는 없다 — 구조는 항상 분석 결과의 확정이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TopicService {

    private final CourseService courseService;
    private final CourseTopicMapper courseTopicMapper;
    private final TopicProgressMapper topicProgressMapper;
    private final TopicLearningEventMapper topicLearningEventMapper;

    /**
     * Material Agent 분석 결과(수정본 포함)를 course_topics로 확정 삽입한다.
     * source_type이 SOURCE/AI_DERIVED가 아니면(모델이 스키마를 어겼거나 사용자 수정이 잘못된 경우)
     * AI_DERIVED로 보수적으로 처리한다 — "원문에 있었다"고 잘못 표시하는 쪽보다 안전하다.
     *
     * 이 메서드는 append-only다 — 기존 topic을 지우거나 병합하지 않는다. 그래서 루트
     * order_index를 0부터 다시 매기면 두 번째 자료를 apply한 순간 기존 목차와 순서가 섞인다
     * (트리 정렬 기준이 order_index 하나뿐이다). 기존 최대값 다음부터 이어 붙인다.
     * 자식은 부모마다 새 카운터를 쓰고 부모 자체가 새로 생기므로 겹칠 대상이 없다.
     */
    @Transactional
    public int applyAnalyzedTopics(Long userId, Long courseId, Long materialId, List<TopicDraft> drafts) {
        courseService.getOwned(userId, courseId);
        int[] count = {0};
        Integer maxRootOrder = courseTopicMapper.findMaxRootOrderIndex(courseId, userId);
        int[] orderIndex = {maxRootOrder == null ? 0 : maxRootOrder + 1};
        for (TopicDraft draft : drafts) {
            insertTopicTree(userId, courseId, materialId, null, draft, orderIndex, count);
        }
        log.info("topic 트리 확정: userId={}, courseId={}, materialId={}, count={}", userId, courseId, materialId, count[0]);
        return count[0];
    }

    private void insertTopicTree(Long userId, Long courseId, Long materialId, Long parentTopicId,
                                  TopicDraft draft, int[] orderIndex, int[] count) {
        TopicSourceType sourceType = "SOURCE".equals(draft.sourceType())
                ? TopicSourceType.SOURCE : TopicSourceType.AI_DERIVED;

        CourseTopic topic = CourseTopic.builder()
                .userId(userId)
                .courseId(courseId)
                .parentTopicId(parentTopicId)
                .title(draft.title())
                .orderIndex(orderIndex[0]++)
                .sourceType(sourceType)
                .sourceMaterialId(materialId)
                .sourceLocator(draft.sourceLocator())
                .status(TopicStatus.ACTIVE)
                .build();
        courseTopicMapper.insert(topic);
        count[0]++;

        if (draft.children() != null) {
            int[] childOrder = {0};
            for (TopicDraft child : draft.children()) {
                insertTopicTree(userId, courseId, materialId, topic.getTopicId(), child, childOrder, count);
            }
        }
    }

    @Transactional(readOnly = true)
    public List<TopicResponse> getTopicTree(Long userId, Long courseId) {
        courseService.getOwned(userId, courseId);
        List<CourseTopic> all = courseTopicMapper.findActiveByCourseIdAndUserId(courseId, userId);
        if (all.isEmpty()) {
            return List.of();
        }
        List<Long> topicIds = all.stream().map(CourseTopic::getTopicId).toList();
        Map<Long, TopicProgress> progressByTopic = topicProgressMapper.findByUserIdAndTopicIds(userId, topicIds)
                .stream().collect(Collectors.toMap(TopicProgress::getTopicId, p -> p));

        Map<Long, List<CourseTopic>> childrenByParent = all.stream()
                .filter(t -> t.getParentTopicId() != null)
                .collect(Collectors.groupingBy(CourseTopic::getParentTopicId));

        List<CourseTopic> roots = all.stream()
                .filter(t -> t.getParentTopicId() == null)
                .sorted(Comparator.comparing(CourseTopic::getOrderIndex))
                .toList();

        return roots.stream().map(root -> buildResponse(root, childrenByParent, progressByTopic)).toList();
    }

    private TopicResponse buildResponse(CourseTopic topic, Map<Long, List<CourseTopic>> childrenByParent,
                                         Map<Long, TopicProgress> progressByTopic) {
        List<CourseTopic> childTopics = childrenByParent.getOrDefault(topic.getTopicId(), List.of()).stream()
                .sorted(Comparator.comparing(CourseTopic::getOrderIndex))
                .toList();
        List<TopicResponse> children = new ArrayList<>();
        for (CourseTopic child : childTopics) {
            children.add(buildResponse(child, childrenByParent, progressByTopic));
        }
        return TopicResponse.of(topic, progressByTopic.get(topic.getTopicId()), children);
    }

    @Transactional(readOnly = true)
    public CourseTopic getOwnedTopic(Long userId, Long topicId) {
        CourseTopic topic = courseTopicMapper.findByIdAndUserId(topicId, userId);
        if (topic == null) {
            throw new NotFoundException(ErrorCode.COURSE_TOPIC_NOT_FOUND);
        }
        return topic;
    }

    @Transactional(readOnly = true)
    public TopicProgress getOrDefaultProgress(Long userId, Long topicId) {
        TopicProgress progress = topicProgressMapper.findByUserIdAndTopicId(userId, topicId);
        if (progress != null) {
            return progress;
        }
        return TopicProgress.builder()
                .userId(userId).topicId(topicId)
                .status(TopicProgressStatus.NOT_STARTED)
                .reviewCount(0)
                .build();
    }

    /** 사용자가 학습 화면에서 명시적으로 topic 상태를 바꾸는 액션. AI/실행 완료가 대신 호출하지 않는다. */
    @Transactional
    public TopicProgress updateProgressStatus(Long userId, Long topicId, TopicProgressStatus newStatus) {
        getOwnedTopic(userId, topicId);
        TopicProgress before = getOrCreateProgress(userId, topicId);
        LocalDateTime now = LocalDateTime.now();
        topicProgressMapper.updateProgress(userId, topicId, newStatus.name(),
                newStatus == TopicProgressStatus.LEARNED || newStatus == TopicProgressStatus.IN_PROGRESS ? now : null,
                null, false);
        topicLearningEventMapper.insert(TopicLearningEvent.builder()
                .userId(userId).topicId(topicId)
                .eventType(LearningEventType.STATUS_CHANGED)
                .fromStatus(before.getStatus())
                .toStatus(newStatus)
                .note("사용자가 학습 화면에서 직접 변경")
                .build());
        log.info("topic 진행 상태 변경: userId={}, topicId={}, {} -> {}", userId, topicId, before.getStatus(), newStatus);
        return topicProgressMapper.findByUserIdAndTopicId(userId, topicId);
    }

    /**
     * ExecutionItem 완료가 호출하는 진입점(실행 -> 학습 피드백). "완료 버튼을 눌렀다"는 사실만으로
     * LEARNED로 넘기지 않는다 — 이미 LEARNED가 아니면 IN_PROGRESS로, 이미 LEARNED였다면
     * 복습으로 보고 reviewCount만 늘린다. 최종 LEARNED 판단은 항상 사용자의 별도 액션이다.
     */
    @Transactional
    public void recordExecutionCompleted(Long userId, Long topicId, Long executionItemId) {
        TopicProgress before = getOrCreateProgress(userId, topicId);
        LocalDateTime now = LocalDateTime.now();
        boolean isReview = before.getStatus() == TopicProgressStatus.LEARNED;

        TopicProgressStatus nextStatus = isReview ? null /* LEARNED 유지 */ : TopicProgressStatus.IN_PROGRESS;
        topicProgressMapper.updateProgress(userId, topicId,
                nextStatus != null ? nextStatus.name() : null,
                now, isReview ? now : null, isReview);

        topicLearningEventMapper.insert(TopicLearningEvent.builder()
                .userId(userId).topicId(topicId)
                .executionItemId(executionItemId)
                .eventType(LearningEventType.EXECUTION_COMPLETED)
                .fromStatus(before.getStatus())
                .toStatus(nextStatus != null ? nextStatus : before.getStatus())
                .note(isReview ? "복습 실행 완료" : "학습 실행 완료")
                .build());
        log.info("실행 완료 -> 학습 피드백: userId={}, topicId={}, executionItemId={}, review={}",
                userId, topicId, executionItemId, isReview);
    }

    private TopicProgress getOrCreateProgress(Long userId, Long topicId) {
        TopicProgress existing = topicProgressMapper.findByUserIdAndTopicId(userId, topicId);
        if (existing != null) {
            return existing;
        }
        TopicProgress created = TopicProgress.builder()
                .userId(userId).topicId(topicId)
                .status(TopicProgressStatus.NOT_STARTED)
                .reviewCount(0)
                .build();
        try {
            topicProgressMapper.insert(created);
            return created;
        } catch (DuplicateKeyException e) {
            return topicProgressMapper.findByUserIdAndTopicId(userId, topicId);
        }
    }
}
