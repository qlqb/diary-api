package com.jungwoo.project.memo.support;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 테스트가 사용자 DB에 붙지 않았는지 확인한다.
 *
 * <p>왜 테스트로 두는가: 격리는 build.gradle의 systemProperty 한 줄에 달려 있고, 그런 설정은
 * 조용히 풀린다 — IDE에서 직접 실행하거나, 누군가 그 줄을 옮기거나, 프로필이 바뀌면 그만이다.
 * 풀린 것을 알아채는 시점이 "사용자 데이터에 테스트 흔적이 남은 뒤"여서는 안 된다.
 * 2026-09-21에 정확히 그 순서로 알았다(material_analysis_timings에 합성 표본 여덟 건).
 *
 * <p>이 테스트가 깨지면 다른 테스트를 돌리기 전에 먼저 고친다. 스키마 이름만 본다 —
 * 접속 정보나 비밀값은 읽지도 찍지도 않는다.
 */
@SpringBootTest
class TestDatabaseIsolationTest {

    /** 사용자의 실제 데이터가 있는 스키마. 테스트는 여기에 붙으면 안 된다. */
    private static final String USER_SCHEMA = "memo";

    @Autowired
    private DataSource dataSource;

    @Test
    void 테스트는_사용자_스키마에_붙지_않는다() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT DATABASE()")) {
            rs.next();
            String schema = rs.getString(1);

            assertThat(schema)
                    .as("테스트가 붙은 스키마. memo면 격리 설정이 풀린 것이다 — "
                            + "build.gradle의 spring.datasource.url systemProperty를 확인하라.")
                    .isNotNull()
                    .isNotEqualTo(USER_SCHEMA);
        }
    }

    @Test
    void 격리_스키마에는_앱_테이블이_갖춰져_있다() throws Exception {
        // 빈 DB에 붙어 "memo가 아니다"만 만족시키면 DB 테스트가 전부 이유 없이 깨진다.
        // 격리가 됐다는 말은 "다른 DB"가 아니라 "같은 스키마를 가진 다른 DB"라는 뜻이다.
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()")) {
            rs.next();
            assertThat(rs.getInt(1))
                    .as("격리 스키마의 테이블 수. 0이면 스키마를 만들지 않은 것이다 — "
                            + "docs/handoff/project-tidy-review-2026-09-21.md의 준비 절차를 보라.")
                    .isGreaterThan(40);
        }
    }
}
