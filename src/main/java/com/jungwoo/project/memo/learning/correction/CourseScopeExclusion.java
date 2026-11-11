package com.jungwoo.project.memo.learning.correction;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** course_scope_exclusions 한 행. 시험·계획 범위에서 뺀 학습 항목(학습 완료가 아니다). */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CourseScopeExclusion {
    private Long exclusionId;
    private Long userId;
    private Long courseId;
    private Long topicId;
    private String label;
    private String status;
    private LocalDateTime createdAt;
}
