package com.jungwoo.project.memo.plan.dto;

import com.jungwoo.project.memo.plan.domain.PlanIntensity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * 기간 계획 초안 생성 요청.
 *
 * 기간 프리셋([오늘] [이번 주] [이번 달] ...)은 화면이 날짜로 변환해 보낸다 — 서버는 프리셋
 * 이름을 저장하지 않는다. 같은 날짜 범위를 어떤 버튼으로 골랐는지는 계획의 성질을 바꾸지
 * 않으므로 남길 이유가 없다.
 *
 * intensity가 null이면 서버가 직전 확정 계획에서 승계한다(11-period-plan.md §5-1-2).
 * 클라이언트는 분 단위를 보내지 않는다 — 기준선 계산은 서버가 소유한다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlanDraftRequest {

    private LocalDate startDate;

    private LocalDate endDate;

    /** null이면 직전 확정 계획에서 승계, 그것도 없으면 NORMAL. */
    private PlanIntensity intensity;

    /** 없으면 AI가 제안한다. */
    private String title;

    /** "시험 전까지 자료구조 위주로" 같은 자유 지시. AI가 기준선을 조정하는 근거가 된다. */
    private String instruction;

    /** 비어 있으면 전체 프로젝트를 대상으로 한다. */
    private List<Long> courseIds;
}
