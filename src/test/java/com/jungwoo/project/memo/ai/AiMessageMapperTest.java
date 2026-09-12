package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.domain.AiConversation;
import com.jungwoo.project.memo.ai.domain.AiMessage;
import com.jungwoo.project.memo.ai.domain.AiProposalTargetScope;
import com.jungwoo.project.memo.ai.domain.ConversationStatus;
import com.jungwoo.project.memo.ai.domain.MessageRole;
import com.jungwoo.project.memo.ai.domain.MessageStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * findRecentByConversationIdAndUserId의 excludeMessageId 필터를 실제 로컬 MariaDB(memo)에
 * 대고 검증한다. Mockito 단위 테스트(ContextSnapshotServiceTest)는 서비스가 excludeMessageId를
 * 마퍼 호출에 올바르게 "전달"하는지까지만 증명할 수 있고, 그 인자가 실제 SQL(AND message_id !=
 * #{excludeMessageId})에서 정확히 걸러지는지는 이 통합 테스트가 아니면 증명할 수 없다
 * (AiTurnLifecycleServiceConcurrencyTest와 같은 이유).
 *
 * prepareTurn()이 스트리밍 시작 전 현재 사용자 메시지를 이미 PROCESSING으로 저장해두므로,
 * 이 필터가 없으면 "최근 대화"에 현재 발언이 다시 섞여 사용자 상담 원문과 중복된다.
 */
@SpringBootTest
class AiMessageMapperTest {

    private static final Long TEST_USER_ID = 999_000_002L;

    @Autowired
    private AiMessageMapper aiMessageMapper;

    @Autowired
    private AiConversationMapper aiConversationMapper;

    @Autowired
    private DataSource dataSource;

    private Long conversationId;

    @AfterEach
    void cleanUp() throws Exception {
        if (conversationId == null) {
            return;
        }
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM ai_messages WHERE conversation_id = ?")) {
                ps.setLong(1, conversationId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM ai_conversations WHERE conversation_id = ?")) {
                ps.setLong(1, conversationId);
                ps.executeUpdate();
            }
        }
    }

    @Test
    void findRecentByConversationIdAndUserId_excludesGivenMessageId_keepsOthersInOriginalOrder() {
        setUpConversation();
        AiMessage old1 = insertMessage(MessageRole.USER, "이전 사용자 메시지", MessageStatus.COMPLETED);
        AiMessage old2 = insertMessage(MessageRole.ASSISTANT, "이전 AI 응답", MessageStatus.COMPLETED);
        // prepareTurn()이 스트리밍 전에 미리 저장해두는 "현재 요청" 메시지를 흉내낸다.
        AiMessage current = insertMessage(MessageRole.USER, "나 이사해서 이제 출퇴근이 20분 걸려", MessageStatus.PROCESSING);

        List<AiMessage> recent = aiMessageMapper.findRecentByConversationIdAndUserId(
                conversationId, TEST_USER_ID, 6, current.getMessageId());

        assertThat(recent).extracting(AiMessage::getMessageId)
                .containsExactly(old1.getMessageId(), old2.getMessageId());
        assertThat(recent).noneMatch(m -> m.getMessageId().equals(current.getMessageId()));
    }

    @Test
    void findRecentByConversationIdAndUserId_excludeAndLimit_dropsOldestFirst_keepsOrder() {
        setUpConversation();
        AiMessage m1 = insertMessage(MessageRole.USER, "old1", MessageStatus.COMPLETED);
        AiMessage m2 = insertMessage(MessageRole.ASSISTANT, "old2", MessageStatus.COMPLETED);
        AiMessage m3 = insertMessage(MessageRole.USER, "old3", MessageStatus.COMPLETED);
        AiMessage m4 = insertMessage(MessageRole.ASSISTANT, "old4", MessageStatus.COMPLETED);
        AiMessage current = insertMessage(MessageRole.USER, "current", MessageStatus.PROCESSING);

        // limit=3, 현재 메시지를 제외한 나머지(m1~m4) 중 최신 3개(m2,m3,m4)만 오래된->최신 순으로 남아야 한다.
        List<AiMessage> recent = aiMessageMapper.findRecentByConversationIdAndUserId(
                conversationId, TEST_USER_ID, 3, current.getMessageId());

        assertThat(recent).extracting(AiMessage::getMessageId)
                .containsExactly(m2.getMessageId(), m3.getMessageId(), m4.getMessageId());
    }

    @Test
    void findRecentByConversationIdAndUserId_nullExcludeId_behavesAsBefore() {
        setUpConversation();
        AiMessage m1 = insertMessage(MessageRole.USER, "m1", MessageStatus.COMPLETED);
        AiMessage m2 = insertMessage(MessageRole.ASSISTANT, "m2", MessageStatus.COMPLETED);

        List<AiMessage> recent = aiMessageMapper.findRecentByConversationIdAndUserId(
                conversationId, TEST_USER_ID, 6, null);

        assertThat(recent).extracting(AiMessage::getMessageId)
                .containsExactly(m1.getMessageId(), m2.getMessageId());
    }

    private void setUpConversation() {
        AiConversation conversation = AiConversation.builder()
                .userId(TEST_USER_ID)
                .scope(AiProposalTargetScope.TODAY)
                .status(ConversationStatus.ACTIVE)
                .build();
        aiConversationMapper.insert(conversation);
        conversationId = conversation.getConversationId();
    }

    private AiMessage insertMessage(MessageRole role, String content, MessageStatus status) {
        AiMessage message = AiMessage.builder()
                .conversationId(conversationId)
                .userId(TEST_USER_ID)
                .role(role)
                .content(content)
                .status(status)
                .build();
        aiMessageMapper.insert(message);
        return message;
    }
}
