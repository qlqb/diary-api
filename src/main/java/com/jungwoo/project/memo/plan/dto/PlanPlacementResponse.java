package com.jungwoo.project.memo.plan.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 롤링 배치 결과.
 *
 * 못 들어간 항목은 실패가 아니다 — 그 주에 자리가 없었을 뿐이고 다음 창에서 다시 시도한다.
 * 화면 문구도 "이번 주에는 자리가 없어 날짜 미정으로 남겨뒀어요"다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlanPlacementResponse {

    private Long planVersionId;

    private LocalDate windowStart;

    private LocalDate windowEnd;

    private List<PlacedItem> placed;

    /** 창에 자리가 없어 UNSCHEDULED로 남은 항목. */
    private List<UnplacedItem> unplaced;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PlacedItem {
        private Long executionItemId;
        private String title;
        private LocalDate scheduledDate;
        private LocalDateTime scheduledStartAt;
        private LocalDateTime scheduledEndAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class UnplacedItem {
        private Long executionItemId;
        private String title;
        private Integer expectedMinutes;
        /** 날짜가 정해진 항목이면 그 날짜(그 날 안에서만 시각을 찾았다). */
        private LocalDate scheduledDate;
        /** 왜 못 넣었는지 — "9/18에 남는 시간이 없어요"처럼 사용자에게 보여 주는 문장. */
        private String reason;
    }
}
