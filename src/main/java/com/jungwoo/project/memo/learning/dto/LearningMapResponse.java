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
 * @param weeks       자료가 스스로 말한 주차. 없으면 빈 목록(주차 보기를 숨긴다)
 */
public record LearningMapResponse(Long courseId, String title, Long treeVersion, State state, List<TopicNode> topics,
                                  List<ProposedGroup> proposed, List<UnlinkedMaterial> unlinked, List<Week> weeks) {

    public record State(int materials, int analysisPending, int analysisFailed, int linkWaiting, int openProposals,
                        int topics, boolean hasRecords) {
    }

    /**
     * @param selfCheck    사용자의 자기평가(KNOW/UNSURE/NEW). 숙달·완료가 아니다
     * @param plannedItems 이 항목에 걸린 실행 항목 수(취소 제외)
     */
    public record TopicNode(Long topicId, Long parentTopicId, String title, String progressStatus, String userMark,
                            String selfCheck, List<MaterialRef> materials, int plannedItems, int doneItems,
                            List<TopicNode> children) {
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
     * @param basis     MATERIAL_LABEL — 자료에 적힌 주차 표기
     * @param confirmed 항상 false다. 실제 수업 진행·개인 진도와 다를 수 있다
     */
    public record Week(String label, String basis, boolean confirmed, List<Long> materialIds, List<Long> sectionIds,
                       List<Long> topicIds) {
    }
}
