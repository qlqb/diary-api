package com.jungwoo.project.memo.material.dto;

import com.jungwoo.project.memo.material.domain.MaterialType;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * 이 프로젝트에서 이 자료가 맡는 역할을 바꾼다.
 *
 * 자료의 물리적 종류가 아니라 링크의 속성이므로 대상은 자료가 아니라 (자료, 프로젝트) 쌍이다.
 * 업로드는 이 값을 강제로 묻지 않고 OTHER로 시작할 수 있고, 그 뒤에 이 요청으로 고친다 —
 * 연결을 끊었다 다시 잇는 우회로가 유일한 수단이 되지 않게 하기 위한 경로다.
 */
@Getter
@Setter
public class MaterialLinkTypeUpdateRequest {

    @NotNull(message = "이 프로젝트에서 이 자료를 무엇으로 쓸지 골라주세요")
    private MaterialType materialType;
}
