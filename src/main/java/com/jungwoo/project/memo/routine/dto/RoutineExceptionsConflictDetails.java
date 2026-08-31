package com.jungwoo.project.memo.routine.dto;

import com.jungwoo.project.memo.routine.domain.RoutineExceptionConflictReason;

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
 *
 * <p>id 목록과 날짜 목록을 나란히 두는 평행 배열이 아니라 객체 배열이다. 평행 배열은 두
 * 리스트의 순서가 맞는다는 보장이 코드 배치에만 있고, 무엇보다 사유를 담을 자리가 없다 —
 * 화면이 "요일이 안 맞아서"와 "기간 밖이라서"를 구분하지 못하면 사용자는 무엇을 고쳐야
 * 하는지 알 수 없다.
 */
public record RoutineExceptionsConflictDetails(
        List<Conflict> conflicts
) {

    /**
     * @param reasons 걸린 사유 전부. 요일과 기간을 한 번에 바꾸면 둘 다 담긴다
     */
    public record Conflict(
            Long exceptionId,
            LocalDate exceptionDate,
            List<RoutineExceptionConflictReason> reasons
    ) {
    }
}
