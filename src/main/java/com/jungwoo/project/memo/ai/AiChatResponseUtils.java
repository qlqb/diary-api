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

    /**
     * 이번 응답이 왜 끝났는지("STOP"/"LENGTH"/"CONTENT_FILTER" 등). 모델별 구현 클래스를
     * 캐스팅하지 않고 Spring AI의 공개 인터페이스(ChatGenerationMetadata)만으로 받는다.
     *
     * <p>"LENGTH"는 출력이 상한에서 잘렸다는 뜻이다. 구조화 JSON을 받는 호출에서 이 값을
     * 확인하지 않으면, 잘린 JSON이 그냥 파싱 실패로 보고돼 원인이 토큰 예산이라는 사실이
     * 스택트레이스 뒤에 숨는다.
     */
    public static String extractFinishReason(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getResult() == null
                || chatResponse.getResult().getMetadata() == null) {
            return null;
        }
        String reason = chatResponse.getResult().getMetadata().getFinishReason();
        return (reason != null && !reason.isBlank()) ? reason : null;
    }

    public static boolean isTruncatedByTokenLimit(String finishReason) {
        return "LENGTH".equalsIgnoreCase(finishReason);
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
