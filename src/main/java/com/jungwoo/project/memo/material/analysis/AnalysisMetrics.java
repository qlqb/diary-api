package com.jungwoo.project.memo.material.analysis;

/**
 * 한 번의 분석이 어디에 시간을 썼는지. 예상 시간을 말할 근거를 모으는 그릇이다.
 *
 * <p>분석기가 채우고 실행기가 저장한다. 원문·프롬프트·파일명은 담지 않는다 — 다음 추정에
 * 필요한 것은 분량과 걸린 시간뿐이다.
 *
 * <p>스레드 하나가 작업 하나를 끝까지 처리하므로 동기화하지 않는다.
 */
public class AnalysisMetrics {

    private String extension;
    private Long sizeBytes;
    private Integer charCount;
    private Integer unitCount;
    private Integer chunkCount;
    private long extractMs;
    private long modelMs;
    private long persistMs;

    public void describeSource(String extension, Long sizeBytes, Integer charCount) {
        this.extension = extension;
        this.sizeBytes = sizeBytes;
        this.charCount = charCount;
    }

    public void describeWork(int unitCount, int chunkCount) {
        this.unitCount = unitCount;
        this.chunkCount = chunkCount;
    }

    public void addExtract(long ms) {
        extractMs += Math.max(0, ms);
    }

    public void addModel(long ms) {
        modelMs += Math.max(0, ms);
    }

    public void addPersist(long ms) {
        persistMs += Math.max(0, ms);
    }

    public String extension() {
        return extension;
    }

    public Long sizeBytes() {
        return sizeBytes;
    }

    public Integer charCount() {
        return charCount;
    }

    public Integer unitCount() {
        return unitCount;
    }

    /**
     * 이번 실행에서 실제로 모델을 부른 청크 수. 전체 청크 수가 아니라 이번에 부른 수여야
     * "청크 하나에 이만큼 걸린다"가 맞는다 — 앞선 시도가 남긴 checkpoint 덕에 건너뛴 청크는
     * 시간을 쓰지 않았다.
     */
    private int calledChunks;

    public void countCall() {
        calledChunks++;
    }

    public int calledChunks() {
        return calledChunks;
    }

    public Integer chunkCount() {
        return chunkCount;
    }

    public long extractMs() {
        return extractMs;
    }

    public long modelMs() {
        return modelMs;
    }

    public long persistMs() {
        return persistMs;
    }
}
