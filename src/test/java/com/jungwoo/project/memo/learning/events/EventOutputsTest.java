package com.jungwoo.project.memo.learning.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jungwoo.project.memo.learning.events.EventOutputs.Output;
import com.jungwoo.project.memo.learning.events.EventVocabulary.Actor;
import com.jungwoo.project.memo.learning.events.EventVocabulary.Evidence;
import com.jungwoo.project.memo.learning.events.EventVocabulary.Verb;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 같은 뜻이면 같은 출력, 다른 뜻(주장 시각 포함)이면 다른 출력. 순서는 다시 돌려도 같다. */
class EventOutputsTest {

    private final ObjectMapper om = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 6, 9, 0, 0, 123_456_789);

    private static EventDraft stuck(long section) {
        return EventDraft.of(Actor.ME, Verb.STUCK, SourceRef.section(section), new Payloads.Stuck(), Evidence.STATED);
    }

    @Test
    void 중복은_하나로_순서는_입력과_무관하다() {
        List<Output> a = EventOutputs.normalize(List.of(stuck(2), stuck(1), stuck(2)), om);
        List<Output> b = EventOutputs.normalize(List.of(stuck(1), stuck(2)), om);

        assertThat(a).hasSize(2);
        assertThat(a.stream().map(Output::objectRef)).containsExactlyElementsOf(b.stream().map(Output::objectRef).toList());
        assertThat(EventOutputs.sameOutputs(a, b)).isTrue();
    }

    @Test
    void 주장_시각과_순번이_바뀌면_다른_출력이다() {
        EventDraft base = stuck(1).withClaim(AT, 10L);
        assertThat(EventOutputs.sameOutputs(EventOutputs.normalize(List.of(base), om),
                EventOutputs.normalize(List.of(stuck(1).withClaim(AT.plusSeconds(1), 10L)), om))).isFalse();
        assertThat(EventOutputs.sameOutputs(EventOutputs.normalize(List.of(base), om),
                EventOutputs.normalize(List.of(stuck(1).withClaim(AT, 11L)), om))).isFalse();
    }

    @Test
    void 밀리초_아래와_확신도_자릿수_차이는_같은_값이다() {
        EventDraft a = stuck(1).withClaim(AT, 1L).withConfidence(new BigDecimal("0.4"));
        EventDraft b = stuck(1).withClaim(AT.withNano(123_000_000), 1L).withConfidence(new BigDecimal("0.400"));
        assertThat(EventOutputs.sameOutputs(EventOutputs.normalize(List.of(a), om),
                EventOutputs.normalize(List.of(b), om))).isTrue();
    }

    @Test
    void 저장된_행과_새_출력을_키_순서와_무관하게_비교한다() {
        EventDraft d = EventDraft.of(Actor.ME, Verb.COVERED_IN_CLASS, SourceRef.course(),
                new Payloads.Covered(List.of(SourceRef.section(5, 12, 25)), null), Evidence.INPUT);
        Output o = EventOutputs.normalize(List.of(d), om).get(0);
        LearningEvent row = LearningEvent.builder().actor("ME").verb("COVERED_IN_CLASS").objectKind("COURSE").objectRef("-")
                .payload("{\"sources\":[{\"to\":25,\"ref\":\"s:5\",\"kind\":\"SECTION\",\"from\":12}],\"v\":1}")
                .evidence("INPUT").build();

        List<Output> fromRow = EventOutputs.fromRows(List.of(row), om);
        assertThat(EventOutputs.sameOutputs(List.of(o), fromRow)).isTrue();
        assertThat(fromRow.get(0).payloadSources()).containsExactly(SourceRef.section(5, 12, 25));
    }

    @Test
    void payload에는_원문_칸이_없고_참조_메서드는_직렬화되지_않는다() throws Exception {
        String json = om.writeValueAsString(new Payloads.Attempted("DONE_UNGRADED", "UNKNOWN", "CONCEPT", true, null));
        assertThat(json).doesNotContain("note").doesNotContain("stuckStep\"").doesNotContain("referenced");
        String covered = om.writeValueAsString(new Payloads.Covered(List.of(SourceRef.section(1)), null));
        assertThat(covered).contains("\"sources\"").doesNotContain("referencedSources");
        assertThat(om.writeValueAsString(new Payloads.Resolved(9, true))).contains("\"resolves\":9").doesNotContain("liveStuck");
    }

    @Test
    void 원천_참조_형식() {
        String hash = "a".repeat(64);
        assertThat(SourceRef.tocEntry(3, hash, 15).tocKey()).isEqualTo(new SourceRef.TocKey(3, hash, 15));
        assertThat(new SourceRef(EventVocabulary.ObjectKind.TOC_ENTRY, "t:3:zz:1", null, null).tocKey()).isNull();
        assertThat(SourceRef.section(42).numericId()).isEqualTo(42L);
        assertThatThrownBy(() -> new SourceRef(EventVocabulary.ObjectKind.SECTION, "", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
