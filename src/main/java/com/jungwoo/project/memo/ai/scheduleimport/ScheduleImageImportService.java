package com.jungwoo.project.memo.ai.scheduleimport;

import com.jungwoo.project.memo.ai.AiConversationMapper;
import com.jungwoo.project.memo.ai.domain.AiConversation;
import com.jungwoo.project.memo.ai.scheduleimport.dto.RawScheduleTable;
import com.jungwoo.project.memo.ai.scheduleimport.dto.ScheduleExtractionResponse;
import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.user.UserMapper;
import com.jungwoo.project.memo.user.domain.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

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
    private final AiConversationMapper aiConversationMapper;
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
