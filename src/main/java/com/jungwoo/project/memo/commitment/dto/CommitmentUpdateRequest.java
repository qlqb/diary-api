package com.jungwoo.project.memo.commitment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 약속 수정 요청. 전체 교체(PUT)다 — locationText를 비우면 비워진다.
 *
 * <p>sourceType은 여기에도 없다. 출처는 만들어진 사실이라 나중에 바뀌지 않는다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CommitmentUpdateRequest {

    @NotBlank(message = "제목은 필수입니다")
    @Size(max = 200, message = "제목은 200자를 넘을 수 없습니다")
    private String title;

    @NotNull(message = "시작 시각은 필수입니다")
    private LocalDateTime startAt;

    @NotNull(message = "종료 시각은 필수입니다")
    private LocalDateTime endAt;

    @Size(max = 100, message = "장소는 100자를 넘을 수 없습니다")
    private String locationText;

    @NotNull(message = "version은 필수입니다")
    private Long version;
}
