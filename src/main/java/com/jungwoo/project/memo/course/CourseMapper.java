package com.jungwoo.project.memo.course;

import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.course.dto.CourseSummaryCounts;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CourseMapper {

    void insert(Course course);

    Course findByIdAndUserId(@Param("courseId") Long courseId, @Param("userId") Long userId);

    /**
     * 그 상태의 프로젝트만. 기본 목록은 ACTIVE로 부르고, 보관함은 ARCHIVED로 부른다 —
     * 보관은 숨김이지 삭제가 아니므로 행은 항상 그대로 있고 어느 쪽으로 읽느냐만 달라진다.
     */
    List<Course> findByUserIdAndStatus(@Param("userId") Long userId, @Param("status") String status);

    /**
     * 여러 프로젝트를 한 번에. 보관한 프로젝트도 포함한다 — 자료에 붙은 연결의 제목을
     * 채울 때 쓰는데, 보관했다고 그 연결이 사라지지는 않기 때문이다.
     */
    List<Course> findByIdsAndUserId(@Param("courseIds") List<Long> courseIds, @Param("userId") Long userId);

    /**
     * 프로젝트 카드 요약(자료 수/학습 구조 수/완료 수/진행 중 주제)을 한 번에 계산한다.
     * courseId가 null이면 그 사용자의 모든 프로젝트를 반환한다.
     */
    List<CourseSummaryCounts> findSummaryCounts(@Param("userId") Long userId,
                                                 @Param("courseId") Long courseId);

    /** AI 분석 적용 경로. 비어 있는 칸만 채운다 — 이미 있는 값은 사람 것으로 보고 건드리지 않는다. */
    void updateTextbookInfo(@Param("courseId") Long courseId,
                             @Param("userId") Long userId,
                             @Param("textbookTitle") String textbookTitle,
                             @Param("textbookAuthor") String textbookAuthor,
                             @Param("textbookPublisher") String textbookPublisher,
                             @Param("textbookIsbn") String textbookIsbn);

    /** 사용자 편집 경로. 받은 값을 그대로 쓴다 — null이면 비운다. */
    void updateTextbookByUser(@Param("courseId") Long courseId,
                              @Param("userId") Long userId,
                              @Param("textbookTitle") String textbookTitle,
                              @Param("textbookAuthor") String textbookAuthor,
                              @Param("textbookPublisher") String textbookPublisher,
                              @Param("textbookIsbn") String textbookIsbn);

    /** 제목/분류 수정. groupLabel은 COALESCE하지 않는다 — null을 보내면 "분류 없음"으로 지운다. */
    void updateBasics(@Param("courseId") Long courseId,
                       @Param("userId") Long userId,
                       @Param("title") String title,
                       @Param("groupLabel") String groupLabel);

    /** 보관/복원 공용. 행을 지우거나 되살리는 것이 아니라 status만 오간다. */
    void updateStatus(@Param("courseId") Long courseId,
                       @Param("userId") Long userId,
                       @Param("status") String status);
}
