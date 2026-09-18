package com.jungwoo.project.memo.plan;

import java.util.ArrayList;
import java.util.List;

/**
 * 한 번의 초안 생성이 쓸 수 있는 모델 호출·본문 조회 상한과, 실제로 쓴 양.
 *
 * <p>기본은 자료 선택 1회 + 최종 생성 1회다. 고정 두 번이 제품 요구사항은 아니다 — 후보가 커 접혔으면 펼친 선택이
 * 한 번 더, 최종 생성이 핵심 근거를 더 달라고 하면 본문을 더 읽고 최종 생성이 한 번 더 돈다. 어느 쪽이든
 * 정상 호출은 {@code maxNormalCalls}(기본 3)까지고, 형식·검증 오류의 복구 호출은 {@code maxRecoveryCalls}(기본 1)까지,
 * 전체 {@code maxTotalCalls}(기본 4)를 넘지 않는다. 상한에 닿으면 무한 반복하지 않고 읽은 범위에서 만든 초안과
 * 한계를 돌려준다.
 *
 * <p>상한은 로그만 남기는 규칙이 아니라 실제 실행 제한이다 — 넘는 호출은 아예 일어나지 않는다.
 */
public final class GenerationBudget {

    public enum Call { SELECTION, SELECTION_EXPAND, PLAN, PLAN_MORE_EVIDENCE, PLAN_RECOVERY }

    /** 호출 하나의 계측. 원문은 남기지 않는다. */
    public record CallRecord(Call kind, int round, Integer inputTokens, Integer outputTokens, long latencyMs,
                             boolean success, String note) {
    }

    private final int maxNormalCalls;
    private final int maxRecoveryCalls;
    private final int maxTotalCalls;
    private final int maxRetrievalRounds;

    private int normalCalls;
    private int recoveryCalls;
    private int retrievalRounds;
    private final List<CallRecord> records = new ArrayList<>();
    private final long startedAt = System.currentTimeMillis();

    public GenerationBudget(int maxNormalCalls, int maxRecoveryCalls, int maxTotalCalls, int maxRetrievalRounds) {
        this.maxNormalCalls = Math.max(1, maxNormalCalls);
        this.maxRecoveryCalls = Math.max(0, maxRecoveryCalls);
        this.maxTotalCalls = Math.max(this.maxNormalCalls, maxTotalCalls);
        this.maxRetrievalRounds = Math.max(1, maxRetrievalRounds);
    }

    public static GenerationBudget defaults() {
        return new GenerationBudget(3, 1, 4, 2);
    }

    /** 정상 호출을 하나 더 할 수 있는가(전체 상한도 함께 본다). */
    public boolean canCallNormal() {
        return normalCalls < maxNormalCalls && normalCalls + recoveryCalls < maxTotalCalls;
    }

    public boolean canRecover() {
        return recoveryCalls < maxRecoveryCalls && normalCalls + recoveryCalls < maxTotalCalls;
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

    public int inputTokens() {
        return records.stream().mapToInt(r -> r.inputTokens() == null ? 0 : r.inputTokens()).sum();
    }

    public int outputTokens() {
        return records.stream().mapToInt(r -> r.outputTokens() == null ? 0 : r.outputTokens()).sum();
    }

    public long elapsedMs() {
        return System.currentTimeMillis() - startedAt;
    }

    /** 응답·로그용 요약. */
    public Summary summary() {
        return new Summary(normalCalls, recoveryCalls, maxNormalCalls, maxTotalCalls, retrievalRounds, maxRetrievalRounds,
                inputTokens(), outputTokens(), elapsedMs(), records());
    }

    public record Summary(int normalCalls, int recoveryCalls, int maxNormalCalls, int maxTotalCalls, int retrievalRounds,
                          int maxRetrievalRounds, int inputTokens, int outputTokens, long elapsedMs,
                          List<CallRecord> calls) {
    }
}
