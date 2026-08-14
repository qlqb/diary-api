package com.jungwoo.project.memo.course;

import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.course.dto.CourseSummaryCounts;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CourseMapper {

    void insert(Course course);

    Course findByIdAndUserId(@Param("courseId") Long courseId, @Param("userId") Long userId);

    /** ACTIVE 프로젝트만. 보관(ARCHIVED)한 프로젝트는 목록에 나타나지 않는다. */
    List<Course> findByUserId(@Param("userId") Long userId);

    /**
     * 프로젝트 카드 요약(자료 수/학습 구조 수/완료 수/진행 중 주제)을 한 번에 계산한다.
     * courseId가 null이면 그 사용자의 모든 프로젝트를 반환한다.
     */
    List<CourseSummaryCounts> findSummaryCounts(@Param("userId") Long userId,
                                                 @Param("courseId") Long courseId);

    void updateTextbookInfo(@Param("courseId") Long courseId,
                             @Param("userId") Long userId,
                             @Param("textbookTitle") String textbookTitle,
                             @Param("textbookAuthor") String textbookAuthor,
                             @Param("textbookPublisher") String textbookPublisher,
                             @Param("textbookIsbn") String textbookIsbn);

    /** 제목/분류 수정. groupLabel은 COALESCE하지 않는다 — null을 보내면 "분류 없음"으로 지운다. */
    void updateBasics(@Param("courseId") Long courseId,
                       @Param("userId") Long userId,
                       @Param("title") String title,
                       @Param("groupLabel") String groupLabel);

    void updateStatus(@Param("courseId") Long courseId,
                       @Param("userId") Long userId,
                       @Param("status") String status);
}
