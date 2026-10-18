package com.jungwoo.project.memo.plan.scope;

import com.jungwoo.project.memo.plan.scope.ScopeReport.Acquisition;
import com.jungwoo.project.memo.plan.scope.ScopeReport.Completion;
import com.jungwoo.project.memo.plan.scope.ScopeReport.PartState;
import com.jungwoo.project.memo.plan.scope.ScopeReport.Reflection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 계획 생성 ⑦ — 요청 범위의 부분마다 원문 확보 상태와 최종 초안의 반영 상태를 서버가 판정한다. 모델의 말이 아니라 이번 회차에 실제로
 * 보여 주고 실은 것, 최종 항목·미룬 범위가 인용한 번호로만 판단한다.
 *
 * <p>항목이 부분에 연결되는 길은 둘이다: (1) 계획 입력에 실은 그 부분의 줄([요청 범위])을 인용, (2) 그 부분 자료의 구간 원문을 인용.
 * (1)만 있으면 원문 없이 사용자 자료로 하는 연습이다. 과목이 정해진 범위에는 같은 과목의 항목만 연결된다. 과목을 정하지 못한 범위는
 * 연결돼도 충족으로 보지 않는다(확인 필요).
 */
public final class ScopeVerdict {

    private ScopeVerdict() {
    }

    /** 최종 항목 하나: 과목과 서버가 인정한 인용 번호. */
    public record ItemRef(Long courseId, List<String> refIds) {
    }

    /** 미룬 범위 하나: 이유와 인용 번호(모델 출력 그대로 — 서버는 아는 번호만 본다). */
    public record DeferredRef(String reason, List<String> refIds) {
    }

    /**
     * @param sectionMaterial 구간 → 자료(이번 카탈로그)
     * @param shownSections   선택 단계에서 목록에 보여 준 구간
     * @param deliveredFull   최종 계획 입력에 원문이 실린 구간 → 단위가 잘리지 않고 실렸는가
     * @param refToSection    원문을 실은 줄의 인용 번호 → 구간
     * @param refToPart       [요청 범위] 줄의 인용 번호 → 부분 id
     */
    public static ScopeReport judge(String status, List<RequestScope> scopes, Map<Long, Long> sectionMaterial,
                                    Set<Long> shownSections, Map<Long, Boolean> deliveredFull,
                                    Map<String, Long> refToSection, Map<String, String> refToPart,
                                    List<ItemRef> items, List<DeferredRef> deferred) {
        return judge(status, false, scopes, sectionMaterial, shownSections, Map.of(), deliveredFull, refToSection, refToPart,
                items, deferred);
    }

    /**
     * @param incomplete    범위 추출에서 버린 것이 있다(전체·필수 완료를 말하지 않는다)
     * @param selectedNotIn 골랐지만 최종 입력에 원문이 실리지 않은 구간 → 사유(예산·조회 실패)
     */
    public static ScopeReport judge(String status, boolean incomplete, List<RequestScope> scopes,
                                    Map<Long, Long> sectionMaterial, Set<Long> shownSections,
                                    Map<Long, String> selectedNotIn, Map<Long, Boolean> deliveredFull,
                                    Map<String, Long> refToSection, Map<String, String> refToPart,
                                    List<ItemRef> items, List<DeferredRef> deferred) {
        if (scopes == null || scopes.isEmpty()) {
            ScopeReport empty = ScopeReport.empty(status);
            return incomplete ? new ScopeReport(status, List.of(), List.of(), false, List.of(), List.of(),
                    Completion.UNKNOWN.name(), true) : empty;
        }
        // 한 자료가 확인된 여러 부분에 걸치면(같은 PDF의 Navigation·SQLite) 그 자료의 구간 인용만으로는 어느 부분인지 모른다.
        Map<Long, Integer> partsPerMaterial = ScopeCoverage.partsPerMaterial(scopes);
        List<PartState> states = new ArrayList<>();
        List<String> remaining = new ArrayList<>();
        List<String> needsConfirm = new ArrayList<>();
        boolean requiredUnmet = false;
        boolean anyVerified = false;
        boolean allVerifiedSatisfied = true;
        for (RequestScope scope : scopes) {
            if (!scope.verified()) {
                needsConfirm.add(scope.scopeId());
            } else {
                anyVerified = true;
            }
            for (RequestScope.Part part : scope.parts()) {
                Set<Long> mats = Set.copyOf(part.materialIds());
                String[] acquisitionNote = {null};
                Acquisition acquisition = acquisition(part, mats, partsPerMaterial, sectionMaterial, shownSections,
                        selectedNotIn, deliveredFull, acquisitionNote);
                List<Integer> linked = new ArrayList<>();
                boolean viaSource = false;
                for (int i = 0; i < items.size(); i++) {
                    ItemRef item = items.get(i);
                    if (scope.courseId() != null && !Objects.equals(scope.courseId(), item.courseId())) {
                        continue;
                    }
                    List<String> refs = item.refIds() == null ? List.of() : item.refIds();
                    boolean citesPart = refs.stream().anyMatch(ref -> part.partId().equals(refToPart.get(ref)));
                    boolean citesSource = false;
                    boolean citesSharedSource = false;
                    for (String ref : refs) {
                        Long section = refToSection.get(ref);
                        Long material = section == null ? null : sectionMaterial.get(section);
                        if (material != null && mats.contains(material)) {
                            if (partsPerMaterial.getOrDefault(material, 0) > 1) {
                                citesSharedSource = true;
                            } else {
                                citesSource = true;
                            }
                        }
                    }
                    // 여러 부분에 걸친 자료의 구간은 부분 줄도 함께 인용했을 때만 이 부분의 원문 근거다.
                    boolean source = citesSource || (citesSharedSource && citesPart);
                    if (source || citesPart) {
                        linked.add(i);
                        viaSource |= source;
                    }
                }
                Reflection reflection;
                String note = null;
                if (!linked.isEmpty() && scope.courseId() == null) {
                    reflection = Reflection.UNHANDLED;
                    note = "과목을 정하지 못한 범위라 항목 연결을 충족으로 보지 않는다(확인 필요)";
                } else if (!linked.isEmpty() && viaSource) {
                    reflection = Reflection.ITEM;
                } else if (!linked.isEmpty() && part.userMaterial()) {
                    reflection = Reflection.PRACTICE_WITH_USER_MATERIAL;
                } else if (!linked.isEmpty()) {
                    reflection = Reflection.UNHANDLED;
                    note = "항목이 이 부분을 가리키지만 원문 근거도 사용자가 가진 자료도 없다";
                } else {
                    DeferredRef d = deferredFor(part, mats, partsPerMaterial, deferred, refToPart, refToSection,
                            sectionMaterial);
                    reflection = d != null ? Reflection.DEFERRED : Reflection.UNHANDLED;
                    note = d == null ? null : d.reason();
                }
                states.add(new PartState(part.partId(), scope.scopeId(), acquisition.name(), reflection.name(),
                        List.copyOf(linked), note, acquisitionNote[0]));
                if (scope.verified() && !reflection.satisfied()) {
                    remaining.add(part.partId());
                    allVerifiedSatisfied = false;
                    if (scope.required()) {
                        requiredUnmet = true;
                    }
                }
            }
        }
        Completion completion;
        if (!anyVerified || (incomplete && !requiredUnmet)) {
            // 확인할 수 없음은 충족이 아니다. 추출에서 버린 범위가 있으면 남은 것만 보고 완료를 말하지 않는다.
            completion = Completion.UNKNOWN;
        } else if (allVerifiedSatisfied && needsConfirm.isEmpty()) {
            completion = Completion.ALL;
        } else if (!requiredUnmet) {
            completion = Completion.REQUIRED_ONLY;
        } else {
            completion = Completion.NOT_COMPLETE;
        }
        return new ScopeReport(status, List.copyOf(scopes), List.copyOf(states), requiredUnmet, List.copyOf(remaining),
                List.copyOf(needsConfirm), completion.name(), incomplete);
    }

