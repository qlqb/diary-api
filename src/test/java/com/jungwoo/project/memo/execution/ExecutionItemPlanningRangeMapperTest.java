package com.jungwoo.project.memo.execution;

import com.jungwoo.project.memo.execution.domain.ExecutionItem;
import com.jungwoo.project.memo.execution.domain.ExecutionOriginType;
import com.jungwoo.project.memo.execution.domain.ExecutionPriority;
import com.jungwoo.project.memo.execution.domain.ExecutionStatus;
import com.jungwoo.project.memo.execution.domain.PlacementType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * findByUserIdAndPlanningRange가 미배치(UNSCHEDULED) 조각을 잡고, 기존
 * findByUserIdAndDateRange는 같은 행을 잡지 않는다는 것을 실제 로컬 MariaDB(memo)에 대고
 * 나란히 검증한다.
 *
 * 두 질의의 "차이"가 이 단계의 산출물이다. 신규 질의가 잡는 것만 확인하면 주간 시간표에
 * 날짜 없는 조각이 흘러드는 회귀를 못 잡는다 — 그 화면은 날짜 칸에 그리므로 놓을 자리가 없다.
 * 그래서 모든 겹침 케이스를 두 질의에 동시에 물어본다.
 *
 * 겹침 판정(planning_start <= endDate AND planning_end >= startDate)은 네 가지 모양을
 * 전부 봐야 한다. 특히 "계획이 조회 범위를 완전히 포함하는" 경우를 놓치면 8월 한 달 계획이
 * 이번 주 화면에서 통째로 사라진다.
 *
 * 스키마가 레포에 없어 CI에서는 -PexcludeDbTests로 제외된다(build.gradle 참고).
 */
@SpringBootTest
class ExecutionItemPlanningRangeMapperTest {

    /** execution_items.user_id에는 users FK가 있어 실제 사용자 하나를 빌려 쓴다. */
    private Long testUserId;

    /** 조회 범위: 2026-08-24 ~ 2026-08-30 (이번 주). */
    private static final LocalDate RANGE_START = LocalDate.of(2026, 8, 24);
    private static final LocalDate RANGE_END = LocalDate.of(2026, 8, 30);

