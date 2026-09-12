package com.jungwoo.project.memo.plan.dto;

import com.jungwoo.project.memo.plan.domain.FamiliarityAnswer;
import com.jungwoo.project.memo.plan.domain.PlanIntensity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * 기간 계획 초안 생성 요청.
 *
 * 기간 프리셋([오늘] [이번 주] [이번 달] ...)은 화면이 날짜로 변환해 보낸다 — 서버는 프리셋
 * 이름을 저장하지 않는다. 같은 날짜 범위를 어떤 버튼으로 골랐는지는 계획의 성질을 바꾸지
 * 않으므로 남길 이유가 없다.
 *
 * intensity가 null이면 서버가 직전 확정 계획에서 승계한다(11-period-plan.md §5-1-2).
 * 클라이언트는 분 단위를 보내지 않는다 — 기준선 계산은 서버가 소유한다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlanDraftRequest {

    private LocalDate startDate;

    private LocalDate endDate;

    /** null이면 직전 확정 계획에서 승계, 그것도 없으면 NORMAL. */
    private PlanIntensity intensity;

    /** 없으면 AI가 제안한다. */
    private String title;

    /** "시험 전까지 자료구조 위주로" 같은 자유 지시. AI가 기준선을 조정하는 근거가 된다. */
    private String instruction;

    /** 비어 있으면 전체 프로젝트를 대상으로 한다. */
    private List<Long> courseIds;

    /**
     * 익숙함을 묻는 되묻기에 고른 답. 화면이 그 답과 함께 아래 topicIds를 그대로 돌려보낸다.
     *
     * <p>★ 값이 있으면 그 요청에서는 다시 묻지 않는다. 이것이 없으면 되묻기가 끝나지 않는다 —
     * "처음이에요"는 저장할 상태가 없어서(근거 없음이 이미 기본이고 그때 취급이 이미 FULL이다)
     * 다음 요청에서 판단이 똑같이 "근거가 없다"고 보고 또 묻는다. 실데이터로 확인했다.
     *
     * <p>{@code FAMILIAR}는 다르다. 그 답은 USER_CONFIRMED 맥락으로 저장되어 <b>이번 초안부터</b>
     * 근거가 되고, 다음 계획에서도 남는다.
     */
    private FamiliarityAnswer familiarityAnswer;

    /** 그 되묻기가 대상으로 삼았던 학습 항목. 응답의 {@code ask.topicIds}를 그대로 돌려보낸다. */
    private List<Long> familiarityTopicIds;

    /**
     * 「이번 계획에서 제외」. 이 요청에서만 후보에서 뺀다. 저장하지 않는다 — 영구 표식(KNOWN/DEFER)과 다르고,
     * 다음 계획에는 다시 후보로 돌아온다.
     */
    private List<Long> excludeTopicIds;
}
