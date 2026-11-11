package com.jungwoo.project.memo.course.textbook.web;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * textbook_web_revisions 한 행 — 한 번 쓰면 바꾸지 않는 웹 근거. url·site·cacheScopeKey는 페이지에서 함께 읽는다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TextbookWebRevision {
    private Long revisionId;
    private Long pageId;
    private String contentHash;
    private Integer parserVersion;
    private LocalDateTime fetchedAt;
    private Integer httpStatus;
    private String isbn13;
    private String title;
    private String authors;
    /** 저자 소개의 다른 표기(로마자 이름 등). 일치 판정의 보조 근거. */
    private String authorNotes;
    private String publisher;
    private String publishedDate;
    private String edition;
    private String tocRaw;
    private String tocJson;
    private int tocEntryCount;
    private String tocCoverage;
    /** 목차 구조화 규칙의 판({@link WebTocStructurer#TOC_VERSION}). 옛 판이면 저장된 원문으로 다시 구조화한다. */
    private Integer tocVersion;
    /** 목차 원문(toc_raw, UTF-8)의 SHA-256. 목차 항목 열쇠(원문 줄)가 기대는 원문이다. */
    private String tocRawHash;
    /** 받은 목차 원문이 잘렸나(서점이 접어 실음·저장 상한). NULL = 기록 전 리비전. */
    private Boolean tocRawTruncated;
    private LocalDateTime createdAt;

    // 페이지에서 함께 읽는 값
    private String url;
    private String site;
    private String cacheScopeKey;
}
