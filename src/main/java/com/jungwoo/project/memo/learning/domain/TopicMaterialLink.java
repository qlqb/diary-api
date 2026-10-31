package com.jungwoo.project.memo.learning.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 토픽 ↔ 자료 구간 연결(다대다). topic_material_links.
 *
 * <p>sectionId 0은 "자료 전체" — 구간 없이 자료만 가리키는 연결이다(백필·수동 연결).
 * course_topics.source_material_id는 최초 출처로 그대로 남고, 이 표가 실제 연결의 원본이다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TopicMaterialLink {

    public static final long WHOLE_MATERIAL = 0L;

    private Long linkId;
    private Long userId;
    private Long courseId;
    private Long topicId;
    private Long materialId;
    private Long sectionId;
    /** SectionRole 이름 또는 "SOURCE"(백필). */
    private String role;
    private String locator;
    private TopicLinkOrigin origin;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public boolean isWholeMaterial() {
        return sectionId == null || sectionId == WHOLE_MATERIAL;
    }
}
