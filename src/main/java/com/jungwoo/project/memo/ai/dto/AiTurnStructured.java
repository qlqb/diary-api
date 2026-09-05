package com.jungwoo.project.memo.ai.dto;

import com.jungwoo.project.memo.ai.domain.AiModelDecision;
import com.jungwoo.project.memo.ai.domain.ProposalPurpose;
import com.jungwoo.project.memo.plan.domain.PlanIntensity;

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
 * periodStartDate/periodEndDate는 모델이 자연어에서 읽어낸 이번 계획의 대상 기간이며,
 * <b>decision=OFFER_PROPOSAL일 때만</b> 값을 가진다. "오늘"이면 오늘 날짜를, "내일"이면 내일
 * 날짜를 그대로 담는다 — 서버가 무조건 오늘로 고정하지 않는다. 기간이 모호하면 OFFER가 아니라
 * ASK_CLARIFICATION으로 실제 날짜를 보여주며 되물어야 한다.
 *
 * decision=PROPOSAL_READY에서는 두 값이 반드시 null이다. 그 단계의 기간은 모델이 아니라
 * 사용자가 OFFER 카드에서 날짜를 보고 누른 CREATE_PROPOSAL 요청(AiMessageRequest의
 * periodStartDate/periodEndDate)이 정한다 — 같은 사실을 모델이 두 번 판단하게 두지 않는다.
 * 그래서 계획 기간의 "종류"를 나타내던 AiPlanScope(DAY/WEEK/MONTH)도 없앴다. 기간은 언제나
 * 실제 날짜 두 개이고, 그것을 몇 일짜리로 부를지는 서버가 알 필요가 없다.
 *
 * contextChanges는 decision과 완전히 독립된 sidecar다 — 이 필드가 있다고 responseType이
 * 바뀌지 않는다(CHAT/ASK_CLARIFICATION/OFFER_PROPOSAL/PROPOSAL_READY 어디에도 붙을 수 있다).
 * AiModelDecision을 CONTEXT_CHANGE 같은 값으로 확장하지 않는다 — 장기 컨텍스트 변경 후보는
 * 상담 결과에 붙는 별도 결과일 뿐이다. [요청 모드]가 CREATE_PROPOSAL이면 서버가 항상 빈
 * 배열로 강제한다(AiConversationService.resolveTurn) — 계획 생성 버튼을 눌렀다고 이전 대화의
 * Context 후보를 또 만들면 중복이 생기기 때문이다. 이 리스트에 담긴 값도 모델 출력을 그대로
 * 신뢰하지 않는다 — ContextChangeSuggestionService가 연산별 계약과 소유권을 다시 검증한다.
 */
public record AiTurnStructured(
        AiModelDecision decision,
        String clarifyingQuestion,
        List<String> missingInformation,
        List<ProposalItem> proposalItems,
        /**
         * 기존 실행 조각을 줄이거나 옮기거나 빼는 후보. proposalItems와 같은 제안 묶음에 함께
         * 담기며 decision=PROPOSAL_READY에서만 값을 가질 수 있다. 새 항목 없이 조정만 있는
         * 제안도 유효하다("오늘 너무 피곤해, 줄여줘" -> 새로 만들 것은 없고 줄이기만 있다).
         */
        List<ProposalAdjustment> adjustments,
        List<UnavailableWindowSpec> unavailableWindows,
        LocalDate periodStartDate,
        LocalDate periodEndDate,
        List<ContextChangeSuggestion> contextChanges,
        /**
         * 이번 제안의 목적. PERIOD_PLAN이면 서버가 기간·강도·대상 프로젝트를 검증한 뒤 기간 계획
         * OFFER(CREATE_PERIOD_PLAN)를 만들고, 실제 생성은 계획 화면과 같은 PlanDraftService가
         * 한다. EXECUTION_CHANGE나 null이면 기존 일반 제안 경로다. 진입 탭이 아니라 이 값이
         * 계획 경로를 정한다.
         */
        ProposalPurpose proposalPurpose,
        /** PERIOD_PLAN일 때 사용자가 말했거나 되물어 확인한 강도. 모르면 null이고 서버가 되묻는다. */
        PlanIntensity planIntensity,
        /** PERIOD_PLAN의 대상 프로젝트. 비어 있으면 활성 전체. 서버가 소유 여부를 다시 확인한다. */
        List<Long> targetCourseIds,
        /**
         * 대화에서 사용자가 말한 "시간을 차지하는 현실"을 구조화한 후보(약속·반복 일정).
         * contextChanges와 같은 sidecar다 — decision과 무관하게 어디에든 붙을 수 있고,
         * 이 필드가 있다고 responseType이 바뀌지 않는다. AiModelDecision을 늘리지 않는다.
         *
         * ai_proposals.items에 섞지 않는다. 저기는 사용자가 수행하고 완료하는 행동이고
         * 여기는 수행 대상이 아닌 사실이라, 한 묶음에 넣으면 적용 경로가 둘을 다시 갈라야 한다.
         *
         * 모델 출력을 그대로 신뢰하지 않는다 — ScheduleSuggestionService가 payload를 실제
         * 도메인 요청으로 읽어 본 뒤에만 PROPOSED로 남긴다.
         */
        List<ScheduleSuggestion> scheduleSuggestions
) {
}
