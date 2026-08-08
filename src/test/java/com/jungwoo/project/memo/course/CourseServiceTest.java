package com.jungwoo.project.memo.course;

import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
}
