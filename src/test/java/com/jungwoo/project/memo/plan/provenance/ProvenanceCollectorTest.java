package com.jungwoo.project.memo.plan.provenance;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 프롬프트에 나간 줄과 스냅샷의 대응. 특히 <b>줄 수가 많아도 기록을 빼먹지 않는다</b>.
 *
 * <p>예전에는 400줄을 넘긴 줄을 번호 없이 프롬프트에만 내보내고 스냅샷에서 뺐다. 그러면
 * "모델에는 줬는데 기록에는 없는" 출처가 생기고, 그 회차의 스냅샷은 "실제로 제공한 정보"라는
 * 이름을 잃는다. 이 테스트는 그 경로가 다시 생기지 않게 막는다.
 */
class ProvenanceCollectorTest {

    private static ProvenanceCollector collector() {
        return new ProvenanceCollector(LocalDateTime.of(2026, 9, 11, 9, 0), "Asia/Seoul",
                LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 20), "AI", "test-model");
    }

    @Test
    void everyMarkedLine_isRecorded_evenBeyondTheWarningThreshold() {
        ProvenanceCollector collector = collector();
        int total = ProvenanceCollector.WARN_SOURCES + 50;

        for (int i = 0; i < total; i++) {
            ProvenanceCollector.Marked marked = collector.mark(ProvenanceSourceType.TOPIC, (long) i, null, null,
                    ProvenanceRepresentation.SELECTED_FIELDS, Map.of("title", "항목 " + i), "- 항목 " + i);
            assertThat(marked.refId()).as("%d번째 줄에도 인용 번호가 붙는다", i).isNotNull();
            assertThat(marked.text()).endsWith(" [" + marked.refId() + "]");
        }

        PlanProvenance snapshot = collector.build();
        assertThat(snapshot.providedSources()).as("프롬프트에 나간 줄은 전부 기록된다").hasSize(total);
        Set<String> refIds = new HashSet<>(snapshot.refIds());
        assertThat(refIds).hasSize(total);
        // 마지막 줄까지 promptLine이 실제 문장과 같다.
        ProvidedSource last = snapshot.providedSources().get(total - 1);
        assertThat(last.promptLine()).isEqualTo("- 항목 " + (total - 1) + " [" + last.refId() + "]");
        assertThat(last.sourceId()).isEqualTo((long) (total - 1));
    }

    @Test
    void refIds_areSequential_andUniqueWithinAGeneration() {
        ProvenanceCollector collector = collector();
        ProvenanceCollector.Marked first = collector.mark(ProvenanceSourceType.COURSE, 6L, null, null,
                ProvenanceRepresentation.SELECTED_FIELDS, Map.of(), "id=6 자료구조");
        ProvenanceCollector.Marked second = collector.mark(ProvenanceSourceType.TOPIC, 7L, null, null,
                ProvenanceRepresentation.SELECTED_FIELDS, Map.of(), "- 재귀");

        assertThat(first.refId()).isEqualTo("s1");
        assertThat(second.refId()).isEqualTo("s2");
        assertThat(collector.build().refIds()).containsExactlyInAnyOrder("s1", "s2");
    }
}