    @Autowired
    private ExecutionItemMapper executionItemMapper;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM execution_items WHERE title LIKE 'PRT-%'")) {
            ps.executeUpdate();
        }
    }

    // ===== 1. 신규 조회가 UNSCHEDULED + planning_* 를 잡는가 =====

    @Test
    void planningRange_findsUnscheduledItemWithOverlappingPlanningPeriod() {
        ExecutionItem unscheduled = insertUnscheduled("PRT-미배치", RANGE_START, RANGE_END);

        assertThat(planningRange()).extracting(ExecutionItem::getExecutionItemId)
                .contains(unscheduled.getExecutionItemId());
    }

    // ===== 2. 기존 조회는 같은 행을 안 잡는가 (주간 시간표 무영향) =====

    @Test
    void dateRange_doesNotFindTheSameUnscheduledItem() {
        ExecutionItem unscheduled = insertUnscheduled("PRT-미배치", RANGE_START, RANGE_END);

        assertThat(dateRange()).extracting(ExecutionItem::getExecutionItemId)
                .doesNotContain(unscheduled.getExecutionItemId());
    }

    @Test
    void bothQueries_returnScheduledItemsIdentically() {
        // 배치된 항목에 대해서는 두 질의가 같아야 한다. 달라지면 계획 화면과 시간표가
        // 서로 다른 "오늘"을 보여준다.
        ExecutionItem scheduled = insertScheduled("PRT-배치", LocalDate.of(2026, 8, 26), 0);

        assertThat(dateRange()).extracting(ExecutionItem::getExecutionItemId)
                .contains(scheduled.getExecutionItemId());
        assertThat(planningRange()).extracting(ExecutionItem::getExecutionItemId)
                .contains(scheduled.getExecutionItemId());
    }

    // ===== 3. 경계: 겹침 네 가지 모양 =====

    @Test
    void planningRange_overlapShapes_allFourAreCaught() {
        // (가) 계획이 조회 범위 안에 완전히 들어감
        ExecutionItem inside = insertUnscheduled("PRT-내부",
                LocalDate.of(2026, 8, 26), LocalDate.of(2026, 8, 28));
        // (나) 계획이 조회 범위를 완전히 포함  ← 놓치기 쉬운 케이스
        ExecutionItem covering = insertUnscheduled("PRT-포함",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));
        // (다) 앞쪽만 걸침: 계획이 범위 시작 전에 시작해 범위 안에서 끝남
        ExecutionItem leading = insertUnscheduled("PRT-앞걸침",
                LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 25));
        // (라) 뒤쪽만 걸침: 계획이 범위 안에서 시작해 범위 끝난 뒤 끝남
        ExecutionItem trailing = insertUnscheduled("PRT-뒤걸침",
                LocalDate.of(2026, 8, 29), LocalDate.of(2026, 9, 5));

        assertThat(planningRange()).extracting(ExecutionItem::getTitle)
                .contains("PRT-내부", "PRT-포함", "PRT-앞걸침", "PRT-뒤걸침");

        // 넷 다 기존 질의에는 안 잡혀야 한다.
        assertThat(dateRange()).extracting(ExecutionItem::getExecutionItemId)
                .doesNotContain(inside.getExecutionItemId(), covering.getExecutionItemId(),
                        leading.getExecutionItemId(), trailing.getExecutionItemId());
    }

    @Test
    void planningRange_touchingByExactlyOneDay_isCaught() {
        // 겹침이 하루뿐인 극단. planning_end == startDate, planning_start == endDate.
        insertUnscheduled("PRT-끝점앞", LocalDate.of(2026, 8, 10), RANGE_START);
        insertUnscheduled("PRT-끝점뒤", RANGE_END, LocalDate.of(2026, 9, 30));

        assertThat(planningRange()).extracting(ExecutionItem::getTitle)
                .contains("PRT-끝점앞", "PRT-끝점뒤");
    }

    @Test
    void planningRange_nonOverlappingPeriods_areNotCaught() {
        // 하루 차이로 안 겹치는 것은 빠져야 한다. 이게 빠지지 않으면 위 경계 통과는 의미가 없다.
        insertUnscheduled("PRT-이전", LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 23));
        insertUnscheduled("PRT-이후", LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 5));

        assertThat(planningRange()).extracting(ExecutionItem::getTitle)
                .doesNotContain("PRT-이전", "PRT-이후");
    }

    @Test
    void planningRange_unscheduledWithoutPlanningPeriod_isNotCaught() {
        // planning_* 가 비어 있으면 "계획에서 빼고 미분류로 보낸" 조각이다(§2-5 (나)).
        // 기간 조회에서 사라지는 것이 의도한 동작이다.
        insertUnscheduled("PRT-미분류", null, null);

        assertThat(planningRange()).extracting(ExecutionItem::getTitle).doesNotContain("PRT-미분류");
    }

    // ===== 4. 정렬: 배치된 항목이 미배치보다 먼저 =====

    @Test
    void planningRange_placedItemsComeBeforeUnplaced() {
        // 일부러 미배치를 먼저 넣어, 정렬이 삽입 순서가 아니라 배치 여부를 따르는지 본다.
        insertUnscheduled("PRT-미배치A", RANGE_START, RANGE_END);
        insertScheduled("PRT-배치늦은날", LocalDate.of(2026, 8, 30), 0);
        insertUnscheduled("PRT-미배치B", RANGE_START, RANGE_END);
        insertScheduled("PRT-배치이른날", LocalDate.of(2026, 8, 25), 0);

        List<String> titles = planningRange().stream()
                .map(ExecutionItem::getTitle)
                .filter(t -> t.startsWith("PRT-"))
                .toList();

        assertThat(titles).containsExactly(
                "PRT-배치이른날", "PRT-배치늦은날", "PRT-미배치A", "PRT-미배치B");
    }

    @Test
    void planningRange_placedItemsOnSameDay_orderByOrderIndex() {
        insertScheduled("PRT-둘째", LocalDate.of(2026, 8, 25), 5);
        insertScheduled("PRT-첫째", LocalDate.of(2026, 8, 25), 1);

        List<String> titles = planningRange().stream()
                .map(ExecutionItem::getTitle)
                .filter(t -> t.startsWith("PRT-"))
                .toList();

        assertThat(titles).containsExactly("PRT-첫째", "PRT-둘째");
    }

    @Test
    void planningRange_softDeletedItem_isNotCaught() {
        ExecutionItem deleted = insertUnscheduled("PRT-삭제됨", RANGE_START, RANGE_END);
        softDelete(deleted.getExecutionItemId());

        assertThat(planningRange()).extracting(ExecutionItem::getTitle).doesNotContain("PRT-삭제됨");
    }

    // ===== fixture =====

    private List<ExecutionItem> planningRange() {
        return executionItemMapper.findByUserIdAndPlanningRange(userId(), RANGE_START, RANGE_END);
    }

    private List<ExecutionItem> dateRange() {
        return executionItemMapper.findByUserIdAndDateRange(userId(), RANGE_START, RANGE_END);
    }

    private ExecutionItem insertUnscheduled(String title, LocalDate planningStart, LocalDate planningEnd) {
        ExecutionItem item = ExecutionItem.builder()
                .userId(userId())
                .title(title)
                .placementType(PlacementType.UNSCHEDULED)
                .planningStartDate(planningStart)
                .planningEndDate(planningEnd)
                .expectedMinutes(30)
                .status(ExecutionStatus.PLANNED)
                .priority(ExecutionPriority.SHOULD)
                .orderIndex(0)
                .originType(ExecutionOriginType.MANUAL)
                .modifiedAfterCreation(false)
                .version(0L)
                .isDeleted(false)
                .build();
        executionItemMapper.insert(item);
        return item;
    }

    private ExecutionItem insertScheduled(String title, LocalDate date, int orderIndex) {
        ExecutionItem item = ExecutionItem.builder()
                .userId(userId())
                .title(title)
                .placementType(PlacementType.DATE_ONLY)
                .scheduledDate(date)
                .expectedMinutes(30)
                .status(ExecutionStatus.PLANNED)
                .priority(ExecutionPriority.SHOULD)
                .orderIndex(orderIndex)
                .originType(ExecutionOriginType.MANUAL)
                .modifiedAfterCreation(false)
                .version(0L)
                .isDeleted(false)
                .build();
        executionItemMapper.insert(item);
        return item;
    }

    private void softDelete(Long executionItemId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE execution_items SET is_deleted = 1 WHERE execution_item_id = ?")) {
            ps.setLong(1, executionItemId);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("테스트 준비 실패", e);
        }
    }

    private Long userId() {
        if (testUserId != null) {
            return testUserId;
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT user_id FROM users ORDER BY user_id LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                throw new IllegalStateException("users 테이블이 비어 있어 테스트할 수 없다");
            }
            testUserId = rs.getLong(1);
            return testUserId;
        } catch (Exception e) {
            throw new IllegalStateException("테스트 준비 실패", e);
        }
    }
}
