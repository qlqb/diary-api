package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.execution.domain.ExecutionItem;
import com.jungwoo.project.memo.execution.domain.ExecutionPriority;
import com.jungwoo.project.memo.execution.domain.PlacementType;
import com.jungwoo.project.memo.plan.domain.PlanSnapshotItem;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 스냅샷은 한 번 쓰면 고칠 수 없다(PlanVersionMapper에 update가 없다). 그래서 잘못된
 * 스냅샷은 나중에 바로잡는 것이 아니라 애초에 저장되지 않아야 하고, 이 테스트는 그 방어선이
 * 실제로 막는지를 본다.
 */
class PlanSnapshotCodecTest {

    private final PlanSnapshotCodec codec = new PlanSnapshotCodec();

    @Test
    void toJson_thenFromJson_preservesScheduledTimes() {
        // 시각까지 살아남아야 "14시에 하려던 걸 22시에 했다"를 회고할 수 있다.
        PlanSnapshotItem item = new PlanSnapshotItem(
                123L, "연결 리스트 삭제 예제", 40, "MUST", 6L, "자료구조", null,
                PlacementType.TIME_FIXED,
                LocalDate.of(2026, 8, 25),
                LocalDateTime.of(2026, 8, 25, 14, 0),
                LocalDateTime.of(2026, 8, 25, 14, 40),
                null, null, "포인터를 이미 아니까 구현부터");

        List<PlanSnapshotItem> back = codec.fromJson(codec.toJson(List.of(item)));

        assertThat(back).hasSize(1);
        assertThat(back.get(0)).isEqualTo(item);
        assertThat(back.get(0).scheduledStartAt()).isEqualTo(LocalDateTime.of(2026, 8, 25, 14, 0));
        assertThat(back.get(0).scheduledEndAt()).isEqualTo(LocalDateTime.of(2026, 8, 25, 14, 40));
    }

    @Test
    void toJson_preservesPlanningRangeForUnscheduledItem() {
        PlanSnapshotItem item = unscheduled(200L, 6L);

        List<PlanSnapshotItem> back = codec.fromJson(codec.toJson(List.of(item)));

        assertThat(back.get(0).planningStartDate()).isEqualTo(LocalDate.of(2026, 8, 24));
        assertThat(back.get(0).planningEndDate()).isEqualTo(LocalDate.of(2026, 8, 30));
        assertThat(back.get(0).scheduledDate()).isNull();
    }

    @Test
    void toJson_missingExecutionItemId_isRejected() {
        PlanSnapshotItem noId = new PlanSnapshotItem(
                null, "id 없는 항목", 30, "SHOULD", 6L, "자료구조", null,
                PlacementType.UNSCHEDULED, null, null, null,
                LocalDate.of(2026, 8, 24), LocalDate.of(2026, 8, 30), null);

        assertThatThrownBy(() -> codec.toJson(List.of(noId)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("executionItemId");
    }

    @Test
    void toJson_duplicateExecutionItemId_isRejected() {
        // 같은 조각이 두 번 들어가면 회고에서 한 항목이 두 줄로 잡힌다.
        assertThatThrownBy(() -> codec.toJson(List.of(unscheduled(1L, 6L), unscheduled(1L, 7L))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("두 번");
    }

    @Test
    void toJson_emptySnapshot_isRejected() {
        assertThatThrownBy(() -> codec.toJson(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.toJson(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fromJson_unknownField_isIgnored() {
        // 스냅샷은 몇 달 뒤에도 읽혀야 한다. 필드가 늘어난 뒤 저장된 JSON을 옛 코드가,
        // 또는 그 반대를 읽을 때 통째로 실패하면 과거 계획의 회고가 전부 막힌다.
        String json = "[{\"executionItemId\":1,\"title\":\"t\",\"someFutureField\":\"x\"}]";

        List<PlanSnapshotItem> back = codec.fromJson(json);

        assertThat(back).hasSize(1);
        assertThat(back.get(0).executionItemId()).isEqualTo(1L);
    }

    @Test
    void fromJson_nullOrBlank_returnsEmptyList() {
        assertThat(codec.fromJson(null)).isEmpty();
        assertThat(codec.fromJson("   ")).isEmpty();
    }

    @Test
    void containsCourse_matchesOnlyWhenSnapshotHoldsThatCourse() {
        String json = codec.toJson(List.of(unscheduled(1L, 6L), unscheduled(2L, 7L)));

        assertThat(codec.containsCourse(json, 6L)).isTrue();
        assertThat(codec.containsCourse(json, 7L)).isTrue();
        assertThat(codec.containsCourse(json, 99L)).isFalse();
    }

    @Test
    void containsCourse_nullCourseId_meansNoFilter() {
        assertThat(codec.containsCourse(codec.toJson(List.of(unscheduled(1L, 6L))), null)).isTrue();
    }

    @Test
    void containsCourse_itemWithoutCourse_doesNotMatchAnyCourse() {
        String json = codec.toJson(List.of(unscheduled(1L, null)));

        assertThat(codec.containsCourse(json, 6L)).isFalse();
    }

    @Test
    void toSnapshotItem_copiesCourseTitleFromCaller_notFromItem() {
        ExecutionItem item = ExecutionItem.builder()
                .executionItemId(11L)
                .title("과제 2번 구현")
                .expectedMinutes(60)
                .priority(ExecutionPriority.MUST)
                .courseId(6L)
                .placementType(PlacementType.DATE_ONLY)
                .scheduledDate(LocalDate.of(2026, 8, 26))
                .build();

        PlanSnapshotItem snapshot = codec.toSnapshotItem(item, "자료구조", "선수 개념이 끝났으니");

        assertThat(snapshot.executionItemId()).isEqualTo(11L);
        assertThat(snapshot.priority()).isEqualTo("MUST");
        assertThat(snapshot.courseTitle()).isEqualTo("자료구조");
        assertThat(snapshot.reason()).isEqualTo("선수 개념이 끝났으니");
        assertThat(snapshot.scheduledStartAt()).isNull();
    }

    @Test
    void toSnapshotItem_nullPriority_doesNotThrow() {
        ExecutionItem item = ExecutionItem.builder()
                .executionItemId(12L).title("우선순위 없는 조각")
                .placementType(PlacementType.UNSCHEDULED)
                .build();

        assertThat(codec.toSnapshotItem(item, null, null).priority()).isNull();
    }

    private PlanSnapshotItem unscheduled(Long executionItemId, Long courseId) {
        return new PlanSnapshotItem(
                executionItemId, "미배치 항목", 30, "SHOULD", courseId, "자료구조", null,
                PlacementType.UNSCHEDULED, null, null, null,
                LocalDate.of(2026, 8, 24), LocalDate.of(2026, 8, 30), null);
    }
}
