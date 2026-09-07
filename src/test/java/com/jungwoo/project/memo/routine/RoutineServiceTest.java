package com.jungwoo.project.memo.routine;

import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.ForbiddenException;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.course.CourseService;
import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.course.domain.CourseStatus;
import com.jungwoo.project.memo.routine.domain.Routine;
import com.jungwoo.project.memo.routine.domain.RoutineException;
import com.jungwoo.project.memo.routine.domain.RoutineExceptionConflictReason;
import com.jungwoo.project.memo.routine.domain.RoutineExceptionType;
import com.jungwoo.project.memo.routine.dto.RoutineExceptionSaveRequest;
import com.jungwoo.project.memo.routine.dto.RoutineExceptionsConflictDetails;
import com.jungwoo.project.memo.routine.dto.LeadMinutesBatchItemRequest;
import com.jungwoo.project.memo.routine.dto.LeadMinutesPendingGroup;
import com.jungwoo.project.memo.routine.dto.RoutineResponse;
import com.jungwoo.project.memo.routine.dto.RoutineSaveRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 반복 일정 CRUD의 검증과 충돌 처리.
 *
 * <p>RoutineReader는 실물이다. 잠근 뒤 요일을 붙이는 경로(lockWithWeekdays)가 예외 검증의
 * 입력을 만들기 때문에, 그 조립을 목으로 건너뛰면 검증 테스트가 자기가 만든 값을 검증하게 된다.
 */
