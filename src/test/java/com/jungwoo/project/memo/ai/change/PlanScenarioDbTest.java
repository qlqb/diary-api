package com.jungwoo.project.memo.ai.change;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.plan.domain.PlanIntensity;
import com.jungwoo.project.memo.plan.selection.PlanRequestContext;
import com.jungwoo.project.memo.routine.RoutineOccurrenceService;
import com.jungwoo.project.memo.routine.RoutineService;
import com.jungwoo.project.memo.routine.ai.RoutineChangeModule;
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
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 계획 초안과 변경 묶음의 계약(memo_test): 초안은 묶음 선택을 겹친 가정 상태로 계산하고 그 스냅샷을 남긴다. 확정은 같은 트랜잭션에서
 * 묶음을 먼저 적용한다. 초안 뒤 선택을 바꾸면 확정을 막는다. 묶음만 먼저(같은 선택으로) 적용했으면 인정한다.
 */
@SpringBootTest
class PlanScenarioDbTest {

    private static final Long USER = 999_000_041L;
    private static final Long CONVERSATION = 999_000_041L;
    private static final LocalDate DAY = LocalDate.of(2027, 3, 14);

    @Autowired private PlanScenarioService planScenarios;
    @Autowired private ChangeBundleService bundles;
    @Autowired private RoutineService routineService;
    @Autowired private RoutineOccurrenceService occurrenceService;
    @Autowired private AvailabilityEstimateService availability;
    @Autowired private DataSource dataSource;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            for (String sql : List.of("DELETE FROM ai_change_bundle_items WHERE user_id = ?",
                    "DELETE FROM ai_change_bundles WHERE user_id = ?",
                    "DELETE FROM routine_exceptions WHERE routine_id IN (SELECT routine_id FROM routines WHERE user_id = ?)",
                    "DELETE FROM routine_weekdays WHERE routine_id IN (SELECT routine_id FROM routines WHERE user_id = ?)",
                    "DELETE FROM routines WHERE user_id = ?")) {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setLong(1, USER);
                    ps.executeUpdate();
                }
            }
        }
    }

    private record Setup(Long shift, ChangeBundleView bundle, String draftJson) {
    }

    private Setup setup() throws Exception {
        Long shift = routineService.create(USER, new RoutineSaveRequest(null, "쿠팡알바 근무", null, EnumSet.allOf(DayOfWeek.class),
                LocalTime.of(17, 0), LocalTime.of(2, 0), DAY.minusDays(7), null)).routineId();
        ChangeOp skip = bundles.safeCheck(USER, new RoutineChangeModule.SkipOccurrence(shift, DAY, null, null, null, null)).op();
        ChangeBundleView view = bundles.create(USER, CONVERSATION, 1L,
                List.of(new ChangeBundleService.Staged(skip, List.of(), true, "PROPOSED")));
        PlanScenarioService.Resolved resolved = planScenarios.forConversation(USER, CONVERSATION);
        assertThat(resolved).isNotNull();
        String draftJson = json.writeValueAsString(new PlanRequestContext(PlanRequestContext.VERSION, "CONVERSATION", DAY, DAY,
                PlanIntensity.NORMAL, null, null, List.of(), List.of(), List.of(), List.of(), null, null, CONVERSATION, null,
                null, null, null, null, null, null, null, null, resolved.snapshot()));
        return new Setup(shift, view, draftJson);
    }

    @Test
    void 초안의_가정_상태는_근무를_비우고_원본은_그대로다() throws Exception {
        Setup s = setup();
        Scenario fromDraft = planScenarios.scenarioFor(USER, s.draftJson());
        var hypo = availability.estimate(USER, DAY, DAY, List.of(), List.of(), fromDraft);
        assertThat(hypo.busyWindows()).noneMatch(b -> b.label().equals("쿠팡알바 근무") && b.startAt().toLocalDate().equals(DAY));
        assertThat(occurrenceService.expand(USER, DAY, DAY)).anyMatch(o -> o.routineId().equals(s.shift()));
    }

    @Test
    void 확정은_초안이_기댄_변경을_같은_트랜잭션에서_적용한다() throws Exception {
        Setup s = setup();
        planScenarios.applyForConfirm(USER, s.draftJson(), 501L, DAY, DAY);
        assertThat(bundles.view(USER, s.bundle().bundleId()).status()).isEqualTo("APPLIED");
        assertThat(occurrenceService.expand(USER, DAY, DAY)).noneMatch(o -> o.routineId().equals(s.shift())
                && o.sourceDate().equals(DAY));
    }

    @Test
    void 초안_뒤에_근무_빼기를_해제하면_확정을_막는다() throws Exception {
        Setup s = setup();
        bundles.select(USER, s.bundle().bundleId(), s.bundle().revision(), List.of()); // 사용자가 근무 제외를 거절
        assertThatThrownBy(() -> planScenarios.applyForConfirm(USER, s.draftJson(), 502L, DAY, DAY))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PLAN_SCENARIO_CHANGED);
        assertThat(occurrenceService.expand(USER, DAY, DAY)).anyMatch(o -> o.routineId().equals(s.shift()));
    }

    @Test
    void 묶음만_먼저_같은_선택으로_적용했으면_확정을_인정한다() throws Exception {
        Setup s = setup();
        bundles.apply(USER, s.bundle().bundleId(), new ChangeBundleService.ApplyCommand(s.bundle().revision(),
                s.bundle().selectedIds(), "first"));
        planScenarios.applyForConfirm(USER, s.draftJson(), 503L, DAY, DAY); // 예외 없음
        // 적용된 뒤 미리보기는 원본(이미 그 상태)을 본다 — 두 번 겹치지 않는다
        assertThat(planScenarios.scenarioFor(USER, s.draftJson()).isOriginal()).isTrue();
    }

    @Test
    void 초안_뒤에_선택을_바꾸면_미리보기도_옛_가정으로_계산하지_않는다() throws Exception {
        Setup s = setup();
        bundles.select(USER, s.bundle().bundleId(), s.bundle().revision(), List.of());
        assertThatThrownBy(() -> planScenarios.scenarioFor(USER, s.draftJson()))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PLAN_SCENARIO_CHANGED);
    }

    @Test
    void 깨진_의존_스냅샷은_원본으로_넘어가지_않고_막는다() {
        String broken = "{\"version\":1,\"scenario\":{\"bundleId\":\"x\"}}";
        assertThatThrownBy(() -> planScenarios.applyForConfirm(USER, broken, 504L, DAY, DAY))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PLAN_SCENARIO_CHANGED);
        assertThat(planScenarios.snapshotOf("{\"version\":1}")).isNull(); // 이 기능 전 초안: 의존 없음
    }
}
