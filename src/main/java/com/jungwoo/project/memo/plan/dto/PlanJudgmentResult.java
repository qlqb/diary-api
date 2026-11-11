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
        return ask(reason, question, options, List.of());
    }

    public static PlanJudgmentResult ask(AskReason reason, String question, List<String> options,
                                         List<Long> topicIds) {
        return new PlanJudgmentResult(null, new Ask(reason, question, options, topicIds));
    }

    public boolean isAsk() {
        return ask != null;
    }

    /**
     * 되묻기 한 건.
     *
     * @param options  사용자가 고를 답. 화면이 버튼으로 그리고, 고른 답을 다시 요청에 실어
     *                 보낸다. 기존 상담의 ASK_CLARIFICATION과 같은 패턴이며 새 상태 개념을
     *                 만들지 않는다
     * @param topicIds 이 질문이 대상으로 삼은 학습 항목. 화면이 그대로 돌려보내면 서버가
     *                 "익숙하다"는 답을 그 항목들에 대한 맥락으로 저장한다. 이 값이 없으면
     *                 답이 텍스트로만 남아 다음 판단이 여전히 근거가 없다고 본다
     */
    public record Ask(AskReason reason, String question, List<String> options, List<Long> topicIds) {
    }
}
