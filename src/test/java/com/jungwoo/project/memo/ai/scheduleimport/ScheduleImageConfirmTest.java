package com.jungwoo.project.memo.ai.scheduleimport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.ai.AiConversationMapper;
import com.jungwoo.project.memo.ai.domain.AiConversation;
import com.jungwoo.project.memo.ai.domain.AiProposalTargetScope;
import com.jungwoo.project.memo.ai.domain.ConversationStatus;
import com.jungwoo.project.memo.ai.domain.ScheduleSuggestionKind;
import com.jungwoo.project.memo.ai.domain.ScheduleSuggestionStatus;
import com.jungwoo.project.memo.ai.ScheduleSuggestionService;
import com.jungwoo.project.memo.ai.dto.ScheduleSuggestionResponse;
import com.jungwoo.project.memo.commitment.CommitmentMapper;
import com.jungwoo.project.memo.commitment.domain.Commitment;
import com.jungwoo.project.memo.commitment.domain.CommitmentSourceType;
import com.jungwoo.project.memo.ai.scheduleimport.dto.RawScheduleTable;
import com.jungwoo.project.memo.ai.scheduleimport.dto.ScheduleImageConfirmRequest;
import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
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
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 확정 경로. 표 원문에서 후보가 만들어지는 지점이고, 여기서 틀리면 남의 근무나 없는 근무가
 * 사용자의 달력 후보로 올라온다.
 *
 * <p>실제 로컬 MariaDB에 대고 돈다. 메시지 두 줄과 후보들이 <b>한 트랜잭션</b>에서 쓰이는지,
 * 같은 키로 다시 불렀을 때 후보가 두 벌 생기지 않는지는 목으로 증명할 수 없다.
 */
@SpringBootTest
class ScheduleImageConfirmTest {

    private static final Long TEST_USER_ID = 999_000_003L;
    /** 픽스처의 본인 행. `[17~23,18~23,18~23,17~22,18~23,D/O,D/O]` */
    private static final int MY_ROW = 7;
    private static final LocalDate MONDAY = LocalDate.of(2026, 9, 7);

    @Autowired
    private ScheduleImageImportService service;

    @Autowired
    private ScheduleSuggestionService scheduleSuggestionService;

    @Autowired
    private CommitmentMapper commitmentMapper;

    @Autowired
    private AiConversationMapper aiConversationMapper;

    @Autowired
    private DataSource dataSource;

    private Long conversationId;

    @BeforeEach
    void setUp() {
        AiConversation conversation = AiConversation.builder()
                .userId(TEST_USER_ID)
                .scope(AiProposalTargetScope.TODAY)
                .status(ConversationStatus.ACTIVE)
                .build();
        aiConversationMapper.insert(conversation);
        conversationId = conversation.getConversationId();
    }

