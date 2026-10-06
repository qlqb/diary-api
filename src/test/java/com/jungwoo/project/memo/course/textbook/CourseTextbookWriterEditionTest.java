package com.jungwoo.project.memo.course.textbook;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 같은 책의 식별 보강(빈 칸 채우기·표기 고치기)과 다른 판으로의 정정을 가른다. 다른 판이면 이은 목차·웹 근거·항목의
 * 책 열쇠를 옮기지 않는다.
 */
class CourseTextbookWriterEditionTest {

    private static CourseTextbookWriter.Values v(String isbn, String edition) {
        return new CourseTextbookWriter.Values("자료구조", "저자", "출판사", isbn, edition);
    }

    @Test
    void 빈_칸을_채우거나_표기만_고치면_같은_판이다() {
        assertThat(CourseTextbookWriter.editionChanged(v(null, null), v("979-11-7340-066-7", null))).isFalse();
        assertThat(CourseTextbookWriter.editionChanged(v(null, null), v(null, "개정 2판"))).isFalse();
        assertThat(CourseTextbookWriter.editionChanged(v("9791173400667", "2판"), v("979-11-7340-066-7", "2 판"))).isFalse();
        assertThat(CourseTextbookWriter.sameEdition(v("9791173400667", "2판"), v("979-11-7340-066-7", "2 판"))).isTrue();
    }

    @Test
    void 있던_ISBN이나_판을_바꾸거나_지우면_다른_판이다() {
        assertThat(CourseTextbookWriter.editionChanged(v("9791173400667", null), v(null, "개정 2판"))).isTrue();
        assertThat(CourseTextbookWriter.editionChanged(v("9791173400667", null), v("9791156645412", null))).isTrue();
        assertThat(CourseTextbookWriter.editionChanged(v(null, "2판"), v(null, "3판"))).isTrue();
        // 웹 근거 유지는 더 엄격하다 — 빈 칸을 채운 것도 그 리비전과 같은 판인지 모르므로 유지하지 않는다.
        assertThat(CourseTextbookWriter.sameEdition(v(null, null), v(null, "개정 2판"))).isFalse();
    }
}
