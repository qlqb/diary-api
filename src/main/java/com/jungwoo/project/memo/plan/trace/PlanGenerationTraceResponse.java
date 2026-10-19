package com.jungwoo.project.memo.plan.trace;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 초안 하나를 만든 회차의 실제 전달 기록(개발 검증용).
 *
 * @param recorded 기록이 있는가. 기록 이전의 초안이나 보존 기간이 지난 회차는 false다(오류가 아니라 사실)
 */
public record PlanGenerationTraceResponse(boolean recorded, String generationId, List<Call> calls) {

    /**
     * @param shown       {sectionIds, topicIds, deliveredSectionIds, perCourse[]} — 이 입력에 실제로 실린 것
     * @param textPurged  자료 삭제로 전문이 지워졌는가
     * @param systemPrompt includeText=true일 때만
     */
    public record Call(String callKind, int callOrder, String apiCommit, String modelName, Integer estimatedTokens,
                       String promptSha256, JsonNode shown, boolean textPurged, LocalDateTime createdAt,
                       String systemPrompt, String userPrompt) {
    }
}
