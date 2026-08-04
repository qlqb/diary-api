package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.domain.AiConversation;
import com.jungwoo.project.memo.ai.domain.AiProposalTargetScope;
import com.jungwoo.project.memo.ai.domain.ConversationStatus;
import com.jungwoo.project.memo.ai.dto.AiMessageRequest;
import com.jungwoo.project.memo.ai.dto.RequestedAction;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 실제 로컬 MariaDB(memo)에 대고 두 스레드로 같은 대화방에 동시에 요청을 보내
 * AiTurnLifecycleService.prepareTurn()의 SELECT ... FOR UPDATE 직렬화가 실제로
 * "정확히 하나만 진행하고 나머지는 409로 막는다"는 것을 증명한다.
 *
 * Mockito 단위 테스트(AiTurnLifecycleServiceTest)는 가드 조건이 올바른 인자로 호출되는지만
 * 증명할 수 있고, @Transactional 프록시가 실제로 트랜잭션 경계를 만드는지, MariaDB가 행
 * 잠금을 실제로 직렬화하는지는 이 통합 테스트가 아니면 증명할 수 없다.
 *
 * AiConsultationClient는 이 환경에 OPENAI_API_KEY가 없어 항상 isConfigured()=false이므로,
 * 잠금 로직까지 도달하도록 스텁으로 교체한다(실제 OpenAI는 호출하지 않는다 — 이 테스트는
 * AI 호출 이전 단계인 prepareTurn()만 검증한다).
 */
@SpringBootTest
class AiTurnLifecycleServiceConcurrencyTest {

    private static final Long TEST_USER_ID = 999_000_001L;

    @TestConfiguration
    static class StubAiConfig {
        @Bean
        @Primary
        AiConsultationClient stubAiConsultationClient() {
            AiConsultationClient stub = mock(AiConsultationClient.class);
            when(stub.isConfigured()).thenReturn(true);
            return stub;
        }
    }

    @Autowired
    private AiTurnLifecycleService service;

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
    void concurrentRequests_onSameConversation_exactlyOneProceeds_otherGetsBusy() throws Exception {
        AiConversation conversation = AiConversation.builder()
                .userId(TEST_USER_ID)
                .scope(AiProposalTargetScope.TODAY)
                .status(ConversationStatus.ACTIVE)
                .build();
        aiConversationMapper.insert(conversation);
        conversationId = conversation.getConversationId();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);

        Callable<Object> taskA = () -> attemptPrepareTurn(barrier, "concurrency-key-A");
        Callable<Object> taskB = () -> attemptPrepareTurn(barrier, "concurrency-key-B");

        Future<Object> futureA = pool.submit(taskA);
        Future<Object> futureB = pool.submit(taskB);

        Object resultA = futureA.get(15, TimeUnit.SECONDS);
        Object resultB = futureB.get(15, TimeUnit.SECONDS);
        pool.shutdown();

        long successCount = Stream.of(resultA, resultB)
                .filter(r -> r instanceof AiTurnLifecycleService.PreparedTurn)
                .count();
        long busyCount = Stream.of(resultA, resultB)
                .filter(r -> r instanceof ConflictException ce && ce.getErrorCode() == ErrorCode.AI_CONVERSATION_BUSY)
                .count();

        assertThat(successCount)
                .withFailMessage("정확히 한 요청만 진행해야 한다. results=%s, %s", resultA, resultB)
                .isEqualTo(1);
        assertThat(busyCount)
                .withFailMessage("나머지 한 요청은 AI_CONVERSATION_BUSY로 막혀야 한다. results=%s, %s", resultA, resultB)
                .isEqualTo(1);
    }

    private Object attemptPrepareTurn(CyclicBarrier barrier, String idempotencyKey) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        try {
            return service.prepareTurn(conversationId, TEST_USER_ID, AiMessageRequest.builder()
                    .message("동시 요청 테스트")
                    .requestedAction(RequestedAction.AUTO)
                    .idempotencyKey(idempotencyKey)
                    .build());
        } catch (Exception e) {
            return e;
        }
    }
}
