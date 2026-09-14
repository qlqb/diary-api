package com.jungwoo.project.memo.scheduling.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * ai_proposal_schedule_previews와 1:1 대응하는 MyBatis 엔티티. Proposal 하나당 마지막으로
 * 계산한 배치 결과 하나만 보존한다(재계산은 덮어쓰기) — 새로고침해도 미리보기가 복원되도록
 * 하기 위한 저장소일 뿐, 공식 execution_items가 아니다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiProposalSchedulePreview {

    private Long schedulePreviewId;
    private Long proposalId;
    private Long userId;
    private LocalDate horizonStart;
    private LocalDate horizonEnd;
    private String availabilityWindows;
    private String userOverrides;
    private String placedItems;
    private String unplacedItems;
    private LocalDateTime computedAt;
}
