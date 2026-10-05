package com.jungwoo.project.memo.learning;

/**
 * 교재 목차에서 온 학습 항목의 판정·표시. 상담·자료 선택·계획 렌더러가 같은 규칙을 쓴다.
 *
 * <p>목차 항목은 제목·쪽만 확인한 것이다(본문 미확인). 같은 교재 목차 안의 원본 순번을 함께 보여 제목이 같은 단원(예:
 * Unit 1과 Unit 10이 둘 다 "What's your name?")을 구분한다.
 */
public final class TocTopics {

    private TocTopics() {
    }

    /** locator가 목차 출처("교재 p.N" 또는 "교재 목차")인가. */
    public static boolean isToc(String locator) {
        return locator != null && (locator.startsWith("교재 p.") || locator.equals("교재 목차"));
    }

    /** 괄호 안에 붙일 꼬리: " — 교재 목차 3번째, 제목만 확인" (순번을 모르면 " — 목차 제목만 확인"). */
    public static String suffix(Integer tocSeq) {
        return tocSeq == null ? " — 목차 제목만 확인" : " — 교재 목차 " + tocSeq + "번째, 제목만 확인";
    }
}
