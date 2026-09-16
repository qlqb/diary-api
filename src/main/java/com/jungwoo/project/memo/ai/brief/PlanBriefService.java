package com.jungwoo.project.memo.ai.brief;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 상담의 계획 합의(ai_plan_briefs). 대화당 한 행, 항목은 JSON 배열.
 *
 * <p>모델은 변경 제안(ops)만 내고 서버가 적용한다. 사용자가 말한 것은 즉시 유효, AI 제안은 수락 전까지 후보. 발화자를 모르는
 * ADD는 후보로 낮춘다 — 추측을 확정으로 올리지 않는다.
 *
 * <p>합의는 적용 범위를 갖는다(2026-09-17 후속). THIS_DRAFT는 초안 흐름(처음 만든 초안 id, 다시 만들기는 같은 흐름)에서만,
 * PERIOD는 실제 날짜와 요청 기간이 겹칠 때만 유효하다. "이번 주"는 발언 시점·사용자 시간대의 주로 해석하고 새 상담의 주로
 * 다시 해석하지 않는다. 날짜를 복원할 수 없는 옛 항목은 "범위 미확인"이며 현재 합의처럼 전달하지 않는다. 지속 선호는 여기
 * 두지 않고 user_contexts로 간다 — 기간 합의를 자동 승격하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanBriefService {

    public static final int MAX_OPS_PER_TURN = 8;
    public static final int MAX_ITEMS = 40;
    public static final int MAX_TEXT_CHARS = 300;
    static final Set<String> KINDS = Set.of("GOAL", "PRIORITY", "EXCLUDE", "TIME_CONSTRAINT", "FREQUENCY", "SCOPE",
            "DIFFICULTY", "CAUSE", "OTHER");
    static final Set<String> OPS = Set.of("ADD", "ACCEPT", "REJECT", "UPDATE", "REMOVE");

    private static final TypeReference<List<PlanBriefItem>> ITEMS = new TypeReference<>() {
    };

    private final PlanBriefMapper mapper;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Value("${ai.context.default-time-zone:Asia/Seoul}")
    private String defaultTimeZoneId = "Asia/Seoul";

    /** 이번 턴이 함께 알고 있는 기간(기간 계획 OFFER의 날짜). PERIOD 합의가 날짜를 말하지 않았을 때의 근거다. */
    public record TurnPeriod(LocalDate offerStart, LocalDate offerEnd) {
        public boolean known() {
            return offerStart != null && offerEnd != null && !offerEnd.isBefore(offerStart);
        }
    }

    /** 이번 요청에 적용되는 합의 하나. note는 "원래 9/14~9/20에 적용"처럼 범위가 일부만 겹칠 때의 설명(없으면 null). */
    public record Applicable(PlanBriefItem item, String note) {
    }

    /** 읽기 전용 뷰. 항목은 이미 파싱돼 있다. */
    public record View(Long briefId, Long conversationId, int version, List<PlanBriefItem> items, Long lastProposalId) {
        public static View empty(Long conversationId) {
            return new View(null, conversationId, 0, List.of(), null);
        }

        public boolean isEmpty() {
            return items.isEmpty();
        }

        /** 수락됐고 거절·삭제되지 않은 항목(범위 무관). 범위를 따지려면 {@link #effectiveFor}. */
        public List<PlanBriefItem> effective() {
            return items.stream().filter(PlanBriefItem::effective).toList();
        }

        public List<PlanBriefItem> pendingProposals() {
            return items.stream().filter(PlanBriefItem::pendingProposal).toList();
        }

        /**
         * [from, to] 기간의 초안(흐름 flowRoot)에 적용되는 합의.
         *
         * <ul>
         *   <li>THIS_DRAFT: 아직 초안에 묶이지 않았거나(이번 초안이 묶는다) 같은 흐름에 묶인 것만. 다른 흐름(이전 계획)의 것은 제외</li>
         *   <li>PERIOD: 날짜가 요청 기간과 겹치는 것만. 일부만 겹치면 원래 범위를 note로 붙인다. 날짜를 모르면 제외(범위 미확인)</li>
         * </ul>
         *
         * @param flowRoot 이번 초안 흐름의 처음 초안 id. 새 초안이면 null
         */
        public List<Applicable> effectiveFor(LocalDate from, LocalDate to, Long flowRoot) {
            List<Applicable> out = new ArrayList<>();
            for (PlanBriefItem item : effective()) {
                if (item.isPeriod()) {
                    if (!item.overlaps(from, to)) {
                        continue;
                    }
                    out.add(new Applicable(item, item.covers(from, to) ? null
                            : "원래 " + range(item.periodStart(), item.periodEnd()) + "에 적용"));
                } else {
                    if (item.flowProposalId() != null && !Objects.equals(item.flowProposalId(), flowRoot)) {
                        continue;
                    }
                    out.add(new Applicable(item, null));
                }
            }
            return out;
        }

        /** PERIOD인데 날짜를 모르는 옛 항목. 현재 합의가 아니라 과거 참고다. */
        public List<PlanBriefItem> unknownRange() {
            return effective().stream().filter(PlanBriefItem::periodUnknown).toList();
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
     * 다른 대화에서 확인된 어려움·원인과, 지금 기간에 걸치는 기간 합의. 새 대화가 같은 사실을 다시 묻지 않게 한다.
     *
     * <p>어려움·원인은 관련 과목·항목·확인 시점을 그대로 가져가고(관계없는 과목으로 일반화하지 않는다), PERIOD 합의는 날짜가
     * 있고 [from, to]와 겹치는 것만 — from/to가 없으면 오늘 이후에 걸치는 것만. 날짜 없는 옛 PERIOD 항목은 가져오지 않는다.
     */
    @Transactional(readOnly = true)
    public List<PlanBriefItem> carriedOver(Long userId, Long excludeConversationId, int limitBriefs) {
        return carriedOver(userId, excludeConversationId, limitBriefs, null, null);
    }

    @Transactional(readOnly = true)
    public List<PlanBriefItem> carriedOver(Long userId, Long excludeConversationId, int limitBriefs, LocalDate from,
                                           LocalDate to) {
        List<PlanBriefItem> out = new ArrayList<>();
        LocalDateTime since = LocalDateTime.now(clock).minusDays(30);
        LocalDate today = today();
        for (PlanBrief brief : mapper.findRecentByUserId(userId, Math.max(1, limitBriefs))) {
            if (Objects.equals(brief.getConversationId(), excludeConversationId)) {
                continue;
            }
            if (brief.getUpdatedAt() != null && brief.getUpdatedAt().isBefore(since)) {
                continue;
            }
            for (PlanBriefItem item : parse(brief.getItems())) {
                if (!item.effective()) {
                    continue;
                }
                boolean fact = "DIFFICULTY".equals(item.kind()) || "CAUSE".equals(item.kind());
                boolean periodInRange = item.isPeriod() && !item.periodUnknown()
                        && (from != null && to != null ? item.overlaps(from, to) : !item.expiredBy(today));
                if (fact || periodInRange) {
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
        return applyTurn(userId, conversationId, userMessageId, assistantMessageId, ops, null);
    }

    /** @param turnPeriod 이번 턴이 함께 낸 기간(OFFER). PERIOD 합의가 날짜를 말하지 않았을 때의 근거. 없으면 null */
    @Transactional
    public View applyTurn(Long userId, Long conversationId, Long userMessageId, Long assistantMessageId,
                          List<PlanBriefOp> ops, TurnPeriod turnPeriod) {
        if (ops == null || ops.isEmpty() || conversationId == null) {
            return load(userId, conversationId);
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            PlanBrief brief = mapper.findByConversationIdAndUserId(conversationId, userId);
            List<PlanBriefItem> items = brief == null ? new ArrayList<>() : new ArrayList<>(parse(brief.getItems()));
            int applied = apply(items, ops, userMessageId, assistantMessageId, turnPeriod);
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

    /** 이 합의로 초안을 만들었다(흐름·기간을 모르는 옛 호출부). 새 초안 자체를 흐름의 시작으로 본다. */
    @Transactional
    public void markProposal(Long userId, Long briefId, Long proposalId) {
        markProposal(userId, briefId, proposalId, proposalId, null, null);
    }

    /**
     * 이 합의로 초안을 만들었다. 다음 상담이 "아직 적용하지 않은 초안"을 알 수 있게 남기고, 아직 범위가 없는 항목을 이번
     * 초안에 묶는다 — THIS_DRAFT는 흐름(처음 초안 id)에, 날짜 없는 PERIOD는 이 계획의 기간에. 이미 묶인 항목은 그대로다.
     *
     * @param flowRoot 이번 초안 흐름의 처음 초안 id(다시 만들기면 원본의 흐름). 새 초안이면 proposalId와 같다
     */
    @Transactional
    public void markProposal(Long userId, Long briefId, Long proposalId, Long flowRoot, LocalDate start, LocalDate end) {
        if (briefId == null || proposalId == null) {
            return;
        }
        mapper.updateLastProposal(briefId, userId, proposalId);
        Long flow = flowRoot != null ? flowRoot : proposalId;
        LocalDateTime now = LocalDateTime.now(clock);
        for (int attempt = 0; attempt < 2; attempt++) {
            PlanBrief brief = mapper.findByIdAndUserId(briefId, userId);
            if (brief == null) {
                return;
            }
            List<PlanBriefItem> items = new ArrayList<>(parse(brief.getItems()));
            int bound = 0;
            for (int i = 0; i < items.size(); i++) {
                PlanBriefItem item = items.get(i);
                if (item.removed()) {
                    continue;
                }
                PlanBriefItem next = item.bound(flow, start, end, now);
                if (!next.equals(item)) {
                    items.set(i, next);
                    bound++;
                }
            }
            if (bound == 0) {
                return;
            }
            if (mapper.updateItems(briefId, userId, brief.getVersion(), serialize(items), brief.getStatus()) == 1) {
                log.info("계획 합의 범위 묶기: briefId={}, proposalId={}, flow={}, 묶은 항목={}개", briefId, proposalId, flow, bound);
                return;
            }
            log.warn("계획 합의 범위 묶기 경합: briefId={} — 다시 읽는다", briefId);
        }
    }

    // ===== 적용 규칙 =====

    int apply(List<PlanBriefItem> items, List<PlanBriefOp> ops, Long userMessageId, Long assistantMessageId) {
        return apply(items, ops, userMessageId, assistantMessageId, null);
    }

    int apply(List<PlanBriefItem> items, List<PlanBriefOp> ops, Long userMessageId, Long assistantMessageId,
              TurnPeriod turnPeriod) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = today();
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
                String scope = scopeOf(op.scope());
                LocalDate[] period = resolvePeriod(scope, op, turnPeriod, today);
                int id = items.stream().mapToInt(PlanBriefItem::id).max().orElse(0) + 1;
                items.add(new PlanBriefItem(id, kindOf(op.kind()), text, speaker, user, false, false,
                        scope, user ? userMessageId : assistantMessageId, user ? userMessageId : null,
                        null, op.topicId(), op.courseId(), op.executionItemId(), 1, List.of(), now,
                        period[0], period[1], today, null));
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
                    LocalDate start = parseDate(op.periodStart());
                    LocalDate end = parseDate(op.periodEnd());
                    boolean datesGiven = start != null && end != null && !end.isBefore(start);
                    boolean sameText = text == null || text.equals(current.text());
                    if (sameText && !datesGiven) {
                        yield null;
                    }
                    yield current.revised(sameText ? current.text() : text,
                            op.scope() == null ? null : scopeOf(op.scope()),
                            datesGiven ? start : null, datesGiven ? end : null, userMessageId, now);
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

    /**
     * PERIOD 합의의 날짜. 모델이 날짜를 말했으면 그것, 아니면 이번 턴의 OFFER 기간, 그것도 없으면 발언 시점의 주(월~일,
     * 사용자 시간대). THIS_DRAFT는 날짜를 두지 않는다(흐름에 묶인다).
     */
    LocalDate[] resolvePeriod(String scope, PlanBriefOp op, TurnPeriod turnPeriod, LocalDate today) {
        if (!PlanBriefItem.SCOPE_PERIOD.equals(scope)) {
            return new LocalDate[]{null, null};
        }
        LocalDate start = parseDate(op.periodStart());
        LocalDate end = parseDate(op.periodEnd());
        if (start != null && end != null && !end.isBefore(start)) {
            return new LocalDate[]{start, end};
        }
        if (turnPeriod != null && turnPeriod.known()) {
            return new LocalDate[]{turnPeriod.offerStart(), turnPeriod.offerEnd()};
        }
        return new LocalDate[]{today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
                today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))};
    }

    LocalDate today() {
        return ZonedDateTime.now(clock).withZoneSameInstant(ZoneId.of(defaultTimeZoneId)).toLocalDate();
    }

    static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (Exception e) {
            return null;
        }
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
            case "TIME_CONSTRAINT" -> "시간 제약(상한)";
            case "FREQUENCY" -> "반복 빈도(매일 등)";
            case "SCOPE" -> "범위";
            case "DIFFICULTY" -> "확인된 어려움";
            case "CAUSE" -> "원인(사용자 확인)";
            default -> "기타";
        };
    }

    static String range(LocalDate start, LocalDate end) {
        if (start == null || end == null) {
            return "기간 미확인";
        }
        return start.getMonthValue() + "/" + start.getDayOfMonth() + "~" + end.getMonthValue() + "/" + end.getDayOfMonth();
    }

    /**
     * 상담 프롬프트의 [계획 합의 현황]. 번호(#n)와 상태를 함께 줘 모델이 ACCEPT/UPDATE에서 그 번호를 쓰게 한다. 비어 있으면 빈 문자열.
     */
    public static String renderForConsultation(View view) {
        return renderForConsultation(view, null);
    }

    /** @param today 기간이 지난 PERIOD 합의를 "지금 조건 아님"으로 표시하는 기준. null이면 표시하지 않는다 */
    public static String renderForConsultation(View view, LocalDate today) {
        if (view == null || view.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("[계획 합의 현황] (#번호는 planBrief의 id. 사용자가 말한 것과 AI 제안을 구분한다. "
                + "기간이 지난 합의는 지금 조건이 아니다 — 새 기간에 그대로 옮기지 않는다)\n");
        for (PlanBriefItem item : view.items()) {
            if (item.removed()) {
                continue;
            }
            sb.append("- #").append(item.id()).append(' ').append(stateLabel(item)).append(' ')
                    .append(kindLabel(item.kind())).append(": ").append(item.text());
            if (item.isPeriod()) {
                if (item.periodUnknown()) {
                    sb.append(" (기간 합의 · 날짜 미확인, 과거 참고)");
                } else if (today != null && item.expiredBy(today)) {
                    sb.append(" (기간 지남 ").append(range(item.periodStart(), item.periodEnd())).append(" — 지금 조건 아님)");
                } else {
                    sb.append(" (이번 기간 ").append(range(item.periodStart(), item.periodEnd())).append(')');
                }
            } else if (item.flowProposalId() != null) {
                sb.append(" (초안 #").append(item.flowProposalId()).append(" 흐름)");
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
     * 않게 하기 위해서다. 범위를 따지지 않는 옛 경로; 생성기는 {@link #agreedLines(List)}를 쓴다.
     */
    public static List<String> agreedLines(View view) {
        List<String> out = new ArrayList<>();
        if (view == null) {
            return out;
        }
        for (PlanBriefItem item : view.effective()) {
            out.add(agreedLine(item, null));
        }
        return out;
    }

    public static List<String> agreedLines(List<Applicable> applicable) {
        List<String> out = new ArrayList<>();
        for (Applicable a : applicable == null ? List.<Applicable>of() : applicable) {
            out.add(agreedLine(a.item(), a.note()));
        }
        return out;
    }

    public static String agreedLine(PlanBriefItem item, String note) {
        StringBuilder sb = new StringBuilder(kindLabel(item.kind())).append(": ").append(item.text());
        sb.append(PlanBriefItem.SPEAKER_USER.equals(item.speaker()) ? " (사용자가 말함" : " (AI 제안을 사용자가 수락함");
        if (item.revision() > 1) {
            sb.append(", 최신 수정판");
        }
        if (item.isPeriod()) {
            sb.append(", 이번 기간");
            if (item.periodStart() != null && item.periodEnd() != null) {
                sb.append(' ').append(range(item.periodStart(), item.periodEnd()));
            }
            if (note != null) {
                sb.append(" — ").append(note);
            }
            sb.append(')');
        } else {
            sb.append(", 이번 초안)");
        }
        return sb.toString();
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
