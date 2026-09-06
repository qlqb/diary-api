package com.jungwoo.project.memo.ai.scheduleimport.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 이미지를 읽은 결과. <b>DB에 아무것도 쓰지 않은 상태</b>다.
 *
 * <p>행 선택·기간 확인·모르는 코드 입력이라는 사용자 개입이 중간에 있어서, 그 사이의 상태를
 * DB에 임시로 두는 대신 이 응답을 클라이언트가 들고 있다가 {@code confirm}에 되돌려준다.
 *
 * <p>{@code rows}는 <b>모든 행</b>의 해석 결과를 담는다. 사용자가 행을 바꿔 골라도 다시
 * 요청할 필요 없이 미리보기가 갱신되게 하려는 것이다 — 이미지를 다시 읽는 것은 느리고
 * 비용도 든다.
 *
 * @param periodMissing          날짜가 아예 없는 표(요일만 있는 강의 시간표). 사용자가 주를 골라야 한다
 * @param periodWeekdayMismatch  보정한 시작일의 요일이 표의 첫 요일과 다르다. 후보는 만들되
 *                               화면이 강조하고 사용자가 주를 다시 고르게 한다
 * @param columnsUnrecognized    일정 열로 읽지 못한 열이 있다
 * @param columnsNormalized      머리글에 "이름"·"세부" 같은 식별 열이 섞여 있어 일정 열만
 *                               골라 썼다. 관측값이다 — 이 값이 자주 true면 모델이 계약을
 *                               지키지 않고 있다는 뜻이고, 보정에 기대는 상태다
 * @param matchedRowIndex        이름이 하나만 일치한 행. 미리 선택일 뿐이고 화면은 늘 목록을 보여준다
 * @param unresolvedCodes        범례에 없어 시간을 모르는 코드들. 사용자가 채워야 확정할 수 있다
 */
public record ScheduleExtractionResponse(
        RawScheduleTable raw,
        ResolvedPeriod resolvedPeriod,
        boolean periodMissing,
        boolean periodWeekdayMismatch,
        boolean columnsUnrecognized,
        boolean columnsNormalized,
        Integer matchedRowIndex,
        List<RowView> rows,
        List<String> unresolvedCodes
) {

    public record ResolvedPeriod(LocalDate startDate, LocalDate endDate) {
    }

    /**
     * @param rowInvalid 셀 수가 요일 수와 달라 이 행은 후보를 만들 수 없다. 다른 행은 정상이다 —
     *                   한 줄이 깨졌다고 표 전체를 버리지 않는다
     */
    public record RowView(
            int index,
            String name,
            String tag,
            boolean rowInvalid,
            List<CellView> cells
    ) {
    }

    /**
     * @param date 그 셀이 가리키는 날짜. 기간을 모르면 null
     * @param kind WORK / OFF / UNRESOLVED
     * @param code 범례로 푼 경우 그 코드. 화면이 "근무 (OP)"를 미리 보여주는 데 쓴다
     */
    public record CellView(
            LocalDate date,
            String raw,
            String kind,
            LocalTime start,
            LocalTime end,
            boolean crossesMidnight,
            String code
    ) {
    }
}
