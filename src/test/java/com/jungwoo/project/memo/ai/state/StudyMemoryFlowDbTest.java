package com.jungwoo.project.memo.ai.state;

import com.jungwoo.project.memo.ai.AiConsultationClient;
import com.jungwoo.project.memo.ai.AiProposalMapper;
import com.jungwoo.project.memo.ai.AiStreamParser;
import com.jungwoo.project.memo.ai.AiWorkspaceContextBuilder;
import com.jungwoo.project.memo.ai.UserContextService;
import com.jungwoo.project.memo.ai.domain.AiConversation;
import com.jungwoo.project.memo.ai.domain.AiProposalTargetScope;
import com.jungwoo.project.memo.ai.domain.ContextEvidenceType;
import com.jungwoo.project.memo.ai.domain.FactKind;
import com.jungwoo.project.memo.ai.dto.RequestedAction;
import com.jungwoo.project.memo.ai.dto.UserContextResponse;
import com.jungwoo.project.memo.plan.PlanDraftService;
import com.jungwoo.project.memo.plan.dto.PlanDraftRequest;
import com.jungwoo.project.memo.plan.dto.PlanDraftResponse;
import com.jungwoo.project.memo.plan.selection.PlanSelectionFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * 성공 시나리오(사진 없는 버전, 계획 v3 §5.2) — 실제 DB(memo_test)·실제 빈, 모델만 가짜.
 *
 * <p>상담에서 진도·막힘·해결을 말하면 서버가 종류·단원과 함께 기억하고, <b>새 대화</b>의 상담 상태 블록과 계획 생성 입력에
 * 교재·진도·도움받아 해결한 단원이 실린다("다시 묻지 않는다" 규칙과 함께). 추정·늦은 옛 턴·동시 저장이 확인된 진도를 흐리지
 * 않고, 지운 것은 다시 저장되지 않으며, 기억이 바뀌면(추정 확인 포함) 초안은 새로고침해도 오래됨으로 보인다.
 *
 * <p>합성 사용자·합성 과목만 쓴다(실 사용자 데이터 없음). 스키마가 레포에 없어 CI에서는 -PexcludeDbTests로 제외된다.
 */
@TestPropertySource(properties = "plan.draft.generator=AI")
@SpringBootTest
class StudyMemoryFlowDbTest {

    private static final String PREFIX = "SMF-";

    @MockitoBean
    private AiConsultationClient aiConsultationClient;
    @Autowired
    private UserContextService userContextService;
    @Autowired
    private ProjectStateService projectStateService;
    @Autowired
    private AiWorkspaceContextBuilder workspaceContextBuilder;
    @Autowired
    private PlanDraftService planDraftService;
    @Autowired
    private AiProposalMapper aiProposalMapper;
    @Autowired
    private DataSource dataSource;

