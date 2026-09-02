package com.jungwoo.project.memo.learning;

import com.jungwoo.project.memo.learning.domain.TopicProgress;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface TopicProgressMapper {

    void insert(TopicProgress progress);

    TopicProgress findByUserIdAndTopicId(@Param("userId") Long userId, @Param("topicId") Long topicId);

    List<TopicProgress> findByUserIdAndTopicIds(@Param("userId") Long userId, @Param("topicIds") List<Long> topicIds);

    /** null 파라미터는 기존 값을 유지한다(COALESCE). reviewCountIncrement가 true면 review_count+1. */
    void updateProgress(@Param("userId") Long userId,
                         @Param("topicId") Long topicId,
                         @Param("status") String status,
                         @Param("lastStudiedAt") LocalDateTime lastStudiedAt,
                         @Param("lastReviewedAt") LocalDateTime lastReviewedAt,
                         @Param("incrementReviewCount") boolean incrementReviewCount);
}
