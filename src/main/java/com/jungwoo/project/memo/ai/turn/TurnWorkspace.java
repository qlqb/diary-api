package com.jungwoo.project.memo.ai.turn;

import com.jungwoo.project.memo.ai.brief.StudyTimeCondition;
import com.jungwoo.project.memo.ai.brief.StudyTimeInterpreter;
import com.jungwoo.project.memo.ai.change.ChangeBundleService;
import com.jungwoo.project.memo.ai.change.ChangeOp;
import com.jungwoo.project.memo.scheduling.scenario.Scenario;
import com.jungwoo.project.memo.scheduling.scenario.ScenarioProjection;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 한 상담 턴의 도구 단계 작업 공간. 모델이 제안한 변경과 확인한 시간 조건을 여기 모은다 — DB에 쓰지 않는다. 턴이 성공으로 끝날 때만
 * 묶음·합의로 저장되고, 실패·취소·시간 초과면 그대로 버려진다(원본도 묶음도 남지 않는다).
 */
public final class TurnWorkspace {

    private final Long userId;
    private final Long conversationId;
    private final Long requestMessageId;
    private final LocalDate today;
    private final LocalDateTime now;
    private final List<StudyTimeInterpreter.Utterance> utterances;
    private final List<Staged> staged = new ArrayList<>();
    private StudyTimeCondition condition;
    private final List<String> limitNotes = new ArrayList<>();
    private final List<String> checked = new ArrayList<>();
    /** 시도한 모델 호출 수. 도구 단계가 시간 초과로 끊겨도 답변 단계가 이 값으로 남은 호출을 센다(다른 스레드가 읽는다). */
    private volatile int modelCalls;
    /** 이전 턴에서 제안돼 아직 적용 전인 변경(대상 키 → 요약). 같은 변경을 다시 제안하지 않는다. */
    private final java.util.Map<String, String> pending = new java.util.LinkedHashMap<>();
    private int toolCalls;
    /** 제안 번호. 철회해도 다시 쓰지 않는다(번호가 겹치면 철회가 다른 제안까지 지운다). */
    private int nextNo = 1;
    /** 도구 결과 원문(잘라서). 답변 단계가 제목·시각·원인을 그대로 근거로 쓴다. */
    private final List<String> details = new ArrayList<>();
    private int detailChars;
    static final int MAX_DETAIL_CHARS = 6000;

    /**
     * @param no       모델과 화면이 쓰는 번호(1부터)
     * @param linkedTo 관련 변경으로 함께 나온 경우 그 원래 항목 번호
     */
    public record Staged(int no, ChangeOp op, String code, String message, boolean selected, String origin,
                         Integer linkedTo, String linkReason) {
    }

    public TurnWorkspace(Long userId, Long conversationId, Long requestMessageId, LocalDate today, LocalDateTime now,
                         List<StudyTimeInterpreter.Utterance> utterances) {
        this.userId = userId;
        this.conversationId = conversationId;
        this.requestMessageId = requestMessageId;
        this.today = today;
        this.now = now;
        this.utterances = utterances == null ? List.of() : List.copyOf(utterances);
    }

    public Long userId() {
        return userId;
    }

    public Long conversationId() {
        return conversationId;
    }

    public Long requestMessageId() {
        return requestMessageId;
    }

    public LocalDate today() {
        return today;
    }

    public LocalDateTime now() {
        return now;
    }

    public List<StudyTimeInterpreter.Utterance> utterances() {
        return utterances;
    }

    public List<Staged> staged() {
        return List.copyOf(staged);
    }

    public boolean hasTarget(String targetKey) {
        return staged.stream().anyMatch(s -> s.op().targetKey().equals(targetKey));
    }

    public void pending(String targetKey, String summary) {
        pending.put(targetKey, summary);
    }

    public boolean isPending(String targetKey) {
        return pending.containsKey(targetKey);
    }

    public java.util.Collection<String> pendingSummaries() {
        return List.copyOf(pending.values());
    }

    public Staged stage(ChangeOp op, boolean selected, String origin, Integer linkedTo, String reason) {
        Staged s = new Staged(nextNo++, op, "OK", null, selected, origin, linkedTo, reason);
        staged.add(s);
        return s;
    }

    public boolean withdraw(int no) {
        return staged.removeIf(s -> s.no() == no || (s.linkedTo() != null && s.linkedTo() == no));
    }

    public StudyTimeCondition condition() {
        return condition;
    }

    public void condition(StudyTimeCondition c) {
        this.condition = c;
    }

    public void note(String limit) {
        limitNotes.add(limit);
    }

    public List<String> limitNotes() {
        return List.copyOf(limitNotes);
    }

    public void checked(String what) {
        if (checked.size() < 20) {
            checked.add(what);
        }
    }

    public List<String> checkedFacts() {
        return List.copyOf(checked);
    }

    /** 도구 결과를 답변 단계에 넘길 만큼(턴 전체 상한 안에서) 남긴다. 넘치면 잘렸다고 표시한다. */
    public void detail(String toolName, String result) {
        if (result == null) {
            return;
        }
        String line = toolName + " → " + (result.length() > 1500 ? result.substring(0, 1500) + "…(잘림)" : result);
        if (detailChars + line.length() > MAX_DETAIL_CHARS) {
            if (details.isEmpty() || !details.get(details.size() - 1).startsWith("(이후 도구 결과")) {
                details.add("(이후 도구 결과는 길이 상한으로 싣지 못했다)");
            }
            return;
        }
        details.add(line);
        detailChars += line.length();
    }

    public List<String> details() {
        return List.copyOf(details);
    }

    public int modelCalls() {
        return modelCalls;
    }

    public void countModelCall() {
        modelCalls++;
    }

    public int toolCalls() {
        return toolCalls;
    }

    public void countToolCall() {
        toolCalls++;
    }

    /** 선택된 제안을 겹친 가정 상태(시간 조건 제외). */
    public Scenario scenario(boolean withChanges) {
        if (!withChanges) {
            return Scenario.ORIGINAL;
        }
        List<ScenarioProjection> projections = new ArrayList<>();
        for (Staged s : staged) {
            if (s.selected() && s.op() instanceof ScenarioProjection p) {
                projections.add(p);
            }
        }
        return Scenario.of(projections, null);
    }

    /** 묶음 저장용: 번호를 목록 순서로 바꾼다. */
    public List<ChangeBundleService.Staged> toBundle() {
        List<ChangeBundleService.Staged> out = new ArrayList<>();
        for (Staged s : staged) {
            List<Integer> deps = new ArrayList<>();
            out.add(new ChangeBundleService.Staged(s.op(), deps, s.selected(), s.origin()));
        }
        return out;
    }

    public boolean isEmpty() {
        return staged.isEmpty() && condition == null;
    }
}
