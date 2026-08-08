package com.jungwoo.project.memo.learning;

import com.jungwoo.project.memo.learning.domain.CourseTopic;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CourseTopicMapper {

    void insert(CourseTopic topic);

    CourseTopic findByIdAndUserId(@Param("topicId") Long topicId, @Param("userId") Long userId);

    List<CourseTopic> findActiveByCourseIdAndUserId(@Param("courseId") Long courseId, @Param("userId") Long userId);

    List<CourseTopic> findActiveByIds(@Param("topicIds") List<Long> topicIds, @Param("userId") Long userId);
}
