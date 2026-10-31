package com.jungwoo.project.memo.learning;

import com.jungwoo.project.memo.course.CourseService;
import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.learning.domain.CourseTopic;
import com.jungwoo.project.memo.learning.domain.TopicProgress;
import com.jungwoo.project.memo.learning.dto.TopicResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * ContextSnapshotService(Today 상담)와 같은 역할을 Learning Agent를 위해 수행한다.
 * 채팅 원문 전체나 자료 원문 전체를 매 호출마다 넘기지 않고, DB에 저장된 확정 상태(topic
 * 트리 + 진행 상태 + 과목 정보)로 만든 스냅샷 텍스트만 프롬프트에 담는다.
 */
@Component
@RequiredArgsConstructor
public class LearningContextBuilder {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final CourseService courseService;
    private final TopicService topicService;

    public String build(Long userId, CourseTopic focusTopic) {
        Course course = courseService.getOwned(userId, focusTopic.getCourseId());
        TopicProgress progress = topicService.getOrDefaultProgress(userId, focusTopic.getTopicId());

        StringBuilder sb = new StringBuilder();
        appendCourseHeader(sb, course);
        sb.append("\n[현재 학습 주제] #").append(focusTopic.getTopicId()).append(" ")
                .append(focusTopic.getTitle()).append(" (상태: ").append(progress.getStatus());
        if (progress.getLastStudiedAt() != null) {
            sb.append(", 마지막 학습: ").append(progress.getLastStudiedAt().format(DATE_FMT));
        }
        if (progress.getReviewCount() != null && progress.getReviewCount() > 0) {
            sb.append(", 복습 ").append(progress.getReviewCount()).append("회");
        }
        sb.append(")\n[전체 학습 구조와 진행 상태]\n");
        appendTree(sb, userId, course.getCourseId(), focusTopic.getTopicId());
        return sb.toString();
    }

    /** 추천처럼 특정 topic에 초점을 두지 않고 과목 전체 구조/진행 상태만 필요한 경우용. */
    public String buildForCourse(Long userId, Long courseId) {
        Course course = courseService.getOwned(userId, courseId);
        StringBuilder sb = new StringBuilder();
        appendCourseHeader(sb, course);
        sb.append("\n[전체 학습 구조와 진행 상태] (각 항목 앞 #번호는 topicId)\n");
        appendTree(sb, userId, courseId, null);
        return sb.toString();
    }

    private void appendCourseHeader(StringBuilder sb, Course course) {
        sb.append("[과목] ").append(course.getTitle());
        if (course.getTextbookTitle() != null) {
            sb.append(" (교재: ").append(course.getTextbookTitle());
            if (course.getTextbookAuthor() != null) {
                sb.append(", ").append(course.getTextbookAuthor());
            }
            sb.append(")");
        }
    }

    private void appendTree(StringBuilder sb, Long userId, Long courseId, Long focusTopicId) {
        List<TopicResponse> tree = topicService.getTopicTree(userId, courseId);
        for (TopicResponse root : tree) {
            appendNode(sb, root, 0, focusTopicId);
        }
    }

    private void appendNode(StringBuilder sb, TopicResponse node, int depth, Long focusTopicId) {
        sb.append("  ".repeat(depth)).append("- #").append(node.getTopicId()).append(" ")
                .append(node.getTitle()).append(" [").append(node.getProgressStatus()).append("]");
        if (node.getLastStudiedAt() != null) {
            sb.append(" (마지막 학습: ").append(node.getLastStudiedAt().format(DATE_FMT)).append(")");
        }
        if (focusTopicId != null && node.getTopicId().equals(focusTopicId)) {
            sb.append(" <- 현재 주제");
        }
        sb.append("\n");
        if (node.getChildren() != null) {
            for (TopicResponse child : node.getChildren()) {
                appendNode(sb, child, depth + 1, focusTopicId);
            }
        }
    }
}
