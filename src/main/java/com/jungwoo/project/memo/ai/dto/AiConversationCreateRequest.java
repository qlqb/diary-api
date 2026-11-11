package com.jungwoo.project.memo.ai.dto;

import com.jungwoo.project.memo.ai.domain.AiProposalTargetScope;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiConversationCreateRequest {

    /**
     * 이 대화가 무엇을 참고할지. 생략하면 TODAY.
     * TODAY=오늘 실행, EXECUTION=이번 주 일정, PLAN=프로젝트 안, MIXED=전체.
     */
    private AiProposalTargetScope scope;

    /** 프로젝트 안에서 시작한 대화면 그 프로젝트. 소유권은 서버가 검증한다. */
    private Long courseId;
}
