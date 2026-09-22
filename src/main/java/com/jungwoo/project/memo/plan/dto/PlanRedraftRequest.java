package com.jungwoo.project.memo.plan.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 같은 조건으로 초안 다시 만들기. 기간·강도·범위·지시·지정 자료는 서버에 남은 요청(plan_request_json)을 그대로 쓰고,
 * 여기서는 바뀐 것만 보낸다. 필드가 null이면 저장된 값을 유지하고, 빈 목록이면 비운다.
 *
 * <p>계획 화면과 상담 초안이 같은 엔드포인트를 쓴다 — 상담 초안을 화면의 기본 날짜로 다시 조립하지 않는다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlanRedraftRequest {
    /** 「이번만 빼기」 목록 전체(추가·되돌리기 결과). null이면 유지. */
    private List<Long> excludeTopicIds;
    /** 지정 자료 목록 전체(모호한 이름을 사용자가 고른 결과 등). null이면 유지. */
    private List<Long> requestedMaterialIds;

    /** 화면이 붙이는 요청 키. PlanDraftRequest.requestKey와 같은 뜻이다. */
    private String requestKey;
}
