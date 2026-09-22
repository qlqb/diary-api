package com.jungwoo.project.memo.ai.dto;

import com.jungwoo.project.memo.plan.domain.PlanIntensity;

import java.time.LocalDate;
import java.util.List;

/**
 * OFFER 턴에서만 채워지는 단일 액션. 서버가 만든다 — 모델은 버튼을 구성하지 않는다.
 *
 * <p>type=CREATE_PROPOSAL: 사용자가 누르면 requestedAction=CREATE_PROPOSAL로 재요청한다(일반
 * 제안·실행 조정, 5개 상한). periodStartDate/periodEndDate를 반드시 들고 있고, 화면은 그 날짜를
 * 버튼 옆에 보여준다 — 사용자가 날짜를 보고 눌러야 "이 기간을 사용자가 승인했다"고 말할 수
 * 있기 때문이다. 클릭하면 화면이 이 두 날짜를 요청에 그대로 되돌려 보내고, 그 값이 계획
 * 기간의 확정값이 된다(intensity/courseIds는 null).
 *
 * <p>type=CREATE_PERIOD_PLAN: 기간 계획. 서버가 검증한 기간·강도·대상 프로젝트를 같이 들고
 * 있고, 사용자가 누르면 화면이 이 값을 requestedAction=CREATE_PERIOD_PLAN 요청의 periodPlan으로
 * 되돌려 보낸다. 실제 생성은 계획 화면과 같은 PlanDraftService가 한다. 예전 화면은 이 type을
 * 몰라도 라벨과 버튼은 그대로 그리고 CREATE_PROPOSAL로 보내므로 즉시 깨지지 않는다.
 *
 * @param courseIds 비어 있으면 활성 프로젝트 전체.
 */
public record OfferAction(
        String type,
        String label,
        LocalDate periodStartDate,
        LocalDate periodEndDate,
        PlanIntensity intensity,
        List<Long> courseIds
) {
    public static final String TYPE_CREATE_PROPOSAL = "CREATE_PROPOSAL";
    public static final String TYPE_CREATE_PERIOD_PLAN = "CREATE_PERIOD_PLAN";

    public static OfferAction createProposal(String label, LocalDate periodStartDate, LocalDate periodEndDate) {
        return new OfferAction(TYPE_CREATE_PROPOSAL, label, periodStartDate, periodEndDate, null, null);
    }

    public static OfferAction createPeriodPlan(String label, PeriodPlanRequest plan) {
        return new OfferAction(TYPE_CREATE_PERIOD_PLAN, label, plan.getPeriodStartDate(), plan.getPeriodEndDate(),
                plan.getIntensity(), plan.getCourseIds() != null ? plan.getCourseIds() : List.of());
    }

    public boolean isPeriodPlan() {
        return TYPE_CREATE_PERIOD_PLAN.equals(type);
    }
}
