package com.jungwoo.project.memo.plan.selection;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.jungwoo.project.memo.plan.domain.FamiliarityAnswer;
import com.jungwoo.project.memo.plan.domain.PlanIntensity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 초안을 만든 요청 그대로. ai_proposals.plan_request_json에 남아 <b>같은 조건으로 다시 만들기</b>(이번만 빼기·되돌리기·
 * 이미 알아요 뒤 재생성)에 쓰인다. 계획 화면과 상담이 같은 모양이다 — 상담 초안을 다시 만들 때 화면의 기본 날짜와 빈
 * 지시로 요청을 새로 조립하지 않기 위해서다.
 *
 * <p>2판(2026-09-17)부터 <b>그때 모델에 준 근거의 스냅샷</b>({@link EvidenceSnapshot})을 함께 둔다. 다시 만들 때 서버는
 * 이 스냅샷과 지금 값을 비교해, 달라진 것이 없으면 자료 선택 호출을 생략하고 같은 근거를 다시 읽으며, 달라졌으면 무엇이
 * 달라졌는지 초안에 표시한다. "그때 읽은 것"을 사후 DB 조회로 꾸미지 않는다.
 *
 * @param source              PLAN_SCREEN / CONVERSATION
 * @param intensity           서버가 승계까지 끝낸 강도(다시 만들 때 직전 계획이 바뀌어도 같은 강도로)
 * @param instruction         서버가 모델에 넘긴 지시 그대로(상담이면 대화 요약)
 * @param requestedMaterialIds 화면·되묻기로 지정한 자료. 지시 문장에서 찾은 자료는 넣지 않는다 — 지시가 그대로 남으므로
 *                            다시 만들 때 같은 규칙으로 다시 찾는다
 * @param requestKey          화면이 붙인 요청 키(중복 클릭·재시도 식별). 없으면 null
 * @param briefId             상담 합의(ai_plan_briefs)와 그때의 판. 계획 화면 요청이면 null
 * @param previousProposalId  이 초안이 대체한 초안. 처음 만든 초안이면 null
 * @param evidence            그때 모델에 준 근거의 스냅샷
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanRequestContext(
        int version,
        String source,
        LocalDate startDate,
        LocalDate endDate,
        PlanIntensity intensity,
        String title,
        String instruction,
        List<Long> courseIds,
        List<Long> excludeTopicIds,
        List<Long> requestedMaterialIds,
        List<Long> requestedSectionIds,
        FamiliarityAnswer familiarityAnswer,
        List<Long> familiarityTopicIds,
        Long conversationId,
        String requestKey,
        Long briefId,
        Integer briefVersion,
        Long previousProposalId,
        EvidenceSnapshot evidence
) {
    public static final int VERSION = 2;

    /** 1판 생성자(테스트·예전 호출부). */
    public PlanRequestContext(int version, String source, LocalDate startDate, LocalDate endDate, PlanIntensity intensity,
                              String title, String instruction, List<Long> courseIds, List<Long> excludeTopicIds,
                              List<Long> requestedMaterialIds, List<Long> requestedSectionIds,
                              FamiliarityAnswer familiarityAnswer, List<Long> familiarityTopicIds, Long conversationId) {
        this(version, source, startDate, endDate, intensity, title, instruction, courseIds, excludeTopicIds,
                requestedMaterialIds, requestedSectionIds, familiarityAnswer, familiarityTopicIds, conversationId,
                null, null, null, null, null);
    }

    /**
     * 그때 모델에 준 근거. 재사용 판정과 변경 감지의 기준이다.
     *
     * @param fingerprint       카탈로그(학습 항목·진행·표식, 구간 id·해시, 과제 id·판·완료)·가용시간·제외 목록의 해시
     * @param availabilityHash  가용 구간만의 해시(무엇이 달라졌는지 말할 때 가른다)
     * @param materialsHash     자료·구간 해시만의 해시
     * @param assignmentsHash   과제 상태만의 해시
     * @param progressHash      학습 항목 진행·표식만의 해시
     * @param sections          모델이 골라 서버가 읽은 구간
     * @param topics            모델이 고른 학습 항목
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EvidenceSnapshot(String fingerprint, String availabilityHash, String materialsHash,
                                   String assignmentsHash, String progressHash, LocalDateTime capturedAt,
                                   List<SectionPick> sections, List<TopicPick> topics) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SectionPick(Long sectionId, Long materialId, String fileHash, Long courseId, Long topicId, String reason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TopicPick(Long topicId, Long courseId, String reason) {
    }
}
