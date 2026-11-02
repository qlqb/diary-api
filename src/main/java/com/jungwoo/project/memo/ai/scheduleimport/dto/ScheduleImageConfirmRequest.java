package com.jungwoo.project.memo.ai.scheduleimport.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.Map;

/**
 * 검토를 마치고 후보를 만들어 달라는 요청.
 *
 * <p>★ {@code raw}를 다시 받는다. 임시 테이블을 두는 대신 클라이언트가 들고 있다가
 * 돌려주는 것인데, <b>서버는 그 값을 신뢰하지 않고 처음부터 다시 파싱한다.</b> 그래서 이
 * 요청에는 셀 해석 결과를 담는 필드가 아예 없다 — 필드가 있으면 언젠가 그것을 쓰게 되고,
 * 그러면 클라이언트가 계산한 시간이 달력에 들어간다.
 *
 * <p>{@code rowIndex}도 클라이언트가 보낸다. 본인 행을 서버가 이름으로 "짐작"할 수는 있지만
 * 확정은 사용자가 한다 — 잘못 고르면 남의 근무가 내 달력에 들어온다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScheduleImageConfirmRequest {

    /** 중복 요청 방지. USER 메시지에 붙고, 같은 키로 다시 오면 같은 후보를 재응답한다. */
    @NotBlank(message = "idempotencyKey는 필수입니다")
    private String idempotencyKey;

    /** extract가 준 표 원문 그대로. 서버가 다시 파싱한다. */
    @NotNull(message = "표 원문은 필수입니다")
    private RawScheduleTable raw;

    /** 표에서 몇 번째 줄이 본인인가. */
    @NotNull(message = "어느 줄이 본인인지 필요합니다")
    private Integer rowIndex;

    /**
     * 이 표의 첫 열이 가리키는 날짜.
     *
     * <p>extract가 보정한 값을 그대로 보내거나, 요일이 어긋났거나 날짜가 없는 표에서
     * 사용자가 고른 값이다. 필수인 이유는 이 값이 없으면 후보의 날짜가 정해지지 않기 때문이다.
     */
    @NotNull(message = "시작 날짜는 필수입니다")
    private LocalDate periodStartDate;

    /** 일정 제목. 전체에 같은 값이 붙고, 범례 코드가 있으면 "근무 (OP)"처럼 뒤에 붙는다. */
    @NotBlank(message = "제목은 필수입니다")
    private String title;

    /**
     * 범례에 없던 코드에 사용자가 알려준 시간. 키는 셀 원문, 값은 {@code "10~15"} 형태.
     *
     * <p>이 값도 {@code CellParser}가 읽는다 — 사용자가 넣었다고 해서 다른 문법을 쓰지 않는다.
     */
    private Map<String, String> legendOverrides;
}
