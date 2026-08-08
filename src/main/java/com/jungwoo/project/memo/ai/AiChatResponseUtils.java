package com.jungwoo.project.memo.ai;

import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * ChatResponse에서 텍스트/사용량을 안전하게 꺼내는 공용 헬퍼.
 * AiConversationService(Today 상담)뿐 아니라 Material/Learning/Planning Agent가 모두 같은
 * 방식으로 Spring AI ChatResponse를 다루므로 여기 하나로 모은다.
 */
public final class AiChatResponseUtils {

    private AiChatResponseUtils() {
    }

    public static String extractText(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getResult() == null || chatResponse.getResult().getOutput() == null) {
            return "";
        }
        String text = chatResponse.getResult().getOutput().getText();
        return text != null ? text : "";
    }

    public static Usage extractUsage(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getMetadata() == null) {
            return null;
        }
        return chatResponse.getMetadata().getUsage();
    }

    public static Integer safeTokenCount(Usage usage, boolean prompt) {
        if (usage == null) {
            return null;
        }
        try {
            return prompt ? usage.getPromptTokens() : usage.getCompletionTokens();
        } catch (Exception e) {
            return null;
        }
    }
}
