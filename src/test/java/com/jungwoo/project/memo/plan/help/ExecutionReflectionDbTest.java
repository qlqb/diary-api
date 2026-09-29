package com.jungwoo.project.memo.plan.help;

import com.jungwoo.project.memo.execution.ExecutionItemService;
import com.jungwoo.project.memo.execution.domain.ExecutionPriority;
import com.jungwoo.project.memo.execution.dto.ExecutionItemCompleteRequest;
import com.jungwoo.project.memo.execution.dto.ExecutionItemCreateRequest;
import com.jungwoo.project.memo.execution.dto.ExecutionItemPartialRequest;
import com.jungwoo.project.memo.execution.dto.ExecutionItemResponse;
import com.jungwoo.project.memo.execution.dto.ExecutionRecordReflectionRequest;
import com.jungwoo.project.memo.execution.dto.ExecutionRecordResponse;
import com.jungwoo.project.memo.plan.evidence.ExecutionEvidence;
import com.jungwoo.project.memo.plan.evidence.ExecutionEvidenceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 수행 결과 기록 → 같은 활동의 작업 공간 → 다음 계획·상담이 읽는 문장.
 *
 * <p>고정하는 것:
 * <ul>
 *   <li>"어떻게 했나"(혼자/도움·막힌 단계)는 선택이다. 쓰지 않아도 완료되고, 모르는 값은 저장하지 않는다.</li>
 *   <li>부분 수행으로 남은 조각의 작업 공간은 앞 조각의 기록을 함께 보인다(같은 활동).</li>
 *   <li>직접 만든 항목은 안내가 "없다"고 말한다(NO_ORIGIN) — 지어내지 않는다.</li>
 *   <li>다음 계획·상담의 기록 문장에 "사용자 진술"로 실린다. 남기지 않았으면 아무것도 실리지 않는다.</li>
 *   <li>사용자는 자기 기록의 "어떻게 했나"를 고칠 수 있고, 결과(완료·부분)는 그대로다.</li>
 * </ul>
 *
 * <p>로컬 memo_test DB 필요. CI 제외.
 */
@SpringBootTest
class ExecutionReflectionDbTest {

    private static final long USER = 999_000_801L;

    @Autowired
    private ExecutionItemService executionItemService;
    @Autowired
    private ExecutionWorkspaceService workspaceService;
    @Autowired
    private ExecutionEvidenceService evidenceService;
    @Autowired
    private DataSource dataSource;

    private long courseId;
    private final LocalDate today = LocalDate.now();

