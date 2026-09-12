package com.jungwoo.project.memo.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jungwoo.project.memo.ai.domain.AiScheduleSuggestion;
import com.jungwoo.project.memo.ai.domain.ScheduleSuggestionKind;
import com.jungwoo.project.memo.ai.domain.ScheduleSuggestionStatus;
import com.jungwoo.project.memo.ai.dto.ScheduleSuggestion;
import com.jungwoo.project.memo.ai.dto.ScheduleSuggestionResponse;
import com.jungwoo.project.memo.ai.draft.DraftProposalBuilder;
import com.jungwoo.project.memo.ai.draft.ExistingTravel;
import com.jungwoo.project.memo.commitment.CommitmentService;
import com.jungwoo.project.memo.commitment.domain.CommitmentSourceType;
import com.jungwoo.project.memo.commitment.domain.DerivedTravelRelation;
import com.jungwoo.project.memo.commitment.dto.CommitmentCreateRequest;
import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.common.exception.ServiceUnavailableException;
import com.jungwoo.project.memo.routine.RoutineService;
import com.jungwoo.project.memo.routine.dto.RoutineSaveRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AI가 대화에서 뽑은 일정 사실 후보의 검증·저장·적용·거절을 전담한다.
 *
 * <p><b>자동 저장하지 않는다.</b> 자연어 → 구조화된 후보 → 사용자 검토/수정 → 사용자 적용
 * → 원본 저장. 이 순서를 건너뛰는 경로를 만들지 않는다. AI가 사용자의 말을 잘못 이해했을
 * 때 그것이 조용히 사실이 되면, 사용자는 자기가 만들지 않은 일정 때문에 계획이 비어 있는
 * 이유를 알 수 없다.
 *
 * <p><b>도메인 생성은 기존 경로를 그대로 쓴다.</b> COMMITMENT는 CommitmentService.create,
 * ROUTINE은 RoutineService.create다. AI 전용 생성 규칙을 복제하지 않는다 — 두 벌이 되면
 * 한쪽만 고쳐져 "화면으로는 못 만드는 값이 AI로는 들어가는" 상태가 된다.
 *
 * <p>이 서비스는 "이건 반복인가"를 판단하지 않는다. 그 판단은 AI의 몫이고(프롬프트가 반복성이
 * 명백할 때만 ROUTINE을 만들게 한다), 여기서는 계약 형태만 본다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleSuggestionService {

    private static final int MAX_SUGGESTIONS_PER_TURN = 5;

    /** 저장 요청 DTO에 매핑하지 않는 안내 키. 저장 컬럼에는 남기고 DTO/화면 경계에서만 뗀다. */
    private static final List<String> ANNOTATION_KEYS = List.of(
            DraftProposalBuilder.FIELD_NOTES_KEY,
            DraftProposalBuilder.ASSUMED_FIELDS_KEY,
            DraftProposalBuilder.DERIVED_FROM_KEY);

    /**
     * 이미지 한 장에서 만들 수 있는 후보 수의 상한.
     *
     * <p>대화 상한(5)과 따로 두는 이유는 두 경로가 다른 것을 막기 때문이다. 5는 "모델이
     * 대화에서 일정을 지어내는 것"을 막는 숫자다. 근무표는 한 주만 해도 최대 7개, 달 단위
     * 표면 31개가 정상이라 그 숫자를 쓰면 기능이 성립하지 않는다. 여기서 막는 것은 표가
     * 아닌 것을 표로 읽었을 때의 폭주이므로 한 달 길이로 잡는다.
     */
    private static final int MAX_IMPORTED_SUGGESTIONS = 31;

    private final AiScheduleSuggestionMapper suggestionMapper;
    private final CommitmentService commitmentService;
    private final RoutineService routineService;
    /**
     * DTO에 선언된 Bean Validation을 직접 돌린다.
     *
     * <p>직접 생성 경로(POST /api/commitments)는 Spring MVC가 @Valid로 이것을 대신 해주지만,
     * 이 경로는 JSON을 ObjectMapper로 읽어 서비스를 바로 부르므로 아무도 돌려주지 않는다.
     * 역직렬화 성공과 Bean Validation 성공은 다른 것이다 — title이 없는 JSON도 객체로는
     * 멀쩡히 만들어지고, 그 객체는 도메인 서비스에서 getTitle().trim()에 닿아 NPE가 된다.
     */
    private final Validator validator;

    /**
     * 공용 빈을 주입받는다. 직접 만들면 날짜 포맷·모듈 설정이 이 클래스에만 따로 남아,
     * 나중에 설정을 바꿔도 이 경로만 옛 동작으로 뒤처진다({@code JacksonConfig} 참고).
     */
    private final ObjectMapper objectMapper;

    // ===== 생성 — AiTurnLifecycleService.completeTurnSuccess 트랜잭션 안에서 호출된다 =====

    /**
     * 모델이 낸 후보를 검증해 PROPOSED로 저장한다.
     *
     * <p>저장 전에 payload를 실제 도메인 요청으로 읽어 본다. 읽히지 않는 후보를 카드로
     * 띄우면 사용자가 [적용]을 눌러야만 그게 못 쓰는 값이었다는 것을 알게 된다. 계약
     * 위반이면 조용히 고쳐 쓰지 않고 턴 전체를 실패시킨다 — 호출부가 같은 트랜잭션이라
     * ASSISTANT 메시지·Proposal까지 함께 롤백된다(ContextChangeSuggestionService와 같다).
     */
    public List<ScheduleSuggestionResponse> createFromSuggestions(
            Long userId, Long conversationId, Long sourceMessageId, List<ScheduleSuggestion> raw
    ) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        if (raw.size() > MAX_SUGGESTIONS_PER_TURN) {
            log.warn("일정 후보 검증 실패: 개수 초과 (count={})", raw.size());
            throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
        }

        List<ScheduleSuggestionResponse> result = new ArrayList<>();
        for (ScheduleSuggestion candidate : raw) {
            if (candidate.kind() == null || candidate.payload() == null
                    || !candidate.payload().isObject()) {
                log.warn("일정 후보 검증 실패: kind 또는 payload 누락");
                throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
            }
            // 모델이 낸 값이므로 계약 위반이다. 실제 도메인 검증(시간 역전 등)은 적용 시점에
            // 같은 도메인 서비스가 한 번 더 한다 — 두 층은 보는 것이 다르다.
            readAndValidatePayload(candidate.kind(), candidate.payload(), PayloadSource.MODEL);

            AiScheduleSuggestion entity = AiScheduleSuggestion.builder()
                    .userId(userId)
                    .conversationId(conversationId)
                    .sourceMessageId(sourceMessageId)
                    .kind(candidate.kind())
                    .proposedPayload(toJson(candidate.payload()))
                    .status(ScheduleSuggestionStatus.PROPOSED)
                    .build();
            suggestionMapper.insert(entity);

            result.add(ScheduleSuggestionResponse.of(entity, toMap(candidate.payload())));
        }
        log.info("일정 후보 저장: userId={}, conversationId={}, count={}", userId, conversationId, result.size());
        return result;
    }

    /**
     * 이미지에서 읽은 근무를 후보로 저장한다.
     *
     * <p>{@link #createFromSuggestions}와 달리 payload를 <b>서버가 만든다.</b> 모델은 표를
     * 받아쓰기만 했고 시간·날짜는 {@code ScheduleTableInterpreter}가 계산했다. 그래서 여기
     * 들어오는 값은 모델의 것이 아니라 우리 코드의 것이다 — 검증이 실패하면 그건 계약
     * 위반(503)이 아니라 우리 버그다.
     *
     * <p>그래도 검증은 돌린다. 저장 전에 걸러야 사용자가 [적용]을 눌렀을 때가 아니라 지금
     * 실패한다.
     */
    public List<ScheduleSuggestionResponse> createFromImport(
            Long userId, Long conversationId, Long sourceMessageId, List<CommitmentCreateRequest> commitments
    ) {
        if (commitments == null || commitments.isEmpty()) {
            return List.of();
        }
        if (commitments.size() > MAX_IMPORTED_SUGGESTIONS) {
            log.warn("이미지 일정 후보 개수 초과: userId={}, count={}", userId, commitments.size());
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }

        List<ScheduleSuggestionResponse> result = new ArrayList<>();
        for (CommitmentCreateRequest commitment : commitments) {
            Set<ConstraintViolation<CommitmentCreateRequest>> violations = validator.validate(commitment);
            if (!violations.isEmpty()) {
                String detail = violations.stream()
                        .map(v -> v.getPropertyPath() + " " + v.getMessage())
                        .sorted()
                        .collect(Collectors.joining(", "));
                // 서버가 만든 값이 검증을 통과하지 못했다. 사용자 입력 오류가 아니다.
                log.error("이미지 일정 후보가 자체 검증에 실패했다: userId={}, 위반={}", userId, detail);
                throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
            }

            JsonNode payload = objectMapper.valueToTree(commitment);
            AiScheduleSuggestion entity = AiScheduleSuggestion.builder()
                    .userId(userId)
                    .conversationId(conversationId)
                    .sourceMessageId(sourceMessageId)
                    .kind(ScheduleSuggestionKind.COMMITMENT)
                    .proposedPayload(toJson(payload))
                    .status(ScheduleSuggestionStatus.PROPOSED)
                    .build();
            suggestionMapper.insert(entity);

            result.add(ScheduleSuggestionResponse.of(entity, toMap(payload)));
        }
        log.info("이미지 일정 후보 저장: userId={}, conversationId={}, count={}",
                userId, conversationId, result.size());
        return result;
    }

    /**
     * draft(진행 중 요청 상태)에서 서버가 만든 후보를 저장한다. AiTurnLifecycleService.completeTurnSuccess
     * 트랜잭션 안에서 DraftPromotionService가 부른다.
     *
     * <p>{@link #createFromImport}와 같은 성격이다 — payload는 모델이 아니라 우리 코드
     * ({@code DraftProposalBuilder})가 시간표·근무 데이터로 계산한 값이라, 검증 실패는 계약 위반이
     * 아니라 우리 버그다(503 + error 로그). 상한도 대화 상한(5)이 아니라 이미지와 같은 31이다 —
     * 첫 수업 요일 4개 + 근무 2주치가 정상 범위다.
     *
     * <p>payload에는 저장 요청 DTO 밖의 두 키({@code fieldNotes}, {@code assumedFields})가 실려 있다.
     * 저장 컬럼에는 그대로 남기고(새로고침 후에도 안내를 다시 그려야 한다), DTO로 읽을 때와 화면
     * payload로 내보낼 때만 떼어낸다.
     */
    public List<ScheduleSuggestionResponse> createFromDraft(
            Long userId, Long conversationId, Long sourceMessageId, List<ScheduleSuggestion> serverMade
    ) {
        if (serverMade == null || serverMade.isEmpty()) {
            return List.of();
        }
        if (serverMade.size() > MAX_IMPORTED_SUGGESTIONS) {
            log.error("draft 후보 개수 초과: userId={}, count={}", userId, serverMade.size());
            throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
        }
        List<ScheduleSuggestionResponse> result = new ArrayList<>();
        for (ScheduleSuggestion candidate : serverMade) {
            if (candidate.kind() == null || candidate.payload() == null || !candidate.payload().isObject()) {
                log.error("draft 후보가 비어 있다: userId={}", userId);
                throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
            }
            readAndValidatePayload(candidate.kind(), candidate.payload(), PayloadSource.SERVER);

            AiScheduleSuggestion entity = AiScheduleSuggestion.builder()
                    .userId(userId)
                    .conversationId(conversationId)
                    .sourceMessageId(sourceMessageId)
                    .kind(candidate.kind())
                    .proposedPayload(toJson(candidate.payload()))
                    .status(ScheduleSuggestionStatus.PROPOSED)
                    .build();
            suggestionMapper.insert(entity);
            result.add(toResponse(entity, candidate.payload()));
        }
        log.info("draft 일정 후보 저장: userId={}, conversationId={}, count={}", userId, conversationId, result.size());
        return result;
    }

    // ===== 조회 =====

    /** 대화 재진입 시 복원할 미처리 후보. */
    @Transactional(readOnly = true)
    public List<ScheduleSuggestionResponse> listPendingByConversation(Long conversationId, Long userId) {
        return toResponses(suggestionMapper.findPendingByConversationIdAndUserId(conversationId, userId));
    }

    /**
     * 아직 적용되지 않은 파생 이동 후보. draft 사실 수집이 "이미 카드가 있는 근무"를 알기 위해 읽는다.
     *
     * <p>적용된 약속만 보면, 같은 요청을 두 번 말했을 때 같은 근무에 카드가 두 장 생긴다.
     * 사용자는 어느 쪽을 눌러야 하는지 알 수 없고, 둘 다 누르면 두 번째는 409로 막힌다 —
     * 막히기 전에 만들지 않는 편이 맞다.
     *
     * <p>payload JSON을 자바에서 읽는다. 저장 컬럼이 텍스트라 SQL로 거르려면 JSON 함수에
     * 의존해야 하고, 미처리 후보는 원래 몇 건 수준이다.
     */
    @Transactional(readOnly = true)
    public List<ExistingTravel> findPendingDerivedTravel(Long userId) {
        List<ExistingTravel> result = new ArrayList<>();
        for (AiScheduleSuggestion suggestion : suggestionMapper.findPendingByUserId(userId)) {
            if (suggestion.getKind() != ScheduleSuggestionKind.COMMITMENT) {
                continue;
            }
            JsonNode payload;
            try {
                payload = objectMapper.readTree(suggestion.getProposedPayload());
            } catch (Exception e) {
                // 읽히지 않는 후보는 여기서 실패시키지 않는다 — 이 조회는 중복 방지용 보조 정보다.
                log.warn("미처리 후보 payload를 읽지 못했다: suggestionId={}", suggestion.getSuggestionId());
                continue;
            }
            DerivedReference reference = derivedReferenceOf(payload);
            if (!reference.usable()) {
                continue;
            }
            result.add(new ExistingTravel(reference.originCommitmentId(), reference.relation(),
                    ExistingTravel.Source.PROPOSED, suggestion.getSuggestionId(),
                    parseDateTime(payload.path("startAt").asText(null)),
                    parseDateTime(payload.path("endAt").asText(null))));
        }
        return result;
    }

    /** idempotency 재생용 — 그 ASSISTANT 메시지가 만든 후보 전체(상태 무관). */
    @Transactional(readOnly = true)
    public List<ScheduleSuggestionResponse> findBySourceMessageId(Long sourceMessageId, Long userId) {
        return toResponses(suggestionMapper.findBySourceMessageIdAndUserId(sourceMessageId, userId));
    }

    // ===== 적용/거절 =====

    /**
     * 후보를 실제 원본으로 만든다.
     *
     * <p>도메인 생성과 상태 전이가 한 트랜잭션이다. 나누면 "약속은 만들어졌는데 후보는
     * 아직 PROPOSED"가 가능해지고, 사용자가 카드를 한 번 더 눌러 같은 약속을 두 개 만든다.
     *
     * <p>editedPayload가 있으면 그것을, 없으면 저장된 원본을 쓴다. 어느 쪽이든 같은 검증을
     * 통과해야 한다 — 사용자가 고쳤다고 검증을 건너뛰지 않는다.
     */
    @Transactional
    public ScheduleSuggestionResponse apply(Long suggestionId, Long userId, Map<String, Object> editedPayload) {
        return applyLocked(requireForUpdate(suggestionId, userId), userId, editedPayload);
    }

    /**
     * 여러 후보를 한 번에 적용한다. <b>전부 되거나 전부 안 된다.</b>
     *
     * <p>근무표 한 장이 7개의 후보를 만들고, 사용자는 그것을 하나씩 누르지 않는다. 그런데
     * 중간에 실패해 3개만 들어가면 사용자는 어느 것이 들어갔는지 카드만 보고는 알 수 없다.
     * 한 트랜잭션으로 묶어 그 상태를 아예 없앤다.
     *
     * <p><b>잠금은 suggestionId 오름차순으로 잡는다.</b> 두 요청이 겹치는 후보 집합을 서로
     * 다른 순서로 잠그면 교착이 난다. 정렬해 두면 먼저 잡은 쪽이 끝날 때까지 다른 쪽이
     * 기다리기만 한다.
     *
     * <p>이미 처리된 후보가 섞여 있으면 통째로 거절한다. [모두 적용]을 두 번 누른 경우가
     * 여기 해당하는데, 조용히 건너뛰면 "몇 개는 방금 만들어졌고 몇 개는 아까 만들어졌다"가
     * 되어 사용자가 무엇을 승인한 것인지 흐려진다. 화면이 목록을 새로 받는 것이 맞다.
     */
    @Transactional
    public List<ScheduleSuggestionResponse> applyBatch(Long userId, List<Long> suggestionIds) {
        if (suggestionIds == null || suggestionIds.isEmpty()) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }
        List<Long> ordered = suggestionIds.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        if (ordered.isEmpty() || ordered.size() > MAX_IMPORTED_SUGGESTIONS) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }

        List<ScheduleSuggestionResponse> applied = new ArrayList<>();
        for (Long suggestionId : ordered) {
            applied.add(applyLocked(requireForUpdate(suggestionId, userId), userId, null));
        }
        log.info("일정 후보 일괄 적용: userId={}, count={}", userId, applied.size());
        return applied;
    }

    /**
     * 잠금을 이미 잡은 후보 하나를 적용한다.
     *
     * <p>{@link #apply}와 {@link #applyBatch}가 공유한다. 두 벌로 두면 한쪽만 고쳐져
     * "하나씩 누를 때와 모두 적용할 때 결과가 다른" 상태가 된다.
     */
    private ScheduleSuggestionResponse applyLocked(AiScheduleSuggestion suggestion, Long userId,
                                                   Map<String, Object> editedPayload) {
        Long suggestionId = suggestion.getSuggestionId();
        // 이미 결론이 난 후보는 다시 적용하지 않는다. APPLIED에 또 적용하면 원본이 하나 더 생긴다.
        if (suggestion.getStatus() != ScheduleSuggestionStatus.PROPOSED) {
            throw new ConflictException(ErrorCode.SCHEDULE_SUGGESTION_ALREADY_RESOLVED);
        }

        /*
         * 사용자가 고친 값이면 그 오류는 사용자의 것이라 400이고, 저장된 원본을 그대로 쓰는
         * 경우면 그 값은 모델이 낸 것이라 계약 위반(503)이다. 저장 시점에 이미 검증했으므로
         * 후자는 사실상 나오지 않지만, 나온다면 그건 우리 저장 데이터가 깨졌다는 뜻이지
         * 사용자가 잘못 입력했다는 뜻이 아니다.
         */
        boolean edited = editedPayload != null && !editedPayload.isEmpty();
        JsonNode stored = readTree(suggestion.getProposedPayload());
        JsonNode payload = edited ? objectMapper.valueToTree(editedPayload) : stored;
        Object request = readAndValidatePayload(suggestion.getKind(), payload,
                edited ? PayloadSource.USER_EDIT : PayloadSource.MODEL);
        /*
         * 파생 관계는 사용자가 고친 payload가 아니라 저장된 원본에서 읽는다. 서버가 근무
         * 데이터로 정한 사실이라 사용자가 바꿀 값이 아니고, 카드가 되돌려 보내는 payload에는
         * 애초에 실려 있지도 않다(withoutAnnotations가 떼어 낸다). 사용자가 시각·제목을 고쳐도
         * "이 블록은 저 근무에서 나왔다"는 사실은 그대로 남아야 다음 조회에서 근무로 오인되지 않는다.
         */
        createDomain(userId, suggestion.getKind(), request, derivedTravelOf(stored));

        int resolved = suggestionMapper.resolveIfProposed(
                suggestionId, userId, ScheduleSuggestionStatus.APPLIED.name(), LocalDateTime.now());
        if (resolved != 1) {
            // 잠금을 잡고 있었으므로 여기 오면 안 된다. 왔다면 조용히 넘기지 않고 롤백한다.
            log.warn("일정 후보 적용 실패: 잠금 상태에서 예상치 못한 동시 갱신 (suggestionId={})", suggestionId);
            throw new ConflictException(ErrorCode.SCHEDULE_SUGGESTION_ALREADY_RESOLVED);
        }

        log.info("일정 후보 적용: userId={}, suggestionId={}, kind={}", userId, suggestionId, suggestion.getKind());
        suggestion.setStatus(ScheduleSuggestionStatus.APPLIED);
        return ScheduleSuggestionResponse.of(suggestion, toMap(payload));
    }

    /** 도메인 행을 만들지 않고 후보만 닫는다. */
    @Transactional
    public ScheduleSuggestionResponse dismiss(Long suggestionId, Long userId) {
        AiScheduleSuggestion suggestion = requireForUpdate(suggestionId, userId);
        if (suggestion.getStatus() != ScheduleSuggestionStatus.PROPOSED) {
            throw new ConflictException(ErrorCode.SCHEDULE_SUGGESTION_ALREADY_RESOLVED);
        }

        int resolved = suggestionMapper.resolveIfProposed(
                suggestionId, userId, ScheduleSuggestionStatus.DISMISSED.name(), LocalDateTime.now());
        if (resolved != 1) {
            throw new ConflictException(ErrorCode.SCHEDULE_SUGGESTION_ALREADY_RESOLVED);
        }

        suggestion.setStatus(ScheduleSuggestionStatus.DISMISSED);
        return ScheduleSuggestionResponse.of(suggestion, toMap(readTree(suggestion.getProposedPayload())));
    }

    // ===== 내부 =====

    /** 종류별로 기존 생성 경로를 그대로 부른다. 여기서 새 규칙을 만들지 않는다. */
    private void createDomain(Long userId, ScheduleSuggestionKind kind, Object request,
                              CommitmentService.DerivedTravel origin) {
        switch (kind) {
            case COMMITMENT -> commitmentService.create(userId, (CommitmentCreateRequest) request,
                    CommitmentSourceType.AI_SUGGESTION_APPROVED, origin);
            case ROUTINE -> routineService.create(userId, (RoutineSaveRequest) request);
        }
    }

    /**
     * 저장된 payload에서 읽은 파생 참조. 세 가지를 구분한다.
     *
     * <p>"파생 정보가 아예 없음"과 "있는데 깨졌음"을 합치면, 깨진 후보가 조용히 보통 약속으로
     * 저장된다. 그 행은 원본 참조가 없어 다음 조회에서 근무로 오인되고 중복 방지도 받지 못한다.
     *
     * @param present 파생 키가 payload에 있었는가
     */
    private record DerivedReference(boolean present, Long originCommitmentId,
                                    DerivedTravelRelation relation, LocalDateTime anchorAt) {
        static final DerivedReference ABSENT = new DerivedReference(false, null, null, null);

        static DerivedReference broken() {
            return new DerivedReference(true, null, null, null);
        }

        /** 파생으로 다룰 수 있는가(필수 식별자·관계가 모두 있는가). */
        boolean usable() {
            return present && originCommitmentId != null && relation != null;
        }

        /** 파생 키는 있는데 쓸 수 없는가. 재검토로 돌려야 하는 상태다. */
        boolean brokenReference() {
            return present && !usable();
        }
    }

    private DerivedReference derivedReferenceOf(JsonNode storedPayload) {
        if (storedPayload == null || !storedPayload.has(DraftProposalBuilder.DERIVED_FROM_KEY)) {
            return DerivedReference.ABSENT;
        }
        JsonNode derived = storedPayload.get(DraftProposalBuilder.DERIVED_FROM_KEY);
        if (derived == null || !derived.isObject()) {
            return DerivedReference.broken();
        }
        JsonNode id = derived.path(DraftProposalBuilder.DERIVED_COMMITMENT_ID);
        DerivedTravelRelation relation = DerivedTravelRelation.from(
                derived.path(DraftProposalBuilder.DERIVED_RELATION).asText(null));
        if (!id.isNumber() || relation == null) {
            return DerivedReference.broken();
        }
        return new DerivedReference(true, id.asLong(), relation,
                parseDateTime(derived.path(DraftProposalBuilder.DERIVED_ANCHOR_AT).asText(null)));
    }

    /**
     * 적용에 쓸 파생 정보. 파생 키가 있는데 깨져 있으면 보통 약속으로 우회 저장하지 않고 거절한다.
     *
     * <p>기준 시각이 없는 것도 깨진 것으로 본다 — 그게 없으면 원본이 바뀌었는지 확인할 수 없고,
     * 확인 없이 저장하면 근무와 어긋난 이동이 조용히 생긴다.
     */
    private CommitmentService.DerivedTravel derivedTravelOf(JsonNode storedPayload) {
        DerivedReference reference = derivedReferenceOf(storedPayload);
        if (reference.brokenReference() || (reference.usable() && reference.anchorAt() == null)) {
            log.warn("파생 후보의 참조가 깨져 있다: {}",
                    storedPayload == null ? null : storedPayload.path(DraftProposalBuilder.DERIVED_FROM_KEY));
            throw new ConflictException(ErrorCode.DERIVED_COMMITMENT_ORIGIN_CHANGED);
        }
        if (!reference.usable()) {
            return null;
        }
        return new CommitmentService.DerivedTravel(reference.originCommitmentId(), reference.relation(),
                reference.anchorAt());
    }

    /** 이 payload가 누구의 것인지. 같은 검증이 실패해도 누구의 잘못인지에 따라 결과가 다르다. */
    private enum PayloadSource {
        /** 모델이 낸 값. 계약 위반이므로 턴을 실패시킨다(503). */
        MODEL,
        /** 사용자가 검토 카드에서 고친 값. 입력 오류이므로 400이고, 후보는 PROPOSED로 남는다. */
        USER_EDIT,
        /** 서버가 draft에서 계산한 값. 실패는 우리 버그라 503이다(createFromImport와 같다). */
        SERVER
    }

    /**
     * payload를 종류에 맞는 도메인 요청으로 읽고, 그 DTO에 선언된 Bean Validation을 돌린다.
     *
     * <p>두 단계가 모두 필요하다. 역직렬화는 "JSON이 이 모양인가"만 보고, title이 없는
     * JSON도 객체로는 멀쩡히 만들어진다. 그 객체가 도메인 서비스에 닿으면
     * {@code getTitle().trim()}에서 NPE가 난다 — 500으로 끝나고 무엇이 잘못됐는지도
     * 사용자에게 알려주지 못한다.
     *
     * <p>이 검증이 도메인 서비스의 검증을 대신하지 않는다. 여기는 DTO 계약(필수값·길이)을
     * 보고, 거기는 도메인 규칙(시작 &lt; 종료, 요일이 하나 이상, 기간 역전)을 본다.
     * 예를 들어 RoutineSaveRequest.daysOfWeek에는 애초에 annotation이 없고
     * RoutineService가 빈 목록을 거절한다 — 두 층을 합치면 그런 규칙이 갈 곳이 없어진다.
     */
    private Object readAndValidatePayload(ScheduleSuggestionKind kind, JsonNode payload,
                                          PayloadSource source) {
        Object request;
        try {
            Class<?> target = switch (kind) {
                case COMMITMENT -> CommitmentCreateRequest.class;
                case ROUTINE -> RoutineSaveRequest.class;
            };
            /*
             * 이 경로만 모르는 필드를 거절한다. 공용 빈은 관대하고(FAIL_ON_UNKNOWN_PROPERTIES
             * 꺼짐) 다른 서비스 넷이 그것에 기대고 있어 빈 자체를 엄격하게 바꿀 수는 없다.
             *
             * 여기서 관대하면 안 되는 이유는 payload가 도메인 요청 그 자체이기 때문이다.
             * 모델이 COMMITMENT에 {@code "repeat": "weekly"}를 얹으면 그 필드는 조용히
             * 버려지고 사용자는 일회성 약속 하나를 보게 된다. 필드가 사라지는 것은 Bean
             * Validation이 보지 못한다 — 남은 필드는 전부 멀쩡하기 때문이다.
             */
            request = objectMapper.readerFor(target)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(withoutAnnotations(payload));
        } catch (Exception e) {
            log.warn("일정 후보 payload를 읽지 못했다: kind={}, source={}, payload={}", kind, source, payload, e);
            throw rejected(source);
        }

        Set<ConstraintViolation<Object>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            String detail = violations.stream()
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .sorted()
                    .collect(Collectors.joining(", "));
            log.warn("일정 후보 payload 검증 실패: kind={}, source={}, 위반={}", kind, source, detail);
            throw rejected(source);
        }
        return request;
    }

    private RuntimeException rejected(PayloadSource source) {
        return source == PayloadSource.USER_EDIT
                ? new BadRequestException(ErrorCode.INVALID_INPUT_VALUE)
                : new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
    }

    private AiScheduleSuggestion requireForUpdate(Long suggestionId, Long userId) {
        AiScheduleSuggestion suggestion = suggestionMapper.findByIdAndUserIdForUpdate(suggestionId, userId);
        if (suggestion == null) {
            throw new NotFoundException(ErrorCode.SCHEDULE_SUGGESTION_NOT_FOUND);
        }
        return suggestion;
    }

    private List<ScheduleSuggestionResponse> toResponses(List<AiScheduleSuggestion> suggestions) {
        List<ScheduleSuggestionResponse> responses = new ArrayList<>();
        for (AiScheduleSuggestion suggestion : suggestions) {
            responses.add(toResponse(suggestion, readTree(suggestion.getProposedPayload())));
        }
        return responses;
    }

    /**
     * 화면 응답. draft가 붙인 안내 키({@code fieldNotes}/{@code assumedFields})는 payload에서 떼어 응답의
     * 별도 필드로 내린다 — 카드가 [적용] 때 되돌려 보내는 payload는 저장 요청 DTO 그대로여야 한다.
     */
    private ScheduleSuggestionResponse toResponse(AiScheduleSuggestion suggestion, JsonNode payload) {
        return ScheduleSuggestionResponse.of(suggestion, toMap(withoutAnnotations(payload)),
                annotationList(payload, DraftProposalBuilder.FIELD_NOTES_KEY),
                annotationList(payload, DraftProposalBuilder.ASSUMED_FIELDS_KEY));
    }

    /**
     * 저장 요청 DTO에 없는 안내 키를 뗀 사본. 세 키는 draft 경로가 payload에 실어 두는 것이고
     * 도메인 필드가 아니다. 그 외의 모르는 키는 여전히 거절된다(FAIL_ON_UNKNOWN_PROPERTIES).
     *
     * <p>{@code derivedFrom}도 여기서 뗀다 — 카드가 되돌려 보내는 payload는 저장 요청 DTO
     * 그대로여야 하고, 원본 참조는 서버가 저장된 payload에서 읽지 사용자에게 받지 않는다.
     */
    private JsonNode withoutAnnotations(JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            return payload;
        }
        boolean hasAny = false;
        for (String key : ANNOTATION_KEYS) {
            if (payload.has(key)) {
                hasAny = true;
                break;
            }
        }
        if (!hasAny) {
            return payload;
        }
        ObjectNode copy = payload.deepCopy();
        for (String key : ANNOTATION_KEYS) {
            copy.remove(key);
        }
        return copy;
    }

    private List<Map<String, Object>> annotationList(JsonNode payload, String key) {
        if (payload == null || !payload.isObject() || !payload.path(key).isArray()) {
            return List.of();
        }
        return objectMapper.convertValue(payload.get(key), new TypeReference<List<Map<String, Object>>>() { });
    }

    private LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.trim());
        } catch (Exception e) {
            log.warn("파생 기준 시각을 읽지 못했다: {}", value);
            return null;
        }
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("저장된 일정 후보 payload를 읽지 못했다", e);
            throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
        }
    }

    /**
     * 화면으로 나가는 payload는 Map이다.
     *
     * <p>HTTP·SSE 직렬화는 Jackson 3(tools.jackson)이 하는데(JacksonConfig 주석 참고),
     * Jackson 3은 Jackson 2의 JsonNode를 트리로 인식하지 못한다 — 일반 객체로 보고 내부
     * 구조를 내보내거나 빈 객체를 쓴다. 실제로 검토 카드에 제목·시각·장소가 하나도 안 떴다.
     *
     * <p>코드 안에서는 계속 JsonNode로 다룬다(모델 출력 파싱과 DTO 역직렬화가 Jackson 2
     * ObjectMapper를 쓴다). 경계에서만 바꾼다.
     */
    private Map<String, Object> toMap(JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            return Map.of();
        }
        return objectMapper.convertValue(payload, new TypeReference<Map<String, Object>>() { });
    }

    private String toJson(JsonNode payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("일정 후보 payload 직렬화 실패", e);
            throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
        }
    }
}
