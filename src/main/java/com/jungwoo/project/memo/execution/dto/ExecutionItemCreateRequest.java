package com.jungwoo.project.memo.execution.dto;

import com.jungwoo.project.memo.execution.domain.ExecutionPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Today 화면에서 직접 만드는 실행 조각 생성 요청.
 *
 * scheduledStartAt/scheduledEndAt이 둘 다 있으면 TIME_FIXED, 없으면 DATE_ONLY로
 * 서버가 placementType을 결정한다. 클라이언트가 placementType을 직접 보내지 않는다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExecutionItemCreateRequest {

    /** 프로젝트 화면에서 직접 만든 경우 그 프로젝트. 소유권은 서버가 검증한다. */
    private Long courseId;

    @NotBlank(message = "제목은 필수입니다")
    private String title;

    private String description;

    @NotNull(message = "날짜는 필수입니다")
    private LocalDate scheduledDate;

    private LocalDateTime scheduledStartAt;

    private LocalDateTime scheduledEndAt;

    @Positive(message = "예상 소요 시간은 양수여야 합니다")
    private Integer expectedMinutes;

    private ExecutionPriority priority;
}
