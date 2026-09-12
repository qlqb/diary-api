package com.jungwoo.project.memo.commitment;

import com.jungwoo.project.memo.commitment.domain.Commitment;
import com.jungwoo.project.memo.commitment.domain.CommitmentSourceType;
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
 * one_off_commitments의 매핑과 제약을 실제 로컬 MariaDB(memo)에 대고 검증한다.
 *
 * <p>Mockito로는 증명할 수 없는 것들이다: 겹침 조회가 정말 "시작 시각이 범위 안"이 아니라
 * "구간이 범위와 겹침"으로 도는지, DATETIME이 LocalDateTime으로 왕복하는지, source_type이
 * enum으로 읽히는지, CHECK가 실제로 무는지. 특히 겹침이 중요하다 — 전날 밤에 시작해 오늘
 * 새벽에 끝나는 약속이 여기서 새면 배치가 그 시간을 비어 있다고 본다.
 *
 * <p><b>이 테스트는 docs/sql/2026-09-01-one-off-commitments.sql을 적용한 뒤에만 통과한다.</b>
 * 적용 전에는 테이블이 없어 전부 실패한다. 스키마가 레포에 없어 CI에서는
 * -PexcludeDbTests로 제외된다(build.gradle 참고).
 */
@SpringBootTest
class CommitmentMapperTest {

    private static final Long TEST_USER_ID = 999_000_031L;
    private static final Long OTHER_USER_ID = 999_000_032L;

    @Autowired
    private CommitmentMapper commitmentMapper;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM one_off_commitments WHERE user_id IN (?, ?)")) {
            ps.setLong(1, TEST_USER_ID);
            ps.setLong(2, OTHER_USER_ID);
            ps.executeUpdate();
        }
    }

    private Commitment insert(Long userId, String title, LocalDateTime startAt, LocalDateTime endAt) {
        Commitment commitment = Commitment.builder()
                .userId(userId)
                .title(title)
                .startAt(startAt)
                .endAt(endAt)
                .locationText("홍대")
                .sourceType(CommitmentSourceType.MANUAL)
                .build();
        commitmentMapper.insert(commitment);
        return commitment;
    }

    private static LocalDateTime at(int day, int hour) {
        return LocalDateTime.of(2026, 9, day, hour, 0);
    }

    private List<Commitment> onDay(int day) {
        LocalDate date = LocalDate.of(2026, 9, day);
        return commitmentMapper.findOverlapping(
                TEST_USER_ID, date.atStartOfDay(), date.plusDays(1).atStartOfDay());
    }

    @Test
    void 저장한_값이_그대로_돌아온다() {
        Commitment saved = insert(TEST_USER_ID, "친구 약속", at(4, 19), at(4, 21));

        Commitment found = commitmentMapper.findByIdAndUserId(saved.getCommitmentId(), TEST_USER_ID);

        assertThat(found).isNotNull();
        assertThat(found.getTitle()).isEqualTo("친구 약속");
        assertThat(found.getStartAt()).isEqualTo(at(4, 19));
        assertThat(found.getEndAt()).isEqualTo(at(4, 21));
        assertThat(found.getLocationText()).isEqualTo("홍대");
        assertThat(found.getSourceType()).isEqualTo(CommitmentSourceType.MANUAL);
        assertThat(found.getVersion()).isZero();
    }

    @Test
    void 남의_약속은_조회되지_않는다() {
        Commitment saved = insert(OTHER_USER_ID, "남의 약속", at(4, 19), at(4, 21));

        assertThat(commitmentMapper.findByIdAndUserId(saved.getCommitmentId(), TEST_USER_ID)).isNull();
    }

    @Test
    void 전날_밤에_시작해_오늘_새벽에_끝나면_오늘_조회에_잡힌다() {
        insert(TEST_USER_ID, "밤샘 행사", at(4, 22), at(5, 2));

        // 시작 시각으로만 걸렀다면 9/5 조회에서 사라진다 — 그 시간에 배치가 들어간다.
        assertThat(onDay(5)).extracting(Commitment::getTitle).containsExactly("밤샘 행사");
        assertThat(onDay(4)).extracting(Commitment::getTitle).containsExactly("밤샘 행사");
    }

    @Test
    void 창_경계에_닿기만_한_것은_겹치지_않는다() {
        // 9/4 00:00에 끝나는 약속은 9/4와 한 순간도 겹치지 않는다(반열린 구간).
        insert(TEST_USER_ID, "전날 밤", at(3, 22), at(4, 0));
        // 9/5 00:00에 시작하는 약속도 9/4와 겹치지 않는다.
        insert(TEST_USER_ID, "다음날 새벽", at(5, 0), at(5, 2));

        assertThat(onDay(4)).isEmpty();
    }

    @Test
    void 삭제한_약속은_조회에서_빠진다() {
        Commitment saved = insert(TEST_USER_ID, "취소된 약속", at(4, 19), at(4, 21));

        assertThat(commitmentMapper.softDeleteWithVersion(saved.getCommitmentId(), TEST_USER_ID, 0L))
                .isEqualTo(1);

        assertThat(commitmentMapper.findByIdAndUserId(saved.getCommitmentId(), TEST_USER_ID)).isNull();
        assertThat(onDay(4)).isEmpty();
    }

    @Test
    void 버전이_어긋나면_수정도_삭제도_한_행도_바꾸지_못한다() {
        Commitment saved = insert(TEST_USER_ID, "친구 약속", at(4, 19), at(4, 21));

        assertThat(commitmentMapper.updateWithVersion(saved.getCommitmentId(), TEST_USER_ID, 7L,
                "바뀐 제목", at(4, 19), at(4, 22), null)).isZero();
        assertThat(commitmentMapper.softDeleteWithVersion(saved.getCommitmentId(), TEST_USER_ID, 7L))
                .isZero();
    }

    @Test
    void 수정하면_버전이_하나_오른다() {
        Commitment saved = insert(TEST_USER_ID, "친구 약속", at(4, 19), at(4, 21));

        assertThat(commitmentMapper.updateWithVersion(saved.getCommitmentId(), TEST_USER_ID, 0L,
                "친구 약속", at(4, 19), at(4, 22), null)).isEqualTo(1);

        Commitment found = commitmentMapper.findByIdAndUserId(saved.getCommitmentId(), TEST_USER_ID);
        assertThat(found.getVersion()).isEqualTo(1L);
        assertThat(found.getEndAt()).isEqualTo(at(4, 22));
        // PUT이라 비운 장소는 비워진다 — COALESCE하지 않는다.
        assertThat(found.getLocationText()).isNull();
    }

    @Test
    void 길이가_0인_구간은_DB가_막는다() {
        // 서비스 검증만 있으면 나중에 다른 경로가 생겼을 때 새는 자리가 된다.
        assertThatThrownBy(() -> insert(TEST_USER_ID, "길이 0", at(4, 19), at(4, 19)))
                .isInstanceOf(Exception.class);
    }

    @Test
    void 역전된_구간은_DB가_막는다() {
        assertThatThrownBy(() -> insert(TEST_USER_ID, "역전", at(4, 21), at(4, 19)))
                .isInstanceOf(Exception.class);
    }
}
