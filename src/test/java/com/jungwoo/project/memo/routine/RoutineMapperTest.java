package com.jungwoo.project.memo.routine;

import com.jungwoo.project.memo.routine.domain.Routine;
import com.jungwoo.project.memo.routine.domain.RoutineException;
import com.jungwoo.project.memo.routine.domain.RoutineExceptionType;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * routines 3형제의 매핑과 제약을 실제 로컬 MariaDB(memo)에 대고 검증한다.
 *
 * <p>Mockito로는 증명할 수 없는 것들이다: TIME 컬럼이 LocalTime으로 왕복하는지, type이
 * enum으로 읽히는지, exception_date 범위 조회와 moved_date 범위 조회가 서로 다른 것을
 * 보는지, 그리고 CHECK 제약이 실제로 무는지. 특히 마지막이 중요하다 — 제약이 안 걸리면
 * 서비스 검증만 남는데, 그건 나중에 다른 경로가 생기면 새는 자리다.
 *
 * <p>스키마가 레포에 없어 CI에서는 -PexcludeDbTests로 제외된다(build.gradle 참고).
 */
@SpringBootTest
class RoutineMapperTest {

    private static final Long TEST_USER_ID = 999_000_011L;
    private static final Long OTHER_USER_ID = 999_000_012L;

    @Autowired
    private RoutineMapper routineMapper;

    @Autowired
    private RoutineExceptionMapper routineExceptionMapper;

