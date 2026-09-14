package com.jungwoo.project.memo.assignment;

import com.jungwoo.project.memo.assignment.domain.CourseAssignment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface CourseAssignmentMapper {

    /** INSERT IGNORE — 같은 (user, dedupe_key)가 있으면 0행. 재분석이 같은 구간을 다시 내도 새 행이 아니다. */
    int insertIgnore(CourseAssignment assignment);

    CourseAssignment findByIdAndUserId(@Param("assignmentId") Long assignmentId, @Param("userId") Long userId);

    CourseAssignment findByDedupeKey(@Param("userId") Long userId, @Param("dedupeKey") String dedupeKey);

    List<CourseAssignment> findByCourseId(@Param("courseId") Long courseId, @Param("userId") Long userId);

    List<CourseAssignment> findByCourseIds(@Param("courseIds") List<Long> courseIds, @Param("userId") Long userId);

    /** 오늘/한눈에 화면용: 확정됐고 미완료인 과제 중 마감이 있는 것(날짜 오름차순). 마감 없는 것도 뒤에 붙인다. */
    List<CourseAssignment> findOpenConfirmed(@Param("userId") Long userId);

    List<CourseAssignment> findByMaterialId(@Param("materialId") Long materialId, @Param("userId") Long userId);

    /**
     * 재분석 보강: 사용자가 손대지 않은 CANDIDATE의 원문 인용·추정만 갱신한다.
     * 확정 상태·사용자 편집 제목·마감은 건드리지 않는다.
     */
    int refreshCandidate(@Param("assignmentId") Long assignmentId, @Param("sourceQuote") String sourceQuote,
                         @Param("dueQuote") String dueQuote, @Param("dueEstimateJson") String dueEstimateJson,
                         @Param("topicId") Long topicId, @Param("courseId") Long courseId);

    /** 과제 여부 답. version 대조. */
    int updateConfirmStatus(@Param("assignmentId") Long assignmentId, @Param("userId") Long userId,
                            @Param("confirmStatus") String confirmStatus,
                            @Param("duplicateOfAssignmentId") Long duplicateOfAssignmentId,
                            @Param("version") Long version);

    /** 마감 설정(사용자). version 대조. */
    int updateDue(@Param("assignmentId") Long assignmentId, @Param("userId") Long userId,
                  @Param("dueKind") String dueKind, @Param("dueDate") LocalDate dueDate,
                  @Param("dueAt") java.time.LocalDateTime dueAt, @Param("dueSource") String dueSource,
                  @Param("version") Long version);

    int updateTitle(@Param("assignmentId") Long assignmentId, @Param("userId") Long userId,
                    @Param("title") String title, @Param("version") Long version);

    /** 완료 체크/해제. version 대조. */
    int updateCompleted(@Param("assignmentId") Long assignmentId, @Param("userId") Long userId,
                        @Param("completedAt") java.time.LocalDateTime completedAt, @Param("version") Long version);

    /** 프로젝트 연결이 나중에 생겼을 때 course_id/topic_id를 채운다(비어 있을 때만). */
    int attachCourseIfMissing(@Param("assignmentId") Long assignmentId, @Param("courseId") Long courseId,
                              @Param("topicId") Long topicId);
}
