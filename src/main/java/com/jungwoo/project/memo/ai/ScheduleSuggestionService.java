package com.jungwoo.project.memo.ai;

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
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.common.exception.ServiceUnavailableException;
import com.jungwoo.project.memo.routine.RoutineService;
import com.jungwoo.project.memo.routine.dto.RoutineSaveRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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

    private final AiScheduleSuggestionMapper suggestionMapper;
    private final CommitmentService commitmentService;
    private final RoutineService routineService;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

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
            // 읽히는지만 본다. 실제 도메인 검증(시간 역전 등)은 적용 시점에 같은 서비스가 한다.
            readPayload(candidate.kind(), candidate.payload());

            AiScheduleSuggestion entity = AiScheduleSuggestion.builder()
                    .userId(userId)
                    .conversationId(conversationId)
                    .sourceMessageId(sourceMessageId)
                    .kind(candidate.kind())
                    .proposedPayload(toJson(candidate.payload()))
                    .status(ScheduleSuggestionStatus.PROPOSED)
                    .build();
            suggestionMapper.insert(entity);

            result.add(ScheduleSuggestionResponse.of(entity, candidate.payload()));
        }
        log.info("일정 후보 저장: userId={}, conversationId={}, count={}", userId, conversationId, result.size());
        return result;
    }

    // ===== 조회 =====

    /** 대화 재진입 시 복원할 미처리 후보. */
    @Transactional(readOnly = true)
    public List<ScheduleSuggestionResponse> listPendingByConversation(Long conversationId, Long userId) {
        return toResponses(suggestionMapper.findPendingByConversationIdAndUserId(conversationId, userId));
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
    public ScheduleSuggestionResponse apply(Long suggestionId, Long userId, JsonNode editedPayload) {
        AiScheduleSuggestion suggestion = requireForUpdate(suggestionId, userId);
        // 이미 결론이 난 후보는 다시 적용하지 않는다. APPLIED에 또 적용하면 원본이 하나 더 생긴다.
        if (suggestion.getStatus() != ScheduleSuggestionStatus.PROPOSED) {
            throw new ConflictException(ErrorCode.SCHEDULE_SUGGESTION_ALREADY_RESOLVED);
        }

        JsonNode payload = editedPayload != null && editedPayload.isObject()
                ? editedPayload : readTree(suggestion.getProposedPayload());
        createDomain(userId, suggestion.getKind(), payload);

        int resolved = suggestionMapper.resolveIfProposed(
                suggestionId, userId, ScheduleSuggestionStatus.APPLIED.name(), LocalDateTime.now());
        if (resolved != 1) {
            // 잠금을 잡고 있었으므로 여기 오면 안 된다. 왔다면 조용히 넘기지 않고 롤백한다.
            log.warn("일정 후보 적용 실패: 잠금 상태에서 예상치 못한 동시 갱신 (suggestionId={})", suggestionId);
            throw new ConflictException(ErrorCode.SCHEDULE_SUGGESTION_ALREADY_RESOLVED);
        }

        log.info("일정 후보 적용: userId={}, suggestionId={}, kind={}", userId, suggestionId, suggestion.getKind());
        suggestion.setStatus(ScheduleSuggestionStatus.APPLIED);
        return ScheduleSuggestionResponse.of(suggestion, payload);
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
        return ScheduleSuggestionResponse.of(suggestion, readTree(suggestion.getProposedPayload()));
    }

    // ===== 내부 =====

    /** 종류별로 기존 생성 경로를 그대로 부른다. 여기서 새 규칙을 만들지 않는다. */
    private void createDomain(Long userId, ScheduleSuggestionKind kind, JsonNode payload) {
        switch (kind) {
            case COMMITMENT -> commitmentService.create(userId,
                    (CommitmentCreateRequest) readPayload(kind, payload),
                    CommitmentSourceType.AI_SUGGESTION_APPROVED);
            case ROUTINE -> routineService.create(userId, (RoutineSaveRequest) readPayload(kind, payload));
        }
    }

    /**
     * payload를 종류에 맞는 도메인 요청으로 읽는다.
     *
     * <p>필드 이름을 도메인 요청과 맞춰 둔 덕에 변환 계층이 없다. 읽히지 않으면 계약 위반이다.
     */
    private Object readPayload(ScheduleSuggestionKind kind, JsonNode payload) {
        try {
            return switch (kind) {
                case COMMITMENT -> objectMapper.treeToValue(payload, CommitmentCreateRequest.class);
                case ROUTINE -> objectMapper.treeToValue(payload, RoutineSaveRequest.class);
            };
        } catch (Exception e) {
            log.warn("일정 후보 payload를 읽지 못했다: kind={}, payload={}", kind, payload, e);
            throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
        }
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
            responses.add(ScheduleSuggestionResponse.of(
                    suggestion, readTree(suggestion.getProposedPayload())));
        }
        return responses;
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("저장된 일정 후보 payload를 읽지 못했다", e);
            throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
        }
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