    @Autowired
    private RoutineReader routineReader;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            execute(conn, "DELETE e FROM routine_exceptions e JOIN routines r"
                    + " ON r.routine_id = e.routine_id WHERE r.user_id IN (?, ?)");
            execute(conn, "DELETE w FROM routine_weekdays w JOIN routines r"
                    + " ON r.routine_id = w.routine_id WHERE r.user_id IN (?, ?)");
            execute(conn, "DELETE FROM routines WHERE user_id IN (?, ?)");
        }
    }

    private void execute(Connection conn, String sql) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, TEST_USER_ID);
            ps.setLong(2, OTHER_USER_ID);
            ps.executeUpdate();
        }
    }

    @Test
    void 루틴과_요일이_그대로_왕복한다() {
        Routine saved = insertThursdayClass(TEST_USER_ID);

        Routine found = routineReader.findOneWithWeekdays(TEST_USER_ID, saved.getRoutineId());

        assertThat(found).isNotNull();
        assertThat(found.getTitle()).isEqualTo("빅데이터분석");
        assertThat(found.getLocation()).isEqualTo("3-315");
        assertThat(found.getStartTime()).isEqualTo(LocalTime.of(10, 0));
        assertThat(found.getEndTime()).isEqualTo(LocalTime.of(12, 50));
        assertThat(found.getEffectiveFrom()).isEqualTo(LocalDate.of(2026, 8, 25));
        assertThat(found.getEffectiveUntil()).isEqualTo(LocalDate.of(2026, 12, 11));
        assertThat(found.isDeleted()).isFalse();
        assertThat(found.getDaysOfWeek()).containsExactly(DayOfWeek.THURSDAY);
    }

    @Test
    void 남의_루틴은_보이지_않는다() {
        Routine saved = insertThursdayClass(OTHER_USER_ID);

        assertThat(routineMapper.findByIdAndUserId(saved.getRoutineId(), TEST_USER_ID)).isNull();
    }

    @Test
    void 소프트_삭제하면_조회에서_빠지지만_요일_행은_남는다() {
        Routine saved = insertThursdayClass(TEST_USER_ID);

        assertThat(routineMapper.softDelete(saved.getRoutineId(), TEST_USER_ID)).isEqualTo(1);

        assertThat(routineMapper.findByIdAndUserId(saved.getRoutineId(), TEST_USER_ID)).isNull();
        assertThat(routineMapper.findAllByUserId(TEST_USER_ID)).isEmpty();
        // 복구할 수 있어야 하므로 자식 행은 지우지 않는다.
        assertThat(routineMapper.findWeekdaysByRoutineId(saved.getRoutineId())).containsExactly("THURSDAY");
    }

    @Test
    void 전체_교체는_비운_값을_실제로_비운다() {
        Routine saved = insertThursdayClass(TEST_USER_ID);

        routineMapper.updateAll(saved.getRoutineId(), TEST_USER_ID, null, "빅데이터분석", null,
                LocalTime.of(13, 0), LocalTime.of(15, 50), LocalDate.of(2026, 9, 1), null);

        Routine found = routineMapper.findByIdAndUserId(saved.getRoutineId(), TEST_USER_ID);
        assertThat(found.getCourseId()).isNull();
        assertThat(found.getLocation()).isNull();
        assertThat(found.getEffectiveUntil()).isNull();
        assertThat(found.getStartTime()).isEqualTo(LocalTime.of(13, 0));
    }

    /** 원본 발생일 조회와 이동 목적지 조회는 서로 다른 것을 본다. 이게 섞이면 전개가 무너진다. */
    @Test
    void 예외는_원본_발생일과_이동_목적지_각각으로_조회된다() {
        Routine saved = insertThursdayClass(TEST_USER_ID);
        routineExceptionMapper.insert(RoutineException.builder()
                .routineId(saved.getRoutineId())
                .exceptionDate(LocalDate.of(2026, 9, 24))
                .type(RoutineExceptionType.MOVED)
                .movedDate(LocalDate.of(2026, 10, 1))
                .note("추석 보강")
                .build());

        // 원본 발생일이 든 주
        assertThat(routineExceptionMapper.findByUserIdAndExceptionDateRange(
                TEST_USER_ID, LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 27))).hasSize(1);
        assertThat(routineExceptionMapper.findByUserIdAndMovedDateRange(
                TEST_USER_ID, LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 27))).isEmpty();

        // 목적지가 든 주 — 반대가 된다
        assertThat(routineExceptionMapper.findByUserIdAndExceptionDateRange(
                TEST_USER_ID, LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 4))).isEmpty();
        List<RoutineException> moved = routineExceptionMapper.findByUserIdAndMovedDateRange(
                TEST_USER_ID, LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 4));
        assertThat(moved).hasSize(1);
        assertThat(moved.get(0).getType()).isEqualTo(RoutineExceptionType.MOVED);
        assertThat(moved.get(0).getNote()).isEqualTo("추석 보강");
    }

    @Test
    void 남의_루틴에_달린_예외는_id로도_보이지_않는다() {
        Routine saved = insertThursdayClass(OTHER_USER_ID);
        RoutineException exception = RoutineException.builder()
                .routineId(saved.getRoutineId())
                .exceptionDate(LocalDate.of(2026, 9, 24))
                .type(RoutineExceptionType.SKIP)
                .build();
        routineExceptionMapper.insert(exception);

        assertThat(routineExceptionMapper.findByIdAndUserId(
                exception.getRoutineExceptionId(), TEST_USER_ID)).isNull();
        assertThat(routineExceptionMapper.findByIdAndUserId(
                exception.getRoutineExceptionId(), OTHER_USER_ID)).isNotNull();
    }

    // ===== 제약 =====

    @Test
    void 자정을_넘는_구간은_허용된다() {
        // 근무표의 CL = 15~00. end_time > start_time을 강제하지 않는 이유가 이것이다.
        Routine routine = routine(TEST_USER_ID, LocalTime.of(15, 0), LocalTime.of(0, 0));
        routineMapper.insert(routine);

        assertThat(routineMapper.findByIdAndUserId(routine.getRoutineId(), TEST_USER_ID)).isNotNull();
    }

    @Test
    void 길이가_0인_구간은_DB가_막는다() {
        Routine routine = routine(TEST_USER_ID, LocalTime.of(10, 0), LocalTime.of(10, 0));

        assertThatThrownBy(() -> routineMapper.insert(routine))
                .hasMessageContaining("chk_routines_span");
    }

    @Test
    void 기간이_거꾸로면_DB가_막는다() {
        Routine routine = routine(TEST_USER_ID, LocalTime.of(10, 0), LocalTime.of(12, 0));
        routine.setEffectiveFrom(LocalDate.of(2026, 12, 11));
        routine.setEffectiveUntil(LocalDate.of(2026, 8, 25));

        assertThatThrownBy(() -> routineMapper.insert(routine))
                .hasMessageContaining("chk_routines_range");
    }

    @Test
    void SKIP인데_이동_정보가_있으면_DB가_막는다() {
        Routine saved = insertThursdayClass(TEST_USER_ID);
        RoutineException exception = RoutineException.builder()
                .routineId(saved.getRoutineId())
                .exceptionDate(LocalDate.of(2026, 9, 24))
                .type(RoutineExceptionType.SKIP)
                .movedLocation("2-201")
                .build();

        assertThatThrownBy(() -> routineExceptionMapper.insert(exception))
                .hasMessageContaining("chk_routine_exceptions_moved");
    }

    @Test
    void MOVED인데_시각이_한쪽만_있으면_DB가_막는다() {
        Routine saved = insertThursdayClass(TEST_USER_ID);
        RoutineException exception = RoutineException.builder()
                .routineId(saved.getRoutineId())
                .exceptionDate(LocalDate.of(2026, 9, 24))
                .type(RoutineExceptionType.MOVED)
                .movedDate(LocalDate.of(2026, 10, 1))
                .movedStartTime(LocalTime.of(14, 0))
                .build();

        assertThatThrownBy(() -> routineExceptionMapper.insert(exception))
                .hasMessageContaining("chk_routine_exceptions_moved");
    }

    @Test
    void 같은_날짜에_예외를_두_번_달면_DB가_막는다() {
        Routine saved = insertThursdayClass(TEST_USER_ID);
        routineExceptionMapper.insert(RoutineException.builder()
                .routineId(saved.getRoutineId())
                .exceptionDate(LocalDate.of(2026, 9, 24))
                .type(RoutineExceptionType.SKIP)
                .build());

        assertThatThrownBy(() -> routineExceptionMapper.insert(RoutineException.builder()
                .routineId(saved.getRoutineId())
                .exceptionDate(LocalDate.of(2026, 9, 24))
                .type(RoutineExceptionType.SKIP)
                .build()))
                .hasMessageContaining("uq_routine_exceptions");
    }

    // ===== 고정자 =====

    private Routine insertThursdayClass(Long userId) {
        Routine routine = routine(userId, LocalTime.of(10, 0), LocalTime.of(12, 50));
        routineMapper.insert(routine);
        routineMapper.insertWeekdays(routine.getRoutineId(), List.of("THURSDAY"));
        return routine;
    }

    private Routine routine(Long userId, LocalTime start, LocalTime end) {
        return Routine.builder()
                .userId(userId)
                .title("빅데이터분석")
                .location("3-315")
                .startTime(start)
                .endTime(end)
                .effectiveFrom(LocalDate.of(2026, 8, 25))
                .effectiveUntil(LocalDate.of(2026, 12, 11))
                .build();
    }
}
