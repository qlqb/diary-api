package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.domain.AiMessage;
import com.jungwoo.project.memo.ai.domain.MessageRole;
import com.jungwoo.project.memo.ai.domain.UserContext;
import com.jungwoo.project.memo.ai.domain.UserContextStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [장기 컨텍스트] 블록 조립을 검증한다. SUPERSEDED/ARCHIVED 제외는 UserContextMapper.
 * findActiveAndStaleByUserId의 SQL(WHERE status IN ('ACTIVE','STALE'))이 계약으로 보장한다 —
 * 이 서비스는 그 계약을 신뢰하고 반환된 값을 그대로 포맷팅만 한다.
 */
@ExtendWith(MockitoExtension.class)
class ContextSnapshotServiceTest {

    private static final Long CONVERSATION_ID = 10L;
    private static final Long USER_ID = 1L;

    @Mock private AiMessageMapper aiMessageMapper;
    @Mock private UserContextMapper userContextMapper;

    @InjectMocks
    private ContextSnapshotService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "recentMessageLimit", 6);
        ReflectionTestUtils.setField(service, "longTermLimit", 30);
    }

    @Test
    void buildContextBlock_includesActiveAndStaleContexts_withContextId() {
        when(aiMessageMapper.findRecentByConversationIdAndUserId(any(), any(), anyInt())).thenReturn(List.of());
        when(userContextMapper.findActiveAndStaleByUserId(USER_ID, 30)).thenReturn(List.of(
                context(12L, "현재 알바는 보통 23시에 끝난다.", UserContextStatus.ACTIVE),
                context(13L, "현재 알바에서 집까지 약 50분 걸린다.", UserContextStatus.STALE)
        ));

        String block = service.buildContextBlock(CONVERSATION_ID, USER_ID, null);

        assertThat(block).contains("[장기 컨텍스트]");
        assertThat(block).contains("#12 [ACTIVE] 현재 알바는 보통 23시에 끝난다.");
        assertThat(block).contains("#13 [STALE] 현재 알바에서 집까지 약 50분 걸린다.");
    }

    @Test
    void buildContextBlock_noLongTermContexts_omitsSection() {
        when(aiMessageMapper.findRecentByConversationIdAndUserId(any(), any(), anyInt())).thenReturn(List.of());
        when(userContextMapper.findActiveAndStaleByUserId(USER_ID, 30)).thenReturn(List.of());

        String block = service.buildContextBlock(CONVERSATION_ID, USER_ID, null);

        assertThat(block).doesNotContain("[장기 컨텍스트]");
    }

    @Test
    void buildContextBlock_appliesConfiguredLimit() {
        ReflectionTestUtils.setField(service, "longTermLimit", 5);
        when(aiMessageMapper.findRecentByConversationIdAndUserId(any(), any(), anyInt())).thenReturn(List.of());
        when(userContextMapper.findActiveAndStaleByUserId(eq(USER_ID), eq(5))).thenReturn(List.of());

        service.buildContextBlock(CONVERSATION_ID, USER_ID, null);

        ArgumentCaptor<Integer> limitCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(userContextMapper).findActiveAndStaleByUserId(eq(USER_ID), limitCaptor.capture());
        assertThat(limitCaptor.getValue()).isEqualTo(5);
    }

    @Test
    void buildContextBlock_combinesSummary_recentMessages_andLongTermContext() {
        when(aiMessageMapper.findRecentByConversationIdAndUserId(any(), any(), anyInt())).thenReturn(List.of(
                AiMessage.builder().role(MessageRole.USER).content("오늘 뭐 하지").build()
        ));
        when(userContextMapper.findActiveAndStaleByUserId(USER_ID, 30)).thenReturn(List.of(
                context(20L, "학교에서 예전 집까지 약 40분 걸렸다.", UserContextStatus.STALE)
        ));

        String block = service.buildContextBlock(CONVERSATION_ID, USER_ID, "지난 대화 요약");

        assertThat(block).contains("[이전 대화 요약]").contains("지난 대화 요약");
        assertThat(block).contains("[최근 대화").contains("오늘 뭐 하지");
        assertThat(block).contains("[장기 컨텍스트]").contains("#20 [STALE]");
        // 순서: 요약 -> 최근 대화 -> 장기 컨텍스트.
        assertThat(block.indexOf("[이전 대화 요약]"))
                .isLessThan(block.indexOf("[최근 대화"))
                .isLessThan(block.indexOf("[장기 컨텍스트]"));
    }

    private static UserContext context(Long contextId, String content, UserContextStatus status) {
        return UserContext.builder().contextId(contextId).content(content).status(status).build();
    }

}
