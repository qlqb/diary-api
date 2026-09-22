package com.jungwoo.project.memo.learning;

import com.jungwoo.project.memo.learning.domain.TopicMaterialLink;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TopicMaterialLinkMapper {

    /**
     * INSERT ... ON DUPLICATE KEY UPDATE status='ACTIVE'. 같은 (topic, material, section)을 다시
     * 연결하면 새 행이 아니라 기존 행이 되살아난다 — 같은 변경안을 두 번 적용해도 링크가 늘지 않는다.
     */
    int upsert(TopicMaterialLink link);

    List<TopicMaterialLink> findActiveByCourseId(@Param("courseId") Long courseId, @Param("userId") Long userId);

    List<TopicMaterialLink> findActiveByTopicIds(@Param("topicIds") List<Long> topicIds, @Param("userId") Long userId);

    List<TopicMaterialLink> findActiveByMaterialId(@Param("materialId") Long materialId, @Param("userId") Long userId);

    /** 병합: 흡수된 항목의 링크를 살아남은 항목으로 옮긴다(upsert로 중복 없이). */
    List<TopicMaterialLink> findActiveByTopicId(@Param("topicId") Long topicId, @Param("userId") Long userId);

    int removeByTopicId(@Param("topicId") Long topicId, @Param("userId") Long userId);

    int remove(@Param("linkId") Long linkId, @Param("userId") Long userId);
}
