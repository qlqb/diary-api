package com.jungwoo.project.memo.plan.dto;

import com.jungwoo.project.memo.ai.domain.AiProposalItemStatus;
import com.jungwoo.project.memo.plan.provenance.EvidenceStatus;
import com.jungwoo.project.memo.plan.provenance.ProvenanceRepresentation;
import com.jungwoo.project.memo.plan.provenance.ProvenanceSourceType;
import com.jungwoo.project.memo.plan.provenance.ServerCalculation;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * "이 초안을 만들 때 무엇을 참고했는가"와 "이 항목은 무엇을 근거로 하는가".
 *
 * <p>초안 화면과 적용된 항목 화면이 <b>같은 응답</b>을 쓴다. 화면마다 다른 모양으로
 * 내려주면 같은 사실이 두 곳에서 다르게 보이기 시작한다.
 *
 * <p>★ 세 가지를 한 덩어리로 묶지 않는다. 제공한 원본({@link #providedSources}), 서버가
 * 계산한 것({@link #serverCalculations}), 모델의 추정({@link ItemView#aiEstimates})은
 * 서로 다른 무게를 갖는다.
 *
 * @param recorded  이 제안에 생성 당시 출처 기록이 있는가. false면 화면은 "이 초안에는
 *                  생성 당시 출처 기록이 없습니다"라고 말하고 나머지 필드는 비어 있다
 */
public record PlanProvenanceResponse(
        boolean recorded,
        Long proposalId,
        Long planVersionId,
        String generationId,
        LocalDateTime capturedAt,
        String timezone,
        LocalDate startDate,
        LocalDate endDate,
        String generator,
        String modelName,
        List<SourceView> providedSources,
        List<ServerCalculation> serverCalculations,
        List<ItemView> items
) {

    public static PlanProvenanceResponse notRecorded(Long proposalId, Long planVersionId,
                                                     List<ItemView> items) {
        return new PlanProvenanceResponse(false, proposalId, planVersionId, null, null, null,
                null, null, null, null, List.of(), List.of(), items);
    }

    /**
     * 제공한 원본 하나.
     *
     * @param providedValue 그때 실제로 준 값. 원본이 나중에 바뀌어도 이 값은 그대로다
     * @param link          지금 원본을 열 수 있는가. 당시 값과 <b>다른 것</b>이라 따로 둔다
     */
    public record SourceView(
            String refId,
            ProvenanceSourceType sourceType,
            Long sourceId,
            Long sourceVersion,
            LocalDateTime sourceUpdatedAt,
            ProvenanceRepresentation representation,
            Map<String, Object> providedValue,
            String promptLine,
            LinkView link
    ) {
    }

    /**
     * 원본으로 가는 링크.
     *
     * @param target             화면이 무엇을 열어야 하는지. 열 곳이 없으면 null
     * @param unavailableReason  못 여는 이유. 지워졌거나 애초에 링크가 없는 종류다
     */
    public record LinkView(
            boolean available,
            String target,
            Long targetId,
            String unavailableReason
    ) {

        public static LinkView none(String reason) {
            return new LinkView(false, null, null, reason);
        }

        public static LinkView of(String target, Long targetId) {
            return new LinkView(true, target, targetId, null);
        }
    }

    /**
     * 항목 하나의 근거.
     *
     * @param recorded          이 항목에 근거 기록이 있는가. 레거시 제안은 false다
     * @param unknownRefCount   모델이 인용했지만 이번 회차에 없어 버린 개수. 정상 근거로
     *                          보여주지 않되, 있었다는 사실은 숨기지 않는다
     * @param afterApplyChanges 적용된 뒤 이 조각에 일어난 변경(사용자가 읽는 문장). 근거는
     *                          그대로이고 지금 내용·배치가 그때와 다르다는 뜻이다. 초안
     *                          조회에서는 항상 비어 있다
     */
    public record ItemView(
            Long proposalItemId,
            Long executionItemId,
            String title,
            AiProposalItemStatus status,
            boolean recorded,
            String generationId,
            List<String> refIds,
            String reason,
            List<String> aiEstimates,
            List<String> serverCalculationIds,
            EvidenceStatus evidenceStatus,
            List<String> staleReasons,
            int unknownRefCount,
            List<String> afterApplyChanges
    ) {
    }
}
