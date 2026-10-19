package com.jungwoo.project.memo.material.batch;

import com.jungwoo.project.memo.material.batch.domain.MaterialAnalysisTiming;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 지난 분석의 소요 시간. 예상 시간을 말할 근거가 여기밖에 없다.
 *
 * <p>기록은 실패해도 분석을 실패시키지 않는다 — 호출자가 삼킨다.
 *
 * <p>중앙값·p80은 SQL이 아니라 Java에서 고른다. MariaDB 10.4에서 백분위를 SQL로 뽑으려면
 * 윈도 함수나 GROUP_CONCAT 트릭이 필요한데, 표본이 수백 건 규모라 최근 것만 읽어 정렬하는
 * 편이 읽기 쉽고 결과도 같다.
 */
@Mapper
public interface MaterialAnalysisTimingMapper {

    /** 같은 작업의 기록이 이미 있으면 무시한다(재시도로 두 번 끝난 경우). */
    int insertIgnore(MaterialAnalysisTiming timing);

    /**
     * 최근 CONTENT 실행의 "청크 하나에 걸린 초". 모델 시간을 청크 수로 나눈 값이다.
     * 실패·취소는 빼고, 청크 수와 모델 시간이 모두 있는 것만.
     */
    List<Double> recentSecondsPerChunk(@Param("limit") int limit);

    /** 최근 CONTENT 실행의 추출 초. 추출을 건너뛴 실행(이미 단위가 있던 경우)은 0이라 들어온다. */
    List<Double> recentExtractSeconds(@Param("limit") int limit);

    /**
     * 이 확장자의 "본문 글자 수 / 파일 바이트" 중앙값. 표본이 {@code minSamples}보다 적으면 null.
     * 고르는 단계에서 파일 크기로 분량을 짐작할 때 쓴다.
     */
    Double medianCharsPerByte(@Param("extension") String extension, @Param("minSamples") int minSamples);

    /** 지금 대기열에서 차례를 기다리는 작업 수. "앞선 대기" 몫을 말할 때 쓴다. */
    int countQueuedAhead();
}
