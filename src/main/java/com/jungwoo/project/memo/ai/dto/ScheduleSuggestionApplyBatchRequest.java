package com.jungwoo.project.memo.ai.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 여러 후보를 한 번에 적용해 달라는 요청.
 *
 * <p>editedPayload가 없다. 일괄 적용은 사용자가 카드를 하나하나 고치지 <b>않고</b> 그대로
 * 받아들이는 동작이다. 고칠 것이 있으면 그 한 건만 {@code /apply}로 보내면 된다 — 여기에
 * 수정 필드를 두면 "무엇을 고쳐서 무엇을 승인했는지"가 한 요청 안에서 흐려진다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScheduleSuggestionApplyBatchRequest {

    @NotEmpty(message = "적용할 후보를 하나 이상 지정해야 합니다")
    private List<Long> suggestionIds;
}
