package com.jungwoo.project.memo.learning.week.dto;

import java.util.List;

public final class MaterialWeekRequests {

    private MaterialWeekRequests() {
    }

    /**
     * 자료 하나의 자리를 정한다(이동·선택·추천 확인). 그 자료의 기존 자리를 통째로 바꾼다.
     *
     * @param placement WEEK / COURSE_WIDE / UNASSIGNED
     * @param weeks     WEEK일 때 하나 이상(여러 주차에 걸친 자료면 여럿). 그 밖에는 비운다
     * @param source    SUGGESTION — 화면에 보인 추천을 그대로 확인했다. 서버가 지금 추천과 같은지 대조하고,
     *                  다르면 409로 거절한다(그 사이 추천이 바뀜). USER — 사용자가 직접 골랐다(드래그·선택)
     */
    public record Place(String placement, List<Integer> weeks, String source) {
    }

    /**
     * "추천대로 적용". 화면에 보였던 추천을 그대로 싣는다 — 서버는 지금 추천이 같고, 한꺼번에 적용해도 되는
     * 것(HIGH·MEDIUM)이고, 아직 확인 전인 것만 적용한다. 나머지는 이유와 함께 건너뛴다.
     */
    public record ApplySuggestions(List<Expected> items) {
    }

    public record Expected(Long materialId, String placement, Integer week) {
    }

    /**
     * @param applied 적용한 자료 수
     * @param skipped 건너뛴 자료와 이유(ALREADY_PLACED / CHANGED / NOT_BULK / NOT_FOUND)
     * @param review  적용 뒤의 확인 화면
     */
    public record ApplyResult(int applied, List<Skipped> skipped, MaterialWeekReviewResponse review) {
    }

    public record Skipped(Long materialId, String reason) {
    }
}
