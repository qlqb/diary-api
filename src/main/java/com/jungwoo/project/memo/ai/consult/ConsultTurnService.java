package com.jungwoo.project.memo.ai.consult;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jungwoo.project.memo.ai.AiMessageMapper;
import com.jungwoo.project.memo.ai.UserContextService;
import com.jungwoo.project.memo.ai.brief.PlanBriefItem;
import com.jungwoo.project.memo.ai.brief.PlanBriefService;
import com.jungwoo.project.memo.ai.domain.ContextEvidenceType;
import com.jungwoo.project.memo.ai.dto.UserContextResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 상담 턴의 마무리에서 "이번 답변으로 무엇을 알게 됐고 무엇이 바뀌었는가"를 만든다.
 *
 * <p>질문 답변을 저장만 하고 끝내지 않는다 — (1) 사용자가 말한 것·자기평가를 출처와 범위를 붙여 기억하고,
 * (2) 그 해석을 사용자에게 보여 고칠 수 있게 하고, (3) 계획 방향의 변화를 말한다. 그리고 그 결과를 ASSISTANT 메시지에
 * 붙여 저장해 새로고침·재접속 뒤에도 같은 카드가 복구되게 한다.
 *
 * <p>이 단계는 턴의 부속이다 — 실패해도 턴(답변·제안)은 이미 저장됐고 그대로 전달된다. 실행 항목·일정은 건드리지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsultTurnService {

    static final int MAX_MEMORY_PER_TURN = 4;
    static final int MAX_CHOICES = 6;
    static final int MAX_TEXT = 300;
    static final int MAX_CHOICE_CHARS = 40;

    private final UserContextService userContextService;
    private final PlanBriefService planBriefService;
    private final AiMessageMapper aiMessageMapper;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    /**
     * @param userMessage 이번 턴의 사용자 발화(인용 확인에 쓴다)
     * @return 화면에 줄 것이 없으면 null
     */
    public ConsultView finish(Long userId, Long conversationId, Long userMessageId, Long assistantMessageId,
                              String userMessage, ConsultOut out) {
        try {
            List<ConsultView.Understanding> understanding = new ArrayList<>();
            if (out != null && out.memory() != null && !out.memory().isEmpty()) {
                List<UserContextService.AutoSave> ops = new ArrayList<>();
                for (ConsultOut.MemoryOut m : out.memory()) {
                    if (m == null) {
                        continue;
                    }
                    ops.add(new UserContextService.AutoSave(m.text(), evidence(m.evidenceType()), m.courseId(),
                            date(m.scopeStart()), date(m.scopeEnd()), m.quote()));
                }
                for (UserContextResponse saved : userContextService.autoSave(userId, userMessageId, userMessage, ops,
                        MAX_MEMORY_PER_TURN)) {
                    understanding.add(new ConsultView.Understanding(String.valueOf(saved.getContextId()), "MEMORY",
                            saved.getContent(), saved.getEvidenceType().name(), scopeLabel(saved), true));
                }
            }
            // 같은 말이 기억과 합의 양쪽에 남았으면 화면에는 한 번만 보인다(기억 쪽 — 바로 고칠 수 있다).
            for (ConsultView.Understanding brief : briefUnderstanding(userId, conversationId, userMessageId)) {
                boolean sameAsMemory = understanding.stream().anyMatch(m -> similar(m.text(), brief.text()));
                if (!sameAsMemory) {
                    understanding.add(brief);
                }
            }

            ConsultView view = new ConsultView(question(out, assistantMessageId), understanding, direction(out),
                    activity(out));
            if (view.isEmpty()) {
                return null;
            }
            aiMessageMapper.updateConsultJson(assistantMessageId, userId, objectMapper.writeValueAsString(view));
            return view;
        } catch (Exception e) {
            log.warn("상담 턴의 부가 정보(질문·해석·방향)를 만들지 못했다 — 답변은 그대로 전달된다. conversationId={}, {}",
                    conversationId, e.getClass().getSimpleName());
            return null;
        }
    }

    /** 저장된 consult_json을 화면용으로 읽는다. 읽지 못하면 null. */
    public ConsultView read(String consultJson) {
        if (consultJson == null || consultJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(consultJson, ConsultView.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 빠른 답(선택지)으로 온 요청의 사용자 발화를 만든다. 선택 답과 자유 답은 같은 경로다 — 고른 것도 대화 기록에 남는다.
     * 클라이언트가 보낸 라벨을 믿지 않고, 저장된 질문의 선택지에서 찾는다.
     *
     * @return 만들 수 없으면(질문이 없거나 선택지가 그 질문 것이 아님) null
     */
    public String composeAnswer(Long userId, Long conversationId, String questionId, List<String> choiceIds, boolean skipped) {
        if (skipped) {
            return "이 질문은 건너뛸게요.";
        }
        if (questionId == null || choiceIds == null || choiceIds.isEmpty()) {
            return null;
        }
        try {
            Long messageId = Long.valueOf(questionId.replaceFirst("^q-", ""));
            var message = aiMessageMapper.findByIdAndUserId(messageId, userId);
            if (message == null || !Objects.equals(message.getConversationId(), conversationId)) {
                return null;
            }
            ConsultView view = read(message.getConsultJson());
            if (view == null || view.question() == null) {
                return null;
            }
            List<String> labels = new ArrayList<>();
            for (ConsultView.Choice choice : view.question().choices()) {
                if (choiceIds.contains(choice.id())) {
                    labels.add(choice.label());
                }
            }
            return labels.isEmpty() ? null : String.join(", ", labels);
        } catch (Exception e) {
            return null;
        }
    }

    // ===== 변환 =====

    private static ConsultView.Question question(ConsultOut out, Long assistantMessageId) {
        if (out == null || out.question() == null || blank(out.question().text())) {
            return null;
        }
        List<ConsultView.Choice> choices = new ArrayList<>();
        int i = 1;
        for (String raw : out.question().choices() == null ? List.<String>of() : out.question().choices()) {
            String label = cut(raw, MAX_CHOICE_CHARS);
            if (label == null || choices.stream().anyMatch(c -> c.label().equals(label))) {
                continue;
            }
            choices.add(new ConsultView.Choice("c" + i++, label));
            if (choices.size() >= MAX_CHOICES) {
                break;
            }
        }
        String topic = out.question().topic() == null ? "OTHER" : out.question().topic().trim().toUpperCase(Locale.ROOT);
        if (!List.of("SUPPORT_LEVEL", "BLOCKER", "TIME", "SCOPE", "DEPTH", "SUBMISSION", "OTHER").contains(topic)) {
            topic = "OTHER";
        }
        return new ConsultView.Question("q-" + assistantMessageId, cut(out.question().text(), MAX_TEXT),
                cut(out.question().why(), MAX_TEXT), topic, choices,
                Boolean.TRUE.equals(out.question().multiSelect()) && choices.size() > 1);
    }

    private static ConsultView.Direction direction(ConsultOut out) {
        if (out == null || out.direction() == null || blank(out.direction().after())) {
            return null;
        }
        return new ConsultView.Direction(cut(out.direction().before(), MAX_TEXT), cut(out.direction().after(), MAX_TEXT),
                cut(out.direction().reason(), MAX_TEXT), Boolean.TRUE.equals(out.direction().affectsDraft()));
    }

    private static ConsultView.Activity activity(ConsultOut out) {
        if (out == null || out.activity() == null || out.activity().items() == null || out.activity().items().isEmpty()
                || out.activity().courseId() == null) {
            return null;
        }
        List<ConsultView.ActivityItem> items = new ArrayList<>();
        int i = 1;
        for (ConsultOut.ActivityItemOut item : out.activity().items()) {
            if (item == null || blank(item.label())) {
                continue;
            }
            items.add(new ConsultView.ActivityItem("a" + i++, cut(item.label(), 80), item.topicId(), item.sectionId()));
            if (items.size() >= 12) {
                break;
            }
        }
        return items.isEmpty() ? null : new ConsultView.Activity("SELF_CHECK", out.activity().courseId(),
                cut(out.activity().title(), 80), items);
    }

    /** 이번 사용자 발화에서 생긴(또는 이번 발화로 동의한) 계획 합의. */
    private List<ConsultView.Understanding> briefUnderstanding(Long userId, Long conversationId, Long userMessageId) {
        List<ConsultView.Understanding> out = new ArrayList<>();
        PlanBriefService.View brief = planBriefService.load(userId, conversationId);
        if (brief == null || brief.items() == null) {
            return out;
        }
        for (PlanBriefItem item : brief.items()) {
            if (item.removed() || item.rejected()) {
                continue;
            }
            boolean fromThisTurn = Objects.equals(item.sourceMessageId(), userMessageId)
                    && PlanBriefItem.SPEAKER_USER.equals(item.speaker());
            boolean acceptedThisTurn = Objects.equals(item.acceptedByMessageId(), userMessageId);
            if (!fromThisTurn && !acceptedThisTurn) {
                continue;
            }
            String scope = item.periodStart() != null && item.periodEnd() != null
                    ? period(item.periodStart(), item.periodEnd()) : "이번 계획";
            out.add(new ConsultView.Understanding(String.valueOf(item.id()), "BRIEF", item.text(),
                    ContextEvidenceType.STATED.name(), scope, true));
        }
        return out;
    }

    /** 표시용 중복 판단: 공백·문장부호를 뺀 앞부분이 같으면 같은 말로 본다. 저장에는 영향을 주지 않는다. */
    static boolean similar(String a, String b) {
        String x = a == null ? "" : a.replaceAll("[\\s\\p{Punct}·…]+", "");
        String y = b == null ? "" : b.replaceAll("[\\s\\p{Punct}·…]+", "");
        int n = Math.min(14, Math.min(x.length(), y.length()));
        return n >= 8 && x.regionMatches(0, y, 0, n);
    }

    private static String scopeLabel(UserContextResponse c) {
        List<String> parts = new ArrayList<>();
        if (c.getCourseTitle() != null) {
            parts.add(c.getCourseTitle());
        }
        if (c.getScopeStart() != null && c.getScopeEnd() != null) {
            parts.add(period(c.getScopeStart(), c.getScopeEnd()));
        }
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }

    private static String period(LocalDate start, LocalDate end) {
        String a = start.getMonthValue() + "/" + start.getDayOfMonth();
        return start.equals(end) ? a : a + "~" + end.getMonthValue() + "/" + end.getDayOfMonth();
    }

    private static ContextEvidenceType evidence(String raw) {
        if (raw == null) {
            return ContextEvidenceType.INFERRED;
        }
        try {
            return ContextEvidenceType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ContextEvidenceType.INFERRED;
        }
    }

    private static LocalDate date(String raw) {
        try {
            return raw == null || raw.isBlank() ? null : LocalDate.parse(raw.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String cut(String s, int max) {
        if (blank(s) || "null".equalsIgnoreCase(s.trim())) {
            return null;
        }
        String flat = s.strip().replaceAll("\\s+", " ");
        return flat.length() > max ? flat.substring(0, max) : flat;
    }
}
