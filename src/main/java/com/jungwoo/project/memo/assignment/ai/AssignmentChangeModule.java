package com.jungwoo.project.memo.assignment.ai;

import com.jungwoo.project.memo.ai.change.ChangeCheck;
import com.jungwoo.project.memo.ai.change.ChangeModule;
import com.jungwoo.project.memo.ai.change.ChangeOp;
import com.jungwoo.project.memo.assignment.CourseAssignmentMapper;
import com.jungwoo.project.memo.assignment.CourseAssignmentService;
import com.jungwoo.project.memo.assignment.domain.CourseAssignment;
import com.jungwoo.project.memo.assignment.domain.DueKind;
import com.jungwoo.project.memo.assignment.dto.AssignmentRequests;
import com.jungwoo.project.memo.course.CourseMapper;
import com.jungwoo.project.memo.scheduling.scenario.Scenario;
import com.jungwoo.project.memo.scheduling.scenario.ScenarioProjection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 과제의 상담 변경: 마감 정정·제출(완료) 표시·만들기. 규칙은 {@link CourseAssignmentService}에 있다 — 마감 정정은 사용자 확정(USER)으로
 * 남아 자료 재분석이 덮지 못한다(due_edited). 확정 전에는 가정 상태에서만 마감이 바뀐다.
 */
@Component
@RequiredArgsConstructor
public class AssignmentChangeModule implements ChangeModule {

    private final CourseAssignmentMapper assignmentMapper;
    private final CourseAssignmentService assignmentService;
    private final CourseMapper courseMapper;

    public record SetDue(CourseAssignment before, DueKind dueKind, LocalDate dueDate, LocalDateTime dueAt)
            implements ChangeOp, ScenarioProjection {
        public String type() {
            return "ASSIGNMENT_DUE";
        }

        public String targetKey() {
            return "assignment:" + before.getAssignmentId();
        }

        public String summary() {
            return before.getTitle() + " 마감을 " + (dueAt != null ? dueAt.toLocalDate() + " " + dueAt.toLocalTime()
                    : dueDate) + "로 바로잡기";
        }

        public List<LocalDate> affectedDates() {
            return List.of();
        }

        public void project(Scenario.Builder s) {
            CourseAssignment copy = copy(before);
            copy.setDueKind(dueKind);
            copy.setDueDate(dueDate);
            copy.setDueAt(dueAt);
            s.assignments().put(copy);
        }
    }

    public record SetCompleted(CourseAssignment before, boolean completed) implements ChangeOp, ScenarioProjection {
        public String type() {
            return "ASSIGNMENT_COMPLETED";
        }

        public String targetKey() {
            return "assignment:" + before.getAssignmentId();
        }

        public String summary() {
            return before.getTitle() + (completed ? " 제출(완료) 표시" : " 완료 표시 취소");
        }

        public void project(Scenario.Builder s) {
            CourseAssignment copy = copy(before);
            copy.setCompletedAt(completed ? LocalDateTime.of(2000, 1, 1, 0, 0) : null);
            s.assignments().put(copy);
        }
    }

    public record CreateAssignment(Long courseId, String title, DueKind dueKind, LocalDate dueDate, LocalDateTime dueAt)
            implements ChangeOp {
        public String type() {
            return "ASSIGNMENT_CREATE";
        }

        public String targetKey() {
            return "assignment:new:" + courseId + ":" + title;
        }

        public String summary() {
            return "과제 추가: " + title + (dueDate == null && dueAt == null ? "" : " (마감 " + (dueAt != null ? dueAt : dueDate) + ")");
        }
    }

    @Override
    public Map<String, Class<? extends ChangeOp>> opTypes() {
        return Map.of("ASSIGNMENT_DUE", SetDue.class, "ASSIGNMENT_COMPLETED", SetCompleted.class,
                "ASSIGNMENT_CREATE", CreateAssignment.class);
    }

    @Override
    public int lockOrder() {
        return 40;
    }

