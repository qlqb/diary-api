package com.jungwoo.project.memo.material.zip.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/** 가져올 항목. 클라이언트가 보낸 경로·크기는 믿지 않는다 — 서버가 만든 entryId만 받는다. */
@Getter
@Setter
@NoArgsConstructor
public class ZipImportConfirmRequest {

    @NotEmpty(message = "가져올 파일을 하나 이상 골라주세요")
    private List<Long> entryIds;
}
