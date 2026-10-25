package com.jungwoo.project.memo.ai.change;

import com.jungwoo.project.memo.assignment.CourseAssignmentMapper;
import com.jungwoo.project.memo.assignment.CourseAssignmentService;
import com.jungwoo.project.memo.assignment.ai.AssignmentChangeModule;
import com.jungwoo.project.memo.assignment.domain.CourseAssignment;
import com.jungwoo.project.memo.assignment.domain.DueKind;
import com.jungwoo.project.memo.assignment.dto.AssignmentRequests;
import com.jungwoo.project.memo.commitment.CommitmentService;
import com.jungwoo.project.memo.commitment.domain.CommitmentSourceType;
import com.jungwoo.project.memo.commitment.dto.CommitmentCreateRequest;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.execution.ExecutionItemMapper;
import com.jungwoo.project.memo.execution.ExecutionItemService;
import com.jungwoo.project.memo.execution.ai.ExecutionChangeModule;
import com.jungwoo.project.memo.execution.domain.ExecutionItem;
import com.jungwoo.project.memo.execution.domain.ExecutionStatus;
import com.jungwoo.project.memo.execution.dto.ExecutionItemCreateRequest;
import com.jungwoo.project.memo.routine.RoutineOccurrenceService;
import com.jungwoo.project.memo.routine.RoutineService;
import com.jungwoo.project.memo.routine.ai.RoutineChangeModule;
import com.jungwoo.project.memo.routine.domain.RoutineOccurrence;
import com.jungwoo.project.memo.routine.dto.RoutineSaveRequest;
import com.jungwoo.project.memo.scheduling.scenario.Scenario;
import com.jungwoo.project.memo.scheduling.service.AvailabilityEstimateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 변경 묶음의 적용 계약을 실제 테이블(memo_test)로 확인한다: 적용 전 원본 불변, 가정 상태 = 실제 적용 후 상태, 선택·의존, 검토 뒤 변경
 * (새 일정·version 없는 반복 일정의 제목 변경), 멱등·중복 적용·동시 적용, 중간 실패 시 전부 되돌림, 다른 사용자의 id.
 */
@SpringBootTest
class ChangeBundleApplyDbTest {

    private static final Long USER = 999_000_031L;
    private static final Long OTHER = 999_000_032L;
    private static final Long CONVERSATION = 999_000_031L;
    /** 실행 항목·과제는 users FK가 있다 — memo_test의 합성 사용자를 빌리고, 이 테스트가 만든 행만 지운다. */
    private static final Long FIXTURE = 900_000_001L;
    private final List<String> fixtureCleanup = new ArrayList<>();
    private static final LocalDate DAY = LocalDate.of(2027, 3, 7); // 일요일, 다른 테스트 데이터와 겹치지 않는 먼 날

