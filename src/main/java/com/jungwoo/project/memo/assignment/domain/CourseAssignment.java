package com.jungwoo.project.memo.assignment.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 과제. course_assignments. 제목·마감·완료 체크가 전부다 — 진행 단계나 작업 조각을 두지 않는다.
 *
 * <p>completedAt은 "사용자가 끝냈다고 체크했다"는 사실이다. AI는 이 값을 쓰지 않는다.
 * 마감은 세 가지를 구분한다: 미확인(UNKNOWN) / 없음(NONE) / 있음(DATE·DATETIME). 추정 마감은
 * dueEstimateJson에만 있고, 사용자가 확정하기 전에는 dueDate·dueAt에 들어가지 않는다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CourseAssignment {

    private Long assignmentId;
    private Long userId;
    private Long courseId;
    private Long materialId;
    private Long sectionId;
    private Long topicId;
    private String title;
    private String sourceQuote;
    private AssignmentConfirmStatus confirmStatus;
    private DueKind dueKind;
    private LocalDate dueDate;
    private LocalDateTime dueAt;
    private DueSource dueSource;
    private String dueQuote;
    private String dueEstimateJson;
    private LocalDateTime completedAt;
    private boolean titleEdited;
    private boolean dueEdited;
    private Long duplicateOfAssignmentId;
    private String dedupeKey;
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public boolean isCompleted() {
        return completedAt != null;
    }

    /** 마감이 확정돼 있는가(추정이 아니라). */
    public boolean hasDue() {
        return dueKind == DueKind.DATE || dueKind == DueKind.DATETIME;
    }

    /** 마감의 날짜 부분. 시각 마감이면 그 날짜, 날짜 마감이면 그대로. 없으면 null. */
    public LocalDate dueDay() {
        if (dueKind == DueKind.DATE) {
            return dueDate;
        }
        if (dueKind == DueKind.DATETIME && dueAt != null) {
            return dueAt.toLocalDate();
        }
        return null;
    }
}
