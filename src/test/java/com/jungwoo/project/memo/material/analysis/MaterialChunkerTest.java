package com.jungwoo.project.memo.material.analysis;

import com.jungwoo.project.memo.material.domain.MaterialTextUnit;
import com.jungwoo.project.memo.material.domain.TextUnitType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 청크 분할의 계약: 단위를 자르지 않고, 겹침은 직전 단위 하나, 마지막 단위까지 빠짐없이 담는다.
 * "긴 자료의 마지막 부분에만 문제가 있는" 시나리오(11번 표 6)는 마지막 페이지가 어느 청크에든
 * 반드시 들어간다는 이 성질에 기댄다.
 */
class MaterialChunkerTest {

    private static List<MaterialTextUnit> pages(int count, int charsEach) {
        List<MaterialTextUnit> units = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String text = ("p" + (i + 1) + " ").repeat(Math.max(1, charsEach / 4));
            units.add(MaterialTextUnit.builder().unitIndex(i).unitNo(i + 1).unitType(TextUnitType.PDF_PAGE)
                    .charCount(text.length()).text(text).build());
        }
        return units;
    }

    @Test
    void everyUnitLandsInSomeChunk_andTheLastPageIsNeverDropped() {
        List<MaterialTextUnit> units = pages(37, 1_500);
        List<MaterialChunker.Chunk> chunks = MaterialChunker.split(units, 10_000, 2_000);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(chunks.size() - 1).lastUnitNo()).isEqualTo(37);
        int covered = 0;
        for (MaterialChunker.Chunk chunk : chunks) {
            covered += chunk.toIndex() - chunk.fromIndex() + 1;
        }
        assertThat(covered).isEqualTo(37);
        for (int i = 1; i < chunks.size(); i++) {
            assertThat(chunks.get(i).fromIndex()).isEqualTo(chunks.get(i - 1).toIndex() + 1);
        }
    }

    @Test
    void chunksOverlapByExactlyOnePreviousUnit_whenItFitsTheOverlapBudget() {
        List<MaterialChunker.Chunk> chunks = MaterialChunker.split(pages(10, 3_000), 7_000, 4_000);

        assertThat(chunks.size()).isGreaterThan(1);
        MaterialChunker.Chunk second = chunks.get(1);
        assertThat(second.overlapFromIndex()).isEqualTo(second.fromIndex() - 1);
        assertThat(second.units().get(0).getUnitNo()).isEqualTo(chunks.get(0).lastUnitNo());
        assertThat(second.firstUnitNo()).isEqualTo(chunks.get(0).lastUnitNo() + 1);
    }

    @Test
    void aUnitLargerThanTheChunkBudget_isStillIncludedWhole() {
        List<MaterialTextUnit> units = pages(3, 500);
        String huge = "x".repeat(50_000);
        units.set(1, MaterialTextUnit.builder().unitIndex(1).unitNo(2).unitType(TextUnitType.PDF_PAGE)
                .charCount(huge.length()).text(huge).build());

        List<MaterialChunker.Chunk> chunks = MaterialChunker.split(units, 10_000, 2_000);

        assertThat(chunks).anySatisfy(chunk -> assertThat(chunk.units()).anyMatch(u -> u.getUnitNo() == 2));
        assertThat(chunks.get(chunks.size() - 1).lastUnitNo()).isEqualTo(3);
    }

    @Test
    void shortDocument_isASingleChunkWithoutOverlap() {
        List<MaterialChunker.Chunk> chunks = MaterialChunker.split(pages(5, 800), 24_000, 2_000);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).overlapFromIndex()).isZero();
        assertThat(chunks.get(0).firstUnitNo()).isEqualTo(1);
        assertThat(chunks.get(0).lastUnitNo()).isEqualTo(5);
    }

    @Test
    void emptyInput_yieldsNoChunks() {
        assertThat(MaterialChunker.split(List.of(), 24_000, 2_000)).isEmpty();
    }
}
