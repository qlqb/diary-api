package com.jungwoo.project.memo.commitment;

import com.jungwoo.project.memo.commitment.domain.Commitment;
import com.jungwoo.project.memo.commitment.domain.CommitmentSourceType;
import com.jungwoo.project.memo.commitment.dto.CommitmentCreateRequest;
import com.jungwoo.project.memo.commitment.dto.CommitmentResponse;
import com.jungwoo.project.memo.commitment.dto.CommitmentUpdateRequest;
import com.jungwoo.project.memo.common.exception.BadRequestException;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.common.time.MinutePrecision;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 일회성 약속 CRUD.
 *
 * <p><b>수행 대상이 아니다.</b> 완료·일부·축소·보류가 없고 ExecutionRecord도 만들지 않는다.
 * 이 서비스가 하는 일은 "그 시간은 못 쓴다"는 사실을 저장하고 돌려주는 것뿐이다.
 *
 * <p><b>생성 경로는 여기 하나다.</b> 직접 추가와 AI 후보 승인이 같은
 * {@link #create(Long, CommitmentCreateRequest, CommitmentSourceType)}을 부른다. 검증을
 * 두 벌 두면 한쪽만 고쳐져 "화면으로는 못 만드는 값이 AI로는 들어가는" 상태가 된다.
 * 다른 것은 출처 하나뿐이라 그것만 인자로 받는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommitmentService {

    private final CommitmentMapper commitmentMapper;

    /**
     * 기간 조회. 겹치는 것을 전부 돌려준다 — 시작 시각이 범위 밖이어도 구간이 걸치면 포함이다.
     *
     * @param from 첫 날(포함)
     * @param to   마지막 날(포함)
     */
    @Transactional(readOnly = true)
    public List<CommitmentResponse> list(Long userId, LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            return List.of();
        }
        List<CommitmentResponse> responses = new ArrayList<>();
        for (Commitment commitment : findOverlapping(userId, from, to)) {
            responses.add(CommitmentResponse.of(commitment));
        }
        return responses;
    }

    /**
     * 가용시간 계산이 쓰는 원본 조회. 응답 DTO로 감싸지 않는다 — 같은 창을 두 번 계산하지
     * 않도록 도메인 그대로 준다.
     */
    @Transactional(readOnly = true)
    public List<Commitment> findOverlapping(Long userId, LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            return List.of();
        }
        return commitmentMapper.findOverlapping(
                userId, from.atStartOfDay(), to.plusDays(1).atStartOfDay());
    }

    @Transactional
    public CommitmentResponse create(Long userId, CommitmentCreateRequest request,
                                     CommitmentSourceType sourceType) {
        validateRange(request.getStartAt(), request.getEndAt());

        Commitment commitment = Commitment.builder()
                .userId(userId)
                .title(request.getTitle().trim())
                .startAt(request.getStartAt())
                .endAt(request.getEndAt())
                .locationText(blankToNull(request.getLocationText()))
                .sourceType(sourceType)
                .build();
        commitmentMapper.insert(commitment);
        commitment.setVersion(0L);

        log.info("약속 생성: userId={}, commitmentId={}, title={}, {} ~ {}, source={}",
                userId, commitment.getCommitmentId(), commitment.getTitle(),
                commitment.getStartAt(), commitment.getEndAt(), sourceType);
        return CommitmentResponse.of(commitment);
    }

    /** 전체 교체. 출처는 바꾸지 않는다 — 어디서 만들어졌는지는 나중에 바뀌는 사실이 아니다. */
    @Transactional
    public CommitmentResponse update(Long userId, Long commitmentId, CommitmentUpdateRequest request) {
        Commitment existing = require(userId, commitmentId);
        validateRange(request.getStartAt(), request.getEndAt());

        int updated = commitmentMapper.updateWithVersion(
                commitmentId, userId, request.getVersion(),
                request.getTitle().trim(), request.getStartAt(), request.getEndAt(),
                blankToNull(request.getLocationText()));
        if (updated != 1) {
            throw new ConflictException(ErrorCode.VERSION_CONFLICT);
        }

        existing.setTitle(request.getTitle().trim());
        existing.setStartAt(request.getStartAt());
        existing.setEndAt(request.getEndAt());
        existing.setLocationText(blankToNull(request.getLocationText()));
        existing.setVersion(request.getVersion() + 1);
        return CommitmentResponse.of(existing);
    }

    @Transactional
    public void delete(Long userId, Long commitmentId, Long version) {
        require(userId, commitmentId);
        if (version == null) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }
        int deleted = commitmentMapper.softDeleteWithVersion(commitmentId, userId, version);
        if (deleted != 1) {
            throw new ConflictException(ErrorCode.VERSION_CONFLICT);
        }
        log.info("약속 삭제: userId={}, commitmentId={}", userId, commitmentId);
    }

    /**
     * 존재 + 소유권. FK가 없으므로 이 확인이 유일한 방어선이다. 남의 약속을 조회할 때
     * 403이 아니라 404를 준다 — 403은 "그 id는 있다"는 사실을 알려준다.
     */
    private Commitment require(Long userId, Long commitmentId) {
        Commitment commitment = commitmentMapper.findByIdAndUserId(commitmentId, userId);
        if (commitment == null) {
            throw new NotFoundException(ErrorCode.COMMITMENT_NOT_FOUND);
        }
        return commitment;
    }

    /**
     * 구간 검증.
     *
     * <p>루틴과 달리 자정 넘김 추론이 없다. 시각이 LocalDateTime이라 22:00~다음날 02:00은
     * 그냥 start &lt; end다. 그래서 길이 0(같은 시각)과 역전이 이 한 조건으로 함께 막힌다.
     *
     * <p>분 단위 판정은 다른 시각 입력과 같은 규칙을 쓴다 — 30초짜리 약속을 만들 수 있으면
     * 화면에 보이는 값과 저장된 값이 어긋난다.
     */
    private void validateRange(LocalDateTime startAt, LocalDateTime endAt) {
        if (startAt == null || endAt == null || !startAt.isBefore(endAt)) {
            throw new BadRequestException(ErrorCode.INVALID_TIME_RANGE);
        }
        if (!MinutePrecision.isMinutePrecision(startAt) || !MinutePrecision.isMinutePrecision(endAt)) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT_VALUE);
        }
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
