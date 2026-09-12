package com.jungwoo.project.memo.learning.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 확정된(Apply된) 학습 구조 트리의 노드. course_topics 테이블과 1:1 대응.
 *
 * Material Agent의 apply()를 통해서만 생성된다 — 직접 CRUD 엔드포인트는 없다
 * (구조 자체를 사용자가 임의로 만드는 것이 아니라 항상 분석 결과의 확정으로 생긴다).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CourseTopic {

    private Long topicId;

    private Long userId;

    private Long courseId;

    private Long parentTopicId;

    private String title;

    private Integer orderIndex;

    private TopicSourceType sourceType;

    private Long sourceMaterialId;

    private String sourceLocator;

    private TopicStatus status;

    /**
     * 사용자가 이 항목에 대해 직접 말한 사실. 없으면 null이고 그것이 기본이다.
     * "모른다"와 "모른다고 답했다"를 구분하지 않는다 — 둘 다 근거 없음이고 판단이 같다.
     */
    private TopicUserMark userMark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
