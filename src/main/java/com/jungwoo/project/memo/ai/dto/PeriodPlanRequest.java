package com.jungwoo.project.memo.ai.dto;

import com.jungwoo.project.memo.plan.domain.PlanIntensity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * 대화에서 기간 계획을 만들 때의 세 축(기간·강도·대상 프로젝트).
 *
 * <p>OFFER 카드(OfferAction.type=CREATE_PERIOD_PLAN)가 이 값을 들고 있고, 사용자가 버튼을
 * 누르면 화면이 같은 값을 requestedAction=CREATE_PERIOD_PLAN 요청의 periodPlan으로 되돌려
 * 보낸다. 서버는 OFFER를 만들 때 한 번, 요청을 받을 때 다시 한 번 검증한다 — 화면이 보낸
 * 값을 그대로 믿지 않는다(기간 1~31일, 강도 필수, courseIds는 소유 프로젝트만).
 *
 * <p>courseIds가 비어 있으면 활성 프로젝트 전체다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PeriodPlanRequest {

    private LocalDate periodStartDate;

    private LocalDate periodEndDate;

    private PlanIntensity intensity;

    private List<Long> courseIds;
}
