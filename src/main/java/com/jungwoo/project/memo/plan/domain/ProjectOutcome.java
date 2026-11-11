package com.jungwoo.project.memo.plan.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 이번 생성에서 대상 프로젝트 하나가 어떻게 처리됐는가. 요청한 대상 프로젝트는 <b>모두 정확히 한 번</b> 나온다.
 *
 * <p>두 축을 섞지 않는다.
 * <ul>
 *   <li>{@link Disposition} — 판단의 결과: 포함 / 의도적 제외 / 판단 보류 / 시스템 제약으로 미검토.</li>
 *   <li>{@link MaterialState} — 자료가 어디까지 갔는가: 없음 / 분석 대기 / 목록에도 못 실림 / 개요만 / 원문 전달 / 조회 실패.
 *       "목록에 있음 → 조회 성공 → 모델 입력에 전달됨"은 서로 다른 단계이고, 전달됐다고 이해가 확인된 것도 아니다.</li>
 * </ul>
 *
 * <p>서버는 의도적 제외의 <b>이유</b>나 학습 항목을 지어내지 않는다. 모델이 결과를 내지 않았거나, 후보를 보지도 못한
 * 프로젝트를 "제외"라고 했으면 서버가 확인한 사실만으로 {@link Disposition#NOT_REVIEWED}로 적는다({@code decidedBy=SERVER}).
 *
 * @param reason      사용자가 읽을 짧은 이유. 조회 실패를 중요도 판단으로 포장하지 않는다
 * @param decidedBy   MODEL(모델의 판단) / SERVER(서버가 확인한 사실로 정함)
 * @param candidates  이 프로젝트의 후보(학습 항목 + 구간) 수
 * @param shown       그중 선택 모델 입력에 줄로 실린 수. 선택을 재사용한 회차면 null(이번에 목록을 보내지 않았다)
 * @param selected    고른 구간 수
 * @param delivered   원문이 계획 모델 입력에 실제로 실린 구간 수
 * @param sectionIds  원문이 전달된 구간 id(유효한 근거 참조)
 * @param nextAction  필요한 경우 사용자가 할 수 있는 다음 행동
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProjectOutcome(Long courseId, String courseTitle, Disposition disposition, String reason,
                             String decidedBy, MaterialState materialState, int candidates, Integer shown,
                             int selected, int delivered, int itemCount, int itemMinutes, List<Long> sectionIds,
                             NextAction nextAction) {

    public enum Disposition {
        /** 이번 초안에 이 프로젝트의 항목이 있다. */
        INCLUDED,
        /** 모델이 판단해 이번에 뺐다(사용자 요청·이미 앎·시간 등). 이유는 모델의 것이다. */
        EXCLUDED_BY_CHOICE,
        /** 본 범위만으로는 정하지 못했다. 답변·추가 자료에 따라 달라진다. */
        UNDECIDED,
        /** 시스템 제약(목록·원문을 보내지 못함, 조회 실패, 모델이 결과를 내지 않음)으로 검토하지 못했다. */
        NOT_REVIEWED
    }

    public enum MaterialState {
        /** 연결된 자료가 없다. */
        NO_MATERIAL,
        /** 자료는 있지만 내용 분석이 끝나지 않아 후보가 없다. */
        ANALYSIS_PENDING,
        /** 분석된 자료는 있지만 계획에 쓸 후보(구간·학습 항목)가 없다. */
        NO_RELEVANT_CONTENT,
        /** 후보는 있지만 입력 한도 때문에 선택 모델에 한 줄도 보여 주지 못했다. */
        NOT_LISTED,
        /** 목록(개요)은 보여 줬지만 원문은 전달되지 않았다. */
        OUTLINE_ONLY,
        /** 고른 구간의 원문을 읽지 못했다(삭제·변경·추출 실패·입력 한도). */
        RETRIEVAL_FAILED,
        /** 원문이 계획 모델 입력에 실렸다. */
        TEXT_DELIVERED
    }

    public enum NextAction { ANSWER_QUESTION, UPLOAD_MATERIAL, WAIT_ANALYSIS, RETRY_ANALYSIS, NARROW_SCOPE, REVIEW_LATER }
}
