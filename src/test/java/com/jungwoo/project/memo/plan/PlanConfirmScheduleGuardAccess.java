package com.jungwoo.project.memo.plan;

import com.jungwoo.project.memo.ai.brief.StudyTimeCondition;
import com.jungwoo.project.memo.ai.dto.AiProposalApplyRequest;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 다른 패키지 테스트에서 확정 검사 규칙(패키지 공개)을 부르기 위한 통로. */
public final class PlanConfirmScheduleGuardAccess {

    private PlanConfirmScheduleGuardAccess() {
    }

    public static List<Map<String, Object>> violations(List<AiProposalApplyRequest.EditedProposalItem> timed,
                                                       StudyTimeCondition condition, LocalDate from, LocalDate to) {
        return PlanConfirmScheduleGuard.studyTimeViolations(timed, condition, from, to);
    }
}
