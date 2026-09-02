package com.jungwoo.project.memo.learning.dto;

import com.jungwoo.project.memo.learning.domain.CourseTopic;
import com.jungwoo.project.memo.learning.domain.TopicProgress;
import com.jungwoo.project.memo.learning.domain.TopicProgressStatus;
import com.jungwoo.project.memo.learning.domain.TopicSourceType;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.material.domain.MaterialStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class TopicResponse {

    private Long topicId;
    private Long parentTopicId;
    private String title;
    private int orderIndex;
    private TopicSourceType sourceType;
    private String sourceLocator;
    private Long sourceMaterialId;
    /**
     * 이 항목이 나온 자료의 파일명. 원본을 삭제했어도 남는다 — 확정된 학습 내용은 유지되므로
     * "어디서 왔는지"도 계속 말해줄 수 있어야 한다. 자료를 찾을 수 없으면 null.
     */
    private String sourceMaterialFilename;
    /** 원본 자료가 삭제되었는지. true면 화면은 "원본 삭제됨 · 파일명"으로 적는다. */
    private boolean sourceMaterialDeleted;
    private TopicProgressStatus progressStatus;
    private LocalDateTime lastStudiedAt;
    private LocalDateTime lastReviewedAt;
    private int reviewCount;
    private List<TopicResponse> children;

    public static TopicResponse of(CourseTopic topic, TopicProgress progress, List<TopicResponse> children,
                                    CourseMaterial sourceMaterial) {
        return TopicResponse.builder()
                .topicId(topic.getTopicId())
                .parentTopicId(topic.getParentTopicId())
                .title(topic.getTitle())
                .orderIndex(topic.getOrderIndex())
                .sourceType(topic.getSourceType())
                .sourceLocator(topic.getSourceLocator())
                .sourceMaterialId(topic.getSourceMaterialId())
                .sourceMaterialFilename(sourceMaterial != null ? sourceMaterial.getOriginalFilename() : null)
                .sourceMaterialDeleted(sourceMaterial != null && sourceMaterial.getStatus() == MaterialStatus.DELETED)
                .progressStatus(progress != null ? progress.getStatus() : TopicProgressStatus.NOT_STARTED)
                .lastStudiedAt(progress != null ? progress.getLastStudiedAt() : null)
                .lastReviewedAt(progress != null ? progress.getLastReviewedAt() : null)
                .reviewCount(progress != null && progress.getReviewCount() != null ? progress.getReviewCount() : 0)
                .children(children)
                .build();
    }
}