@ExtendWith(MockitoExtension.class)
class RoutineServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long ROUTINE_ID = 10L;
    private static final Long COURSE_ID = 7L;

    private static final LocalDate SEMESTER_START = LocalDate.of(2026, 8, 25);
    private static final LocalDate SEMESTER_END = LocalDate.of(2026, 12, 11);

    @Mock
    private RoutineMapper routineMapper;

    @Mock
    private RoutineExceptionMapper routineExceptionMapper;

    @Mock
    private CourseService courseService;

    private RoutineService service;

    @BeforeEach
    void setUp() {
        RoutineReader reader = new RoutineReader(routineMapper);
        service = new RoutineService(routineMapper, routineExceptionMapper, reader, courseService,
                Clock.fixed(Instant.parse("2026-08-31T00:00:00Z"), ZoneOffset.UTC),
                new RoutineOccurrenceService(reader, routineExceptionMapper));
    }

    // ===== 소유권 =====

    @Test
    void 남의_루틴은_수정할_수_없다() {
        when(routineMapper.findByIdAndUserIdForUpdate(ROUTINE_ID, USER_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.update(USER_ID, ROUTINE_ID, request(DayOfWeek.THURSDAY)))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("반복 일정을 찾을 수 없습니다");
    }

    @Test
    void 남의_루틴은_삭제할_수_없다() {
        when(routineMapper.softDelete(ROUTINE_ID, USER_ID)).thenReturn(0);

        assertThatThrownBy(() -> service.delete(USER_ID, ROUTINE_ID))
                .isInstanceOf(NotFoundException.class);
    }

    /** 예외에는 user_id가 없다. 소유권은 routines를 JOIN한 조회 하나로만 확인된다. */
    @Test
    void 남의_루틴에_달린_예외는_수정할_수_없다() {
        lockedRoutine(DayOfWeek.THURSDAY);
        when(routineExceptionMapper.findByIdAndUserId(99L, USER_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.updateException(USER_ID, ROUTINE_ID, 99L,
                skipRequest(LocalDate.of(2026, 9, 24))))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("예외를 찾을 수 없습니다");
    }

    // ===== 루틴 검증 =====

    @Test
    void 시작과_종료가_같으면_거부한다() {
        RoutineSaveRequest request = new RoutineSaveRequest(null, "빅데이터분석", null,
                Set.of(DayOfWeek.THURSDAY), LocalTime.of(10, 0), LocalTime.of(10, 0),
                SEMESTER_START, SEMESTER_END);

        assertThatThrownBy(() -> service.create(USER_ID, request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("분 단위");
    }

    @Test
    void 시각에_초가_섞이면_거부한다() {
        RoutineSaveRequest request = new RoutineSaveRequest(null, "빅데이터분석", null,
                Set.of(DayOfWeek.THURSDAY), LocalTime.of(10, 0, 30), LocalTime.of(12, 50),
                SEMESTER_START, SEMESTER_END);

        assertThatThrownBy(() -> service.create(USER_ID, request))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void 요일이_비어_있으면_거부한다() {
        RoutineSaveRequest request = new RoutineSaveRequest(null, "빅데이터분석", null,
                Set.of(), LocalTime.of(10, 0), LocalTime.of(12, 50), SEMESTER_START, SEMESTER_END);

        assertThatThrownBy(() -> service.create(USER_ID, request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("요일");
    }

    @Test
    void 종료일이_시작일보다_이르면_거부한다() {
        RoutineSaveRequest request = new RoutineSaveRequest(null, "빅데이터분석", null,
                Set.of(DayOfWeek.THURSDAY), LocalTime.of(10, 0), LocalTime.of(12, 50),
                SEMESTER_END, SEMESTER_START);

        assertThatThrownBy(() -> service.create(USER_ID, request))
                .isInstanceOf(BadRequestException.class);
    }

    /** 자정 넘김은 정상값이다. 여기서 막으면 알바 근무표(CL = 15~00)를 넣을 수 없다. */
    @Test
    void 자정을_넘는_구간은_허용한다() {
        RoutineSaveRequest request = new RoutineSaveRequest(null, "알바", null,
                Set.of(DayOfWeek.MONDAY), LocalTime.of(15, 0), LocalTime.of(0, 0),
                SEMESTER_START, null);

        service.create(USER_ID, request);

        verify(routineMapper).insert(any(Routine.class));
    }

    /** getOwned는 보관된 프로젝트도 돌려준다 — ACTIVE 검사가 따로 필요한 이유다. */
    @Test
    void 보관된_프로젝트에는_루틴을_붙일_수_없다() {
        when(courseService.getOwned(USER_ID, COURSE_ID)).thenReturn(
                Course.builder().courseId(COURSE_ID).userId(USER_ID).status(CourseStatus.ARCHIVED).build());
        RoutineSaveRequest request = new RoutineSaveRequest(COURSE_ID, "빅데이터분석", null,
                Set.of(DayOfWeek.THURSDAY), LocalTime.of(10, 0), LocalTime.of(12, 50),
                SEMESTER_START, SEMESTER_END);

        assertThatThrownBy(() -> service.create(USER_ID, request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("보관된 프로젝트");
        verify(routineMapper, never()).insert(any());
    }

    // ===== 예외 검증 =====

    @Test
    void MOVED인데_옮길_날짜가_없으면_거부한다() {
        lockedRoutine(DayOfWeek.THURSDAY);
        RoutineExceptionSaveRequest request = new RoutineExceptionSaveRequest(
                LocalDate.of(2026, 9, 24), RoutineExceptionType.MOVED, null, null, null, null, null);

        assertThatThrownBy(() -> service.addException(USER_ID, ROUTINE_ID, request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("옮길 날짜");
    }

    @Test
    void MOVED의_시각이_한쪽만_있으면_거부한다() {
        lockedRoutine(DayOfWeek.THURSDAY);
        RoutineExceptionSaveRequest request = new RoutineExceptionSaveRequest(
                LocalDate.of(2026, 9, 24), RoutineExceptionType.MOVED, LocalDate.of(2026, 10, 1),
                LocalTime.of(14, 0), null, null, null);

        assertThatThrownBy(() -> service.addException(USER_ID, ROUTINE_ID, request))
                .isInstanceOf(BadRequestException.class);
    }

    /**
     * 저장은 해놓고 전개에서 조용히 누락시키지 않는다. 화요일 예외는 목요일 루틴에서 절대
     * 발생하지 않으므로 저장 시점에 막는다.
     */
    @Test
    void 예외_날짜의_요일이_루틴_요일이_아니면_거부한다() {
        lockedRoutine(DayOfWeek.THURSDAY);
        // 2026-09-22는 화요일.
        RoutineExceptionSaveRequest request = skipRequest(LocalDate.of(2026, 9, 22));

        assertThatThrownBy(() -> service.addException(USER_ID, ROUTINE_ID, request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("기간이나 요일");
    }

    @Test
    void 예외_날짜가_기간_밖이면_거부한다() {
        lockedRoutine(DayOfWeek.THURSDAY);
        // 2026-12-17은 목요일이지만 종강(12/11) 이후다.
        assertThatThrownBy(() -> service.addException(USER_ID, ROUTINE_ID,
                skipRequest(LocalDate.of(2026, 12, 17))))
                .isInstanceOf(BadRequestException.class);
    }

    /** 반대로 movedDate는 기간 밖이어도 받는다 — 종강 뒤 보강이 정상이다. */
    @Test
    void 옮길_날짜는_기간_밖이어도_받는다() {
        lockedRoutine(DayOfWeek.THURSDAY);
        RoutineExceptionSaveRequest request = new RoutineExceptionSaveRequest(
                LocalDate.of(2026, 12, 10), RoutineExceptionType.MOVED, LocalDate.of(2026, 12, 18),
                null, null, null, "기말 보강");

        service.addException(USER_ID, ROUTINE_ID, request);

        verify(routineExceptionMapper).insert(any(RoutineException.class));
    }

    @Test
    void 같은_날짜에_예외를_두_번_달면_409다() {
        lockedRoutine(DayOfWeek.THURSDAY);
        org.mockito.Mockito.doThrow(new DuplicateKeyException("uq_routine_exceptions"))
                .when(routineExceptionMapper).insert(any(RoutineException.class));

        assertThatThrownBy(() -> service.addException(USER_ID, ROUTINE_ID,
                skipRequest(LocalDate.of(2026, 9, 24))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("이미 예외가 있습니다");
    }

    // ===== 수정이 기존 예외를 재검증한다 =====

    @Test
    void 요일을_바꿔서_기존_예외가_무효가_되면_409에_그_목록을_담는다() {
        lockedRoutine(DayOfWeek.THURSDAY);
        when(routineExceptionMapper.findByRoutineId(ROUTINE_ID)).thenReturn(List.of(
                exception(101L, LocalDate.of(2026, 9, 24)),   // 목요일
                exception(102L, LocalDate.of(2026, 10, 8))));  // 목요일

        // 화요일로 바꾸면 두 예외 모두 발생하지 않는 날이 된다.
        assertThatThrownBy(() -> service.update(USER_ID, ROUTINE_ID, request(DayOfWeek.TUESDAY)))
                .isInstanceOf(ConflictException.class)
                .satisfies(thrown -> {
                    List<RoutineExceptionsConflictDetails.Conflict> conflicts = conflictsOf(thrown);
                    assertThat(conflicts)
                            .extracting(RoutineExceptionsConflictDetails.Conflict::exceptionId,
                                    RoutineExceptionsConflictDetails.Conflict::exceptionDate)
                            .containsExactly(
                                    tuple(101L, LocalDate.of(2026, 9, 24)),
                                    tuple(102L, LocalDate.of(2026, 10, 8)));
                    // 기간은 그대로이므로 요일만 걸린다.
                    assertThat(conflicts).allSatisfy(conflict -> assertThat(conflict.reasons())
                            .containsExactly(RoutineExceptionConflictReason.DAY_OF_WEEK_MISMATCH));
                });

        // 예외를 자동으로 지우지 않는다. 루틴도 그대로 둔다 — 전체를 거부하는 것이 요점이다.
        verify(routineMapper, never()).updateAll(anyLong(), anyLong(), any(), any(), any(),
                any(), any(), any(), any(), any());
        verify(routineExceptionMapper, never()).deleteByIdAndRoutineId(anyLong(), anyLong());
    }

    @Test
    void 기간을_당겨서_기존_예외가_기간_밖이_되면_409다() {
        lockedRoutine(DayOfWeek.THURSDAY);
        when(routineExceptionMapper.findByRoutineId(ROUTINE_ID))
                .thenReturn(List.of(exception(101L, LocalDate.of(2026, 10, 8))));

        RoutineSaveRequest shortened = new RoutineSaveRequest(null, "빅데이터분석", null,
                Set.of(DayOfWeek.THURSDAY), LocalTime.of(10, 0), LocalTime.of(12, 50),
                SEMESTER_START, LocalDate.of(2026, 10, 1));

        assertThatThrownBy(() -> service.update(USER_ID, ROUTINE_ID, shortened))
                .isInstanceOf(ConflictException.class)
                .satisfies(thrown -> {
                    // 요일은 그대로이므로 기간만 걸린다. 사유가 하나로 좁혀져야 화면이
                    // "기간 밖이에요"만 말하고, 사용자가 요일까지 의심하지 않는다.
                    assertThat(conflictsOf(thrown)).singleElement()
                            .extracting(RoutineExceptionsConflictDetails.Conflict::reasons)
                            .isEqualTo(List.of(RoutineExceptionConflictReason.OUTSIDE_EFFECTIVE_RANGE));
                });
    }

    /**
     * 요일과 기간을 한 번에 바꾸면 한 예외가 둘 다 위반할 수 있다. 첫 위반에서 빠져나가면
     * 화면은 "기간을 고치면 되겠다"고 알려주고, 사용자가 기간을 고친 뒤에야 요일도 안 맞는다는
     * 것을 알게 된다 — boolean을 돌려주던 때와 같아진다.
     */
    @Test
    void 요일과_기간을_함께_바꾸면_사유가_둘_다_담긴다() {
        lockedRoutine(DayOfWeek.THURSDAY);
        when(routineExceptionMapper.findByRoutineId(ROUTINE_ID))
                .thenReturn(List.of(exception(101L, LocalDate.of(2026, 10, 8))));

        // 화요일로 바꾸고(10/8은 목요일) 기간도 10/1까지로 당긴다.
        RoutineSaveRequest both = new RoutineSaveRequest(null, "빅데이터분석", null,
                Set.of(DayOfWeek.TUESDAY), LocalTime.of(10, 0), LocalTime.of(12, 50),
                SEMESTER_START, LocalDate.of(2026, 10, 1));

        assertThatThrownBy(() -> service.update(USER_ID, ROUTINE_ID, both))
                .isInstanceOf(ConflictException.class)
                .satisfies(thrown -> assertThat(conflictsOf(thrown)).singleElement()
                        .extracting(RoutineExceptionsConflictDetails.Conflict::reasons)
                        .isEqualTo(List.of(
                                RoutineExceptionConflictReason.DAY_OF_WEEK_MISMATCH,
                                RoutineExceptionConflictReason.OUTSIDE_EFFECTIVE_RANGE)));
    }

    @SuppressWarnings("unchecked")
    private static List<RoutineExceptionsConflictDetails.Conflict> conflictsOf(Throwable thrown) {
        Object details = ((ConflictException) thrown).getDetails();
        assertThat(details).isInstanceOf(RoutineExceptionsConflictDetails.class);
        return ((RoutineExceptionsConflictDetails) details).conflicts();
    }

    /** movedDate는 재검증 대상이 아니다. 기간을 당겨도 그 밖의 보강은 그대로 남는다. */
    @Test
    void 기간을_당겨도_기간_밖인_것이_movedDate뿐이면_통과한다() {
        lockedRoutine(DayOfWeek.THURSDAY);
        RoutineException moved = exception(101L, LocalDate.of(2026, 10, 1));
        moved.setType(RoutineExceptionType.MOVED);
        moved.setMovedDate(LocalDate.of(2026, 12, 18));
        when(routineExceptionMapper.findByRoutineId(ROUTINE_ID)).thenReturn(List.of(moved));

        RoutineSaveRequest shortened = new RoutineSaveRequest(null, "빅데이터분석", null,
                Set.of(DayOfWeek.THURSDAY), LocalTime.of(10, 0), LocalTime.of(12, 50),
                SEMESTER_START, LocalDate.of(2026, 10, 8));

        service.update(USER_ID, ROUTINE_ID, shortened);

        verify(routineMapper).updateAll(eq(ROUTINE_ID), eq(USER_ID), any(), any(), any(),
                any(), any(), any(), any(), eq(LocalDate.of(2026, 10, 8)));
    }

    @Test
    void 예외를_먼저_지우면_같은_수정이_통과한다() {
        lockedRoutine(DayOfWeek.THURSDAY);
        when(routineExceptionMapper.findByRoutineId(ROUTINE_ID)).thenReturn(List.of());

        service.update(USER_ID, ROUTINE_ID, request(DayOfWeek.TUESDAY));

        verify(routineMapper).deleteWeekdays(ROUTINE_ID);
        verify(routineMapper).insertWeekdays(ROUTINE_ID, List.of("TUESDAY"));
    }

    // ===== 잠금 =====

    /**
     * 잠금 없이 통과하면 실패하는 테스트를 만들려면 경합을 일부러 만들어야 하는데, 그렇게 만든
     * 테스트는 타이밍에 의존해 불안정해진다. 대신 "무효 상태를 만들 수 있는 세 경로가 부모
     * 행을 잠그고 시작하는가"를 고정한다. 삭제는 무효 상태를 만들지 않으므로 잠그지 않는다.
     */
    @Test
    void 무효_상태를_만들_수_있는_세_경로는_부모_루틴을_잠근다() {
        lockedRoutine(DayOfWeek.THURSDAY);
        when(routineExceptionMapper.findByRoutineId(ROUTINE_ID)).thenReturn(List.of());
        when(routineExceptionMapper.findByIdAndUserId(101L, USER_ID))
                .thenReturn(exception(101L, LocalDate.of(2026, 9, 24)));

        service.update(USER_ID, ROUTINE_ID, request(DayOfWeek.THURSDAY));
        service.addException(USER_ID, ROUTINE_ID, skipRequest(LocalDate.of(2026, 9, 24)));
        service.updateException(USER_ID, ROUTINE_ID, 101L, skipRequest(LocalDate.of(2026, 10, 1)));

        verify(routineMapper, org.mockito.Mockito.times(3))
                .findByIdAndUserIdForUpdate(ROUTINE_ID, USER_ID);
    }

    @Test
    void 예외_삭제는_부모를_잠그지_않는다() {
        when(routineExceptionMapper.findByIdAndUserId(101L, USER_ID))
                .thenReturn(exception(101L, LocalDate.of(2026, 9, 24)));

        service.deleteException(USER_ID, ROUTINE_ID, 101L);

        verify(routineMapper, never()).findByIdAndUserIdForUpdate(anyLong(), anyLong());
        verify(routineExceptionMapper).deleteByIdAndRoutineId(101L, ROUTINE_ID);
    }

    // ===== 이동시간 =====

    private Routine ownedRoutine(Long routineId) {
        return Routine.builder()
                .routineId(routineId)
                .userId(USER_ID)
                .title("수업 " + routineId)
                .startTime(LocalTime.of(14, 0))
                .endTime(LocalTime.of(17, 0))
                .effectiveFrom(SEMESTER_START)
                .daysOfWeek(new LinkedHashSet<>())
                .build();
    }

    /** L8. 남의(또는 없는) routineId가 하나라도 섞이면 403이고 아무것도 저장되지 않는다. */
    @Test
    void 이동시간_일괄_저장은_남의_루틴이_섞이면_403이고_아무것도_저장하지_않는다() {
        when(routineMapper.findByIdAndUserIdForUpdate(1L, USER_ID)).thenReturn(ownedRoutine(1L));
        when(routineMapper.findByIdAndUserIdForUpdate(2L, USER_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.updateLeadMinutes(USER_ID, List.of(
                new LeadMinutesBatchItemRequest(1L, 60), new LeadMinutesBatchItemRequest(2L, 60))))
                .isInstanceOf(ForbiddenException.class);

        verify(routineMapper, never()).updateLeadMinutes(anyLong(), anyLong(), any());
    }

    @Test
    void 이동시간_일괄_저장은_전부_본인_것이면_전부_저장한다() {
        when(routineMapper.findByIdAndUserIdForUpdate(1L, USER_ID)).thenReturn(ownedRoutine(1L));
        when(routineMapper.findByIdAndUserIdForUpdate(2L, USER_ID)).thenReturn(ownedRoutine(2L));

        List<RoutineResponse> responses = service.updateLeadMinutes(USER_ID, List.of(
                new LeadMinutesBatchItemRequest(2L, 0), new LeadMinutesBatchItemRequest(1L, 60)));

        verify(routineMapper).updateLeadMinutes(1L, USER_ID, 60);
        verify(routineMapper).updateLeadMinutes(2L, USER_ID, 0);
        // "없음"은 0으로 남는다 — null과 다르다. 응답이 저장된 값을 그대로 돌려준다.
        assertThat(responses).extracting(RoutineResponse::routineId, RoutineResponse::leadMinutes)
                .containsExactly(tuple(1L, 60), tuple(2L, 0));
    }

    @Test
    void 이동시간은_0에서_480분_사이여야_하고_null은_받지_않는다() {
        assertThatThrownBy(() -> service.updateLeadMinutes(USER_ID,
                List.of(new LeadMinutesBatchItemRequest(1L, 481))))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.updateLeadMinutes(USER_ID,
                List.of(new LeadMinutesBatchItemRequest(1L, -1))))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.updateLeadMinutes(USER_ID,
                List.of(new LeadMinutesBatchItemRequest(1L, null))))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.updateLeadMinutes(USER_ID, List.of()))
                .isInstanceOf(BadRequestException.class);

        verify(routineMapper, never()).findByIdAndUserIdForUpdate(anyLong(), anyLong());
        verify(routineMapper, never()).updateLeadMinutes(anyLong(), anyLong(), any());
    }

    /**
     * PUT 전체 교체가 이동시간을 모르는 요청(null)으로 오면 저장된 값을 건드리지 않는다.
     * 매퍼는 null을 받아 컬럼을 SET 목록에서 빼고(XML의 if), 응답에는 저장돼 있던 값이 남는다.
     */
    @Test
    void 루틴_수정이_이동시간을_생략하면_저장된_값이_남는다() {
        lockedRoutine(DayOfWeek.THURSDAY);
        Routine locked = routineMapper.findByIdAndUserIdForUpdate(ROUTINE_ID, USER_ID);
        locked.setLeadMinutes(45);
        when(routineExceptionMapper.findByRoutineId(ROUTINE_ID)).thenReturn(List.of());

        RoutineResponse response = service.update(USER_ID, ROUTINE_ID, request(DayOfWeek.THURSDAY));

        verify(routineMapper).updateAll(eq(ROUTINE_ID), eq(USER_ID), any(), any(), any(),
                any(), any(), eq(null), any(), any());
        assertThat(response.leadMinutes()).isEqualTo(45);
    }

    @Test
    void 루틴_수정에_이동시간이_있으면_함께_저장된다() {
        lockedRoutine(DayOfWeek.THURSDAY);
        when(routineExceptionMapper.findByRoutineId(ROUTINE_ID)).thenReturn(List.of());
        RoutineSaveRequest request = new RoutineSaveRequest(null, "빅데이터분석", "3-315",
                Set.of(DayOfWeek.THURSDAY), LocalTime.of(10, 0), LocalTime.of(12, 50), 30,
                SEMESTER_START, SEMESTER_END);

        RoutineResponse response = service.update(USER_ID, ROUTINE_ID, request);

        verify(routineMapper).updateAll(eq(ROUTINE_ID), eq(USER_ID), any(), any(), any(),
                any(), any(), eq(30), any(), any());
        assertThat(response.leadMinutes()).isEqualTo(30);
    }

    // ===== 이동시간: 아직 정하지 않은 수업 =====

    /* 계획 기간: 2026-09-07(월) ~ 09-13(일). */
    private static final LocalDate PLAN_FROM = LocalDate.of(2026, 9, 7);
    private static final LocalDate PLAN_TO = LocalDate.of(2026, 9, 13);

    private Routine pendingRoutine(Long routineId, String title, Long courseId, Integer leadMinutes,
                                   LocalDate effectiveFrom, LocalDate effectiveUntil, DayOfWeek... days) {
        return Routine.builder()
                .routineId(routineId)
                .userId(USER_ID)
                .courseId(courseId)
                .title(title)
                .startTime(LocalTime.of(9 + routineId.intValue(), 0))
                .endTime(LocalTime.of(12 + routineId.intValue(), 0))
                .leadMinutes(leadMinutes)
                .effectiveFrom(effectiveFrom)
                .effectiveUntil(effectiveUntil)
                .daysOfWeek(new LinkedHashSet<>(List.of(days)))
                .build();
    }

    private void listed(Routine... routines) {
        when(routineMapper.findAllByUserId(USER_ID)).thenReturn(new java.util.ArrayList<>(List.of(routines)));
        List<com.jungwoo.project.memo.routine.dto.RoutineWeekdayRow> rows = new java.util.ArrayList<>();
        for (Routine routine : routines) {
            for (DayOfWeek day : routine.getDaysOfWeek()) {
                com.jungwoo.project.memo.routine.dto.RoutineWeekdayRow row =
                        new com.jungwoo.project.memo.routine.dto.RoutineWeekdayRow();
                row.setRoutineId(routine.getRoutineId());
                row.setDayOfWeek(day.name());
                rows.add(row);
            }
        }
        org.mockito.Mockito.lenient().when(routineMapper.findWeekdaysByUserId(USER_ID)).thenReturn(rows);
    }

    /**
     * NULL이고 수업(courseId != null)이고 기간 안에 도는 것만 묻는다. 0/양수는 답한 것이고,
     * 알바는 수업이 아니고, 기간 밖(다음 달 개강, 지난 학기 종강)은 이번 계획에 영향이 없다.
     */
    @Test
    void 기간_안에_도는_수업_중_아직_정하지_않은_것만_한_묶음으로_묻는다() {
        listed(pendingRoutine(1L, "자료구조", 101L, null, SEMESTER_START, SEMESTER_END, DayOfWeek.TUESDAY),
                pendingRoutine(2L, "웹서버", 102L, 0, SEMESTER_START, SEMESTER_END, DayOfWeek.WEDNESDAY),
                pendingRoutine(3L, "센서", 103L, 60, SEMESTER_START, SEMESTER_END, DayOfWeek.WEDNESDAY),
                pendingRoutine(4L, "지난 학기", 104L, null, LocalDate.of(2026, 3, 2), LocalDate.of(2026, 6, 20), DayOfWeek.MONDAY),
                pendingRoutine(5L, "다음 달 개강", 105L, null, LocalDate.of(2026, 10, 5), null, DayOfWeek.MONDAY),
                pendingRoutine(6L, "알바", null, null, SEMESTER_START, null, DayOfWeek.FRIDAY),
                pendingRoutine(7L, "빅데이터", 107L, null, SEMESTER_START, SEMESTER_END, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY));

        List<LeadMinutesPendingGroup> pending = service.pendingLeadMinutes(USER_ID, PLAN_FROM, PLAN_TO);

        assertThat(pending).hasSize(1);
        LeadMinutesPendingGroup group = pending.get(0);
        assertThat(group.groupKey()).isEqualTo("class");
        assertThat(group.label()).isEqualTo("수업");
        assertThat(group.routineIds()).containsExactly(1L, 7L);
        // 샘플은 기간 안 첫 발생분부터 최대 셋: 화 10:00(자료구조), 목 16:00, 금 16:00(빅데이터).
        assertThat(group.sample()).containsExactly(
                new LeadMinutesPendingGroup.Sample(DayOfWeek.TUESDAY, LocalTime.of(10, 0)),
                new LeadMinutesPendingGroup.Sample(DayOfWeek.THURSDAY, LocalTime.of(16, 0)),
                new LeadMinutesPendingGroup.Sample(DayOfWeek.FRIDAY, LocalTime.of(16, 0)));
    }

    /** 수업 수가 몇이든 묶음은 하나다. */
    @Test
    void 수업이_여럿이어도_카드는_한_줄이다() {
        Routine[] routines = new Routine[6];
        for (int i = 0; i < routines.length; i++) {
            routines[i] = pendingRoutine((long) (i + 1), "수업 " + (i + 1), (long) (100 + i), null,
                    SEMESTER_START, SEMESTER_END, DayOfWeek.of(i % 5 + 1));
        }
        listed(routines);

        List<LeadMinutesPendingGroup> pending = service.pendingLeadMinutes(USER_ID, PLAN_FROM, PLAN_TO);

        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).routineIds()).hasSize(routines.length);
    }

    @Test
    void 정할_것이_없으면_빈_목록이다() {
        listed(pendingRoutine(1L, "자료구조", 101L, 30, SEMESTER_START, SEMESTER_END, DayOfWeek.TUESDAY),
                pendingRoutine(2L, "알바", null, null, SEMESTER_START, null, DayOfWeek.FRIDAY));

        assertThat(service.pendingLeadMinutes(USER_ID, PLAN_FROM, PLAN_TO)).isEmpty();
    }

    @Test
    void 기간이_없거나_뒤집혀_있으면_400이다() {
        assertThatThrownBy(() -> service.pendingLeadMinutes(USER_ID, null, PLAN_TO))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.pendingLeadMinutes(USER_ID, PLAN_TO, PLAN_FROM))
                .isInstanceOf(BadRequestException.class);
    }

    // ===== 고정자 =====

    private void lockedRoutine(DayOfWeek... days) {
        Routine routine = Routine.builder()
                .routineId(ROUTINE_ID)
                .userId(USER_ID)
                .title("빅데이터분석")
                .location("3-315")
                .startTime(LocalTime.of(10, 0))
                .endTime(LocalTime.of(12, 50))
                .effectiveFrom(SEMESTER_START)
                .effectiveUntil(SEMESTER_END)
                .daysOfWeek(new LinkedHashSet<>())
                .build();
        when(routineMapper.findByIdAndUserIdForUpdate(ROUTINE_ID, USER_ID)).thenReturn(routine);
        List<String> names = new java.util.ArrayList<>();
        for (DayOfWeek day : days) {
            names.add(day.name());
        }
        /*
         * 예외 경로만 잠근 뒤 요일을 다시 읽는다(lockWithWeekdays). 루틴 수정은 요청이 새
         * 요일을 통째로 들고 오므로 읽지 않는다 — 그래서 lenient다. 이 차이 자체가 의도이고,
         * 두 경로를 억지로 같은 모양으로 맞추면 수정 경로가 쓰지도 않는 값을 읽게 된다.
         */
        org.mockito.Mockito.lenient()
                .when(routineMapper.findWeekdaysByRoutineId(ROUTINE_ID)).thenReturn(names);
    }

    private RoutineSaveRequest request(DayOfWeek day) {
        return new RoutineSaveRequest(null, "빅데이터분석", "3-315", Set.of(day),
                LocalTime.of(10, 0), LocalTime.of(12, 50), SEMESTER_START, SEMESTER_END);
    }

    private RoutineExceptionSaveRequest skipRequest(LocalDate date) {
        return new RoutineExceptionSaveRequest(date, RoutineExceptionType.SKIP,
                null, null, null, null, null);
    }

    private RoutineException exception(Long id, LocalDate date) {
        return RoutineException.builder()
                .routineExceptionId(id)
                .routineId(ROUTINE_ID)
                .exceptionDate(date)
                .type(RoutineExceptionType.SKIP)
                .build();
    }
}
