package com.jungwoo.project.memo.material;

import com.jungwoo.project.memo.material.domain.TextUnitType;
import lombok.Data;
import lombok.NoArgsConstructor;

/** {@link MaterialTextUnitMapper#searchByTerms}의 결과 한 줄. 본문 없이 위치와 걸린 검색어 수만. */
@Data
@NoArgsConstructor
public class TextUnitHit {
    private Long unitId;
    private Long materialId;
    private Integer unitIndex;
    private TextUnitType unitType;
    private Integer unitNo;
    private Integer charCount;
    private Integer hits;
}
