package com.jungwoo.project.memo.material.batch.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 묶음 하나의 현재 모습. 시작 전 미리보기와 진행 중 화면이 같은 모양을 쓴다.
 *
 * <p><b>processedPercent는 "처리 진행률"이지 "성공률"이 아니다.</b> 실패·본문 없음으로 끝난
 * 자리도 더 할 일이 없으므로 처리된 것으로 센다. 화면은 100%를 "다 됐어요"가 아니라
 * "처리 종료 · 성공 n · 안 된 것 m"으로 말해야 한다.
 *
 * <p>산식: 자리 하나가 1이고, 분석 중인 자리는 청크 진행분만큼(최대 0.95) 센다. 분모는
 * 묶음을 만들 때 고정된 자리 수라 분석 중에 다른 파일을 올려도 움직이지 않는다.
 */
@Getter
@Builder
public class BatchResponse {

    private Long batchId;
    private Long courseId;
    /** STAGED / UPLOADING / ANALYZING / FINISHED / ABANDONED */
    private String status;
    private int itemCount;

    /** 처리 진행률(0~100). 위 설명 참고. */
    private int processedPercent;
    private int doneCount;
    private int runningCount;
    private int waitingCount;
    private int failedCount;
    /** 본문을 읽지 못했거나 형식이 맞지 않아 분석 대상이 아닌 자리. 실패와 구분해 센다. */
    private int skippedCount;

    /** 지금 가장 많은 자리가 머무는 단계의 사람 말("내용 분석"). 없으면 null. */
    private String currentStage;
    /**
     * 남은 예상 시간(초) 범위. 아직 아무것도 시작하지 않았으면 전체 예상이다.
     * 시간이 지났다는 이유만으로 상태를 바꾸지 않는다 — 이 값이 0이 되어도 끝난 것이 아니다.
     */
    private Integer remainingMinSeconds;
    private Integer remainingMaxSeconds;
    private String estimateBasis;
    /** 업로드 전송 시간은 이 숫자에 들어 있지 않다. 화면이 그 말을 한다. */
    private boolean uploadTimeExcluded;
    /**
     * 기다리는 이유가 처리 차례가 아닐 때: DAILY_LIMIT / PAUSED / SERVICE_UNAVAILABLE.
     * 값이 있으면 남은 시간 대신 이 사유를 보여준다.
     */
    private String waitingReason;
    private LocalDateTime resumesAt;

    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    private List<Item> items;

    @Getter
    @Builder
    public static class Item {
        private Long itemId;
        private String filename;
        private Long sizeBytes;
        private String extension;
        private Long materialId;
        /** STAGED / UPLOADING / UPLOADED / UPLOAD_FAILED / UNSUPPORTED / NO_TEXT / ABANDONED */
        private String uploadState;
        /**
         * 이 자리가 지금 어디에 있는가.
         * STAGED UPLOADING QUEUED ANALYZING DONE PARTIAL FAILED NO_TEXT UNSUPPORTED UPLOAD_FAILED
         * PAUSED CANCELLED
         */
        private String stage;
        private String stageLabel;
        private Integer totalChunks;
        private Integer completedChunks;
        private Integer estMinSeconds;
        private Integer estMaxSeconds;
        private String message;
        /** 이 자리가 더 할 일이 없는가(성공이든 아니든). */
        private boolean settled;
        /** 다시 시도할 수 있는가. 실패한 분석만 true다. */
        private boolean retryable;
    }

    /**
     * 열린 묶음 한 쪽.
     *
     * <p>totalOpen을 함께 준다. 화면이 "지금 보이는 것 말고도 더 있다"를 알아야, 목록에 없는
     * 묶음을 끝난 것으로 추측하지 않는다.
     */
    @Getter
    @Builder
    public static class Page {
        private List<BatchResponse> batches;
        /** 다음 쪽을 부를 때 넘길 값. null이면 마지막 쪽이다. */
        private Long nextCursor;
        /** 지금 열린 묶음 전체 수(이 쪽에 실린 것만이 아니다). */
        private int totalOpen;
    }
}
