package com.jungwoo.project.memo.plan;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.plan.domain.PlanStrategy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * plan_versions.strategy_json / ai_proposals.plan_strategy_json의 JSON ↔ PlanStrategy 변환.
 *
 * <p>PlanSnapshotCodec과 같은 자리에 있지만 강도가 다르다. 스냅샷은 우리가 만든 값이라
 * 깨졌으면 저장을 막는 것이 옳지만, 전략은 읽는 쪽이 관대해야 한다 — 판단이 없거나
 * 읽히지 않는다고 해서 계획 조회가 실패하면 안 된다. 판단은 계획의 부가 설명이지
 * 계획 자체가 아니다.
 */
@Component
public class PlanStrategyCodec {

    /*
     * 모르는 enum 값을 null로 읽는다. 기본 동작은 예외인데, 그러면 모델이 EvidenceType을
     * 하나 지어냈다는 이유로 전략 전체가 읽히지 않는다 — 근거 하나를 버리는 것과 판단
     * 전체를 잃는 것은 무게가 다르다. null로 들어온 값은 sanitize()가 걷어낸다.
     */
    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL);

    /**
     * 전략을 JSON으로 굳힌다. null이면 null을 돌려준다 — 판단 없이 만든 계획이 있을 수 있고
     * (예전 계획, 그리고 판단층을 거치지 않는 경로) 그때 컬럼은 NULL이어야 한다.
     */
    public String toJson(PlanStrategy strategy) {
        if (strategy == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(strategy);
        } catch (Exception e) {
            throw new IllegalStateException("계획 전략 직렬화 실패", e);
        }
    }

    /** 읽지 못하면 null이다. 예외를 던지지 않는다 — 위 클래스 주석 참고. */
    public PlanStrategy fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, PlanStrategy.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 모델이 낸 전략에서 서버가 인정하지 않는 부분을 걷어낸다.
     *
     * <p>걷어내는 것은 모르는 enum 값이다. 위 ObjectMapper 설정이 그것을 null로 읽어
     * 두는데, null인 채로 목록에 남으면 "근거가 하나 있다"로 세어져 근거 등급표가 잘못
     * 적용된다. 근거의 개수가 취급의 상한을 정하므로, 세는 것과 남아 있는 것이 같아야 한다.
     * 취급(treatment)을 못 읽은 항목은 아예 뺀다 — 판단되지 않은 항목은 근거 없음이고,
     * 근거 없음은 FULL이다.
     *
     * <p>취급 상한 강제(근거 등급 → FULL/SKIM/SKIP)는 여기가 아니라 판단 서비스가 한다 —
     * 그쪽만이 근거의 내용(맥락의 수명주기, 범위 일치 여부)을 안다.
     */
    public PlanStrategy sanitize(PlanStrategy strategy) {
        if (strategy == null || strategy.topics() == null) {
            return strategy;
        }
        List<PlanStrategy.TopicTreatment> topics = new ArrayList<>();
        for (PlanStrategy.TopicTreatment topic : strategy.topics()) {
            if (topic == null || topic.topicId() == null || topic.treatment() == null) {
                continue;
            }
            topics.add(new PlanStrategy.TopicTreatment(
                    topic.topicId(), topic.treatment(), topic.rank(), topic.reason(),
                    knownEvidence(topic.evidence())));
        }
        return new PlanStrategy(
                strategy.goal(), strategy.strategySummary(), strategy.strategySource(),
                strategy.reusedFromVersionId(), strategy.referencedContextIds(),
                strategy.courses(), topics, strategy.planningRules());
    }

    private List<PlanStrategy.Evidence> knownEvidence(List<PlanStrategy.Evidence> evidence) {
        if (evidence == null) {
            return List.of();
        }
        List<PlanStrategy.Evidence> kept = new ArrayList<>();
        for (PlanStrategy.Evidence one : evidence) {
            if (one != null && one.type() != null) {
                kept.add(one);
            }
        }
        return kept;
    }
}
