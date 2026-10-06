package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.domain.UsageResultStatus;
import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.TooManyRequestsException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 일일 한도가 상담(AI_CONSULTATION) 기록만 세는지 실제 로컬 MariaDB(memo)에 대고 검증한다.
 * 집계 조건은 SQL(feature = #{feature})에 있어 Mockito로는 증명할 수 없다.
 *
 * 2026-09-15 사용자 계정에서 자료 29개 업로드 직후 백그라운드 분석 기록 64건만으로 상담이
 * "오늘의 AI 상담 호출 한도를 모두 사용했습니다"로 막혔다(상담 0회). 그 상황을 줄여 재현한다.
 */
@SpringBootTest(properties = "ai.usage.daily-limit=3")
class AiUsageLimitServiceDbTest {

    /** users에 없는 합성 id — ai_usage_logs에 FK가 없고, 이 id의 기존 기록도 없다. */
    private static final Long TEST_USER_ID = 999_000_061L;
    private static final String TEST_MODEL = "usage-limit-db-test";

    @Autowired
    private AiUsageLimitService aiUsageLimitService;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    @AfterEach
    void cleanUp() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM ai_usage_logs WHERE user_id = ? AND model = ?")) {
            ps.setLong(1, TEST_USER_ID);
            ps.setString(2, TEST_MODEL);
            ps.executeUpdate();
        }
    }

    @Test
    void checkLimit_ignoresNonConsultationUsage() {
        recordTimes("MATERIAL_ANALYSIS_JOB", 5);
        recordTimes("LINK_PROPOSAL", 2);
        recordTimes("PLAN_DRAFT", 2);

        assertThatCode(() -> aiUsageLimitService.checkLimit(TEST_USER_ID)).doesNotThrowAnyException();
    }

    @Test
    void checkLimit_blocksWhenConsultationUsageReachesLimit() {
        recordTimes("MATERIAL_ANALYSIS_JOB", 5);
        recordTimes("AI_CONSULTATION", 2);
        assertThatCode(() -> aiUsageLimitService.checkLimit(TEST_USER_ID)).doesNotThrowAnyException();

        recordTimes("AI_CONSULTATION", 1);
        assertThatThrownBy(() -> aiUsageLimitService.checkLimit(TEST_USER_ID))
                .isInstanceOf(TooManyRequestsException.class)
                .satisfies(e -> assertThat(((TooManyRequestsException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AI_USAGE_LIMIT_EXCEEDED));
    }

    private void recordTimes(String feature, int times) {
        for (int i = 0; i < times; i++) {
            aiUsageLimitService.record(TEST_USER_ID, null, null, TEST_MODEL, 10, null, 5,
                    UsageResultStatus.SUCCESS, null, feature, null, null, null);
        }
    }
}
