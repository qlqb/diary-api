package com.jungwoo.project.memo.course;

import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.course.domain.CourseStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 교재 칸의 유일한 쓰기(writeTextbook)를 실제 DB에 대고 확인한다 — 판 대조·판 올리기·목차 연결 풀기는 SQL 조건이라
 * Mockito로는 증명할 수 없다.
 *
 * (2026-10-04) 예전의 두 경로(AI 분석은 빈 칸만, 사용자 편집은 그대로)는 CourseTextbookWriter 하나로 모였다. 덮어쓰기 방지는
 * 이제 "빈 칸 COALESCE"가 아니라 판(textbook_version) 대조다.
 *
 * 스키마가 레포에 없어 CI에서는 -PexcludeDbTests로 제외된다(build.gradle 참고).
 */
@SpringBootTest
class CourseTextbookMapperTest {

    private static final String TITLE_PREFIX = "CTB-";

    @Autowired
    private CourseMapper courseMapper;
    @Autowired
    private DataSource dataSource;

    private Long userId;
    private final List<Long> createdCourseIds = new ArrayList<>();

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            for (Long courseId : createdCourseIds) {
                st.executeUpdate("DELETE FROM courses WHERE course_id = " + courseId);
            }
        }
        createdCourseIds.clear();
    }

    private Long givenCourse() {
        Course course = Course.builder()
                .userId(userId())
                .title(TITLE_PREFIX + System.nanoTime())
                .status(CourseStatus.ACTIVE)
                .build();
        courseMapper.insert(course);
        createdCourseIds.add(course.getCourseId());
        return course.getCourseId();
    }

    private Course reload(Long courseId) {
        return courseMapper.findByIdAndUserId(courseId, userId());
    }

    @Test
    @DisplayName("받은 값을 그대로 쓰고 판을 1 올린다 — 비운 칸은 비워진다")
    void writesVerbatimAndBumpsVersion() {
        Long courseId = givenCourse();

        assertThat(courseMapper.writeTextbook(courseId, userId(), 0, "전처리와 시각화", "오경선 외", "길벗", null, null,
                "USER", null, null, false, null)).isEqualTo(1);
        Course first = reload(courseId);
        assertThat(first.getTextbookTitle()).isEqualTo("전처리와 시각화");
        assertThat(first.getTextbookInfoSource()).isEqualTo("USER");
        assertThat(first.getTextbookVersion()).isEqualTo(1);

        // 사람이 화면에서 지운 것은 "모른다"는 뜻이다. 되살리지 않는다.
        courseMapper.writeTextbook(courseId, userId(), 1, "전처리와 시각화", null, null, null, null, "USER", null, null,
                false, null);
        Course cleared = reload(courseId);
        assertThat(cleared.getTextbookAuthor()).isNull();
        assertThat(cleared.getTextbookPublisher()).isNull();
        assertThat(cleared.getTextbookVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("본 판과 다르면 0행 — 늦은 쓰기가 사용자가 고친 값을 덮지 않는다")
    void staleVersionWritesNothing() {
        Long courseId = givenCourse();
        courseMapper.writeTextbook(courseId, userId(), 0, "A책", null, null, null, null, "MATERIAL", null, null, false, null);
        courseMapper.writeTextbook(courseId, userId(), 1, "B책", null, null, null, null, "USER", null, null, false, null);

        // 판 0을 본 옛 작업이 A를 다시 쓰려 한다.
        assertThat(courseMapper.writeTextbook(courseId, userId(), 0, "A책", null, null, null, null, "MATERIAL", null,
                null, false, null)).isZero();
        assertThat(reload(courseId).getTextbookTitle()).isEqualTo("B책");
    }

    @Test
    @DisplayName("목차 연결은 판을 올리고, 교재 식별이 바뀌는 쓰기는 연결을 푼다")
    void tocLinkIsClearedWhenBookChanges() {
        Long courseId = givenCourse();
        courseMapper.writeTextbook(courseId, userId(), 0, "A책", null, null, null, null, "USER", null, null, false, null);
        assertThat(courseMapper.writeTextbookTocLink(courseId, userId(), 1, 77L, "hash-a", "title:a책")).isEqualTo(1);
        Course linked = reload(courseId);
        assertThat(linked.getTextbookTocMaterialId()).isEqualTo(77L);
        assertThat(linked.getTextbookVersion()).isEqualTo(2);

        courseMapper.writeTextbook(courseId, userId(), 2, "B책", null, null, null, null, "USER", null, null, true, null);
        Course after = reload(courseId);
        assertThat(after.getTextbookTocMaterialId()).isNull();
        assertThat(after.getTextbookTocFileHash()).isNull();
        assertThat(after.getTextbookTocBookKey()).isNull();
    }

    private Long userId() {
        if (userId != null) {
            return userId;
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT user_id FROM users ORDER BY user_id LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                throw new IllegalStateException("users 테이블이 비어 있어 테스트할 수 없다");
            }
            userId = rs.getLong(1);
            return userId;
        } catch (Exception e) {
            throw new IllegalStateException("테스트 준비 실패", e);
        }
    }
}
