package com.jungwoo.project.memo.ai.dto;

import com.jungwoo.project.memo.ai.domain.AiModelDecision;
import com.jungwoo.project.memo.ai.domain.AiPlanScope;

import java.time.LocalDate;
import java.util.List;

/**
 * 모델 출력의 구분자(&lt;&lt;&lt;AI_STRUCTURED&gt;&gt;&gt;) 뒤 JSON을 파싱한 결과.
 * reply(구분자 앞 텍스트)는 스트리밍 중에 이미 별도로 처리하므로 여기 포함하지 않는다.
 *
 * 이 레코드는 모델의 판단(decision)과 그 판단을 뒷받침하는 데이터만 담는다 — 화면에 보여줄
 * 최종 상태(AiResponseType)나 OFFER 버튼(OfferAction)은 여기 없다. 그 값들은 서버가
 * requestedAction과 decision을 조합해 직접 만든다(AiConversationService.resolveTurn 참고) —
 * 모델은 UI 상태나 버튼 구성 권한을 갖지 않는다.
 *
 * unavailableWindows는 decision=PROPOSAL_READY에서만 값을 가질 수 있는 대화 차원의 제약이다
 * (개별 항목이 아니라 이번 계획 전체에 적용된다).
 *
 * planScope는 모델이 판단한 이번 계획의 기간(DAY/WEEK/MONTH)이며, decision=PROPOSAL_READY일
 * 때만 값을 가진다. 모델이 값을 비워 보내면 서버가 가장 좁은 범위인 DAY로 취급한다.
 *
 * periodStartDate/periodEndDate는 모델이 판단한 이번 계획의 실제 대상 기간이다. "오늘"이면
 * 오늘 날짜를, "내일"이면 내일 날짜를 그대로 담아야 한다 — 서버가 무조건 오늘로 고정하지
 * 않는다. decision=PROPOSAL_READY일 때만 값이 필요하며, proposalItems의 모든 날짜는 이 범위
 * 안에 있어야 한다(범위를 벗어나면 서버가 PROPOSAL 전체를 계약 위반으로 처리한다).
 */
public record AiTurnStructured(
        AiModelDecision decision,
        String clarifyingQuestion,
        List<String> missingInformation,
        List<ProposalItem> proposalItems,
        List<UnavailableWindowSpec> unavailableWindows,
        AiPlanScope planScope,
        LocalDate periodStartDate,
        LocalDate periodEndDate
) {
}
