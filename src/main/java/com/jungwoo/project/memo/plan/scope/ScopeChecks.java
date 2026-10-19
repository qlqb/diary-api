package com.jungwoo.project.memo.plan.scope;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 3단계 — 부분별 판정을 초안에 반영하는 서버 검사.
 *
 * <ul>
 *   <li>필수 범위가 남았는데 "가능하면" 범위의 항목이 있으면, 계획이 그 판단을 설명해야 한다. 설명이 없으면 검증 미통과로 두고
 *       서버가 남은 부분과 상태를 이유로 적는다(경고만 남기지 않는다).</li>
 *   <li>완료 표시는 모델의 목표 문장이 아니라 판정으로 정한다: 서버가 만든 안내 문장 하나가 화면 맨 위에 보인다.</li>
 * </ul>
 */
public final class ScopeChecks {

    private ScopeChecks() {
    }

    public static final String OPTIONAL_WITHOUT_REASON = "OPTIONAL_OVER_REQUIRED_WITHOUT_REASON";

    /**
     * @param itemCourses   최종 항목의 과목(순서대로)
     * @param modelReasons  계획이 남긴 판단 설명(전략 changes의 what·why, 미룬 범위 이유)
     * @return 검사 결과를 담은 판정과, 서버가 덧붙일 판단 설명(없으면 null)
     */
    public static Result check(ScopeReport report, List<Long> itemCourses, List<String> modelReasons) {
        if (report == null) {
            return new Result(null, null);
        }
        if (report.scopes().isEmpty()) {
            // 범위를 말하지 않은 것과 범위를 판정하지 못한 것(추출 실패·버린 원소)은 다르게 보인다.
            boolean unjudged = report.incomplete() || ScopeReport.FAILED.equals(report.status());
            return new Result(report.withChecks(List.of(),
                    unjudged ? "말한 범위를 읽지 못해 계획이 범위를 다 담았는지 판단하지 못했어요." : null), null);
        }
        Map<String, RequestScope> byId = new LinkedHashMap<>();
        report.scopes().forEach(s -> byId.put(s.scopeId(), s));
        Map<String, RequestScope.Part> parts = new LinkedHashMap<>();
        report.scopes().forEach(s -> s.parts().forEach(p -> parts.put(p.partId(), p)));

        List<String> issues = new ArrayList<>();
        String serverReason = null;
        // 판단 설명이 가리켜야 할 과목: 항목이 들어간 "가능하면" 범위와 남은 "꼭" 범위. 다른 과목 얘기는 이 판단의 설명이 아니다.
        java.util.Set<String> relevantTitles = new java.util.LinkedHashSet<>();
        boolean optionalItems = false;
        for (RequestScope s : report.scopes()) {
            if (s.verified() && !s.required() && s.courseId() != null && itemCourses.contains(s.courseId())) {
                optionalItems = true;
                if (s.courseTitle() != null) {
                    relevantTitles.add(s.courseTitle());
                }
            }
        }
        List<String> unmetRequired = new ArrayList<>();
        for (ScopeReport.PartState state : report.parts()) {
            RequestScope s = byId.get(state.scopeId());
            if (s != null && s.verified() && s.required() && report.remaining().contains(state.partId())) {
                unmetRequired.add(label(s, parts.get(state.partId())) + "(" + describe(state) + ")");
                if (s.courseTitle() != null) {
                    relevantTitles.add(s.courseTitle());
                }
            }
        }
        if (report.requiredUnmet() && optionalItems) {
            boolean explained = modelReasons != null && modelReasons.stream().filter(Objects::nonNull)
                    .anyMatch(r -> relevantTitles.stream().anyMatch(r::contains));
            if (!explained) {
                issues.add(OPTIONAL_WITHOUT_REASON);
                serverReason = "꼭 하기로 한 범위 중 " + String.join(", ", unmetRequired)
                        + "이(가) 남았는데 '가능하면' 범위를 넣었다. 계획이 이 판단의 이유를 남기지 않아 서버가 남은 범위를 적는다.";
            }
        }
        return new Result(report.withChecks(issues, notice(report, unmetRequired)), serverReason);
    }

    public record Result(ScopeReport report, String serverReason) {
    }

    /** 화면 맨 위 문장. 판정만 보고 만든다. */
    static String notice(ScopeReport report, List<String> unmetRequired) {
        int remainingOptional = Math.max(0, report.remaining().size() - unmetRequired.size());
        String confirm = report.needsConfirm().isEmpty() ? "" : " 원문에서 확인하지 못한 범위 " + report.needsConfirm().size()
                + "개는 확인이 필요해요.";
        return switch (ScopeReport.Completion.valueOf(report.completion())) {
            case ALL -> "말한 범위를 모두 계획에 담았어요.";
            case REQUIRED_ONLY -> "꼭 하기로 한 범위는 담았어요." + (remainingOptional > 0
                    ? " '가능하면' 범위 중 " + remainingOptional + "곳은 이번 계획에 없어요." : "") + confirm;
            case NOT_COMPLETE -> "꼭 하기로 한 범위 중 " + unmetRequired.size() + "곳이 아직 계획에 없어요: "
                    + String.join(", ", unmetRequired) + "." + confirm;
            case UNKNOWN -> report.scopes().isEmpty() ? null
                    : "말한 범위를 원문에서 확인하지 못해 계획이 범위를 다 담았는지 판단하지 못했어요." + confirm;
        };
    }

    private static String label(RequestScope s, RequestScope.Part p) {
        String course = s.courseTitle() == null ? "" : s.courseTitle() + " ";
        return course + (p == null ? s.scopeId() : p.label());
    }

    private static String describe(ScopeReport.PartState state) {
        String reflection = switch (ScopeReport.Reflection.valueOf(state.reflection())) {
            case DEFERRED -> "미룸" + (state.note() == null ? "" : ": " + state.note());
            case UNHANDLED -> "항목 없음";
            default -> state.reflection();
        };
        String acquisition = switch (ScopeReport.Acquisition.valueOf(state.acquisition())) {
            case NO_MATERIAL_IN_APP -> "앱에 자료 없음";
            case SHOWN_NOT_SELECTED -> "자료를 고르지 않음";
            case AVAILABLE_NOT_ACQUIRED -> "원문 미확보";
            case EXCERPT_ONLY -> "원문 일부만";
            case DELIVERED_UNIT -> "원문 있음";
        };
        return reflection + " · " + acquisition;
    }
}
