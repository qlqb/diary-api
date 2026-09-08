package com.jungwoo.project.memo.commitment;

import com.jungwoo.project.memo.commitment.domain.Commitment;
import com.jungwoo.project.memo.commitment.domain.CommitmentSourceType;
import com.jungwoo.project.memo.commitment.domain.DerivedTravelRelation;
import com.jungwoo.project.memo.common.exception.ConflictException;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.commitment.dto.CommitmentCreateRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 파생 이동 컬럼과 중복 제약을 실제 로컬 MariaDB(memo)에 대고 검증한다.
 *
 * <p>Mockito로는 증명할 수 없는 것들이다: uk_commitments_derived가 정말 같은 (사용자, 원본,
 * 앞/뒤)를 막는지, 파생이 아닌 약속은 NULL 여러 개로 제한 없이 들어가는지, 소프트 삭제가
 * unique 키를 놓아주어 지운 뒤 다시 만들 수 있는지.
 *
 * <p><b>docs/sql/2026-09-09-commitment-derived-travel.sql을 적용한 뒤에만 통과한다.</b>
 * 스키마가 레포에 없어 CI에서는 -PexcludeDbTests로 제외된다(build.gradle 참고).
 */
@SpringBootTest
class CommitmentDerivedTravelMapperTest {

    private static final Long TEST_USER_ID = 999_000_041L;

    @Autowired
    private CommitmentService commitmentService;

