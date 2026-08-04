package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.domain.AiMessage;
import com.jungwoo.project.memo.ai.domain.MessageRole;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 매 사용자 메시지마다 대화 원문 전체를 OpenAI에 보내지 않기 위해, 서버가 고정 크기의
 * 컨텍스트만 구성한다: 저장된 요약(있으면) 1개 + 최근 메시지 N개. 일기 원문/전체 실행
 * 기록은 여기 포함하지 않는다 — 화면 범위 데이터는 컨트롤러 레벨에서 focusId로만 참조한다.
 *
 * 오래된 대화를 요약하는 별도 AI 호출은 이번 범위에 없다 (요구사항: 사용자 메시지마다 두
 * 번째 AI 호출을 추가하지 말 것). summary는 컬럼만 두고, 최근 메시지 개수 제한부터
 * 정확히 적용한다.
 */
@Service
@RequiredArgsConstructor
public class ContextSnapshotService {

    private final AiMessageMapper aiMessageMapper;

    @Value("${ai.context.recent-message-limit:6}")
    private int recentMessageLimit;

    @Transactional(readOnly = true)
    public String buildContextBlock(Long conversationId, Long userId, String summary) {
        List<AiMessage> recent = aiMessageMapper.findRecentByConversationIdAndUserId(
                conversationId, userId, recentMessageLimit);

        StringBuilder sb = new StringBuilder();
        if (summary != null && !summary.isBlank()) {
            sb.append("[이전 대화 요약]\n").append(summary).append("\n\n");
        }
        if (!recent.isEmpty()) {
            sb.append("[최근 대화 (최대 ").append(recentMessageLimit).append("개)]\n");
            for (AiMessage message : recent) {
                String roleLabel = message.getRole() == MessageRole.USER ? "사용자" : "AI";
                sb.append(roleLabel).append(": ").append(message.getContent()).append('\n');
            }
        }
        return sb.toString();
    }
}
