package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.course.domain.Course;

/**
 * 계획 입력에 싣는 교재 사실. 화면에만 교재를 보이고 생성기는 예전 교재를 읽는 일이 없게, 지금 교재 칸(사용자 정정 포함)을
 * 그대로 옮긴다. 목차는 범위의 근거일 뿐 진도·시험 범위가 아니라는 것을 같은 줄에 적는다.
 */
final class PlanTextbookFacts {

    private PlanTextbookFacts() {
    }

    /** 「제목」 판 · 출판사 · 저자 — 확인 출처(직접 적음·자료·웹). 교재 칸이 비면 null. */
    static String identity(Course course) {
        return course == null ? null : com.jungwoo.project.memo.course.textbook.TextbookFacts.identity(
                course.getTextbookTitle(), course.getTextbookIsbn(), course.getTextbookEdition(),
                course.getTextbookPublisher(), course.getTextbookAuthor(), course.getTextbookInfoSource());
    }

    /**
     * 목차에서 온 학습 항목이 있으면 그 뜻을 한 줄로: 교재가 다루는 범위이지 진도·밀린 일·시험 범위가 아니다.
     */
    static String scopeLine(PlanMaterialContextService.CourseCatalog catalog) {
        if (catalog == null || catalog.topics() == null) {
            return null;
        }
        long fromToc = catalog.topics().stream().filter(t -> t.locator() != null
                && (t.locator().startsWith("교재 p.") || t.locator().equals("교재 목차"))).count();
        long prior = catalog.topics().stream().filter(PlanMaterialContextService.TopicLine::priorTextbook).count();
        if (fromToc == 0 && prior == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder("[교재 범위] 학습 항목 중 ").append(fromToc)
                .append("개는 교재 목차에서 왔다(목차 제목·쪽만 확인 — 본문은 확인하지 않았다). 책이 다루는 범위이지 실제 수업 진도·")
                .append("시험 범위·밀린 일이 아니다");
        if (prior > 0) {
            sb.append(". ").append(prior).append("개는 이전 교재 목차의 항목이라 지금 교재의 범위로 세지 않는다");
        }
        return sb.toString();
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }
}
