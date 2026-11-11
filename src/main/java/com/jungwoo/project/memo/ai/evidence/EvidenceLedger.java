package com.jungwoo.project.memo.ai.evidence;

import com.jungwoo.project.memo.ai.consult.ConsultView;
import com.jungwoo.project.memo.material.domain.CourseMaterial;
import com.jungwoo.project.memo.material.domain.TextUnitType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 한 상담 턴 안에서 모델에게 보여 준 근거의 장부. 번호(M#·S#·E#·F#)는 이 턴 안에서만 유효하다 — 모델이 낸 번호 중 여기
 * 있는 것만 인정하므로, 모델이 다른 사용자의 자료 id나 지어낸 id를 내도 읽을 길이 없다.
 *
 * <p>추가 읽기는 이 장부의 번호로만 대상을 고르고, 읽기 직전에 자료의 현재 상태(삭제·해시·소유)를 다시 확인한다.
 */
public final class EvidenceLedger {

    /** 자료 목록의 한 줄. */
    public static final class MaterialRef {
        final String handle;
        final CourseMaterial material;
        final List<Long> courseIds;
        final List<String> courseTitles;
        /** 원문 단위(현재 해시)가 있는가. */
        final boolean hasUnits;
        /** 단위는 없지만 추출 원문이 있어 메모리에서 나눠 읽을 수 있는가(옛 자료). */
        final boolean textOnly;
        final TextUnitType unitType;
        final Integer firstUnitNo;
        final Integer lastUnitNo;
        /** NO_TEXT / EXTRACTION_FAILED / EXTRACTING / null(읽을 수 있음). */
        final String unreadableReason;
        final String stateLabel;

        MaterialRef(String handle, CourseMaterial material, List<Long> courseIds, List<String> courseTitles,
                    boolean hasUnits, boolean textOnly, TextUnitType unitType, Integer firstUnitNo, Integer lastUnitNo,
                    String unreadableReason, String stateLabel) {
            this.handle = handle;
            this.material = material;
            this.courseIds = courseIds;
            this.courseTitles = courseTitles;
            this.hasUnits = hasUnits;
            this.textOnly = textOnly;
            this.unitType = unitType;
            this.firstUnitNo = firstUnitNo;
            this.lastUnitNo = lastUnitNo;
            this.unreadableReason = unreadableReason;
            this.stateLabel = stateLabel;
        }

        boolean readable() {
            return unreadableReason == null && (hasUnits || textOnly);
        }

        String courseLabel() {
            return courseTitles.isEmpty() ? "프로젝트 미연결" : String.join("·", courseTitles);
        }
    }

    /** 모델에 실은 근거 하나(원문·분석 구간·앱 사실). */
    public static final class SourceRef {
        final String ref;
        final String kind;
        final String title;
        final String courseTitle;
        final String origin;
        final Long materialId;
        final String fileHash;
        final TextUnitType unitType;
        final Integer unitStart;
        final Integer unitEnd;
        final Long sectionId;
        final String readState;
        final int round;

        SourceRef(String ref, String kind, String title, String courseTitle, String origin, Long materialId,
                  String fileHash, TextUnitType unitType, Integer unitStart, Integer unitEnd, Long sectionId,
                  String readState, int round) {
            this.ref = ref;
            this.kind = kind;
            this.title = title;
            this.courseTitle = courseTitle;
            this.origin = origin;
            this.materialId = materialId;
            this.fileHash = fileHash;
            this.unitType = unitType;
            this.unitStart = unitStart;
            this.unitEnd = unitEnd;
            this.sectionId = sectionId;
            this.readState = readState;
            this.round = round;
        }

        String locator() {
            if (unitStart == null) {
                return null;
            }
            String label = unitType == null ? "" : unitType.label();
            return label + unitStart + (unitEnd != null && !unitEnd.equals(unitStart) ? "~" + unitEnd : "");
        }
    }

    final Map<String, MaterialRef> materials = new LinkedHashMap<>();
    final Map<Long, MaterialRef> materialsById = new LinkedHashMap<>();
    final Map<String, SourceRef> sources = new LinkedHashMap<>();
    final List<ConsultView.Gap> gaps = new ArrayList<>();
    /**
     * 통째로 실은 (자료, 단위). 추가 검색에서 다시 찾지 않는다. 일부만 실은 단위는 여기 넣지 않는다 — 그 단위의 다른 줄에 답이
     * 있을 수 있다(같은 발췌는 원문 해시로 거른다).
     */
    final Set<String> shownUnits = new HashSet<>();
    final Set<String> shownTextHashes = new HashSet<>();
    /** 원문 해시 → 그 원문을 실은 번호. 추가 읽기가 같은 원문을 다시 요청하면 "이미 [E#]로 실렸다"고 알린다. */
    final Map<String, String> refByTextHash = new LinkedHashMap<>();
    final List<String> searchTerms = new ArrayList<>();
    int searchedMaterials;
    int readableMaterials;
    int totalMaterials;
    int rounds;
    private int nextE = 1;
    private int nextS = 1;
    private int nextF = 1;

    /** 마지막으로 붙인 원문 번호. */
    String lastEvidence;

    String nextEvidence() {
        lastEvidence = "E" + nextE++;
        return lastEvidence;
    }

    String nextSection() {
        return "S" + nextS++;
    }

    String nextFact() {
        return "F" + nextF++;
    }

    public boolean hasSources() {
        return !sources.isEmpty();
    }

    /** 파일에서 읽은 원문을 모델에 실었는가. 원문은 외부 글이라 이 턴의 저장 범위를 좁히는 데 쓴다. */
    public boolean showedMaterialText() {
        return sources.values().stream().anyMatch(s -> "MATERIAL_TEXT".equals(s.kind));
    }

    public boolean hasMaterials() {
        return !materials.isEmpty();
    }

    public int rounds() {
        return rounds;
    }

    static String unitKey(Long materialId, Integer unitNo) {
        return materialId + ":" + unitNo;
    }

    /** 화면용 결과. used는 모델이 밝힌 번호 중 이 장부에 있는 것만. */
    public ConsultView.Evidence toView(Set<String> used, List<ConsultView.Gap> extraGaps) {
        if (sources.isEmpty() && gaps.isEmpty() && (extraGaps == null || extraGaps.isEmpty())) {
            return null;
        }
        List<ConsultView.Source> out = new ArrayList<>();
        for (SourceRef s : sources.values()) {
            boolean isUsed = used != null && used.contains(s.ref);
            Integer page = s.unitType == TextUnitType.PDF_PAGE && s.unitStart != null ? s.unitStart : null;
            out.add(new ConsultView.Source(s.ref, s.kind, s.title, s.courseTitle, s.origin, s.materialId, s.locator(),
                    page, s.readState, isUsed, s.round));
        }
        // 답변에 쓴 근거가 먼저 보이게 한다. 같은 무리 안에서는 번호 순이다.
        out.sort((a, b) -> Boolean.compare(b.used(), a.used()));
        List<ConsultView.Gap> allGaps = new ArrayList<>(gaps);
        if (extraGaps != null) {
            allGaps.addAll(extraGaps);
        }
        long readCount = out.stream().filter(s -> "MATERIAL_TEXT".equals(s.kind())).count();
        StringBuilder summary = new StringBuilder();
        if (totalMaterials > 0) {
            summary.append("자료 ").append(totalMaterials).append("개 중 읽을 수 있는 ").append(readableMaterials)
                    .append("개를 검색");
        } else {
            summary.append("올린 자료 없음");
        }
        summary.append(" · 원문 ").append(readCount).append("곳 확인");
        long usedCount = out.stream().filter(ConsultView.Source::used).count();
        if (usedCount > 0) {
            summary.append(" · 답변에 ").append(usedCount).append("곳 사용");
        }
        if (!allGaps.isEmpty()) {
            summary.append(" · 확인하지 못한 범위 ").append(allGaps.size()).append("건");
        }
        return new ConsultView.Evidence(summary.toString(), out, allGaps, Math.max(1, rounds));
    }
}