    @AfterEach
    void cleanUp() throws Exception {
        if (conversationId == null) {
            return;
        }
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM one_off_commitments WHERE user_id = ?")) {
                ps.setLong(1, TEST_USER_ID);
                ps.executeUpdate();
            }
            for (String sql : List.of(
                    "DELETE FROM ai_schedule_suggestions WHERE conversation_id = ?",
                    "DELETE FROM ai_messages WHERE conversation_id = ?",
                    "DELETE FROM ai_conversations WHERE conversation_id = ?")) {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setLong(1, conversationId);
                    ps.executeUpdate();
                }
            }
        }
    }

    // ===== 셀 -> 후보 =====

    @Test
    @DisplayName("D/O는 후보를 만들지 않는다 — 빈 시간이 곧 휴무다")
    void offDaysProduceNothing() {
        List<ScheduleSuggestionResponse> created = confirm(request(fixture(), MY_ROW));

        assertThat(created).as("근무 5칸 + D/O 2칸").hasSize(5);
        assertThat(created).extracting(ScheduleSuggestionResponse::getKind)
                .containsOnly(ScheduleSuggestionKind.COMMITMENT);
        assertThat(created).extracting(ScheduleSuggestionResponse::getStatus)
                .as("확정은 후보까지다. 약속은 apply가 만든다")
                .containsOnly(ScheduleSuggestionStatus.PROPOSED);
        assertThat(created).extracting(r -> r.getPayload().get("startAt"))
                .containsExactly("2026-09-07T17:00:00", "2026-09-08T18:00:00", "2026-09-09T18:00:00",
                        "2026-09-10T17:00:00", "2026-09-11T18:00:00");
        assertThat(created).extracting(r -> r.getPayload().get("endAt"))
                .containsExactly("2026-09-07T23:00:00", "2026-09-08T23:00:00", "2026-09-09T23:00:00",
                        "2026-09-10T22:00:00", "2026-09-11T23:00:00");
    }

    @Test
    @DisplayName("자정을 넘는 근무는 종료가 다음 날이다 — 한 날짜에 넣으면 역전된 약속이 된다")
    void nightShiftEndsOnTheNextDay() {
        // 픽스처의 CL 행: [D/O, D/O, CL, CL, CL, CL, CL], 범례 CL=15~00
        List<ScheduleSuggestionResponse> created = confirm(request(fixture(), 1));

        assertThat(created).hasSize(5);
        assertThat(created.get(0).getPayload().get("startAt")).isEqualTo("2026-09-09T15:00:00");
        assertThat(created.get(0).getPayload().get("endAt")).isEqualTo("2026-09-10T00:00:00");
        assertThat(created.get(0).getPayload().get("title"))
                .as("범례 코드는 제목에 남는다 — 어느 칸에서 왔는지 사용자가 알아볼 수 있게")
                .isEqualTo("근무 (CL)");
    }

    @Test
    @DisplayName("직접 시간이 적힌 칸에는 코드가 붙지 않는다")
    void directTimesCarryNoCode() {
        assertThat(confirm(request(fixture(), MY_ROW)))
                .extracting(r -> r.getPayload().get("title"))
                .containsOnly("근무");
    }

    // ===== 서버가 다시 판단한다 =====

    @Test
    @DisplayName("사용자가 고른 시작일이 표의 날짜를 이긴다")
    void confirmedStartDateWins() {
        ScheduleImageConfirmRequest request = request(fixture(), MY_ROW);
        request.setPeriodStartDate(LocalDate.of(2026, 9, 14));   // 표에는 9/7로 적혀 있다

        assertThat(confirm(request)).extracting(r -> r.getPayload().get("startAt"))
                .first().asString().startsWith("2026-09-14");
    }

    @Test
    @DisplayName("머리글에 이름·세부가 섞인 표도 확정된다 — 보정이 확정 경로에도 걸린다")
    void normalizationAppliesToConfirmToo() {
        assertThat(fixture().safeScheduleColumns()).as("9열짜리 픽스처를 쓰고 있다").hasSize(9);
        assertThat(confirm(request(fixture(), MY_ROW))).hasSize(5);
    }

    // ===== 모르는 코드 =====

    @Test
    @DisplayName("고른 행에 모르는 코드가 남아 있으면 만들지 않는다")
    void unresolvedCodeInMyRowBlocks() {
        RawScheduleTable raw = table(row("본인", "A", "18~23", "D/O", "D/O", "D/O", "D/O", "D/O"));

        assertThatThrownBy(() -> confirm(request(raw, 0)))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNRESOLVED_CELLS_REMAIN);
    }

    @Test
    @DisplayName("남의 행에 있는 모르는 코드는 내 확정을 막지 않는다")
    void unresolvedCodeInAnotherRowDoesNotBlock() {
        RawScheduleTable raw = table(
                row("동료", "A", "A", "A", "A", "A", "A", "A"),
                row("본인", "18~23", "D/O", "D/O", "D/O", "D/O", "D/O", "D/O"));

        assertThat(confirm(request(raw, 1))).hasSize(1);
    }

    @Test
    @DisplayName("사용자가 알려준 시간으로 모르는 코드가 풀린다")
    void legendOverrideResolvesTheCode() {
        RawScheduleTable raw = table(row("본인", "A", "D/O", "D/O", "D/O", "D/O", "D/O", "D/O"));
        ScheduleImageConfirmRequest request = request(raw, 0);
        request.setLegendOverrides(Map.of("A", "10~15"));

        List<ScheduleSuggestionResponse> created = confirm(request);

        assertThat(created).hasSize(1);
        assertThat(created.get(0).getPayload().get("startAt")).isEqualTo("2026-09-07T10:00:00");
        assertThat(created.get(0).getPayload().get("endAt")).isEqualTo("2026-09-07T15:00:00");
        assertThat(created.get(0).getPayload().get("title")).isEqualTo("근무 (A)");
    }

    // ===== 행 선택 =====

    @Test
    @DisplayName("표에 없는 행 번호는 거절한다 — 남의 근무가 들어오는 자리다")
    void rowIndexOutOfRange() {
        assertThatThrownBy(() -> confirm(request(fixture(), 99)))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SCHEDULE_IMPORT_ROW_REQUIRED);
        assertThatThrownBy(() -> confirm(request(fixture(), -1)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("셀 수가 열 수와 다른 행은 확정할 수 없다")
    void brokenRowCannotBeConfirmed() {
        RawScheduleTable raw = table(new RawScheduleTable.Row("본인", "PT", List.of("18~23", "D/O")));

        assertThatThrownBy(() -> confirm(request(raw, 0)))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SCHEDULE_IMPORT_ROW_REQUIRED);
    }

    @Test
    @DisplayName("전부 휴무인 주는 후보 0건이다 — 오류가 아니다")
    void allOffIsNotAnError() {
        RawScheduleTable raw = table(row("본인", "D/O", "D/O", "D/O", "D/O", "D/O", "D/O", "D/O"));

        assertThat(confirm(request(raw, 0))).isEmpty();
    }

    // ===== 중복 요청 =====

    @Test
    @DisplayName("같은 키로 다시 부르면 그때 만든 후보를 그대로 돌려준다 — 두 벌 만들지 않는다")
    void sameIdempotencyKeyReplays() {
        ScheduleImageConfirmRequest request = request(fixture(), MY_ROW);

        List<ScheduleSuggestionResponse> first = confirm(request);
        List<ScheduleSuggestionResponse> again = confirm(request);

        assertThat(first).hasSize(5);
        assertThat(again).extracting(ScheduleSuggestionResponse::getSuggestionId)
                .containsExactlyElementsOf(first.stream().map(ScheduleSuggestionResponse::getSuggestionId).toList());
        assertThat(countSuggestions()).as("DB에도 5건뿐이다").isEqualTo(5);
    }

    @Test
    @DisplayName("다른 키면 새로 만든다")
    void differentKeyCreatesAgain() {
        confirm(request(fixture(), MY_ROW));
        confirm(request(fixture(), MY_ROW));

        assertThat(countSuggestions()).isEqualTo(10);
    }

    // ===== 적용까지 =====

    @Test
    @DisplayName("확정한 후보가 그대로 약속이 된다 — payload가 CommitmentCreateRequest 모양이라는 증거")
    void confirmedSuggestionApplies() {
        List<ScheduleSuggestionResponse> created = confirm(request(fixture(), MY_ROW));

        /*
         * 이 왕복이 계약의 증거다. confirm이 쓴 JSON을 apply가 CommitmentCreateRequest로
         * 다시 읽어 기존 생성 경로에 넘긴다. payload 모양이 조금이라도 다르면 여기서 깨진다 —
         * 실제로 날짜를 배열로 쓰고 있었고, 그때는 화면 카드에도 배열이 떴다.
         */
        for (ScheduleSuggestionResponse suggestion : created) {
            scheduleSuggestionService.apply(suggestion.getSuggestionId(), TEST_USER_ID, null);
        }

        List<Commitment> commitments = commitmentMapper.findOverlapping(
                TEST_USER_ID, MONDAY.atStartOfDay(), MONDAY.plusDays(7).atStartOfDay());

        assertThat(commitments).hasSize(5);
        assertThat(commitments).extracting(Commitment::getStartAt)
                .containsExactlyInAnyOrder(
                        LocalDateTime.of(2026, 9, 7, 17, 0), LocalDateTime.of(2026, 9, 8, 18, 0),
                        LocalDateTime.of(2026, 9, 9, 18, 0), LocalDateTime.of(2026, 9, 10, 17, 0),
                        LocalDateTime.of(2026, 9, 11, 18, 0));
        assertThat(commitments).extracting(Commitment::getSourceType)
                .as("이미지에서 온 것도 AI 제안 승인이다 — 사용자가 직접 적은 것과 구분된다")
                .containsOnly(CommitmentSourceType.AI_SUGGESTION_APPROVED);
    }

    @Test
    @DisplayName("자정을 넘는 근무도 약속이 된다 — 종료가 시작보다 이르면 도메인이 거절한다")
    void nightShiftApplies() {
        List<ScheduleSuggestionResponse> created = confirm(request(fixture(), 1));

        for (ScheduleSuggestionResponse suggestion : created) {
            scheduleSuggestionService.apply(suggestion.getSuggestionId(), TEST_USER_ID, null);
        }

        List<Commitment> commitments = commitmentMapper.findOverlapping(
                TEST_USER_ID, MONDAY.atStartOfDay(), MONDAY.plusDays(8).atStartOfDay());
        assertThat(commitments).hasSize(5);
        assertThat(commitments).allSatisfy(c ->
                assertThat(c.getEndAt()).isEqualTo(c.getStartAt().plusHours(9)));
    }

    // ===== 소유권 =====

    @Test
    @DisplayName("남의 대화에는 넣을 수 없다")
    void otherUsersConversationIsRejected() {
        assertThatThrownBy(() -> service.confirm(
                TEST_USER_ID + 1, conversationId, request(fixture(), MY_ROW)))
                .isInstanceOf(NotFoundException.class);
    }

    // ===== fixture =====

    private List<ScheduleSuggestionResponse> confirm(ScheduleImageConfirmRequest request) {
        return service.confirm(TEST_USER_ID, conversationId, request);
    }

    private ScheduleImageConfirmRequest request(RawScheduleTable raw, int rowIndex) {
        return ScheduleImageConfirmRequest.builder()
                .idempotencyKey(UUID.randomUUID().toString())
                .raw(raw)
                .rowIndex(rowIndex)
                .periodStartDate(MONDAY)
                .title("근무")
                .build();
    }

    /** 실제로 모델이 낸 표(9열 머리글). 사람 이름만 별칭이다. */
    private static RawScheduleTable fixture() {
        try (InputStream in = ScheduleImageConfirmTest.class
                .getResourceAsStream("/schedule-import-raw/run1-9columns.json")) {
            return new ObjectMapper().findAndRegisterModules().readValue(in, RawScheduleTable.class);
        } catch (Exception e) {
            throw new IllegalStateException("픽스처를 읽지 못했습니다", e);
        }
    }

    private static RawScheduleTable table(RawScheduleTable.Row... rows) {
        return new RawScheduleTable(true, "근무표",
                new RawScheduleTable.Period(9, 7, 9, 13, null),
                Map.of("OP", "14~23", "CL", "15~00"),
                List.of("월", "화", "수", "목", "금", "토", "일"),
                List.of(rows));
    }

    private static RawScheduleTable.Row row(String name, String... cells) {
        return new RawScheduleTable.Row(name, "PT", Arrays.asList(cells));
    }

    private int countSuggestions() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM ai_schedule_suggestions WHERE conversation_id = ?")) {
            ps.setLong(1, conversationId);
            var rs = ps.executeQuery();
            rs.next();
            return rs.getInt(1);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
