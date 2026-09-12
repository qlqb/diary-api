package com.jungwoo.project.memo.course;

import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.course.domain.CourseNote;
import com.jungwoo.project.memo.course.domain.CourseNoteCategory;
import com.jungwoo.project.memo.course.dto.CourseNoteDraft;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CourseNoteServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long COURSE_ID = 10L;
    private static final Long MATERIAL_ID = 20L;

    @Mock
    private CourseService courseService;

    @Mock
    private CourseNoteMapper courseNoteMapper;

    @InjectMocks
    private CourseNoteService service;

    @Test
    void saveAll_defaultsUnrecognizedCategoryToCourseInfo_ratherThanFailing() {
        List<CourseNoteDraft> drafts = List.of(
                new CourseNoteDraft("ASSESSMENT", "평가 비율", "중간 30%"),
                new CourseNoteDraft("GARBAGE", "담당교수", "홍길동"));

        service.saveAll(USER_ID, COURSE_ID, MATERIAL_ID, drafts);

        ArgumentCaptor<CourseNote> captor = ArgumentCaptor.forClass(CourseNote.class);
        verify(courseNoteMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        assertThat(captor.getAllValues().get(0).getCategory()).isEqualTo(CourseNoteCategory.ASSESSMENT);
        assertThat(captor.getAllValues().get(1).getCategory()).isEqualTo(CourseNoteCategory.COURSE_INFO);
    }

    @Test
    void saveAll_skipsBlankLabelDrafts_ratherThanStoringEmptyNotes() {
        List<CourseNoteDraft> drafts = List.of(new CourseNoteDraft("COURSE_INFO", "  ", "detail"));

        int saved = service.saveAll(USER_ID, COURSE_ID, MATERIAL_ID, drafts);

        assertThat(saved).isZero();
        verify(courseNoteMapper, never()).insert(any());
    }

    @Test
    void saveAll_doesNothing_whenDraftsIsEmpty() {
        int saved = service.saveAll(USER_ID, COURSE_ID, MATERIAL_ID, List.of());

        assertThat(saved).isZero();
        verify(courseNoteMapper, never()).insert(any());
    }

    @Test
    void getByCourse_deniesAccess_whenCourseBelongsToAnotherUser() {
        when(courseService.getOwned(USER_ID, COURSE_ID)).thenThrow(new NotFoundException(ErrorCode.COURSE_NOT_FOUND));

        assertThatThrownBy(() -> service.getByCourse(USER_ID, COURSE_ID))
                .isInstanceOfSatisfying(NotFoundException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.COURSE_NOT_FOUND));

        verify(courseNoteMapper, never()).findByCourseIdAndUserId(any(), any());
    }

    @Test
    void getByCourse_mapsCategoryToResponseString() {
        when(courseService.getOwned(USER_ID, COURSE_ID)).thenReturn(Course.builder().courseId(COURSE_ID).build());
        when(courseNoteMapper.findByCourseIdAndUserId(COURSE_ID, USER_ID)).thenReturn(List.of(
                CourseNote.builder().noteId(1L).courseId(COURSE_ID).userId(USER_ID)
                        .category(CourseNoteCategory.ASSESSMENT).label("평가 비율").detail("중간 30%").build()));

        var result = service.getByCourse(USER_ID, COURSE_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCategory()).isEqualTo("ASSESSMENT");
        assertThat(result.get(0).getLabel()).isEqualTo("평가 비율");
    }
}
