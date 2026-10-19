package com.jungwoo.project.memo.scheduling.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 이번 계산에서 배치하지 못한 항목. 오류가 아니라 "배치 가능한 시간이 부족함" 같은 사유를 담는다. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UnplacedItemDto {
    private Long proposalItemId;
    private String title;
    private String reason;
}
