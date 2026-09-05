package com.jungwoo.project.memo.plan.dto;

import com.jungwoo.project.memo.plan.domain.AskReason;
import com.jungwoo.project.memo.plan.domain.PlanStrategy;

import java.util.List;

/**
 * 판단의 결과. 전략이 나오거나, 되묻거나, 둘 중 하나다.
 *
 * <p>되묻는 경우에는 계획을 <b>만들지 않는다</b>. 일단 만들어 놓고 "이게 맞나요?"라고 묻는
 * 것과 다르다 — 만들어진 계획은 그 자체로 제안이 되어, 사용자가 답을 고르기 전에 이미
 * 화면의 기준점이 된다.
 */
public record PlanJudgmentResult(PlanStrategy strategy, Ask ask) {

    public static PlanJudgmentResult of(PlanStrategy strategy) {
        return new PlanJudgmentResult(strategy, null);
    }

    public static PlanJudgmentResult ask(AskReason reason, String question, List<String> options) {
        return new PlanJudgmentResult(null, new Ask(reason, question, options));
    }

    public boolean isAsk() {
        return ask != null;
    }

    /**
     * 되묻기 한 건.
     *
     * @param options 사용자가 고를 답. 화면이 버튼으로 그리고, 고른 답을 지시문에 이어 붙여
     *                다시 요청한다. 기존 상담의 ASK_CLARIFICATION과 같은 패턴이며 새 상태
     *                개념을 만들지 않는다
     */
    public record Ask(AskReason reason, String question, List<String> options) {
    }
}
