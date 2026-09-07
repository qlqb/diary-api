package com.jungwoo.project.memo.ai;

import com.jungwoo.project.memo.ai.domain.ScheduleSuggestionKind;
import com.jungwoo.project.memo.ai.dto.ContextSuggestionResponse;
import com.jungwoo.project.memo.ai.dto.ScheduleSuggestionResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 서버가 만드는 확인 문장. 모델은 이 줄을 만들 수도 지울 수도 없다.
 *
 * <p>"반영해둘게요"라고 말해 놓고 아무것도 남지 않은 사고의 구조적 방어다. 이번 턴에 실제로
 * 생긴 레코드(후보·맥락)로만 문장을 정하고, 아무것도 안 생겼으면 아무 말도 하지 않는다.
 * 승인(apply) 뒤의 문장도 같은 원칙이다 — 저장이 끝난 값만 말하고, 실패하면 문장이 없다.
 *
 * <p>문구 규칙: 실패·미완료·이행률·누락 같은 말을 쓰지 않는다.
 */
public final class SystemNotes {

    static final String CONTEXT_ONLY = "맥락으로 기록했어요. 계획 판단에 쓰이지만, 시간을 비우지는 않아요.";
    static final String SCHEDULE_TEMPLATE = "일정 후보 %d건을 만들었어요. 승인하면 그 시간이 비워져요.";
    static final String LEAD_TEMPLATE = "이동시간 후보 %d건을 만들었어요. 승인하면 %s 앞 %d분이 비워져요.";
    static final String LEAD_TEMPLATE_UNDECIDED =
            "이동시간 후보 %d건을 만들었어요. 승인할 때 고른 만큼 %s 앞이 비워져요.";
    static final String LEAD_UNRESOLVED = "어느 일정 앞인지 찾지 못했어요. 일정 이름을 알려주시면 다시 만들게요.";
    static final String LEAD_APPLIED_TEMPLATE = "%s의 이동시간을 %d분으로 저장했어요.";
    static final String LEAD_APPLIED_NONE_TEMPLATE = "%s의 이동시간을 없음으로 저장했어요.";

    private SystemNotes() {
    }

    /**
     * 상담 한 턴의 확인 문장. 생긴 것이 없으면 null이다.
     *
     * @param unresolvedLeadTargets 이동시간 후보를 내려 했지만 대상 일정을 못 찾은 수
     */
    public static String forTurn(List<ContextSuggestionResponse> contextSuggestions,
                                 List<ScheduleSuggestionResponse> scheduleSuggestions,
                                 int unresolvedLeadTargets) {
        List<String> lines = new ArrayList<>();
        List<ScheduleSuggestionResponse> leads = new ArrayList<>();
        int others = 0;
        for (ScheduleSuggestionResponse suggestion : scheduleSuggestions == null ? List.<ScheduleSuggestionResponse>of()
                : scheduleSuggestions) {
            if (suggestion.getKind() == ScheduleSuggestionKind.ROUTINE_LEAD) {
                leads.add(suggestion);
            } else {
                others++;
            }
        }
        if (!leads.isEmpty()) {
            lines.add(leadLine(leads));
        }
        if (others > 0) {
            lines.add(String.format(SCHEDULE_TEMPLATE, others));
        }
        if (lines.isEmpty() && contextSuggestions != null && !contextSuggestions.isEmpty()) {
            lines.add(CONTEXT_ONLY);
        }
        if (unresolvedLeadTargets > 0) {
            lines.add(LEAD_UNRESOLVED);
        }
        return lines.isEmpty() ? null : String.join(" ", lines);
    }

    /** 이동시간을 실제로 저장한 뒤의 문장. label은 "수업 일정" 또는 대상 요약이다. */
    public static String forLeadApplied(String label, int leadMinutes) {
        return leadMinutes == 0
                ? String.format(LEAD_APPLIED_NONE_TEMPLATE, label)
                : String.format(LEAD_APPLIED_TEMPLATE, label, leadMinutes);
    }

    private static String leadLine(List<ScheduleSuggestionResponse> leads) {
        Map<String, Object> payload = leads.get(0).getPayload();
        Object summary = payload == null ? null : payload.get("targetSummary");
        Object minutes = payload == null ? null : payload.get("leadMinutes");
        String target = summary instanceof String s && !s.isBlank() ? s : "그 일정";
        if (minutes instanceof Number n) {
            return String.format(LEAD_TEMPLATE, leads.size(), target, n.intValue());
        }
        return String.format(LEAD_TEMPLATE_UNDECIDED, leads.size(), target);
    }
}
