package com.jungwoo.project.memo.ai.dto;

/**
 * 모델이 만든 오늘 실행 후보 하나.
 *
 * priority는 문자열로 받아 서버가 MUST/SHOULD/OPTIONAL 여부를 검증한다.
 * 날짜와 placementType은 모델 출력에 맡기지 않는다 — 서버가 요청의 targetDate와
 * DATE_ONLY를 강제한다.
 */
public record ProposalItem(
        String title,
        String description,
        int expectedMinutes,
        String priority
) {
}
