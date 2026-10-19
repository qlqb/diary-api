package com.jungwoo.project.memo.material.analysis;

import com.jungwoo.project.memo.material.domain.MaterialTextUnit;

import java.util.ArrayList;
import java.util.List;

/**
 * 추출 단위(페이지·슬라이드)를 모델 한 번에 넣을 청크로 묶는다. 순수 함수.
 *
 * <p>규칙:
 * <ul>
 *   <li>단위를 자르지 않는다 — 청크 경계는 항상 단위 경계다. 페이지 중간에서 끊긴 문제를 두 청크가
 *       반씩 보는 일이 없다.</li>
 *   <li>청크는 이전 청크의 마지막 단위를 겹쳐서 시작한다(overlapChars 상한). 페이지 경계를 넘어
 *       이어지는 문제 조건을 놓치지 않기 위해서다. 겹친 단위에서 같은 구간이 두 번 나와도
 *       dedupe_key로 한 행이 된다.</li>
 *   <li>단위 하나가 chunkChars보다 커도 그 단위만으로 청크 하나를 만든다(잘라서 버리지 않는다).</li>
 * </ul>
 */
public final class MaterialChunker {

    private MaterialChunker() {
    }

    /**
     * @param units      unit_index 순
     * @param fromIndex  이 청크에 포함될 첫 단위의 목록 인덱스(겹침 제외)
     * @param toIndex    마지막 단위의 목록 인덱스(포함)
     * @param overlapFromIndex 겹침으로 앞에 붙인 단위의 인덱스. 없으면 fromIndex와 같다
     */
    public record Chunk(int chunkIndex, int overlapFromIndex, int fromIndex, int toIndex, List<MaterialTextUnit> units) {

        /** 이 청크가 "새로" 담당하는 단위 번호 범위(겹침 제외). 화면·checkpoint 표시용. */
        public int firstUnitNo() {
            return units.get(fromIndex - overlapFromIndex).getUnitNo();
        }

        public int lastUnitNo() {
            return units.get(units.size() - 1).getUnitNo();
        }
    }

    public static List<Chunk> split(List<MaterialTextUnit> units, int chunkChars, int overlapChars) {
        List<Chunk> chunks = new ArrayList<>();
        if (units == null || units.isEmpty()) {
            return chunks;
        }
        int i = 0;
        int chunkIndex = 0;
        while (i < units.size()) {
            int overlapFrom = i;
            int budget = chunkChars;
            List<MaterialTextUnit> members = new ArrayList<>();
            // 겹침: 직전 단위 하나를 상한 안에서만 붙인다.
            if (i > 0 && overlapChars > 0) {
                MaterialTextUnit prev = units.get(i - 1);
                if (safeLen(prev) <= overlapChars) {
                    members.add(prev);
                    overlapFrom = i - 1;
                    budget -= safeLen(prev);
                }
            }
            int from = i;
            int used = 0;
            int to = i;
            while (to < units.size()) {
                int len = safeLen(units.get(to));
                if (used > 0 && used + len > budget) {
                    break;
                }
                members.add(units.get(to));
                used += len;
                to++;
                if (used >= budget) {
                    break;
                }
            }
            int last = to - 1;
            chunks.add(new Chunk(chunkIndex++, overlapFrom, from, last, List.copyOf(members)));
            i = to;
        }
        return chunks;
    }

    private static int safeLen(MaterialTextUnit unit) {
        if (unit.getCharCount() != null) {
            return Math.max(1, unit.getCharCount());
        }
        return unit.getText() == null ? 1 : Math.max(1, unit.getText().length());
    }
}
