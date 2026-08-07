package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.domain.AiMessage;
import com.jungwoo.project.memo.ai.domain.MessageRole;
import com.jungwoo.project.memo.ai.domain.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 매 사용자 메시지마다 대화 원문 전체를 OpenAI에 보내지 않기 위해, 서버가 고정 크기의
 * 컨텍스트만 구성한다: 저장된 요약(있으면) 1개 + 최근 메시지 N개 + 사용자의 장기 컨텍스트
 * (ACTIVE/STALE만). 일기 원문/전체 실행 기록은 여기 포함하지 않는다 — 화면 범위 데이터는
 * 컨트롤러 레벨에서 focusId로만 참조한다.
 *
 * 장기 컨텍스트는 각 줄에 context_id를 함께 내려준다 — 모델이 SUPERSEDE/MARK_STALE/ARCHIVE/
 * CONFIRM 후보를 만들 때 그 id를 targetContextId로 그대로 참조하게 하기 위함이다. SUPERSEDED/
 * ARCHIVED 상태는 이미 대체되었거나 더 이상 쓰지 않는 사실이므로 일반 상담 프롬프트에 보내지
 * 않는다.
 *
 * 오래된 대화를 요약하는 별도 AI 호출은 이번 범위에 없다 (요구사항: 사용자 메시지마다 두
 * 번째 AI 호출을 추가하지 말 것). summary는 컬럼만 두고, 최근 메시지 개수 제한부터
 * 정확히 적용한다.
 */
@Service
@RequiredArgsConstructor
public class ContextSnapshotService {

    private final AiMessageMapper aiMessageMapper;
    private final UserContextMapper userContextMapper;

    @Value("${ai.context.recent-message-limit:6}")
    private int recentMessageLimit;

    /** 장기 컨텍스트가 토큰 예산을 잡아먹지 않도록 최근 갱신 순으로 이 개수까지만 프롬프트에 담는다. */
    @Value("${ai.context.long-term-limit:30}")
    private int longTermLimit;

    @Transactional(readOnly = true)
    public String buildContextBlock(Long conversationId, Long userId, String summary) {
        List<AiMessage> recent = aiMessageMapper.findRecentByConversationIdAndUserId(
                conversationId, userId, recentMessageLimit);
        List<UserContext> longTermContexts = userContextMapper.findActiveAndStaleByUserId(userId, longTermLimit);

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
        if (!longTermContexts.isEmpty()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("[장기 컨텍스트]\n");
            for (UserContext context : longTermContexts) {
                sb.append('#').append(context.getContextId())
                        .append(" [").append(context.getStatus()).append("] ")
                        .append(context.getContent()).append('\n');
            }
        }
        return sb.toString();
    }
}
