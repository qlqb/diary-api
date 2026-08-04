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

    /** 생략하면 TODAY로 시작한다 — 지금 화면은 오늘 상담뿐이다. */
    private AiProposalTargetScope scope;
}
