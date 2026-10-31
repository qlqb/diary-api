package com.jungwoo.project.memo.plan;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 진행 중인 초안 생성의 상태. 요청 키(requestKey)로 찾는다.
 *
 * <p>두 가지에 쓴다. (1) 화면이 "자료 확인 중 / 계획 정리 중"을 서버가 실제로 밟은 단계로 보여 준다 — 모델이 완료를
 * 선언하는 것이 아니라 서버가 단계를 옮길 때 기록한다. (2) 같은 요청 키로 다시 눌러도 생성이 두 번 돌지 않는다.
 *
 * <p>프로세스 안 메모리다. 재시작하면 사라지지만 그때는 진행 중이던 생성도 함께 사라지므로 잘못된 상태가 남지 않는다.
 * 늦게 끝난 요청의 결과는 requestKey → proposalId로 잠시 남겨 화면이 다시 찾을 수 있게 한다.
 */
@Component
public class PlanGenerationProgress {

    public enum Stage {
        /** 요청·일정·자료 목록·합의·기록을 모으는 중 */
        COLLECTING,
        /** 자료 선택 호출 */
        SELECTING,
        /** 고른 구간의 원문을 읽는 중 */
        RETRIEVING,
        /** 최종 계획 호출 */
        PLANNING,
        /** 최종 판단이 요청한 근거를 더 읽는 중 */
        READING_MORE,
        /** 검증·저장 */
        SAVING,
        DONE,
        FAILED
    }

    public record State(String requestKey, Long userId, Stage stage, Instant startedAt, Instant updatedAt,
                        Long proposalId, String errorCode) {
        State with(Stage next, Long proposalId, String errorCode) {
            return new State(requestKey, userId, next, startedAt, Instant.now(), proposalId, errorCode);
        }

        public String stageLabel() {
            return label(stage);
        }
    }

    /** 사용자에게 보이는 단계 문구. 서버가 실제로 밟은 단계만 말한다. */
    public static String label(Stage stage) {
        return switch (stage) {
            case COLLECTING -> "일정·자료·기록을 모으는 중";
            case SELECTING -> "자료 확인 중";
            case RETRIEVING -> "고른 자료의 원문을 읽는 중";
            case PLANNING -> "계획 정리 중";
            case READING_MORE -> "근거를 더 읽는 중";
            case SAVING -> "초안을 저장하는 중";
            case DONE -> "완료";
            case FAILED -> "만들지 못했어요";
        };
    }

    private static final Duration KEEP_FINISHED = Duration.ofMinutes(10);
    private static final Duration STALE_RUNNING = Duration.ofMinutes(10);

    private final Map<String, State> states = new ConcurrentHashMap<>();

    /**
     * 진행을 시작한다. 같은 키가 이미 진행 중이면 false — 호출자는 중복 요청으로 처리한다.
     */
    public boolean start(String requestKey, Long userId) {
        if (requestKey == null || requestKey.isBlank()) {
            return true;
        }
        sweep();
        Instant now = Instant.now();
        State fresh = new State(requestKey, userId, Stage.COLLECTING, now, now, null, null);
        State previous = states.putIfAbsent(requestKey, fresh);
        if (previous == null) {
            return true;
        }
        if (!previous.userId().equals(userId)) {
            return false;
        }
        // 끝난 요청의 키를 다시 쓰면 새로 시작하지 않는다 — 결과가 이미 있다.
        return false;
    }

    public void stage(String requestKey, Stage stage) {
        if (requestKey == null) {
            return;
        }
        states.computeIfPresent(requestKey, (k, s) -> s.with(stage, s.proposalId(), s.errorCode()));
    }

    public void done(String requestKey, Long proposalId) {
        if (requestKey == null) {
            return;
        }
        states.computeIfPresent(requestKey, (k, s) -> s.with(Stage.DONE, proposalId, null));
    }

    public void failed(String requestKey, String errorCode) {
        if (requestKey == null) {
            return;
        }
        states.computeIfPresent(requestKey, (k, s) -> s.with(Stage.FAILED, null, errorCode));
    }

    public Optional<State> find(String requestKey, Long userId) {
        if (requestKey == null) {
            return Optional.empty();
        }
        State state = states.get(requestKey);
        return state == null || !state.userId().equals(userId) ? Optional.empty() : Optional.of(state);
    }

    /** 사용자에게 진행 중인 생성이 있는가(다른 키). 중복 생성 방지용. */
    public boolean hasRunning(Long userId, String exceptKey) {
        sweep();
        return states.values().stream().anyMatch(s -> s.userId().equals(userId)
                && !s.requestKey().equals(exceptKey)
                && s.stage() != Stage.DONE && s.stage() != Stage.FAILED);
    }

    /** 끝난 항목은 잠시 뒤에, 오래 멈춘 항목은 실패로 정리한다. */
    void sweep() {
        Instant now = Instant.now();
        states.entrySet().removeIf(e -> {
            State s = e.getValue();
            boolean finished = s.stage() == Stage.DONE || s.stage() == Stage.FAILED;
            return finished && Duration.between(s.updatedAt(), now).compareTo(KEEP_FINISHED) > 0;
        });
        states.replaceAll((k, s) -> {
            boolean finished = s.stage() == Stage.DONE || s.stage() == Stage.FAILED;
            if (!finished && Duration.between(s.updatedAt(), now).compareTo(STALE_RUNNING) > 0) {
                return s.with(Stage.FAILED, null, "STALE");
            }
            return s;
        });
    }
}