    @Autowired
    private CommitmentMapper commitmentMapper;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM one_off_commitments WHERE user_id = ?")) {
            ps.setLong(1, TEST_USER_ID);
            ps.executeUpdate();
        }
    }

    private Commitment insertWorkShift(LocalDateTime startAt, LocalDateTime endAt) {
        Commitment shift = Commitment.builder()
                .userId(TEST_USER_ID).title("근무").startAt(startAt).endAt(endAt)
                .sourceType(CommitmentSourceType.MANUAL).build();
        commitmentMapper.insert(shift);
        return shift;
    }

    private CommitmentCreateRequest travel(LocalDateTime startAt, LocalDateTime endAt) {
        return CommitmentCreateRequest.builder()
                .title("근무 후 이동").startAt(startAt).endAt(endAt).build();
    }

    @Test
    void 파생_컬럼이_그대로_왕복한다() {
        Commitment shift = insertWorkShift(
                LocalDateTime.of(2026, 9, 8, 18, 0), LocalDateTime.of(2026, 9, 8, 23, 0));

        var created = commitmentService.create(TEST_USER_ID,
                travel(LocalDateTime.of(2026, 9, 8, 23, 0), LocalDateTime.of(2026, 9, 9, 0, 0)),
                CommitmentSourceType.AI_SUGGESTION_APPROVED,
                new CommitmentService.DerivedTravel(shift.getCommitmentId(),
                        DerivedTravelRelation.AFTER_WORK, LocalDateTime.of(2026, 9, 8, 23, 0)));

        Commitment stored = commitmentMapper.findByIdAndUserId(created.getCommitmentId(), TEST_USER_ID);
        assertThat(stored.getDerivedFromCommitmentId()).isEqualTo(shift.getCommitmentId());
        assertThat(stored.getDerivedRelation()).isEqualTo(DerivedTravelRelation.AFTER_WORK);
        // 자정을 넘긴 구간이 그대로 저장된다.
        assertThat(stored.getEndAt()).isEqualTo(LocalDateTime.of(2026, 9, 9, 0, 0));
    }

    @Test
    void 같은_근무의_같은_방향_이동은_두_번_만들어지지_않는다() {
        Commitment shift = insertWorkShift(
                LocalDateTime.of(2026, 9, 8, 18, 0), LocalDateTime.of(2026, 9, 8, 23, 0));
        var origin = new CommitmentService.DerivedTravel(shift.getCommitmentId(),
                DerivedTravelRelation.AFTER_WORK, LocalDateTime.of(2026, 9, 8, 23, 0));
        commitmentService.create(TEST_USER_ID,
                travel(LocalDateTime.of(2026, 9, 8, 23, 0), LocalDateTime.of(2026, 9, 9, 0, 0)),
                CommitmentSourceType.AI_SUGGESTION_APPROVED, origin);

        // 길이를 바꿔 다시 보내도 조용히 하나 더 생기지 않는다.
        assertThatThrownBy(() -> commitmentService.create(TEST_USER_ID,
                travel(LocalDateTime.of(2026, 9, 8, 23, 0), LocalDateTime.of(2026, 9, 9, 0, 30)),
                CommitmentSourceType.AI_SUGGESTION_APPROVED, origin))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DERIVED_COMMITMENT_ALREADY_EXISTS);

        assertThat(commitmentService.findDerivedByOrigins(TEST_USER_ID, List.of(shift.getCommitmentId())))
                .hasSize(1);
    }

    @Test
    void 앞과_뒤는_서로_다른_이동이라_둘_다_만들어진다() {
        Commitment shift = insertWorkShift(
                LocalDateTime.of(2026, 9, 8, 18, 0), LocalDateTime.of(2026, 9, 8, 23, 0));

        commitmentService.create(TEST_USER_ID,
                travel(LocalDateTime.of(2026, 9, 8, 17, 0), LocalDateTime.of(2026, 9, 8, 18, 0)),
                CommitmentSourceType.AI_SUGGESTION_APPROVED,
                new CommitmentService.DerivedTravel(shift.getCommitmentId(),
                        DerivedTravelRelation.BEFORE_WORK, LocalDateTime.of(2026, 9, 8, 18, 0)));
        commitmentService.create(TEST_USER_ID,
                travel(LocalDateTime.of(2026, 9, 8, 23, 0), LocalDateTime.of(2026, 9, 9, 0, 0)),
                CommitmentSourceType.AI_SUGGESTION_APPROVED,
                new CommitmentService.DerivedTravel(shift.getCommitmentId(),
                        DerivedTravelRelation.AFTER_WORK, LocalDateTime.of(2026, 9, 8, 23, 0)));

        assertThat(commitmentService.findDerivedByOrigins(TEST_USER_ID, List.of(shift.getCommitmentId())))
                .hasSize(2);
    }

    /** 원본이 그 사이 옮겨졌으면 그대로 만들지 않는다 — 근무와 겹치는 이동이 생긴다. */
    @Test
    void 원본_근무가_바뀌었으면_적용하지_않고_재검토로_돌린다() {
        Commitment shift = insertWorkShift(
                LocalDateTime.of(2026, 9, 8, 18, 0), LocalDateTime.of(2026, 9, 8, 23, 0));
        commitmentMapper.updateWithVersion(shift.getCommitmentId(), TEST_USER_ID, 0L, "근무",
                LocalDateTime.of(2026, 9, 8, 20, 0), LocalDateTime.of(2026, 9, 9, 1, 0), null);

        assertThatThrownBy(() -> commitmentService.create(TEST_USER_ID,
                travel(LocalDateTime.of(2026, 9, 8, 23, 0), LocalDateTime.of(2026, 9, 9, 0, 0)),
                CommitmentSourceType.AI_SUGGESTION_APPROVED,
                new CommitmentService.DerivedTravel(shift.getCommitmentId(),
                        DerivedTravelRelation.AFTER_WORK, LocalDateTime.of(2026, 9, 8, 23, 0))))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DERIVED_COMMITMENT_ORIGIN_CHANGED);
    }

    /** 지운 이동 블록이 unique 키를 계속 잡고 있으면 같은 근무에 다시 만들 수 없다. */
    @Test
    void 삭제한_이동은_같은_근무에_다시_만들_수_있다() {
        Commitment shift = insertWorkShift(
                LocalDateTime.of(2026, 9, 8, 18, 0), LocalDateTime.of(2026, 9, 8, 23, 0));
        var origin = new CommitmentService.DerivedTravel(shift.getCommitmentId(),
                DerivedTravelRelation.AFTER_WORK, LocalDateTime.of(2026, 9, 8, 23, 0));
        var first = commitmentService.create(TEST_USER_ID,
                travel(LocalDateTime.of(2026, 9, 8, 23, 0), LocalDateTime.of(2026, 9, 9, 0, 0)),
                CommitmentSourceType.AI_SUGGESTION_APPROVED, origin);

        commitmentService.delete(TEST_USER_ID, first.getCommitmentId(), first.getVersion());

        assertThat(commitmentService.create(TEST_USER_ID,
                travel(LocalDateTime.of(2026, 9, 8, 23, 0), LocalDateTime.of(2026, 9, 9, 0, 30)),
                CommitmentSourceType.AI_SUGGESTION_APPROVED, origin).getCommitmentId()).isNotNull();
    }

    /** 파생이 아닌 약속은 제한을 받지 않는다(NULL 여러 개). */
    @Test
    void 파생이_아닌_약속은_같은_시간에_여러_개_들어간다() {
        commitmentService.create(TEST_USER_ID,
                CommitmentCreateRequest.builder().title("친구 약속")
                        .startAt(LocalDateTime.of(2026, 9, 9, 10, 0))
                        .endAt(LocalDateTime.of(2026, 9, 9, 11, 0)).build(),
                CommitmentSourceType.MANUAL);
        commitmentService.create(TEST_USER_ID,
                CommitmentCreateRequest.builder().title("친구 약속")
                        .startAt(LocalDateTime.of(2026, 9, 9, 10, 0))
                        .endAt(LocalDateTime.of(2026, 9, 9, 11, 0)).build(),
                CommitmentSourceType.MANUAL);

        assertThat(commitmentService.list(TEST_USER_ID,
                LocalDate.of(2026, 9, 9), LocalDate.of(2026, 9, 9))).hasSize(2);
    }
}