    static Acquisition acquisition(RequestScope.Part part, Set<Long> mats, Map<Long, Integer> partsPerMaterial,
                                   Map<Long, Long> sectionMaterial, Set<Long> shownSections,
                                   Map<Long, String> selectedNotIn, Map<Long, Boolean> deliveredFull, String[] note) {
        if (mats.isEmpty()) {
            if (part.noMaterial()) {
                return Acquisition.NO_MATERIAL_IN_APP;
            }
            if (part.userMaterial()) {
                note[0] = "사용자가 가진 자료로 한다고 했다(앱 자료와 연결하지 않음)";
                return Acquisition.NO_MATERIAL_IN_APP;
            }
            note[0] = "자료 연결 미확인";
            return Acquisition.AVAILABLE_NOT_ACQUIRED;
        }
        // 여러 부분이 함께 쓰는 자료의 구간은 어느 부분의 것인지 서버가 알 수 없다 — 이 부분만의 자료로 확보를 판정한다.
        Set<Long> own = new java.util.HashSet<>();
        for (Long m : mats) {
            if (partsPerMaterial.getOrDefault(m, 0) <= 1) {
                own.add(m);
            }
        }
        if (own.isEmpty()) {
            note[0] = "여러 부분이 같은 자료라 이 부분의 구간이 실렸는지 확인하지 못했다";
            return Acquisition.AVAILABLE_NOT_ACQUIRED;
        }
        boolean full = false;
        boolean some = false;
        for (Map.Entry<Long, Boolean> d : deliveredFull.entrySet()) {
            if (own.contains(sectionMaterial.get(d.getKey()))) {
                some = true;
                full |= Boolean.TRUE.equals(d.getValue());
            }
        }
        if (full) {
            return Acquisition.DELIVERED_UNIT;
        }
        if (some) {
            return Acquisition.EXCERPT_ONLY;
        }
        for (Map.Entry<Long, String> s : selectedNotIn.entrySet()) {
            if (own.contains(sectionMaterial.get(s.getKey()))) {
                note[0] = "골랐지만 원문이 실리지 않았다: " + s.getValue();
                return Acquisition.AVAILABLE_NOT_ACQUIRED;
            }
        }
        boolean shown = shownSections.stream().anyMatch(s -> own.contains(sectionMaterial.get(s)));
        return shown ? Acquisition.SHOWN_NOT_SELECTED : Acquisition.AVAILABLE_NOT_ACQUIRED;
    }

    /** 이 부분을 미룬 항목. 부분 줄을 인용했거나, 이 부분만의 자료(다른 부분과 함께 쓰지 않는 자료) 구간을 인용했을 때. */
    private static DeferredRef deferredFor(RequestScope.Part part, Set<Long> mats, Map<Long, Integer> partsPerMaterial,
                                           List<DeferredRef> deferred, Map<String, String> refToPart,
                                           Map<String, Long> refToSection, Map<Long, Long> sectionMaterial) {
        for (DeferredRef d : deferred == null ? List.<DeferredRef>of() : deferred) {
            for (String ref : d.refIds() == null ? List.<String>of() : d.refIds()) {
                if (part.partId().equals(refToPart.get(ref))) {
                    return d;
                }
                Long section = refToSection.get(ref);
                Long material = section == null ? null : sectionMaterial.get(section);
                if (material != null && mats.contains(material) && partsPerMaterial.getOrDefault(material, 0) <= 1) {
                    return d;
                }
            }
        }
        return null;
    }
}
