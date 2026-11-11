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
    /** 규칙이 못 읽은 교재 표에서 모델 보조로 읽은 단서 {status, clues[]} — 조회 작업이 채운다. */
    private String clueJson;
    private LocalDateTime createdAt;
}
