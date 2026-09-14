package com.jungwoo.project.memo.plan.provenance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 제안 항목 하나의 근거. ai_proposal_items.evidence_json에 항목마다 한 벌씩 저장된다.
 *
 * <p>세 가지를 <b>섞지 않는다</b>(handoff §2.2).
 * <ul>
 *   <li>{@link #refIds} — 이 항목의 근거로 연결한 <b>원본 정보</b>. 회차 스냅샷의 refId다.</li>
 *   <li>{@link #serverCalculationIds} — 서버가 실제로 <b>계산</b>한 결과.</li>
 *   <li>{@link #aiEstimates} — 모델의 <b>추정·판단</b>. 검증된 인과관계가 아니다.</li>
 * </ul>
 *
 * <p>★ refId가 유효하다는 것은 "그 정보를 모델에게 줬다"는 뜻일 뿐이다. 내용이 맞다는
 * 검증도, 제약을 다 반영했다는 검증도 아니다. 화면이 "검증됨"으로 읽히게 만들지 않는다.
 *
 * <p>★ 근거가 없으면 빈 refIds가 정답이다. 있어 보이게 채우지 않는다.
 *
 * @param generationId          이 근거가 속한 생성 회차. refId는 이 값과 함께 해석한다
 * @param reason                모델이 쓴 한 문장. 판단이지 확인된 사실이 아니다
 * @param unknownRefCount       모델이 인용했지만 이번 회차에 없어 버린 refId 개수.
 *                              정상 근거로 보여주지 않되, 있었다는 사실은 남긴다
 * @param staleReasons          상태가 CURRENT가 아닌 이유. 사용자가 읽는 문장이다
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanItemEvidence(
        String generationId,
        List<String> refIds,
        String reason,
        List<String> aiEstimates,
        List<String> serverCalculationIds,
        EvidenceStatus status,
        List<String> staleReasons,
        int unknownRefCount
) {

    public static PlanItemEvidence of(String generationId, List<String> refIds, String reason,
                                      List<String> aiEstimates, List<String> serverCalculationIds,
                                      int unknownRefCount) {
        return new PlanItemEvidence(generationId, safe(refIds), reason, safe(aiEstimates),
                safe(serverCalculationIds), EvidenceStatus.CURRENT, List.of(), unknownRefCount);
    }

    /** 상태만 바꾼 사본. 근거 자체는 그대로 둔다. */
    public PlanItemEvidence withStatus(EvidenceStatus newStatus, List<String> reasons) {
        return new PlanItemEvidence(generationId, refIds, reason, aiEstimates, serverCalculationIds,
                newStatus, safe(reasons), unknownRefCount);
    }

    private static List<String> safe(List<String> values) {
        return values == null ? List.of() : List.copyOf(new ArrayList<>(values));
    }
}
