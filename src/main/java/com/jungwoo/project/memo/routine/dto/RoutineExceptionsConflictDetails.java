package com.jungwoo.project.memo.routine.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 409 ROUTINE_EXCEPTIONS_CONFLICT의 본문 details.
 *
 * <p>어떤 예외가 걸렸는지를 화면이 보여주고, 사용자가 그 예외를 먼저 정리한 뒤 다시 수정한다.
 * 예외를 자동으로 삭제하지 않는다 — 보강 일정을 요일 변경의 부수효과로 지우면 사용자가
 * 모르는 사이에 일정이 사라진다.
 *
 * <p>날짜를 message 문자열에 나열하고 프론트가 파싱하는 방식은 쓰지 않는다. 서버 문구와
 * 화면이 결합되어, 문구를 다듬는 순간 화면이 깨진다.
 */
public record RoutineExceptionsConflictDetails(
        List<Long> conflictingExceptionIds,
        List<LocalDate> conflictingDates
) {
}