    @Autowired private ChangeBundleService bundles;
    @Autowired private RoutineService routineService;
    @Autowired private RoutineOccurrenceService occurrenceService;
    @Autowired private CommitmentService commitmentService;
    @Autowired private ExecutionItemService executionItemService;
    @Autowired private ExecutionItemMapper executionItemMapper;
    @Autowired private CourseAssignmentService assignmentService;
    @Autowired private CourseAssignmentMapper assignmentMapper;
    @Autowired private AvailabilityEstimateService availability;
    @Autowired private RoutineChangeModule routineModule;
    @Autowired private DataSource dataSource;

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            fixtureCleanup.add("DELETE FROM ai_change_bundle_items WHERE user_id = " + FIXTURE);
            fixtureCleanup.add("DELETE FROM ai_change_bundles WHERE user_id = " + FIXTURE);
            for (String sql : fixtureCleanup) {
                try (var st = conn.createStatement()) {
                    st.executeUpdate(sql);
                }
            }
            fixtureCleanup.clear();
            for (Long user : List.of(USER, OTHER)) {
                for (String sql : List.of(
                        "DELETE FROM ai_change_bundle_items WHERE user_id = ?",
                        "DELETE FROM ai_change_bundles WHERE user_id = ?",
                        "DELETE FROM routine_exceptions WHERE routine_id IN (SELECT routine_id FROM routines WHERE user_id = ?)",
                        "DELETE FROM routine_weekdays WHERE routine_id IN (SELECT routine_id FROM routines WHERE user_id = ?)",
                        "DELETE FROM routines WHERE user_id = ?",
                        "DELETE FROM one_off_commitments WHERE user_id = ?",
                        "DELETE FROM execution_records WHERE user_id = ?",
                        "DELETE FROM execution_item_events WHERE user_id = ?",
                        "DELETE FROM execution_items WHERE user_id = ?",
                        "DELETE FROM course_assignments WHERE user_id = ?")) {
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setLong(1, user);
                        ps.executeUpdate();
                    }
                }
            }
        }
    }

    private Long routine(Long user, String title, LocalTime start, LocalTime end) {
        return routineService.create(user, new RoutineSaveRequest(null, title, null, EnumSet.allOf(DayOfWeek.class), start, end,
                DAY.minusDays(7), null)).routineId();
    }

    private ChangeOp checked(ChangeOp op) {
        ChangeCheck check = bundles.safeCheck(USER, op);
        assertThat(check.code()).as(op.summary()).isEqualTo(ChangeCheck.OK);
        return check.op();
    }

    private ChangeOp checkedAs(Long user, ChangeOp op) {
        ChangeCheck check = bundles.safeCheck(user, op);
        assertThat(check.code()).as(op.summary()).isEqualTo(ChangeCheck.OK);
        return check.op();
    }

    private ChangeBundleView bundle(ChangeBundleService.Staged... staged) {
        return bundles.create(USER, CONVERSATION, 1L, List.of(staged));
    }

    private static ChangeBundleService.Staged item(ChangeOp op) {
        return new ChangeBundleService.Staged(op, List.of(), true, "PROPOSED");
    }

    private ChangeBundleView apply(ChangeBundleView view, List<Long> selected, String key) {
        return bundles.apply(USER, view.bundleId(), new ChangeBundleService.ApplyCommand(view.revision(), selected, key));
    }

    private static boolean hasShift(List<RoutineOccurrence> occ, Long routineId, LocalDate sourceDate) {
        return occ.stream().anyMatch(o -> o.routineId().equals(routineId) && o.sourceDate().equals(sourceDate));
    }

    @Test
    void 근무_회차_제외는_적용_전에는_가정이고_적용_후_상태가_가정과_같다() {
        Long shift = routine(USER, "쿠팡알바 근무", LocalTime.of(17, 0), LocalTime.of(2, 0));
        Long travel = routine(USER, "쿠팡알바 이동시간", LocalTime.of(15, 30), LocalTime.of(17, 0));
        ChangeOp skip = checked(new RoutineChangeModule.SkipOccurrence(shift, DAY, null, null, null, null));

        // 추정 연결(이동시간)은 함께 고를 항목으로 나오지만 기본 미선택이다.
        List<com.jungwoo.project.memo.ai.change.ChangeModule.LinkedChange> linked = routineModule.linked(USER, skip);
        assertThat(linked).hasSize(1);
        assertThat(linked.get(0).stored()).isFalse();
        ChangeBundleView view = bundle(item(skip),
                new ChangeBundleService.Staged(linked.get(0).op(), List.of(), false, "INFERRED_LINK"));

        Scenario hypo = bundles.scenario(USER, view.bundleId(), null);
        List<RoutineOccurrence> projected = occurrenceService.expand(USER, DAY, DAY.plusDays(1), hypo.routines());
        assertThat(hasShift(projected, shift, DAY)).isFalse();
        assertThat(hasShift(projected, travel, DAY)).isTrue(); // 미선택 이동은 그대로
        // 원본은 그대로다
        assertThat(hasShift(occurrenceService.expand(USER, DAY, DAY.plusDays(1)), shift, DAY)).isTrue();
        // 가정 상태의 남는 시간에는 17~24시가 열린다
        var hypoAvail = availability.estimate(USER, DAY, DAY, List.of(), List.of(), hypo);
        assertThat(hypoAvail.busyWindows()).noneMatch(b -> b.label().equals("쿠팡알바 근무") && b.startAt().toLocalDate().equals(DAY));

        ChangeBundleView applied = apply(view, List.of(view.items().get(0).itemId()), "k-1");

        assertThat(applied.status()).isEqualTo("APPLIED");
        assertThat(applied.items().get(0).status()).isEqualTo("APPLIED");
        assertThat(applied.items().get(1).status()).isEqualTo("DISMISSED");
        assertThat(occurrenceService.expand(USER, DAY, DAY.plusDays(1))).isEqualTo(projected); // 가정 = 적용 후
    }

    @Test
    void 기대는_항목을_빼고_적용하면_거절하고_원본은_그대로다() {
        Long shift = routine(USER, "근무", LocalTime.of(9, 0), LocalTime.of(12, 0));
        ChangeOp skip = checked(new RoutineChangeModule.SkipOccurrence(shift, DAY, null, null, null, null));
        ChangeOp other = checked(new RoutineChangeModule.SkipOccurrence(shift, DAY.plusDays(1), null, null, null, null));
        ChangeBundleView view = bundles.create(USER, CONVERSATION, 1L, List.of(item(skip),
                new ChangeBundleService.Staged(other, List.of(0), true, "PROPOSED")));

        assertThatThrownBy(() -> apply(view, List.of(view.items().get(1).itemId()), "k-dep"))
                .isInstanceOf(ConflictException.class).hasFieldOrPropertyWithValue("errorCode", ErrorCode.CHANGE_BUNDLE_DEPENDENCY);
        assertThat(hasShift(occurrenceService.expand(USER, DAY, DAY.plusDays(1)), shift, DAY.plusDays(1))).isTrue();
    }

    @Test
    void 검토_뒤_그_범위에_새_약속이_생기면_STALE이고_다시_검토하면_적용된다() {
        Long shift = routine(USER, "근무", LocalTime.of(17, 0), LocalTime.of(22, 0));
        ChangeBundleView view = bundle(item(checked(new RoutineChangeModule.SkipOccurrence(shift, DAY, null, null, null, null))));
        commitmentService.create(USER, CommitmentCreateRequest.builder().title("새 약속").startAt(DAY.atTime(13, 0))
                .endAt(DAY.atTime(14, 0)).build(), CommitmentSourceType.MANUAL);

        assertThatThrownBy(() -> apply(view, view.selectedIds(), "k-stale"))
                .isInstanceOf(ConflictException.class).hasFieldOrPropertyWithValue("errorCode", ErrorCode.CHANGE_BUNDLE_STALE);

        ChangeBundleView refreshed = bundles.refresh(USER, view.bundleId(), view.revision());
        assertThat(refreshed.revision()).isEqualTo(view.revision() + 1);
        assertThat(apply(refreshed, refreshed.selectedIds(), "k-stale-2").status()).isEqualTo("APPLIED");
    }

    @Test
    void version이_없는_반복_일정의_제목만_바뀌어도_검토_뒤_변경으로_거절한다() {
        Long shift = routine(USER, "근무", LocalTime.of(17, 0), LocalTime.of(22, 0));
        ChangeBundleView view = bundle(item(checked(new RoutineChangeModule.SkipOccurrence(shift, DAY, null, null, null, null))));
        routineService.update(USER, shift, new RoutineSaveRequest(null, "근무(이름 바꿈)", null, EnumSet.allOf(DayOfWeek.class),
                LocalTime.of(17, 0), LocalTime.of(22, 0), DAY.minusDays(7), null));

        assertThatThrownBy(() -> apply(view, view.selectedIds(), "k-title"))
                .isInstanceOf(ConflictException.class).hasFieldOrPropertyWithValue("errorCode", ErrorCode.CHANGE_BUNDLE_STALE);
    }

    @Test
    void 같은_키는_저장된_결과이고_다른_내용이면_거절하며_적용된_묶음은_다시_적용되지_않는다() {
        Long shift = routine(USER, "근무", LocalTime.of(17, 0), LocalTime.of(22, 0));
        ChangeBundleView view = bundle(item(checked(new RoutineChangeModule.SkipOccurrence(shift, DAY, null, null, null, null))));
        ChangeBundleView first = apply(view, view.selectedIds(), "k-same");
        ChangeBundleView again = apply(view, view.selectedIds(), "k-same"); // 응답 유실 뒤 재시도

        assertThat(again.applyResult()).isEqualTo(first.applyResult());
        assertThatThrownBy(() -> apply(view, List.of(), "k-same"))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CHANGE_BUNDLE_IDEMPOTENCY_REUSED);
        assertThatThrownBy(() -> apply(view, view.selectedIds(), "k-other"))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CHANGE_BUNDLE_ALREADY_RESOLVED);
        assertThat(exceptionsOn(shift)).isEqualTo(1);
    }

    @Test
    void 동시에_두_번_적용해도_예외는_하나만_생긴다() throws Exception {
        Long shift = routine(USER, "근무", LocalTime.of(17, 0), LocalTime.of(22, 0));
        ChangeBundleView view = bundle(item(checked(new RoutineChangeModule.SkipOccurrence(shift, DAY, null, null, null, null))));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Callable<String>> tasks = List.of(() -> outcome(view, "k-c1"), () -> outcome(view, "k-c2"));
        List<String> outcomes = new ArrayList<>();
        for (Future<String> f : pool.invokeAll(tasks)) {
            outcomes.add(f.get());
        }
        pool.shutdown();
        assertThat(outcomes).containsExactlyInAnyOrder("APPLIED", ErrorCode.CHANGE_BUNDLE_ALREADY_RESOLVED.name());
        assertThat(exceptionsOn(shift)).isEqualTo(1);
    }

    private String outcome(ChangeBundleView view, String key) {
        try {
            return apply(view, view.selectedIds(), key).status();
        } catch (ConflictException e) {
            return e.getErrorCode().name();
        }
    }

    @Test
    void 적용_중간에_실패하면_앞서_적용한_항목까지_전부_되돌린다() {
        Long shift = routine(USER, "근무", LocalTime.of(17, 0), LocalTime.of(22, 0));
        ChangeOp skip = checked(new RoutineChangeModule.SkipOccurrence(shift, DAY, null, null, null, null));
        // 두 번째 항목: 요일에서 DAY를 빼는 규칙 변경 — 첫 항목이 만든 그날 예외와 맞지 않아 서비스가 409로 거절한다
        ChangeOp update = checked(new RoutineChangeModule.UpdateRoutine(shift, null, "근무", null,
                EnumSet.of(DayOfWeek.MONDAY), LocalTime.of(17, 0), LocalTime.of(22, 0), DAY.minusDays(7), null, null));
        ChangeBundleView view = bundle(item(skip), item(update));

        assertThatThrownBy(() -> apply(view, view.selectedIds(), "k-fail")).isInstanceOf(RuntimeException.class);
        assertThat(exceptionsOn(shift)).isZero();
        assertThat(bundles.view(USER, view.bundleId()).status()).isEqualTo("PROPOSED");
    }

    @Test
    void 완료와_일부_수행은_실제_시간을_남기고_가정_상태와_적용_후_목록이_같다() {
        String day = "'" + DAY + "'";
        fixtureCleanup.add("DELETE FROM execution_records WHERE user_id = " + FIXTURE + " AND execution_item_id IN "
                + "(SELECT execution_item_id FROM execution_items WHERE scheduled_date = " + day + ")");
        fixtureCleanup.add("DELETE FROM execution_item_events WHERE user_id = " + FIXTURE + " AND execution_item_id IN "
                + "(SELECT execution_item_id FROM execution_items WHERE scheduled_date = " + day + ")");
        fixtureCleanup.add("DELETE FROM execution_items WHERE user_id = " + FIXTURE + " AND scheduled_date = " + day
                + " AND source_execution_item_id IS NOT NULL"); // 남은 분량 항목이 원본을 FK로 가리킨다 — 먼저 지운다
        fixtureCleanup.add("DELETE FROM execution_items WHERE user_id = " + FIXTURE + " AND scheduled_date = " + day);
        Long a = executionItemService.create(FIXTURE, ExecutionItemCreateRequest.builder().title("스택 복습").scheduledDate(DAY)
                .expectedMinutes(60).build()).getExecutionItemId();
        Long b = executionItemService.create(FIXTURE, ExecutionItemCreateRequest.builder().title("큐 문제").scheduledDate(DAY)
                .expectedMinutes(80).build()).getExecutionItemId();
        ChangeOp complete = checkedAs(FIXTURE, new ExecutionChangeModule.CompleteItem(executionItemMapper.findByIdAndUserId(a, FIXTURE), 70, null));
        ChangeOp partial = checkedAs(FIXTURE, new ExecutionChangeModule.PartialItem(executionItemMapper.findByIdAndUserId(b, FIXTURE), 50, 40, null));
        ChangeBundleView view = bundles.create(FIXTURE, CONVERSATION, 1L, List.of(item(complete), item(partial)));

        Scenario hypo = bundles.scenario(FIXTURE, view.bundleId(), null);
        List<ExecutionItem> projected = hypo.execution().apply(executionItemMapper.findByUserIdAndPlanningRange(FIXTURE, DAY, DAY),
                i -> DAY.equals(i.getScheduledDate()));

        bundles.apply(FIXTURE, view.bundleId(), new ChangeBundleService.ApplyCommand(view.revision(), view.selectedIds(), "k-exec"));

        List<ExecutionItem> after = executionItemMapper.findByUserIdAndPlanningRange(FIXTURE, DAY, DAY);
        assertThat(after).extracting(ExecutionItem::getTitle, ExecutionItem::getStatus, ExecutionItem::getExpectedMinutes)
                .containsExactlyInAnyOrderElementsOf(projected.stream().map(i -> org.assertj.core.groups.Tuple.tuple(
                        i.getTitle(), i.getStatus(), i.getExpectedMinutes())).toList());
        assertThat(after).anyMatch(i -> i.getTitle().equals("큐 문제 (남은 분량)") && i.getStatus() == ExecutionStatus.PLANNED);
        Integer actual = jdbcInt("SELECT actual_minutes FROM execution_records WHERE execution_item_id = " + a);
        assertThat(actual).isEqualTo(70);
    }

    @Test
    void 과제_마감_정정은_사용자_확정으로_남는다() {
        AssignmentRequests.Create create = new AssignmentRequests.Create();
        create.setTitle("JSP 과제(변경 묶음 테스트)");
        fixtureCleanup.add("DELETE FROM course_assignments WHERE user_id = " + FIXTURE + " AND title = 'JSP 과제(변경 묶음 테스트)'");
        create.setDueKind(DueKind.DATE);
        create.setDueDate(DAY);
        Long id = assignmentService.create(FIXTURE, create).getAssignmentId();
        CourseAssignment before = assignmentMapper.findByIdAndUserId(id, FIXTURE);
        ChangeOp due = checkedAs(FIXTURE, new AssignmentChangeModule.SetDue(before, DueKind.DATE, DAY.plusDays(1), null));
        ChangeBundleView view = bundles.create(FIXTURE, CONVERSATION, 1L, List.of(item(due)));
        assertThat(assignmentMapper.findByIdAndUserId(id, FIXTURE).getDueDate()).isEqualTo(DAY); // 적용 전 원본 그대로

        bundles.apply(FIXTURE, view.bundleId(), new ChangeBundleService.ApplyCommand(view.revision(), view.selectedIds(), "k-due"));

        CourseAssignment after = assignmentMapper.findByIdAndUserId(id, FIXTURE);
        assertThat(after.getDueDate()).isEqualTo(DAY.plusDays(1));
        assertThat(after.isDueEdited()).isTrue();
        assertThat(String.valueOf(after.getDueSource())).isEqualTo("USER");
    }

    @Test
    void 다른_사용자의_id는_없는_것으로_보고_묶음도_볼_수_없다() {
        Long othersShift = routine(OTHER, "남의 근무", LocalTime.of(9, 0), LocalTime.of(10, 0));
        assertThat(bundles.safeCheck(USER, new RoutineChangeModule.SkipOccurrence(othersShift, DAY, null, null, null, null))
                .code()).isEqualTo(ChangeCheck.NOT_FOUND);
        Long mine = routine(USER, "근무", LocalTime.of(9, 0), LocalTime.of(10, 0));
        ChangeBundleView view = bundle(item(checked(new RoutineChangeModule.SkipOccurrence(mine, DAY, null, null, null, null))));
        assertThatThrownBy(() -> bundles.view(OTHER, view.bundleId())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> bundles.apply(OTHER, view.bundleId(),
                new ChangeBundleService.ApplyCommand(view.revision(), view.selectedIds(), "k-x")))
                .isInstanceOf(NotFoundException.class);
    }

    private int exceptionsOn(Long routineId) {
        return jdbcInt("SELECT COUNT(*) FROM routine_exceptions WHERE routine_id = " + routineId);
    }

    private Integer jdbcInt(String sql) {
        try (Connection conn = dataSource.getConnection(); var st = conn.createStatement(); var rs = st.executeQuery(sql)) {
            return rs.next() ? (Integer) rs.getObject(1, Integer.class) : null;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
