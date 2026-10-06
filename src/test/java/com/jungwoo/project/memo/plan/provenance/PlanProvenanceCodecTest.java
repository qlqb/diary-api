package com.jungwoo.project.memo.plan.provenance;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 저장된 스냅샷 JSON의 판 호환. 2판(2026-09-11)이 원본 줄에 당시 구조와 자료 파일을 더했지만,
 * 그 전에 저장된 1판 JSON은 <b>그대로 읽혀야</b> 한다 — 못 읽으면 그 초안은 "출처 없음"이
 * 되어 이미 있던 근거를 잃는다.
 */
class PlanProvenanceCodecTest {

    private final PlanProvenanceCodec codec = new PlanProvenanceCodec();

    /** 2026-09-10 코드가 실제로 저장하던 모양. 필드 이름·순서를 그대로 옮겼다. */
    private static final String VERSION_1_JSON = """
            {"schemaVersion":1,"generationId":"gen-v1-fixture","capturedAt":"2026-09-10T15:00:00",
             "timezone":"Asia/Seoul","startDate":"2026-09-14","endDate":"2026-09-20","generator":"AI",
             "modelName":"test-model",
             "providedSources":[
               {"refId":"s1","sourceType":"COURSE","sourceId":6,"sourceVersion":null,"sourceUpdatedAt":null,
                "representation":"SELECTED_FIELDS","providedValue":{"title":"네트워크프로그래밍"},
                "promptLine":"- id=6 네트워크프로그래밍 [s1]"},
               {"refId":"s2","sourceType":"TOPIC","sourceId":101,"sourceVersion":null,"sourceUpdatedAt":null,
                "representation":"SELECTED_FIELDS",
                "providedValue":{"courseId":6,"title":"소켓의 개념","sourceLocator":"2주차"},
                "promptLine":"  - 소켓의 개념 (2주차) [s2]"}
             ],
             "serverCalculations":[
               {"calculationId":"calc-1","kind":"AVAILABILITY_ESTIMATE","providedToModel":true,
                "inputRefIds":["s1"],"inputLineage":"PARTIAL","lineageNote":"기본 창은 서버 상수다",
                "result":{"availableMinutes":925}}
             ]}
            """;

    @Test
    void version1Json_isReadAsIs_withStructureAndMaterialLeftEmpty() {
        PlanProvenance read = codec.fromJson(VERSION_1_JSON);

        assertThat(read).isNotNull();
        assertThat(read.schemaVersion()).isEqualTo(1);
        assertThat(read.generationId()).isEqualTo("gen-v1-fixture");
        assertThat(read.providedSources()).hasSize(2);
        ProvidedSource topic = read.providedSources().get(1);
        assertThat(topic.sourceType()).isEqualTo(ProvenanceSourceType.TOPIC);
        assertThat(topic.promptLine()).isEqualTo("  - 소켓의 개념 (2주차) [s2]");
        assertThat(topic.providedValue()).containsEntry("sourceLocator", "2주차");
        // 1판에는 없던 값. 지어내지 않고 비워 둔다.
        assertThat(topic.parentSourceId()).isNull();
        assertThat(topic.material()).isNull();
        assertThat(read.serverCalculations()).hasSize(1);
        assertThat(read.refIds()).containsExactlyInAnyOrder("s1", "s2");
    }

    @Test
    void version2_roundTrips_structureAndMaterial() {
        ProvidedSource topic = new ProvidedSource("s2", ProvenanceSourceType.TOPIC, 102L, null, null,
                ProvenanceRepresentation.SELECTED_FIELDS,
                Map.of("courseId", 6, "title", "소켓의 개념", "sourceLocator", "2주차"),
                "  - 소켓의 개념 (2주차) [s2]", 101L,
                new ProvidedMaterial(657L, "네트워크 2주차.pdf", "application/pdf", "abc123", "2주차"));
        PlanProvenance snapshot = new PlanProvenance(PlanProvenance.SCHEMA_VERSION, "gen-v2",
                LocalDateTime.of(2026, 9, 11, 9, 0), "Asia/Seoul", LocalDate.of(2026, 9, 14),
                LocalDate.of(2026, 9, 20), "AI", "test-model", List.of(topic), List.of());

        String json = codec.toJson(snapshot);
        PlanProvenance read = codec.fromJson(json);

        assertThat(json).contains("\"schemaVersion\":2");
        assertThat(read.schemaVersion()).isEqualTo(2);
        ProvidedSource readTopic = read.providedSources().get(0);
        assertThat(readTopic.parentSourceId()).isEqualTo(101L);
        assertThat(readTopic.material()).isEqualTo(topic.material());
        assertThat(readTopic.promptLine()).isEqualTo(topic.promptLine());
    }

    @Test
    void unreadableJson_readsAsNull_notAsAnException() {
        assertThat(codec.fromJson("{")).isNull();
        assertThat(codec.fromJson("")).isNull();
        assertThat(codec.fromJson(null)).isNull();
    }
}
