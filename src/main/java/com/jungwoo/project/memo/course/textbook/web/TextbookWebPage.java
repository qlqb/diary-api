package com.jungwoo.project.memo.course.textbook.web;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** textbook_web_pages 한 행 — URL 하나의 캐시 자리. 근거는 리비전이다. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TextbookWebPage {
    private Long pageId;
    private String cacheScopeKey;
    private String urlHash;
    private String url;
    private String site;
    private Long latestRevisionId;
    private LocalDateTime lastFetchedAt;
    private String lastFetchStatus;
    private LocalDateTime createdAt;
}
