package com.jungwoo.project.memo.routine.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

/**
 * 반복 일정 생성·수정 요청. 생성과 수정이 같은 타입이다.
 *
 * <p>수정이 PATCH가 아니라 PUT(전체 교체)이라 두 요청의 모양이 정확히 같다. 굳이
 * Create/Update로 나누면 같은 필드 목록이 두 곳에 남아 한쪽만 바뀌는 자리가 생긴다.
 *
 * <p>PATCH를 쓰지 않는 이유: courseId·location·effectiveUntil은 "생략"과 "null로 비우기"를
 * 구분해야 하는데, 일반 DTO의 nullable 필드로는 그 둘이 같아 보인다. 화면 폼이 전체 값을
 * 제출하므로 전체 교체가 더 단순하다.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class RoutineSaveRequest {

    /** 프로젝트에 묶이는 루틴만. 없으면 null(알바·운동). */
    private Long courseId;

    @NotBlank
    @Size(max = 200)
    private String title;

    @Size(max = 100)
    private String location;

    /** 비어 있으면 400. 중복은 서비스에서 제거한다. */
    private Set<DayOfWeek> daysOfWeek;

    @NotNull
    private LocalTime startTime;

    /** startTime보다 이르거나 같으면 다음 날로 본다. 같으면(길이 0) 거부한다. */
    @NotNull
    private LocalTime endTime;

    /**
     * 발생분 앞 이동시간(분). 생략(null)하면 저장된 값을 건드리지 않는다 — PUT 전체 교체의
     * 유일한 예외다. null은 "아직 안 물어봄"이라 되돌릴 이유가 없고, 이 필드를 모르는 폼이
     * 이미 답한 값을 지우면 안 된다. 0은 "없음"으로 저장된다.
     */
    @Min(0)
    @Max(480)
    private Integer leadMinutes;

    @NotNull
    private LocalDate effectiveFrom;

    /** 비워 두면 무기한. 그만두는 날 이 값을 채우는 것이 곧 "종료"다. */
    private LocalDate effectiveUntil;

    /** 이동시간을 말하지 않는 요청(= 저장된 값 유지). 기존 호출부의 모양을 그대로 받는다. */
    public RoutineSaveRequest(Long courseId, String title, String location, Set<DayOfWeek> daysOfWeek,
                              LocalTime startTime, LocalTime endTime,
                              LocalDate effectiveFrom, LocalDate effectiveUntil) {
        this(courseId, title, location, daysOfWeek, startTime, endTime, null, effectiveFrom, effectiveUntil);
    }
}
