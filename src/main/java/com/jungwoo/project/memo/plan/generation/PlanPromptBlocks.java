package com.jungwoo.project.memo.plan.generation;

import com.jungwoo.project.memo.ai.brief.PlanBriefItem;
import com.jungwoo.project.memo.ai.brief.PlanBriefService;
import com.jungwoo.project.memo.ai.domain.AiMessage;
import com.jungwoo.project.memo.ai.domain.MessageRole;
import com.jungwoo.project.memo.plan.evidence.ExecutionEvidence;
import com.jungwoo.project.memo.plan.evidence.ExecutionEvidenceService;
import com.jungwoo.project.memo.plan.provenance.ProvenanceCollector;
import com.jungwoo.project.memo.plan.provenance.ProvenanceRepresentation;
import com.jungwoo.project.memo.plan.provenance.ProvenanceSourceType;
import com.jungwoo.project.memo.routine.domain.RoutineOccurrence;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 계획 호출 프롬프트의 새 블록(상담 기록·합의·실행 기록·다음 수업). 줄마다 출처를 남긴다 — 줄을 쓰고 나서 따로 기록하지
 * 않고 기록이 줄을 만들어 돌려준다(PeriodPlanDraftGenerator의 규칙과 같다).
 */
public final class PlanPromptBlocks {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    static final int MAX_MESSAGE_CHARS = 600;
    static final int MAX_HISTORY_LINES_PER_COURSE = 12;

    private PlanPromptBlocks() {
    }

