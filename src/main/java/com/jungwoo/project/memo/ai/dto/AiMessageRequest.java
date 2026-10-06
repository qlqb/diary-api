package com.jungwoo.project.memo.ai.dto;

import com.jungwoo.project.memo.ai.domain.AiProposalTargetScope;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * requestedAction=AUTO(기본)이면 message가 이번 사용자 턴의 원문이다.
 * requestedAction=CREATE_PROPOSAL이면 OFFER 카드의 버튼 클릭이므로 message는 비워도 되고,
 * 대신 sourceMessageId(그 OFFER를 만든 자신의 이전 USER 메시지)를 반드시 넣는다. 여기에
 * 그 카드가 보여주던 periodStartDate/periodEndDate도 함께 넣는다 — 이 두 날짜가 이번 계획의
 * 확정 기간이고, 모델은 이 단계에서 기간을 다시 판단하지 않는다.
 * requestedAction=CREATE_PERIOD_PLAN이면 기간 계획 OFFER 카드의 버튼 클릭이다. periodPlan에
 * 그 카드가 들고 있던 기간·강도·대상 프로젝트를 그대로 되돌려 보낸다(서버가 다시 검증한다).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiMessageRequest {

    private String message;

    private AiProposalTargetScope scope;

    private Long focusId;

    @Builder.Default
    private RequestedAction requestedAction = RequestedAction.AUTO;

    private Long sourceMessageId;

    /**
     * requestedAction=CREATE_PROPOSAL에서만 값을 가지며 그때는 필수다(둘 다). OFFER 카드가
     * 보여준 기간을 사용자가 보고 누른 값이라 이번 턴의 계획 기간 확정값이다. AUTO나
     * CREATE_PERIOD_PLAN 요청이 이 값을 실어 보내면 조용히 무시하지 않고 400으로 막는다 —
     * 어느 단계가 기간을 정하는지 흐릿해지면 같은 사실을 두 곳에서 판단하게 된다.
     */
    private LocalDate periodStartDate;

    private LocalDate periodEndDate;

    /** requestedAction=CREATE_PERIOD_PLAN에서만 값을 가진다. */
    private PeriodPlanRequest periodPlan;

    /**
     * 질문 카드의 빠른 답. message가 비어 있으면 서버가 저장된 질문의 선택지 라벨로 사용자 발화를 만든다 —
     * 선택 답과 자유 답은 같은 경로이고 둘 다 대화 기록에 남는다.
     */
    private Answer answer;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Answer {
        private String questionId;
        private java.util.List<String> choiceIds;
        private boolean skipped;
        /**
         * "내 자료에서 찾아봐". 질문의 선택지가 아니라 화면이 늘 주는 길이다 — 서버가 자료 확인을 넓힌다. 예전 화면은 이 칸을
         * 보내지 않으므로 객체형이다(원시형이면 본문에 없을 때 역직렬화가 실패한다).
         */
        private Boolean lookup;

        public boolean lookupRequested() {
            return Boolean.TRUE.equals(lookup);
        }
    }

    /**
     * 서버가 정한다: 이번 발화가 자료 확인을 넓혀 달라는 요청인가(LOOKUP 선택지·"내 자료에서 찾아봐"). 클라이언트 본문에서는
     * 읽지 않는다.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Boolean evidenceLookup;

    public boolean evidenceLookupRequested() {
        return Boolean.TRUE.equals(evidenceLookup);
    }

    @NotBlank(message = "idempotencyKey는 필수입니다")
    private String idempotencyKey;

    /** 이번 발화에 붙인 상담 교재 사진(이 대화에 올린 것만, 최대 4장). 서버가 소유·대화를 다시 확인한다. */
    private java.util.List<Long> photoIds;

    public boolean hasPhotos() {
        return photoIds != null && !photoIds.isEmpty();
    }
}
