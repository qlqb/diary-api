package com.jungwoo.project.memo.material.analysis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * CONTENT 분석의 모델 출력. 청크 하나당 하나.
 *
 * <p>모르는 필드는 무시한다(모델이 스키마 밖 필드를 붙여도 파싱 실패로 청크를 버리지 않기 위해).
 * 값의 검증은 {@link MaterialContentAnalyzer}가 한다 — 여기서는 모양만 받는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ContentAnalysisPayload(List<Section> sections, DocMeta docMeta) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Section(Integer unitStart, Integer unitEnd, Integer printedPageStart, Integer printedPageEnd,
                          String label, String title, List<String> roles, String task, String excerpt,
                          Boolean assignmentCue, String assignmentQuote, List<DateCandidate> dates) {
    }

    /**
     * @param text     원문 표현 그대로
     * @param isoDate  연도까지 확실할 때만 YYYY-MM-DD. 연도를 모르면 null
     * @param monthDay 연도 없이 월·일만 있을 때 "MM-DD"
     * @param kind     DUE(제출) / EXAM / CLASS / OTHER
     * @param relative "다음 수업까지"처럼 기준일이 있어야 해석되는 표현인가
     * @param basis    isoDate를 정한 근거(원문에 연도가 있음 등)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DateCandidate(String text, String isoDate, String monthDay, String kind, Boolean relative,
                                String basis) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DocMeta(String documentDate, String weekLabel, Boolean hasPrintedPageNumbers, Boolean looksScanned) {
    }
}
