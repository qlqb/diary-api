package com.jungwoo.project.memo.material.analysis;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 자료 하나의 분석 상태. 화면의 칩("분석 대기 / 분석 중 / 일부 완료 / 완료 / 다시 시도 / 사용 불가 / 일시중지")은
 * state 하나로 그린다. 세부(청크 진행·오류 종류·연결별 변경안)는 접힌 곳에 쓴다.
 *
 * <p>state 값: NONE(등록 전) QUEUED RUNNING PARTIAL DONE FAILED UNAVAILABLE PAUSED NO_TEXT(추출 실패).
 * linkStates는 (프로젝트별 LINK 작업) 상태다.
 */
@Getter
@Builder
public class MaterialAnalysisStatusResponse {

    private Long materialId;
    private String state;
    private Integer totalChunks;
    private Integer completedChunks;
    private String errorCode;
    private String message;
    private Integer sectionCount;
    private Integer assignmentCandidateCount;
    private Integer pageCount;
    private LocalDateTime updatedAt;
    private LocalDateTime finishedAt;
    private List<LinkState> linkStates;

    @Getter
    @Builder
    public static class LinkState {
        private Long courseId;
        private String state;
        private Long proposalId;
        private String message;
    }
}
