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

    /**
     * 끝나지 않은 작업이 왜 기다리는가: DAILY_LIMIT(오늘 한도) / PAUSED(사용자가 멈춤) / SERVICE_UNAVAILABLE(모델 미설정·
     * 인증 문제) / QUEUED(차례를 기다림). 끝났거나 실패한 자료는 null이다.
     */
    private String waitingReason;

    /** 프로젝트 연결(구조 제안) 작업 중 가장 덜 끝난 상태. 연결이 없으면 null. */
    private String linkState;

    @Getter
    @Builder
    public static class LinkState {
        private Long courseId;
        private String state;
        private Long proposalId;
        private String message;
    }
}
