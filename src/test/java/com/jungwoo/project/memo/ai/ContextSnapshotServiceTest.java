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
 * [장기 컨텍스트]/[최근 대화]/[이전 대화 요약] 세 영역을 contextBudgetChars 안에서 배분·조립하는
 * 것을 검증한다. 전체 문자열을 뒤에서부터 substring하던 예전 방식(장기 컨텍스트가 최근 메시지보다
 * 먼저 살아남는 우선순위 역전 버그)을 대신하는 로직이므로, 다음을 항상 함께 확인한다:
 * (1) 최종 길이가 contextBudgetChars를 넘지 않는가, (2) 최근 대화/장기 컨텍스트는 항상 온전한
 * 단위(메시지 한 개/행 한 개)로만 포함되거나 제외되는가(중간 substring 없음), (3) 우선순위
 * (최근 대화 > 장기 컨텍스트 > 요약)와 남는 예산 재분배가 지켜지는가.
 *
 * SUPERSEDED/ARCHIVED 제외는 UserContextMapper.findActiveAndStaleByUserId의 SQL 계약이 보장한다
 * — 이 서비스는 그 계약을 신뢰하고 반환된 값을 그대로 배분·포맷팅만 한다.
 */
@ExtendWith(MockitoExtension.class)
class ContextSnapshotServiceTest {

    private static final Long CONVERSATION_ID = 10L;
    private static final Long USER_ID = 1L;
    private static final int RECENT_MESSAGE_LIMIT = 6;
    private static final int LONG_TERM_LIMIT = 30;

    @Mock private AiMessageMapper aiMessageMapper;
    @Mock private UserContextMapper userContextMapper;

