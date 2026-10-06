package com.jungwoo.project.memo.course;

import com.jungwoo.project.memo.course.domain.CourseNote;
import com.jungwoo.project.memo.course.domain.CourseNoteCategory;
import com.jungwoo.project.memo.course.dto.CourseNoteDraft;
import com.jungwoo.project.memo.course.dto.CourseNoteResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 학습 topic이 아닌 과목 정보/평가 정보(course_notes)를 다룬다.
 *
 * course_topics와 마찬가지로 Material Agent 분석의 apply()를 통해서만 생성된다 — 사용자가
 * 직접 만드는 CRUD는 없다. LearningMap을 오염시키지 않기 위해 애초에 topic으로 만들지 않고
 * 여기로 분리해서 쌓아 둘 뿐이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CourseNoteService {

    private final CourseService courseService;
    private final CourseNoteMapper courseNoteMapper;

    @Transactional
    public int saveAll(Long userId, Long courseId, Long materialId, List<CourseNoteDraft> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (CourseNoteDraft draft : drafts) {
            if (draft.label() == null || draft.label().isBlank()) {
                continue;
            }
            CourseNoteCategory category = "ASSESSMENT".equals(draft.category())
                    ? CourseNoteCategory.ASSESSMENT : CourseNoteCategory.COURSE_INFO;
            CourseNote note = CourseNote.builder()
                    .userId(userId)
                    .courseId(courseId)
                    .category(category)
                    .label(draft.label())
                    .detail(draft.detail() != null ? draft.detail() : "")
                    .sourceMaterialId(materialId)
                    .build();
            courseNoteMapper.insert(note);
            count++;
        }
        log.info("과목 정보/평가 정보 저장: userId={}, courseId={}, materialId={}, count={}", userId, courseId, materialId, count);
        return count;
    }

    @Transactional(readOnly = true)
    public List<CourseNoteResponse> getByCourse(Long userId, Long courseId) {
        courseService.getOwned(userId, courseId);
        return courseNoteMapper.findByCourseIdAndUserId(courseId, userId).stream()
                .map(CourseNoteResponse::of)
                .toList();
    }
}
