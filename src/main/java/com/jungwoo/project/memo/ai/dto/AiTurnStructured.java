package com.jungwoo.project.memo.ai.dto;

import com.jungwoo.project.memo.ai.domain.AiPlanScope;
import com.jungwoo.project.memo.ai.domain.AiResponseType;

import java.time.LocalDate;
import java.util.List;

/**
 * 모델 출력의 구분자(&lt;&lt;&lt;AI_STRUCTURED&gt;&gt;&gt;) 뒤 JSON을 파싱한 결과.
 * reply(구분자 앞 텍스트)는 스트리밍 중에 이미 별도로 처리하므로 여기 포함하지 않는다.
 *
 * unavailableWindows는 PROPOSAL에서만 값을 가질 수 있는 대화 차원의 제약이다(개별 항목이
 * 아니라 이번 계획 전체에 적용된다) — CHAT/OFFER에서는 항상 빈 배열이거나 null이다.
 *
 * planScope는 모델이 판단한 이번 요청의 계획 기간(DAY/WEEK/MONTH)이다. 모델이 값을 비워
 * 보내면 서버가 가장 좁은 범위인 DAY로 취급한다(AiConversationService 참고).
 *
 * needsClarification/clarifyingQuestion/missingInformation은 모델이 스스로 내린 "정보가
 * 충분한가"라는 판단을 구조화한 값이다. 서버는 이 값의 진위를 알 수 없으므로 판단 자체를
 * 대신하지 않고, responseType과의 내적 일관성만 검증한다(AiConversationService의
 * enforceClarificationContract 참고) — 예를 들어 needsClarification=true인데 responseType이
 * PROPOSAL이면 모순이므로 CHAT으로 되돌린다.
 *
 * periodStartDate/periodEndDate는 모델이 판단한 이번 계획의 실제 대상 기간이다. "오늘"이면
 * 오늘 날짜를, "내일"이면 내일 날짜를 그대로 담아야 한다 — 서버가 무조건 오늘로 고정하지
 * 않는다. responseType=PROPOSAL일 때만 값이 필요하며, proposalItems의 모든 날짜는 이 범위
 * 안에 있어야 한다(범위를 벗어나면 서버가 PROPOSAL 전체를 계약 위반으로 처리한다).
 */
public record AiTurnStructured(
        AiResponseType responseType,
        List<ProposalItem> proposalItems,
        OfferAction offerAction,
        List<UnavailableWindowSpec> unavailableWindows,
        AiPlanScope planScope,
        boolean needsClarification,
        String clarifyingQuestion,
        List<String> missingInformation,
        LocalDate periodStartDate,
        LocalDate periodEndDate
) {
}
