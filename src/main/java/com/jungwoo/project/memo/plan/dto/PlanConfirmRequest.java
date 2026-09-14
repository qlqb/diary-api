package com.jungwoo.project.memo.plan.dto;

import com.jungwoo.project.memo.ai.dto.AiProposalApplyRequest;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 계획 확정 요청.
 *
 * ★ 기간·강도·목표 시간을 받지 않는다. 전부 ai_proposals에서 읽는다 — 클라이언트가 다시
 * 보내게 하면 초안과 다른 값으로 확정될 수 있고, 그러면 스냅샷의 기간과 항목의 planning_* 가
 * 어긋난다. 어긋났을 때 어느 쪽이 진실인지 알 방법이 없다.
 *
 * 항목 편집·제외는 기존 제안 적용과 같은 형식을 그대로 쓴다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlanConfirmRequest {

    private List<AiProposalApplyRequest.EditedProposalItem> editedItems;

    /** 초안에서 체크를 푼 항목. */
    private List<Long> excludedItemIds;

    private String title;

    private String goalSummary;
}
