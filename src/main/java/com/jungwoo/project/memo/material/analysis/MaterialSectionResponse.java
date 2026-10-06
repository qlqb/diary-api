package com.jungwoo.project.memo.material.analysis;

import com.jungwoo.project.memo.material.domain.MaterialSection;
import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;

/** 자료 구간 한 줄. "원문 보기"와 토픽 상세의 연결 자료 목록이 쓴다. */
@Getter
@Builder
public class MaterialSectionResponse {
    private Long sectionId;
    private Long materialId;
    private String locator;
    private Integer unitStart;
    private Integer unitEnd;
    private String unitType;
    private Integer printedPageStart;
    private Integer printedPageEnd;
    private String label;
    private String title;
    private List<String> roles;
    private List<String> roleLabels;
    private String taskText;
    private String excerpt;
    private boolean assignmentCue;
    private String assignmentQuote;
    private List<Map<String, Object>> dates;

    public static MaterialSectionResponse of(MaterialSection s, List<String> roles, List<String> roleLabels,
                                             List<Map<String, Object>> dates) {
        return MaterialSectionResponse.builder()
                .sectionId(s.getSectionId())
                .materialId(s.getMaterialId())
                .locator(s.locator())
                .unitStart(s.getUnitStart())
                .unitEnd(s.getUnitEnd())
                .unitType(s.getUnitType() == null ? null : s.getUnitType().name())
                .printedPageStart(s.getPrintedPageStart())
                .printedPageEnd(s.getPrintedPageEnd())
                .label(s.getSectionLabel())
                .title(s.getDisplayTitle())
                .roles(roles)
                .roleLabels(roleLabels)
                .taskText(s.getTaskText())
                .excerpt(s.getExcerpt())
                .assignmentCue(s.isAssignmentCue())
                .assignmentQuote(s.getAssignmentQuote())
                .dates(dates)
                .build();
    }
}
