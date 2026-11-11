package com.jungwoo.project.memo.routine.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 매주 반복되는 고정 일정의 규칙. routines 테이블과 1:1 대응하는 MyBatis 엔티티다.
 *
 * <p>수업 전용이 아니다 — 알바·운동·스터디도 같은 규칙으로 들어온다. 그래서 courseId는
 * nullable 선택 참조다.
 *
 * <p>발생분(특정 날짜에 실제로 도는 한 건)은 행으로 만들지 않는다. 조회할 때마다
 * {@code RoutineOccurrenceService.expand}가 이 규칙에서 계산한다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Routine {

    private Long routineId;

    private Long userId;

    /** 프로젝트에 묶이는 루틴(수업)만 채운다. 알바·운동은 null. */
    private Long courseId;

    private String title;

    /** 표시 전용. 장소 간 거리를 앱이 알 방법이 없으므로 배치는 이 값을 보지 않는다. */
    private String location;

    private LocalTime startTime;

    /**
     * endTime이 startTime보다 이르거나 같으면 다음 날로 넘어간다는 뜻이다. 알바 근무표의
     * CL = 15~00이 실제 사례이고, 22:00~02:00도 같다. startTime과 같은 값은 길이 0 또는
     * 24시간이라 둘 다 의도가 아니므로 서비스에서 거부한다.
     */
    private LocalTime endTime;

    private LocalDate effectiveFrom;

    /**
     * 없으면 무기한. 루틴이 끝나는 경우는 둘뿐이고 둘 다 이 필드로 표현된다 — 수업은 만들 때
     * 종강일을 채우고, 기한 없이 시작한 알바는 그만두는 날 이 값을 채운다. 별도의 상태
     * 컬럼을 두지 않는 이유는 표시와 배치가 서로 다른 상태 집합을 읽게 되는 것을 막기
     * 위해서다.
     */
    private LocalDate effectiveUntil;

    /** routine_weekdays를 모아 담는다. 저장은 "전부 지우고 다시 넣기"다. */
    @Builder.Default
    private Set<DayOfWeek> daysOfWeek = new LinkedHashSet<>();

    private boolean deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /**
     * 화면의 "종료됨" 뱃지는 저장하지 않고 계산한다. 상태 컬럼을 두면 같은 사실이 두 곳에
     * 남아 어긋난다.
     */
    public boolean isEndedAsOf(LocalDate today) {
        return effectiveUntil != null && effectiveUntil.isBefore(today);
    }
}