    @Override
    public ChangeCheck check(Long userId, ChangeOp op) {
        if (op instanceof CreateAssignment c) {
            if (c.title() == null || c.title().isBlank()) {
                return ChangeCheck.fail(ChangeCheck.INVALID, "과제 제목이 필요해요", op);
            }
            if (c.courseId() != null && courseMapper.findByIdAndUserId(c.courseId(), userId) == null) {
                return ChangeCheck.fail(ChangeCheck.NOT_FOUND, "그 프로젝트를 찾지 못했어요", op);
            }
            return ChangeCheck.ok(op);
        }
        CourseAssignment before = op instanceof SetDue d ? d.before() : ((SetCompleted) op).before();
        CourseAssignment current = before == null ? null : assignmentMapper.findByIdAndUserId(before.getAssignmentId(), userId);
        if (current == null) {
            return ChangeCheck.fail(ChangeCheck.NOT_FOUND, "그 과제를 찾지 못했어요", op);
        }
        return switch (op) {
            case SetDue d -> d.dueKind() == null || (d.dueKind() == DueKind.DATE && d.dueDate() == null)
                    || (d.dueKind() == DueKind.DATETIME && d.dueAt() == null)
                    ? ChangeCheck.fail(ChangeCheck.INVALID, "마감 날짜(또는 시각)가 필요해요", op)
                    : ChangeCheck.ok(new SetDue(current, d.dueKind(), d.dueDate(), d.dueAt()));
            case SetCompleted c -> (current.getCompletedAt() != null) == c.completed()
                    ? ChangeCheck.fail(ChangeCheck.ALREADY_DONE, c.completed() ? "이미 완료로 표시돼 있어요" : "완료 표시가 없어요", op)
                    : ChangeCheck.ok(new SetCompleted(current, c.completed()));
            default -> throw new IllegalArgumentException(op.type());
        };
    }

    @Override
    public void lock(Long userId, List<ChangeOp> ops) {
        // 과제 갱신은 version 조건 갱신이 경합을 막는다(서비스와 같다).
    }

    @Override
    public ChangeCheck recheck(Long userId, ChangeOp op) {
        CourseAssignment before = op instanceof SetDue d ? d.before() : op instanceof SetCompleted c ? c.before() : null;
        if (before == null) {
            return ChangeCheck.ok(op);
        }
        CourseAssignment current = assignmentMapper.findByIdAndUserId(before.getAssignmentId(), userId);
        if (current == null) {
            return ChangeCheck.fail(ChangeCheck.STALE, "그 과제가 지워졌어요", op);
        }
        return Objects.equals(current.getVersion(), before.getVersion()) ? ChangeCheck.ok(op)
                : ChangeCheck.fail(ChangeCheck.STALE, current.getTitle() + "이 검토한 뒤 바뀌었어요", op);
    }

    @Override
    public Map<String, Object> apply(Long userId, ChangeOp op) {
        return switch (op) {
            case SetDue d -> {
                AssignmentRequests.Due req = new AssignmentRequests.Due();
                req.setDueKind(d.dueKind());
                req.setDueDate(d.dueDate());
                req.setDueAt(d.dueAt());
                req.setVersion(d.before().getVersion());
                yield Map.of("assignmentId", assignmentService.setDue(userId, d.before().getAssignmentId(), req).getAssignmentId());
            }
            case SetCompleted c -> {
                AssignmentRequests.Complete req = new AssignmentRequests.Complete();
                req.setCompleted(c.completed());
                req.setVersion(c.before().getVersion());
                yield Map.of("assignmentId", assignmentService.setCompleted(userId, c.before().getAssignmentId(), req)
                        .getAssignmentId());
            }
            case CreateAssignment c -> {
                AssignmentRequests.Create req = new AssignmentRequests.Create();
                req.setCourseId(c.courseId());
                req.setTitle(c.title());
                req.setDueKind(c.dueKind());
                req.setDueDate(c.dueDate());
                req.setDueAt(c.dueAt());
                yield Map.of("assignmentId", assignmentService.create(userId, req).getAssignmentId());
            }
            default -> throw new IllegalArgumentException(op.type());
        };
    }

    static CourseAssignment copy(CourseAssignment a) {
        return CourseAssignment.builder().assignmentId(a.getAssignmentId()).userId(a.getUserId()).courseId(a.getCourseId())
                .materialId(a.getMaterialId()).sectionId(a.getSectionId()).topicId(a.getTopicId()).title(a.getTitle())
                .sourceQuote(a.getSourceQuote()).confirmStatus(a.getConfirmStatus()).dueKind(a.getDueKind())
                .dueDate(a.getDueDate()).dueAt(a.getDueAt()).dueSource(a.getDueSource()).dueQuote(a.getDueQuote())
                .dueEstimateJson(a.getDueEstimateJson()).completedAt(a.getCompletedAt()).titleEdited(a.isTitleEdited())
                .dueEdited(a.isDueEdited()).duplicateOfAssignmentId(a.getDuplicateOfAssignmentId()).dedupeKey(a.getDedupeKey())
                .version(a.getVersion()).build();
    }
}
