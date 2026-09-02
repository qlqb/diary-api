package com.jungwoo.project.memo.routine;

import com.jungwoo.project.memo.routine.domain.RoutineException;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * routine_exceptions에는 user_id가 없다. 소유권은 routines를 JOIN해서 확인한다 — 같은 사실을
 * 두 곳에 저장하면 어긋나기 때문이다.
 */
@Mapper
public interface RoutineExceptionMapper {

    void insert(RoutineException exception);

    /** 소유권까지 함께 본다. 남의 루틴에 달린 예외는 돌아오지 않는다. */
    RoutineException findByIdAndUserId(@Param("routineExceptionId") Long routineExceptionId,
                                       @Param("userId") Long userId);

    List<RoutineException> findByRoutineId(@Param("routineId") Long routineId);

    /**
     * 전개 1단계용. 원본 발생일(exception_date)이 창 안인 예외들 — 그 날짜의 발생분을
     * 건너뛰는 데 쓴다.
     */
    List<RoutineException> findByUserIdAndExceptionDateRange(@Param("userId") Long userId,
                                                             @Param("from") LocalDate from,
                                                             @Param("to") LocalDate to);

    /**
     * 전개 2단계용. 이동 목적지(moved_date)가 창 안인 MOVED 예외들.
     *
     * <p>1단계만으로는 부족하다. 이동한 날이 창 안이고 원래 날은 창 밖일 수 있다 —
     * 9/24 -> 10/1 이동에서 창이 9/28~10/4면 원래 날은 밖, 목적지는 안이다. 이 조회가
     * 없으면 그 수업이 통째로 사라지고, 앱은 10/1을 비어 있다고 보고 학습을 배치한다.
     */
    List<RoutineException> findByUserIdAndMovedDateRange(@Param("userId") Long userId,
                                                         @Param("from") LocalDate from,
                                                         @Param("to") LocalDate to);

    int update(@Param("routineExceptionId") Long routineExceptionId,
               @Param("exception") RoutineException exception);

    int deleteByIdAndRoutineId(@Param("routineExceptionId") Long routineExceptionId,
                               @Param("routineId") Long routineId);
}
