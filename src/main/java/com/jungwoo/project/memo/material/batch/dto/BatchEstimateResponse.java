package com.jungwoo.project.memo.material.batch.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * [분석 시작] 전에 보여줄 것. 파일마다 지원 여부와 예상 시간 범위, 그리고 묶음 전체의 범위.
 *
 * <p>숫자는 보장이 아니다. 고르는 시점에는 확장자와 크기밖에 없어 본문 분량을 <b>짐작</b>한다 —
 * 업로드·추출이 끝나면 실제 분량으로 다시 센다. basis가 DEFAULT·PARTIAL_HISTORY면 화면이
 * "초기 추정"이라고 말한다.
 *
 * <p>업로드 전송 시간은 포함하지 않는다(서버가 전송 속도를 모른다).
 */
@Getter
@Builder
public class BatchEstimateResponse {

    private Integer minSeconds;
    private Integer maxSeconds;
    /** HISTORY / PARTIAL_HISTORY / DEFAULT */
    private String basis;
    private int estimableCount;
    /** 지원 형식이지만 열어 봐야 분량을 아는 것(압축 파일 등). */
    private int unestimableCount;
    private int unsupportedCount;
    /** 앞선 대기열 때문에 더해진 몫(초). 0이면 지금 바로 시작한다. */
    private int queueAheadSeconds;
    private boolean uploadTimeExcluded;
    private List<File> files;

    @Getter
    @Builder
    public static class File {
        private String filename;
        private String extension;
        private Long sizeBytes;
        private boolean supported;
        private boolean estimable;
        private Integer minSeconds;
        private Integer maxSeconds;
        /** 지원하지 않거나 추정할 수 없는 이유. 지원하고 추정도 되면 null. */
        private String reason;
    }
}
