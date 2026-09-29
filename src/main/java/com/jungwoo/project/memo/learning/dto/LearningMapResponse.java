package com.jungwoo.project.memo.learning.dto;

import java.util.List;

/**
 * 프로젝트 전체 학습 지도. 주제별 보기와 주차별 보기는 같은 학습 항목의 다른 보기다.
 *
 * <p>다섯 상태를 구분할 수 있게 {@link State}를 준다: 자료 없음(materials=0) / 구조 제안 대기(openProposals&gt;0, topics=0) /
 * 분석 대기(analysisPending&gt;0) / 분석 실패(analysisFailed&gt;0) / 학습 기록 없음(hasRecords=false).
 *
 * @param treeVersion 학습 구조의 판. 구조 제안을 적용할 때의 낙관적 잠금 기준과 같다
 * @param proposed    승인 전 자동 분석 제안(읽기 전용). 학습 항목이 아니다 — 진도를 표시하지 않는다
 * @param weeks       사용자가 확인한 자료의 실제 주차(자료 ↔ 주차 관계). 없으면 빈 목록(주차 보기를 숨긴다)
 * @param weekReview  자료 주차 확인 요약. 추천은 여기 없다 — 확인 화면(material-weeks)에서만 본다
 */
public record LearningMapResponse(Long courseId, String title, Long treeVersion, State state, List<TopicNode> topics,
                                  List<ProposedGroup> proposed, List<UnlinkedMaterial> unlinked, List<Week> weeks,
                                  WeekReview weekReview) {

    public record State(int materials, int analysisPending, int analysisFailed, int linkWaiting, int openProposals,
                        int topics, boolean hasRecords) {
    }

    /**
     * @param selfCheck    사용자의 자기평가(KNOW/UNSURE/NEW). 숙달·완료가 아니다
     * @param plannedItems 이 항목에 걸린 실행 항목 수(취소 제외)
     */
    /**
     * @param classWeek       사용자가 정정한 실제 수업 주차(없으면 null). 교재 위치와 별개다
     * @param classSeq        사용자가 정정한 실제 수업 순서(없으면 null)
     * @param scopeLabel      시험·계획 범위에서 뺐으면 그 이름("중간고사", 이름 없으면 "")
     * @param mergedDoneItems 이 항목으로 병합된(보관된) 항목에 남아 있는 끝낸 실행 수. 옮겨 오지 않았다 — 상태는 사용자가 정한다
     * @param sourceType      SOURCE(원문에 그 제목이 있음) / AI_DERIVED. 상세 화면이 출처를 바르게 말하려면 필요하다
     * @param sourceLocator   원문 위치("교재 p.41")
     */
    public record TopicNode(Long topicId, Long parentTopicId, String title, String progressStatus, String userMark,
                            String selfCheck, List<MaterialRef> materials, int plannedItems, int doneItems,
                            List<TopicNode> children, Integer classWeek, Integer classSeq, String scopeLabel,
                            int mergedDoneItems, String sourceType, String sourceLocator) {

        public TopicNode(Long topicId, Long parentTopicId, String title, String progressStatus, String userMark,
                         String selfCheck, List<MaterialRef> materials, int plannedItems, int doneItems,
                         List<TopicNode> children) {
            this(topicId, parentTopicId, title, progressStatus, userMark, selfCheck, materials, plannedItems, doneItems,
                    children, null, null, null, 0, null, null);
        }
    }

    public record MaterialRef(Long materialId, String filename, Long sectionId, String sectionTitle, String locator) {
    }

    public record ProposedGroup(Long proposalId, Long materialId, String filename, List<ProposedNode> nodes) {
    }

    /**
     * @param tempId        "p{proposalId}:{tempId}" — 학습 항목 id가 아니다
     * @param parentTopicId 이미 있는 학습 항목 아래에 붙는 제안이면 그 항목(LINK면 연결 대상)
     */
    public record ProposedNode(String tempId, String title, String parentTempId, Long parentTopicId, String op,
                               String reason, List<SectionRef> sections) {
    }

    public record SectionRef(Long sectionId, String title, String locator) {
    }

    /** @param waitingReason DAILY_LIMIT / QUEUED / PAUSED / SERVICE_UNAVAILABLE / null */
    public record UnlinkedMaterial(Long materialId, String filename, String analysisState, String waitingReason,
                                   List<SectionRef> sections) {
    }

    /**
     * 확인된 주차 하나. 주차는 학습 항목이 아니라 <b>자료</b>의 속성이다 — 그 주차에 놓인 자료, 그 자료의 구간,
     * 그 자료와 직접 연결된 학습 항목 순으로 따라간다. 상위 항목이라는 이유만으로 하위 항목의 주차에 복제하지 않는다.
     *
     * @param basis     CONFIRMED_MATERIAL — 사용자가 확인한 자료 ↔ 주차 관계
     * @param confirmed 항상 true. 추천(확인 전)은 지도에 넣지 않는다
     * @param topicIds  그 주차 자료와 직접 연결된 항목. 같은 근거를 하위 항목이 이미 갖고 있으면 상위 항목은 뺀다
     * @param materials materialIds와 같은 자료의 이름. 항목에 연결되지 않은 자료도 이름이 보여야 한다
     */
    public record Week(String label, String basis, boolean confirmed, List<Long> materialIds, List<Long> sectionIds,
                       List<Long> topicIds, int weekNo, List<WeekMaterial> materials) {
    }

    public record WeekMaterial(Long materialId, String filename) {
    }

    /**
     * @param needsReview           아직 어디에도 놓이지 않은 자료 수(분석이 도는 중인 자료는 빼고 센다)
     * @param placed                확인된 자리가 있는 자료 수
     * @param courseWide  전체 참고자료로 확인된 자료(강의계획서 등). 특정 주차에 넣지 않는다
     */
    public record WeekReview(int needsReview, int placed, List<WeekMaterial> courseWide) {
    }
}
