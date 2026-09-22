package com.jungwoo.project.memo.ai.scheduleimport;

import com.jungwoo.project.memo.ai.AiConversationMapper;
import com.jungwoo.project.memo.ai.AiMessageMapper;
import com.jungwoo.project.memo.ai.ScheduleSuggestionService;
import com.jungwoo.project.memo.ai.domain.AiConversation;
import com.jungwoo.project.memo.ai.domain.AiMessage;
import com.jungwoo.project.memo.ai.domain.AiResponseType;
import com.jungwoo.project.memo.ai.domain.MessageRole;
import com.jungwoo.project.memo.ai.domain.MessageStatus;
import com.jungwoo.project.memo.ai.dto.ScheduleSuggestionResponse;
import com.jungwoo.project.memo.ai.scheduleimport.dto.RawScheduleTable;
import com.jungwoo.project.memo.ai.scheduleimport.dto.ScheduleExtractionResponse;
import com.jungwoo.project.memo.ai.scheduleimport.dto.ScheduleExtractionResponse.CellView;
import com.jungwoo.project.memo.ai.scheduleimport.dto.ScheduleExtractionResponse.RowView;
import com.jungwoo.project.memo.ai.scheduleimport.dto.ScheduleImageConfirmRequest;
import com.jungwoo.project.memo.commitment.dto.CommitmentCreateRequest;
import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.common.exception.ServiceUnavailableException;
import com.jungwoo.project.memo.user.UserMapper;
import com.jungwoo.project.memo.user.domain.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 이미지에서 일정을 가져온다.
 *
 * <p>두 단계로 나뉜다. {@code extract}는 이미지를 읽어 해석 결과만 돌려주고 <b>DB에 아무것도
 * 쓰지 않는다.</b> {@code confirm}이 사용자의 선택을 받아 한 트랜잭션에서 저장한다.
 *
 * <p>나눈 이유는 중간에 사람이 개입하기 때문이다 — 어느 줄이 본인인지, 어느 주인지, 모르는
 * 코드가 몇 시인지. 그 사이의 상태를 DB에 임시로 두면 "확정되지 않은 일정"이라는 상태가
 * 하나 더 생기고, 그것을 언제 지울지가 새 문제가 된다. 대신 클라이언트가 들고 있다가
 * 되돌려주고, 서버는 돌아온 값을 신뢰하지 않고 처음부터 다시 파싱한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleImageImportService {

    private final ScheduleImageVisionClient visionClient;
    private final ScheduleSuggestionService scheduleSuggestionService;
    private final AiConversationMapper aiConversationMapper;
    private final AiMessageMapper aiMessageMapper;
    private final UserMapper userMapper;
    private final Clock clock;

    /**
     * 이미지를 읽어 해석한다. <b>DB 쓰기 0건.</b>
     *
     * <p>모델 호출이 수십 초 걸릴 수 있어 트랜잭션을 열지 않는다 — 열면 그 시간 내내 커넥션을
     * 붙잡는다. 어차피 쓸 것이 없다.
     */
    public ScheduleExtractionResponse extract(Long userId, Long conversationId,
                                              byte[] image, String contentType) {
        requireOwnedConversation(conversationId, userId);

        RawScheduleTable raw = visionClient.read(image, contentType);
        if (!raw.isScheduleTable() || raw.safeRows().isEmpty()) {
            // 실패가 아니라 "이건 일정표가 아니다"라는 답이다. 사용자에게 그대로 말한다.
            log.info("일정 이미지: 표를 찾지 못함. userId={}, conversationId={}", userId, conversationId);
            throw new BadRequestException(ErrorCode.NOT_A_SCHEDULE_IMAGE);
        }

        ScheduleExtractionResponse result = ScheduleTableInterpreter.interpret(raw, today(), displayName(userId));
        log.info("일정 이미지 해석: userId={}, 행={}개, 기간={}, 모르는 코드={}건, 요일 불일치={}",
                userId, result.rows().size(),
                result.resolvedPeriod() == null ? "없음" : result.resolvedPeriod().startDate(),
                result.unresolvedCodes().size(), result.periodWeekdayMismatch());
        return result;
    }

    /**
     * 사용자가 고른 행을 일정 후보로 만든다. 실제 약속은 아직 생기지 않는다 —
     * {@code /api/ai/schedule-suggestions/{id}/apply}를 눌러야 만들어진다.
     *
     * <p>★ 표 원문만 받아 <b>처음부터 다시 파싱한다.</b> 요청에는 셀 해석 결과를 담는 필드가
     * 아예 없다({@link ScheduleImageConfirmRequest}). 화면이 계산한 시간을 저장하면 화면
     * 코드가 바뀔 때 달력에 들어가는 값이 조용히 달라지고, 그때 서버 로그에는 아무 흔적도
     * 남지 않는다.
     *
     * <p>모델을 부르지 않으므로 트랜잭션 안이다. 대화방 잠금도 잡지 않는다 — 그 잠금은 모델
     * 호출을 직렬화하려고 있는 것이고 여기는 DB 쓰기뿐이다.
     */
    @Transactional
    public List<ScheduleSuggestionResponse> confirm(Long userId, Long conversationId,
                                                    ScheduleImageConfirmRequest request) {
        requireOwnedConversation(conversationId, userId);

        List<ScheduleSuggestionResponse> replayed = replayIfAlreadyDone(userId, request.getIdempotencyKey());
        if (replayed != null) {
            log.info("일정 이미지 확정 재요청: 같은 키로 이미 만든 후보를 그대로 돌려준다. userId={}", userId);
            return replayed;
        }

        RowView row = pickRow(request, userId);
        List<CommitmentCreateRequest> commitments = toCommitments(row, request.getTitle().trim());
        if (commitments.isEmpty()) {
            // 전부 휴무인 주. 오류가 아니다 — 만들 것이 없을 뿐이다.
            log.info("일정 이미지 확정: 근무 칸이 없어 후보를 만들지 않았다. userId={}", userId);
        }

        Long assistantMessageId = recordTurn(userId, conversationId, request, commitments.size());
        List<ScheduleSuggestionResponse> created = scheduleSuggestionService.createFromImport(
                userId, conversationId, assistantMessageId, commitments);

        log.info("일정 이미지 확정: userId={}, conversationId={}, 후보={}건, 시작일={}",
                userId, conversationId, created.size(), request.getPeriodStartDate());
        return created;
    }

    // ===== 확정 내부 =====

    /**
     * 같은 키로 이미 만든 적이 있으면 그때 만든 후보를 그대로 돌려준다.
     *
     * <p>없으면 null. 이 경로에는 모델 호출이 없어 "처리 중"이 존재하지 않으므로, 대화 턴의
     * PROCESSING/FAILED 분기({@code AiTurnLifecycleService})가 필요 없다. 한 트랜잭션 안에서
     * 끝나거나 통째로 롤백되거나 둘 중 하나다.
     */
    private List<ScheduleSuggestionResponse> replayIfAlreadyDone(Long userId, String idempotencyKey) {
        AiMessage existing = aiMessageMapper.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
        if (existing == null) {
            return null;
        }
        AiMessage reply = aiMessageMapper.findByReplyToMessageIdAndUserId(existing.getMessageId(), userId);
        if (reply == null) {
            /*
             * USER 메시지는 있는데 ASSISTANT가 없다. 이 경로는 둘을 한 트랜잭션에서 쓰므로
             * 정상적으로는 나올 수 없고, 나왔다면 같은 키가 대화 턴 쪽에서 쓰인 것이다.
             * 후보를 두 벌 만들지 않도록 막는다.
             */
            log.warn("일정 이미지 확정: idempotencyKey가 다른 경로에서 이미 쓰였다. userId={}", userId);
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }
        return scheduleSuggestionService.findBySourceMessageId(reply.getMessageId(), userId);
    }

    /**
     * 사용자가 고른 행을 다시 해석한다.
     *
     * <p>행 번호는 사용자가 보낸다. 이름으로 서버가 짐작할 수는 있어도 확정은 사용자가 한다 —
     * 잘못 고르면 남의 근무가 내 달력에 들어온다.
     */
    private RowView pickRow(ScheduleImageConfirmRequest request, Long userId) {
        ScheduleExtractionResponse result = ScheduleTableInterpreter.interpret(
                request.getRaw(), today(), null,
                request.getPeriodStartDate(), request.getLegendOverrides());

        int index = request.getRowIndex();
        if (index < 0 || index >= result.rows().size()) {
            throw new BadRequestException(ErrorCode.SCHEDULE_IMPORT_ROW_REQUIRED);
        }
        RowView row = result.rows().get(index);
        if (row.rowInvalid()) {
            // 셀 수가 열 수와 달라 어느 칸이 어느 날인지 셀 수 없는 행이다.
            throw new BadRequestException(ErrorCode.SCHEDULE_IMPORT_ROW_REQUIRED);
        }

        /*
         * 모르는 코드가 이 행에 남아 있으면 만들지 않는다. 표 전체가 아니라 이 행만 본다 —
         * 남의 행에 있는 모르는 코드 때문에 내 일정을 못 만들 이유가 없다.
         */
        List<String> unresolved = row.cells().stream()
                .filter(cell -> "UNRESOLVED".equals(cell.kind()))
                .map(CellView::raw)
                .distinct()
                .toList();
        if (!unresolved.isEmpty()) {
            log.info("일정 이미지 확정 보류: 모르는 코드가 남아 있다. userId={}, 코드={}", userId, unresolved);
            throw new BadRequestException(ErrorCode.UNRESOLVED_CELLS_REMAIN);
        }
        return row;
    }

    /**
     * 근무 칸을 약속 생성 요청으로 바꾼다.
     *
     * <p>{@code D/O}는 아무것도 만들지 않는다. 빈 시간이 곧 휴무이고, "휴무"라는 일정을 넣으면
     * 그 시간이 무언가로 차 있는 것처럼 보여 가용시간 계산이 반대로 뒤집힌다.
     */
    private List<CommitmentCreateRequest> toCommitments(RowView row, String title) {
        List<CommitmentCreateRequest> commitments = new ArrayList<>();
        for (CellView cell : row.cells()) {
            if (!"WORK".equals(cell.kind()) || cell.date() == null) {
                continue;
            }
            // 자정을 넘는 근무(15~00, 17~02)는 종료가 다음 날이다. 한 날짜에 밀어 넣으면
            // 종료가 시작보다 이른 약속이 되어 도메인 검증에서 튕긴다.
            LocalDate endDate = cell.crossesMidnight() ? cell.date().plusDays(1) : cell.date();
            commitments.add(CommitmentCreateRequest.builder()
                    .title(cell.code() == null ? title : title + " (" + cell.code() + ")")
                    .startAt(LocalDateTime.of(cell.date(), cell.start()))
                    .endAt(LocalDateTime.of(endDate, cell.end()))
                    .build());
        }
        return commitments;
    }

    /**
     * 이 확정을 대화에 남긴다. USER 메시지가 idempotencyKey를 들고, ASSISTANT 메시지가
     * 후보들의 출처가 된다.
     *
     * <p>대화 기록에 남기는 이유는 후보 카드가 대화 화면에 뜨기 때문이다. 출처 메시지가
     * 없으면 카드가 매달릴 곳이 없고, 대화를 다시 열었을 때 복원되지도 않는다.
     */
    private Long recordTurn(Long userId, Long conversationId,
                            ScheduleImageConfirmRequest request, int count) {
        AiMessage userMessage = AiMessage.builder()
                .conversationId(conversationId)
                .userId(userId)
                .role(MessageRole.USER)
                .content("근무표 이미지에서 일정을 가져왔습니다.")
                .idempotencyKey(request.getIdempotencyKey())
                .status(MessageStatus.COMPLETED)
                .build();
        aiMessageMapper.insert(userMessage);

        AiMessage assistantMessage = AiMessage.builder()
                .conversationId(conversationId)
                .userId(userId)
                .role(MessageRole.ASSISTANT)
                .content(count == 0
                        ? "표에서 근무가 잡힌 날이 없었습니다."
                        : "표에서 %d개의 일정을 찾았습니다. 확인하고 적용해 주세요.".formatted(count))
                .responseType(AiResponseType.CHAT)
                .replyToMessageId(userMessage.getMessageId())
                .status(MessageStatus.COMPLETED)
                .build();
        aiMessageMapper.insert(assistantMessage);

        if (assistantMessage.getMessageId() == null) {
            // insert가 키를 돌려주지 않으면 후보의 출처가 비게 된다. 조용히 넘기면 카드가
            // 대화에 매달리지 못하고 복원도 안 된다.
            log.error("일정 이미지 확정: ASSISTANT 메시지 id를 받지 못했다. userId={}", userId);
            throw new ServiceUnavailableException(ErrorCode.AI_GENERATION_FAILED);
        }
        return assistantMessage.getMessageId();
    }

    // ===== 공통 =====

    /**
     * 본인 행을 미리 골라 볼 이름. 없으면 null이고, 그러면 행을 항상 사용자가 고른다.
     *
     * <p>nickname은 로그인 표시명이라 근무표의 실명과 다를 수 있다. 그래서 자동 매칭은
     * "미리 선택"일 뿐이고 화면은 언제나 전체 목록을 보여준다.
     */
    private String displayName(Long userId) {
        User user = userMapper.findById(userId);
        return user == null ? null : user.getNickname();
    }

    private LocalDate today() {
        return ZonedDateTime.now(clock).toLocalDate();
    }

    private AiConversation requireOwnedConversation(Long conversationId, Long userId) {
        AiConversation conversation = aiConversationMapper.findByIdAndUserId(conversationId, userId);
        if (conversation == null) {
            throw new NotFoundException(ErrorCode.ENTITY_NOT_FOUND);
        }
        return conversation;
    }
}
