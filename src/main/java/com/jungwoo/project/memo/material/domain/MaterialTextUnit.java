package com.jungwoo.project.memo.material.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 자료의 추출 단위 하나 — PDF 물리 페이지, PPTX 슬라이드, 또는 그 둘이 없을 때의 텍스트 블록.
 *
 * <p>extracted_text와 별개로 두는 이유는 "몇 페이지에 무엇이 있었나"를 말하기 위해서다.
 * unit_no는 사람이 보는 번호(1부터), unit_index는 저장 순서다. 한 페이지가 너무 길면 같은
 * unit_no로 여러 행이 된다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MaterialTextUnit {

    private Long unitId;
    private Long userId;
    private Long materialId;
    private String fileHash;
    private Integer unitIndex;
    private TextUnitType unitType;
    private Integer unitNo;
    private Integer charCount;
    private String text;
    private LocalDateTime createdAt;
}
