package com.jungwoo.project.memo.learning;

import com.jungwoo.project.memo.learning.domain.CourseTopic;
import com.jungwoo.project.memo.learning.domain.TopicUserMark;
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
    /**
     * 사용자가 직접 남긴 익숙함 표식. AI는 이 컬럼에 쓰지 않는다.
     *
     * <p>mark가 null이면 표식을 지운다 — "모른다"로 되돌리는 것이지 "처음이다"를 저장하는
     * 것이 아니다. 그 둘은 판단이 같으므로 구분하지 않는다.
     */
    int updateUserMark(
            @Param("topicId") Long topicId,
            @Param("userId") Long userId,
            @Param("userMark") TopicUserMark userMark
    );

    Integer findMaxRootOrderIndex(@Param("courseId") Long courseId, @Param("userId") Long userId);

    // ===== 구조 변경(변경안 적용 전용). TopicTreeEditor만 부른다. =====

    /** ARCHIVED 포함. 병합된 항목의 행선지를 화면에 보여줄 때. */
    List<CourseTopic> findByCourseIdAndUserIdIncludingArchived(@Param("courseId") Long courseId,
                                                               @Param("userId") Long userId);

    /** 변경안 적용이 트리 전체를 잠근다. 같은 프로젝트의 동시 적용을 직렬화하기 위해서다. */
    List<CourseTopic> findActiveByCourseIdAndUserIdForUpdate(@Param("courseId") Long courseId,
                                                             @Param("userId") Long userId);

    int updateTitle(@Param("topicId") Long topicId, @Param("userId") Long userId, @Param("title") String title);

    int updateParent(@Param("topicId") Long topicId, @Param("userId") Long userId,
                     @Param("parentTopicId") Long parentTopicId, @Param("orderIndex") Integer orderIndex);

    /** 병합: 흡수된 항목을 ARCHIVED로 내리고 살아남은 항목을 적는다. ID는 그대로다. */
    int archiveMerged(@Param("topicId") Long topicId, @Param("userId") Long userId,
                      @Param("survivingTopicId") Long survivingTopicId);

    int updateReviewNote(@Param("topicId") Long topicId, @Param("userId") Long userId,
                         @Param("reviewNote") String reviewNote);

    Integer findMaxChildOrderIndex(@Param("courseId") Long courseId, @Param("userId") Long userId,
                                   @Param("parentTopicId") Long parentTopicId);
}
