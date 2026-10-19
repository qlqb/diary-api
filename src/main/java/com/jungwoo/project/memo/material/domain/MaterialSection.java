package com.jungwoo.project.memo.material.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AI가 식별한 자료 구간. 자료 종류보다 "이 구간이 무슨 역할인가"를 말한다.
 *
 * <p>unitStart/unitEnd는 물리 단위(PDF 페이지·슬라이드 번호)다. printedPage*는 원문에 찍힌
 * 쪽수가 확인된 경우에만 채운다 — 일괄 오프셋으로 계산하지 않는다. rolesJson은 SectionRole
 * 이름의 JSON 배열이고 한 구간에 여러 역할이 있을 수 있다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MaterialSection {

    private Long sectionId;
    private Long userId;
    private Long materialId;
    private String fileHash;
    private Integer analysisVersion;
    private Integer chunkIndex;
    private TextUnitType unitType;
    private Integer unitStart;
    private Integer unitEnd;
    private Integer printedPageStart;
    private Integer printedPageEnd;
    private String sectionLabel;
    private String displayTitle;
    private String rolesJson;
    private String taskText;
    private String excerpt;
    private boolean assignmentCue;
    private String assignmentQuote;
    private String dateCandidatesJson;
    private String dedupeKey;
    private String status;
    private LocalDateTime createdAt;

    /** 사람이 읽는 위치 문자열. "p.3~4", "슬라이드 7". 인쇄 쪽수가 확인됐으면 그것을 덧붙인다. */
    public String locator() {
        StringBuilder sb = new StringBuilder();
        if (unitStart != null) {
            sb.append(unitType == null ? "" : unitType.label()).append(unitStart);
            if (unitEnd != null && !unitEnd.equals(unitStart)) {
                sb.append("~").append(unitEnd);
            }
        }
        if (printedPageStart != null) {
            String printed = "인쇄 " + printedPageStart
                    + (printedPageEnd != null && !printedPageEnd.equals(printedPageStart) ? "~" + printedPageEnd : "")
                    + "쪽";
            if (sb.length() == 0) {
                return printed;
            }
            sb.append(" (").append(printed).append(")");
        }
        return sb.toString();
    }
}
