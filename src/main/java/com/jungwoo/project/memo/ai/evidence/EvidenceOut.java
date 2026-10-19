package com.jungwoo.project.memo.ai.evidence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 상담 모델이 구조화 출력의 {@code evidence}에 내는 것(검증 전).
 *
 * @param used     답변에 실제로 쓴 근거 번호(E#·F#·S#). 이번 턴에 보여 준 번호만 인정한다
 * @param readMore 더 읽어야 답할 수 있을 때의 요청. 한 턴에 한 번만 받는다
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EvidenceOut(List<String> used, List<ReadRequest> readMore) {

    /**
     * 추가 읽기 하나. 셋 중 하나를 채운다.
     *
     * @param ref   M#(자료 — units로 범위 지정), S#(분석 구간 — 그 구간의 원문), E#(이미 본 원문 — 그 단위 전체)
     * @param units "4-6"처럼 단위 번호 범위(M#에서만)
     * @param query 새 검색어(공백으로 구분). refs가 있으면 그 자료 안에서만 찾는다
     * @param refs  query를 찾을 자료(M#)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReadRequest(String ref, String units, String query, List<String> refs) {
    }
}
