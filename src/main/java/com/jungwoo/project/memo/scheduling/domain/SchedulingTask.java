package com.jungwoo.project.memo.scheduling.domain;

import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.common.PlanningId;
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider;
import ai.timefold.solver.core.api.domain.variable.PlanningVariable;
import com.jungwoo.project.memo.execution.domain.ExecutionPriority;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Timefold 계산 전용 모델. AiProposalItem(제안 항목) 하나를 배치 대상 하나로 표현한다.
 * DB 엔티티(ExecutionItem, AiProposalItem)와 분리된 별도 모델이며, 서로 어노테이션이 섞이지
 * 않는다 — id는 대응하는 ai_proposal_items.proposal_item_id를 그대로 담아 결과를 다시 매핑할
 * 때만 쓴다.
 *
 * candidateStarts는 이 후보가 실제로 시작할 수 있는 시각의 목록이다(값 범위). 이미
 * AvailabilityEstimateService가 "가용시간 안", "현재 시각 이후", "earliestStart 이후",
 * "deadline 안에 끝남" 조건을 만족하는 시각만 걸러 넣으므로, 이 값 범위 밖으로는 애초에
 * 배치될 수 없다 — 나머지(다른 후보와 겹치지 않기, 기존 일정과 겹치지 않기)만 제약으로 남는다.
 */
@PlanningEntity
public class SchedulingTask {

    private Long proposalItemId;
    private String title;
    private int durationMinutes;
    private ExecutionPriority priority;
    private LocalDateTime deadline;
    /**
     * 이 배치가 속한 프로젝트. 순서 제약(preferOrderIndexSequence)이 같은 프로젝트 안에서만
     * 비교하기 위해 쓴다 — 과목이 다르면 선행 관계가 없다.
     */
    private Long courseId;
    /**
     * 계획이 나열한 순서. AI가 낸 배열 순서가 execution_items.order_index로 살아남은 값이다.
     * "진짜 선행 관계"가 아니라 "이 순서로 하자고 제안된 것"이므로 소프트 제약으로만 쓴다.
     */
    private Integer orderIndex;
    private List<TimeSlotOption> candidateStarts;

    @PlanningVariable(valueRangeProviderRefs = "taskStartRange", allowsUnassigned = true)
    private TimeSlotOption assignedSlot;

    public SchedulingTask() {
        // Timefold가 클론을 만들 때 필요하다.
    }

    public SchedulingTask(Long proposalItemId, String title, int durationMinutes,
                           ExecutionPriority priority, LocalDateTime deadline,
                           List<TimeSlotOption> candidateStarts) {
        this(proposalItemId, title, durationMinutes, priority, deadline, null, null, candidateStarts);
    }

    public SchedulingTask(Long proposalItemId, String title, int durationMinutes,
                           ExecutionPriority priority, LocalDateTime deadline,
                           Long courseId, Integer orderIndex,
                           List<TimeSlotOption> candidateStarts) {
        this.proposalItemId = proposalItemId;
        this.title = title;
        this.durationMinutes = durationMinutes;
        this.priority = priority;
        this.deadline = deadline;
        this.courseId = courseId;
        this.orderIndex = orderIndex;
        this.candidateStarts = candidateStarts;
    }

    @ValueRangeProvider(id = "taskStartRange")
    public List<TimeSlotOption> getCandidateStarts() {
        return candidateStarts;
    }

    @PlanningId
    public Long getProposalItemId() {
        return proposalItemId;
    }

    public String getTitle() {
        return title;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public ExecutionPriority getPriority() {
        return priority;
    }

    public LocalDateTime getDeadline() {
        return deadline;
    }

    public Long getCourseId() {
        return courseId;
    }

    public Integer getOrderIndex() {
        return orderIndex;
    }

    public TimeSlotOption getAssignedSlot() {
        return assignedSlot;
    }

    public void setAssignedSlot(TimeSlotOption assignedSlot) {
        this.assignedSlot = assignedSlot;
    }

    public LocalDateTime getAssignedStart() {
        return assignedSlot != null ? assignedSlot.startAt() : null;
    }

    public LocalDateTime getAssignedEnd() {
        return assignedSlot != null ? assignedSlot.startAt().plusMinutes(durationMinutes) : null;
    }

    public boolean isScheduled() {
        return assignedSlot != null;
    }
}
