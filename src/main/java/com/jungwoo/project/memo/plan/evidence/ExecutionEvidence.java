package com.jungwoo.project.memo.plan.evidence;

import com.jungwoo.project.memo.execution.domain.ExecutionItemEvent;
import com.jungwoo.project.memo.execution.domain.ExecutionRecord;
import com.jungwoo.project.memo.execution.domain.ExecutionRecordOutcome;
import com.jungwoo.project.memo.execution.domain.ExecutionStatus;
import com.jungwoo.project.memo.execution.domain.PlacementType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 한 기간의 실행 기록을 <b>관찰 사실</b>로 묶은 것. 원인 해석은 여기 없다 — "세 번 옮김"은 옮긴 횟수이지
 * "어려워서 회피함"이 아니다. 그 해석은 모델이 가설로 내고 사용자가 확인한다.
 *
 * <p>실제 수행 시간은 기록에 실제로 남은 값만 실측이다. 완료 체크만 하고 시간을 적지 않은 항목은
 * {@code measuredMinutes = null}이고 "미기록"으로 표시한다 — 예정 시간을 실제 시간으로 바꿔 넣지 않는다.
 *
 * @param from    조회 창의 첫 날(포함)
 * @param to      조회 창의 마지막 날(포함)
 * @param items   창 안의 항목(삭제·취소 포함, 계획 항목 우선)
 * @param byCourse 프로젝트별 요약. 프로젝트 없는 항목은 키 null
 */
public record ExecutionEvidence(LocalDate from, LocalDate to, List<ItemHistory> items,
                                Map<Long, CourseSummary> byCourse) {

    public static ExecutionEvidence empty(LocalDate from, LocalDate to) {
        return new ExecutionEvidence(from, to, List.of(), Map.of());
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public List<ItemHistory> ofCourse(Long courseId) {
        return items.stream().filter(i -> java.util.Objects.equals(i.courseId(), courseId)).toList();
    }

    public List<ItemHistory> ofTopic(Long topicId) {
        return items.stream().filter(i -> topicId != null && topicId.equals(i.topicId())).toList();
    }

    /**
     * 항목 하나의 이력.
     *
     * @param plannedDate     확정 당시 계획 날짜(스냅샷). 계획 밖 항목이면 null
     * @param movedCount      다른 날짜로 옮긴 횟수(사용자·서버 배치 모두)
     * @param reducedCount    분량을 줄인 횟수
     * @param latestRecord    최신 실행 기록. 없으면 null
     * @param measuredMinutes 최신 기록에 실제로 적힌 수행 시간. 미기록이면 null
     * @param userNote        사용자가 기록에 남긴 메모. 없으면 null
     * @param leftoverOfId    일부 수행 뒤 남은 분량으로 갈라져 나온 원본 항목. 없으면 null
     */
    public record ItemHistory(
            Long executionItemId,
            Long courseId,
            Long topicId,
            Long planVersionId,
            String title,
            ExecutionStatus status,
            PlacementType placementType,
            LocalDate plannedDate,
            LocalDate currentDate,
            LocalDateTime deadlineAt,
            Integer expectedMinutes,
            boolean deleted,
            int movedCount,
            int reducedCount,
            boolean held,
            boolean reopened,
            ExecutionRecord latestRecord,
            int recordCount,
            Integer measuredMinutes,
            String userNote,
            Long leftoverOfId,
            Long version,
            LocalDateTime updatedAt,
            List<ExecutionItemEvent> events
    ) {
        /** 실행이 한 번이라도 시작됐는가(기록이 있거나 완료·일부 상태). 옮김만으로는 시작이 아니다. */
        public boolean started() {
            return recordCount > 0 || status == ExecutionStatus.DONE || status == ExecutionStatus.PARTIAL;
        }

        public ExecutionRecordOutcome latestOutcome() {
            return latestRecord == null ? null : latestRecord.getOutcome();
        }

        /** 기록은 있는데 시간이 없는 경우. 예정 시간을 대신 쓰지 않으려고 따로 구분한다. */
        public boolean unmeasured() {
            return recordCount > 0 && measuredMinutes == null;
        }
    }

    /**
     * 프로젝트 하나의 집계. 원인이 아니라 개수다.
     *
     * @param measuredMinutes  실제로 적힌 수행 시간의 합(측정)
     * @param unmeasuredDone   완료·일부로 표시했지만 시간이 없는 항목 수
     */
    public record CourseSummary(Long courseId, int total, int done, int partial, int remaining, int unplaced,
                                int held, int excluded, int moved, int measuredMinutes, int unmeasuredDone) {
    }
}
