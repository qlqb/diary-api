package com.jungwoo.project.memo.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.ai.domain.AiScheduleSuggestion;
import com.jungwoo.project.memo.ai.domain.ScheduleSuggestionKind;
import com.jungwoo.project.memo.ai.domain.ScheduleSuggestionStatus;
import com.jungwoo.project.memo.ai.dto.ScheduleSuggestion;
import com.jungwoo.project.memo.common.config.JacksonConfig;
import com.jungwoo.project.memo.ai.dto.ScheduleSuggestionResponse;
import com.jungwoo.project.memo.commitment.CommitmentService;
import com.jungwoo.project.memo.commitment.domain.CommitmentSourceType;
import com.jungwoo.project.memo.commitment.dto.CommitmentCreateRequest;
import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.common.exception.ServiceUnavailableException;
import com.jungwoo.project.memo.common.exception.ForbiddenException;
import com.jungwoo.project.memo.routine.RoutineService;
import com.jungwoo.project.memo.routine.domain.Routine;
import com.jungwoo.project.memo.routine.dto.LeadMinutesBatchItemRequest;
import com.jungwoo.project.memo.routine.dto.RoutineResponse;
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
    private static final String LEAD_JSON = """
            {"routineIds":[1,2,3],"leadMinutes":60,"targetSummary":"자료구조 외 2개"}""";
    private static final String LEAD_UNDECIDED_JSON = """
            {"routineIds":[1,2,3],"leadMinutes":null,"targetSummary":"자료구조 외 2개"}""";

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
        /*
         * ObjectMapper도 실물이다. JacksonConfig가 만드는 것과 같은 것을 써야 날짜가
         * ISO 문자열로 나가는지를 이 테스트가 실제로 확인한다 — 여기서 따로 만들면
         * 설정이 갈라져도 테스트는 계속 통과한다.
         */
        service = new ScheduleSuggestionService(
                suggestionMapper, commitmentService, routineService, factory.getValidator(),
                new JacksonConfig().objectMapper());
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

    // ===== 계약에 없는 필드 =====

    @Test
    void 모델이_계약에_없는_필드를_얹으면_턴을_실패시킨다() {
        /*
         * 조용히 버리면 안 되는 이유가 이 필드다. repeat이 무시되면 반복이어야 할 것이
         * 일회성 약속 하나가 되어 저장되고, 사용자는 자기가 확인한 적 없는 일정을 보게 된다.
         * 필드가 사라지는 것은 Bean Validation이 보지 못한다 — 남은 필드는 전부 멀쩡하다.
         */
        JsonNode payload = json("""
                {"title":"친구 약속","startAt":"2026-09-04T19:00","endAt":"2026-09-04T21:00",
                 "repeat":"weekly"}""");

        assertThatThrownBy(() -> service.createFromSuggestions(USER_ID, CONVERSATION_ID, SOURCE_MESSAGE_ID,
                List.of(new ScheduleSuggestion(ScheduleSuggestionKind.COMMITMENT, payload))))
                .isInstanceOf(ServiceUnavailableException.class);

        verify(suggestionMapper, never()).insert(any());
    }

    @Test
    void 사용자가_고친_값에_계약에_없는_필드가_있으면_400이다() {
        when(suggestionMapper.findByIdAndUserIdForUpdate(SUGGESTION_ID, USER_ID))
                .thenReturn(stored(ScheduleSuggestionKind.COMMITMENT, COMMITMENT_JSON,
                        ScheduleSuggestionStatus.PROPOSED));

        Map<String, Object> edited = map("""
                {"title":"친구 약속","startAt":"2026-09-04T19:00","endAt":"2026-09-04T21:00",
                 "repeat":"weekly"}""");

        // 모델이 아니라 사용자가 보낸 값이므로 계약 위반(503)이 아니라 입력 오류(400)다.
        assertThatThrownBy(() -> service.apply(SUGGESTION_ID, USER_ID, edited))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_VALUE);

        verify(commitmentService, never()).create(anyLong(), any(), any());
    }

    @Test
    void 저장된_후보를_그대로_적용할_때도_모르는_필드는_거절한다() {
        // 저장 시점에 걸렀으므로 정상적으로는 나오지 않는다. 나왔다면 저장 데이터가 깨진 것이다.
        when(suggestionMapper.findByIdAndUserIdForUpdate(SUGGESTION_ID, USER_ID))
                .thenReturn(stored(ScheduleSuggestionKind.COMMITMENT,
                        """
                        {"title":"친구 약속","startAt":"2026-09-04T19:00",
                         "endAt":"2026-09-04T21:00","repeat":"weekly"}""",
                        ScheduleSuggestionStatus.PROPOSED));

        assertThatThrownBy(() -> service.apply(SUGGESTION_ID, USER_ID, null))
                .isInstanceOf(ServiceUnavailableException.class);

        verify(commitmentService, never()).create(anyLong(), any(), any());
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

    // ===== ROUTINE_LEAD: 의도 → 서버 해석 → 후보 =====

    private Routine classRoutine(Long routineId, String title) {
        return Routine.builder().routineId(routineId).userId(USER_ID).courseId(100L + routineId).title(title).build();
    }

    private RoutineResponse savedLead(Long routineId, Long courseId, int leadMinutes) {
        return new RoutineResponse(routineId, courseId, "수업 " + routineId, null, java.util.Set.of(DayOfWeek.TUESDAY),
                LocalTime.of(14, 0), LocalTime.of(17, 0), leadMinutes, LocalDate.of(2026, 8, 25), null,
                false, false, false, List.of());
    }

    @Test
    void 이동시간_후보는_힌트를_서버가_해석해_대상_id와_요약을_저장한다() {
        when(routineService.resolveLeadTargets(USER_ID, null, "수업"))
                .thenReturn(List.of(classRoutine(1L, "자료구조"), classRoutine(2L, "웹서버"), classRoutine(3L, "센서")));

        ScheduleSuggestionService.Created created = service.createFromModelSuggestions(
                USER_ID, CONVERSATION_ID, SOURCE_MESSAGE_ID, List.of(new ScheduleSuggestion(
                        ScheduleSuggestionKind.ROUTINE_LEAD,
                        json("{\"targetRoutineIds\":null,\"targetHint\":\"수업\",\"leadMinutes\":60}"))));

        assertThat(created.unresolvedLeadTargets()).isZero();
        assertThat(created.saved()).hasSize(1);
        ScheduleSuggestionResponse response = created.saved().get(0);
        assertThat(response.getKind()).isEqualTo(ScheduleSuggestionKind.ROUTINE_LEAD);
        assertThat(response.getPayload()).containsEntry("leadMinutes", 60)
                .containsEntry("targetSummary", "자료구조 외 2개");
        assertThat((List<Object>) response.getPayload().get("routineIds")).containsExactly(1L, 2L, 3L);
        // 후보일 뿐이다 — 아직 아무 루틴의 이동시간도 바뀌지 않았다.
        verify(routineService, never()).updateLeadMinutes(anyLong(), any());
    }

    @Test
    void 대상을_못_찾으면_후보를_만들지_않고_그_수만_센다() {
        when(routineService.resolveLeadTargets(USER_ID, null, "xyz")).thenReturn(List.of());

        ScheduleSuggestionService.Created created = service.createFromModelSuggestions(
                USER_ID, CONVERSATION_ID, SOURCE_MESSAGE_ID, List.of(new ScheduleSuggestion(
                        ScheduleSuggestionKind.ROUTINE_LEAD,
                        json("{\"targetRoutineIds\":null,\"targetHint\":\"xyz\",\"leadMinutes\":60}"))));

        assertThat(created.saved()).isEmpty();
        assertThat(created.unresolvedLeadTargets()).isEqualTo(1);
        verify(suggestionMapper, never()).insert(any());
    }

    @Test
    void 이동시간_후보의_시간이_범위_밖이면_계약_위반이다() {
        assertThatThrownBy(() -> service.createFromModelSuggestions(
                USER_ID, CONVERSATION_ID, SOURCE_MESSAGE_ID, List.of(new ScheduleSuggestion(
                        ScheduleSuggestionKind.ROUTINE_LEAD,
                        json("{\"targetRoutineIds\":[1],\"targetHint\":null,\"leadMinutes\":481}")))))
                .isInstanceOf(ServiceUnavailableException.class);
        verify(suggestionMapper, never()).insert(any());
    }

    @Test
    void 시간을_말하지_않은_후보는_값이_비어_있는_채로_저장된다() {
        when(routineService.resolveLeadTargets(USER_ID, null, "수업")).thenReturn(List.of(classRoutine(1L, "자료구조")));

        ScheduleSuggestionService.Created created = service.createFromModelSuggestions(
                USER_ID, CONVERSATION_ID, SOURCE_MESSAGE_ID, List.of(new ScheduleSuggestion(
                        ScheduleSuggestionKind.ROUTINE_LEAD,
                        json("{\"targetRoutineIds\":null,\"targetHint\":\"수업\",\"leadMinutes\":null}"))));

        assertThat(created.saved()).hasSize(1);
        assertThat(created.saved().get(0).getPayload().get("leadMinutes")).isNull();
        assertThat(created.saved().get(0).getPayload()).containsEntry("targetSummary", "자료구조");
    }

    // ===== ROUTINE_LEAD: 승인 =====

    @Test
    void 이동시간_후보를_적용하면_루틴마다_같은_값으로_저장하고_확인_문장을_돌려준다() {
        when(suggestionMapper.findByIdAndUserIdForUpdate(SUGGESTION_ID, USER_ID))
                .thenReturn(stored(ScheduleSuggestionKind.ROUTINE_LEAD, LEAD_JSON, ScheduleSuggestionStatus.PROPOSED));
        when(suggestionMapper.resolveIfProposed(anyLong(), anyLong(), any(), any())).thenReturn(1);
        when(routineService.updateLeadMinutes(eq(USER_ID), any()))
                .thenReturn(List.of(savedLead(1L, 101L, 60), savedLead(2L, 102L, 60), savedLead(3L, 103L, 60)));

        ScheduleSuggestionResponse response = service.apply(SUGGESTION_ID, USER_ID, null);

        ArgumentCaptor<List<LeadMinutesBatchItemRequest>> captor = ArgumentCaptor.forClass(List.class);
        verify(routineService).updateLeadMinutes(eq(USER_ID), captor.capture());
        assertThat(captor.getValue()).extracting(LeadMinutesBatchItemRequest::getRoutineId, LeadMinutesBatchItemRequest::getLeadMinutes)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1L, 60),
                        org.assertj.core.groups.Tuple.tuple(2L, 60), org.assertj.core.groups.Tuple.tuple(3L, 60));
        assertThat(response.getStatus()).isEqualTo(ScheduleSuggestionStatus.APPLIED);
        assertThat(response.getSystemNote()).isEqualTo("수업 일정의 이동시간을 60분으로 저장했어요.");
        verify(routineService, never()).create(anyLong(), any());
    }

    @Test
    void 값이_비어_있는_이동시간_후보는_카드에서_고른_값으로만_적용된다() {
        when(suggestionMapper.findByIdAndUserIdForUpdate(SUGGESTION_ID, USER_ID))
                .thenReturn(stored(ScheduleSuggestionKind.ROUTINE_LEAD, LEAD_UNDECIDED_JSON, ScheduleSuggestionStatus.PROPOSED));

        // 고르지 않고 누르면 400이고 아무것도 저장하지 않는다.
        assertThatThrownBy(() -> service.apply(SUGGESTION_ID, USER_ID, null))
                .isInstanceOf(BadRequestException.class);
        verify(routineService, never()).updateLeadMinutes(anyLong(), any());
        verify(suggestionMapper, never()).resolveIfProposed(anyLong(), anyLong(), any(), any());

        // 골라서 보내면 그 값으로 저장한다.
        when(suggestionMapper.resolveIfProposed(anyLong(), anyLong(), any(), any())).thenReturn(1);
        when(routineService.updateLeadMinutes(eq(USER_ID), any()))
                .thenReturn(List.of(savedLead(1L, 101L, 0), savedLead(2L, 102L, 0), savedLead(3L, 103L, 0)));
        ScheduleSuggestionResponse response = service.apply(SUGGESTION_ID, USER_ID,
                map("{\"routineIds\":[1,2,3],\"leadMinutes\":0,\"targetSummary\":\"자료구조 외 2개\"}"));

        assertThat(response.getStatus()).isEqualTo(ScheduleSuggestionStatus.APPLIED);
        assertThat(response.getSystemNote()).isEqualTo("수업 일정의 이동시간을 없음으로 저장했어요.");
    }

    /** 저장이 거부되면(남의 루틴) 예외가 그대로 올라간다 — 완료 문장도, 상태 전이도 없다. */
    @Test
    void 이동시간_저장이_거부되면_완료_문장을_만들지_않는다() {
        when(suggestionMapper.findByIdAndUserIdForUpdate(SUGGESTION_ID, USER_ID))
                .thenReturn(stored(ScheduleSuggestionKind.ROUTINE_LEAD, LEAD_JSON, ScheduleSuggestionStatus.PROPOSED));
        when(routineService.updateLeadMinutes(eq(USER_ID), any())).thenThrow(new ForbiddenException());

        assertThatThrownBy(() -> service.apply(SUGGESTION_ID, USER_ID, null))
                .isInstanceOf(ForbiddenException.class);

        verify(suggestionMapper, never()).resolveIfProposed(anyLong(), anyLong(), any(), any());
    }

    /** 수업이 아닌 대상이 섞이면 "수업 일정" 대신 대상 요약으로 말한다. */
    @Test
    void 수업이_아닌_대상이면_확인_문장은_대상_요약으로_말한다() {
        when(suggestionMapper.findByIdAndUserIdForUpdate(SUGGESTION_ID, USER_ID))
                .thenReturn(stored(ScheduleSuggestionKind.ROUTINE_LEAD,
                        "{\"routineIds\":[5],\"leadMinutes\":30,\"targetSummary\":\"쿠팡 알바\"}",
                        ScheduleSuggestionStatus.PROPOSED));
        when(suggestionMapper.resolveIfProposed(anyLong(), anyLong(), any(), any())).thenReturn(1);
        when(routineService.updateLeadMinutes(eq(USER_ID), any())).thenReturn(List.of(savedLead(5L, null, 30)));

        ScheduleSuggestionResponse response = service.apply(SUGGESTION_ID, USER_ID, null);

        assertThat(response.getSystemNote()).isEqualTo("쿠팡 알바의 이동시간을 30분으로 저장했어요.");
    }
}
