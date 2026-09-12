package com.jungwoo.project.memo.course;

import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.course.domain.CourseStatus;
import com.jungwoo.project.memo.course.dto.CourseUpdateRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CourseServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final Long COURSE_ID = 10L;

    @Mock
    private CourseMapper courseMapper;

    @InjectMocks
    private CourseService service;

    @Test
    void getOwned_throwsNotFound_whenCourseBelongsToAnotherUser() {
        // findByIdAndUserId already scopes by user_id — 다른 사용자 소유면 null이 반환된다.
        when(courseMapper.findByIdAndUserId(COURSE_ID, OTHER_USER_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.getOwned(OTHER_USER_ID, COURSE_ID))
                .isInstanceOfSatisfying(NotFoundException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.COURSE_NOT_FOUND));
    }

    @Test
    void update_writesTextbookThroughTheUserPath_soClearingActuallyClears() {
        when(courseMapper.findByIdAndUserId(COURSE_ID, USER_ID))
                .thenReturn(Course.builder().courseId(COURSE_ID).status(CourseStatus.ACTIVE).build());
        when(courseMapper.findSummaryCounts(USER_ID, COURSE_ID)).thenReturn(List.of());

        CourseUpdateRequest request = new CourseUpdateRequest();
        org.springframework.test.util.ReflectionTestUtils.setField(request, "title", "빅데이터분석");
        org.springframework.test.util.ReflectionTestUtils.setField(request, "textbookTitle", "전처리와 시각화");
        org.springframework.test.util.ReflectionTestUtils.setField(request, "textbookAuthor", "오경선 외");
        // 빈 문자열은 "모른다"는 뜻이다 — 지울 수 있어야 한다.
        org.springframework.test.util.ReflectionTestUtils.setField(request, "textbookPublisher", "  ");

        service.update(USER_ID, COURSE_ID, request);

        // AI 경로(updateTextbookInfo)는 비어 있는 칸만 채우므로 사용자 편집에 쓸 수 없다.
        verify(courseMapper).updateTextbookByUser(COURSE_ID, USER_ID,
                "전처리와 시각화", "오경선 외", null, null);
        verify(courseMapper, never()).updateTextbookInfo(any(), any(), any(), any(), any(), any());
    }

    @Test
    void archive_onlyLowersStatus_leavingTopicsLinksAndAnalysesIntact() {
        when(courseMapper.findByIdAndUserId(COURSE_ID, USER_ID))
                .thenReturn(Course.builder().courseId(COURSE_ID).status(CourseStatus.ACTIVE).build());

        service.archive(USER_ID, COURSE_ID);

        // 보관은 숨김이지 삭제가 아니다. 관련 데이터를 정리하는 코드가 여기 생기면 복원이
        // status 한 줄로 끝나지 않게 되고, material_links를 지우지 않는다는 규칙도 깨진다.
        verify(courseMapper).updateStatus(COURSE_ID, USER_ID, "ARCHIVED");
        verify(courseMapper, never()).findSummaryCounts(any(), any());
    }

    @Test
    void restore_putsStatusBackToActive_withoutAnyRecoveryLogic() {
        when(courseMapper.findByIdAndUserId(COURSE_ID, USER_ID))
                .thenReturn(Course.builder().courseId(COURSE_ID).status(CourseStatus.ARCHIVED).build());

        service.restore(USER_ID, COURSE_ID);

        // 자료 연결은 조회에서만 숨겨져 있었으므로 status를 되돌리면 저절로 다시 보인다.
        verify(courseMapper).updateStatus(COURSE_ID, USER_ID, "ACTIVE");
    }

    @Test
    void list_readsArchivedProjects_whenArchivedStatusRequested() {
        when(courseMapper.findSummaryCounts(USER_ID, null)).thenReturn(List.of());
        when(courseMapper.findByUserIdAndStatus(USER_ID, "ARCHIVED"))
                .thenReturn(List.of(Course.builder().courseId(COURSE_ID).title("자료구조")
                        .status(CourseStatus.ARCHIVED).build()));

        var result = service.list(USER_ID, CourseStatus.ARCHIVED);

        assertThat(result).singleElement()
                .satisfies(c -> assertThat(c.getStatus()).isEqualTo(CourseStatus.ARCHIVED));
        verify(courseMapper, never()).findByUserIdAndStatus(USER_ID, "ACTIVE");
    }
}
