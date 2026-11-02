package com.jungwoo.project.memo.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.ai.domain.AiConversation;
import com.jungwoo.project.memo.ai.domain.AiProposalTargetScope;
import com.jungwoo.project.memo.ai.domain.ConversationStatus;
import com.jungwoo.project.memo.ai.domain.ScheduleSuggestionStatus;
import com.jungwoo.project.memo.ai.dto.ScheduleSuggestionResponse;
import com.jungwoo.project.memo.ai.scheduleimport.ScheduleImageImportService;
import com.jungwoo.project.memo.ai.scheduleimport.dto.RawScheduleTable;
import com.jungwoo.project.memo.ai.scheduleimport.dto.ScheduleImageConfirmRequest;
import com.jungwoo.project.memo.commitment.CommitmentMapper;
import com.jungwoo.project.memo.commitment.domain.Commitment;
import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [모두 적용]. 근무표 한 장이 후보 여러 개를 만들고, 사용자는 그것을 하나씩 누르지 않는다.
 *
 * <p>여기서 증명할 것은 "전부 되거나 전부 안 된다"는 것 하나다. 중간에 3개만 들어간 상태가
 * 되면 사용자는 카드만 보고 어느 것이 들어갔는지 알 수 없다. 트랜잭션 경계를 확인하는
 * 테스트라 목으로는 할 수 없고 실제 DB에 대고 돈다.
 */
@SpringBootTest
class ScheduleSuggestionApplyBatchTest {

    private static final Long TEST_USER_ID = 999_000_004L;
    private static final Long OTHER_USER_ID = 999_000_005L;
    private static final int MY_ROW = 7;
    private static final LocalDate MONDAY = LocalDate.of(2026, 9, 7);

    @Autowired
    private ScheduleSuggestionService service;

    @Autowired
    private ScheduleImageImportService importService;

    @Autowired
    private AiConversationMapper aiConversationMapper;

    @Autowired
    private AiScheduleSuggestionMapper suggestionMapper;

    @Autowired
    private CommitmentMapper commitmentMapper;

    @Autowired
    private DataSource dataSource;

