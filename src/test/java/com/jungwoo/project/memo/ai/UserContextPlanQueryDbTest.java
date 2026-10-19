package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.domain.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 계획 생성용 맥락 조회는 과목·기간으로 거른 뒤 자른다 — 무관한 맥락이 많아도 유효한 맥락이 밀려나지 않는다. */
@SpringBootTest
class UserContextPlanQueryDbTest {

    private static final long USER = 999_000_710L;
    private static final long PLAN_COURSE = 936L;
    private static final long OTHER_COURSE = 934L;

    @Autowired
    private UserContextMapper mapper;
    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        cleanUp();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO user_contexts (user_id, content, status, source_type, "
                     + "course_id, scope_start, scope_end, updated_at) VALUES (?, ?, 'ACTIVE', 'USER_CONFIRMED', ?, ?, ?, ?)")) {
            // 가장 최근에 바뀐 무관한 맥락 85개: 다른 프로젝트 한정 45개, 지난 기간 한정 40개
            for (int i = 0; i < 85; i++) {
                boolean other = i < 45;
                ps.setLong(1, USER);
                ps.setString(2, "무관 " + i);
                if (other) {
                    ps.setLong(3, OTHER_COURSE);
                } else {
                    ps.setNull(3, java.sql.Types.BIGINT);
                }
                ps.setObject(4, other ? null : LocalDate.of(2026, 10, 3));
                ps.setObject(5, other ? null : LocalDate.of(2026, 10, 3));
                ps.setObject(6, java.time.LocalDateTime.of(2026, 10, 18, 12, 0).minusMinutes(i));
                ps.addBatch();
            }
            // 그보다 오래된 유효한 맥락: 전역 하나, 이번 과목 하나, 기간이 걸치는 것 하나
            Object[][] valid = {
                    {"금요일 밤은 비움", null, null, null},
                    {"빅데이터는 코드 재현이 어렵다", PLAN_COURSE, null, null},
                    {"시험 주간은 알바 안 감", null, LocalDate.of(2026, 10, 18), LocalDate.of(2026, 10, 23)}};
            for (Object[] v : valid) {
                ps.setLong(1, USER);
                ps.setString(2, (String) v[0]);
                ps.setObject(3, v[1]);
                ps.setObject(4, v[2]);
                ps.setObject(5, v[3]);
                ps.setObject(6, java.time.LocalDateTime.of(2026, 9, 1, 9, 0));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("DELETE FROM user_contexts WHERE user_id = " + USER);
        }
    }

    @Test
    void 무관한_맥락이_상한보다_많아도_유효한_맥락을_가져온다() {
        LocalDate day = LocalDate.of(2026, 10, 18);
        List<UserContext> found = mapper.findActiveAndStaleForPlan(USER, Set.of(PLAN_COURSE, 937L), day, day, 20);

        assertThat(found).extracting(UserContext::getContent)
                .containsExactlyInAnyOrder("금요일 밤은 비움", "빅데이터는 코드 재현이 어렵다", "시험 주간은 알바 안 감");
    }
}
