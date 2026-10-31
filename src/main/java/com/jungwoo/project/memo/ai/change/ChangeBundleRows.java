package com.jungwoo.project.memo.ai.change;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 변경 묶음 테이블 행(ai_change_bundles, ai_change_bundle_items). */
public final class ChangeBundleRows {

    private ChangeBundleRows() {
    }

    public static final String PROPOSED = "PROPOSED";
    public static final String APPLIED = "APPLIED";
    public static final String DISMISSED = "DISMISSED";

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Bundle {
        private Long bundleId;
        private Long userId;
        private Long conversationId;
        private Long sourceMessageId;
        private String status;
        private Integer revision;
        private LocalDate rangeStart;
        private LocalDate rangeEnd;
        private String basisFingerprint;
        private String applyIdempotencyKey;
        private String applyRequestHash;
        private String applyResultJson;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private LocalDateTime resolvedAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Item {
        private Long itemId;
        private Long bundleId;
        private Long userId;
        private Integer seq;
        private String opType;
        private String targetKey;
        private String opJson;
        private String summary;
        private String dependsOn;
        private String origin;
        private Boolean selected;
        private String status;
        private String checkCode;
        private String checkMessage;
        private String resultJson;
        private LocalDateTime createdAt;
    }
}
