package com.jungwoo.project.memo.course;

import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.course.domain.CourseStatus;
import com.jungwoo.project.memo.course.dto.CourseCreateRequest;
import com.jungwoo.project.memo.course.dto.CourseResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CourseService {

    private final CourseMapper courseMapper;

    @Transactional
    public CourseResponse create(Long userId, CourseCreateRequest request) {
        Course course = Course.builder()
                .userId(userId)
                .title(request.getTitle())
                .status(CourseStatus.ACTIVE)
                .build();
        courseMapper.insert(course);
        log.info("과목 생성: userId={}, courseId={}, title={}", userId, course.getCourseId(), course.getTitle());
        return CourseResponse.of(course, 0);
    }

    @Transactional(readOnly = true)
    public List<CourseResponse> list(Long userId) {
        return courseMapper.findByUserId(userId).stream()
                .map(course -> CourseResponse.of(course, courseMapper.countActiveTopicsByCourseId(course.getCourseId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public CourseResponse get(Long userId, Long courseId) {
        Course course = getOwned(userId, courseId);
        return CourseResponse.of(course, courseMapper.countActiveTopicsByCourseId(courseId));
    }

    /** 소유권 검증까지 마친 Course 엔티티. 다른 패키지(material/learning) 서비스가 재사용한다. */
    @Transactional(readOnly = true)
    public Course getOwned(Long userId, Long courseId) {
        Course course = courseMapper.findByIdAndUserId(courseId, userId);
        if (course == null) {
            throw new NotFoundException(ErrorCode.COURSE_NOT_FOUND);
        }
        return course;
    }
}
