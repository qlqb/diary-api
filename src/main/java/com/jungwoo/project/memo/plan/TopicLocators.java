package com.jungwoo.project.memo.plan;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * course_topics.source_locator에서 주차를 읽는다.
 *
 * <p>주차 컬럼을 따로 만들지 않는다. locator는 자료 분석이 원문에서 뽑아 적어 둔 위치
 * 문자열이고, 거기서 파생되는 값을 컬럼으로 굳히면 분석을 다시 돌릴 때 두 값이 어긋난다.
 * 파싱은 계획을 만드는 순간에만 필요하고, 그 비용은 topic 수백 개에 정규식 한 번이다.
 *
 * <p>실제 값의 모양(로컬 DB 271행 기준):
 * <pre>
 *   "2주차"                      대부분
 *   "6 주차"                     공백이 섞인다
 *   "9주차, 12주차"              여러 주에 걸친 내용
 *   "7주차, (Ch03-Lesson 04)"    교재 위치가 함께 적힌다
 *   "과목 개요"                  주차가 아예 없다
 * </pre>
 *
 * <p>여러 개면 가장 이른 주차를 쓴다. 9주차와 12주차에 걸친 내용은 9주차 수업을 들으려면
 * 이미 필요하기 때문이다 — 늦은 쪽을 쓰면 그 항목이 계획 창 밖으로 밀려난다.
 */
public final class TopicLocators {

    /**
     * "12주차", "12 주차" 모두 잡는다. 앞의 정수만 본다 — "Ch03-Lesson 04"의 숫자를
     * 주차로 읽지 않기 위해 반드시 "주차"가 뒤따라야 한다.
     */
    private static final Pattern WEEK = Pattern.compile("(\\d{1,2})\\s*주차");

    private TopicLocators() {
    }

    /**
     * 이 locator가 가리키는 주차. 주차를 읽을 수 없으면 null이다.
     *
     * <p>null은 "0주차"가 아니라 "모른다"다. 호출부는 이 둘을 섞지 말아야 한다 — 모르는
     * 항목을 0주차로 취급하면 계획 앞머리가 "과목 개요" 같은 항목으로 채워진다.
     */
    public static Integer weekOf(String sourceLocator) {
        if (sourceLocator == null || sourceLocator.isBlank()) {
            return null;
        }
        Matcher matcher = WEEK.matcher(sourceLocator);
        Integer earliest = null;
        while (matcher.find()) {
            int week = Integer.parseInt(matcher.group(1));
            if (week <= 0) {
                continue;
            }
            if (earliest == null || week < earliest) {
                earliest = week;
            }
        }
        return earliest;
    }
}
