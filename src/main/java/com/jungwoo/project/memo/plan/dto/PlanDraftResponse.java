package com.jungwoo.project.memo.plan.dto;

import com.jungwoo.project.memo.ai.dto.AiProposalResponse;
import com.jungwoo.project.memo.plan.domain.PlanIntensity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * 계획 초안. 아직 execution_items도 plan_versions도 만들어지지 않았다 — 사용자가 확정해야
 * 실제 데이터가 생긴다.
 *
 * <p>계획 화면(/api/plans/draft)과 AI 대화(period_plan.ready)가 같은 모양을 쓴다. 어느 탭에서
 * 만들었든 같은 검토·확정 화면이 받는다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlanDraftResponse {

    private Long proposalId;

    private LocalDate startDate;

    private LocalDate endDate;

    private Integer days;

    private PlanIntensity intensity;

    /**
     * 예전 화면 호환용. 지금은 targetMinutes와 같은 값이다 — 학습 예산은 서버가 가용시간과
     * 강도로 계산하고 모델이 조정하지 않는다.
     */
    private Integer baselineMinutes;

    /** 학습 예산(분). 추정 가용시간 × 강도 비율을 15분 단위로 내린 값. 소진할 할당량이 아니다. */
    private Integer targetMinutes;

    /** 예전 화면 호환용. 서버가 예산을 정하므로 항상 null이다. */
    private String targetMinutesReason;

    /** 계획 기간에서 고정 일정·지난 시간을 뺀 추정 남는 시간(분). */
    private Integer estimatedAvailableMinutes;

    /**
     * 남는 시간 추정의 근거 요약. 근거가 없어 기본 시간대(09~23시에서 확정 일정을 뺀 시간)를
     * 쓴 부분이 있으면 그 사실을 말한다 — 확정 사실처럼 보이지 않게 하기 위해서다.
     */
    private String availabilityConfidenceSummary;

    /** 추정 남는 시간에서 학습 예산을 뺀 여유(분). 휴식·변동에 남겨 둔 시간이다. */
    private Integer reservedBufferMinutes;

    /**
     * 강도 비율로 계산한 예산이 한 제안의 물리적 상한(항목 30개 × 120분 = 3,600분)을 넘어
     * 상한으로 깎였다. 8일 이상 계획에서 나온다 — 화면은 "이 기간의 남는 시간을 다 담지는
     * 못했다"고 말해야 한다. 실패가 아니다.
     */
    private boolean targetCappedByItemLimit;

    /** targetCappedByItemLimit일 때 담지 못한 시간(분). 남는 시간 × 강도 − 실제 예산. */
    private Integer uncoveredMinutes;

    /**
     * 추정 남는 시간이 0이라 항목을 만들지 않았다. proposal은 null이다. 화면은 실패가 아니라
     * "현재 추정으로는 배치 가능한 시간이 없다"와 가용시간 수정 경로를 보여준다.
     */
    private boolean noAvailableTime;

    private String suggestedTitle;

    private String goalSummary;

    /** 항목 목록. 사용자는 여기서 체크를 풀어 부하를 조절한다. noAvailableTime이면 null. */
    private AiProposalResponse proposal;

    /**
     * 이 초안을 만든 판단. 판단층을 거치지 않은 경로(AI·V0)는 null이다.
     *
     * <p>화면이 "이번 계획은 이렇게 봤어요"를 그리고, 조각의 취급을 topicId로 이어 붙인다.
     * 취급을 조각에 복사하지 않는 이유는 원본이 하나여야 하기 때문이다 — 두 곳에 두면
     * 조각 편집이 판단과 어긋나도 아무도 모른다.
     */
    private PlanStrategyResponse strategy;

    /**
     * 계획을 만들기 전에 물어볼 것이 있을 때만 값이 있다. 이때 proposal은 null이다.
     *
     * <p>화면은 선택지를 버튼으로 그리고, 고른 답을 지시문에 이어 붙여 다시 요청한다. 기존
     * 상담의 ASK_CLARIFICATION과 같은 패턴이며 새 상태 개념을 만들지 않는다.
     */
    private PlanJudgmentResult.Ask ask;

    /**
     * 대상 프로젝트에 연결됐지만 아직 자동 분석이 끝나지 않은 자료. 이 초안은 그 내용을 보지 못했다.
     * 화면은 "아직 반영되지 않은 자료 N개"로 짧게 말한다. 없으면 빈 목록.
     */
    private List<PendingMaterial> pendingMaterials;

    /**
     * 이번 생성에서 모델이 고른 자료와 서버가 읽어 넣은 원문 범위, 검토하지 못한 범위. 자료 선택을 거치지 않은
     * 경로(v0·판단)는 null.
     */
    private com.jungwoo.project.memo.plan.selection.MaterialSelectionSummary materialSelection;

    /** 이 초안을 만든 요청 중 화면이 알아야 하는 것. 다시 만들기(redraft)는 서버에 남은 요청을 쓴다. */
    private RequestContextView requestContext;

    /**
     * 이번 생성의 호출·토큰·지연과 상한, 자료 선택 재사용 여부. 모델이 완료를 선언하는 값이 아니라 서버가 센 값이다.
     * 저장된 초안을 다시 읽을 때는 근거 스냅샷의 서버 계산(GENERATION_CALLS)에서 복원한다.
     */
    private GenerationView generation;

    /** 이 초안이 대체한 초안과, 서버가 근거 스냅샷을 비교해 확인한 달라진 점. 처음 만든 초안이면 null. */
    private PreviousDraftView previousDraft;

    /** 상담 합의(ai_plan_briefs)에서 만든 초안이면 그 합의와 판. 계획 화면이면 null. */
    private Long briefId;
    private Integer briefVersion;

    /** 저장된 검토 상태(제목·제외·편집값·답). 새 초안은 null. 새로고침 복구가 이 값으로 화면을 되돌린다. */
    private PlanReviewState reviewState;

    @lombok.Getter
    @lombok.Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GenerationView {
        private int normalCalls;
        private int recoveryCalls;
        private int maxNormalCalls;
        private int maxTotalCalls;
        private int retrievalRounds;
        private int maxRetrievalRounds;
        private int inputTokens;
        private int outputTokens;
        private long elapsedMs;
        private boolean selectionReused;
        private List<String> calls;
    }

    @lombok.Getter
    @lombok.Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PreviousDraftView {
        private Long proposalId;
        private List<String> changes;
    }

    @lombok.Getter
    @lombok.Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RequestContextView {
        /** CONVERSATION / PLAN_SCREEN */
        private String source;
        private List<Long> courseIds;
        /** 「이번만 빼기」 목록(제목은 알 때만). 다시 만들 때 이 목록을 고쳐 보낸다. */
        private List<ExcludedTopic> excludedTopics;
        /** 이번 요청에서 지정한 자료(화면 지정 + 지시 문장에서 찾은 것). */
        private List<com.jungwoo.project.memo.plan.selection.MaterialSelectionSummary.RequestedMaterialView> requestedMaterials;
        /** 같은 조건으로 다시 만들 수 있는가(요청이 저장된 초안인가). */
        private boolean redraftable;
    }

    @lombok.Getter
    @lombok.Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExcludedTopic {
        private Long topicId;
        private String title;
    }

    @lombok.Getter
    @lombok.Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PendingMaterial {
        private Long materialId;
        private String filename;
        private String state;
        private Long courseId;
    }
}
