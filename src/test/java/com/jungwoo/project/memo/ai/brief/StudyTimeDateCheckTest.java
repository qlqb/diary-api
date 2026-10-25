package com.jungwoo.project.memo.ai.brief;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Codex 코드 리뷰 2회차(2026-10-26): 모델이 정리한 날짜는 말한 낱말과 맞아야 쓰고, 비었으면 낱말에서 정한다. */
class StudyTimeDateCheckTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 26); // 월요일

    private static StudyTimeCondition window(String said, String quote, String date, LocalDate planStart, int days) {
        return StudyTimeInterpreter.interpret(
                List.of(new StudyTimeInterpreter.Part("WINDOW", quote, "14:00", "20:00", date, null, null, null)), null,
                List.of(new StudyTimeInterpreter.Utterance(1L, said, DAY.atTime(10, 0))), planStart, days);
    }

    @Test
    void 내일이라고_했는데_모델이_오늘로_정리하면_채택하지_않는다() {
        StudyTimeCondition c = window("내일 2시부터 8시까지 공부할게", "내일 2시부터 8시까지", DAY.toString(), DAY, 3);
        assertThat(c.adopted()).isFalse();
        assertThat(c.issues()).anyMatch(i -> "VALUE_MISMATCH".equals(i.code()));
    }

    @Test
    void 날짜가_비면_내일이라는_낱말에서_정한다() {
        StudyTimeCondition c = window("내일 2시부터 8시까지 공부할게", "내일 2시부터 8시까지", null, DAY, 3);
        assertThat(c.adopted()).isTrue();
        assertThat(c.windows().get(0).date()).isEqualTo(DAY.plusDays(1));
    }

    @Test
    void 오늘과_내일이_함께_나오면_구절의_낱말을_따른다() {
        StudyTimeCondition c = window("오늘은 쉬고 내일 2시부터 8시까지", "내일 2시부터 8시까지", DAY.plusDays(1).toString(), DAY, 3);
        assertThat(c.adopted()).isTrue();
        assertThat(c.windows().get(0).date()).isEqualTo(DAY.plusDays(1));
    }

    @Test
    void 요일과_월일로_말한_날짜는_받는다() {
        assertThat(window("수요일 2시부터 8시까지", "수요일 2시부터 8시까지", "2026-10-28", DAY, 7).adopted()).isTrue();
        assertThat(window("10월 27일 2시부터 8시까지", "10월 27일 2시부터 8시까지", "2026-10-27", DAY, 7).adopted()).isTrue();
        assertThat(window("수요일 2시부터 8시까지", "수요일 2시부터 8시까지", "2026-10-29", DAY, 7).adopted()).isFalse();
    }

    @Test
    void 날짜_말이_없으면_계획_기간_밖의_날짜는_받지_않는다() {
        assertThat(window("2시부터 8시까지 공부", "2시부터 8시까지", "2026-10-27", DAY, 3).adopted()).isTrue();
        assertThat(window("2시부터 8시까지 공부", "2시부터 8시까지", "2026-11-05", DAY, 3).adopted()).isFalse();
    }

    @Test
    void 계획_기간을_알면_기간_밖의_날짜는_조용히_버리지_않고_알린다() {
        StudyTimeCondition c = StudyTimeInterpreter.interpret(
                List.of(new StudyTimeInterpreter.Part("WINDOW", "오늘 2시부터 8시까지", "14:00", "20:00", null, null, null, null)),
                null, List.of(new StudyTimeInterpreter.Utterance(1L, "오늘 2시부터 8시까지", DAY.atTime(10, 0))),
                DAY.plusDays(1), 3, true);
        assertThat(c.adopted()).isFalse();
        assertThat(c.issues()).anyMatch(i -> "OUT_OF_PERIOD".equals(i.code()));
    }
}
