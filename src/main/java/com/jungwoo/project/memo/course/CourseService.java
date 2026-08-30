package com.jungwoo.project.memo.course;

import com.jungwoo.project.memo.common.exception.ErrorCode;
import com.jungwoo.project.memo.common.exception.NotFoundException;
import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.course.domain.CourseStatus;
import com.jungwoo.project.memo.course.dto.CourseCreateRequest;
import com.jungwoo.project.memo.course.dto.CourseResponse;
import com.jungwoo.project.memo.course.dto.CourseSummaryCounts;
import com.jungwoo.project.memo.course.dto.CourseUpdateRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 프로젝트(courses) 서비스.
 *
 * 생성에 필요한 것은 제목뿐이다 — 자료가 없어도 프로젝트는 완전히 사용 가능한 공간이며,
 * 여기서 자료 유무를 검사하거나 막지 않는다.
 */
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
                .groupLabel(blankToNull(request.getGroupLabel()))
                .status(CourseStatus.ACTIVE)
                .build();
        courseMapper.insert(course);
        log.info("프로젝트 생성: userId={}, courseId={}, title={}", userId, course.getCourseId(), course.getTitle());
        return CourseResponse.of(course, null);
    }

    /**
     * 제목·분류·교재 정보를 고친다.
     *
     * 교재는 사용자 편집 전용 경로(updateTextbookByUser)로 쓴다 — 받은 값을 그대로 넣고,
     * 비우면 비운다. AI 분석 적용 경로(updateTextbookInfo)는 반대로 비어 있는 칸만 채우는데,
     * 그래야 사람이 고쳐 놓은 값을 재분석이 조용히 뒤집지 않는다.
     */
    @Transactional
    public CourseResponse update(Long userId, Long courseId, CourseUpdateRequest request) {
        getOwned(userId, courseId);
        courseMapper.updateBasics(courseId, userId, blankToNull(request.getTitle()),
                blankToNull(request.getGroupLabel()));
        courseMapper.updateTextbookByUser(courseId, userId,
                blankToNull(request.getTextbookTitle()),
                blankToNull(request.getTextbookAuthor()),
                blankToNull(request.getTextbookPublisher()),
                blankToNull(request.getTextbookIsbn()));
        return get(userId, courseId);
    }

    /**
     * 보관. 삭제하지 않는다 — 지금까지 쌓인 자료·대화·실행 기록을 잃지 않기 위해서다.
     *
     * status 한 칸만 내린다. course_topics·material_links·course_material_analyses는 전부
     * 그대로 두고, 조회하는 쪽에서만 걸러낸다. 그래서 복원도 status를 되돌리는 것으로 끝난다.
     */
    @Transactional
    public void archive(Long userId, Long courseId) {
        getOwned(userId, courseId);
        courseMapper.updateStatus(courseId, userId, CourseStatus.ARCHIVED.name());
        log.info("프로젝트 보관: userId={}, courseId={}", userId, courseId);
    }

    /**
     * 보관 해제. 자료 연결을 되살리는 별도 복구 로직은 없다 — material_links 행을 애초에
     * 지우지 않고 조회에서만 숨겼기 때문에, ACTIVE로 되돌리는 순간 연결이 저절로 다시 보인다.
     */
    @Transactional
    public void restore(Long userId, Long courseId) {
        getOwned(userId, courseId);
        courseMapper.updateStatus(courseId, userId, CourseStatus.ACTIVE.name());
        log.info("프로젝트 복원: userId={}, courseId={}", userId, courseId);
    }

    /** 기본은 ACTIVE 목록. 보관함 화면만 ARCHIVED를 넘겨 같은 경로로 읽는다. */
    @Transactional(readOnly = true)
    public List<CourseResponse> list(Long userId, CourseStatus status) {
        Map<Long, CourseSummaryCounts> countsById = courseMapper.findSummaryCounts(userId, null).stream()
                .collect(Collectors.toMap(CourseSummaryCounts::getCourseId, Function.identity()));
        return courseMapper.findByUserIdAndStatus(userId, status.name()).stream()
                .map(course -> CourseResponse.of(course, countsById.get(course.getCourseId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public CourseResponse get(Long userId, Long courseId) {
        Course course = getOwned(userId, courseId);
        List<CourseSummaryCounts> counts = courseMapper.findSummaryCounts(userId, courseId);
        return CourseResponse.of(course, counts.isEmpty() ? null : counts.get(0));
    }

    /** 소유권 검증까지 마친 Course 엔티티. 다른 패키지(material/learning/ai) 서비스가 재사용한다. */
    @Transactional(readOnly = true)
    public Course getOwned(Long userId, Long courseId) {
        Course course = courseMapper.findByIdAndUserId(courseId, userId);
        if (course == null) {
            throw new NotFoundException(ErrorCode.COURSE_NOT_FOUND);
        }
        return course;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
