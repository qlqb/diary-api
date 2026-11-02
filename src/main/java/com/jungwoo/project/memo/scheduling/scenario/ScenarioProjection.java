package com.jungwoo.project.memo.scheduling.scenario;

/**
 * 시간·마감 사실을 바꾸는 변경이 가정 상태에 자기 효과를 쓰는 방법. 시간 효과가 없는 변경은 구현하지 않는다.
 *
 * <p>적용(apply)과 같은 op 값에서 나온다 — 미리보기와 확정이 다른 의미를 쓰지 않게 하려는 것이다.
 * 여기서 읽는 것은 op에 이미 담긴 대상 상태(검토 당시 스냅샷)뿐이며, DB를 읽지 않는다.
 */
public interface ScenarioProjection {

    void project(Scenario.Builder scenario);
}
