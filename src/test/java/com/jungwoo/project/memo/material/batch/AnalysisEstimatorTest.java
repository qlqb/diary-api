package com.jungwoo.project.memo.material.batch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 시작 전 예상 시간.
 *
 * <p>고정하는 것:
 * <ul>
 *   <li>표본이 없으면 기본값을 쓰고 basis가 DEFAULT다 — 화면이 "초기 추정"이라고 말할 근거.</li>
 *   <li>표본이 쌓이면 지난 실행을 쓰고 basis가 HISTORY다.</li>
 *   <li>묶음은 동시 처리 수로 나눈다. 다만 가장 오래 걸리는 파일보다 짧아지지 않는다.</li>
 *   <li>압축은 열어 봐야 아는 것이라 초를 내지 않는다 — 지어내지 않고 사유를 준다.</li>
 *   <li>업로드 전송 시간은 절대 포함하지 않는다.</li>
 * </ul>
 */
class AnalysisEstimatorTest {

    private MaterialAnalysisTimingMapper timings;
    private AnalysisEstimator estimator;

    @BeforeEach
    void setUp() {
        timings = mock(MaterialAnalysisTimingMapper.class);
        when(timings.recentSecondsPerChunk(anyInt())).thenReturn(List.of());
        when(timings.recentExtractSeconds(anyInt())).thenReturn(List.of());
        when(timings.medianCharsPerByte(anyString(), anyInt())).thenReturn(null);
        when(timings.countQueuedAhead()).thenReturn(0);
        estimator = new AnalysisEstimator(timings);
        ReflectionTestUtils.setField(estimator, "chunkChars", 24000);
        ReflectionTestUtils.setField(estimator, "maxChunksPerJob", 12);
        ReflectionTestUtils.setField(estimator, "concurrency", 2);
    }

    @Test
    void 표본이_없으면_기본값을_쓰고_초기_추정이라고_말할_수_있게_basis를_준다() {
        List<AnalysisEstimator.FileEstimate> files = estimator.estimateFiles(
                List.of(new AnalysisEstimator.StagedFile("1주차.pdf", 2_000_000L)));

        assertThat(files).hasSize(1);
        assertThat(files.get(0).basis()).isEqualTo("DEFAULT");
        assertThat(files.get(0).estimable()).isTrue();
        assertThat(files.get(0).minSeconds()).isLessThan(files.get(0).maxSeconds());
    }

    @Test
    void 표본이_충분하면_지난_실행을_쓴다() {
        when(timings.recentSecondsPerChunk(anyInt()))
                .thenReturn(List.of(10.0, 11.0, 12.0, 13.0, 14.0, 15.0, 16.0, 40.0));
        when(timings.recentExtractSeconds(anyInt())).thenReturn(List.of(0.5, 0.7, 1.0));

        AnalysisEstimator.Baseline baseline = estimator.baseline();

        assertThat(baseline.basis()).isEqualTo("HISTORY");
        // 낮은 쪽은 중앙값, 높은 쪽은 상위 20% — 범위를 주는 이유는 실제로 그만큼 흔들리기 때문이다.
        assertThat(baseline.secondsPerChunkLow()).isLessThan(baseline.secondsPerChunkHigh());
        assertThat(baseline.secondsPerChunkHigh()).isGreaterThanOrEqualTo(16.0);
    }

    @Test
    void 표본이_적으면_기본값과_섞고_PARTIAL_HISTORY로_표시한다() {
        when(timings.recentSecondsPerChunk(anyInt())).thenReturn(List.of(4.0, 5.0, 6.0));

        AnalysisEstimator.Baseline baseline = estimator.baseline();

        assertThat(baseline.basis()).isEqualTo("PARTIAL_HISTORY");
        // 세 건만 보고 "5초"라고 말하지 않는다 — 기본값 쪽으로 끌어올린다.
        assertThat(baseline.secondsPerChunkLow()).isGreaterThan(6.0);
    }

