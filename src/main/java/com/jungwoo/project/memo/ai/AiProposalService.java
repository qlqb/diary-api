package com.jungwoo.project.memo.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.ai.domain.AiConversation;
import com.jungwoo.project.memo.ai.domain.AiProposal;
import com.jungwoo.project.memo.ai.domain.AiProposalItem;
import com.jungwoo.project.memo.ai.domain.AiProposalItemStatus;
import com.jungwoo.project.memo.ai.domain.AiProposalItemType;
import com.jungwoo.project.memo.ai.domain.AiProposalStatus;
import com.jungwoo.project.memo.ai.domain.ProposalOperation;
import com.jungwoo.project.memo.ai.dto.AiProposalApplyRequest;
import com.jungwoo.project.memo.ai.dto.AiProposalItemResponse;
import com.jungwoo.project.memo.ai.dto.AiProposalResponse;
import com.jungwoo.project.memo.ai.dto.ProposalAdjustment;
import com.jungwoo.project.memo.ai.dto.ProposalItem;
import com.jungwoo.project.memo.ai.dto.ProposalItemPayload;
import com.jungwoo.project.memo.ai.dto.UnavailableWindowSpec;
import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.common.exception.ServiceUnavailableException;
import com.jungwoo.project.memo.execution.ExecutionItemService;
import com.jungwoo.project.memo.execution.domain.ExecutionItem;
import com.jungwoo.project.memo.execution.domain.ExecutionPriority;
import com.jungwoo.project.memo.execution.domain.PlacementDuration;
import com.jungwoo.project.memo.execution.domain.PlacementType;
import com.jungwoo.project.memo.execution.dto.ExecutionItemHoldRequest;
import com.jungwoo.project.memo.execution.dto.ExecutionItemMoveRequest;
import com.jungwoo.project.memo.execution.dto.ExecutionItemReduceRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * AI 제안(Proposal) 저장·조회·적용.
 *
 * 생성은 더 이상 이 서비스가 LLM을 직접 부르지 않는다 — AiConversationService가 대화 한 턴을
 * 스트리밍하고 구조화 결과를 파싱한 뒤, PROPOSAL로 판단됐을 때만 createFromItems()를 부른다.
 * 적용 흐름: 행 잠금(SELECT ... FOR UPDATE) -> 상태 재확인 -> execution_items 생성 -> 상태 갱신,
 *          전부 하나의 트랜잭션.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiProposalService {

    private static final Set<String> VALID_PRIORITIES = Set.of("MUST", "SHOULD", "OPTIONAL");
    /**
     * Today 상담 제안 한 묶음의 상한. 한 번에 검토할 수 있는 양을 넘기지 않는다.
     *
     * 기간 계획은 이 값을 쓰지 않는다 — 계획의 주 제약은 개수가 아니라 시간 예산이고
     * (11-period-plan.md §5-1-1), 개수는 "확실히 뭔가 잘못됐다"는 폭주 방지선으로만
     * 훨씬 느슨하게 둔다. 그래서 상한은 상수가 아니라 호출자가 넘기는 값이다.
     */
    private static final int DEFAULT_MAX_ITEMS = 5;

    /**
     * "잘게 쪼갠 할 일" 하나의 길이 범위. 이 상한은 <b>시각이 정해지지 않은</b> 후보에만
     * 적용된다(DATE_ONLY/UNSCHEDULED) — 그쪽은 얼마나 걸릴지를 사람이 정하는 값이라
     * 한 조각이 두 시간을 넘으면 조각이 아니다.
     *
     * TIME_FIXED는 다르다. 알바·수업처럼 이미 시각이 박힌 일정은 길이가 현실이 정하는
     * 값이고 두 시간을 우습게 넘는다. 여기에 상한을 걸었더니 모델이 검증을 통과하려고
     * 시각은 진짜로 넣고 expectedMinutes만 120으로 깎아 내는 일이 실제로 있었다
     * (17:00~23:00짜리 알바가 120분으로 저장됨). 그래서 TIME_FIXED에서는 이 범위를
     * 검사하지 않고, 길이를 모델에게 묻지도 않는다 — PlacementDuration이 계산한다.
     */
    private static final int MIN_EXPECTED_MINUTES = 5;
    private static final int MAX_EXPECTED_MINUTES = 120;

    private final AiProposalPersistenceService persistenceService;
    private final AiProposalMapper aiProposalMapper;
    private final AiProposalItemMapper aiProposalItemMapper;
    private final AiConversationMapper aiConversationMapper;
    private final ExecutionItemService executionItemService;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    /** 다른 배치 경로와 같은 키를 쓴다 — 시간대가 하루를 좌우하므로 두 벌이 되게 하지 않는다. */
    @Value("${scheduling.availability.default-time-zone:Asia/Seoul}")
    private String defaultTimeZoneId = "Asia/Seoul";

    /**
     * 배치 가능 구간(placementWindow)의 시작일. 요청 기간(requestedPeriod)과는 다른 값이다.
     *
     * <p>수요일에 "이번 주"를 요청하면 요청 기간은 8/31~9/6 그대로 두고, 새 조각을 놓을 수
     * 있는 구간만 오늘 이후로 본다. SchedulePreviewService가 horizonStart를 오늘로 자르고
     * AvailabilityEstimateService가 lowerBound를 지금으로 자르는 것과 같은 층이다 —
     * 그 둘은 UNSCHEDULED 경로를 막고 있었고, 날짜가 박힌 경로만 뚫려 있었다.
     */
    private LocalDate placementFloor() {
        return ZonedDateTime.now(clock).withZoneSameInstant(ZoneId.of(defaultTimeZoneId)).toLocalDate();
    }

    // ===== 생성 (PROPOSAL 턴에서만 호출) =====

    /**
     * 이미 파싱된 모델 출력(items)을 검증해 ai_proposals/ai_proposal_items로 저장한다.
     * CHAT/OFFER 턴에서는 절대 호출되지 않는다 — 항목 1~5개 검증은 여기, PROPOSAL 한 곳에만 있다.
     */
    public AiProposalResponse createFromItems(
            Long userId, Long conversationId, Long sourceMessageId,
            List<ProposalItem> items, LocalDate targetDate, List<UnavailableWindowSpec> unavailableWindows
    ) {
        return createFromItems(userId, conversationId, sourceMessageId, items, List.of(), targetDate,
                unavailableWindows, DEFAULT_MAX_ITEMS);
    }

    /**
     * 새 후보(items)와 기존 조각 조정 후보(adjustments)를 하나의 제안 묶음으로 저장한다.
     * 둘 중 하나만 있어도 된다 — "줄이기만 하는 제안"도 유효한 제안이다. 다만 둘 다 비어 있으면
     * 보여줄 것이 없으므로 실패로 본다.
     */
    public AiProposalResponse createFromItems(
            Long userId, Long conversationId, Long sourceMessageId,
            List<ProposalItem> items, List<ProposalAdjustment> adjustments,
            LocalDate targetDate, List<UnavailableWindowSpec> unavailableWindows
    ) {
        return createFromItems(userId, conversationId, sourceMessageId, items, adjustments, targetDate,
                unavailableWindows, DEFAULT_MAX_ITEMS);
    }

    /**
     * 항목 수 상한을 호출자가 정하는 형태. 기간 계획 전용 경로가 쓴다(≤7일 15개 / 8~31일 30개).
     *
     * ★ 상한 검사는 두 곳에서 일어난다 — validateAndNormalize의 새 후보 검사와, 새 후보 +
     * 조정 후보를 합친 뒤의 총계 검사다. 둘 중 하나만 파라미터화하면 한쪽은 통과하고 다른
     * 쪽에서 거절되는(또는 그 반대) 상태가 되므로 반드시 같은 값을 함께 넘긴다.
     */
    public AiProposalResponse createFromItems(
            Long userId, Long conversationId, Long sourceMessageId,
            List<ProposalItem> items, List<ProposalAdjustment> adjustments,
            LocalDate targetDate, List<UnavailableWindowSpec> unavailableWindows, int maxItems
    ) {
        List<ProposalItemPayload> payloads = new ArrayList<>(validateAndNormalize(items, targetDate, maxItems));
        payloads.addAll(normalizeAdjustments(userId, adjustments, targetDate));

        if (payloads.isEmpty()) {
            log.warn("AI 제안 구조 검증 실패: 새 후보도 조정 후보도 없음");
            throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
        }
        if (payloads.size() > maxItems) {
            log.warn("AI 제안 구조 검증 실패: 후보 총 개수가 상한({})을 넘음: {}", maxItems, payloads.size());
            throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
        }
        return persistenceService.save(userId, conversationId, sourceMessageId, payloads, unavailableWindows);
    }

    /**
     * 조정 후보를 검증해 payload로 바꾼다.
     *
     * 모델이 존재하지 않는 id를 가리키거나 이미 끝난 항목을 조정하려 하면 그 후보만 조용히
     * 버린다 — 턴 전체를 실패시키지 않는다("파싱 실패 시 AI 자동 재호출 금지"와 같은 이유로,
     * 사용자 요청 하나를 모델의 사소한 실수로 통째로 날리지 않는다). 대신 유효한 후보가 하나도
     * 남지 않고 새 후보도 없으면 호출부가 실패로 처리한다.
     */
    private List<ProposalItemPayload> normalizeAdjustments(
            Long userId, List<ProposalAdjustment> adjustments, LocalDate targetDate
    ) {
        if (adjustments == null || adjustments.isEmpty()) {
            return List.of();
        }

        List<ProposalItemPayload> result = new ArrayList<>();
        Set<Long> seenTargets = new HashSet<>();

        for (ProposalAdjustment adjustment : adjustments) {
            if (adjustment.executionItemId() == null || adjustment.operation() == null
                    || adjustment.operation() == ProposalOperation.CREATE) {
                log.warn("AI 조정 후보 무시: executionItemId 또는 operation이 유효하지 않음");
                continue;
            }
            if (!seenTargets.add(adjustment.executionItemId())) {
                log.warn("AI 조정 후보 무시: 같은 실행 조각(#{})에 대한 조정이 중복됨", adjustment.executionItemId());
                continue;
            }

            ExecutionItem target = executionItemService.findOwnedForAdjustment(adjustment.executionItemId(), userId);
            if (target == null) {
                log.warn("AI 조정 후보 무시: 실행 조각 #{}을 찾을 수 없거나 조정할 수 있는 상태가 아님",
                        adjustment.executionItemId());
                continue;
            }

            ProposalItemPayload payload = switch (adjustment.operation()) {
                case REDUCE -> toReducePayload(adjustment, target);
                case MOVE -> toMovePayload(adjustment, target);
                case DROP -> ProposalItemPayload.adjust(ProposalOperation.DROP, target.getExecutionItemId(),
                        target.getVersion(), target.getTitle(), target.getExpectedMinutes(),
                        priorityName(target), target.getScheduledDate(),
                        target.getTitle(), target.getExpectedMinutes(), target.getScheduledDate(),
                        adjustment.reason());
                case CREATE -> null;
            };

            if (payload != null) {
                result.add(payload);
            }
        }
        return result;
    }

    private ProposalItemPayload toReducePayload(ProposalAdjustment adjustment, ExecutionItem target) {
        Integer newMinutes = adjustment.expectedMinutes();
        String newTitle = adjustment.title() != null && !adjustment.title().isBlank()
                ? adjustment.title() : target.getTitle();

        boolean minutesChanged = newMinutes != null && !Objects.equals(newMinutes, target.getExpectedMinutes());
        boolean titleChanged = !Objects.equals(newTitle, target.getTitle());
        if (!minutesChanged && !titleChanged) {
            log.warn("AI 조정 후보 무시: REDUCE인데 실제로 달라지는 값이 없음 (#{})", target.getExecutionItemId());
            return null;
        }
        if (newMinutes != null && newMinutes <= 0) {
            log.warn("AI 조정 후보 무시: REDUCE 분량이 0 이하 (#{})", target.getExecutionItemId());
            return null;
        }

        return ProposalItemPayload.adjust(ProposalOperation.REDUCE, target.getExecutionItemId(), target.getVersion(),
                newTitle, newMinutes != null ? newMinutes : target.getExpectedMinutes(), priorityName(target),
                target.getScheduledDate(),
                target.getTitle(), target.getExpectedMinutes(), target.getScheduledDate(), adjustment.reason());
    }

    /**
     * MOVE는 "언제 할지 변경"이다 — 다른 날짜로 옮기는 것과 같은 날 안에서 시각만 뒤로 미는 것
     * ("오전에 못 한 것을 오늘 16시로") 둘 다 포함한다. 후자는 시각이 정해진 항목에만 가능하고,
     * 이때만 toDate가 지금 날짜와 같아도 된다.
     */
    private ProposalItemPayload toMovePayload(ProposalAdjustment adjustment, ExecutionItem target) {
        LocalDate toDate = adjustment.toDate();
        if (toDate == null) {
            log.warn("AI 조정 후보 무시: MOVE 대상 날짜가 없음 (#{})", target.getExecutionItemId());
            return null;
        }
        if (target.getPlacementType() == PlacementType.UNSCHEDULED) {
            log.warn("AI 조정 후보 무시: 날짜 미정 조각은 MOVE할 수 없음 (#{})", target.getExecutionItemId());
            return null;
        }

        LocalDateTime newStart = null;
        LocalDateTime newEnd = null;
        if (adjustment.startTime() != null || adjustment.endTime() != null) {
            if (adjustment.startTime() == null || adjustment.endTime() == null
                    || !adjustment.endTime().isAfter(adjustment.startTime())) {
                log.warn("AI 조정 후보 무시: MOVE 시각이 올바르지 않음 (#{})", target.getExecutionItemId());
                return null;
            }
            if (target.getPlacementType() != PlacementType.TIME_FIXED) {
                log.warn("AI 조정 후보 무시: 시각 미정 조각에 MOVE 시각을 붙일 수 없음 (#{})",
                        target.getExecutionItemId());
                return null;
            }
            newStart = LocalDateTime.of(toDate, adjustment.startTime());
            newEnd = LocalDateTime.of(toDate, adjustment.endTime());
        }

        boolean dateChanged = !toDate.equals(target.getScheduledDate());
        boolean timeChanged = newStart != null && !Objects.equals(newStart, target.getScheduledStartAt());
        if (!dateChanged && !timeChanged) {
            log.warn("AI 조정 후보 무시: MOVE인데 날짜도 시각도 달라지지 않음 (#{})", target.getExecutionItemId());
            return null;
        }

        return ProposalItemPayload.adjust(ProposalOperation.MOVE, target.getExecutionItemId(), target.getVersion(),
                target.getTitle(), target.getExpectedMinutes(), priorityName(target), toDate, newStart, newEnd,
                target.getTitle(), target.getExpectedMinutes(), target.getScheduledDate(), adjustment.reason());
    }

    private String priorityName(ExecutionItem item) {
        return item.getPriority() != null ? item.getPriority().name() : "SHOULD";
    }

    private List<ProposalItemPayload> validateAndNormalize(
            List<ProposalItem> items, LocalDate targetDate, int maxItems) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        if (items.size() > maxItems) {
            log.warn("AI 제안 구조 검증 실패: 항목 개수가 상한({})을 넘음: {}", maxItems, items.size());
            throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
        }

        List<ProposalItemPayload> result = new ArrayList<>();
        for (ProposalItem item : items) {
            if (item.title() == null || item.title().isBlank()) {
                log.warn("AI 제안 구조 검증 실패: 제목 누락");
                throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
            }
            // expectedMinutes 검사는 placementType이 확정된 뒤에 한다 — TIME_FIXED인지에
            // 따라 규칙이 다르고, TIME_FIXED 여부는 아래 fixedStartAt 처리까지 가야 정해진다.
            if (item.priority() == null || !VALID_PRIORITIES.contains(item.priority())) {
                log.warn("AI 제안 구조 검증 실패: priority가 유효하지 않음");
                throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
            }

            // 메서드 파라미터 targetDate는 반복문 안에서 절대 바꾸지 않는다 — 한 항목의
            // fixedStartAt 날짜가 다음 항목(예: DATE_ONLY)에 새어 들어가는 것을 막기 위해,
            // 이번 항목에만 적용되는 지역 변수로만 다룬다.
            LocalDate itemTargetDate = targetDate;
            PlacementType placementType = item.placementType() != null ? item.placementType() : PlacementType.DATE_ONLY;
            LocalDateTime scheduledStartAt = null;
            LocalDateTime scheduledEndAt = null;
            LocalDate earliestStartDate = null;
            LocalDate deadlineDate = null;

            if (item.fixedStartAt() != null || item.fixedEndAt() != null) {
                // 사용자가 명확한 날짜+시각을 함께 말한 경우 — 이 후보를 그 시각에 고정한다.
                // Timefold가 계산하지 않고 그대로 TIME_FIXED로 저장한다.
                if (item.fixedStartAt() == null || item.fixedEndAt() == null
                        || !item.fixedEndAt().isAfter(item.fixedStartAt())) {
                    log.warn("AI 제안 구조 검증 실패: fixedStartAt/fixedEndAt이 올바르지 않음");
                    throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
                }
                placementType = PlacementType.TIME_FIXED;
                scheduledStartAt = item.fixedStartAt();
                scheduledEndAt = item.fixedEndAt();
                itemTargetDate = scheduledStartAt.toLocalDate();
            } else if (placementType == PlacementType.TIME_FIXED) {
                if (item.startTime() == null || item.endTime() == null) {
                    log.warn("AI 제안 구조 검증 실패: TIME_FIXED인데 시작/종료 시각 누락");
                    throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
                }
                if (!item.endTime().isAfter(item.startTime())) {
                    log.warn("AI 제안 구조 검증 실패: 종료 시각이 시작 시각보다 이후가 아님");
                    throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
                }
                scheduledStartAt = LocalDateTime.of(itemTargetDate, item.startTime());
                scheduledEndAt = LocalDateTime.of(itemTargetDate, item.endTime());
            } else if (placementType == PlacementType.DATE_ONLY) {
                if (item.startTime() != null || item.endTime() != null) {
                    log.warn("AI 제안 구조 검증 실패: DATE_ONLY인데 시각이 채워짐");
                    throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
                }
            } else if (placementType == PlacementType.UNSCHEDULED) {
                if (item.startTime() != null || item.endTime() != null) {
                    log.warn("AI 제안 구조 검증 실패: UNSCHEDULED인데 시각이 채워짐");
                    throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
                }
                // earliestStartDate/deadlineDate는 AI_INFERRED 힌트일 뿐이다 — 모델이 이상한
                // 값(범위 역전 등)을 내도 전체 PROPOSAL을 실패시키지 않고 조용히 무시한다
                // ("파싱 실패 시 AI 자동 재호출 금지" 원칙과 같은 이유로, 여기서도 재호출 없이
                // 최선으로 진행한다).
                earliestStartDate = item.earliestStartDate();
                deadlineDate = item.deadlineDate();
                if (earliestStartDate != null && deadlineDate != null && deadlineDate.isBefore(earliestStartDate)) {
                    earliestStartDate = null;
                    deadlineDate = null;
                }
            }

            /*
             * 날짜가 박힌 항목(DATE_ONLY/TIME_FIXED)은 배치 가능 구간 안이어야 한다.
             *
             * UNSCHEDULED는 여기서 보지 않는다 — 그쪽은 SchedulePreviewService가 horizonStart를
             * 오늘로 자르고 AvailabilityEstimateService가 lowerBound를 지금으로 잘라 이미 두 겹으로
             * 막혀 있다. 뚫려 있던 것은 날짜가 박힌 경로 하나뿐이었고, 실제로 "이번 주" 계획
             * 5건이 전부 지난 월요일에 쌓였다.
             *
             * 조용히 오늘로 당기지 않는다. 모델이 지난 날짜를 지정했다는 것은 그 항목을 언제
             * 하기로 한 것인지 서버가 모른다는 뜻이고, 날짜만 바꿔 놓으면 사용자는 자기가
             * 승인하지 않은 날짜를 보게 된다.
             */
            if (placementType != PlacementType.UNSCHEDULED
                    && itemTargetDate != null && itemTargetDate.isBefore(placementFloor())) {
                log.warn("AI 제안 구조 검증 실패: 항목 '{}'의 배치 날짜({})가 이미 지났음(오늘={})",
                        item.title(), itemTargetDate, placementFloor());
                throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
            }

            Integer expectedMinutes;
            if (placementType == PlacementType.TIME_FIXED) {
                // 시각이 정해진 순간 길이는 이미 결정돼 있다. 모델이 따로 적어 낸 숫자는 보지
                // 않는다 — 두 출처가 어긋나면 화면과 하루 부하 계산이 조용히 틀어진다.
                expectedMinutes = PlacementDuration.minutesBetween(scheduledStartAt, scheduledEndAt);
            } else {
                if (item.expectedMinutes() == null
                        || item.expectedMinutes() < MIN_EXPECTED_MINUTES
                        || item.expectedMinutes() > MAX_EXPECTED_MINUTES) {
                    log.warn("AI 제안 구조 검증 실패: expectedMinutes가 유효 범위를 벗어남");
                    throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
                }
                expectedMinutes = item.expectedMinutes();
            }

            result.add(ProposalItemPayload.create(
                    item.title(), item.description(), expectedMinutes, item.priority(), itemTargetDate,
                    placementType, scheduledStartAt, scheduledEndAt, earliestStartDate, deadlineDate,
                    item.courseId()));
        }
        return result;
    }

    // ===== 조회 =====

    /** 이 ASSISTANT 메시지가 만든 제안을 조회한다. 없으면 null(예외 아님 — CHAT/OFFER 메시지는 정상적으로 없다). */
    @Transactional(readOnly = true)
    public AiProposalResponse findBySourceMessageId(Long assistantMessageId, Long userId) {
        AiProposal proposal = aiProposalMapper.findBySourceMessageIdAndUserId(assistantMessageId, userId);
        if (proposal == null) {
            return null;
        }
        return get(proposal.getProposalId(), userId);
    }

    @Transactional(readOnly = true)
    public AiProposalResponse get(Long proposalId, Long userId) {
        AiProposal proposal = aiProposalMapper.findByIdAndUserId(proposalId, userId);
        if (proposal == null) {
            throw new NotFoundException(ErrorCode.AI_PROPOSAL_NOT_FOUND);
        }

        List<AiProposalItem> items = aiProposalItemMapper.findByProposalIdAndUserId(proposalId, userId);
        List<AiProposalItemResponse> itemResponses = items.stream()
                .map(this::toItemResponse)
                .toList();

        return AiProposalResponse.builder()
                .proposalId(proposal.getProposalId())
                .targetScope(proposal.getTargetScope())
                .status(proposal.getStatus())
                .createdAt(proposal.getCreatedAt())
                .expiresAt(proposal.getExpiresAt())
                .respondedAt(proposal.getRespondedAt())
                .items(itemResponses)
                .build();
    }

    // ===== 전체 적용 =====

    @Transactional
    public AiProposalResponse apply(Long proposalId, Long userId, AiProposalApplyRequest request) {
        AiProposal proposal = aiProposalMapper.findByIdAndUserIdForUpdate(proposalId, userId);
        if (proposal == null) {
            throw new NotFoundException(ErrorCode.AI_PROPOSAL_NOT_FOUND);
        }
        if (proposal.getStatus() != AiProposalStatus.PROPOSED) {
            throw new ConflictException(ErrorCode.AI_PROPOSAL_ALREADY_RESPONDED);
        }

        List<AiProposalItem> items = aiProposalItemMapper.findByProposalIdAndUserId(proposalId, userId);
        if (items.isEmpty()) {
            throw new ConflictException(ErrorCode.AI_PROPOSAL_ALREADY_RESPONDED);
        }

        Map<Long, AiProposalApplyRequest.EditedProposalItem> editedById = indexAndValidateEditedItems(request, items);
        Set<Long> excludedIds = request.getExcludedItemIds() != null
                ? new HashSet<>(request.getExcludedItemIds())
                : Set.of();
        validateExcludedIds(excludedIds, items);

        if (excludedIds.size() == items.size()) {
            // 전부 제외하면 적용할 것이 없다 — 빈 묶음을 APPLIED로 만들지 않는다.
            throw new BadRequestException(ErrorCode.INVALID_PROPOSAL_ITEM_SELECTION);
        }

        // 항목마다 최종 날짜가 다를 수 있다(7일 범위 배치 미리보기 적용 시) — 날짜별로 별도
        // order_index 시작값을 그날의 기존 최대값 다음부터 이어 붙인다. 오늘 하루짜리 기존
        // 흐름은 items가 전부 같은 날짜이므로 동작이 그대로다.
        Map<LocalDate, Integer> nextOrderIndexByDate = new HashMap<>();

        Long proposalCourseId = resolveCourseId(proposal, userId);

        boolean anyModified = false;
        List<AiProposalItemResponse> responses = new ArrayList<>();
        LocalDateTime respondedAt = LocalDateTime.now();

        for (AiProposalItem item : items) {
            if (excludedIds.contains(item.getProposalItemId())) {
                aiProposalItemMapper.updateAfterApply(
                        item.getProposalItemId(), userId, AiProposalItemStatus.DISMISSED,
                        null, null, null, respondedAt);
                responses.add(toDismissedResponse(item));
                continue;
            }

            ProposalItemPayload original = fromJson(item.getOriginalPayload());
            AiProposalApplyRequest.EditedProposalItem edit = editedById.get(item.getProposalItemId());

            // 기존 조각을 바꾸는 제안은 새 조각을 만들지 않는다 — 대상 항목에 실제 도메인
            // 액션(줄이기/이동/보류)을 건다. 아래 CREATE 경로와 완전히 분리해 두어야, 조정
            // 제안이 실수로 "새 항목 추가"가 되는 일이 생기지 않는다.
            if (original.isAdjustment()) {
                AiProposalItemResponse adjusted = applyAdjustment(userId, item, original, edit, respondedAt);
                anyModified = anyModified || Boolean.TRUE.equals(adjusted.getModified());
                responses.add(adjusted);
                continue;
            }

            String title = original.title();
            String description = original.description();
            Integer expectedMinutes = original.expectedMinutes();
            String priority = original.priority();
            PlacementType placementType = original.placementType();
            LocalDateTime scheduledStartAt = original.scheduledStartAt();
            LocalDateTime scheduledEndAt = original.scheduledEndAt();
            LocalDate scheduledDate = placementType == PlacementType.TIME_FIXED && scheduledStartAt != null
                    ? scheduledStartAt.toLocalDate() : original.targetDate();
            boolean modified = false;

            if (edit != null) {
                String newTitle = edit.getTitle() != null ? edit.getTitle() : original.title();
                String newDescription = edit.getDescription() != null ? edit.getDescription() : original.description();
                Integer newMinutes = edit.getExpectedMinutes() != null ? edit.getExpectedMinutes() : original.expectedMinutes();
                String newPriority = edit.getPriority() != null ? edit.getPriority() : original.priority();
                PlacementType newPlacementType = edit.getPlacementType() != null ? edit.getPlacementType() : original.placementType();
                LocalDateTime newStart = edit.getPlacementType() != null ? edit.getScheduledStartAt() : original.scheduledStartAt();
                LocalDateTime newEnd = edit.getPlacementType() != null ? edit.getScheduledEndAt() : original.scheduledEndAt();
                // scheduledDate: 편집에서 명시했으면 그 값을, 아니면 TIME_FIXED 시각의 날짜를,
                // 그마저 없으면(UNSCHEDULED 등) 원래 날짜를 그대로 쓴다. 7일 범위 배치 미리보기는
                // 항목마다 다른 날짜를 편집으로 함께 보낸다 — 하나의 targetDate를 모든 항목에
                // 강제하던 기존 방식으로는 다른 날짜에 배치된 항목을 적용할 수 없었다.
                LocalDate newScheduledDate = edit.getScheduledDate() != null
                        ? edit.getScheduledDate()
                        : (newPlacementType == PlacementType.TIME_FIXED && newStart != null
                                ? newStart.toLocalDate() : original.targetDate());

                if (newTitle == null || newTitle.isBlank()) {
                    throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
                }
                if (newMinutes == null || newMinutes <= 0) {
                    throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
                }
                if (!VALID_PRIORITIES.contains(newPriority)) {
                    throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
                }
                // placementType/시각 무결성은 execution_items 생성 시 validatePlacement가 최종 확인한다.

                modified = !Objects.equals(newTitle, original.title())
                        || !Objects.equals(newDescription, original.description())
                        || !Objects.equals(newMinutes, original.expectedMinutes())
                        || !Objects.equals(newPriority, original.priority())
                        || !Objects.equals(newPlacementType, original.placementType())
                        || !Objects.equals(newStart, original.scheduledStartAt())
                        || !Objects.equals(newEnd, original.scheduledEndAt())
                        || !Objects.equals(newScheduledDate, scheduledDate);

                title = newTitle;
                description = newDescription;
                expectedMinutes = newMinutes;
                priority = newPriority;
                placementType = newPlacementType;
                scheduledStartAt = newStart;
                scheduledEndAt = newEnd;
                scheduledDate = newScheduledDate;
            }

            anyModified = anyModified || modified;

            // UNSCHEDULED는 날짜·시각을 갖지 않는다(REQ-EXECUTION-002) — 원본 payload의
            // 임시 targetDate나 편집값에 남아있을 수 있는 날짜를 여기서 확실히 비운다.
            if (placementType == PlacementType.UNSCHEDULED) {
                scheduledDate = null;
                scheduledStartAt = null;
                scheduledEndAt = null;
            }

            // TIME_FIXED의 길이를 여기서 다시 계산하지 않는다 — 편집으로 시각만 바꿔도
            // createFromApprovedProposal이 PlacementDuration으로 맞춰 저장한다.

            /*
             * 적용 시점에 다시 본다. 초안을 만든 뒤 자정이 지나 적용하는 경우가 있고, 그때는
             * 생성 시점 검사를 통과한 날짜가 이미 과거다. 여기서 조용히 당기지 않고 막는다 —
             * 사용자가 승인한 것은 "그 날짜"이지 "오늘"이 아니다.
             *
             * 계약 위반(503)이 아니라 400이다. 모델이 잘못한 것이 아니라 시간이 지난 것이고,
             * 사용자가 할 일은 재시도가 아니라 기간을 다시 잡는 것이다.
             */
            if (placementType != PlacementType.UNSCHEDULED
                    && scheduledDate != null && scheduledDate.isBefore(placementFloor())) {
                log.warn("제안 적용 차단: 항목 '{}'의 날짜({})가 이미 지났음(오늘={}, proposalId={})",
                        title, scheduledDate, placementFloor(), proposalId);
                throw new BadRequestException(ErrorCode.PLAN_PLACEMENT_IN_PAST);
            }

            int orderIndex = nextOrderIndexByDate.computeIfAbsent(
                    scheduledDate, d -> executionItemService.nextOrderIndexStart(userId, d));
            nextOrderIndexByDate.put(scheduledDate, orderIndex + 1);

            ExecutionItem createdItem = executionItemService.createFromApprovedProposal(
                    userId, title, description, scheduledDate,
                    expectedMinutes, ExecutionPriority.valueOf(priority), orderIndex, modified,
                    placementType, scheduledStartAt, scheduledEndAt);

            // 프로젝트 안에서 나눈 대화에서 나온 제안이면 그 프로젝트를 새 조각에 연결한다 —
            // 이래야 프로젝트 화면의 "관련 실행"과 오늘 화면의 프로젝트 표시가 성립한다.
            // 항목이 스스로 프로젝트를 아는 경우(기간 계획)는 그것을 쓰고, 아니면 기존처럼
            // 제안이 달린 대화의 프로젝트를 따른다. 한 제안에 여러 프로젝트가 섞이는 것은
            // 계획 경로에서만 생기므로 기존 흐름의 동작은 바뀌지 않는다.
            Long itemCourseId = original.courseId() != null ? original.courseId() : proposalCourseId;
            if (itemCourseId != null) {
                executionItemService.linkCourse(createdItem.getExecutionItemId(), userId, itemCourseId);
            }

            AiProposalItemStatus newStatus = modified
                    ? AiProposalItemStatus.MODIFIED_APPLIED
                    : AiProposalItemStatus.APPLIED;
            String editedJson = modified
                    ? toJson(ProposalItemPayload.create(title, description, expectedMinutes, priority,
                            scheduledDate, placementType, scheduledStartAt, scheduledEndAt, null, null,
                            original.courseId()))
                    : null;

            aiProposalItemMapper.updateAfterApply(
                    item.getProposalItemId(), userId, newStatus, editedJson,
                    AiProposalItemType.EXECUTION_ITEM.name(), createdItem.getExecutionItemId(), respondedAt);

            responses.add(AiProposalItemResponse.builder()
                    .proposalItemId(item.getProposalItemId())
                    .status(newStatus)
                    .title(title)
                    .description(description)
                    .expectedMinutes(expectedMinutes)
                    .priority(priority)
                    .targetDate(scheduledDate)
                    .placementType(placementType)
                    .courseId(original.courseId())
                    .scheduledStartAt(scheduledStartAt)
                    .scheduledEndAt(scheduledEndAt)
                    .modified(modified)
                    .createdItemId(createdItem.getExecutionItemId())
                    .build());
        }

        AiProposalStatus headerStatus = anyModified ? AiProposalStatus.MODIFIED_APPLIED : AiProposalStatus.APPLIED;
        aiProposalMapper.updateStatusAndRespondedAt(proposalId, userId, headerStatus, respondedAt);

        log.info("AI 제안 적용 완료: proposalId={}, userId={}, status={}", proposalId, userId, headerStatus);

        return AiProposalResponse.builder()
                .proposalId(proposalId)
                .targetScope(proposal.getTargetScope())
                .status(headerStatus)
                .createdAt(proposal.getCreatedAt())
                .expiresAt(proposal.getExpiresAt())
                .respondedAt(respondedAt)
                .items(responses)
                .build();
    }

    /**
     * 기존 실행 조각을 바꾸는 제안 하나를 실제로 적용한다.
     *
     * 새 조각을 만들지 않고 execution_items의 기존 도메인 액션(reduce/move/hold)을 그대로
     * 호출한다 — 이벤트 로그·낙관적 락·상태 전이 규칙을 우회하는 별도 경로를 만들지 않기 위해서다.
     * 제안을 만든 뒤 사용자가 그 조각을 직접 고쳤다면 base_version이 어긋나 409로 막힌다.
     *
     * 사용자가 미리보기에서 값을 고쳤으면(edit) 그 값을 우선한다 — REDUCE는 분량/제목,
     * MOVE는 날짜를 고칠 수 있다.
     */
    private AiProposalItemResponse applyAdjustment(
            Long userId, AiProposalItem item, ProposalItemPayload original,
            AiProposalApplyRequest.EditedProposalItem edit, LocalDateTime respondedAt
    ) {
        ProposalOperation operation = original.effectiveOperation();
        Long targetId = original.targetExecutionItemId();
        Long baseVersion = original.targetBaseVersion();
        if (targetId == null || baseVersion == null) {
            throw new BadRequestException(ErrorCode.INVALID_PROPOSAL_ITEM_SELECTION);
        }

        String title = edit != null && edit.getTitle() != null ? edit.getTitle() : original.title();
        Integer expectedMinutes = edit != null && edit.getExpectedMinutes() != null
                ? edit.getExpectedMinutes() : original.expectedMinutes();
        LocalDate targetDate = edit != null && edit.getScheduledDate() != null
                ? edit.getScheduledDate() : original.targetDate();
        // 사용자가 미리보기에서 시각을 직접 고쳤으면 그 값을, 아니면 제안이 담고 있던 시각을 쓴다.
        // 둘 다 없으면 null이고, 그때 move는 예전처럼 날짜만 옮긴다.
        LocalDateTime startAt = edit != null && edit.getScheduledStartAt() != null
                ? edit.getScheduledStartAt() : original.scheduledStartAt();
        LocalDateTime endAt = edit != null && edit.getScheduledEndAt() != null
                ? edit.getScheduledEndAt() : original.scheduledEndAt();

        boolean modified = !Objects.equals(title, original.title())
                || !Objects.equals(expectedMinutes, original.expectedMinutes())
                || !Objects.equals(targetDate, original.targetDate())
                || !Objects.equals(startAt, original.scheduledStartAt())
                || !Objects.equals(endAt, original.scheduledEndAt());

        switch (operation) {
            case REDUCE -> executionItemService.reduce(targetId, userId, ExecutionItemReduceRequest.builder()
                    .reducedTitle(Objects.equals(title, original.beforeTitle()) ? null : title)
                    .expectedMinutes(expectedMinutes)
                    .reason("AI 제안 적용" + (original.reason() != null ? ": " + original.reason() : ""))
                    .version(baseVersion)
                    .build());
            case MOVE -> executionItemService.move(targetId, userId, ExecutionItemMoveRequest.builder()
                    .toDate(targetDate)
                    .startTime(startAt != null ? startAt.toLocalTime() : null)
                    .endTime(endAt != null ? endAt.toLocalTime() : null)
                    .version(baseVersion)
                    .reason("AI 제안 적용" + (original.reason() != null ? ": " + original.reason() : ""))
                    .build());
            case DROP -> executionItemService.hold(targetId, userId, ExecutionItemHoldRequest.builder()
                    .version(baseVersion)
                    .reason("AI 제안 적용" + (original.reason() != null ? ": " + original.reason() : ""))
                    .build());
            case CREATE -> throw new IllegalStateException("CREATE는 조정 경로로 오지 않는다");
        }

        AiProposalItemStatus newStatus = modified
                ? AiProposalItemStatus.MODIFIED_APPLIED : AiProposalItemStatus.APPLIED;
        String editedJson = modified
                ? toJson(ProposalItemPayload.adjust(operation, targetId, baseVersion, title, expectedMinutes,
                        original.priority(), targetDate, startAt, endAt, original.beforeTitle(),
                        original.beforeExpectedMinutes(), original.beforeScheduledDate(), original.reason()))
                : null;

        // created_item_id에는 "이 제안이 만든 새 항목"이 아니라 "바뀐 대상 항목"을 남긴다 —
        // 어느 실행 조각이 이 제안으로 변경됐는지 추적할 수 있어야 하기 때문이다.
        aiProposalItemMapper.updateAfterApply(item.getProposalItemId(), userId, newStatus, editedJson,
                AiProposalItemType.EXECUTION_ITEM.name(), targetId, respondedAt);

        log.info("AI 조정 제안 적용: proposalItemId={}, operation={}, targetExecutionItemId={}",
                item.getProposalItemId(), operation, targetId);

        return AiProposalItemResponse.builder()
                .proposalItemId(item.getProposalItemId())
                .status(newStatus)
                .title(title)
                .expectedMinutes(expectedMinutes)
                .priority(original.priority())
                .targetDate(targetDate)
                .scheduledStartAt(startAt)
                .scheduledEndAt(endAt)
                .modified(modified)
                .createdItemId(targetId)
                .operation(operation)
                .targetExecutionItemId(targetId)
                .beforeTitle(original.beforeTitle())
                .beforeExpectedMinutes(original.beforeExpectedMinutes())
                .beforeScheduledDate(original.beforeScheduledDate())
                .reason(original.reason())
                .build();
    }

    /** 이 제안을 만든 대화가 프로젝트에 속해 있으면 그 프로젝트. 아니면 null. */
    private Long resolveCourseId(AiProposal proposal, Long userId) {
        if (proposal.getConversationId() == null) {
            return null;
        }
        AiConversation conversation = aiConversationMapper.findByIdAndUserId(proposal.getConversationId(), userId);
        return conversation != null ? conversation.getCourseId() : null;
    }

    private void validateExcludedIds(Set<Long> excludedIds, List<AiProposalItem> items) {
        if (excludedIds.isEmpty()) {
            return;
        }
        Set<Long> validIds = new HashSet<>();
        for (AiProposalItem item : items) {
            validIds.add(item.getProposalItemId());
        }
        for (Long excludedId : excludedIds) {
            if (!validIds.contains(excludedId)) {
                throw new BadRequestException(ErrorCode.INVALID_PROPOSAL_ITEM_SELECTION);
            }
        }
    }

    private AiProposalItemResponse toDismissedResponse(AiProposalItem item) {
        ProposalItemPayload payload = fromJson(item.getOriginalPayload());
        return AiProposalItemResponse.builder()
                .proposalItemId(item.getProposalItemId())
                .status(AiProposalItemStatus.DISMISSED)
                .title(payload.title())
                .description(payload.description())
                .expectedMinutes(payload.expectedMinutes())
                .priority(payload.priority())
                .targetDate(payload.targetDate())
                .placementType(payload.placementType())
                .courseId(payload.courseId())
                .scheduledStartAt(payload.scheduledStartAt())
                .scheduledEndAt(payload.scheduledEndAt())
                .modified(false)
                .createdItemId(null)
                .operation(payload.effectiveOperation())
                .targetExecutionItemId(payload.targetExecutionItemId())
                .beforeTitle(payload.beforeTitle())
                .beforeExpectedMinutes(payload.beforeExpectedMinutes())
                .beforeScheduledDate(payload.beforeScheduledDate())
                .reason(payload.reason())
                .build();
    }

    private Map<Long, AiProposalApplyRequest.EditedProposalItem> indexAndValidateEditedItems(
            AiProposalApplyRequest request, List<AiProposalItem> items
    ) {
        Map<Long, AiProposalApplyRequest.EditedProposalItem> editedById = new HashMap<>();
        if (request.getEditedItems() == null || request.getEditedItems().isEmpty()) {
            return editedById;
        }

        Set<Long> validIds = new HashSet<>();
        for (AiProposalItem item : items) {
            validIds.add(item.getProposalItemId());
        }

        Set<Long> seen = new HashSet<>();
        for (AiProposalApplyRequest.EditedProposalItem edit : request.getEditedItems()) {
            if (edit.getProposalItemId() == null || !validIds.contains(edit.getProposalItemId())) {
                throw new BadRequestException(ErrorCode.INVALID_PROPOSAL_ITEM_SELECTION);
            }
            if (!seen.add(edit.getProposalItemId())) {
                throw new BadRequestException(ErrorCode.INVALID_PROPOSAL_ITEM_SELECTION);
            }
            editedById.put(edit.getProposalItemId(), edit);
        }
        return editedById;
    }

    private AiProposalItemResponse toItemResponse(AiProposalItem item) {
        ProposalItemPayload effective = item.getEditedPayload() != null
                ? fromJson(item.getEditedPayload())
                : fromJson(item.getOriginalPayload());

        return AiProposalItemResponse.builder()
                .proposalItemId(item.getProposalItemId())
                .status(item.getStatus())
                .title(effective.title())
                .description(effective.description())
                .expectedMinutes(effective.expectedMinutes())
                .priority(effective.priority())
                .targetDate(effective.targetDate())
                .placementType(effective.placementType())
                .courseId(effective.courseId())
                .scheduledStartAt(effective.scheduledStartAt())
                .scheduledEndAt(effective.scheduledEndAt())
                .modified(item.getEditedPayload() != null)
                .createdItemId(item.getCreatedItemId())
                .operation(effective.effectiveOperation())
                .targetExecutionItemId(effective.targetExecutionItemId())
                .beforeTitle(effective.beforeTitle())
                .beforeExpectedMinutes(effective.beforeExpectedMinutes())
                .beforeScheduledDate(effective.beforeScheduledDate())
                .reason(effective.reason())
                .build();
    }

    private String toJson(ProposalItemPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("제안 payload 직렬화 실패", e);
        }
    }

    private ProposalItemPayload fromJson(String json) {
        try {
            return objectMapper.readValue(json, ProposalItemPayload.class);
        } catch (Exception e) {
            throw new IllegalStateException("제안 payload 역직렬화 실패", e);
        }
    }
}
