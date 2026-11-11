package com.jungwoo.project.memo.learning.correction;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** topic_class_progress 한 행. 사용자가 정정한 실제 수업 순서·주차. 교재 위치(트리)와 별개다. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TopicClassProgress {
    private Long progressId;
    private Long userId;
    private Long courseId;
    private Long topicId;
    private Integer classSeq;
    private Integer weekNo;
    private String note;
}
