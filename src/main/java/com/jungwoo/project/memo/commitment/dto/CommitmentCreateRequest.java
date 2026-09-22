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
 * 약속 생성 요청.
 *
 * <p>sourceType이 없다. 이 경로로 만들어진 것은 서버가 MANUAL로 정한다 — 클라이언트가
 * 출처를 지정할 수 있으면 "AI가 만든 것"이라는 표시가 사실을 담지 못하게 된다.
 *
 * <p>반복 필드도 없다. 반복이면 /api/routines다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CommitmentCreateRequest {

    @NotBlank(message = "제목은 필수입니다")
    @Size(max = 200, message = "제목은 200자를 넘을 수 없습니다")
    private String title;

    @NotNull(message = "시작 시각은 필수입니다")
    private LocalDateTime startAt;

    @NotNull(message = "종료 시각은 필수입니다")
    private LocalDateTime endAt;

    /** 없어도 유효하다. 모르는 장소를 지어내 채우지 않는다. */
    @Size(max = 100, message = "장소는 100자를 넘을 수 없습니다")
    private String locationText;
}
