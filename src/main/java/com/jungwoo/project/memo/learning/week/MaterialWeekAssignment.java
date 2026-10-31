package com.jungwoo.project.memo.learning.week;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 사용자가 확인한 "이 프로젝트에서 이 자료는 어디에 놓이는가". material_week_assignments와 1:1.
 *
 * <p>학습 지도의 실제 주차는 이 행들만으로 만든다. 추천(WeekSuggestionEngine)은 여기 쓰지 않는다 —
 * 쓰는 길은 사용자의 적용·이동 하나뿐이다.
 *
 * @see WeekSuggestionEngine.Placement
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MaterialWeekAssignment {

    /** 추천을 사용자가 적용했다(화면: "확인됨"). */
    public static final String SOURCE_SUGGESTION = "SUGGESTION";
    /** 사용자가 직접 골랐다(화면: "직접 지정"). */
    public static final String SOURCE_USER = "USER";

    private Long assignmentId;
    private Long userId;
    private Long courseId;
    private Long materialId;
    /** WEEK / COURSE_WIDE / UNASSIGNED */
    private String placement;
    /** placement=WEEK일 때 1~30. 그 밖에는 0 */
    private int weekNo;
    private String source;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
