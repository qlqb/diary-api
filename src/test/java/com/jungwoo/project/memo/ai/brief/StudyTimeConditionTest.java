package com.jungwoo.project.memo.ai.brief;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 말한 시간이 사라지던 지점(2026-10-25 재현 R1~R6)의 회귀 테스트. 재현은 같은 커밋 소스(5d43c82)에서 여섯 개 모두 실패했다.
 */
class StudyTimeConditionTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 25);

    private static StudyTimeInterpreter.Utterance said(String text, int hour, int minute) {
        return new StudyTimeInterpreter.Utterance(1L, text, DAY.atTime(hour, minute));
    }

    private static StudyTimeInterpreter.Part window(String quote, String from, String until) {
        return new StudyTimeInterpreter.Part("WINDOW", quote, from, until, null, null, null, null);
    }

    private static StudyTimeCondition interpret(List<StudyTimeInterpreter.Part> parts, StudyTimeInterpreter.Utterance u) {
        return StudyTimeInterpreter.interpret(parts, null, List.of(u), DAY, 1);
    }

    @Test
    void R1_원문_그대로의_시간_합의는_사용자_작성이고_값은_따로_구조로_남는다() {
        PlanBriefMapper mapper = mock(PlanBriefMapper.class);
        when(mapper.findByConversationIdAndUserId(12L, 7L)).thenReturn(null);
        PlanBriefService service = new PlanBriefService(mapper,
                Clock.fixed(Instant.parse("2026-10-25T04:00:00Z"), ZoneId.of("Asia/Seoul")));
        String text = "오늘 2시부터 8시까지 공부할게";

        PlanBriefService.View view = service.applyTurn(7L, 12L, 300L, 301L, List.of(
                new PlanBriefOp("ADD", null, "TIME_BUDGET", text, "USER", "THIS_DRAFT", null, null, null, null, null,
                        null, "DAY", "14:00", "20:00", text)), null,
                List.of(new PlanBriefService.UserUtterance(300L, text)));

        PlanBriefItem item = view.items().get(0);
        assertThat(item.speaker()).isEqualTo("USER");
        assertThat(item.text()).isEqualTo(text); // 화면 문구(꼬리표)가 문장에 섞이지 않는다
        assertThat(item.timeCondition().adopted()).isTrue();
        assertThat(item.timeCondition().windows().get(0).start()).isEqualTo(LocalTime.of(14, 0));
        assertThat(item.timeCondition().windows().get(0).end()).isEqualTo(LocalTime.of(20, 0));
    }

    @Test
    void 사용자_문장이어도_모델이_적은_시각이_원문과_다르면_사용자_작성이_아니다() {
        PlanBriefMapper mapper = mock(PlanBriefMapper.class);
        PlanBriefService service = new PlanBriefService(mapper,
                Clock.fixed(Instant.parse("2026-10-25T04:00:00Z"), ZoneId.of("Asia/Seoul")));
        String text = "오늘 2시부터 8시까지 공부할게";
        PlanBriefService.View view = service.applyTurn(7L, 12L, 300L, 301L, List.of(
                new PlanBriefOp("ADD", null, "TIME_BUDGET", text, "USER", "THIS_DRAFT", null, null, null, null, null,
                        null, "DAY", "15:00", "20:00", text)), null,
                List.of(new PlanBriefService.UserUtterance(300L, text)));
        assertThat(view.items().get(0).speaker()).isEqualTo("ASSISTANT");
        assertThat(view.items().get(0).timeCondition().adopted()).isFalse();
    }

    @Test
    void R2_시작이_지났어도_조건을_버리지_않고_메모만_남긴다() {
        StudyTimeCondition c = interpret(List.of(window("2시부터 8시까지", "14:00", "20:00")),
                said("2시부터 8시까지 공부할게", 14, 5));
        assertThat(c.adopted()).isTrue();
        assertThat(c.windows().get(0).start()).isEqualTo(LocalTime.of(14, 0));
        assertThat(c.notes()).anyMatch(n -> n.contains("이미 지나"));
    }

    @Test
    void R3_식사_시각은_제외_구간으로_따로_확인된다() {
        StudyTimeInterpreter.Utterance u = said("2시부터 8시까지 공부하고 6시에는 저녁 먹어", 13, 0);
        StudyTimeCondition c = interpret(List.of(window("2시부터 8시까지", "14:00", "20:00"),
                new StudyTimeInterpreter.Part("EXCLUSION", "6시에는 저녁", "06:00", null, null, null, null, "저녁")), u);
        assertThat(c.adopted()).isTrue();
        assertThat(c.exclusions()).hasSize(1);
        // 오전·오후를 말하지 않은 6시는 공부 구간 안의 18시로 본다. 끝을 말하지 않아 1시간으로 본다(메모로 알린다).
        assertThat(c.exclusions().get(0).start()).isEqualTo(LocalTime.of(18, 0));
        assertThat(c.exclusions().get(0).end()).isEqualTo(LocalTime.of(19, 0));
        assertThat(c.notes()).anyMatch(n -> n.contains("60분"));
    }

    @Test
    void R4_하루_계획의_분량은_단위_말이_없어도_채택하고_여러_날이면_묻는다() {
        StudyTimeInterpreter.Part amount = new StudyTimeInterpreter.Part("AMOUNT", "3시간만", null, null, null, 180, null, null);
        StudyTimeInterpreter.Utterance u = said("3시간만 공부할래", 13, 0);
        StudyTimeCondition one = StudyTimeInterpreter.interpret(List.of(amount), null, List.of(u), DAY, 1);
        assertThat(one.adopted()).isTrue();
        assertThat(one.minutes()).isEqualTo(180);

        StudyTimeCondition multi = StudyTimeInterpreter.interpret(List.of(amount), null, List.of(u), DAY, 3);
        assertThat(multi.status()).isEqualTo(StudyTimeCondition.AMBIGUOUS);
        assertThat(multi.issues()).anyMatch(i -> i.code().equals("AMBIGUOUS_UNIT"));
    }

    @Test
    void R5_붙여_쓴_물결표와_하이픈_구간을_읽는다() {
        for (String q : List.of("2시~8시", "14시~20시", "2시-8시")) {
            StudyTimeCondition c = interpret(List.of(window(q, "14:00", "20:00")), said(q + " 공부", 13, 0));
            assertThat(c.adopted()).as(q).isTrue();
        }
        // 지속 시간의 범위("1~2시간")는 여전히 읽지 않는다 — 어느 쪽인지 서버가 정할 수 없다.
        assertThat(SaidTime.durations("1~2시간 공부")).isEmpty();
    }

    @Test
    void R6_밤에_말한_새벽_1시까지는_다음_날이고_새벽에_말하면_그날이다() {
        StudyTimeCondition night = interpret(List.of(window("새벽 1시까지", null, "01:00")), said("새벽 1시까지", 21, 6));
        assertThat(night.windows().get(0).endsNextDay()).isTrue();
        LocalDateTime[] on = night.windows().get(0).on(DAY, StudyTimeInterpreter.DEFAULT_START);
        assertThat(on[1]).isEqualTo(DAY.plusDays(1).atTime(1, 0));

        StudyTimeCondition early = interpret(List.of(window("새벽 1시까지", null, "01:00")), said("새벽 1시까지", 0, 30));
        assertThat(early.windows().get(0).endsNextDay()).isFalse();
    }

    @Test
    void 자정까지와_밤_11시부터_1시까지() {
        StudyTimeCondition midnight = interpret(List.of(window("밤 12시까지", null, "24:00")), said("밤 12시까지 공부", 20, 0));
        assertThat(midnight.windows().get(0).on(DAY, StudyTimeInterpreter.DEFAULT_START)[1])
                .isEqualTo(DAY.plusDays(1).atStartOfDay());

        StudyTimeCondition wrap = interpret(List.of(window("밤 11시부터 1시까지", "23:00", "01:00")),
                said("밤 11시부터 1시까지 할게", 20, 0));
        assertThat(wrap.adopted()).isTrue();
        assertThat(wrap.windows().get(0).endsNextDay()).isTrue();
    }

    @Test
    void 모델이_새벽으로_적어도_말한_시각이_지났으면_오후로_바로잡고_알린다() {
        StudyTimeCondition c = interpret(List.of(window("2시부터 8시까지", "02:00", "08:00")),
                said("2시부터 8시까지 공부할게", 13, 0));
        assertThat(c.windows().get(0).start()).isEqualTo(LocalTime.of(14, 0));
        assertThat(c.notes()).anyMatch(n -> n.contains("오전·오후"));
    }

    @Test
    void 둘_다_그럴듯하면_모델의_해석을_쓰고_해석을_밝힌다() {
        StudyTimeCondition c = interpret(List.of(window("8시부터 10시까지", "20:00", "22:00")),
                said("8시부터 10시까지 공부", 7, 0));
        assertThat(c.adopted()).isTrue();
        assertThat(c.windows().get(0).start()).isEqualTo(LocalTime.of(20, 0));
        assertThat(c.notes()).anyMatch(n -> n.contains("20:00~22:00"));
    }

    @Test
    void 원문에_없는_값과_구절은_버리지_않고_이유를_남긴다() {
        StudyTimeCondition wrongValue = interpret(List.of(window("2시부터 8시까지", "15:00", "20:00")),
                said("2시부터 8시까지 공부", 13, 0));
        assertThat(wrongValue.status()).isEqualTo(StudyTimeCondition.VERIFY_FAILED);
        assertThat(wrongValue.issues()).extracting(StudyTimeCondition.Issue::code).containsExactly("VALUE_MISMATCH");

        StudyTimeCondition notSaid = interpret(List.of(window("3시부터 9시까지", "15:00", "21:00")),
                said("2시부터 8시까지 공부", 13, 0));
        assertThat(notSaid.issues()).extracting(StudyTimeCondition.Issue::code).containsExactly("NOT_FOUND");
    }

    @Test
    void 나중_발언의_시간_표현은_조건을_버리지_않고_반영되지_않았다고_알린다() {
        List<StudyTimeInterpreter.Utterance> utterances = List.of(
                new StudyTimeInterpreter.Utterance(1L, "2시부터 8시까지 공부할게", DAY.atTime(13, 0)),
                new StudyTimeInterpreter.Utterance(2L, "6시에 친구 잠깐 봐", DAY.atTime(13, 5)));
        StudyTimeCondition c = StudyTimeInterpreter.interpret(List.of(window("2시부터 8시까지", "14:00", "20:00")), null,
                utterances, DAY, 1);
        assertThat(c.adopted()).isTrue();
        assertThat(c.issues()).anyMatch(i -> i.code().equals("LATER_TIME_UNUSED"));
    }
}
