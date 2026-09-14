package com.jungwoo.project.memo.learning;

import com.jungwoo.project.memo.learning.domain.TopicLearningEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TopicLearningEventMapper {

    void insert(TopicLearningEvent event);

    List<TopicLearningEvent> findRecentByUserIdAndTopicId(
            @Param("userId") Long userId, @Param("topicId") Long topicId, @Param("limit") int limit);
}
