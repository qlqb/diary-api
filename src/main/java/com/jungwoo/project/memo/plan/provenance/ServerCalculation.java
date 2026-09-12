package com.jungwoo.project.memo.plan.provenance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * 서버가 실제로 계산한 결과 하나와 그때의 조건. 모델의 추정과 같은 자리에 두지 않는다.
 *
 * <p>가용시간 추정(AvailabilityEstimateService)의 출력은 <b>의미를 유지해서</b> 담는다 —
 * source/confidence를 새 척도로 바꾸지 않는다. 추정은 추정으로 표시한다(handoff §3.2).
 *
 * @param inputRefIds   이 계산에 실제로 쓴 입력의 refId. 모델이 추측한 연결이 아니라 서버가
 *                      계산에 넣은 것들이다
 * @param inputLineage  위 목록이 전체 계보인지, 설명용 일부인지. 일부만 담고 "재현 가능"이라고
 *                      말하지 않기 위한 값이다(handoff §3.2)
 * @param lineageNote   PARTIAL일 때 무엇이 빠졌는지 한 줄
 * @param result        그 계산이 낸 값. 서비스가 돌려준 의미를 그대로 옮긴다
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ServerCalculation(
        String calculationId,
        ServerCalculationKind kind,
        boolean providedToModel,
        List<String> inputRefIds,
        InputLineage inputLineage,
        String lineageNote,
        Map<String, Object> result
) {

    public enum ServerCalculationKind {
        /** AvailabilityEstimateService.estimate() — 남는 시간 구간과 그 근거. */
        AVAILABILITY_ESTIMATE,

        /** 강도 비율로 정한 학습 예산(분). 가용시간 추정을 입력으로 받는다. */
        STUDY_BUDGET
    }

    public enum InputLineage {
        /** 이 계산에 들어간 입력이 refId로 빠짐없이 남아 있다. */
        COMPLETE,

        /** 일부만 남아 있다. 전체 재현의 근거로 쓰면 안 된다. */
        PARTIAL
    }
}
