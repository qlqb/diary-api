package com.jungwoo.project.memo.course;

import com.jungwoo.project.memo.course.domain.CourseNote;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CourseNoteMapper {

    void insert(CourseNote note);

    List<CourseNote> findByCourseIdAndUserId(@Param("courseId") Long courseId, @Param("userId") Long userId);
}
