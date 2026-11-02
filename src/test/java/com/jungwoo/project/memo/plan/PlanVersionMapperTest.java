package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.plan.domain.PlanIntensity;
import com.jungwoo.project.memo.plan.domain.PlanVersion;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * findCoveringDate의 날짜 경계와 정렬을 실제 로컬 MariaDB(memo)에 대고 검증한다.
 *
 * Mockito로는 "서비스가 마퍼를 부른다"까지만 증명할 수 있고, 이 테스트가 증명하려는 것은
 * SQL 자체다 — start_date/end_date가 양끝을 포함하는지, DATEDIFF 정렬이 실제로 기간이
 * 짧은 계획을 앞에 두는지, 확정 시각이 같을 때 순서가 흔들리지 않는지. 이 정렬이 곧
 * "프로젝트 화면에 어느 계획을 대표로 띄우는가"라서 흔들리면 화면이 깜빡인다.
 *
 * 스키마가 레포에 없어 CI에서는 -PexcludeDbTests로 제외된다(build.gradle 참고).
 */
@SpringBootTest
class PlanVersionMapperTest {

    private static final Long TEST_USER_ID = 999_000_003L;
    private static final Long OTHER_USER_ID = 999_000_004L;

    @Autowired
    private PlanVersionMapper planVersionMapper;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM plan_versions WHERE user_id IN (?, ?)")) {
            ps.setLong(1, TEST_USER_ID);
            ps.setLong(2, OTHER_USER_ID);
            ps.executeUpdate();
        }
    }

    @Test
    void insert_thenFindByIdAndUserId_roundTripsEveryField() {
        PlanVersion saved = insertPlan(TEST_USER_ID, "8월 마무리",
                LocalDate.of(2026, 8, 24), LocalDate.of(2026, 8, 30));

        PlanVersion found = planVersionMapper.findByIdAndUserId(saved.getPlanVersionId(), TEST_USER_ID);

        assertThat(found).isNotNull();
        assertThat(found.getTitle()).isEqualTo("8월 마무리");
        assertThat(found.getPlanKey()).isEqualTo(saved.getPlanKey());
        assertThat(found.getVersion()).isEqualTo(1);
        assertThat(found.getStartDate()).isEqualTo(LocalDate.of(2026, 8, 24));
        assertThat(found.getEndDate()).isEqualTo(LocalDate.of(2026, 8, 30));
        assertThat(found.getItemsSnapshot()).contains("\"executionItemId\"");
        // confirmed_at은 insert가 보내지 않고 DB 기본값이 채운다.
        assertThat(found.getConfirmedAt()).isNotNull();
    }

    @Test
    void findByIdAndUserId_otherUsersPlan_returnsNull() {
        PlanVersion saved = insertPlan(TEST_USER_ID, "내 계획",
                LocalDate.of(2026, 8, 24), LocalDate.of(2026, 8, 30));

        assertThat(planVersionMapper.findByIdAndUserId(saved.getPlanVersionId(), OTHER_USER_ID)).isNull();
    }

    @Test
    void findCoveringDate_includesBothBoundaryDays_excludesOutside() {
        insertPlan(TEST_USER_ID, "그 주", LocalDate.of(2026, 8, 24), LocalDate.of(2026, 8, 30));

        assertThat(planVersionMapper.findCoveringDate(TEST_USER_ID, LocalDate.of(2026, 8, 24)))
                .as("시작일 당일도 포함해야 한다").hasSize(1);
        assertThat(planVersionMapper.findCoveringDate(TEST_USER_ID, LocalDate.of(2026, 8, 30)))
                .as("종료일 당일도 포함해야 한다").hasSize(1);
        assertThat(planVersionMapper.findCoveringDate(TEST_USER_ID, LocalDate.of(2026, 8, 23))).isEmpty();
        assertThat(planVersionMapper.findCoveringDate(TEST_USER_ID, LocalDate.of(2026, 8, 31))).isEmpty();
    }

    @Test
    void findCoveringDate_ordersByShorterPeriodFirst() {
        // 일부러 긴 것부터 넣어, 정렬이 삽입 순서가 아니라 기간 길이를 따르는지 본다.
        insertPlan(TEST_USER_ID, "8월 전체", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));
        insertPlan(TEST_USER_ID, "이번 주", LocalDate.of(2026, 8, 24), LocalDate.of(2026, 8, 30));
        insertPlan(TEST_USER_ID, "오늘", LocalDate.of(2026, 8, 25), LocalDate.of(2026, 8, 25));

        List<PlanVersion> covering = planVersionMapper.findCoveringDate(TEST_USER_ID, LocalDate.of(2026, 8, 25));

        assertThat(covering).extracting(PlanVersion::getTitle)
                .containsExactly("오늘", "이번 주", "8월 전체");
    }

    @Test
    void findCoveringDate_samePeriodLength_ordersByMostRecentlyConfirmed() {
        PlanVersion older = insertPlan(TEST_USER_ID, "먼저 확정",
                LocalDate.of(2026, 8, 24), LocalDate.of(2026, 8, 30));
        PlanVersion newer = insertPlan(TEST_USER_ID, "나중 확정",
                LocalDate.of(2026, 8, 24), LocalDate.of(2026, 8, 30));

        // confirmed_at은 DB 기본값이라 같은 초에 들어갈 수 있다. 정렬 대상을 명시적으로 벌린다.
        setConfirmedAt(older.getPlanVersionId(), LocalDateTime.of(2026, 8, 20, 9, 0));
        setConfirmedAt(newer.getPlanVersionId(), LocalDateTime.of(2026, 8, 23, 9, 0));

        List<PlanVersion> covering = planVersionMapper.findCoveringDate(TEST_USER_ID, LocalDate.of(2026, 8, 25));

        assertThat(covering).extracting(PlanVersion::getTitle)
                .containsExactly("나중 확정", "먼저 확정");
    }

    @Test
    void findCoveringDate_otherUsersPlan_isNotReturned() {
        insertPlan(OTHER_USER_ID, "남의 계획", LocalDate.of(2026, 8, 24), LocalDate.of(2026, 8, 30));

        assertThat(planVersionMapper.findCoveringDate(TEST_USER_ID, LocalDate.of(2026, 8, 25))).isEmpty();
    }

    // ===== 강도 (§5-1-1, §5-1-2) =====

    @Test
    void insert_roundTripsIntensityAndTargetMinutes() {
        PlanVersion saved = insertPlan(TEST_USER_ID, "집중 계획",
                LocalDate.of(2026, 8, 24), LocalDate.of(2026, 8, 30),
                PlanIntensity.FOCUSED, 1080);

        PlanVersion found = planVersionMapper.findByIdAndUserId(saved.getPlanVersionId(), TEST_USER_ID);

        assertThat(found.getIntensity()).isEqualTo(PlanIntensity.FOCUSED);
        assertThat(found.getTargetMinutes()).isEqualTo(1080);
    }

    @Test
    void insert_allowsNullIntensity() {
        PlanVersion saved = insertPlan(TEST_USER_ID, "강도 없는 계획",
                LocalDate.of(2026, 8, 24), LocalDate.of(2026, 8, 30), null, null);

        PlanVersion found = planVersionMapper.findByIdAndUserId(saved.getPlanVersionId(), TEST_USER_ID);

        assertThat(found.getIntensity()).isNull();
        assertThat(found.getTargetMinutes()).isNull();
    }

    @Test
    void findLatestConfirmed_returnsMostRecentlyConfirmedPlan() {
        PlanVersion older = insertPlan(TEST_USER_ID, "먼저",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 7), PlanIntensity.LIGHT, 240);
        PlanVersion newer = insertPlan(TEST_USER_ID, "나중",
                LocalDate.of(2026, 8, 8), LocalDate.of(2026, 8, 14), PlanIntensity.FOCUSED, 1080);
        setConfirmedAt(older.getPlanVersionId(), LocalDateTime.of(2026, 8, 1, 9, 0));
        setConfirmedAt(newer.getPlanVersionId(), LocalDateTime.of(2026, 8, 8, 9, 0));

        PlanVersion latest = planVersionMapper.findLatestConfirmed(TEST_USER_ID);

        assertThat(latest.getTitle()).isEqualTo("나중");
        assertThat(latest.getIntensity()).isEqualTo(PlanIntensity.FOCUSED);
    }

    @Test
    void findLatestConfirmed_noHistory_returnsNull() {
        assertThat(planVersionMapper.findLatestConfirmed(TEST_USER_ID)).isNull();
    }

    @Test
    void findLatestConfirmed_ignoresOtherUsers() {
        insertPlan(OTHER_USER_ID, "남의 계획",
                LocalDate.of(2026, 8, 24), LocalDate.of(2026, 8, 30), PlanIntensity.FOCUSED, 1080);

        assertThat(planVersionMapper.findLatestConfirmed(TEST_USER_ID)).isNull();
    }

    @Test
    void findByPlanKeyAndUserId_returnsOnlyThatPlanKey() {
        PlanVersion target = insertPlan(TEST_USER_ID, "대상",
                LocalDate.of(2026, 8, 24), LocalDate.of(2026, 8, 30));
        insertPlan(TEST_USER_ID, "다른 계획", LocalDate.of(2026, 8, 24), LocalDate.of(2026, 8, 30));

        List<PlanVersion> versions = planVersionMapper.findByPlanKeyAndUserId(target.getPlanKey(), TEST_USER_ID);

        assertThat(versions).extracting(PlanVersion::getTitle).containsExactly("대상");
    }

    private PlanVersion insertPlan(Long userId, String title, LocalDate start, LocalDate end) {
        return insertPlan(userId, title, start, end, PlanIntensity.NORMAL, 600);
    }

    private PlanVersion insertPlan(Long userId, String title, LocalDate start, LocalDate end,
                                   PlanIntensity intensity, Integer targetMinutes) {
        PlanVersion plan = PlanVersion.builder()
                .userId(userId)
                .planKey(UUID.randomUUID().toString())
                .version(1)
                .startDate(start)
                .endDate(end)
                .title(title)
                .intensity(intensity)
                .targetMinutes(targetMinutes)
                .itemsSnapshot("[{\"executionItemId\":1,\"title\":\"" + title + " 항목\",\"courseId\":6}]")
                .build();
        planVersionMapper.insert(plan);
        assertThat(plan.getPlanVersionId()).as("useGeneratedKeys로 id가 채워져야 한다").isNotNull();
        return plan;
    }

    private void setConfirmedAt(Long planVersionId, LocalDateTime confirmedAt) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE plan_versions SET confirmed_at = ? WHERE plan_version_id = ?")) {
            ps.setObject(1, confirmedAt);
            ps.setLong(2, planVersionId);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("테스트 준비 실패", e);
        }
    }
}
