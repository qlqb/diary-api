package com.jungwoo.project.memo.ai.change;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDate;
import java.util.List;

/**
 * 변경 묶음의 항목 하나 — 도메인이 정한 타입이 있는 변경(예: 반복 일정의 그날 회차 빼기, 실행 완료). 범용 CRUD·Map으로 통일하지 않는다.
 *
 * <p>같은 op 값이 미리보기(가정 상태 투영)와 적용(기존 서비스 호출) 양쪽에 쓰인다. op에는 검토 당시 대상의 상태(version 또는 상태 해시)가
 * 함께 담기고, 적용 때 그 값으로 그사이 바뀌었는지 본다.
 */
public interface ChangeOp {

    /** 저장·도구에서 쓰는 이름(예: ROUTINE_SKIP). */
    @JsonIgnore
    String type();

    /** 같은 대상에 충돌하는 op를 한 묶음에 두지 않기 위한 키(예: routine:220@2026-10-25). */
    @JsonIgnore
    String targetKey();

    /** 카드·답변에 보일 한 줄. */
    @JsonIgnore
    String summary();

    /** 시간 효과가 닿는 날짜. 적용 때 그 범위의 실제 일정 지문을 다시 비교한다. 시간 효과가 없으면 빈 목록. */
    @JsonIgnore
    default List<LocalDate> affectedDates() {
        return List.of();
    }
}
