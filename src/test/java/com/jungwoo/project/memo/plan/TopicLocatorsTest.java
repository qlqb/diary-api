package com.jungwoo.project.memo.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 주차 파싱. 예시는 전부 로컬 DB의 course_topics.source_locator에 실제로 있는 값이다 —
 * 지어낸 입력으로 만든 파서는 실제 데이터에서 조용히 빗나간다.
 */
class TopicLocatorsTest {

    @Test
    @DisplayName("가장 흔한 모양: N주차")
    void plainWeek() {
        assertThat(TopicLocators.weekOf("2주차")).isEqualTo(2);
        assertThat(TopicLocators.weekOf("13주차")).isEqualTo(13);
    }

    @Test
    @DisplayName("공백이 섞여도 읽는다")
    void weekWithSpace() {
        assertThat(TopicLocators.weekOf("6 주차")).isEqualTo(6);
    }

    @Test
    @DisplayName("여러 주에 걸친 항목은 가장 이른 주차로 본다 — 그 수업 때 이미 필요하기 때문이다")
    void multipleWeeksTakeTheEarliest() {
        assertThat(TopicLocators.weekOf("9주차, 12주차")).isEqualTo(9);
        assertThat(TopicLocators.weekOf("6주차; 9주차")).isEqualTo(6);
        assertThat(TopicLocators.weekOf("8주차; 15주차")).isEqualTo(8);
    }

    @Test
    @DisplayName("교재 위치가 함께 적혀 있어도 그 숫자를 주차로 읽지 않는다")
    void ignoresNumbersThatAreNotWeeks() {
        assertThat(TopicLocators.weekOf("7주차, (Ch03-Lesson 04)")).isEqualTo(7);
        assertThat(TopicLocators.weekOf("Ch03-Lesson 04")).isNull();
    }

    @Test
    @DisplayName("주차가 없으면 null이다 — 0주차가 아니라 모른다는 뜻")
    void noWeek() {
        assertThat(TopicLocators.weekOf("과목 개요")).isNull();
        assertThat(TopicLocators.weekOf("")).isNull();
        assertThat(TopicLocators.weekOf(null)).isNull();
    }

    @Test
    @DisplayName("원문 표기가 섞인 값도 읽는다")
    void weekWithTrailingNote() {
        assertThat(TopicLocators.weekOf("7주차 (원문에 '힢' 표기)")).isEqualTo(7);
    }
}
