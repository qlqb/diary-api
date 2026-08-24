package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.execution.ExecutionItemMapper;
import com.jungwoo.project.memo.execution.ExecutionRecordMapper;
import com.jungwoo.project.memo.execution.domain.ExecutionItem;
import com.jungwoo.project.memo.execution.domain.ExecutionRecord;
import com.jungwoo.project.memo.execution.domain.ExecutionRecordOutcome;
import com.jungwoo.project.memo.execution.domain.ExecutionStatus;
import com.jungwoo.project.memo.execution.domain.PlacementType;
import com.jungwoo.project.memo.plan.domain.PlanIntensity;
import com.jungwoo.project.memo.plan.domain.PlanVersion;
import com.jungwoo.project.memo.plan.dto.PlanReviewCategory;
import com.jungwoo.project.memo.plan.dto.PlanReviewMoveFlag;
import com.jungwoo.project.memo.plan.dto.PlanReviewResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * 회고 판정. 증명하려는 것은 "status가 주 분류를 단독으로 결정한다"이다.
 *
 * reopen()이 execution_records를 지우지 않으므로 "COMPLETED 기록이 있는 PLANNED 항목"이
 * 정상적으로 존재한다(실데이터의 item 19가 그렇다). 기록을 우선하면 사용자가 되돌린 항목이
 * 완료로 보이므로, 그 케이스를 여기서 고정한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlanReviewServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long PLAN_VERSION_ID = 100L;
    private static final LocalDate START = LocalDate.of(2026, 8, 24);
    private static final LocalDate END = LocalDate.of(2026, 8, 30);

    @Mock
    private PlanVersionMapper planVersionMapper;
    @Mock
    private ExecutionItemMapper executionItemMapper;
    @Mock
    private ExecutionRecordMapper executionRecordMapper;

    private final PlanSnapshotCodec codec = new PlanSnapshotCodec();
    private PlanReviewService service;

    @BeforeEach
    void setUp() {
        PlanVersionService versionService = new PlanVersionService(planVersionMapper, codec);
        service = new PlanReviewService(planVersionMapper, versionService, codec,
                executionItemMapper, executionRecordMapper);
        when(executionItemMapper.findInPeriodForReview(anyLong(), any(), any())).thenReturn(List.of());
        when(executionRecordMapper.findByExecutionItemIds(anyLong(), any())).thenReturn(List.of());
    }

    @Test
    void doneWithCompletedRecord_isDone() {
        givenPlan(snapshot(1L, "했어요", LocalDate.of(2026, 8, 25), 40));
        givenCurrent(item(1L, ExecutionStatus.DONE, PlacementType.DATE_ONLY, LocalDate.of(2026, 8, 25)));
        givenRecords(record(1L, ExecutionRecordOutcome.COMPLETED, 45, LocalDateTime.of(2026, 8, 25, 15, 0)));

        PlanReviewResponse review = service.review(USER_ID, PLAN_VERSION_ID);

        assertThat(review.getItems()).singleElement()
                .extracting(PlanReviewResponse.PlanReviewItem::getCategory)
                .isEqualTo(PlanReviewCategory.DONE);
        assertThat(review.getCompletedMinutes()).isEqualTo(45);
    }

    @Test
    void doneWithPartialRecord_isPartialDone() {
        givenPlan(snapshot(1L, "일부", LocalDate.of(2026, 8, 25), 40));
        givenCurrent(item(1L, ExecutionStatus.DONE, PlacementType.DATE_ONLY, LocalDate.of(2026, 8, 25)));
        givenRecords(record(1L, ExecutionRecordOutcome.PARTIAL, 20, LocalDateTime.of(2026, 8, 25, 15, 0)));

        assertThat(firstCategory()).isEqualTo(PlanReviewCategory.PARTIAL_DONE);
    }

    @Test
    void plannedWithCompletedRecord_isRemaining_becauseStatusWins() {
        // ★ 이 테스트가 이 서비스의 존재 이유다. 완료했다가 되돌린(REOPENED) 항목은
        // COMPLETED 기록이 남아 있지만 현재 상태는 PLANNED다. 기록을 우선하면 완료로 보인다.
        givenPlan(snapshot(1L, "되돌린 항목", LocalDate.of(2026, 8, 25), 40));
        givenCurrent(item(1L, ExecutionStatus.PLANNED, PlacementType.DATE_ONLY, LocalDate.of(2026, 8, 25)));
        givenRecords(record(1L, ExecutionRecordOutcome.COMPLETED, 40, LocalDateTime.of(2026, 8, 25, 15, 0)));

        PlanReviewResponse review = service.review(USER_ID, PLAN_VERSION_ID);

        assertThat(review.getItems().get(0).getCategory()).isEqualTo(PlanReviewCategory.REMAINING);
        assertThat(review.getCompletedMinutes()).as("되돌린 항목은 수행 시간에 세지 않는다").isZero();
    }

    @Test
    void plannedWithoutDate_isUnplaced() {
        givenPlan(snapshot(1L, "미배치", null, 30));
        givenCurrent(item(1L, ExecutionStatus.PLANNED, PlacementType.UNSCHEDULED, null));

        assertThat(firstCategory()).isEqualTo(PlanReviewCategory.UNPLACED);
    }

    @Test
    void holdAndCancelledAndDeleted_areSeparated() {
        givenPlan(snapshot(1L, "보류", LocalDate.of(2026, 8, 25), 30),
                snapshot(2L, "취소", LocalDate.of(2026, 8, 25), 30),
                snapshot(3L, "삭제", LocalDate.of(2026, 8, 25), 30));
        ExecutionItem deleted = item(3L, ExecutionStatus.PLANNED, PlacementType.DATE_ONLY, LocalDate.of(2026, 8, 25));
        deleted.setIsDeleted(true);
        givenCurrent(
                item(1L, ExecutionStatus.HOLD, PlacementType.DATE_ONLY, LocalDate.of(2026, 8, 25)),
                item(2L, ExecutionStatus.CANCELLED, PlacementType.DATE_ONLY, LocalDate.of(2026, 8, 25)),
                deleted);

        assertThat(service.review(USER_ID, PLAN_VERSION_ID).getItems())
                .extracting(PlanReviewResponse.PlanReviewItem::getCategory)
                .containsExactly(PlanReviewCategory.HOLD,
                        PlanReviewCategory.EXCLUDED, PlanReviewCategory.EXCLUDED);
    }

    // ===== moved 부가 플래그 =====

    @Test
    void movedToAnotherDay_setsMovedFlag_andKeepsPrimaryCategory() {
        givenPlan(snapshot(1L, "옮긴 항목", LocalDate.of(2026, 8, 25), 40));
        givenCurrent(item(1L, ExecutionStatus.DONE, PlacementType.DATE_ONLY, LocalDate.of(2026, 8, 27)));
        givenRecords(record(1L, ExecutionRecordOutcome.COMPLETED, 40, LocalDateTime.of(2026, 8, 27, 15, 0)));

        PlanReviewResponse.PlanReviewItem item = service.review(USER_ID, PLAN_VERSION_ID).getItems().get(0);

        // 주 분류와 부가 플래그가 동시에 성립해야 한다 — "완료했는데 다른 날 했다"가 흔하다.
        assertThat(item.getCategory()).isEqualTo(PlanReviewCategory.DONE);
        assertThat(item.getMoveFlag()).isEqualTo(PlanReviewMoveFlag.MOVED);
    }

    @Test
    void unscheduledThenDated_isScheduledNotMoved() {
        // 배치이지 이동이 아니다. "옮겼다"고 쓰면 사용자가 무언가 어긋났다고 읽는다.
        givenPlan(snapshot(1L, "배치된 항목", null, 40));
        givenCurrent(item(1L, ExecutionStatus.PLANNED, PlacementType.TIME_FIXED, LocalDate.of(2026, 8, 26)));

        assertThat(service.review(USER_ID, PLAN_VERSION_ID).getItems().get(0).getMoveFlag())
                .isEqualTo(PlanReviewMoveFlag.SCHEDULED);
    }

    @Test
    void sameDate_hasNoMoveFlag_andNullSafeComparison() {
        givenPlan(snapshot(1L, "그대로", null, 40));
        givenCurrent(item(1L, ExecutionStatus.PLANNED, PlacementType.UNSCHEDULED, null));

        // 양쪽 다 null이면 플래그가 없어야 한다. != 로 비교하면 UNKNOWN이 되어 조용히 틀린다.
        assertThat(service.review(USER_ID, PLAN_VERSION_ID).getItems().get(0).getMoveFlag()).isNull();
    }

    // ===== 기록 다건 =====

    @Test
    void multipleRecords_usesLatestAndReportsCount() {
        givenPlan(snapshot(1L, "기록 두 건", LocalDate.of(2026, 8, 25), 40));
        givenCurrent(item(1L, ExecutionStatus.DONE, PlacementType.DATE_ONLY, LocalDate.of(2026, 8, 25)));
        givenRecords(
                record(1L, ExecutionRecordOutcome.COMPLETED, 40, LocalDateTime.of(2026, 8, 25, 10, 0)),
                record(1L, ExecutionRecordOutcome.PARTIAL, 15, LocalDateTime.of(2026, 8, 25, 18, 0)));

        PlanReviewResponse.PlanReviewItem item = service.review(USER_ID, PLAN_VERSION_ID).getItems().get(0);

        assertThat(item.getCategory()).as("최신 기록(PARTIAL)이 판정을 결정한다")
                .isEqualTo(PlanReviewCategory.PARTIAL_DONE);
        assertThat(item.getActualMinutes()).isEqualTo(15);
        assertThat(item.getRecordCount()).as("여러 건이었다는 사실을 추적할 수 있어야 한다").isEqualTo(2);
    }

    // ===== 스냅샷 밖 =====

    @Test
    void itemInPeriodButNotInSnapshot_isOutsidePlan() {
        givenPlan(snapshot(1L, "계획 항목", LocalDate.of(2026, 8, 25), 40));
        givenCurrent(item(1L, ExecutionStatus.PLANNED, PlacementType.DATE_ONLY, LocalDate.of(2026, 8, 25)));
        when(executionItemMapper.findInPeriodForReview(anyLong(), any(), any())).thenReturn(List.of(
                item(1L, ExecutionStatus.PLANNED, PlacementType.DATE_ONLY, LocalDate.of(2026, 8, 25)),
                item(9L, ExecutionStatus.DONE, PlacementType.DATE_ONLY, LocalDate.of(2026, 8, 26))));

        assertThat(service.review(USER_ID, PLAN_VERSION_ID).getItems())
                .filteredOn(i -> i.getExecutionItemId().equals(9L))
                .singleElement()
                .extracting(PlanReviewResponse.PlanReviewItem::getCategory)
                .isEqualTo(PlanReviewCategory.OUTSIDE_PLAN);
    }

    @Test
    void remainderItem_isLeftover_trackedByRemainingExecutionItemId() {
        // 잔여분 추적은 execution_records.remaining_execution_item_id가 1급 근거다 —
        // DB가 outcome=PARTIAL일 때만 이 값을 갖도록 강제하므로 가장 확실하다.
        givenPlan(snapshot(1L, "부분 완료된 원본", LocalDate.of(2026, 8, 25), 40));
        givenCurrent(item(1L, ExecutionStatus.DONE, PlacementType.DATE_ONLY, LocalDate.of(2026, 8, 25)));
        ExecutionRecord partial = record(1L, ExecutionRecordOutcome.PARTIAL, 20, LocalDateTime.of(2026, 8, 25, 18, 0));
        partial.setRemainingExecutionItemId(50L);
        givenRecords(partial);
        when(executionItemMapper.findInPeriodForReview(anyLong(), any(), any())).thenReturn(List.of(
                item(50L, ExecutionStatus.PLANNED, PlacementType.DATE_ONLY, LocalDate.of(2026, 8, 25))));

        assertThat(service.review(USER_ID, PLAN_VERSION_ID).getItems())
                .filteredOn(i -> i.getExecutionItemId().equals(50L))
                .singleElement()
                .extracting(PlanReviewResponse.PlanReviewItem::getCategory)
                .isEqualTo(PlanReviewCategory.LEFTOVER);
    }

    // ===== fixture =====

    private PlanReviewCategory firstCategory() {
        return service.review(USER_ID, PLAN_VERSION_ID).getItems().get(0).getCategory();
    }

    private void givenPlan(com.jungwoo.project.memo.plan.domain.PlanSnapshotItem... items) {
        when(planVersionMapper.findByIdAndUserId(PLAN_VERSION_ID, USER_ID)).thenReturn(
                PlanVersion.builder()
                        .planVersionId(PLAN_VERSION_ID).userId(USER_ID).planKey("k").version(1)
                        .startDate(START).endDate(END).title("이번 주")
                        .intensity(PlanIntensity.NORMAL).targetMinutes(600)
                        .itemsSnapshot(codec.toJson(List.of(items)))
                        .build());
    }

    private void givenCurrent(ExecutionItem... items) {
        when(executionItemMapper.findByIdsForReview(anyLong(), any())).thenReturn(List.of(items));
    }

    private void givenRecords(ExecutionRecord... records) {
        when(executionRecordMapper.findByExecutionItemIds(anyLong(), any())).thenReturn(List.of(records));
    }

    private com.jungwoo.project.memo.plan.domain.PlanSnapshotItem snapshot(
            Long id, String title, LocalDate date, Integer minutes) {
        return new com.jungwoo.project.memo.plan.domain.PlanSnapshotItem(
                id, title, minutes, "SHOULD", 6L, "자료구조", null,
                date != null ? PlacementType.DATE_ONLY : PlacementType.UNSCHEDULED,
                date, null, null,
                date == null ? START : null, date == null ? END : null, null);
    }

    private ExecutionItem item(Long id, ExecutionStatus status, PlacementType placement, LocalDate date) {
        return ExecutionItem.builder()
                .executionItemId(id).userId(USER_ID).title("항목 " + id)
                .status(status).placementType(placement).scheduledDate(date)
                .expectedMinutes(40).isDeleted(false)
                .build();
    }

    private ExecutionRecord record(Long itemId, ExecutionRecordOutcome outcome, Integer actual, LocalDateTime at) {
        return ExecutionRecord.builder()
                .executionRecordId(itemId * 10).userId(USER_ID).executionItemId(itemId)
                .outcome(outcome).actualMinutes(actual).recordedAt(at)
                .build();
    }
}
