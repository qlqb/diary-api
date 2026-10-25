package com.jungwoo.project.memo.ai.turn;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/** 이번 턴에 올린 제안을 뺀다(그 제안에 딸린 관련 변경도 함께). */
@Component
public class WithdrawChangeTool implements ConsultTool {

    @Override
    public String name() {
        return "withdraw_change";
    }

    @Override
    public String description() {
        return "이번 턴에 propose_change로 올린 제안(itemNo)을 뺀다. 딸린 관련 변경도 함께 빠진다.";
    }

    @Override
    public String inputSchema() {
        return """
                {"type":"object","properties":{"itemNo":{"type":"integer"}},"required":["itemNo"],"additionalProperties":false}""";
    }

    @Override
    public String progressLabel() {
        return "변경안 정리 중";
    }

    @Override
    public String call(TurnWorkspace ws, JsonNode args) {
        if (!args.path("itemNo").canConvertToInt()) {
            throw new IllegalArgumentException("itemNo는 정수여야 한다");
        }
        return ws.withdraw(args.path("itemNo").asInt()) ? "{\"result\":\"OK\"}" : "{\"result\":\"NOT_FOUND\"}";
    }
}
