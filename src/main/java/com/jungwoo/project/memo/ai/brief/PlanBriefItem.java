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
 * @param quote               (2026-10-18) 근거가 된 사용자 원문 구절. 서버가 그 사용자 메시지에 실제로 있는지 확인한 것만 남는다
 * @param basisMessageId      quote가 들어 있는 사용자 메시지. 서버가 대화에서 직접 찾은 USER 메시지다(모델이 준 id가 아니다)
 * @param basisSentence       quote가 든 문장 전체(서버가 원문에서 잘라 냄). 짧은 구절만으로는 "빼지 마"가 "빼"로 읽힐 수 있다
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
        Long flowProposalId,
        String quote,
        Long basisMessageId,
        String basisSentence,
        /**
         * (2026-10-25) TIME_BUDGET의 해석된 시간 조건. 사용자 원문 근거(quote·basisSentence)와 문장(text)과 따로 둔다 — 화면 문구를
         * 문장에 꼬리표로 붙여 다시 읽던 방식은 꼬리표가 원문 대조를 망가뜨렸다. 옛 저장본은 null(꼬리표를 읽는다).
         */
        StudyTimeCondition timeCondition
) {
    /** 시간 조건 필드가 생기기 전 모양. */
    public PlanBriefItem(int id, String kind, String text, String speaker, boolean accepted, boolean rejected,
                         boolean removed, String scope, Long sourceMessageId, Long acceptedByMessageId, Integer supersedes,
                         Long topicId, Long courseId, Long executionItemId, int revision, List<String> history,
                         LocalDateTime updatedAt, LocalDate periodStart, LocalDate periodEnd, LocalDate saidOn,
                         Long flowProposalId, String quote, Long basisMessageId, String basisSentence) {
        this(id, kind, text, speaker, accepted, rejected, removed, scope, sourceMessageId, acceptedByMessageId, supersedes,
                topicId, courseId, executionItemId, revision, history, updatedAt, periodStart, periodEnd, saidOn,
                flowProposalId, quote, basisMessageId, basisSentence, null);
    }

    public PlanBriefItem withTimeCondition(StudyTimeCondition condition) {
        return new PlanBriefItem(id, kind, text, speaker, accepted, rejected, removed, scope, sourceMessageId,
                acceptedByMessageId, supersedes, topicId, courseId, executionItemId, revision, history, updatedAt,
                periodStart, periodEnd, saidOn, flowProposalId, quote, basisMessageId, basisSentence, condition);
    }

    public static final String SPEAKER_USER = "USER";
    public static final String SPEAKER_ASSISTANT = "ASSISTANT";
    public static final String SCOPE_THIS_DRAFT = "THIS_DRAFT";
    public static final String SCOPE_PERIOD = "PERIOD";

    /** 근거 필드가 생기기 전 모양(옛 저장본·호출부·테스트). 근거 없음으로 읽힌다. */
    public PlanBriefItem(int id, String kind, String text, String speaker, boolean accepted, boolean rejected,
                         boolean removed, String scope, Long sourceMessageId, Long acceptedByMessageId, Integer supersedes,
                         Long topicId, Long courseId, Long executionItemId, int revision, List<String> history,
                         LocalDateTime updatedAt, LocalDate periodStart, LocalDate periodEnd, LocalDate saidOn,
                         Long flowProposalId) {
        this(id, kind, text, speaker, accepted, rejected, removed, scope, sourceMessageId, acceptedByMessageId, supersedes, topicId, courseId, executionItemId, revision, history, updatedAt, periodStart, periodEnd, saidOn, flowProposalId, null, null, null);
    }

    /** 범위 정보 없이 만드는 경로(옛 호출부·테스트). 범위는 markProposal이 초안을 만들 때 묶는다. */
    public PlanBriefItem(int id, String kind, String text, String speaker, boolean accepted, boolean rejected,
                         boolean removed, String scope, Long sourceMessageId, Long acceptedByMessageId, Integer supersedes,
                         Long topicId, Long courseId, Long executionItemId, int revision, List<String> history,
                         LocalDateTime updatedAt) {
        this(id, kind, text, speaker, accepted, rejected, removed, scope, sourceMessageId, acceptedByMessageId, supersedes,
                topicId, courseId, executionItemId, revision, history, updatedAt, null, null, null, null);
    }

    /**
     * 사용자 발언에 근거가 확인됐는가. 승인과는 다르다 — AI가 사용자 말을 정리한 문장은 작성자가 AI여도 근거가 사용자 발언이다.
     */
    public boolean grounded() {
        return basisMessageId != null;
    }

    /**
     * 유효한가: 사용자가 받아들였거나 사용자 발언에 근거가 확인됐고, 거절·삭제되지 않았다(범위는 보지 않는다 — 범위는
     * {@link PlanBriefService.View#effectiveFor}). 근거만 있는 항목은 승인이 아니라 "사용자 요청을 정리한 것"으로 다룬다.
     */
    public boolean effective() {
        return (accepted || grounded()) && !rejected && !removed;
    }

    /** 아직 사용자가 답하지 않은 AI 제안인가(사용자 발언 근거가 없는 것만). */
    public boolean pendingProposal() {
        return SPEAKER_ASSISTANT.equals(speaker) && !accepted && !grounded() && !rejected && !removed;
    }

    /** 사용자 발언 근거를 붙인다. 거절·삭제 상태는 그대로다 — 근거가 붙었다고 되살아나지 않는다. */
    public PlanBriefItem withBasis(String newQuote, Long messageId, String sentence, LocalDateTime at) {
        return new PlanBriefItem(id, kind, text, speaker, accepted, rejected, removed, scope, sourceMessageId,
                acceptedByMessageId, supersedes, topicId, courseId, executionItemId, revision, history, at, periodStart,
                periodEnd, saidOn, flowProposalId, newQuote, messageId, sentence, timeCondition);
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
                flowProposalId, quote, basisMessageId, basisSentence, timeCondition);
    }

    public PlanBriefItem withRejected(LocalDateTime at) {
        return new PlanBriefItem(id, kind, text, speaker, false, true, removed, scope, sourceMessageId, acceptedByMessageId,
                supersedes, topicId, courseId, executionItemId, revision, history, at, periodStart, periodEnd, saidOn,
                flowProposalId, quote, basisMessageId, basisSentence, timeCondition);
    }

    public PlanBriefItem withRemoved(LocalDateTime at) {
        return new PlanBriefItem(id, kind, text, speaker, accepted, rejected, true, scope, sourceMessageId,
                acceptedByMessageId, supersedes, topicId, courseId, executionItemId, revision, history, at, periodStart,
                periodEnd, saidOn, flowProposalId, quote, basisMessageId, basisSentence, timeCondition);
    }

    /**
     * 초안 흐름·기간을 묶는다(markProposal). 이미 있는 값은 바꾸지 않는다 — 처음 묶인 범위가 그 합의의 범위다.
     * 수정 시각(updatedAt)은 그대로 둔다 — 범위를 묶는 것은 사용자가 그 합의를 고친 것이 아니다(가장 최근에 고친 합의를
     * 고르는 판단(예: 계획 목적)이 초안 저장으로 바뀌지 않게).
     */
    public PlanBriefItem bound(Long flow, LocalDate start, LocalDate end, LocalDateTime at) {
        Long nextFlow = flowProposalId != null ? flowProposalId : (SCOPE_THIS_DRAFT.equals(scope) ? flow : null);
        LocalDate nextStart = periodStart != null ? periodStart : (isPeriod() ? start : null);
        LocalDate nextEnd = periodEnd != null ? periodEnd : (isPeriod() ? end : null);
        if (java.util.Objects.equals(nextFlow, flowProposalId) && java.util.Objects.equals(nextStart, periodStart)
                && java.util.Objects.equals(nextEnd, periodEnd)) {
            return this;
        }
        return new PlanBriefItem(id, kind, text, speaker, accepted, rejected, removed, scope, sourceMessageId,
                acceptedByMessageId, supersedes, topicId, courseId, executionItemId, revision, history,
                updatedAt != null ? updatedAt : at, nextStart, nextEnd, saidOn, nextFlow, quote, basisMessageId,
                basisSentence, timeCondition);
    }

    /**
     * 문장을 고쳤다. 최신 문장이 우선하고 이전 문장은 history에 남는다. 기간을 함께 말했으면 그 날짜로 바뀐다.
     *
     * <p>(2026-10-18) 근거·승인은 이 수정판으로 다시 정한다 — 이전 판의 근거를 승계하지 않는다. 사용자가 고쳐 말한 문장을 그대로
     * 옮겼으면 사용자 수정판(승인), 사용자 발언 근거는 있지만 AI가 자기 말로 정리했으면 근거 있는 AI 정리(승인 아님), 근거가
     * 확인되지 않으면 AI가 고친 후보다(사용자가 다시 답해야 유효).
     *
     * @param basis 이 수정의 근거로 확인된 사용자 발언. 없으면 null
     */
    public PlanBriefItem revised(String newText, String newScope, LocalDate newStart, LocalDate newEnd,
                                 PlanBriefService.UserBasis basis, LocalDateTime at) {
        List<String> next = new java.util.ArrayList<>(history == null ? List.of() : history);
        next.add(text);
        String scopeNext = newScope != null ? newScope : scope;
        boolean datesGiven = newStart != null && newEnd != null;
        boolean grounded = basis != null;
        boolean byUser = grounded && PlanBriefService.verbatim(newText, basis.sentence());
        return new PlanBriefItem(id, kind, newText, byUser ? SPEAKER_USER : SPEAKER_ASSISTANT, byUser, false, removed,
                scopeNext, sourceMessageId, byUser ? basis.messageId() : null, supersedes, topicId, courseId,
                executionItemId, revision + 1, next, at, datesGiven ? newStart : periodStart,
                datesGiven ? newEnd : periodEnd, saidOn, flowProposalId,
                grounded ? basis.quote() : null, grounded ? basis.messageId() : null, grounded ? basis.sentence() : null);
    }
}
