package com.jungwoo.project.memo.course.textbook.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * textbook_lookups.result_json의 모양. 후보 리비전과 판정, 판본 묶음.
 *
 * @param searchedWith web_search · isbn_cache · link · reused
 * @param note         사용자에게 그대로 보일 수 있는 짧은 설명(없으면 null)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LookupResult(Query clue, String searchedWith, List<Candidate> candidates, List<Edition> editions,
                           List<Failure> failures, List<SyllabusOption> clueOptions, String note) {

    /** 외부로 보낸 단서(사용자에게도 그대로 보인다). link는 사용자 링크(쿼리 마스킹) */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Query(String title, String author, String publisher, String isbn, String edition, String link,
                        Boolean needsClue) {
    }

    /**
     * @param verdict MATCH · MISMATCH · UNVERIFIED · LINK(사용자가 준 링크 — 사용자가 확인한다)
     * @param url     공유 페이지는 그대로, 사용자 페이지는 쿼리를 가린 주소
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Candidate(Long revisionId, String site, String url, String verdict, List<String> reasons,
                            String isbn13, String title, String authors, String publisher, String publishedDate,
                            String edition, String tocCoverage, int tocEntryCount, String fetchedAt) {
    }

    /**
     * 같은 책·같은 판(ISBN)으로 묶은 것. bestRevisionId는 그 판의 페이지 중 목차를 가장 온전히 읽은 리비전.
     *
     * @param sameTocAs 목차 항목이 이 판과 똑같은 다른 판의 key
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Edition(String key, String isbn13, String title, String authors, String publisher,
                          String publishedDate, String edition, Long bestRevisionId, String site, String url,
                          String tocCoverage, int tocEntryCount, List<Long> revisionIds, List<String> sameTocAs) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Failure(String url, String status) {
    }

    /** CLUE_CONFLICT일 때 고를 수 있는 강의계획서 단서. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SyllabusOption(String title, String author, String publisher, String isbn, String edition,
                                 String role, Long materialId, String filename) {
    }
}