    @BeforeEach
    void setUp() throws Exception {
        cleanUp();
        // execution_items는 users에 외래키가 있다. 이 테스트만의 합성 사용자(로그인 불가)를 둔다.
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO users (user_id, email, password_hash, nickname, role, status) "
                        + "VALUES (?, 'reflection-test@memo-test.invalid', '!no-login', '기록 테스트', 'USER', 'ACTIVE')")) {
            ps.setLong(1, USER);
            ps.executeUpdate();
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO courses (user_id, title, status) VALUES (?, '자료구조', 'ACTIVE')",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, USER);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                courseId = rs.getLong(1);
            }
        }
    }

    private ExecutionItemResponse create(String title) {
        return executionItemService.create(USER, ExecutionItemCreateRequest.builder()
                .courseId(courseId).title(title).description("조건을 바꿔 실행하고 직접 작성 · 완료: 3개 작성")
                .scheduledDate(today).expectedMinutes(40).priority(ExecutionPriority.SHOULD).build());
    }

    @Test
    void 도움받아_일부_수행하고_막힌_단계를_남기면_남은_조각의_작업공간과_다음_계획_문장에_그_범위로_실린다() {
        ExecutionItemResponse item = create("연결 리스트 · 삽입 함수 작성");

        ExecutionItemPartialRequest partial = new ExecutionItemPartialRequest();
        partial.setVersion(item.getVersion());
        partial.setCompletionPercent(50);
        partial.setActualMinutes(30);
        partial.setSupportLevel("guided");
        partial.setStuckStep("  빈 리스트에 첫 노드를 넣는 부분  ");
        executionItemService.recordPartial(item.getExecutionItemId(), USER, partial);

        Long remainderId = count("SELECT remaining_execution_item_id FROM execution_records WHERE execution_item_id = "
                + item.getExecutionItemId());
        ExecutionWorkspaceService.ExecutionWorkspaceResponse ws = workspaceService.get(USER, remainderId);

        assertThat(ws.leftoverOfId()).isEqualTo(item.getExecutionItemId());
        assertThat(ws.guidanceState()).as("직접 만든 항목은 안내를 지어내지 않는다").isEqualTo("NO_ORIGIN");
        assertThat(ws.records()).singleElement().satisfies(r -> {
            assertThat(r.getSupportLevel()).isEqualTo("GUIDED");
            assertThat(r.getStuckStep()).isEqualTo("빈 리스트에 첫 노드를 넣는 부분");
            assertThat(r.getOutcome()).isEqualTo("PARTIAL");
        });

        ExecutionEvidence evidence = evidenceService.collect(USER, today.minusDays(1), today.plusDays(1), List.of(courseId));
        String line = evidence.ofCourse(courseId).stream()
                .filter(h -> h.executionItemId().equals(item.getExecutionItemId()))
                .map(ExecutionEvidenceService::describe).findFirst().orElseThrow();
        assertThat(line).contains("사용자 진술: 설명·예제를 보고 수행함(이 활동에 지원이 필요했음)")
                .contains("막힌 단계: \"빈 리스트에 첫 노드를 넣는 부분\"");
    }

    @Test
    void 어떻게_했는지_남기지_않아도_완료되고_모르는_값은_버리며_나중에_고칠_수_있다() {
        ExecutionItemResponse plain = create("스택 · push/pop 추적");
        ExecutionItemCompleteRequest done = new ExecutionItemCompleteRequest();
        done.setVersion(plain.getVersion());
        executionItemService.complete(plain.getExecutionItemId(), USER, done);

        ExecutionItemResponse odd = create("큐 · 원형 큐 구현");
        ExecutionItemCompleteRequest oddDone = new ExecutionItemCompleteRequest();
        oddDone.setVersion(odd.getVersion());
        oddDone.setSupportLevel("MAYBE");
        executionItemService.complete(odd.getExecutionItemId(), USER, oddDone);

        ExecutionRecordResponse plainRecord = workspaceService.get(USER, plain.getExecutionItemId()).records().get(0);
        assertThat(plainRecord.getOutcome()).isEqualTo("COMPLETED");
        assertThat(plainRecord.getSupportLevel()).as("남기지 않음은 null이다 — '혼자 못 함'이 아니다").isNull();
        assertThat(workspaceService.get(USER, odd.getExecutionItemId()).records().get(0).getSupportLevel()).isNull();

        String line = evidenceService.collect(USER, today.minusDays(1), today.plusDays(1), List.of(courseId))
                .ofCourse(courseId).stream().filter(h -> h.executionItemId().equals(plain.getExecutionItemId()))
                .map(ExecutionEvidenceService::describe).findFirst().orElseThrow();
        assertThat(line).doesNotContain("사용자 진술").doesNotContain("막힌 단계");

        // 나중에 "사실 혼자 했어"로 고친다. 결과·분량은 그대로다.
        ExecutionRecordResponse updated = executionItemService.updateReflection(USER, plainRecord.getExecutionRecordId(),
                new ExecutionRecordReflectionRequest("SOLO", null, null, "다시 해 보니 됐다"));
        assertThat(updated.getSupportLevel()).isEqualTo("SOLO");
        assertThat(updated.getNote()).isEqualTo("다시 해 보니 됐다");
        assertThat(updated.getOutcome()).isEqualTo("COMPLETED");
        assertThat(updated.getCompletionPercent()).isEqualTo(100);
    }

    private Long count(String sql) {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM execution_records WHERE user_id = ?")) {
                ps.setLong(1, USER);
                ps.executeUpdate();
            }
            // 남은 조각이 원래 조각을 가리킨다(자기 참조 외래키). 연결을 먼저 끊는다.
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE execution_items SET source_execution_item_id = NULL WHERE user_id = ?")) {
                ps.setLong(1, USER);
                ps.executeUpdate();
            }
            for (String table : List.of("execution_records", "execution_item_events", "execution_items", "courses", "users")) {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + table + " WHERE user_id = ?")) {
                    ps.setLong(1, USER);
                    ps.executeUpdate();
                }
            }
        }
    }
}
