package com.jungwoo.project.memo.learning.dto;

import com.jungwoo.project.memo.learning.domain.CourseTopic;
import com.jungwoo.project.memo.learning.domain.TopicProgress;
import com.jungwoo.project.memo.learning.domain.TopicProgressStatus;
import com.jungwoo.project.memo.learning.domain.TopicSourceType;
import com.jungwoo.project.memo.learning.domain.TopicUserMark;
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
    /** 사용자가 직접 말한 사실(KNOWN/DEFER). 없으면 null. progressStatus와 다른 축이다. */
    private TopicUserMark userMark;
    /** 병합·분할에서 학습 기록 승계가 애매할 때 서버가 남긴 안내. 없으면 null. */
    private String reviewNote;
    /**
     * 이 항목에 연결된 자료 구간(topic_material_links). 한 항목에 여러 자료·여러 역할이 있을 수 있다.
     * sourceMaterialId(최초 출처)와 별개다. 비어 있으면 아직 구간 연결이 없다.
     */
    private List<LinkedMaterial> linkedMaterials;
    private List<TopicResponse> children;

    @Getter
    @Builder
    public static class LinkedMaterial {
        private Long linkId;
        private Long materialId;
        private String filename;
        private boolean materialDeleted;
        private Long sectionId;
        private String sectionTitle;
        private String locator;
        private String role;
        private String roleLabel;
        private String taskText;
    }

    public static TopicResponse of(CourseTopic topic, TopicProgress progress, List<TopicResponse> children,
                                    CourseMaterial sourceMaterial) {
        return of(topic, progress, children, sourceMaterial, List.of());
    }

    public static TopicResponse of(CourseTopic topic, TopicProgress progress, List<TopicResponse> children,
                                    CourseMaterial sourceMaterial, List<LinkedMaterial> linkedMaterials) {
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
                .userMark(topic.getUserMark())
                .reviewNote(topic.getReviewNote())
                .linkedMaterials(linkedMaterials == null ? List.of() : linkedMaterials)
                .children(children)
                .build();
    }
}
