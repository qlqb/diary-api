package com.jungwoo.project.memo.plan.provenance;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * provenance JSON ↔ 객체 변환.
 *
 * <p>★ 읽기는 절대 실패하지 않는다. 이 값이 없거나 못 읽는다고 초안 조회·수정·적용이
 * 막히면, 출처 추적이 기존 기능의 가용성을 깎는 셈이 된다(handoff §8). 못 읽으면 null을
 * 돌려주고 화면이 "출처 기록 없음"으로 처리한다.
 *
 * <p>쓰기는 다르다. 직렬화가 실패하면 그건 서버 코드의 버그이고, 조용히 넘기면 그 회차는
 * 영원히 근거 없는 제안이 된다. 다만 생성 자체를 실패시키지는 않는다 — 사용자가 잃는 것이
 * 계획 전체가 되어서는 안 된다. 실패는 로그로 크게 남긴다.
 */
@Slf4j
@Component
public class PlanProvenanceCodec {

    /**
     * 날짜·시간은 문자열로 쓴다.
     *
     * <p>기본 설정이면 providedValue 같은 Map 안의 LocalDateTime이 숫자 배열([2026,9,11,9,0])로
     * 굳는다. 그러면 스냅샷의 시각 표기가 이 앱의 다른 API 응답과 달라지고, 몇 달 뒤에 이
     * JSON을 읽는 사람이 배열의 어느 칸이 분인지 세어야 한다.
     */
    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public String toJson(PlanProvenance provenance) {
        if (provenance == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(provenance);
        } catch (Exception e) {
            log.error("생성 출처 스냅샷 직렬화 실패 — 이 회차는 출처 없이 저장된다. generationId={}",
                    provenance.generationId(), e);
            return null;
        }
    }

    public PlanProvenance fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, PlanProvenance.class);
        } catch (Exception e) {
            log.warn("생성 출처 스냅샷을 읽지 못했다 — 출처 없음으로 처리한다.", e);
            return null;
        }
    }

    public String toJson(PlanItemEvidence evidence) {
        if (evidence == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(evidence);
        } catch (Exception e) {
            log.error("항목 근거 직렬화 실패 — 이 항목은 근거 없이 저장된다.", e);
            return null;
        }
    }

    public PlanItemEvidence evidenceFromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, PlanItemEvidence.class);
        } catch (Exception e) {
            log.warn("항목 근거를 읽지 못했다 — 근거 없음으로 처리한다.", e);
            return null;
        }
    }
}
