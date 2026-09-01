package com.jungwoo.project.memo.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.ai.domain.AiScheduleSuggestion;
import com.jungwoo.project.memo.ai.domain.ScheduleSuggestionKind;
import com.jungwoo.project.memo.ai.domain.ScheduleSuggestionStatus;
import com.jungwoo.project.memo.ai.dto.ScheduleSuggestion;
import com.jungwoo.project.memo.ai.dto.ScheduleSuggestionResponse;
import com.jungwoo.project.memo.commitment.CommitmentService;
import com.jungwoo.project.memo.commitment.domain.CommitmentSourceType;
import com.jungwoo.project.memo.commitment.dto.CommitmentCreateRequest;
import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.common.exception.ServiceUnavailableException;
import com.jungwoo.project.memo.routine.RoutineService;
import com.jungwoo.project.memo.routine.dto.RoutineSaveRequest;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 일정 후보의 저장·적용·거절.
 *
 * <p>여기서 지키는 선 셋:
 * <ol>
 *   <li>후보 저장은 원본을 만들지 않는다 — 승인 전에는 약속도 루틴도 생기지 않는다.
 *   <li>적용은 기존 도메인 경로를 그대로 부른다 — AI 전용 생성 규칙을 만들지 않는다.
 *   <li>결론이 난 후보는 다시 적용되지 않는다 — 같은 약속이 두 개 생기는 경로를 막는다.
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class ScheduleSuggestionServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long CONVERSATION_ID = 10L;
    private static final Long SOURCE_MESSAGE_ID = 100L;
    private static final Long SUGGESTION_ID = 700L;

    private static final String COMMITMENT_JSON = """
            {"title":"친구 약속","startAt":"2026-09-04T19:00","endAt":"2026-09-04T21:00",
             "locationText":"홍대"}""";
    private static final String ROUTINE_JSON = """
            {"courseId":null,"title":"알바","location":null,"daysOfWeek":["THURSDAY"],
             "startTime":"18:00","endTime":"23:00","effectiveFrom":"2026-09-01","effectiveUntil":null}""";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Mock
    private AiScheduleSuggestionMapper suggestionMapper;

    @Mock
    private CommitmentService commitmentService;

    @Mock
    private RoutineService routineService;

    private ScheduleSuggestionService service;

    /*
     * Validator는 목이 아니라 실물이다. 이 테스트가 증명하려는 것이 "DTO의 annotation이
     * 이 경로에서도 실제로 걸리는가"인데, 목으로 바꾸면 그 질문에 답하지 못하고 내가 짠
     * 스텁이 통과하는 것만 확인하게 된다.
     */
    @BeforeEach
    void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        service = new ScheduleSuggestionService(
                suggestionMapper, commitmentService, routineService, factory.getValidator());
    }

    /** apply()가 받는 것은 화면에서 온 값이라 Map이다. HTTP 경계에서 Jackson 3이 그렇게 만든다. */
    private Map<String, Object> map(String raw) {
        return objectMapper.convertValue(json(raw), new TypeReference<Map<String, Object>>() { });
    }

    private JsonNode json(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private AiScheduleSuggestion stored(ScheduleSuggestionKind kind, String payload,
                                        ScheduleSuggestionStatus status) {
        return AiScheduleSuggestion.builder()
                .suggestionId(SUGGESTION_ID)
                .userId(USER_ID)
                .conversationId(CONVERSATION_ID)
                .sourceMessageId(SOURCE_MESSAGE_ID)
                .kind(kind)
                .proposedPayload(payload)
                .status(status)
                .build();
    }

    // ===== 저장 =====

    @Test
    void 후보를_저장해도_약속이나_루틴은_만들어지지_않는다() {
        List<ScheduleSuggestionResponse> result = service.createFromSuggestions(
                USER_ID, CONVERSATION_ID, SOURCE_MESSAGE_ID,
                List.of(new ScheduleSuggestion(ScheduleSuggestionKind.COMMITMENT, json(COMMITMENT_JSON))));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo(ScheduleSuggestionStatus.PROPOSED);
        // 승인 전에 저장되면 사용자는 자기가 만들지 않은 일정 때문에 계획이 비는 이유를 모른다.
        verify(commitmentService, never()).create(anyLong(), any(), any());
        verify(routineService, never()).create(anyLong(), any());
    }

    @Test
    void 한_발언에서_후보가_여럿이면_전부_저장한다() {
        // "금요일엔 친구 만나고 토요일엔 병원 가."
        List<ScheduleSuggestionResponse> result = service.createFromSuggestions(
                USER_ID, CONVERSATION_ID, SOURCE_MESSAGE_ID, List.of(
                        new ScheduleSuggestion(ScheduleSuggestionKind.COMMITMENT, json(COMMITMENT_JSON)),
                        new ScheduleSuggestion(ScheduleSuggestionKind.COMMITMENT, json(
                                "{\"title\":\"병원\",\"startAt\":\"2026-09-05T14:00\",\"endAt\":\"2026-09-05T15:00\"}"))));

        assertThat(result).hasSize(2);
        verify(suggestionMapper, org.mockito.Mockito.times(2)).insert(any());
    }

    @Test
    void 읽을_수_없는_payload는_저장하지_않고_턴을_실패시킨다() {
        // 카드로 띄우면 사용자가 [적용]을 눌러야만 못 쓰는 값이었다는 것을 알게 된다.
        assertThatThrownBy(() -> service.createFromSuggestions(
                USER_ID, CONVERSATION_ID, SOURCE_MESSAGE_ID,
                List.of(new ScheduleSuggestion(ScheduleSuggestionKind.COMMITMENT,
                        json("{\"startAt\":\"어제쯤\"}")))))
                .isInstanceOf(ServiceUnavailableException.class);
        verify(suggestionMapper, never()).insert(any());
    }

    @Test
    void kind가_없으면_계약_위반이다() {
        assertThatThrownBy(() -> service.createFromSuggestions(
                USER_ID, CONVERSATION_ID, SOURCE_MESSAGE_ID,
                List.of(new ScheduleSuggestion(null, json(COMMITMENT_JSON)))))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void 후보가_없으면_아무것도_하지_않는다() {
        assertThat(service.createFromSuggestions(USER_ID, CONVERSATION_ID, SOURCE_MESSAGE_ID, List.of()))
                .isEmpty();
        assertThat(service.createFromSuggestions(USER_ID, CONVERSATION_ID, SOURCE_MESSAGE_ID, null))
                .isEmpty();
        verify(suggestionMapper, never()).insert(any());
    }

    // ===== 적용 =====

    @Test
    void 약속_후보를_적용하면_기존_생성_경로로_AI_출처를_달아_만든다() {
        when(suggestionMapper.findByIdAndUserIdForUpdate(SUGGESTION_ID, USER_ID))
                .thenReturn(stored(ScheduleSuggestionKind.COMMITMENT, COMMITMENT_JSON,
                        ScheduleSuggestionStatus.PROPOSED));
        when(suggestionMapper.resolveIfProposed(anyLong(), anyLong(), any(), any())).thenReturn(1);

        ScheduleSuggestionResponse response = service.apply(SUGGESTION_ID, USER_ID, null);

        ArgumentCaptor<CommitmentCreateRequest> captor =
                ArgumentCaptor.forClass(CommitmentCreateRequest.class);
        verify(commitmentService).create(eq(USER_ID), captor.capture(),
                eq(CommitmentSourceType.AI_SUGGESTION_APPROVED));
        assertThat(captor.getValue().getTitle()).isEqualTo("친구 약속");
        assertThat(captor.getValue().getStartAt()).isEqualTo(LocalDateTime.of(2026, 9, 4, 19, 0));
        assertThat(captor.getValue().getLocationText()).isEqualTo("홍대");
        assertThat(response.getStatus()).isEqualTo(ScheduleSuggestionStatus.APPLIED);
    }

    @Test
    void 반복_후보를_적용하면_기존_RoutineService를_부른다() {
        when(suggestionMapper.findByIdAndUserIdForUpdate(SUGGESTION_ID, USER_ID))
                .thenReturn(stored(ScheduleSuggestionKind.ROUTINE, ROUTINE_JSON,
                        ScheduleSuggestionStatus.PROPOSED));
        when(suggestionMapper.resolveIfProposed(anyLong(), anyLong(), any(), any())).thenReturn(1);

        service.apply(SUGGESTION_ID, USER_ID, null);

        ArgumentCaptor<RoutineSaveRequest> captor = ArgumentCaptor.forClass(RoutineSaveRequest.class);
        // AI 전용 루틴 생성 규칙을 복제하지 않는다 — 검증도 저장도 저쪽이 한다.
        verify(routineService).create(eq(USER_ID), captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("알바");
        assertThat(captor.getValue().getDaysOfWeek()).containsExactly(DayOfWeek.THURSDAY);
        assertThat(captor.getValue().getStartTime()).isEqualTo(LocalTime.of(18, 0));
        assertThat(captor.getValue().getEffectiveFrom()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(captor.getValue().getEffectiveUntil()).isNull();
        verify(commitmentService, never()).create(anyLong(), any(), any());
    }

    @Test
    void 사용자가_고친_값으로_만든다() {
        when(suggestionMapper.findByIdAndUserIdForUpdate(SUGGESTION_ID, USER_ID))
                .thenReturn(stored(ScheduleSuggestionKind.COMMITMENT, COMMITMENT_JSON,
                        ScheduleSuggestionStatus.PROPOSED));
        when(suggestionMapper.resolveIfProposed(anyLong(), anyLong(), any(), any())).thenReturn(1);

        service.apply(SUGGESTION_ID, USER_ID, map(
                "{\"title\":\"친구 약속\",\"startAt\":\"2026-09-04T19:00\",\"endAt\":\"2026-09-04T21:30\"}"));

        ArgumentCaptor<CommitmentCreateRequest> captor =
                ArgumentCaptor.forClass(CommitmentCreateRequest.class);
        verify(commitmentService).create(eq(USER_ID), captor.capture(), any());
        assertThat(captor.getValue().getEndAt()).isEqualTo(LocalDateTime.of(2026, 9, 4, 21, 30));
    }

    @Test
    void 이미_적용한_후보는_다시_적용되지_않는다() {
        when(suggestionMapper.findByIdAndUserIdForUpdate(SUGGESTION_ID, USER_ID))
                .thenReturn(stored(ScheduleSuggestionKind.COMMITMENT, COMMITMENT_JSON,
                        ScheduleSuggestionStatus.APPLIED));

        // 다시 적용되면 같은 약속이 하나 더 생긴다.
        assertThatThrownBy(() -> service.apply(SUGGESTION_ID, USER_ID, null))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SCHEDULE_SUGGESTION_ALREADY_RESOLVED);
        verify(commitmentService, never()).create(anyLong(), any(), any());
    }

    @Test
    void 거절한_후보에는_적용할_수_없다() {
        when(suggestionMapper.findByIdAndUserIdForUpdate(SUGGESTION_ID, USER_ID))
                .thenReturn(stored(ScheduleSuggestionKind.COMMITMENT, COMMITMENT_JSON,
                        ScheduleSuggestionStatus.DISMISSED));

        assertThatThrownBy(() -> service.apply(SUGGESTION_ID, USER_ID, null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 상태_조건_UPDATE가_0행이면_만들었던_것까지_롤백되게_예외를_던진다() {
        when(suggestionMapper.findByIdAndUserIdForUpdate(SUGGESTION_ID, USER_ID))
                .thenReturn(stored(ScheduleSuggestionKind.COMMITMENT, COMMITMENT_JSON,
                        ScheduleSuggestionStatus.PROPOSED));
        when(suggestionMapper.resolveIfProposed(anyLong(), anyLong(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.apply(SUGGESTION_ID, USER_ID, null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 남의_후보는_없는_것으로_본다() {
        when(suggestionMapper.findByIdAndUserIdForUpdate(SUGGESTION_ID, 2L)).thenReturn(null);

        assertThatThrownBy(() -> service.apply(SUGGESTION_ID, 2L, null))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SCHEDULE_SUGGESTION_NOT_FOUND);
    }

    // ===== DTO 계약 검증(Bean Validation) =====
    //
    // 직접 생성 경로는 Spring MVC가 @Valid로 이것을 대신 해주지만, 이 경로는 JSON을
    // ObjectMapper로 읽어 서비스를 바로 부르므로 아무도 돌려주지 않는다. 역직렬화 성공과
    // Bean Validation 성공은 다른 것이다 — title이 없는 JSON도 객체로는 멀쩡히 만들어지고,
    // 그 객체는 도메인 서비스의 getTitle().trim()에서 NPE가 된다.
    //
    // 모델이 낸 값의 위반은 계약 위반(503)이고, 사용자가 카드에서 고친 값의 위반은
    // 입력 오류(400)다. 같은 검증이지만 누구의 잘못인지가 다르다.

    private void modelCandidateRejected(ScheduleSuggestionKind kind, String payloadJson) {
        assertThatThrownBy(() -> service.createFromSuggestions(
                USER_ID, CONVERSATION_ID, SOURCE_MESSAGE_ID,
                List.of(new ScheduleSuggestion(kind, json(payloadJson)))))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.AI_GENERATION_FAILED);
        // PROPOSED 행이 남으면 사용자는 못 쓰는 카드를 보게 된다.
        verify(suggestionMapper, never()).insert(any());
        verify(commitmentService, never()).create(anyLong(), any(), any());
        verify(routineService, never()).create(anyLong(), any());
    }

    @Test
    void 약속_후보에_제목이_없으면_저장하지_않는다() {
        modelCandidateRejected(ScheduleSuggestionKind.COMMITMENT,
                "{\"startAt\":\"2026-09-04T19:00\",\"endAt\":\"2026-09-04T21:00\"}");
    }

    @Test
    void 약속_후보의_제목이_공백뿐이면_저장하지_않는다() {
        modelCandidateRejected(ScheduleSuggestionKind.COMMITMENT,
                "{\"title\":\"   \",\"startAt\":\"2026-09-04T19:00\",\"endAt\":\"2026-09-04T21:00\"}");
    }

    @Test
    void 약속_후보의_제목이_200자를_넘으면_저장하지_않는다() {
        String tooLong = "가".repeat(201);
        modelCandidateRejected(ScheduleSuggestionKind.COMMITMENT,
                "{\"title\":\"" + tooLong + "\",\"startAt\":\"2026-09-04T19:00\","
                        + "\"endAt\":\"2026-09-04T21:00\"}");
    }

    @Test
    void 약속_후보에_시작_시각이_없으면_저장하지_않는다() {
        modelCandidateRejected(ScheduleSuggestionKind.COMMITMENT,
                "{\"title\":\"친구 약속\",\"endAt\":\"2026-09-04T21:00\"}");
    }

    @Test
    void 약속_후보에_종료_시각이_없으면_저장하지_않는다() {
        modelCandidateRejected(ScheduleSuggestionKind.COMMITMENT,
                "{\"title\":\"친구 약속\",\"startAt\":\"2026-09-04T19:00\"}");
    }

    @Test
    void 반복_후보에_제목이_없거나_공백이면_저장하지_않는다() {
        modelCandidateRejected(ScheduleSuggestionKind.ROUTINE,
                "{\"daysOfWeek\":[\"THURSDAY\"],\"startTime\":\"18:00\",\"endTime\":\"23:00\","
                        + "\"effectiveFrom\":\"2026-09-01\"}");
        modelCandidateRejected(ScheduleSuggestionKind.ROUTINE,
                "{\"title\":\" \",\"daysOfWeek\":[\"THURSDAY\"],\"startTime\":\"18:00\","
                        + "\"endTime\":\"23:00\",\"effectiveFrom\":\"2026-09-01\"}");
    }

    @Test
    void 반복_후보에_시작이나_종료_시각이_없으면_저장하지_않는다() {
        modelCandidateRejected(ScheduleSuggestionKind.ROUTINE,
                "{\"title\":\"알바\",\"daysOfWeek\":[\"THURSDAY\"],\"endTime\":\"23:00\","
                        + "\"effectiveFrom\":\"2026-09-01\"}");
        modelCandidateRejected(ScheduleSuggestionKind.ROUTINE,
                "{\"title\":\"알바\",\"daysOfWeek\":[\"THURSDAY\"],\"startTime\":\"18:00\","
                        + "\"effectiveFrom\":\"2026-09-01\"}");
    }

    @Test
    void 반복_후보에_적용_시작일이_없으면_저장하지_않는다() {
        modelCandidateRejected(ScheduleSuggestionKind.ROUTINE,
                "{\"title\":\"알바\",\"daysOfWeek\":[\"THURSDAY\"],\"startTime\":\"18:00\","
                        + "\"endTime\":\"23:00\"}");
    }

    // ===== 사용자가 고친 값 =====

    private void editedPayloadRejected(ScheduleSuggestionKind kind, String storedJson, String editedJson) {
        when(suggestionMapper.findByIdAndUserIdForUpdate(SUGGESTION_ID, USER_ID))
                .thenReturn(stored(kind, storedJson, ScheduleSuggestionStatus.PROPOSED));

        assertThatThrownBy(() -> service.apply(SUGGESTION_ID, USER_ID, map(editedJson)))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_VALUE);

        verify(commitmentService, never()).create(anyLong(), any(), any());
        verify(routineService, never()).create(anyLong(), any());
        // 후보는 PROPOSED로 남는다 — 사용자가 값을 고쳐 다시 시도할 수 있어야 한다.
        verify(suggestionMapper, never()).resolveIfProposed(anyLong(), anyLong(), any(), any());
    }

    @Test
    void 사용자가_제목을_비우고_적용하면_400이고_후보는_남는다() {
        editedPayloadRejected(ScheduleSuggestionKind.COMMITMENT, COMMITMENT_JSON,
                "{\"title\":\"\",\"startAt\":\"2026-09-04T19:00\",\"endAt\":\"2026-09-04T21:00\"}");
    }

    @Test
    void 사용자가_반복_적용_시작일을_지우고_적용하면_400이고_후보는_남는다() {
        editedPayloadRejected(ScheduleSuggestionKind.ROUTINE, ROUTINE_JSON,
                "{\"title\":\"알바\",\"daysOfWeek\":[\"THURSDAY\"],\"startTime\":\"18:00\","
                        + "\"endTime\":\"23:00\"}");
    }

    // ===== 두 층이 각자 자기 것을 본다 =====

    @Test
    void DTO_검증을_통과해도_도메인_규칙은_도메인_서비스가_본다() {
        // startAt < endAt은 @NotNull로 표현할 수 없다 — CommitmentService가 본다.
        when(suggestionMapper.findByIdAndUserIdForUpdate(SUGGESTION_ID, USER_ID))
                .thenReturn(stored(ScheduleSuggestionKind.COMMITMENT, COMMITMENT_JSON,
                        ScheduleSuggestionStatus.PROPOSED));
        org.mockito.Mockito.doThrow(new BadRequestException(ErrorCode.INVALID_TIME_RANGE))
                .when(commitmentService).create(anyLong(), any(), any());

        assertThatThrownBy(() -> service.apply(SUGGESTION_ID, USER_ID, map(
                "{\"title\":\"친구 약속\",\"startAt\":\"2026-09-04T21:00\",\"endAt\":\"2026-09-04T19:00\"}")))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_TIME_RANGE);
        // 도메인이 거절했으므로 후보도 그대로 남는다.
        verify(suggestionMapper, never()).resolveIfProposed(anyLong(), anyLong(), any(), any());
    }

    @Test
    void 요일이_비어도_DTO는_통과하고_RoutineService가_거절한다() {
        /*
         * RoutineSaveRequest.daysOfWeek에는 annotation이 없다(RoutineService가 빈 목록을
         * 거절한다). 두 층을 합치면 이런 규칙이 갈 곳이 없어진다 — 그래서 합치지 않았다.
         */
        when(suggestionMapper.findByIdAndUserIdForUpdate(SUGGESTION_ID, USER_ID))
                .thenReturn(stored(ScheduleSuggestionKind.ROUTINE, ROUTINE_JSON,
                        ScheduleSuggestionStatus.PROPOSED));
        org.mockito.Mockito.doThrow(new BadRequestException(ErrorCode.ROUTINE_WEEKDAYS_REQUIRED))
                .when(routineService).create(anyLong(), any());

        assertThatThrownBy(() -> service.apply(SUGGESTION_ID, USER_ID, map(
                "{\"title\":\"알바\",\"daysOfWeek\":[],\"startTime\":\"18:00\",\"endTime\":\"23:00\","
                        + "\"effectiveFrom\":\"2026-09-01\"}")))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ROUTINE_WEEKDAYS_REQUIRED);
        // DTO 검증에서 걸린 것이 아니라 도메인까지 갔다는 뜻이다.
        verify(routineService).create(anyLong(), any());
    }

    // ===== 거절 =====

    @Test
    void 거절하면_도메인_행을_만들지_않는다() {
        when(suggestionMapper.findByIdAndUserIdForUpdate(SUGGESTION_ID, USER_ID))
                .thenReturn(stored(ScheduleSuggestionKind.COMMITMENT, COMMITMENT_JSON,
                        ScheduleSuggestionStatus.PROPOSED));
        when(suggestionMapper.resolveIfProposed(anyLong(), anyLong(), any(), any())).thenReturn(1);

        ScheduleSuggestionResponse response = service.dismiss(SUGGESTION_ID, USER_ID);

        assertThat(response.getStatus()).isEqualTo(ScheduleSuggestionStatus.DISMISSED);
        verify(commitmentService, never()).create(anyLong(), any(), any());
        verify(routineService, never()).create(anyLong(), any());
    }

    @Test
    void 이미_결론이_난_후보는_다시_거절되지_않는다() {
        when(suggestionMapper.findByIdAndUserIdForUpdate(SUGGESTION_ID, USER_ID))
                .thenReturn(stored(ScheduleSuggestionKind.ROUTINE, ROUTINE_JSON,
                        ScheduleSuggestionStatus.APPLIED));

        assertThatThrownBy(() -> service.dismiss(SUGGESTION_ID, USER_ID))
                .isInstanceOf(ConflictException.class);
    }

    // ===== 복원 =====

    @Test
    void 대화_재진입_시_미처리_후보를_payload까지_복원한다() {
        when(suggestionMapper.findPendingByConversationIdAndUserId(CONVERSATION_ID, USER_ID))
                .thenReturn(List.of(stored(ScheduleSuggestionKind.COMMITMENT, COMMITMENT_JSON,
                        ScheduleSuggestionStatus.PROPOSED)));

        List<ScheduleSuggestionResponse> pending =
                service.listPendingByConversation(CONVERSATION_ID, USER_ID);

        assertThat(pending).hasSize(1);
        // 화면이 다시 파싱하지 않도록 payload를 객체로 내보낸다.
        assertThat(pending.get(0).getPayload()).containsEntry("title", "친구 약속");
    }
}
