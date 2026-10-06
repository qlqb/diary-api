package com.jungwoo.project.memo.learning.correction;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CourseCorrectionMapper {

    /** 활성 항목의 실제 수업 진행만. 보관(병합)된 항목의 행은 빼고 읽는다. */
    List<TopicClassProgress> findClassProgress(@Param("courseId") Long courseId, @Param("userId") Long userId);

    /** (course, topic)마다 한 행. week·seq 중 null로 넘긴 칸은 그대로 둔다. */
    int upsertClassProgress(TopicClassProgress progress);

    int updateClassSeq(@Param("courseId") Long courseId, @Param("userId") Long userId,
                       @Param("topicId") Long topicId, @Param("classSeq") Integer classSeq);

    int deleteClassProgress(@Param("courseId") Long courseId, @Param("userId") Long userId,
                            @Param("topicId") Long topicId);

    List<CourseScopeExclusion> findActiveExclusions(@Param("courseId") Long courseId, @Param("userId") Long userId);

    List<CourseScopeExclusion> findActiveExclusionsForCourses(@Param("courseIds") List<Long> courseIds,
                                                              @Param("userId") Long userId);

    int upsertExclusion(CourseScopeExclusion exclusion);

    List<TopicRecordCount> countTopicRecords(@Param("topicIds") List<Long> topicIds, @Param("userId") Long userId);

    int removeExclusion(@Param("exclusionId") Long exclusionId, @Param("courseId") Long courseId,
                        @Param("userId") Long userId);
}