    @InjectMocks
    private ContextSnapshotService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "recentMessageLimit", RECENT_MESSAGE_LIMIT);
        ReflectionTestUtils.setField(service, "longTermLimit", LONG_TERM_LIMIT);
    }

    // ===== 1. 세 영역 모두 예산 안 =====

    @Test
    void allSectionsFitWithinBudget_includesEverythingAsIs() {
        AiMessage m1 = userMessage("어제 얘기했던 계획 이어서 하고 싶어");
        AiMessage m2 = assistantMessage("좋아요, 어디까지 진행했었는지 알려주세요");
        stubRecent(m1, m2);
        UserContext ctx = context(12L, "현재 알바는 보통 23시에 끝난다.", UserContextStatus.ACTIVE);
        stubLongTerm(ctx);
        String summary = "지난 대화에서 이직 준비 얘기를 나눴다.";

        int need = recentHeader().length() + recentLine(m1).length() + recentLine(m2).length()
                + LONG_TERM_HEADER.length() + longTermLine(ctx).length()
                + SUMMARY_HEADER.length() + summary.length();

        String block = service.buildContextBlock(CONVERSATION_ID, USER_ID, summary, need + 100);

        assertThat(block).contains(recentLine(m1)).contains(recentLine(m2));
        assertThat(block).contains(longTermLine(ctx));
        assertThat(block).contains(summary);
        assertThat(block).doesNotContain(SUMMARY_TRUNCATION_MARKER);
        assertThat(block.length()).isLessThanOrEqualTo(need + 100);
    }

    // ===== 2. 최근 메시지가 기본 50%보다 크지만 장기/요약이 적게 사용 → 남는 예산 재분배 =====

    @Test
    void recentNeedExceedsBaseShare_butOthersAreSmall_redistributionKeepsAllRecentMessages() {
        List<AiMessage> messages = List.of(
                userMessage("m1 " + "가".repeat(60)),
                assistantMessage("m2 " + "나".repeat(60)),
                userMessage("m3 " + "다".repeat(60)),
                assistantMessage("m4 " + "라".repeat(60))
        );
        stubRecent(messages.toArray(new AiMessage[0]));
        UserContext ctx = context(20L, "짧은 정보", UserContextStatus.ACTIVE);
        stubLongTerm(ctx);
        String summary = "짧은 요약";

        int recentNeed = recentHeader().length() + messages.stream().mapToInt(this::recentLineLen).sum();
        int longTermNeed = LONG_TERM_HEADER.length() + longTermLine(ctx).length();
        int summaryNeed = SUMMARY_HEADER.length() + summary.length();
        int total = recentNeed + longTermNeed + summaryNeed;
        // 이 테스트의 전제: 최근 대화 필요량이 기본 배분(50%)보다 크다 — 그래야 재분배 여부를
        // 실제로 가른다. 장기/요약은 자기 몫(35%/15%)을 넘지 않을 만큼 작게 유지한다.
        assertThat(recentNeed).isGreaterThan(total / 2);

        String block = service.buildContextBlock(CONVERSATION_ID, USER_ID, summary, total + 2);

        for (AiMessage m : messages) {
            assertThat(block).contains(recentLine(m));
        }
        assertThat(block).contains(longTermLine(ctx));
        assertThat(block).contains(summary);
        assertThat(block.length()).isLessThanOrEqualTo(total + 2);
    }

    // ===== 3. 전체 예산 부족 → 가장 오래된 최근 메시지부터 제거 =====

    @Test
    void recentBudgetInsufficient_dropsOldestMessagesFirst_keepsNewest() {
        AiMessage oldest = userMessage("오래된 메시지 내용입니다 " + "0".repeat(30));
        AiMessage middle = assistantMessage("중간 메시지 내용입니다 " + "1".repeat(30));
        AiMessage newest = userMessage("최신 메시지 내용입니다 " + "2".repeat(30));
        stubRecent(oldest, middle, newest);
        stubLongTerm();

        int budgetForTwoNewest = recentHeader().length() + recentLineLen(middle) + recentLineLen(newest) + 2;

        String block = service.buildContextBlock(CONVERSATION_ID, USER_ID, null, budgetForTwoNewest);

        assertThat(block).doesNotContain(oldest.getContent());
        assertThat(block).contains(recentLine(middle));
        assertThat(block).contains(recentLine(newest));
        assertThat(block.length()).isLessThanOrEqualTo(budgetForTwoNewest);
    }

    // ===== 4. 최근 메시지 하나가 allocation 경계에 걸림 → 중간 truncate 없이 통째로 제외 =====

    @Test
    void recentMessageJustOverBoundary_isExcludedWhole_notPartiallyTruncated() {
        AiMessage tooLarge = userMessage("고유표시_경계초과메시지_" + "x".repeat(100));
        AiMessage fits = assistantMessage("작은 최신 메시지");
        stubRecent(tooLarge, fits);
        stubLongTerm();

        // fits는 정확히 들어가지만 tooLarge를 더하면 1글자라도 넘치도록 예산을 잡는다.
        int budget = recentHeader().length() + recentLineLen(fits) + (recentLineLen(tooLarge) - 1) + 2;

        String block = service.buildContextBlock(CONVERSATION_ID, USER_ID, null, budget);

        assertThat(block).contains(recentLine(fits));
        // 부분적으로라도 섞여 들어가면 안 된다 — 고유 표시 문자열 자체가 전혀 없어야 한다.
        assertThat(block).doesNotContain("고유표시_경계초과메시지");
        assertThat(block.length()).isLessThanOrEqualTo(budget);
    }

    // ===== 5. 장기 Context 예산 부족 → 최근 갱신 Context 유지, 오래된 것 제외 =====

    @Test
    void longTermBudgetInsufficient_keepsRecentlyUpdatedContexts_dropsOldest() {
        // UserContextMapper는 이미 updated_at DESC(최근 갱신 우선)로 내려준다 — 그 순서 그대로 stub한다.
        UserContext recentlyUpdated = context(30L, "최근에 갱신된 정보", UserContextStatus.ACTIVE);
        UserContext midUpdated = context(29L, "중간에 갱신된 정보", UserContextStatus.ACTIVE);
        UserContext oldestUpdated = context(28L, "오래전에 갱신된 정보 " + "z".repeat(30), UserContextStatus.STALE);
        stubLongTerm(recentlyUpdated, midUpdated, oldestUpdated);
        stubRecent();

        int budgetForTwoNewest = LONG_TERM_HEADER.length()
                + longTermLine(recentlyUpdated).length() + longTermLine(midUpdated).length() + 2;

        String block = service.buildContextBlock(CONVERSATION_ID, USER_ID, null, budgetForTwoNewest);

        assertThat(block).contains(longTermLine(recentlyUpdated));
        assertThat(block).contains(longTermLine(midUpdated));
        assertThat(block).doesNotContain(oldestUpdated.getContent());
        assertThat(block.length()).isLessThanOrEqualTo(budgetForTwoNewest);
    }

    // ===== 6. 장기 Context 한 행이 너무 큼 → 중간 truncate 없이 제외 =====

    @Test
    void longTermRowTooLarge_isExcludedWhole_notTruncated() {
        UserContext huge = context(40L, "고유표시_거대한컨텍스트_" + "y".repeat(200), UserContextStatus.ACTIVE);
        stubLongTerm(huge);
        stubRecent();

        String block = service.buildContextBlock(CONVERSATION_ID, USER_ID, null, 50);

        assertThat(block).doesNotContain("고유표시_거대한컨텍스트");
        assertThat(block).doesNotContain("#40");
        assertThat(block.length()).isLessThanOrEqualTo(50);
    }

    // ===== 7. summary만 매우 큼 → 최근/장기는 그대로, summary만 제한 =====

    @Test
    void summaryMuchLarger_recentAndLongTermStayIntact_summaryTruncatedSafely() {
        AiMessage m = userMessage("짧은 최근 메시지");
        stubRecent(m);
        UserContext ctx = context(50L, "짧은 장기 정보", UserContextStatus.ACTIVE);
        stubLongTerm(ctx);
        String hugeSummary = "요약시작표시_" + "s".repeat(5000);

        int recentNeed = recentHeader().length() + recentLineLen(m);
        int longTermNeed = LONG_TERM_HEADER.length() + longTermLine(ctx).length();
        int budget = recentNeed + longTermNeed + 200; // summary가 다 들어가기엔 한참 부족한 예산.

        String block = service.buildContextBlock(CONVERSATION_ID, USER_ID, hugeSummary, budget);

        assertThat(block).contains(recentLine(m));
        assertThat(block).contains(longTermLine(ctx));
        assertThat(block).contains(SUMMARY_TRUNCATION_MARKER);
        assertThat(block).doesNotContain(hugeSummary); // 전체가 들어가지는 않았다.
        assertThat(block).contains("요약시작표시_"); // 앞부분은 남아 있다(잘림 방향: 앞부분 유지).
        assertThat(block.length()).isLessThanOrEqualTo(budget);
    }

    // ===== 8. recent/longTerm/summary 모두 매우 큼 =====

    @Test
    void allThreeSectionsHugelyExceedBudget_finalLengthWithinBudget_priorityRespected() {
        // 각 줄은 헤더(대략 10~20자)보다 훨씬 큰 80여 자로 만들어, 배분 후 남는 pool이 항상
        // 헤더+메시지 한 줄 이상은 담을 수 있을 만큼 넉넉한 여유를 둔다 — 그래야 "최근 대화
        // 섹션이 반드시 나타난다"는 단언이 헤더 정확한 글자 수와 무관하게 안정적으로 성립한다.
        List<AiMessage> messages = List.of(
                userMessage("메시지1 " + "a".repeat(80)),
                assistantMessage("메시지2 " + "b".repeat(80)),
                userMessage("메시지3 " + "c".repeat(80)),
                assistantMessage("메시지4 " + "d".repeat(80)),
                userMessage("메시지5 " + "e".repeat(80)),
                assistantMessage("메시지6 " + "f".repeat(80))
        );
        stubRecent(messages.toArray(new AiMessage[0]));
        List<UserContext> contexts = List.of(
                context(60L, "장기1 " + "g".repeat(80), UserContextStatus.ACTIVE),
                context(61L, "장기2 " + "h".repeat(80), UserContextStatus.STALE),
                context(62L, "장기3 " + "i".repeat(80), UserContextStatus.ACTIVE),
                context(63L, "장기4 " + "j".repeat(80), UserContextStatus.STALE),
                context(64L, "장기5 " + "k".repeat(80), UserContextStatus.ACTIVE),
                context(65L, "장기6 " + "l".repeat(80), UserContextStatus.STALE),
                context(66L, "장기7 " + "m".repeat(80), UserContextStatus.ACTIVE),
                context(67L, "장기8 " + "n".repeat(80), UserContextStatus.STALE),
                context(68L, "장기9 " + "o".repeat(80), UserContextStatus.ACTIVE),
                context(69L, "장기10 " + "p".repeat(80), UserContextStatus.STALE)
        );
        stubLongTerm(contexts.toArray(new UserContext[0]));
        String hugeSummary = "q".repeat(4000);

        int budget = 500;
        String block = service.buildContextBlock(CONVERSATION_ID, USER_ID, hugeSummary, budget);

        assertThat(block.length()).isLessThanOrEqualTo(budget);
        // 우선순위: 최근 대화가 가장 먼저 예산을 받으므로 이렇게 넉넉한 줄 크기 차이에서는
        // 반드시 일부라도 포함된다.
        assertThat(block).contains("[최근 대화");
        if (block.contains("[이전 대화 요약]")) {
            assertThat(block.indexOf("[이전 대화 요약]")).isLessThan(block.indexOf("[최근 대화"));
        }
        if (block.contains("[장기 컨텍스트]")) {
            assertThat(block.indexOf("[최근 대화")).isLessThan(block.indexOf("[장기 컨텍스트]"));
        }
    }

    // ===== 9. contextBudgetChars=0 =====

    @Test
    void zeroBudget_returnsEmptyString_noException() {
        stubRecent(userMessage("아무 메시지"));
        stubLongTerm(context(1L, "아무 컨텍스트", UserContextStatus.ACTIVE));

        String block = service.buildContextBlock(CONVERSATION_ID, USER_ID, "아무 요약", 0);

        assertThat(block).isEmpty();
    }

    // ===== 10. 장기 Context 30개(long-term-limit) 제한 유지 =====

    @Test
    void appliesConfiguredLongTermLimit() {
        stubRecent();
        when(userContextMapper.findActiveAndStaleByUserId(eq(USER_ID), eq(LONG_TERM_LIMIT))).thenReturn(List.of());

        service.buildContextBlock(CONVERSATION_ID, USER_ID, null, 1000);

        ArgumentCaptor<Integer> limitCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(userContextMapper).findActiveAndStaleByUserId(eq(USER_ID), limitCaptor.capture());
        assertThat(limitCaptor.getValue()).isEqualTo(LONG_TERM_LIMIT);
    }

    // ===== 11. ACTIVE/STALE 포함, context_id 노출 =====

    @Test
    void includesActiveAndStaleContexts_withContextId() {
        stubRecent();
        UserContext active = context(12L, "현재 알바는 보통 23시에 끝난다.", UserContextStatus.ACTIVE);
        UserContext stale = context(13L, "현재 알바에서 집까지 약 50분 걸린다.", UserContextStatus.STALE);
        stubLongTerm(active, stale);

        String block = service.buildContextBlock(CONVERSATION_ID, USER_ID, null, 1000);

        assertThat(block).contains("[장기 컨텍스트]");
        assertThat(block).contains("#12 [ACTIVE] 현재 알바는 보통 23시에 끝난다.");
        assertThat(block).contains("#13 [STALE] 현재 알바에서 집까지 약 50분 걸린다.");
    }

    // ===== 12. 최근 메시지 content가 원본과 정확히 동일(partial substring 없음) =====

    @Test
    void includedRecentMessageContent_matchesOriginalExactly_noPartialSubstring() {
        String tricky = "여러 문장. 쉼표, 특수문자!? 그리고 줄 안에서 안 잘려야 함.";
        AiMessage m = userMessage(tricky);
        stubRecent(m);
        stubLongTerm();

        String block = service.buildContextBlock(CONVERSATION_ID, USER_ID, null, 1000);

        assertThat(block).contains("사용자: " + tricky + "\n");
    }

    @Test
    void buildContextBlock_noLongTermContexts_omitsSection() {
        stubRecent();
        stubLongTerm();

        String block = service.buildContextBlock(CONVERSATION_ID, USER_ID, null, 1000);

        assertThat(block).doesNotContain("[장기 컨텍스트]");
    }

    @Test
    void buildContextBlock_combinesSummary_recentMessages_andLongTermContext_inOrder() {
        stubRecent(userMessage("오늘 뭐 하지"));
        stubLongTerm(context(20L, "학교에서 예전 집까지 약 40분 걸렸다.", UserContextStatus.STALE));

        String block = service.buildContextBlock(CONVERSATION_ID, USER_ID, "지난 대화 요약", 1000);

        assertThat(block).contains("[이전 대화 요약]").contains("지난 대화 요약");
        assertThat(block).contains("[최근 대화").contains("오늘 뭐 하지");
        assertThat(block).contains("[장기 컨텍스트]").contains("#20 [STALE]");
        assertThat(block.indexOf("[이전 대화 요약]"))
                .isLessThan(block.indexOf("[최근 대화"))
                .isLessThan(block.indexOf("[장기 컨텍스트]"));
    }

    // ===== helpers =====

    private static final String LONG_TERM_HEADER = "[장기 컨텍스트]\n";
    private static final String SUMMARY_HEADER = "[이전 대화 요약]\n";
    private static final String SUMMARY_TRUNCATION_MARKER = "...(이하 생략)...";

    private void stubRecent(AiMessage... messages) {
        when(aiMessageMapper.findRecentByConversationIdAndUserId(any(), any(), anyInt()))
                .thenReturn(List.of(messages));
    }

    private void stubLongTerm(UserContext... contexts) {
        when(userContextMapper.findActiveAndStaleByUserId(any(), any()))
                .thenReturn(List.of(contexts));
    }

    private static String recentHeader() {
        return "[최근 대화 (최대 " + RECENT_MESSAGE_LIMIT + "개)]\n";
    }

    private static String recentLine(AiMessage message) {
        String label = message.getRole() == MessageRole.USER ? "사용자" : "AI";
        return label + ": " + message.getContent() + "\n";
    }

    private int recentLineLen(AiMessage message) {
        return recentLine(message).length();
    }

    private static String longTermLine(UserContext context) {
        return "#" + context.getContextId() + " [" + context.getStatus() + "] " + context.getContent() + "\n";
    }

    private static UserContext context(Long contextId, String content, UserContextStatus status) {
        return UserContext.builder().contextId(contextId).content(content).status(status).build();
    }

    private static AiMessage userMessage(String content) {
        return AiMessage.builder().role(MessageRole.USER).content(content).build();
    }

    private static AiMessage assistantMessage(String content) {
        return AiMessage.builder().role(MessageRole.ASSISTANT).content(content).build();
    }
}
