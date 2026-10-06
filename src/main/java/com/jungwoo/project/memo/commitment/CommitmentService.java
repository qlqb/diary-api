package com.jungwoo.project.memo.commitment;

import com.jungwoo.project.memo.commitment.domain.Commitment;
import com.jungwoo.project.memo.commitment.domain.CommitmentSourceType;
import com.jungwoo.project.memo.commitment.domain.DerivedTravelRelation;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
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

    /**
     * 근무 후보 조회 — 시작 날짜가 기간 안인 약속. {@link #findOverlapping}과 목적이 다르다.
     *
     * <p>겹침 조회를 그대로 쓰면 "전날 밤에 시작해 오늘 새벽에 끝나는 근무"가 오늘 기간의
     * 근무로 딸려 온다. 가용시간 계산에서는 그게 맞지만, "이번 주 근무마다 이동을 붙여라"에서는
     * 그 근무가 이번 주 것이 아니다.
     */
    @Transactional(readOnly = true)
    public List<Commitment> findWorkShiftCandidates(Long userId, LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            return List.of();
        }
        return commitmentMapper.findWorkShiftCandidates(
                userId, from.atStartOfDay(), to.plusDays(1).atStartOfDay());
    }

    @Transactional
    public CommitmentResponse create(Long userId, CommitmentCreateRequest request,
                                     CommitmentSourceType sourceType) {
        return create(userId, request, sourceType, null);
    }

    /**
     * 파생 관계까지 기록하는 생성.
     *
     * <p>{@code origin}이 있으면 이 약속은 그 근무에서 만들어진 이동 블록이고, 다음 조회에서
     * 원본 근무로 다시 뽑히지 않는다. 그 정보는 요청 payload가 아니라 서버가 넘긴다 —
     * 사용자가 카드에서 고친 값으로 파생 관계를 지어낼 수 있으면 분류가 사실을 담지 못한다.
     *
     * <p>같은 근무·같은 방향의 살아 있는 이동이 이미 있으면 만들지 않고 409다. 조용히 하나 더
     * 만들면 사용자는 같은 시간에 겹친 블록 두 개를 나중에 발견한다. 확인은 FOR UPDATE로
     * 잡고, 그래도 빠져나가는 경쟁은 uk_commitments_derived가 막는다.
     */
    @Transactional
    public CommitmentResponse create(Long userId, CommitmentCreateRequest request,
                                     CommitmentSourceType sourceType, DerivedTravel origin) {
        validateRange(request.getStartAt(), request.getEndAt());

        if (origin != null) {
            // 잠금 순서를 고정한다: 원본 근무 → 파생 이동. 두 요청이 반대로 잡으면 교착이 난다.
            requireOriginUnchanged(userId, origin);
            Commitment existing = commitmentMapper.findDerivedForUpdate(
                    userId, origin.originCommitmentId(), origin.relation().name());
            if (existing != null) {
                log.info("파생 이동 중복: userId={}, origin={}, relation={}, 기존 commitmentId={}",
                        userId, origin.originCommitmentId(), origin.relation(), existing.getCommitmentId());
                throw new ConflictException(ErrorCode.DERIVED_COMMITMENT_ALREADY_EXISTS);
            }
        }

        Commitment commitment = Commitment.builder()
                .userId(userId)
                .title(request.getTitle().trim())
                .startAt(request.getStartAt())
                .endAt(request.getEndAt())
                .locationText(blankToNull(request.getLocationText()))
                .sourceType(sourceType)
                .derivedFromCommitmentId(origin != null ? origin.originCommitmentId() : null)
                .derivedRelation(origin != null ? origin.relation() : null)
                .build();
        try {
            commitmentMapper.insert(commitment);
        } catch (DuplicateKeyException e) {
            // FOR UPDATE를 빠져나간 경쟁. 제약이 최종 방어선이고, 사용자에게는 같은 이유를 준다.
            log.info("파생 이동 중복(제약): userId={}, origin={}", userId,
                    origin != null ? origin.originCommitmentId() : null);
            throw new ConflictException(ErrorCode.DERIVED_COMMITMENT_ALREADY_EXISTS);
        }
        commitment.setVersion(0L);

        log.info("약속 생성: userId={}, commitmentId={}, title={}, {} ~ {}, source={}, 파생={}",
                userId, commitment.getCommitmentId(), commitment.getTitle(),
                commitment.getStartAt(), commitment.getEndAt(), sourceType, origin);
        return CommitmentResponse.of(commitment);
    }

    /**
     * 이 약속이 어떤 근무의 앞/뒤 이동인가. 서버만 만든다.
     *
     * @param originCommitmentId 원본 근무의 commitment_id
     * @param relation           원본의 앞인가 뒤인가
     * @param expectedAnchorAt   후보를 만들 때 기준으로 삼은 원본의 시각(뒤면 원본 종료, 앞이면 원본 시작).
     *                           적용 시점에 원본이 그 값 그대로인지 확인한다
     */
    public record DerivedTravel(Long originCommitmentId, DerivedTravelRelation relation,
                                LocalDateTime expectedAnchorAt) {
        public DerivedTravel {
            if (originCommitmentId == null || relation == null) {
                throw new IllegalArgumentException("원본과 관계는 같이 있거나 같이 없다");
            }
        }
    }

    /**
     * 후보를 만든 뒤 원본 근무가 바뀌지 않았는지 본다.
     *
     * <p>후보는 만들어질 때의 근무 시각으로 계산돼 있다. 그 사이 사용자가 근무를 18시에서
     * 20시로 옮겼다면, 그대로 적용하면 근무와 겹치거나 한참 떨어진 이동 블록이 생기고
     * 사용자는 자기가 만든 적 없는 시간을 보게 된다. 자동으로 따라가지도 않는다 — 그건
     * 반복 일정 시스템의 일이고, 여기서는 다시 검토하게 돌려보내는 것이 맞다.
     *
     * <p>원본이 지워졌을 때도 같다. 기준이 사라졌으므로 그 이동은 더 이상 근거가 없다.
     */
    private void requireOriginUnchanged(Long userId, DerivedTravel origin) {
        if (origin.expectedAnchorAt() == null) {
            return;
        }
        /*
         * FOR UPDATE로 읽는다. 일반 SELECT로 검사하면 검사와 INSERT 사이에 다른 트랜잭션이
         * 근무 시각을 바꿔 커밋할 수 있고, 그러면 "검사할 때는 맞았던" 값으로 이동이 저장된다.
         * 잠금은 이 트랜잭션이 커밋할 때까지 유지된다.
         */
        Commitment source = commitmentMapper.findByIdAndUserIdForUpdate(origin.originCommitmentId(), userId);
        LocalDateTime actual = source == null ? null
                : origin.relation() == DerivedTravelRelation.AFTER_WORK ? source.getEndAt() : source.getStartAt();
        if (!origin.expectedAnchorAt().equals(actual)) {
            log.info("파생 이동 기준 변경 감지: userId={}, origin={}, 기대={}, 실제={}",
                    userId, origin.originCommitmentId(), origin.expectedAnchorAt(), actual);
            throw new ConflictException(ErrorCode.DERIVED_COMMITMENT_ORIGIN_CHANGED);
        }
    }

    /**
     * 이 원본 근무들에 이미 붙어 있는 이동 블록.
     *
     * <p>기준은 <b>원본 근무 id</b>다. 이동 자체의 날짜로 찾으면 마지막 날 근무의 이동(다음 날로
     * 넘어간다)과 사용자가 옮긴 이동을 놓치고, 그러면 "없다"고 보고 같은 블록을 하나 더 만든다.
     */
    @Transactional(readOnly = true)
    public List<Commitment> findDerivedByOrigins(Long userId, Collection<Long> originIds) {
        if (originIds == null || originIds.isEmpty()) {
            return List.of();
        }
        return commitmentMapper.findDerivedByOrigins(userId, originIds);
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
