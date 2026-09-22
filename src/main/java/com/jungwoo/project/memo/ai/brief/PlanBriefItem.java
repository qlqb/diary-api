package com.jungwoo.project.memo.ai.brief;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 상담에서 오간 계획 관련 합의 한 줄. {@code ai_plan_briefs.items}(JSON 배열)의 원소다.
 *
 * <p>발화자·출처·동의 상태를 잃지 않는 것이 이 구조의 이유다. 사용자가 직접 말한 것(speaker=USER)은 말한 순간
 * 유효하고, AI가 제안한 것(speaker=ASSISTANT)은 사용자가 "좋아", "그대로"로 받아들이기 전까지 <b>후보</b>다.
 * 원문이 구조화됐다는 이유만으로 확정 사실이 되지 않는다.
 *
 * <p>합의는 적용 범위를 갖는다(2026-09-17 후속). THIS_DRAFT는 초안 흐름(처음 만든 초안 id = flowProposalId, 다시 만들기는
 * 같은 흐름)에서만, PERIOD는 실제 날짜(periodStart~periodEnd)와 요청 기간이 겹칠 때만 유효하다. 날짜를 복원할 수 없는 옛
 * 항목은 "범위 미확인"으로 남고 현재 합의처럼 전달되지 않는다.
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
 * @param updatedAt           마지막 변경 시각
 * @param periodStart         PERIOD 합의가 적용되는 실제 시작 날짜. 모르면 null(범위 미확인)
 * @param periodEnd           PERIOD 합의가 적용되는 실제 종료 날짜
 * @param saidOn              발언 날짜(사용자 시간대). "이번 주"의 해석 기준
 * @param flowProposalId      THIS_DRAFT 합의가 묶인 초안 흐름(처음 만든 초안 id). 아직 초안을 만들지 않았으면 null
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
        LocalDateTime updatedAt,
        LocalDate periodStart,
        LocalDate periodEnd,
        LocalDate saidOn,
        Long flowProposalId
) {
    public static final String SPEAKER_USER = "USER";
    public static final String SPEAKER_ASSISTANT = "ASSISTANT";
    public static final String SCOPE_THIS_DRAFT = "THIS_DRAFT";
    public static final String SCOPE_PERIOD = "PERIOD";

    /** 범위 정보 없이 만드는 경로(옛 호출부·테스트). 범위는 markProposal이 초안을 만들 때 묶는다. */
    public PlanBriefItem(int id, String kind, String text, String speaker, boolean accepted, boolean rejected,
                         boolean removed, String scope, Long sourceMessageId, Long acceptedByMessageId, Integer supersedes,
                         Long topicId, Long courseId, Long executionItemId, int revision, List<String> history,
                         LocalDateTime updatedAt) {
        this(id, kind, text, speaker, accepted, rejected, removed, scope, sourceMessageId, acceptedByMessageId, supersedes,
                topicId, courseId, executionItemId, revision, history, updatedAt, null, null, null, null);
    }

    /** 사용자가 받아들였고 거절·삭제되지 않았는가(범위는 보지 않는다 — 범위는 {@link PlanBriefService.View#effectiveFor}). */
    public boolean effective() {
        return accepted && !rejected && !removed;
    }

    /** 아직 사용자가 답하지 않은 AI 제안인가. */
    public boolean pendingProposal() {
        return SPEAKER_ASSISTANT.equals(speaker) && !accepted && !rejected && !removed;
    }

    public boolean isPeriod() {
        return SCOPE_PERIOD.equals(scope);
    }

    /** PERIOD 합의인데 날짜를 모른다(옛 레코드). 현재 합의처럼 전달하지 않는다. */
    public boolean periodUnknown() {
        return isPeriod() && (periodStart == null || periodEnd == null);
    }

    /** PERIOD 합의가 [from, to]와 겹치는가. 날짜를 모르면 false. */
    public boolean overlaps(LocalDate from, LocalDate to) {
        if (periodStart == null || periodEnd == null || from == null || to == null) {
            return false;
        }
        return !periodEnd.isBefore(from) && !periodStart.isAfter(to);
    }

    /** PERIOD 합의가 [from, to]를 전부 덮는가. */
    public boolean covers(LocalDate from, LocalDate to) {
        return periodStart != null && periodEnd != null && from != null && to != null
                && !periodStart.isAfter(from) && !periodEnd.isBefore(to);
    }

    public boolean expiredBy(LocalDate today) {
        return periodEnd != null && today != null && periodEnd.isBefore(today);
    }

    public PlanBriefItem withAccepted(Long byMessageId, LocalDateTime at) {
        return new PlanBriefItem(id, kind, text, speaker, true, false, removed, scope, sourceMessageId, byMessageId,
                supersedes, topicId, courseId, executionItemId, revision, history, at, periodStart, periodEnd, saidOn,
                flowProposalId);
    }

    public PlanBriefItem withRejected(LocalDateTime at) {
        return new PlanBriefItem(id, kind, text, speaker, false, true, removed, scope, sourceMessageId, acceptedByMessageId,
                supersedes, topicId, courseId, executionItemId, revision, history, at, periodStart, periodEnd, saidOn,
                flowProposalId);
    }

    public PlanBriefItem withRemoved(LocalDateTime at) {
        return new PlanBriefItem(id, kind, text, speaker, accepted, rejected, true, scope, sourceMessageId,
                acceptedByMessageId, supersedes, topicId, courseId, executionItemId, revision, history, at, periodStart,
                periodEnd, saidOn, flowProposalId);
    }

    /** 초안 흐름·기간을 묶는다(markProposal). 이미 있는 값은 바꾸지 않는다 — 처음 묶인 범위가 그 합의의 범위다. */
    public PlanBriefItem bound(Long flow, LocalDate start, LocalDate end, LocalDateTime at) {
        Long nextFlow = flowProposalId != null ? flowProposalId : (SCOPE_THIS_DRAFT.equals(scope) ? flow : null);
        LocalDate nextStart = periodStart != null ? periodStart : (isPeriod() ? start : null);
        LocalDate nextEnd = periodEnd != null ? periodEnd : (isPeriod() ? end : null);
        return new PlanBriefItem(id, kind, text, speaker, accepted, rejected, removed, scope, sourceMessageId,
                acceptedByMessageId, supersedes, topicId, courseId, executionItemId, revision, history, at, nextStart,
                nextEnd, saidOn, nextFlow);
    }

    /** 사용자가 문장을 고쳤다. 최신 문장이 우선하고 이전 문장은 history에 남는다. 기간을 함께 말했으면 그 날짜로 바뀐다. */
    public PlanBriefItem revised(String newText, String newScope, LocalDate newStart, LocalDate newEnd, Long byMessageId,
                                 LocalDateTime at) {
        List<String> next = new java.util.ArrayList<>(history == null ? List.of() : history);
        next.add(text);
        String scopeNext = newScope != null ? newScope : scope;
        boolean datesGiven = newStart != null && newEnd != null;
        return new PlanBriefItem(id, kind, newText, SPEAKER_USER, true, false, removed, scopeNext, sourceMessageId,
                byMessageId, supersedes, topicId, courseId, executionItemId, revision + 1, next, at,
                datesGiven ? newStart : periodStart, datesGiven ? newEnd : periodEnd, saidOn, flowProposalId);
    }

    public PlanBriefItem revised(String newText, String newScope, Long byMessageId, LocalDateTime at) {
        return revised(newText, newScope, null, null, byMessageId, at);
    }
}
