package com.jungwoo.project.memo.assignment.dto;

import com.jungwoo.project.memo.assignment.domain.AssignmentConfirmStatus;
import com.jungwoo.project.memo.assignment.domain.CourseAssignment;
import com.jungwoo.project.memo.assignment.domain.DueKind;
import com.jungwoo.project.memo.assignment.domain.DueSource;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 과제 한 줄. 화면은 이 응답 하나로 "☐ 제목 · 9월 18일까지 · 자료 보기"를 그린다.
 *
 * <p>dueEstimates는 확정되지 않은 추정 후보다(원문 표현·해석 근거). 화면은 이것을 마감으로 그리지
 * 않고 "[이 날짜 맞아요]" 같은 확인 동작으로만 쓴다. overdue는 서버가 오늘 날짜(사용자 시간대)로 계산한다.
 */
@Getter
@Builder
public class AssignmentResponse {

    private Long assignmentId;
    private Long courseId;
    private Long materialId;
    private String materialFilename;
    private boolean materialDeleted;
    private Long sectionId;
    private String sectionLocator;
    private Long topicId;
    private String title;
    private String sourceQuote;
    private AssignmentConfirmStatus confirmStatus;
    private DueKind dueKind;
    private LocalDate dueDate;
    private LocalDateTime dueAt;
    private DueSource dueSource;
    private String dueQuote;
    private List<Map<String, Object>> dueEstimates;
    private LocalDateTime completedAt;
    private boolean completed;
    private boolean overdue;
    private boolean titleEdited;
    private boolean dueEdited;
    private Long duplicateOfAssignmentId;
    private String duplicateOfTitle;
    private Long version;
    private LocalDateTime createdAt;

    public static AssignmentResponse of(CourseAssignment a, LocalDate today, List<Map<String, Object>> estimates,
                                        String materialFilename, boolean materialDeleted, String sectionLocator,
                                        String duplicateOfTitle) {
        LocalDate dueDay = a.dueDay();
        return AssignmentResponse.builder()
                .assignmentId(a.getAssignmentId())
                .courseId(a.getCourseId())
                .materialId(a.getMaterialId())
                .materialFilename(materialFilename)
                .materialDeleted(materialDeleted)
                .sectionId(a.getSectionId())
                .sectionLocator(sectionLocator)
                .topicId(a.getTopicId())
                .title(a.getTitle())
                .sourceQuote(a.getSourceQuote())
                .confirmStatus(a.getConfirmStatus())
                .dueKind(a.getDueKind())
                .dueDate(a.getDueDate())
                .dueAt(a.getDueAt())
                .dueSource(a.getDueSource())
                .dueQuote(a.getDueQuote())
                .dueEstimates(estimates)
                .completedAt(a.getCompletedAt())
                .completed(a.isCompleted())
                .overdue(!a.isCompleted() && dueDay != null && today != null && dueDay.isBefore(today))
                .titleEdited(a.isTitleEdited())
                .dueEdited(a.isDueEdited())
                .duplicateOfAssignmentId(a.getDuplicateOfAssignmentId())
                .duplicateOfTitle(duplicateOfTitle)
                .version(a.getVersion())
                .createdAt(a.getCreatedAt())
                .build();
    }
}
