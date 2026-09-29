package com.jungwoo.project.memo.learning.week.dto;

import java.util.List;

/**
 * 프로젝트의 자료 주차 확인 화면. 확정 자리(assignment)와 추천(suggestion)을 따로 싣는다 — 둘을 한 값으로
 * 합치면 화면이 "AI가 3주차라고 했다"와 "사용자가 3주차로 정했다"를 구분할 수 없다.
 *
 * @param weekCount   보여 줄 주차 칸 수. 기본 15, 확정·추천·예정 진도에 더 뒤 주차가 있으면 거기까지
 * @param needsReview 아직 어디에도 놓이지 않은 자료 수(분석이 도는 중인 자료는 빼고 센다)
 */
public record MaterialWeekReviewResponse(Long courseId, String courseTitle, int weekCount, int needsReview,
                                         List<Item> items) {

    /**
     * @param materialType      이 프로젝트에서의 자료 역할(SYLLABUS 등). 추천 근거가 아니라 화면 표시용이다
     * @param analysisState     자동 분석 상태(QUEUED/RUNNING/DONE/…). 돌고 있으면 추천이 더 좋아질 수 있다
     * @param assignment        사용자가 확인한 자리. 아직 없으면 null
     * @param suggestion        추천. 근거가 없으면 null
     * @param suggestionDiffers 확정 자리가 있는데 새 추천이 다른 곳을 가리킨다. 자동으로 바꾸지 않고 알리기만 한다
     */
    public record Item(Long materialId, String filename, String materialType, String analysisState,
                       Assignment assignment, Suggestion suggestion, boolean suggestionDiffers) {
    }

    /**
     * @param placement WEEK / COURSE_WIDE / UNASSIGNED
     * @param weeks     placement=WEEK일 때의 주차(오름차순, 하나 이상). 그 밖에는 빈 목록
     * @param source    SUGGESTION(추천을 적용함 — "확인됨") / USER(직접 지정)
     */
    public record Assignment(String placement, List<Integer> weeks, String source) {
    }

    /**
     * @param placement      WEEK / COURSE_WIDE. CONFLICT면 null이고 options 중에서 고른다
     * @param confidence     HIGH / MEDIUM / LOW / CONFLICT
     * @param bulkApplicable "추천대로 적용"에 들어가는가(HIGH·MEDIUM)
     * @param evidence       근거 신호. 모델의 자유 서술이 아니라 신호 그 자체다
     */
    public record Suggestion(String placement, Integer week, String confidence, boolean bulkApplicable,
                             List<Option> options, List<Evidence> evidence) {
    }

    public record Option(String placement, Integer week) {
    }

    /** @param kind FILENAME_WEEK / DOCUMENT_TITLE_WEEK / CONTENT_WEEK / FILENAME_NUMBER / FIRST_WEEK_NAME / NEXT_LECTURE / PRACTICE_OF / COURSE_PLAN */
    public record Evidence(String kind, String detail) {
    }
}
