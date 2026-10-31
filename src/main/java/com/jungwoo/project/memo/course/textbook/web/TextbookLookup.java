package com.jungwoo.project.memo.course.textbook.web;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** textbook_lookups 한 행 — 과목 하나의 교재 조회 작업(상태의 원본). */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TextbookLookup {

    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";
    public static final String FOUND = "FOUND";
    public static final String NEEDS_CHOICE = "NEEDS_CHOICE";
    public static final String BOOK_NO_TOC = "BOOK_NO_TOC";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String ACCESS_FAILED = "ACCESS_FAILED";
    public static final String FAILED = "FAILED";
    public static final String CLUE_CONFLICT = "CLUE_CONFLICT";
    public static final String SUPERSEDED = "SUPERSEDED";
    public static final String CANCELLED = "CANCELLED";
    public static final String DISABLED = "DISABLED";

    private Long lookupId;
    private Long userId;
    private Long courseId;
    private String basisKey;
    private String queryKey;
    private String queryJson;
    private String clueOrigin;
    private Long clueMaterialId;
    private String clueFileHash;
    private Integer clueExtractorVersion;
    private Integer textbookVersion;
    private boolean forceRefresh;
    private String status;
    private Integer attempt;
    private Integer maxAttempts;
    private LocalDateTime nextRunAt;
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    private Integer leaseToken;
    private String resultJson;
    private Long chosenRevisionId;
    private Long reusedFromLookupId;
    private String autoTidyState;
    private Long tidyJobId;
    private LocalDateTime autoTidyNextAt;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime finishedAt;

    public boolean isOpen() {
        return QUEUED.equals(status) || RUNNING.equals(status);
    }
}