    @Test
    void 압축_파일은_열어_봐야_아는_것이라_초를_지어내지_않는다() {
        List<AnalysisEstimator.FileEstimate> files = estimator.estimateFiles(
                List.of(new AnalysisEstimator.StagedFile("강의자료.zip", 5_000_000L)));

        assertThat(files.get(0).supported()).isTrue();
        assertThat(files.get(0).estimable()).isFalse();
        assertThat(files.get(0).minSeconds()).isNull();
        assertThat(files.get(0).reason()).contains("열어 봐야");
    }

    @Test
    void 지원하지_않는_형식은_이유와_함께_지원_안_함으로_표시된다() {
        List<AnalysisEstimator.FileEstimate> files = estimator.estimateFiles(
                List.of(new AnalysisEstimator.StagedFile("사진.png", 100_000L)));

        assertThat(files.get(0).supported()).isFalse();
        assertThat(files.get(0).reason()).isNotNull();
    }

    @Test
    void 묶음은_동시_처리_수로_나누되_가장_오래_걸리는_파일보다_짧아지지_않는다() {
        List<AnalysisEstimator.FileEstimate> files = estimator.estimateFiles(List.of(
                new AnalysisEstimator.StagedFile("a.pdf", 3_000_000L),
                new AnalysisEstimator.StagedFile("b.pdf", 3_000_000L),
                new AnalysisEstimator.StagedFile("c.pdf", 3_000_000L),
                new AnalysisEstimator.StagedFile("d.pdf", 3_000_000L)));
        int longest = files.stream().mapToInt(AnalysisEstimator.FileEstimate::minSeconds).max().orElseThrow();
        int sum = files.stream().mapToInt(AnalysisEstimator.FileEstimate::minSeconds).sum();

        AnalysisEstimator.BatchEstimate batch = estimator.estimateBatch(files, 0);

        assertThat(batch.minSeconds()).isGreaterThanOrEqualTo(longest);
        assertThat(batch.minSeconds()).isLessThan(sum);
        assertThat(batch.estimableCount()).isEqualTo(4);
        // 전송 시간은 여기 없다. 화면이 따로 말해야 한다.
        assertThat(batch.uploadTimeExcluded()).isTrue();
    }

    @Test
    void 앞선_대기열이_있으면_그만큼_더해지고_없으면_0이다() {
        List<AnalysisEstimator.FileEstimate> files = estimator.estimateFiles(
                List.of(new AnalysisEstimator.StagedFile("a.pdf", 1_000_000L)));

        assertThat(estimator.estimateBatch(files, 0).queueAheadSeconds()).isZero();
        assertThat(estimator.estimateBatch(files, 6).queueAheadSeconds()).isPositive();
    }

    @Test
    void 추정할_수_있는_파일이_하나도_없으면_초를_null로_준다() {
        List<AnalysisEstimator.FileEstimate> files = estimator.estimateFiles(
                List.of(new AnalysisEstimator.StagedFile("사진.png", 100L),
                        new AnalysisEstimator.StagedFile("묶음.zip", 100L)));

        AnalysisEstimator.BatchEstimate batch = estimator.estimateBatch(files, 0);

        assertThat(batch.minSeconds()).isNull();
        assertThat(batch.estimableCount()).isZero();
        assertThat(batch.unestimableCount()).isEqualTo(1); // zip은 지원하지만 못 센다
    }

    @Test
    void 실제_글자_수를_알면_그것으로_다시_센다() {
        // 같은 크기의 PDF라도 스캔본이면 글자가 거의 없다. 고르는 단계의 짐작을 실측이 대신한다.
        AnalysisEstimator.FileEstimate guessed = estimator.estimateFiles(
                List.of(new AnalysisEstimator.StagedFile("스캔본.pdf", 20_000_000L))).get(0);
        AnalysisEstimator.FileEstimate refined = estimator.refine("스캔본.pdf", 20_000_000L, 300);

        assertThat(refined.minSeconds()).isLessThan(guessed.minSeconds());
    }

    @Test
    void 청크_수는_상한을_넘지_않는다() {
        // 아주 긴 문서라도 한 번의 실행이 읽는 청크에는 상한이 있다(PARTIAL로 남는다).
        assertThat(estimator.chunkCount(10_000_000L)).isEqualTo(12);
        assertThat(estimator.chunkCount(1)).isEqualTo(1);
    }
}
