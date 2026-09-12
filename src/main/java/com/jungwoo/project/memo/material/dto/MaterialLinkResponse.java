package com.jungwoo.project.memo.material.dto;

import com.jungwoo.project.memo.material.domain.MaterialLink;
import com.jungwoo.project.memo.material.domain.MaterialType;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 자료가 어떤 프로젝트에 어떤 성격으로 걸려 있는지.
 *
 * materialType이 자료가 아니라 여기 있는 이유: 같은 파일이 A에서는 강의계획서,
 * B에서는 참고자료일 수 있다.
 */
@Getter
@Builder
public class MaterialLinkResponse {

    private Long courseId;
    private String courseTitle;
    private MaterialType materialType;
    private LocalDateTime linkedAt;

    public static MaterialLinkResponse of(MaterialLink link, String courseTitle) {
        return MaterialLinkResponse.builder()
                .courseId(link.getCourseId())
                .courseTitle(courseTitle)
                .materialType(link.getMaterialType())
                .linkedAt(link.getLinkedAt())
                .build();
    }
}
