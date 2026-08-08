package com.jungwoo.project.memo.course;

import com.jungwoo.project.memo.course.domain.Course;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CourseMapper {

    void insert(Course course);

    Course findByIdAndUserId(@Param("courseId") Long courseId, @Param("userId") Long userId);

    List<Course> findByUserId(@Param("userId") Long userId);

    int countActiveTopicsByCourseId(@Param("courseId") Long courseId);

    void updateTextbookInfo(@Param("courseId") Long courseId,
                             @Param("userId") Long userId,
                             @Param("textbookTitle") String textbookTitle,
                             @Param("textbookAuthor") String textbookAuthor,
                             @Param("textbookPublisher") String textbookPublisher,
                             @Param("textbookIsbn") String textbookIsbn);
}
