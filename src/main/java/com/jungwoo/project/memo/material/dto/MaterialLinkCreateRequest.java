package com.jungwoo.project.memo.material.dto;

import com.jungwoo.project.memo.material.domain.MaterialType;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * 자료를 프로젝트에 연결한다.
 *
 * materialType은 업로드가 아니라 이 시점에 정해진다 — "이 프로젝트가 이 자료를 무엇으로
 * 쓰는가"이기 때문이다.
 */
@Getter
@Setter
public class MaterialLinkCreateRequest {

    @NotNull(message = "연결할 프로젝트를 지정해주세요")
    private Long courseId;

    @NotNull(message = "이 프로젝트에서 이 자료를 무엇으로 쓸지 골라주세요")
    private MaterialType materialType;
}