    /**
     * [상담 기록] — 사용자·AI 발화를 발화자와 함께, 오래된 것부터. 글자 예산 안에서 최근 것부터 담는다.
     *
     * @param excludeMessageId 이번 생성 버튼 턴의 메시지(내용이 비어 있고 대화가 아니다)
     */
    public static void appendTranscript(StringBuilder sb, List<AiMessage> messages, Long excludeMessageId,
                                        int budgetChars, ProvenanceCollector collector) {
        if (messages == null || messages.isEmpty() || budgetChars <= 0) {
            return;
        }
        List<AiMessage> ordered = messages.stream()
                .filter(m -> m.getContent() != null && !m.getContent().isBlank())
                .filter(m -> !Objects.equals(m.getMessageId(), excludeMessageId))
                .sorted(Comparator.comparing(AiMessage::getMessageId, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
        List<String> lines = new ArrayList<>();
        int used = 0;
        int skipped = 0;
        for (int i = ordered.size() - 1; i >= 0; i--) {
            AiMessage m = ordered.get(i);
            String text = cut(m.getContent(), MAX_MESSAGE_CHARS);
            String role = m.getRole() == MessageRole.USER ? "사용자" : "AI";
            String line = role + ": " + text;
            if (used + line.length() > budgetChars) {
                skipped = i + 1;
                break;
            }
            ProvenanceCollector.Marked marked = collector.mark(ProvenanceSourceType.CONVERSATION_MESSAGE, m.getMessageId(),
                    null, m.getCreatedAt(), ProvenanceRepresentation.EXCERPT,
                    ProvenanceCollector.value("role", m.getRole() == null ? null : m.getRole().name(), "text", text), line);
            lines.add(0, "- " + marked.text());
            used += line.length();
        }
        if (lines.isEmpty()) {
            return;
        }
        sb.append("[상담 기록] (오래된 것부터. \"AI:\"는 제안이지 확정이 아니다 — 사용자가 받아들였는지는 [상담에서 합의한 것]이 말한다)\n");
        if (skipped > 0) {
            sb.append("- (앞의 ").append(skipped).append("개 발화는 예산 때문에 싣지 않았다)\n");
        }
        lines.forEach(l -> sb.append(l).append('\n'));
        sb.append('\n');
    }

    /**
     * [상담에서 합의한 것] — 이 초안에 적용되는 합의(범위 안)와, 아직 답 없는 AI 제안을 나눈다. 다른 초안 흐름·지난 기간의
     * 합의는 싣지 않는다({@link PlanBriefService.View#effectiveFor}).
     */
    public static void appendBrief(StringBuilder sb, PlanBriefService.View brief, List<PlanBriefService.Applicable> applicable,
                                   ProvenanceCollector collector) {
        if (brief == null || brief.isEmpty()) {
            return;
        }
        List<PlanBriefItem> pending = brief.pendingProposals();
        if ((applicable == null || applicable.isEmpty()) && pending.isEmpty()) {
            return;
        }
        sb.append("[상담에서 합의한 것] (사용자가 말했거나 AI 제안을 받아들인 것. 원인 없이 새로 정하지 않는다 — ")
                .append("본문·최신 일정 때문에 바꿔야 하면 changes에 무엇을 왜 바꿨는지 적는다. 기간이 일부만 겹치는 합의는 원래 범위 안에서만 적용한다)\n");
        for (PlanBriefService.Applicable a : applicable == null ? List.<PlanBriefService.Applicable>of() : applicable) {
            PlanBriefItem item = a.item();
            String text = PlanBriefService.agreedLine(item, a.note());
            sb.append("- ").append(collector.mark(ProvenanceSourceType.PLAN_BRIEF, brief.briefId(), (long) brief.version(),
                    item.updatedAt(), ProvenanceRepresentation.EXCERPT,
                    ProvenanceCollector.value("itemId", item.id(), "kind", item.kind(), "speaker", item.speaker(),
                            "accepted", item.accepted(), "scope", item.scope(), "text", item.text(),
                            "topicId", item.topicId(), "courseId", item.courseId(),
                            "executionItemId", item.executionItemId(), "sourceMessageId", item.sourceMessageId(),
                            "periodStart", item.periodStart(), "periodEnd", item.periodEnd(),
                            "flowProposalId", item.flowProposalId()),
                    text).text()).append('\n');
        }
        if (!pending.isEmpty()) {
            sb.append("아직 사용자가 답하지 않은 AI 제안(합의가 아니다 — 근거가 있으면 반영해도 되지만 합의처럼 쓰지 않는다):\n");
            for (PlanBriefItem item : pending) {
                sb.append("- ").append(collector.mark(ProvenanceSourceType.PLAN_BRIEF, brief.briefId(), (long) brief.version(),
                        item.updatedAt(), ProvenanceRepresentation.EXCERPT,
                        ProvenanceCollector.value("itemId", item.id(), "kind", item.kind(), "speaker", item.speaker(),
                                "accepted", false, "scope", item.scope(), "text", item.text()),
                        PlanBriefService.kindLabel(item.kind()) + ": " + item.text() + " (AI 제안, 답 없음)").text()).append('\n');
            }
        }
        sb.append('\n');
    }

    /**
     * 프로젝트 하나의 [관련 실행 기록]. 관찰 사실만 적는다. 기록·이동·메모가 있는 항목을 앞에 둔다.
     *
     * @return 실은 줄 수
     */
    public static int appendHistory(StringBuilder sb, ExecutionEvidence evidence, Long courseId,
                                    ProvenanceCollector collector, String indent) {
        if (evidence == null || evidence.isEmpty()) {
            return 0;
        }
        List<ExecutionEvidence.ItemHistory> items = new ArrayList<>(evidence.ofCourse(courseId));
        if (items.isEmpty()) {
            return 0;
        }
        items.sort(Comparator.comparingInt((ExecutionEvidence.ItemHistory h) -> interest(h)).reversed());
        ExecutionEvidence.CourseSummary summary = evidence.byCourse().get(courseId);
        sb.append(indent).append("[관련 실행 기록 — ").append(evidence.from()).append("~").append(evidence.to())
                .append("] (관찰 사실이다. \"옮김 3회\"를 \"어려워서 피함\"으로 단정하지 않는다. 기록이 없는 날은 실패가 아니다)\n");
        if (summary != null) {
            sb.append(indent).append("- ").append(ExecutionEvidenceService.summaryLine(summary)).append('\n');
        }
        int shown = 0;
        for (ExecutionEvidence.ItemHistory h : items) {
            if (shown >= MAX_HISTORY_LINES_PER_COURSE) {
                sb.append(indent).append("- … 외 ").append(items.size() - shown).append("개\n");
                break;
            }
            sb.append(indent).append("- ").append(collector.mark(ProvenanceSourceType.EXECUTION_HISTORY, h.executionItemId(),
                    h.version(), h.updatedAt(), ProvenanceRepresentation.SELECTED_FIELDS,
                    ProvenanceCollector.value("courseId", courseId, "topicId", h.topicId(), "title", h.title(),
                            "status", h.status() == null ? null : h.status().name(),
                            "plannedDate", h.plannedDate(), "currentDate", h.currentDate(),
                            "expectedMinutes", h.expectedMinutes(), "measuredMinutes", h.measuredMinutes(),
                            "unmeasured", h.unmeasured() ? Boolean.TRUE : null,
                            "movedCount", h.movedCount(), "reducedCount", h.reducedCount(),
                            "held", h.held() ? Boolean.TRUE : null, "reopened", h.reopened() ? Boolean.TRUE : null,
                            "note", h.userNote(), "leftoverOf", h.leftoverOfId()),
                    ExecutionEvidenceService.describe(h), h.topicId(), null).text()).append('\n');
            shown++;
        }
        return shown;
    }

    private static int interest(ExecutionEvidence.ItemHistory h) {
        int score = 0;
        if (h.userNote() != null) {
            score += 4;
        }
        if (h.recordCount() > 0) {
            score += 3;
        }
        score += Math.min(3, h.movedCount()) + Math.min(2, h.reducedCount());
        if (h.held() || h.reopened()) {
            score += 1;
        }
        return score;
    }

    /** [다음 수업] 한 줄. 마감 참조(deadlineRefId)의 근거가 된다. */
    public static ProvenanceCollector.Marked markNextClass(RoutineOccurrence next, Long courseId, ProvenanceCollector collector) {
        String text = "다음 수업: " + span(next.startAt(), next.endAt())
                + (next.title() == null ? "" : " " + next.title())
                + " — 수업 전에 끝내야 하는 항목은 이 줄을 deadlineRefId로 가리킨다";
        return collector.mark(ProvenanceSourceType.NEXT_CLASS, next.routineId(), null, null,
                ProvenanceRepresentation.SELECTED_FIELDS,
                ProvenanceCollector.value("courseId", courseId, "startAt", next.startAt(), "endAt", next.endAt(),
                        "title", next.title()), text);
    }

    static String span(LocalDateTime startAt, LocalDateTime endAt) {
        String day = startAt.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN);
        return startAt.getMonthValue() + "/" + startAt.getDayOfMonth() + " " + day + " "
                + startAt.toLocalTime().format(TIME_FMT) + "~" + endAt.toLocalTime().format(TIME_FMT);
    }

    static String cut(String s, int max) {
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }
}
