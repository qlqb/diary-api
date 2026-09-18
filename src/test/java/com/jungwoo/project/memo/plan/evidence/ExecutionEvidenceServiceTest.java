package com.jungwoo.project.memo.plan.evidence;

import com.jungwoo.project.memo.execution.ExecutionItemEventMapper;
import com.jungwoo.project.memo.execution.ExecutionItemMapper;
import com.jungwoo.project.memo.execution.ExecutionRecordMapper;
import com.jungwoo.project.memo.execution.domain.ExecutionEventType;
import com.jungwoo.project.memo.execution.domain.ExecutionItem;
import com.jungwoo.project.memo.execution.domain.ExecutionItemEvent;
import com.jungwoo.project.memo.execution.domain.ExecutionRecord;
import com.jungwoo.project.memo.execution.domain.ExecutionRecordOutcome;
import com.jungwoo.project.memo.execution.domain.ExecutionStatus;
import com.jungwoo.project.memo.execution.domain.PlacementType;
import com.jungwoo.project.memo.plan.PlanSnapshotCodec;
import com.jungwoo.project.memo.plan.PlanVersionMapper;
import com.jungwoo.project.memo.plan.domain.PlanSnapshotItem;
import com.jungwoo.project.memo.plan.domain.PlanVersion;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 실행 기록 근거는 관찰 사실이다. 실제 시간이 없으면 "미기록"이지 예정 시간이 아니고, 옮긴 횟수는 원인이 아니다.
 */
class ExecutionEvidenceServiceTest {

    private static final long USER = 7L;
    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 20);

    private final ExecutionItemMapper items = mock(ExecutionItemMapper.class);
    private final ExecutionRecordMapper records = mock(ExecutionRecordMapper.class);
    private final ExecutionItemEventMapper events = mock(ExecutionItemEventMapper.class);
    private final PlanVersionMapper plans = mock(PlanVersionMapper.class);
    private final PlanSnapshotCodec codec = new PlanSnapshotCodec();
    private final ExecutionEvidenceService service = new ExecutionEvidenceService(items, records, events, plans, codec);

    @Test
    void 실측_미기록_이동을_구분해_항목별로_남기고_요약도_실측만_센다() {
        ExecutionItem done = item(1L, 6L, 44L, ExecutionStatus.DONE, PlacementType.DATE_ONLY, LocalDate.of(2026, 9, 12), 45);
        ExecutionItem unmeasured = item(2L, 6L, 45L, ExecutionStatus.DONE, PlacementType.DATE_ONLY, LocalDate.of(2026, 9, 13), 30);
        ExecutionItem moved = item(3L, 6L, 46L, ExecutionStatus.PLANNED, PlacementType.DATE_ONLY, LocalDate.of(2026, 9, 18), 40);
        ExecutionItem other = item(4L, 9L, null, ExecutionStatus.PLANNED, PlacementType.UNSCHEDULED, null, 20);
        when(items.findInPeriodForReview(USER, FROM, TO)).thenReturn(List.of(done, unmeasured, moved, other));
        when(records.findByExecutionItemIds(eq(USER), any())).thenReturn(List.of(
                record(1L, ExecutionRecordOutcome.PARTIAL, 20, "반복문에서 막힘", LocalDateTime.of(2026, 9, 12, 21, 0)),
                record(2L, ExecutionRecordOutcome.COMPLETED, null, null, LocalDateTime.of(2026, 9, 13, 21, 0))));
        when(events.findByExecutionItemIds(eq(USER), any())).thenReturn(List.of(
                event(3L, ExecutionEventType.MOVED), event(3L, ExecutionEventType.MOVED), event(3L, ExecutionEventType.REDUCED)));
        moved.setPlanVersionId(500L);
        when(plans.findByIdAndUserId(500L, USER)).thenReturn(PlanVersion.builder().planVersionId(500L).itemsSnapshot(
                codec.toJson(List.of(new PlanSnapshotItem(3L, "항목 3", 60, "SHOULD", 6L, "자료구조", 46L,
                        PlacementType.DATE_ONLY, LocalDate.of(2026, 9, 15), null, null, null, null, null)))).build());

        ExecutionEvidence evidence = service.collect(USER, FROM, TO, List.of(6L));

        assertThat(evidence.items()).extracting(ExecutionEvidence.ItemHistory::executionItemId).containsExactly(1L, 2L, 3L);
        ExecutionEvidence.ItemHistory h1 = evidence.items().get(0);
        assertThat(h1.measuredMinutes()).isEqualTo(20);
        assertThat(h1.userNote()).isEqualTo("반복문에서 막힘");
        assertThat(ExecutionEvidenceService.describe(h1)).contains("일부 수행(실제 20분, 측정)").contains("사용자 메모: \"반복문에서 막힘\"");
        ExecutionEvidence.ItemHistory h2 = evidence.items().get(1);
        assertThat(h2.measuredMinutes()).isNull();
        assertThat(h2.unmeasured()).isTrue();
        assertThat(ExecutionEvidenceService.describe(h2)).contains("완료(실제 시간 미기록)").doesNotContain("30분, 측정");
        ExecutionEvidence.ItemHistory h3 = evidence.items().get(2);
        assertThat(h3.movedCount()).isEqualTo(2);
        assertThat(h3.reducedCount()).isEqualTo(1);
        assertThat(h3.plannedDate()).isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(h3.started()).isFalse();
        assertThat(ExecutionEvidenceService.describe(h3)).contains("계획 9/15").contains("지금 9/18").contains("옮김 2회")
                .contains("아직 시작 안 함").doesNotContain("회피");
        ExecutionEvidence.CourseSummary summary = evidence.byCourse().get(6L);
        assertThat(summary.measuredMinutes()).isEqualTo(20);
        assertThat(summary.unmeasuredDone()).isEqualTo(1);
        assertThat(summary.moved()).isEqualTo(1);
        assertThat(ExecutionEvidenceService.summaryLine(summary)).contains("실제 측정 20분").contains("시간 미기록 1건");
    }

    @Test
    void 기간이_뒤집히거나_항목이_없으면_빈_근거다() {
        when(items.findInPeriodForReview(anyLong(), any(), any())).thenReturn(List.of());
        assertThat(service.collect(USER, FROM, TO, List.of()).isEmpty()).isTrue();
        assertThat(service.collect(USER, TO, FROM, List.of()).isEmpty()).isTrue();
    }

    private static ExecutionItem item(Long id, Long courseId, Long topicId, ExecutionStatus status, PlacementType placement,
                                      LocalDate date, Integer minutes) {
        return ExecutionItem.builder().executionItemId(id).userId(USER).courseId(courseId).topicId(topicId).title("항목 " + id)
                .status(status).placementType(placement).scheduledDate(date).expectedMinutes(minutes).isDeleted(false)
                .version(1L).build();
    }

    private static ExecutionRecord record(Long itemId, ExecutionRecordOutcome outcome, Integer actual, String note,
                                          LocalDateTime at) {
        return ExecutionRecord.builder().executionRecordId(itemId * 10).userId(USER).executionItemId(itemId)
                .outcome(outcome).actualMinutes(actual).note(note).recordedAt(at).build();
    }

    private static ExecutionItemEvent event(Long itemId, ExecutionEventType type) {
        return ExecutionItemEvent.builder().executionItemId(itemId).userId(USER).eventType(type)
                .occurredAt(LocalDateTime.of(2026, 9, 14, 9, 0)).build();
    }
}
