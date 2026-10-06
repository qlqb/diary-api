package com.jungwoo.project.memo.plan.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 「자세히」 응답. available=false면 아직 만든 안내가 없다는 뜻이고, canGenerate가 그때 만들 수 있는지(인용한
 * 자료 구간이 있는지)를 말한다. stale=true면 항목이나 원문이 바뀐 뒤의 옛 안내다 — 최신이라고 표시하지 않는다.
 */
@Getter
@Builder
public class PlanItemDetailResponse {

    private Long detailId;
    private Long proposalItemId;
    private String evidenceVersion;
    private List<Step> steps;
    private String userText;
    private boolean stale;
    private boolean available;
    private boolean canGenerate;
    private List<SectionRef> sections;
    private LocalDateTime createdAt;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Step(String text, List<String> refIds, List<Long> sectionIds) {
    }

    public record SectionRef(Long sectionId, Long materialId, String title, String locator) {
    }

    public static PlanItemDetailResponse none(Long proposalItemId, String version, boolean canGenerate) {
        return none(proposalItemId, version, canGenerate, null, List.of());
    }

    /** 단계는 아직 없지만 메모가 있을 수 있다(메모만 먼저 남긴 항목). */
    public static PlanItemDetailResponse none(Long proposalItemId, String version, boolean canGenerate, String userText,
                                              List<SectionRef> sections) {
        return PlanItemDetailResponse.builder()
                .proposalItemId(proposalItemId).evidenceVersion(version)
                .steps(List.of()).userText(userText).stale(false).available(false).canGenerate(canGenerate)
                .sections(sections == null ? List.of() : sections)
                .build();
    }

    public PlanItemDetailResponse withVersion(String currentVersion) {
        return PlanItemDetailResponse.builder()
                .detailId(detailId).proposalItemId(proposalItemId).evidenceVersion(currentVersion)
                .steps(steps).userText(userText).stale(true).available(available).canGenerate(canGenerate)
                .sections(sections).createdAt(createdAt)
                .build();
    }
}
