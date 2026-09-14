package com.jungwoo.project.memo.material.dto;

import com.jungwoo.project.memo.material.domain.ProposalAction;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 제안 묶음 하나. 켜고 끄는 단위이자 apply의 단위다.
 *
 * matchingProjects가 필요한 이유: 동명 경고가 붙은 CREATE_AND_LINK 그룹은 existingCourseId가
 * null이다. 프론트가 경고 문장을 파싱하거나 제목으로 프로젝트 목록을 뒤져 id를 역추적하게
 * 만들면 안 된다. 동명 프로젝트가 둘 이상일 수 있어 배열이다.
 */
@Getter
@Builder
public class ProposalGroupResponse {

    private String groupId;
    private ProposalAction action;
    private Long existingCourseId;
    private String existingCourseTitle;
    private String proposedTitle;
    private String reason;

    /** 서버가 계산한다. 프론트는 재계산하지 않는다 — 근거의 품질을 아는 쪽은 서버다. */
    private boolean defaultSelected;

    private List<String> notices;

    /** 동명 경고가 붙었을 때의 실제 후보. 그 외에는 빈 배열이다. */
    private List<ProjectRef> matchingProjects;

    private List<ProposalMemberResponse> members;
}
