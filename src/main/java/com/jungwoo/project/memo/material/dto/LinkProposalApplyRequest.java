package com.jungwoo.project.memo.material.dto;

import com.jungwoo.project.memo.material.domain.MaterialType;
import com.jungwoo.project.memo.material.domain.ProposalAction;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 사용자가 승인한 것을 그대로 적는 명령.
 *
 * 제안 응답을 되돌려받지 않는다 — 사용자가 제목·역할·목적지를 편집할 수 있으므로 실제
 * 적용값만 받는다. groupId도 받지 않는다: 서버가 제안을 저장하지 않으므로 대조할 대상이
 * 없고, 요청 자체를 완결된 명령으로 취급한다.
 */
@Getter
@Setter
public class LinkProposalApplyRequest {

    @NotEmpty(message = "적용할 묶음이 없습니다")
    @Valid
    private List<ApplyGroup> groups;

    @Getter
    @Setter
    public static class ApplyGroup {

        /** LEAVE는 400이다 — "그냥 둔다"는 적용할 것이 없다는 뜻이라 명령이 될 수 없다. */
        @NotNull(message = "묶음을 어떻게 처리할지 지정해주세요")
        private ProposalAction action;

        /** LINK_EXISTING일 때 필수. */
        private Long existingCourseId;

        /** CREATE_AND_LINK일 때 필수. 사용자가 편집했을 수 있다. */
        private String title;

        @NotEmpty(message = "묶음에 자료가 없습니다")
        @Valid
        private List<ApplyMember> members;
    }

    @Getter
    @Setter
    public static class ApplyMember {

        @NotNull
        private Long materialId;

        @NotNull(message = "이 프로젝트에서 자료를 무엇으로 쓸지 골라주세요")
        private MaterialType materialType;
    }
}