    private Long userId;
    private Long courseId;
    private final List<Long> unit = new ArrayList<>(); // unit.get(n) = Unit n의 topic_id (0은 비움)
    private long proposalWatermark;
    private long usageWatermark;
    private final List<String> planPrompts = new ArrayList<>();
    private final List<String> selectionPrompts = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        userId = firstUser();
        proposalWatermark = maxId("ai_proposals", "proposal_id");
        usageWatermark = maxId("ai_usage_logs", "usage_log_id");
        try (Connection c = dataSource.getConnection()) {
            courseId = insert(c, "INSERT INTO courses (user_id, title, textbook_title, textbook_isbn, textbook_publisher, "
                    + "textbook_info_source, status) VALUES (" + userId + ", '" + PREFIX + "영어회화', '합성 회화 교재 1', "
                    + "'9790000000001', '합성출판', 'USER', 'ACTIVE')");
            unit.add(null);
            String[] titles = {"What's your name?", "Where are you from?", "I have to make hotel reservations",
                    "Did you have a good weekend?", "I'm doing my homework right now", "Shopping", "Food", "Travel",
                    "Weather", "What's your name?", "Hobbies", "Review"};
            for (int i = 1; i <= 12; i++) {
                unit.add(insert(c, "INSERT INTO course_topics (user_id, course_id, title, order_index, source_type, "
                        + "source_locator, source_toc_seq, status) VALUES (" + userId + ", " + courseId + ", 'Unit " + i
                        + " " + titles[i - 1].replace("'", "''") + "', " + i + ", 'SOURCE', '교재 p." + (i * 6) + "', "
                        + i + ", 'ACTIVE')"));
            }
        }
        when(aiConsultationClient.isConfigured()).thenReturn(true);
        when(aiConsultationClient.streamTurn(any(), any(), anyInt())).thenAnswer(inv -> {
            String system = inv.getArgument(0);
            String user = inv.getArgument(1);
            if (PlanSelectionFixture.isSelection(system)) {
                synchronized (selectionPrompts) {
                    selectionPrompts.add(user);
                }
                return PlanSelectionFixture.structured(PlanSelectionFixture.emptySelection());
            }
            synchronized (planPrompts) {
                planPrompts.add(user);
            }
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(planJson())))));
        });
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM ai_proposal_items WHERE proposal_id > " + proposalWatermark
                    + " AND proposal_id IN (SELECT proposal_id FROM ai_proposals WHERE user_id = " + userId + ")");
            s.executeUpdate("DELETE FROM ai_proposals WHERE proposal_id > " + proposalWatermark + " AND user_id = " + userId);
            s.executeUpdate("DELETE FROM ai_usage_logs WHERE usage_log_id > " + usageWatermark + " AND user_id = " + userId);
            if (courseId != null) {
                s.executeUpdate("DELETE FROM user_contexts WHERE course_id = " + courseId);
                s.executeUpdate("DELETE FROM course_topics WHERE course_id = " + courseId);
                s.executeUpdate("DELETE FROM courses WHERE course_id = " + courseId);
            }
        }
    }

    private UserContextService.AutoSave fact(String text, ContextEvidenceType type, String quote, FactKind kind,
                                             Long topicId, String help, Long resolves) {
        return new UserContextService.AutoSave(text, type, courseId, null, null, quote, kind, topicId, null, help, resolves);
    }

    private List<UserContextResponse> say(String message, LocalDateTime at, long messageId, UserContextService.AutoSave... ops) {
        return userContextService.autoSave(userId, messageId, message, List.of(ops), 4, at, courseId);
    }

    private ProjectStateService.Fact only(FactKind kind) {
        List<ProjectStateService.Fact> facts = projectStateService.load(userId, courseId).facts().stream()
                .filter(f -> f.kind() == kind && !f.inferred()).toList();
        assertThat(facts).as(kind + " 확인된 값").hasSize(1);
        return facts.get(0);
    }

    @Test
    void 상담에서_말한_진도와_막힘_해결이_새_대화의_상담과_계획_입력에_같은_모양으로_실린다() throws Exception {
        LocalDateTime t0 = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).minusHours(1);

        // 대화 1 — 진도와 막힘(Unit 1과 Unit 10은 제목이 같다 — 번호로 가른다)
        say("수업은 Unit 4까지 나갔고 Unit 3 have to 문장 만들기가 막혀", t0, 9_000_001,
                fact("수업은 Unit 4까지 나갔다", ContextEvidenceType.STATED, "Unit 4까지 나갔고", FactKind.PROGRESS, null, null, null),
                fact("have to 문장을 만들기 어렵다", ContextEvidenceType.STATED, "have to 문장 만들기가 막혀", FactKind.DIFFICULTY,
                        unit.get(3), null, null));
        long stuck = only(FactKind.DIFFICULTY).contextId();
        assertThat(only(FactKind.DIFFICULTY).topicId()).isEqualTo(unit.get(3));

        // 단원을 말하지 않은 해결 답 — 대상 막힘에서 단원을 승계하고 막힘을 닫는다.
        say("설명 보고 이제 만들 수 있어", t0.plusMinutes(2), 9_000_002, fact("설명을 보고 have to 문장을 만들 수 있게 됐다",
                ContextEvidenceType.SELF_REPORT, "설명 보고 이제 만들 수 있어", FactKind.RESOLVED, null, "GUIDED", stuck));
        assertThat(projectStateService.load(userId, courseId).facts())
                .noneMatch(f -> f.kind() == FactKind.DIFFICULTY);
        assertThat(only(FactKind.RESOLVED).topicId()).isEqualTo(unit.get(3));
        assertThat(only(FactKind.RESOLVED).help()).isEqualTo("GUIDED");

        // 추정·가정은 확인된 진도를 흐리지 않는다.
        say("다음 주는 바빠", t0.plusMinutes(3), 9_000_003, fact("수업은 Unit 2까지 나간 것 같다", ContextEvidenceType.INFERRED,
                null, FactKind.PROGRESS, null, null, null));
        say("Unit 6까지 나가면 좋겠다", t0.plusMinutes(4), 9_000_004, fact("수업은 Unit 6까지 나갔다", ContextEvidenceType.STATED,
                "Unit 6까지 나가면 좋겠다", FactKind.PROGRESS, null, null, null));
        assertThat(only(FactKind.PROGRESS).text()).isEqualTo("수업은 Unit 4까지 나갔다");

        // 동시 저장: 같은 초의 두 턴이 겹쳐도 확인된 진도는 하나이고, 메시지 번호가 큰 쪽이 남는다.
        LocalDateTime same = t0.plusMinutes(5);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Object> a = () -> {
                go.await(10, TimeUnit.SECONDS);
                return say("Unit 5까지 나갔어", same, 9_000_005, fact("수업은 Unit 5까지 나갔다", ContextEvidenceType.STATED,
                        "Unit 5까지 나갔어", FactKind.PROGRESS, null, null, null));
            };
            Callable<Object> b = () -> {
                go.await(10, TimeUnit.SECONDS);
                return say("Unit 6까지 나갔어", same, 9_000_006, fact("수업은 Unit 6까지 나갔다", ContextEvidenceType.STATED,
                        "Unit 6까지 나갔어", FactKind.PROGRESS, null, null, null));
            };
            Future<Object> fa = pool.submit(a);
            Future<Object> fb = pool.submit(b);
            go.countDown();
            fa.get(30, TimeUnit.SECONDS);
            fb.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(only(FactKind.PROGRESS).text()).isEqualTo("수업은 Unit 6까지 나갔다");

        // 다른 막힘을 지우면 같은 내용은 다시 저장되지 않는다(이력 전체 — 본문 키).
        say("Unit 4 과거시제 질문이 헷갈려", t0.plusMinutes(6), 9_000_007, fact("과거시제 질문이 헷갈린다",
                ContextEvidenceType.STATED, "과거시제 질문이 헷갈려", FactKind.DIFFICULTY, unit.get(4), null, null));
        long other = only(FactKind.DIFFICULTY).contextId();
        userContextService.withdraw(userId, other);
        assertThat(say("Unit 4 과거시제 질문이 헷갈려", t0.plusMinutes(7), 9_000_008, fact("과거시제 질문이 헷갈린다",
                ContextEvidenceType.STATED, "과거시제 질문이 헷갈려", FactKind.DIFFICULTY, unit.get(4), null, null))).isEmpty();

        // AI 추정 하나(확인 전) — 나중에 확인하면 초안이 오래됨으로 보여야 한다.
        say("말하기 연습이 좋겠다", t0.plusMinutes(8), 9_000_009, fact("발음 연습을 원하는 것 같다", ContextEvidenceType.INFERRED,
                null, FactKind.PREFERENCE, null, null, null));

        // 새 대화(과목 대화) — 상담 상태 블록
        AiConversation fresh = AiConversation.builder().userId(userId).courseId(courseId)
                .scope(AiProposalTargetScope.PLAN).build();
        ProjectStateService.Rendered consult = workspaceContextBuilder.projectStateBlock(fresh, userId, RequestedAction.AUTO);
        assertThat(consult.text())
                .contains("다시 묻지 않는다")
                .contains("교재: 「합성 회화 교재 1」")
                .contains("수업 진도: 수업은 Unit 6까지 나갔다")
                .contains("도움받아 해결 [#" + unit.get(3) + " Unit 3 I have to make hotel reservations (교재 목차 3번째)]")
                .contains("AI 추정(확인 전) 선호")
                .doesNotContain("Unit 2까지").doesNotContain("과거시제 질문이 헷갈린다");

        // 새 대화에서 계획 요청 — 선택 단계와 최종 생성 모두 같은 상태를 받는다.
        LocalDate start = LocalDate.now().plusDays(7);
        PlanDraftResponse draft = planDraftService.createDraft(userId, PlanDraftRequest.builder().startDate(start)
                .endDate(start.plusDays(6)).courseIds(List.of(courseId)).requestKey(PREFIX + "k1").build());
        String plan = planPrompts.get(planPrompts.size() - 1);
        assertThat(plan)
                .contains("[이 프로젝트에서 확인된 상태]")
                .contains("수업 진도: 수업은 Unit 6까지 나갔다")
                .contains("도움받아 해결")
                .contains("questions·missingInformation에 다시 넣지 않는다")
                .contains("같은 교재 목차 안에서 제목이 같아도 번호·순번이 다르면 다른 단원이다");
        assertThat(selectionPrompts).isNotEmpty();
        // 자료 선택 단계도 같은 상태(출처 라벨 포함)와, 제목이 같은 단원을 가르는 목차 순번을 본다.
        assertThat(selectionPrompts.get(selectionPrompts.size() - 1))
                .contains("수업 진도: 수업은 Unit 6까지 나갔다").contains("AI 추정")
                .contains("교재 목차 10번째, 제목만 확인").contains("교재 목차 1번째, 제목만 확인");
        assertThat(aiProposalMapper.findByIdAndUserId(draft.getProposalId(), userId).getPlanRequestJson())
                .contains("\"projectStates\"");
        assertThat(planDraftService.loadDraft(userId, draft.getProposalId()).getFreshness().state()).isEqualTo("CURRENT");

        // 추정을 "맞아요"로 확인(같은 행이 바뀜) — 초안은 오래됨이고, 다시 읽어도 그대로다.
        long inferred = projectStateService.load(userId, courseId).facts().stream()
                .filter(ProjectStateService.Fact::inferred).findFirst().orElseThrow().contextId();
        Thread.sleep(1100); // updated_at이 초 단위다
        userContextService.confirm(userId, inferred);
        PlanDraftResponse.Freshness freshness = planDraftService.loadDraft(userId, draft.getProposalId()).getFreshness();
        assertThat(freshness.state()).isEqualTo("STALE");
        assertThat(planDraftService.loadDraft(userId, draft.getProposalId()).getFreshness().state()).isEqualTo("STALE");
    }

    @Test
    void 사용자가_고친_진도는_늦게_끝난_옛_턴이_되돌리지_못한다() {
        LocalDateTime t0 = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).minusMinutes(30);
        say("Unit 3까지 나갔어", t0, 9_100_001, fact("수업은 Unit 3까지 나갔다", ContextEvidenceType.STATED, "Unit 3까지 나갔어",
                FactKind.PROGRESS, null, null, null));
        userContextService.edit(userId, only(FactKind.PROGRESS).contextId(),
                new UserContextService.Edit("수업은 Unit 5까지 나갔다", null, null, null, null));

        // 고치기 전에 보낸 메시지(시각이 이르다)의 저장이 이제 끝났다.
        say("Unit 4까지 나갔어", t0.plusMinutes(1), 9_100_002, fact("수업은 Unit 4까지 나갔다", ContextEvidenceType.STATED,
                "Unit 4까지 나갔어", FactKind.PROGRESS, null, null, null));

        assertThat(only(FactKind.PROGRESS).text()).isEqualTo("수업은 Unit 5까지 나갔다");
        assertThat(only(FactKind.PROGRESS).sourceType().name()).isEqualTo("USER_EDITED");
    }

    // ===== fixture =====

    private static String planJson() {
        return "초안이에요\n" + AiStreamParser.DELIMITER + "\n"
                + "{\"title\":\"" + PREFIX + "주간\",\"goalSummary\":\"목표\",\"strategy\":{\"goal\":\"목표\"},"
                + "\"items\":[{\"title\":\"" + PREFIX + "have to 말하기\",\"description\":\"말한다 · 완료: 5문장\","
                + "\"actionType\":\"PRACTICE\",\"expectedMinutes\":30,\"priority\":\"SHOULD\",\"courseId\":null,"
                + "\"scheduledDate\":null,\"reason\":\"도움받아 해결한 단원\",\"refIds\":[]}]}";
    }

    private Long firstUser() throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT user_id FROM users ORDER BY user_id LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                throw new IllegalStateException("users 테이블이 비어 있어 테스트할 수 없다");
            }
            return rs.getLong(1);
        }
    }

    private long maxId(String table, String column) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COALESCE(MAX(" + column + "), 0) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static Long insert(Connection c, String sql) throws Exception {
        try (Statement s = c.createStatement()) {
            s.executeUpdate(sql, Statement.RETURN_GENERATED_KEYS);
            try (ResultSet rs = s.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
