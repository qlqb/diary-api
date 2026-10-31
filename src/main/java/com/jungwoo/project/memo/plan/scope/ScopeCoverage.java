package com.jungwoo.project.memo.plan.scope;

import com.jungwoo.project.memo.plan.PlanMaterialContextService.CourseCatalog;
import com.jungwoo.project.memo.plan.selection.PlanMaterialSelector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 2단계 — 요청 범위의 부분마다 원문 후보가 하나는 있게 한다.
 *
 * <p>모델의 선택이 어떤 부분의 자료를 하나도 고르지 않았으면(이번 사례: 2주차, 빅데이터 앞 단원, 스마트앱 전부) 서버가 그 부분만의
 * 자료에서 대표 구간 몇 개를 보충한다. 근거가 확인된 범위만, 다른 부분과 함께 쓰지 않는 자료만, 강의계획서가 아닌 구간만, 앞쪽(문서
 * 순서)부터 고른다. 실제로 원문이 실리는지는 그 뒤의 입력 예산이 정하고, 최종 판정이 확보 상태로 남긴다.
 */
public final class ScopeCoverage {

    private ScopeCoverage() {
    }

    public static final String REASON = "요청 범위의 이 부분을 다루는 구간이 선택에 없어 서버가 보충했다";
    /** 부분마다 보충하는 구간 수. */
    static final int PER_PART = 2;
    /** 한 회차에 보충하는 구간 수의 합. 선택 상한과 함께 본다. */
    static final int MAX_TOTAL = 16;

    /**
     * 확인된 범위의 부분들이 각 자료를 몇 부분에서 쓰는가. 2 이상이면 그 자료의 구간이 어느 부분 것인지 서버가 정할 수 없다.
     * 근거 미확인 범위는 세지 않는다 — 확인되지 않은 추출 결과가 확인된 범위의 확보·보호를 막지 않게(보충·예산 보호·판정 공통).
     */
    public static Map<Long, Integer> partsPerMaterial(List<RequestScope> scopes) {
        Map<Long, Integer> out = new HashMap<>();
        for (RequestScope s : scopes == null ? List.<RequestScope>of() : scopes) {
            if (!s.verified()) {
                continue;
            }
            for (RequestScope.Part p : s.parts()) {
                for (Long m : Set.copyOf(p.materialIds())) {
                    out.merge(m, 1, Integer::sum);
                }
            }
        }
        return out;
    }

    /** 이 부분만의 자료(확인된 다른 부분과 함께 쓰지 않는 자료). */
    public static Set<Long> ownMaterials(RequestScope.Part part, Map<Long, Integer> partsPerMaterial) {
        Set<Long> own = new LinkedHashSet<>();
        for (Long m : part.materialIds()) {
            if (partsPerMaterial.getOrDefault(m, 0) <= 1) {
                own.add(m);
            }
        }
        return own;
    }

    public static PlanMaterialSelector.Result supplement(PlanMaterialSelector.Result selection, List<RequestScope> scopes,
                                                         List<CourseCatalog> catalogs) {
        if (selection == null || scopes == null || scopes.isEmpty() || selection.byHandle() == null) {
            return selection;
        }
        Map<Long, Integer> partsPerMaterial = partsPerMaterial(scopes);
        Set<Long> picked = new HashSet<>();
        Set<Long> pickedMaterials = new HashSet<>();
        selection.sections().forEach(s -> {
            picked.add(s.line().section().getSectionId());
            pickedMaterials.add(s.line().material().getMaterialId());
        });
        int room = Math.min(MAX_TOTAL, PlanMaterialSelector.MAX_SELECTED_SECTIONS - selection.sections().size());
        if (room <= 0) {
            return selection;
        }
        // 보충이 필요한 부분과 그 후보(앞쪽부터)를 먼저 모은다.
        List<String> labels = new ArrayList<>();
        List<List<Map.Entry<String, PlanMaterialSelector.SelectedSection>>> queues = new ArrayList<>();
        for (RequestScope scope : scopes) {
            if (!scope.verified() || scope.courseId() == null) {
                continue;
            }
            for (RequestScope.Part part : scope.parts()) {
                Set<Long> own = ownMaterials(part, partsPerMaterial);
                if (own.isEmpty() || own.stream().anyMatch(pickedMaterials::contains)) {
                    continue; // 이미 이 부분의 자료를 골랐거나, 어느 부분의 것인지 모르는 공유 자료뿐이다
                }
                List<Map.Entry<String, PlanMaterialSelector.SelectedSection>> candidates = selection.byHandle().entrySet()
                        .stream()
                        .filter(e -> own.contains(e.getValue().line().material().getMaterialId()))
                        .filter(e -> Objects.equals(e.getValue().catalog().courseId(), scope.courseId()))
                        .filter(e -> !e.getValue().line().syllabus())
                        .filter(e -> !picked.contains(e.getValue().line().section().getSectionId()))
                        .sorted(Comparator.comparing((Map.Entry<String, PlanMaterialSelector.SelectedSection> e) ->
                                        e.getValue().line().material().getMaterialId())
                                .thenComparing(e -> unitStart(e.getValue())))
                        .limit(PER_PART)
                        .toList();
                if (!candidates.isEmpty()) {
                    labels.add(part.partId() + " " + part.label());
                    queues.add(candidates);
                }
            }
        }
        List<PlanMaterialSelector.SelectedSection> sections = new ArrayList<>(selection.sections());
        // 한 바퀴에 부분마다 하나씩 — 여유가 모자라도 빠진 부분 각각이 먼저 하나씩 받는다.
        for (int round = 0; round < PER_PART && room > 0; round++) {
            for (int i = 0; i < queues.size() && room > 0; i++) {
                List<Map.Entry<String, PlanMaterialSelector.SelectedSection>> queue = queues.get(i);
                if (round >= queue.size()) {
                    continue;
                }
                Map.Entry<String, PlanMaterialSelector.SelectedSection> e = queue.get(round);
                PlanMaterialSelector.SelectedSection s = e.getValue();
                if (picked.add(s.line().section().getSectionId())) {
                    sections.add(new PlanMaterialSelector.SelectedSection(e.getKey(), s.line(), s.catalog(),
                            REASON + " (" + labels.get(i) + ")"));
                    room--;
                }
            }
        }
        if (sections.size() == selection.sections().size()) {
            return selection;
        }
        return new PlanMaterialSelector.Result(PlanMaterialSelector.Status.SELECTED, selection.mode(), sections,
                selection.topics(), selection.insufficientEvidence(), selection.note(), selection.calls(),
                selection.expanded(), selection.candidateTotal(), selection.candidateShown(), selection.unreviewed(),
                selection.unknownIds(), selection.estimatedInputTokens(), selection.overLimit(), selection.sectionHandles(),
                selection.byHandle(), selection.exposure(), selection.traces());
    }

    private static int unitStart(PlanMaterialSelector.SelectedSection s) {
        Integer start = s.line().section().getUnitStart();
        return start == null ? Integer.MAX_VALUE : start;
    }
}
