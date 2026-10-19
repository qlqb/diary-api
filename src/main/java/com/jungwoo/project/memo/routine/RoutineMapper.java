package com.jungwoo.project.memo.routine;

import com.jungwoo.project.memo.routine.domain.Routine;
import com.jungwoo.project.memo.routine.dto.RoutineWeekdayRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Mapper
public interface RoutineMapper {

    void insert(Routine routine);

    /** 소프트 삭제된 것은 돌려주지 않는다. 목록·전개·소유권 확인이 전부 이 조건을 공유한다. */
    Routine findByIdAndUserId(@Param("routineId") Long routineId, @Param("userId") Long userId);

    /**
     * 수정·예외 변경 트랜잭션에서 부모 행 잠금을 건다. MariaDB: SELECT ... FOR UPDATE.
     *
     * <p>필요한 이유는 검증이 서로를 못 보기 때문이다. 루틴의 요일·기간을 바꾸는 수정과
     * 새 예외 추가가 겹치면, 각자 상대가 아직 커밋하지 않은 상태를 보고 둘 다 통과해
     * "저장은 됐는데 전개에서는 사라지는" 예외가 남을 수 있다.
     */
    Routine findByIdAndUserIdForUpdate(@Param("routineId") Long routineId, @Param("userId") Long userId);

    /**
     * 그 사용자의 살아 있는 루틴 전부. 요일은 비어 있다 — {@code RoutineReader}가
     * {@link #findWeekdaysByUserId}의 결과를 붙인다. 목록 화면과 전개가 같은 조회를 쓴다.
     */
    List<Routine> findAllByUserId(@Param("userId") Long userId);

    /** 루틴이 몇 개든 요일 조회는 이 한 번이다. */
    List<RoutineWeekdayRow> findWeekdaysByUserId(@Param("userId") Long userId);

    /**
     * {@link #findAllByUserId}의 잠금 조회. 계획 확정이 "그 사이 시간표가 바뀌지 않았나"를
     * 최신 커밋 기준으로 보고, 검사와 커밋 사이에 바뀌지 못하게 할 때 쓴다.
     *
     * <p>일반 조회는 트랜잭션이 처음 읽은 시점의 스냅샷(REPEATABLE READ)이라, 확정 트랜잭션이
     * 시작된 뒤 커밋된 수업 시각 변경을 보지 못한다. 잠금 조회는 항상 최신 커밋을 읽고, 수정·
     * 예외 변경 경로가 먼저 잡는 부모 행 잠금({@link #findByIdAndUserIdForUpdate})과 같은 행을
     * 잡으므로 아직 커밋되지 않은 변경은 끝날 때까지 기다린다. (user_id, is_deleted) 인덱스
     * 범위를 훑으므로 같은 사용자의 새 루틴 INSERT도 확정이 끝날 때까지 기다린다.
     */
    List<Routine> findAllByUserIdForUpdate(@Param("userId") Long userId);

    /**
     * {@link #findWeekdaysByUserId}의 잠금 조회. 루틴 본체만 최신으로 읽고 요일은 옛 스냅샷으로
     * 읽으면 "요일은 바뀌었는데 전개는 옛 요일" 같은 섞인 상태가 되므로 함께 잠금 조회한다.
     */
    List<RoutineWeekdayRow> findWeekdaysByUserIdForUpdate(@Param("userId") Long userId);

    List<String> findWeekdaysByRoutineId(@Param("routineId") Long routineId);

    /** 전체 교체. courseId·location·effectiveUntil은 COALESCE하지 않는다 — null이면 비운다. */
    int updateAll(@Param("routineId") Long routineId,
                  @Param("userId") Long userId,
                  @Param("courseId") Long courseId,
                  @Param("title") String title,
                  @Param("location") String location,
                  @Param("startTime") LocalTime startTime,
                  @Param("endTime") LocalTime endTime,
                  @Param("effectiveFrom") LocalDate effectiveFrom,
                  @Param("effectiveUntil") LocalDate effectiveUntil);

    /**
     * 소프트 삭제. 행을 지우지 않으므로 요일·예외 행도 그대로 남는다 — 복구할 수 있어야 한다.
     */
    int softDelete(@Param("routineId") Long routineId, @Param("userId") Long userId);

    /** 요일 갱신은 전부 지우고 다시 넣는 것뿐이다. 부분 갱신을 만들지 않는다. */
    void deleteWeekdays(@Param("routineId") Long routineId);

    void insertWeekdays(@Param("routineId") Long routineId, @Param("daysOfWeek") List<String> daysOfWeek);
}
