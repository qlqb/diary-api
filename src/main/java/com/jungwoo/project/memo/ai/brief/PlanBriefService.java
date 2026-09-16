package com.jungwoo.project.memo.ai.brief;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 상담의 계획 합의를 읽고, 모델이 낸 변경 제안을 검증해 적용하고, 프롬프트 블록으로 만든다.
 *
 * <p>모델은 합의를 직접 쓰지 않는다. 모델이 낸 {@link PlanBriefOp}를 여기서 검증한다 — 없는 번호는 버리고, 발화자를
 * 모르면 AI 제안(후보)으로 낮춰 적는다. 추측을 확정으로 올리는 방향의 실수보다 그 반대가 낫다.
 *
 * <p>저장은 턴 마무리 트랜잭션(ASSISTANT 메시지 INSERT 뒤) 안에서만 일어난다. 대화 잠금이 직렬화를 보장하지만,
 * 그래도 version을 대조해 0행이면 다시 읽어 한 번 더 시도한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanBriefService {

    public static final int MAX_OPS_PER_TURN = 8;
    public static final int MAX_ITEMS = 40;
    public static final int MAX_TEXT_CHARS = 300;
    static final Set<String> KINDS = Set.of("GOAL", "PRIORITY", "EXCLUDE", "TIME_CONSTRAINT", "SCOPE", "DIFFICULTY",
            "CAUSE", "OTHER");
    static final Set<String> OPS = Set.of("ADD", "ACCEPT", "REJECT", "UPDATE", "REMOVE");

    private static final TypeReference<List<PlanBriefItem>> ITEMS = new TypeReference<>() {
    };

    private final PlanBriefMapper mapper;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** 읽기 전용 뷰. 항목은 이미 파싱돼 있다. */
    public record View(Long briefId, Long conversationId, int version, List<PlanBriefItem> items, Long lastProposalId) {
        public static View empty(Long conversationId) {
            return new View(null, conversationId, 0, List.of(), null);
        }

        public boolean isEmpty() {
            return items.isEmpty();
        }

        public List<PlanBriefItem> effective() {
            return items.stream().filter(PlanBriefItem::effective).toList();
        }

        public List<PlanBriefItem> pendingProposals() {
            return items.stream().filter(PlanBriefItem::pendingProposal).toList();
        }
    }

    @Transactional(readOnly = true)
    public View load(Long userId, Long conversationId) {
        if (conversationId == null) {
            return View.empty(null);
        }
        PlanBrief brief = mapper.findByConversationIdAndUserId(conversationId, userId);
        return brief == null ? View.empty(conversationId) : toView(brief);
    }

    @Transactional(readOnly = true)
    public View loadById(Long userId, Long briefId) {
        if (briefId == null) {
            return View.empty(null);
        }
        PlanBrief brief = mapper.findByIdAndUserId(briefId, userId);
        return brief == null ? View.empty(null) : toView(brief);
    }

    /**
     * 다른 대화에서 확인된 어려움·원인과 기간 범위의 합의. 새 대화가 같은 사실을 다시 묻지 않게 한다.
     * 관계없는 과거 대화 전체를 넣지 않는다 — 항목 종류와 최근성으로 좁힌다.
     */
    @Transactional(readOnly = true)
    public List<PlanBriefItem> carriedOver(Long userId, Long excludeConversationId, int limitBriefs) {
        List<PlanBriefItem> out = new ArrayList<>();
        LocalDateTime since = LocalDateTime.now(clock).minusDays(30);
        for (PlanBrief brief : mapper.findRecentByUserId(userId, Math.max(1, limitBriefs))) {
            if (Objects.equals(brief.getConversationId(), excludeConversationId)) {
                continue;
            }
            if (brief.getUpdatedAt() != null && brief.getUpdatedAt().isBefore(since)) {
                continue;
            }
            for (PlanBriefItem item : parse(brief.getItems())) {
                boolean carry = item.effective()
                        && ("DIFFICULTY".equals(item.kind()) || "CAUSE".equals(item.kind())
                        || PlanBriefItem.SCOPE_PERIOD.equals(item.scope()));
                if (carry) {
                    out.add(item);
                }
            }
        }
        return out;
    }

    /**
     * 모델의 변경 제안을 적용한다. 반환값은 적용 뒤의 뷰(변경이 없으면 기존 뷰).
     *
     * @param userMessageId      이번 턴의 사용자 메시지(USER 발화·수락의 출처)
     * @param assistantMessageId 이번 턴의 답변(AI 제안의 출처)
     */
    @Transactional
    public View applyTurn(Long userId, Long conversationId, Long userMessageId, Long assistantMessageId,
                          List<PlanBriefOp> ops) {
        if (ops == null || ops.isEmpty() || conversationId == null) {
            return load(userId, conversationId);
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            PlanBrief brief = mapper.findByConversationIdAndUserId(conversationId, userId);
            List<PlanBriefItem> items = brief == null ? new ArrayList<>() : new ArrayList<>(parse(brief.getItems()));
            int applied = apply(items, ops, userMessageId, assistantMessageId);
            if (applied == 0) {
                return brief == null ? View.empty(conversationId) : toView(brief);
            }
            String serialized = serialize(items);
            if (brief == null) {
                PlanBrief created = PlanBrief.builder().userId(userId).conversationId(conversationId).version(1)
                        .status("OPEN").items(serialized).build();
                mapper.insert(created);
                log.info("계획 합의 생성: conversationId={}, 항목={}개, 적용={}개", conversationId, items.size(), applied);
                return new View(created.getBriefId(), conversationId, 1, List.copyOf(items), null);
            }
            int updated = mapper.updateItems(brief.getBriefId(), userId, brief.getVersion(), serialized, "OPEN");
            if (updated == 1) {
                log.info("계획 합의 갱신: conversationId={}, version {}→{}, 항목={}개, 적용={}개", conversationId,
                        brief.getVersion(), brief.getVersion() + 1, items.size(), applied);
                return new View(brief.getBriefId(), conversationId, brief.getVersion() + 1, List.copyOf(items),
                        brief.getLastProposalId());
            }
            log.warn("계획 합의 갱신 경합: conversationId={}, version={} — 다시 읽는다", conversationId, brief.getVersion());
        }
        return load(userId, conversationId);
    }

    /** 이 합의로 초안을 만들었다. 다음 상담이 "아직 적용하지 않은 초안"을 알 수 있게 남긴다. */
    @Transactional
    public void markProposal(Long userId, Long briefId, Long proposalId) {
        if (briefId == null || proposalId == null) {
            return;
        }
        mapper.updateLastProposal(briefId, userId, proposalId);
    }

    // ===== 적용 규칙 =====

    int apply(List<PlanBriefItem> items, List<PlanBriefOp> ops, Long userMessageId, Long assistantMessageId) {
        LocalDateTime now = LocalDateTime.now(clock);
        int applied = 0;
        int seen = 0;
        for (PlanBriefOp op : ops) {
            if (op == null || op.op() == null || seen++ >= MAX_OPS_PER_TURN) {
                continue;
            }
            String kind = op.op().trim().toUpperCase(Locale.ROOT);
            if (!OPS.contains(kind)) {
                continue;
            }
            if ("ADD".equals(kind)) {
                String text = clean(op.text());
                if (text == null || items.stream().filter(i -> !i.removed()).count() >= MAX_ITEMS) {
                    continue;
                }
                boolean user = PlanBriefItem.SPEAKER_USER.equalsIgnoreCase(op.speaker());
                String speaker = user ? PlanBriefItem.SPEAKER_USER : PlanBriefItem.SPEAKER_ASSISTANT;
                // 같은 문장을 같은 발화자가 또 내면 중복이다.
                boolean duplicate = items.stream().anyMatch(i -> !i.removed() && i.speaker().equals(speaker)
                        && i.text().equals(text));
                if (duplicate) {
                    continue;
                }
                int id = items.stream().mapToInt(PlanBriefItem::id).max().orElse(0) + 1;
                items.add(new PlanBriefItem(id, kindOf(op.kind()), text, speaker, user, false, false,
                        scopeOf(op.scope()), user ? userMessageId : assistantMessageId, user ? userMessageId : null,
                        null, op.topicId(), op.courseId(), op.executionItemId(), 1, List.of(), now));
                applied++;
                continue;
            }
            int index = indexOf(items, op.id());
            if (index < 0) {
                continue;
            }
            PlanBriefItem current = items.get(index);
            PlanBriefItem next = switch (kind) {
                case "ACCEPT" -> current.effective() ? null : current.withAccepted(userMessageId, now);
                case "REJECT" -> current.rejected() ? null : current.withRejected(now);
                case "REMOVE" -> current.removed() ? null : current.withRemoved(now);
                case "UPDATE" -> {
                    String text = clean(op.text());
                    yield text == null || text.equals(current.text()) ? null
                            : current.revised(text, op.scope() == null ? null : scopeOf(op.scope()), userMessageId, now);
                }
                default -> null;
            };
            if (next != null) {
                items.set(index, next);
                applied++;
            }
        }
        return applied;
    }

    private static int indexOf(List<PlanBriefItem> items, Integer id) {
        if (id == null) {
            return -1;
        }
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id() == id && !items.get(i).removed()) {
                return i;
            }
        }
        return -1;
    }

    static String kindOf(String raw) {
        if (raw == null) {
            return "OTHER";
        }
        String k = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        return KINDS.contains(k) ? k : "OTHER";
    }

    static String scopeOf(String raw) {
        if (raw == null) {
            return PlanBriefItem.SCOPE_THIS_DRAFT;
        }
        String s = raw.trim().toUpperCase(Locale.ROOT);
        return PlanBriefItem.SCOPE_PERIOD.equals(s) ? PlanBriefItem.SCOPE_PERIOD : PlanBriefItem.SCOPE_THIS_DRAFT;
    }

    static String clean(String text) {
        if (text == null) {
            return null;
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        if (flat.isEmpty()) {
            return null;
        }
        return flat.length() <= MAX_TEXT_CHARS ? flat : flat.substring(0, MAX_TEXT_CHARS);
    }

    // ===== 프롬프트 =====

    public static String kindLabel(String kind) {
        return switch (kind == null ? "OTHER" : kind) {
            case "GOAL" -> "목표";
            case "PRIORITY" -> "우선순위";
            case "EXCLUDE" -> "제외";
            case "TIME_CONSTRAINT" -> "시간 제약";
            case "SCOPE" -> "범위";
            case "DIFFICULTY" -> "확인된 어려움";
            case "CAUSE" -> "원인(사용자 확인)";
            default -> "기타";
        };
    }

    /**
     * 상담 프롬프트의 [계획 합의 현황]. 번호(#n)와 상태를 함께 줘 모델이 ACCEPT/UPDATE에서 그 번호를 쓰게 한다.
     * 비어 있으면 빈 문자열.
     */
    public static String renderForConsultation(View view) {
        if (view == null || view.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("[계획 합의 현황] (#번호는 planBrief의 id. 사용자가 말한 것과 AI 제안을 구분한다)\n");
        for (PlanBriefItem item : view.items()) {
            if (item.removed()) {
                continue;
            }
            sb.append("- #").append(item.id()).append(' ').append(stateLabel(item)).append(' ')
                    .append(kindLabel(item.kind())).append(": ").append(item.text());
            if (PlanBriefItem.SCOPE_PERIOD.equals(item.scope())) {
                sb.append(" (이번 기간)");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    static String stateLabel(PlanBriefItem item) {
        if (item.rejected()) {
            return "[AI 제안 · 거절됨]";
        }
        if (PlanBriefItem.SPEAKER_USER.equals(item.speaker())) {
            return item.revision() > 1 ? "[사용자 · 수정판]" : "[사용자]";
        }
        return item.accepted() ? "[AI 제안 · 사용자 수락]" : "[AI 제안 · 아직 답 없음]";
    }

    /**
     * 계획 생성 프롬프트의 [상담에서 합의한 것]. 유효한 합의와 아직 답 없는 후보를 나눈다 — 후보를 합의처럼 이어받지
     * 않게 하기 위해서다.
     */
    public static List<String> agreedLines(View view) {
        List<String> out = new ArrayList<>();
        if (view == null) {
            return out;
        }
        for (PlanBriefItem item : view.effective()) {
            StringBuilder sb = new StringBuilder(kindLabel(item.kind())).append(": ").append(item.text());
            sb.append(PlanBriefItem.SPEAKER_USER.equals(item.speaker()) ? " (사용자가 말함" : " (AI 제안을 사용자가 수락함");
            if (item.revision() > 1) {
                sb.append(", 최신 수정판");
            }
            sb.append(PlanBriefItem.SCOPE_PERIOD.equals(item.scope()) ? ", 이번 기간)" : ", 이번 초안)");
            out.add(sb.toString());
        }
        return out;
    }

    public static List<String> pendingLines(View view) {
        List<String> out = new ArrayList<>();
        if (view == null) {
            return out;
        }
        for (PlanBriefItem item : view.pendingProposals()) {
            out.add(kindLabel(item.kind()) + ": " + item.text());
        }
        return out;
    }

    // ===== JSON =====

    private View toView(PlanBrief brief) {
        return new View(brief.getBriefId(), brief.getConversationId(), brief.getVersion(), parse(brief.getItems()),
                brief.getLastProposalId());
    }

    List<PlanBriefItem> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            List<PlanBriefItem> items = json.readValue(raw, ITEMS);
            return items == null ? List.of() : items;
        } catch (Exception e) {
            log.warn("계획 합의 JSON을 읽지 못했다: {}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    String serialize(List<PlanBriefItem> items) {
        try {
            return json.writeValueAsString(items);
        } catch (Exception e) {
            throw new IllegalStateException("계획 합의 직렬화 실패", e);
        }
    }
}
