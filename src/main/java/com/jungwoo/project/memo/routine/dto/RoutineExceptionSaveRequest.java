package com.jungwoo.project.memo.routine.dto;

import com.jungwoo.project.memo.routine.domain.RoutineExceptionType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 예외 추가·수정 요청. 추가와 수정이 같은 타입이다(둘 다 전체 교체).
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class RoutineExceptionSaveRequest {

    /** 원래 발생했어야 할 날짜. 이동한 날이 아니다. */
    @NotNull
    private LocalDate exceptionDate;

    @NotNull
    private RoutineExceptionType type;

    /**
     * MOVED면 필수. 기간(effectiveFrom~Until) 밖이어도 받는다 — 종강 뒤 보강이 정상이다.
     */
    private LocalDate movedDate;

    /** 비워 두면 원래 시각을 쓴다. movedEndTime과 함께 있거나 함께 없어야 한다. */
    private LocalTime movedStartTime;

    private LocalTime movedEndTime;

    /** 비워 두면 원래 장소를 쓴다. */
    @Size(max = 100)
    private String movedLocation;

    @Size(max = 200)
    private String note;
}
