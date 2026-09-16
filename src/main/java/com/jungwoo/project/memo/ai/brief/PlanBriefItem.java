package com.jungwoo.project.memo.ai.brief;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 상담에서 오간 계획 관련 합의 한 줄. {@code ai_plan_briefs.items}(JSON 배열)의 원소다.
 *
 * <p>발화자·출처·동의 상태를 잃지 않는 것이 이 구조의 이유다. 사용자가 직접 말한 것(speaker=USER)은 말한 순간
 * 유효하고, AI가 제안한 것(speaker=ASSISTANT)은 사용자가 "좋아", "그대로"로 받아들이기 전까지 <b>후보</b>다.
 * 원문이 구조화됐다는 이유만으로 확정 사실이 되지 않는다.
 *
 * @param id                  이 합의 안에서만 유효한 번호(프롬프트의 #n)
 * @param kind                GOAL / PRIORITY / EXCLUDE / TIME_CONSTRAINT / SCOPE / DIFFICULTY / CAUSE / OTHER
 * @param text                사람이 읽는 한 문장
 * @param speaker             USER / ASSISTANT
 * @param accepted            사용자가 유효하다고 한 상태(USER 발화는 처음부터 true)
 * @param rejected            사용자가 거절한 AI 제안
 * @param removed             더 이상 유효하지 않음(사용자가 지웠거나 새 문장으로 대체됨)
 * @param scope               THIS_DRAFT(이번 초안만) / PERIOD(이번 기간) — 지속 선호는 여기 두지 않고 user_contexts로 간다
 * @param sourceMessageId     이 문장을 만든 메시지(USER면 사용자 메시지, ASSISTANT면 그 답변)
 * @param acceptedByMessageId 받아들인 사용자 메시지
 * @param supersedes          이 문장이 대체한 이전 항목 id
 * @param topicId             관련 학습 항목(어려움·원인 등). 없으면 null
 * @param courseId            관련 프로젝트
 * @param executionItemId     관련 실행 항목
 * @param revision            같은 항목을 고친 횟수
 * @param history             이전 문장들(최신 수정판이 우선하되 원문을 잃지 않는다)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanBriefItem(
        int id,
        String kind,
        String text,
        String speaker,
        boolean accepted,
        boolean rejected,
        boolean removed,
        String scope,
        Long sourceMessageId,
        Long acceptedByMessageId,
        Integer supersedes,
        Long topicId,
        Long courseId,
        Long executionItemId,
        int revision,
        List<String> history,
        LocalDateTime updatedAt
) {
    public static final String SPEAKER_USER = "USER";
    public static final String SPEAKER_ASSISTANT = "ASSISTANT";
    public static final String SCOPE_THIS_DRAFT = "THIS_DRAFT";
    public static final String SCOPE_PERIOD = "PERIOD";

    /** 지금 계획에 유효한 합의인가. */
    public boolean effective() {
        return accepted && !rejected && !removed;
    }

    /** 아직 사용자가 답하지 않은 AI 제안인가. */
    public boolean pendingProposal() {
        return SPEAKER_ASSISTANT.equals(speaker) && !accepted && !rejected && !removed;
    }

    public PlanBriefItem withAccepted(Long byMessageId, LocalDateTime at) {
        return new PlanBriefItem(id, kind, text, speaker, true, false, removed, scope, sourceMessageId, byMessageId,
                supersedes, topicId, courseId, executionItemId, revision, history, at);
    }

    public PlanBriefItem withRejected(LocalDateTime at) {
        return new PlanBriefItem(id, kind, text, speaker, false, true, removed, scope, sourceMessageId, acceptedByMessageId,
                supersedes, topicId, courseId, executionItemId, revision, history, at);
    }

    public PlanBriefItem withRemoved(LocalDateTime at) {
        return new PlanBriefItem(id, kind, text, speaker, accepted, rejected, true, scope, sourceMessageId,
                acceptedByMessageId, supersedes, topicId, courseId, executionItemId, revision, history, at);
    }

    /** 사용자가 문장을 고쳤다. 최신 문장이 우선하고 이전 문장은 history에 남는다. */
    public PlanBriefItem revised(String newText, String newScope, Long byMessageId, LocalDateTime at) {
        List<String> next = new java.util.ArrayList<>(history == null ? List.of() : history);
        next.add(text);
        return new PlanBriefItem(id, kind, newText, SPEAKER_USER, true, false, removed,
                newScope != null ? newScope : scope, sourceMessageId, byMessageId, supersedes, topicId, courseId,
                executionItemId, revision + 1, next, at);
    }
}
