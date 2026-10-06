package com.jungwoo.project.memo.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * 한 번의 초안 생성이 쓸 수 있는 모델 호출·본문 조회·입력 토큰·경과 시간의 상한과, 실제로 쓴 양.
 *
 * <p>한 생성 회차가 선택 → 펼친 선택 → 본문 조회 → 최종 계획 → 추가 읽기 뒤 계획 → 형식 복구를 <b>하나의 예산</b>으로
 * 소유한다. 기본은 자료 선택 1회 + 최종 계획 1회이고, 후보가 커 접혔으면 펼친 선택이 한 번 더, 최종 계획이 핵심 근거를 더
 * 달라고 하면 본문을 더 읽고 계획이 한 번 더 돈다.
 *
 * <p>(2026-09-19) 선택 단계가 호출을 다 써서 추가 읽기가 <b>구조적으로</b> 불가능해지던 문제를 고쳤다. 필수 호출(선택 1 +
 * 최종 계획 1)과 선택적 호출(펼친 선택, 추가 읽기 뒤 계획)을 구분한다:
 * <ul>
 *   <li>선택적 호출은 그 뒤에도 최종 계획 호출 하나가 남을 때만 허용한다({@link #canCallOptional}).</li>
 *   <li>횟수만이 아니라 입력 토큰 합과 경과 시간도 함께 본다. 선택적 호출은 시작 기한({@code optionalDeadlineMs})이
 *       지났거나 남은 토큰이 다음 호출 추정치보다 작으면 하지 않는다. 필수 호출은 전체 기한 안이면 한다.</li>
 *   <li>상한에 닿으면 무한 반복하지 않고 읽은 범위에서 만든 초안과 한계를 돌려준다.</li>
 * </ul>
 *
 * <p>상한은 로그만 남기는 규칙이 아니라 실제 실행 제한이다 — 넘는 호출은 아예 일어나지 않는다.
 */
public final class GenerationBudget {

    public enum Call { SELECTION, SELECTION_EXPAND, PLAN, PLAN_MORE_EVIDENCE, PLAN_RECOVERY }

    /** 호출 하나의 계측. 원문은 남기지 않는다. */
    public record CallRecord(Call kind, int round, Integer inputTokens, Integer outputTokens, long latencyMs,
                             boolean success, String note) {
    }

    /**
     * @param maxInputTokens     한 회차의 입력 토큰 합 상한(0 이하면 보지 않는다)
     * @param maxElapsedMs       한 회차의 전체 경과 시간 상한(0 이하면 보지 않는다)
     * @param optionalDeadlineMs 선택적 호출을 새로 시작할 수 있는 마지막 시점(0 이하면 보지 않는다)
     */
    public record Limits(int maxNormalCalls, int maxRecoveryCalls, int maxTotalCalls, int maxRetrievalRounds,
                         int maxInputTokens, long maxElapsedMs, long optionalDeadlineMs) {
    }

    private final int maxNormalCalls;
    private final int maxRecoveryCalls;
    private final int maxTotalCalls;
    private final int maxRetrievalRounds;
    private final int maxInputTokens;
    private final long maxElapsedMs;
    private final long optionalDeadlineMs;
    private final LongSupplier clock;

    private int normalCalls;
    private int recoveryCalls;
    private int retrievalRounds;
    private final List<CallRecord> records = new ArrayList<>();
    private final List<String> refusals = new ArrayList<>();
    private final long startedAt;

    public GenerationBudget(int maxNormalCalls, int maxRecoveryCalls, int maxTotalCalls, int maxRetrievalRounds) {
        this(new Limits(maxNormalCalls, maxRecoveryCalls, maxTotalCalls, maxRetrievalRounds, 0, 0, 0),
                System::currentTimeMillis);
    }

    public GenerationBudget(Limits limits, LongSupplier clock) {
        this.maxNormalCalls = Math.max(1, limits.maxNormalCalls());
        this.maxRecoveryCalls = Math.max(0, limits.maxRecoveryCalls());
        this.maxTotalCalls = Math.max(this.maxNormalCalls, limits.maxTotalCalls());
        this.maxRetrievalRounds = Math.max(1, limits.maxRetrievalRounds());
        this.maxInputTokens = limits.maxInputTokens();
        this.maxElapsedMs = limits.maxElapsedMs();
        this.optionalDeadlineMs = limits.optionalDeadlineMs();
        this.clock = clock;
        this.startedAt = clock.getAsLong();
    }

    public static GenerationBudget defaults() {
        return new GenerationBudget(4, 1, 5, 2);
    }

    /** 정상 호출을 하나 더 할 수 있는가(전체 상한·전체 기한도 함께 본다). 필수 호출용. */
    public boolean canCallNormal() {
        return normalCalls < maxNormalCalls && normalCalls + recoveryCalls < maxTotalCalls && withinElapsed();
    }

    /**
     * 선택적 호출(펼친 선택, 추가 읽기 뒤 계획)을 해도 되는가.
     *
     * @param reserveNormalAfter 이 호출 뒤에 반드시 남겨 둘 필수 호출 수(펼친 선택이면 최종 계획 1, 추가 읽기 뒤 계획이면 0)
     * @param estimatedTokens    이 호출의 입력 토큰 추정(모르면 0)
     * @param reserveTokens      남겨 둘 필수 호출의 입력 토큰 추정(모르면 0)
     * @return 안 되면 이유를 {@link #refusals()}에 남기고 false
     */
    public boolean canCallOptional(String what, int reserveNormalAfter, int estimatedTokens, int reserveTokens) {
        if (normalCalls + 1 + reserveNormalAfter > maxNormalCalls
                || normalCalls + recoveryCalls + 1 + reserveNormalAfter > maxTotalCalls) {
            refusals.add(what + ": 호출 횟수 상한(" + maxNormalCalls + "회)");
            return false;
        }
        if (maxInputTokens > 0 && inputTokens() + estimatedTokens + reserveTokens > maxInputTokens) {
            refusals.add(what + ": 입력 토큰 상한(" + maxInputTokens + ", 사용 " + inputTokens() + ")");
            return false;
        }
        if (optionalDeadlineMs > 0 && elapsedMs() > optionalDeadlineMs) {
            refusals.add(what + ": 경과 시간(" + elapsedMs() / 1000 + "초, 선택적 호출 기한 " + optionalDeadlineMs / 1000 + "초)");
            return false;
        }
        return withinElapsed();
    }

    public boolean canRecover() {
        return recoveryCalls < maxRecoveryCalls && normalCalls + recoveryCalls < maxTotalCalls && withinElapsed();
    }

    private boolean withinElapsed() {
        return maxElapsedMs <= 0 || elapsedMs() <= maxElapsedMs;
    }

    /** 본문 조회 라운드를 하나 더 열 수 있는가. 첫 조회가 1라운드다. */
    public boolean canRetrieveAgain() {
        return retrievalRounds < maxRetrievalRounds;
    }

    public void retrievalRound() {
        retrievalRounds++;
    }

    public void record(Call kind, int round, Integer inputTokens, Integer outputTokens, long latencyMs, boolean success,
                       String note) {
        if (kind == Call.PLAN_RECOVERY) {
            recoveryCalls++;
        } else {
            normalCalls++;
        }
        records.add(new CallRecord(kind, round, inputTokens, outputTokens, latencyMs, success, note));
    }

    public int normalCalls() {
        return normalCalls;
    }

    public int recoveryCalls() {
        return recoveryCalls;
    }

    public int totalCalls() {
        return normalCalls + recoveryCalls;
    }

    public int retrievalRounds() {
        return retrievalRounds;
    }

    public int maxNormalCalls() {
        return maxNormalCalls;
    }

    public int maxTotalCalls() {
        return maxTotalCalls;
    }

    public int maxRetrievalRounds() {
        return maxRetrievalRounds;
    }

    public List<CallRecord> records() {
        return List.copyOf(records);
    }

    /** 선택적 호출을 하지 않은 이유(사용자·근거 기록에 그대로 남긴다). */
    public List<String> refusals() {
        return List.copyOf(refusals);
    }

    public int inputTokens() {
        return records.stream().mapToInt(r -> r.inputTokens() == null ? 0 : r.inputTokens()).sum();
    }

    public int outputTokens() {
        return records.stream().mapToInt(r -> r.outputTokens() == null ? 0 : r.outputTokens()).sum();
    }

    public long elapsedMs() {
        return clock.getAsLong() - startedAt;
    }

    /** 응답·로그용 요약. */
    public Summary summary() {
        return new Summary(normalCalls, recoveryCalls, maxNormalCalls, maxTotalCalls, retrievalRounds, maxRetrievalRounds,
                inputTokens(), outputTokens(), elapsedMs(), records(), maxInputTokens, maxElapsedMs, refusals());
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record Summary(int normalCalls, int recoveryCalls, int maxNormalCalls, int maxTotalCalls, int retrievalRounds,
                          int maxRetrievalRounds, int inputTokens, int outputTokens, long elapsedMs,
                          List<CallRecord> calls, int maxInputTokens, long maxElapsedMs, List<String> refusals) {
    }
}
