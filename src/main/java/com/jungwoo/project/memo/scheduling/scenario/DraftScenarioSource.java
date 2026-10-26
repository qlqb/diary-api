package com.jungwoo.project.memo.scheduling.scenario;

/**
 * 계획 초안이 기대는 변경(가정 상태)을 초안의 저장된 스냅샷에서 다시 만든다. 미리보기·확정 검증이 생성 때와 같은 상태를 보게 한다.
 *
 * <p>현재 묶음을 다시 읽지 않는다 — 초안을 만든 뒤 묶음이 바뀌었으면 그 초안은 재계산 대상이지, 바뀐 묶음으로 조용히 다시 계산할 대상이
 * 아니다. 변경 묶음 기능이 없으면 이 빈이 없고, 그때는 원본이다.
 */
public interface DraftScenarioSource {

    /** @param planRequestJson 초안의 요청 맥락(ai_proposals.plan_request_json). 기대는 변경이 없으면 원본 */
    Scenario scenarioFor(Long userId, String planRequestJson);
}
