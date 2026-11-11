package com.jungwoo.project.memo.commitment.dto;

import com.jungwoo.project.memo.commitment.domain.Commitment;
import com.jungwoo.project.memo.commitment.domain.CommitmentSourceType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 약속 응답.
 *
 * <p>완료 상태나 진행률에 해당하는 필드가 없다. 이 응답을 받는 화면이 완료 버튼을 그릴
 * 근거가 아예 없어야 한다 — 필드가 있으면 언젠가 누가 쓴다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CommitmentResponse {

    private Long commitmentId;
    private String title;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private String locationText;
    private CommitmentSourceType sourceType;
    private Long version;

    public static CommitmentResponse of(Commitment commitment) {
        return CommitmentResponse.builder()
                .commitmentId(commitment.getCommitmentId())
                .title(commitment.getTitle())
                .startAt(commitment.getStartAt())
                .endAt(commitment.getEndAt())
                .locationText(commitment.getLocationText())
                .sourceType(commitment.getSourceType())
                .version(commitment.getVersion())
                .build();
    }
}
