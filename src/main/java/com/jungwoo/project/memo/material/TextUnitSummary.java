package com.jungwoo.project.memo.material;

import com.jungwoo.project.memo.material.domain.TextUnitType;
import lombok.Data;
import lombok.NoArgsConstructor;

/** {@link MaterialTextUnitMapper#summarizeByUser}의 한 줄: 자료·해시별 원문 단위 개수와 번호 범위. */
@Data
@NoArgsConstructor
public class TextUnitSummary {
    private Long materialId;
    private String fileHash;
    private Integer unitCount;
    private Integer firstUnitNo;
    private Integer lastUnitNo;
    private TextUnitType unitType;
}
