package com.jungwoo.project.memo.course.textbook;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** material_textbook_extracts 한 행. (자료, 파일 해시, 추출 규칙 판)마다 하나. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MaterialTextbookExtract {
    private Long extractId;
    private Long userId;
    private Long materialId;
    private String fileHash;
    private Integer extractorVersion;
    private String bookJson;
    private String tocJson;
    private int tocEntryCount;
    private LocalDateTime createdAt;
}
