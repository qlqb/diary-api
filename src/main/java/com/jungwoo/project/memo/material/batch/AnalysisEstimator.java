package com.jungwoo.project.memo.material.batch;

import com.jungwoo.project.memo.material.MaterialFileFormat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "이 파일들을 분석하면 얼마나 걸릴까"의 추정.
 *
 * <h2>산식</h2>
 * <pre>
 *   글자 수  = charCount(알면) 또는 sizeBytes × 확장자별 글자/바이트 비율
 *   청크 수  = clamp(ceil(글자 수 / chunkChars), 1, maxChunksPerJob)
 *   분석 초  = 추출 초 + 청크 수 × 청크당 초
 *   묶음 초  = max(가장 오래 걸리는 파일, 전체 합 / 동시 처리 수) + 앞선 대기열 몫
 * </pre>
 * 청크당 초와 글자/바이트 비율은 {@code material_analysis_timings}의 지난 실행에서 가져온다.
 * 낮은 쪽은 중앙값, 높은 쪽은 상위 20%(p80)다 — 범위를 주는 이유는 모델 응답 시간이 실제로
 * 그만큼 흔들리기 때문이다.
 *
 * <h2>한계 — 화면이 이 숫자를 어떻게 말해야 하는지</h2>
 * <ul>
 *   <li>보장이 아니라 <b>범위</b>다. 초 단위로 말하지 않는다.</li>
 *   <li>고를 때는 파일 메타데이터(확장자·크기)뿐이라 글자 수를 <b>바이트에서 짐작</b>한다.
 *       같은 크기의 PDF라도 스캔본이면 글자가 거의 없고 텍스트 PDF면 많다. 업로드·추출이
 *       끝나 실제 분량을 알면 그때 보정한다({@link #refine}).</li>
 *   <li>표본이 {@code MIN_SAMPLES_FULL}보다 적으면 기본값을 쓰고 basis가 DEFAULT·
 *       PARTIAL_HISTORY가 된다. 화면은 그때 "초기 추정"이라고 말한다.</li>
 *   <li><b>업로드 시간은 포함하지 않는다.</b> 전송 속도를 서버가 모른다. 화면이 따로 알린다.</li>
 *   <li>ZIP처럼 안에 몇 개가 들어 있는지 열어 봐야 아는 형식은 여기서 추정하지 않는다
 *       (supported=false, 사유를 준다). 가져오기가 끝나 자료가 생기면 그 자료로 다시 센다.</li>
 *   <li>하루 한도·일시중지·인증 실패로 멈춘 시간은 들어 있지 않다. 그건 "남은 시간"이 아니라
 *       별도 상태로 보여준다.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalysisEstimator {

    /** 이만큼 모이면 지난 실행만으로 추정한다. */
    static final int MIN_SAMPLES_FULL = 8;
    /** 이만큼이면 지난 실행과 기본값을 섞는다. */
    static final int MIN_SAMPLES_PARTIAL = 3;
    /** 이만큼 최근 것만 본다. 몇 달 전 모델의 속도는 지금을 말해 주지 않는다. */
    static final int SAMPLE_WINDOW = 200;

    /**
     * 표본이 없을 때의 청크당 초. 2026-09-13~19 실행 기록에서 관찰한 모델 응답이 한 청크에
     * 10~40초였다. 모르는 쪽으로 넉넉하게 잡는다 — 짧게 말했다가 넘기는 것보다 낫다.
     */
    static final int DEFAULT_SECONDS_PER_CHUNK_LOW = 20;
    static final int DEFAULT_SECONDS_PER_CHUNK_HIGH = 50;
    /** 추출은 대개 1초 안쪽이지만 큰 PDF는 몇 초 걸린다. */
    static final int DEFAULT_EXTRACT_SECONDS = 3;

    /**
     * 확장자별 "본문 글자 수 / 파일 바이트" 기본 비율. 표본이 쌓이면 실측이 이 값을 대신한다.
     * 압축·이미지가 많은 형식일수록 작다. 정확한 값이 아니라 자릿수를 맞추기 위한 값이다.
     */
    private static final Map<String, Double> DEFAULT_CHARS_PER_BYTE = Map.ofEntries(
            Map.entry("txt", 1.0), Map.entry("md", 1.0), Map.entry("csv", 0.8),
            Map.entry("json", 0.8), Map.entry("py", 1.0), Map.entry("sh", 1.0),
            Map.entry("ipynb", 0.5), Map.entry("docx", 0.06), Map.entry("hwpx", 0.04),
            Map.entry("hwp", 0.03), Map.entry("pptx", 0.015), Map.entry("pdf", 0.03),
            Map.entry("xlsx", 0.05));
    private static final double FALLBACK_CHARS_PER_BYTE = 0.1;

    /** 자료가 아니라 "여러 자료를 가져오는 통로". 열기 전에는 몇 개인지 모른다. */
    static final String ARCHIVE_EXTENSION = "zip";

    private final MaterialAnalysisTimingMapper timingMapper;

    @Value("${material.analysis.chunk-chars:24000}")
    private int chunkChars = 24000;

    @Value("${material.analysis.max-chunks-per-job:12}")
    private int maxChunksPerJob = 12;

    @Value("${material.analysis.worker.concurrency:2}")
    private int concurrency = 2;

    /**
     * 파일 하나의 예상.
     *
     * @param supported 분석할 수 있는 형식인가. false면 초는 null이고 reason이 왜인지 말한다
     * @param basis     HISTORY / PARTIAL_HISTORY / DEFAULT — 화면이 "초기 추정"을 붙일지 정한다
     * @param estimable 초를 낼 수 있었는가. 지원 형식이라도 ZIP처럼 열어 봐야 아는 것은 false
     */
    public record FileEstimate(String filename, String extension, Long sizeBytes, boolean supported,
                               boolean estimable, Integer minSeconds, Integer maxSeconds, String basis,
                               String reason) {
    }

    /**
     * 묶음 전체의 예상.
     *
     * @param queueAheadSeconds 앞서 기다리는 다른 작업 때문에 더 걸리는 몫. 0일 수 있다
     * @param uploadTimeExcluded 언제나 true — 업로드 시간은 여기 없다는 것을 화면이 말하게 한다
     */
    public record BatchEstimate(Integer minSeconds, Integer maxSeconds, String basis, int estimableCount,
                                int unestimableCount, int queueAheadSeconds, boolean uploadTimeExcluded) {
    }

    /** 표본에서 뽑은 기준값. 한 번의 추정 동안 다시 묻지 않으려고 묶어 둔다. */
    record Baseline(double secondsPerChunkLow, double secondsPerChunkHigh, int extractSeconds, int samples,
                    String basis) {
    }

    // ===== 고를 때(파일 메타데이터만) =====

    public List<FileEstimate> estimateFiles(List<StagedFile> files) {
        Baseline baseline = baseline();
        List<FileEstimate> out = new ArrayList<>();
        for (StagedFile file : files) {
            out.add(estimateOne(file.filename(), file.sizeBytes(), null, baseline));
        }
        return out;
    }

    /** 고른 파일 하나. 이 단계에서 서버가 아는 전부다. */
    public record StagedFile(String filename, Long sizeBytes) {
    }

    /**
     * 업로드·추출이 끝나 실제 분량을 알게 된 뒤의 보정.
     *
     * @param charCount 추출된 본문 글자 수. null이면 다시 바이트에서 짐작한다
     */
    public FileEstimate refine(String filename, Long sizeBytes, Integer charCount) {
        return estimateOne(filename, sizeBytes, charCount, baseline());
    }

    private FileEstimate estimateOne(String filename, Long sizeBytes, Integer charCount, Baseline baseline) {
        String ext = extensionOf(filename);
        if (ARCHIVE_EXTENSION.equals(ext)) {
            return new FileEstimate(filename, ext, sizeBytes, true, false, null, null, baseline.basis(),
                    "압축 파일 안을 열어 봐야 몇 개인지 알 수 있어요");
        }
        if (MaterialFileFormat.fromExtension(ext).isEmpty()) {
            return new FileEstimate(filename, ext, sizeBytes, false, false, null, null, baseline.basis(),
                    "분석할 수 없는 형식이에요");
        }
        long chars = charCount != null && charCount > 0 ? charCount : guessChars(ext, sizeBytes);
        if (chars <= 0) {
            return new FileEstimate(filename, ext, sizeBytes, true, false, null, null, baseline.basis(),
                    "분량을 짐작할 수 없어요");
        }
        int chunks = chunkCount(chars);
        int min = (int) Math.round(baseline.extractSeconds() + chunks * baseline.secondsPerChunkLow());
        int max = (int) Math.round(baseline.extractSeconds() + chunks * baseline.secondsPerChunkHigh());
        return new FileEstimate(filename, ext, sizeBytes, true, true, min, Math.max(min, max), baseline.basis(), null);
    }

    /**
     * 묶음 전체. 파일이 동시에 {@code concurrency}개씩 처리되므로 합을 그대로 쓰지 않는다.
     * 다만 가장 오래 걸리는 파일보다 짧아질 수는 없다.
     */
    public BatchEstimate estimateBatch(List<FileEstimate> files, int queuedJobsAhead) {
        int estimable = 0;
        int unestimable = 0;
        long sumMin = 0, sumMax = 0;
        int maxMin = 0, maxMax = 0;
        String basis = null;
        for (FileEstimate file : files) {
            if (!file.estimable() || file.minSeconds() == null) {
                if (file.supported()) {
                    unestimable++;
                }
                continue;
            }
            estimable++;
            sumMin += file.minSeconds();
            sumMax += file.maxSeconds();
            maxMin = Math.max(maxMin, file.minSeconds());
            maxMax = Math.max(maxMax, file.maxSeconds());
            basis = file.basis();
        }
        if (estimable == 0) {
            return new BatchEstimate(null, null, basis, 0, unestimable, 0, true);
        }
        int lanes = Math.max(1, concurrency);
        int min = (int) Math.max(maxMin, Math.ceil(sumMin / (double) lanes));
        int max = (int) Math.max(maxMax, Math.ceil(sumMax / (double) lanes));
        int ahead = queueAheadSeconds(queuedJobsAhead);
        return new BatchEstimate(min + ahead, max + ahead, basis, estimable, unestimable, ahead, true);
    }

    /** 앞선 대기열이 비워질 때까지의 몫. 작업 하나를 "청크 1개짜리"로 보수적으로 본다. */
    int queueAheadSeconds(int queuedJobsAhead) {
        if (queuedJobsAhead <= 0) {
            return 0;
        }
        Baseline baseline = baseline();
        int lanes = Math.max(1, concurrency);
        return (int) Math.ceil(queuedJobsAhead * baseline.secondsPerChunkLow() / lanes);
    }

    /** 일감 초를 동시 처리 수로 나눈다. 남은 시간을 셀 때 쓴다. */
    public int perLane(int seconds) {
        return (int) Math.ceil(seconds / (double) Math.max(1, concurrency));
    }

    int chunkCount(long chars) {
        return (int) Math.min(maxChunksPerJob, Math.max(1, Math.ceil(chars / (double) chunkChars)));
    }

    long guessChars(String extension, Long sizeBytes) {
        if (sizeBytes == null || sizeBytes <= 0) {
            return 0;
        }
        Double measured = timingMapper.medianCharsPerByte(extension, MIN_SAMPLES_PARTIAL);
        double ratio = measured != null && measured > 0 ? measured
                : DEFAULT_CHARS_PER_BYTE.getOrDefault(extension, FALLBACK_CHARS_PER_BYTE);
        return Math.round(sizeBytes * ratio);
    }

    /**
     * 지난 실행에서 뽑은 기준값. 표본이 모자라면 기본값 쪽으로 기운다 — 세 건을 보고 정밀한
     * 숫자를 말하지 않는다.
     */
    Baseline baseline() {
        List<Double> perChunk = new ArrayList<>(timingMapper.recentSecondsPerChunk(SAMPLE_WINDOW));
        perChunk.removeIf(v -> v == null || v <= 0);
        int samples = perChunk.size();
        if (samples < MIN_SAMPLES_PARTIAL) {
            return new Baseline(DEFAULT_SECONDS_PER_CHUNK_LOW, DEFAULT_SECONDS_PER_CHUNK_HIGH,
                    DEFAULT_EXTRACT_SECONDS, samples, "DEFAULT");
        }
        perChunk.sort(Double::compareTo);
        double low = percentile(perChunk, 0.5);
        double high = Math.max(low, percentile(perChunk, 0.8));
        List<Double> extracts = new ArrayList<>(timingMapper.recentExtractSeconds(SAMPLE_WINDOW));
        extracts.removeIf(java.util.Objects::isNull);
        extracts.sort(Double::compareTo);
        int extract = extracts.isEmpty()
                ? DEFAULT_EXTRACT_SECONDS : (int) Math.ceil(percentile(extracts, 0.8));
        if (samples < MIN_SAMPLES_FULL) {
            // 표본과 기본값을 반씩. 한쪽으로 튀지 않게 하면서 "아직 초기"라는 사실도 남긴다.
            low = (low + DEFAULT_SECONDS_PER_CHUNK_LOW) / 2;
            high = (high + DEFAULT_SECONDS_PER_CHUNK_HIGH) / 2;
            return new Baseline(low, high, Math.max(extract, 1), samples, "PARTIAL_HISTORY");
        }
        return new Baseline(low, high, Math.max(extract, 1), samples, "HISTORY");
    }

    /** 오름차순으로 정렬된 표본에서 백분위 하나. 표본이 비었으면 부르지 않는다. */
    static double percentile(List<Double> sorted, double q) {
        int index = (int) Math.min(sorted.size() - 1L, Math.max(0L, Math.round(q * (sorted.size() - 1))));
        return sorted.get(index);
    }

    static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 || dot == filename.length() - 1 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
