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

    /**
     * 이 프로젝트에 이미 있는 루트 topic의 최대 order_index. 행이 없으면 null.
     * 두 번째 자료를 apply할 때 루트 순서가 0부터 다시 매겨져 기존 목차와 섞이는 것을 막는다.
     */
    Integer findMaxRootOrderIndex(@Param("courseId") Long courseId, @Param("userId") Long userId);
}
