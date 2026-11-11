package com.jungwoo.project.memo.assignment;

import com.jungwoo.project.memo.assignment.domain.CourseAssignment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 아직 적용하지 않은 과제 변경(마감 정정·완료). assignmentId → 바뀐 과제(복사본). 조회 결과에 겹쳐 쓴다.
 */
public final class AssignmentOverlay {

    public static final AssignmentOverlay NONE = new AssignmentOverlay(Map.of());

    private final Map<Long, CourseAssignment> changes;

    private AssignmentOverlay(Map<Long, CourseAssignment> changes) {
        this.changes = changes;
    }

    public boolean isEmpty() {
        return changes.isEmpty();
    }

    public List<CourseAssignment> apply(List<CourseAssignment> fromDb, Predicate<CourseAssignment> condition) {
        if (changes.isEmpty()) {
            return fromDb;
        }
        List<CourseAssignment> out = new ArrayList<>();
        for (CourseAssignment a : fromDb) {
            out.add(changes.containsKey(a.getAssignmentId()) ? null : a);
        }
        out.removeIf(java.util.Objects::isNull);
        for (CourseAssignment changed : changes.values()) {
            if (condition.test(changed)) {
                out.add(changed);
            }
        }
        return out;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Map<Long, CourseAssignment> changes = new LinkedHashMap<>();

        public Builder put(CourseAssignment assignment) {
            changes.put(assignment.getAssignmentId(), assignment);
            return this;
        }

        public AssignmentOverlay build() {
            return changes.isEmpty() ? NONE : new AssignmentOverlay(Collections.unmodifiableMap(new LinkedHashMap<>(changes)));
        }
    }
}
