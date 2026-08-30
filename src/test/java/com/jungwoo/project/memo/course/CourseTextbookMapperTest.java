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
 * 교재 정보를 쓰는 두 경로가 서로 반대라는 것을 실제 DB에 대고 확인한다.
 *
 * 이건 SQL의 COALESCE 인자 순서로 표현되어 있어 Mockito로는 증명할 수 없다 — 두 메서드
 * 모두 호출됐다는 것만 보이고, 무엇이 남는지는 안 보인다.
 *
 * 지키려는 규칙: 자료 분석은 한 프로젝트에서 여러 번 일어나는데(강의계획서 한 번, 나중에
 * 교수 자료로 또 한 번), 그때마다 덮어쓰면 사용자가 고쳐 놓은 값이 조용히 되돌아간다.
 * DB는 그 값이 사람이 고친 것인지 AI가 넣은 것인지 구분할 수 없으므로, 한 번 채워진 칸은
 * 사람 것으로 보고 손대지 않는다.
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
    @DisplayName("AI 경로는 비어 있는 칸만 채운다 — 이미 있는 값은 건드리지 않는다")
    void aiPathOnlyFillsEmptyColumns() {
        Long courseId = givenCourse();

        // 첫 분석: 전부 비어 있으므로 다 채워진다.
        courseMapper.updateTextbookInfo(courseId, userId(),
                "전처리와 시각화", "오경선 외", "길벗", null);

        Course afterFirst = reload(courseId);
        assertThat(afterFirst.getTextbookTitle()).isEqualTo("전처리와 시각화");
        assertThat(afterFirst.getTextbookPublisher()).isEqualTo("길벗");
        assertThat(afterFirst.getTextbookIsbn()).isNull();

        // 두 번째 분석이 다른 값을 들고 와도 이미 찬 칸은 그대로 두고, 빈 칸만 채운다.
        courseMapper.updateTextbookInfo(courseId, userId(),
                "다른 제목", "다른 저자", "한빛미디어", "9788956746425");

        Course afterSecond = reload(courseId);
        assertThat(afterSecond.getTextbookTitle()).isEqualTo("전처리와 시각화");
        assertThat(afterSecond.getTextbookAuthor()).isEqualTo("오경선 외");
        assertThat(afterSecond.getTextbookPublisher()).isEqualTo("길벗");
        // ISBN만 비어 있었으므로 이번에 채워진다.
        assertThat(afterSecond.getTextbookIsbn()).isEqualTo("9788956746425");
    }

    @Test
    @DisplayName("재분석이 사용자가 고친 값을 덮지 않는다")
    void reanalysisDoesNotOverwriteUserCorrection() {
        Long courseId = givenCourse();
        courseMapper.updateTextbookInfo(courseId, userId(), "전처리와 시각화", "오경선", "길벗", null);

        // 사용자가 저자를 바로잡는다.
        courseMapper.updateTextbookByUser(courseId, userId(),
                "전처리와 시각화", "오경선, 양숙희, 장은실", "길벗", null);

        // 나중에 다른 자료를 분석해 적용한다.
        courseMapper.updateTextbookInfo(courseId, userId(), "전처리와 시각화", "오경선", "길벗", null);

        assertThat(reload(courseId).getTextbookAuthor()).isEqualTo("오경선, 양숙희, 장은실");
    }

    @Test
    @DisplayName("사용자 경로는 비우면 비워진다 — 지울 방법이 있어야 한다")
    void userPathCanClearValues() {
        Long courseId = givenCourse();
        courseMapper.updateTextbookInfo(courseId, userId(), "전처리와 시각화", "오경선", "길벗", "978");

        // 사람이 화면에서 지운 것은 "모른다"는 뜻이다. COALESCE로 되살리면 지울 방법이 없어진다.
        courseMapper.updateTextbookByUser(courseId, userId(), "전처리와 시각화", null, null, null);

        Course after = reload(courseId);
        assertThat(after.getTextbookTitle()).isEqualTo("전처리와 시각화");
        assertThat(after.getTextbookAuthor()).isNull();
        assertThat(after.getTextbookPublisher()).isNull();
        assertThat(after.getTextbookIsbn()).isNull();
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