    private final List<Long> conversationIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        conversationIds.clear();
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            for (Long id : conversationIds) {
                for (String sql : List.of(
                        "DELETE FROM ai_schedule_suggestions WHERE conversation_id = ?",
                        "DELETE FROM ai_messages WHERE conversation_id = ?",
                        "DELETE FROM ai_conversations WHERE conversation_id = ?")) {
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setLong(1, id);
                        ps.executeUpdate();
                    }
                }
            }
            for (Long userId : List.of(TEST_USER_ID, OTHER_USER_ID)) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM one_off_commitments WHERE user_id = ?")) {
                    ps.setLong(1, userId);
                    ps.executeUpdate();
                }
            }
        }
    }

    @Test
    @DisplayName("한 번에 전부 적용된다")
    void appliesEveryone() {
        List<Long> ids = ids(seed(TEST_USER_ID));

        List<ScheduleSuggestionResponse> applied = service.applyBatch(TEST_USER_ID, ids);

        assertThat(applied).hasSize(5);
        assertThat(applied).extracting(ScheduleSuggestionResponse::getStatus)
                .containsOnly(ScheduleSuggestionStatus.APPLIED);
        assertThat(commitments(TEST_USER_ID)).hasSize(5);
    }

    @Test
    @DisplayName("잠금은 suggestionId 오름차순이다 — 겹치는 요청이 서로 다른 순서로 잡으면 교착이 난다")
    void locksInAscendingIdOrder() {
        List<Long> ids = ids(seed(TEST_USER_ID));
        List<Long> shuffled = new ArrayList<>(ids);
        java.util.Collections.reverse(shuffled);

        List<ScheduleSuggestionResponse> applied = service.applyBatch(TEST_USER_ID, shuffled);

        assertThat(applied).extracting(ScheduleSuggestionResponse::getSuggestionId)
                .as("요청 순서가 아니라 오름차순으로 처리된다")
                .isSorted()
                .containsExactlyElementsOf(ids.stream().sorted().toList());
    }

    @Test
    @DisplayName("같은 id를 여러 번 보내도 한 번만 적용된다")
    void duplicateIdsAreCollapsed() {
        List<Long> ids = ids(seed(TEST_USER_ID));
        List<Long> withDuplicates = new ArrayList<>(ids);
        withDuplicates.addAll(ids);

        assertThat(service.applyBatch(TEST_USER_ID, withDuplicates)).hasSize(5);
        assertThat(commitments(TEST_USER_ID)).as("약속이 두 벌 생기지 않는다").hasSize(5);
    }

    // ===== 전부 되거나 전부 안 되거나 =====

    @Test
    @DisplayName("하나가 이미 처리됐으면 통째로 거절한다 — 아무것도 만들어지지 않는다")
    void alreadyResolvedRejectsTheWholeBatch() {
        List<Long> ids = ids(seed(TEST_USER_ID));
        service.dismiss(ids.get(4), TEST_USER_ID);

        assertThatThrownBy(() -> service.applyBatch(TEST_USER_ID, ids))
                .isInstanceOf(ConflictException.class);

        assertThat(commitments(TEST_USER_ID)).as("앞 4건도 롤백된다").isEmpty();
        assertThat(statusesOf(ids.subList(0, 4)))
                .containsOnly(ScheduleSuggestionStatus.PROPOSED);
    }

    @Test
    @DisplayName("[모두 적용]을 두 번 누르면 두 번째는 거절된다 — 조용히 건너뛰지 않는다")
    void applyingTwiceIsRejected() {
        List<Long> ids = ids(seed(TEST_USER_ID));
        service.applyBatch(TEST_USER_ID, ids);

        assertThatThrownBy(() -> service.applyBatch(TEST_USER_ID, ids))
                .isInstanceOf(ConflictException.class);

        assertThat(commitments(TEST_USER_ID)).as("약속은 5건 그대로다").hasSize(5);
    }

    @Test
    @DisplayName("남의 후보가 섞여 있으면 통째로 거절한다")
    void anotherUsersSuggestionRejectsTheWholeBatch() {
        List<Long> mine = ids(seed(TEST_USER_ID));
        List<Long> theirs = ids(seed(OTHER_USER_ID));

        List<Long> mixed = new ArrayList<>(mine);
        mixed.add(theirs.get(0));

        assertThatThrownBy(() -> service.applyBatch(TEST_USER_ID, mixed))
                .isInstanceOf(NotFoundException.class);

        assertThat(commitments(TEST_USER_ID)).isEmpty();
        assertThat(commitments(OTHER_USER_ID)).isEmpty();
    }

    // ===== 입력 =====

    @Test
    @DisplayName("빈 목록은 거절한다")
    void emptyListIsRejected() {
        assertThatThrownBy(() -> service.applyBatch(TEST_USER_ID, List.of()))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.applyBatch(TEST_USER_ID, null))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("상한을 넘는 목록은 거절한다 — 그만큼의 행을 한꺼번에 잠그지 않는다")
    void tooManyIsRejected() {
        List<Long> tooMany = new ArrayList<>();
        for (long i = 1; i <= 32; i++) {
            tooMany.add(i);
        }
        assertThatThrownBy(() -> service.applyBatch(TEST_USER_ID, tooMany))
                .isInstanceOf(BadRequestException.class);
    }

    // ===== fixture =====

    /** 실제 경로로 후보를 만든다 — 근무표 이미지 확정이 이 기능의 유일한 대량 생성원이다. */
    private List<ScheduleSuggestionResponse> seed(Long userId) {
        AiConversation conversation = AiConversation.builder()
                .userId(userId)
                .scope(AiProposalTargetScope.TODAY)
                .status(ConversationStatus.ACTIVE)
                .build();
        aiConversationMapper.insert(conversation);
        conversationIds.add(conversation.getConversationId());

        return importService.confirm(userId, conversation.getConversationId(),
                ScheduleImageConfirmRequest.builder()
                        .idempotencyKey(UUID.randomUUID().toString())
                        .raw(fixture())
                        .rowIndex(MY_ROW)
                        .periodStartDate(MONDAY)
                        .title("근무")
                        .build());
    }

    private static List<Long> ids(List<ScheduleSuggestionResponse> created) {
        return created.stream().map(ScheduleSuggestionResponse::getSuggestionId).toList();
    }

    private List<ScheduleSuggestionStatus> statusesOf(List<Long> ids) {
        return ids.stream()
                .map(id -> suggestionMapper.findByIdAndUserIdForUpdate(id, TEST_USER_ID).getStatus())
                .toList();
    }

    private List<Commitment> commitments(Long userId) {
        return commitmentMapper.findOverlapping(
                userId, MONDAY.atStartOfDay(), MONDAY.plusDays(8).atStartOfDay());
    }

    private static RawScheduleTable fixture() {
        try (InputStream in = ScheduleSuggestionApplyBatchTest.class
                .getResourceAsStream("/schedule-import-raw/run1-9columns.json")) {
            return new ObjectMapper().findAndRegisterModules().readValue(in, RawScheduleTable.class);
        } catch (Exception e) {
            throw new IllegalStateException("픽스처를 읽지 못했습니다", e);
        }
    }
}
