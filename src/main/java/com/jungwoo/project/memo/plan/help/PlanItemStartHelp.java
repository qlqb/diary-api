package com.jungwoo.project.memo.plan.help;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** plan_item_start_helps 한 행. 항목의 첫 행동을 구체화한 안내이며 항목의 범위·시간·마감은 바꾸지 않는다. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlanItemStartHelp {
    private Long helpId;
    private Long userId;
    private Long proposalItemId;
    private Long executionItemId;
    private String evidenceVersion;
    private String requestKind;
    private String requestText;
    private String helpJson;
    private String model;
    private LocalDateTime createdAt;
}
