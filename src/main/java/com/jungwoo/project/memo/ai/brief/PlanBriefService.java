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
            "DEPTH", "TIME_BUDGET", "PURPOSE",
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
        return applyTurn(userId, conversationId, userMessageId, assistantMessageId, ops, turnPeriod, List.of());
    }

    /**
     * @param utterances 근거 확인에 쓰는 이 대화의 사용자 발언(서버가 직접 조회한 USER 메시지, 최신이 앞). 비어 있으면 어떤 항목도
     *                   사용자 발언 근거를 얻지 못한다 — 모델이 speaker=USER라고 해도 후보다
     */
    @Transactional
    public View applyTurn(Long userId, Long conversationId, Long userMessageId, Long assistantMessageId,
                          List<PlanBriefOp> ops, TurnPeriod turnPeriod, List<UserUtterance> utterances) {
        if (ops == null || ops.isEmpty() || conversationId == null) {
            return load(userId, conversationId);
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            PlanBrief brief = mapper.findByConversationIdAndUserId(conversationId, userId);
            List<PlanBriefItem> items = brief == null ? new ArrayList<>() : new ArrayList<>(parse(brief.getItems()));
            int applied = apply(items, ops, userMessageId, assistantMessageId, turnPeriod, utterances);
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
        return apply(items, ops, userMessageId, assistantMessageId, turnPeriod, List.of());
    }

    /**
     * (2026-10-18) 작성 주체(speaker)·근거(사용자 발언)·승인을 나눠 저장한다.
     * <ul>
     *   <li>speaker=USER이고 quote가 사용자 발언에서 확인되면 사용자 진술(유효).</li>
     *   <li>speaker=ASSISTANT이고 quote가 확인되면 AI가 사용자 말을 정리한 항목 — 작성 주체는 AI, 근거는 그 발언, 사용자 요청이므로 유효.</li>
     *   <li>quote가 없거나 확인되지 않으면 speaker 값과 무관하게 AI 제안(후보). speaker=USER만으로 승인하지 않는다.</li>
     * </ul>
     */
    int apply(List<PlanBriefItem> items, List<PlanBriefOp> ops, Long userMessageId, Long assistantMessageId,
              TurnPeriod turnPeriod, List<UserUtterance> utterances) {
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
                String text = withTimeBudget(clean(op.text()), op);
                if (text == null) {
                    continue;
                }
                String scope = scopeOf(op.scope());
                UserBasis basis = admissibleBasis(op, scope, userMessageId, utterances);
                // 사용자 말을 그대로 옮긴 문장만 사용자 작성이다. AI가 자기 말로 정리했으면 작성자는 AI, 근거는 그 발언(승인은 아니다).
                boolean user = basis != null && PlanBriefItem.SPEAKER_USER.equalsIgnoreCase(op.speaker())
                        && verbatim(text, basis.sentence());
                String speaker = user ? PlanBriefItem.SPEAKER_USER : PlanBriefItem.SPEAKER_ASSISTANT;
                LocalDate[] period = resolvePeriod(scope, op, turnPeriod, today);
                // 같은 문장·같은 적용 범위(초안/기간·날짜·과목)면 같은 항목이다 — 새로 만들지 않고, 근거가 새로 확인됐으면 붙인다.
                // 범위나 과목이 다르면 다른 요청이다. 거절된 항목은 근거가 붙어도 되살리지 않는다(사용자가 다시 말하면 새 문장으로 온다).
                int same = sameItem(items, text, scope, period, op.courseId());
                if (same >= 0) {
                    PlanBriefItem existing = items.get(same);
                    if (basis != null && !existing.grounded() && !existing.rejected()) {
                        items.set(same, existing.withBasis(basis.quote(), basis.messageId(), basis.sentence(), now));
                        applied++;
                    }
                    continue;
                }
                if (items.stream().filter(i -> !i.removed()).count() >= MAX_ITEMS) {
                    continue; // 상한은 새 항목에만 — 기존 항목에 근거를 붙이는 것은 막지 않는다
                }
                int id = items.stream().mapToInt(PlanBriefItem::id).max().orElse(0) + 1;
                items.add(new PlanBriefItem(id, kindOf(op.kind()), text, speaker, user, false, false,
                        scope, user ? basis.messageId() : assistantMessageId, user ? basis.messageId() : null,
                        null, op.topicId(), op.courseId(), op.executionItemId(), 1, List.of(), now,
                        period[0], period[1], today, null,
                        basis == null ? null : basis.quote(), basis == null ? null : basis.messageId(),
                        basis == null ? null : basis.sentence()));
                applied++;
                continue;
            }
            int index = indexOf(items, op.id());
            if (index < 0) {
                continue;
            }
            PlanBriefItem current = items.get(index);
            PlanBriefItem next = switch (kind) {
                case "ACCEPT" -> current.accepted() || current.rejected() ? null : current.withAccepted(userMessageId, now);
                case "REJECT" -> current.rejected() ? null : current.withRejected(now);
                case "REMOVE" -> current.removed() ? null : current.withRemoved(now);
                case "UPDATE" -> {
                    String text = withTimeBudget(clean(op.text()), op);
                    LocalDate start = parseDate(op.periodStart());
                    LocalDate end = parseDate(op.periodEnd());
                    boolean datesGiven = start != null && end != null && !end.isBefore(start);
                    boolean sameText = text == null || text.equals(current.text());
                    if (current.rejected()) {
                        yield null; // 거절된 제안은 고쳐 쓰는 것으로 되살아나지 않는다
                    }
                    String scopeNext = op.scope() == null ? current.scope() : scopeOf(op.scope());
                    UserBasis basis = admissibleBasis(op, scopeNext, userMessageId, utterances);
                    if (sameText && !datesGiven && scopeNext.equals(current.scope())) {
                        // 내용은 그대로인데 사용자가 같은 말을 했다 — 근거만 붙인다(판·승인 상태는 그대로).
                        yield basis != null && !current.grounded()
                                ? current.withBasis(basis.quote(), basis.messageId(), basis.sentence(), now) : null;
                    }
                    yield current.revised(sameText ? current.text() : text, scopeNext,
                            datesGiven ? start : null, datesGiven ? end : null, basis, now);
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

    /** 근거 확인에 쓰는 사용자 발언 하나. 서버가 대화에서 직접 조회한 USER 메시지만 담는다. */
    public record UserUtterance(Long messageId, String text) {
    }

    /** 확인된 근거: 사용자 메시지, 모델이 낸 구절, 그 구절이 든 문장. */
    public record UserBasis(Long messageId, String quote, String sentence) {
    }

    /** 근거로 인정하는 구절의 최소 글자 수(공백 제외). "네", "그래"처럼 어디에나 있는 말로 근거를 얻지 못하게 한다. */
    static final int MIN_QUOTE_CHARS = 3;
    /** 근거 구절 상한. 시험 안내처럼 한 줄이 긴 원문을 통째로 인용해도 확인할 수 있게 문장 상한과 같게 둔다. */
    static final int MAX_QUOTE_CHARS = 600;
    /** 근거 문장 상한. 넘으면 자르지 않고 근거로 인정하지 않는다 — 잘린 문장에서는 뒤쪽의 부정·조건이 사라질 수 있다. */
    static final int MAX_SENTENCE_CHARS = 600;

    /**
     * quote가 사용자 발언(최신부터)에 그대로 있는지 확인한다. 공백 차이만 무시한다 — 다른 말로 바꾼 구절은 근거가 아니다.
     * 있으면 그 메시지와, 구절이 든 문장(줄·문장 경계로 자른 전체)을 돌려준다.
     */
    public static UserBasis verifyQuote(String quote, List<UserUtterance> utterances) {
        if (quote == null || utterances == null || utterances.isEmpty()) {
            return null;
        }
        String q = quote.replaceAll("\\s+", " ").trim();
        if (q.replace(" ", "").length() < MIN_QUOTE_CHARS || q.length() > MAX_QUOTE_CHARS) {
            return null;
        }
        for (UserUtterance u : utterances) {
            if (u == null || u.messageId() == null || u.text() == null) {
                continue;
            }
            // 줄바꿈은 문장 경계로 남기고 그 밖의 공백만 접는다. 줄을 넘는 구절이면 줄바꿈까지 접어 한 번 더 찾는다.
            String lined = u.text().replace("\r", "").replaceAll("[ \\t\\x0B\\f]+", " ");
            int at = lined.indexOf(q);
            if (at < 0) {
                lined = lined.replace('\n', ' ').replaceAll(" +", " ");
                at = lined.indexOf(q);
            }
            if (at < 0) {
                continue;
            }
            String sentence = sentenceAround(lined, at, q.length());
            return sentence == null ? null : new UserBasis(u.messageId(), q, sentence);
        }
        return null;
    }

    /**
     * 근거로 인정할 사용자 발언(ADD·UPDATE 공통). quote가 사용자 발언에 그대로 있어야 하고, 지난 발언을 근거로 날짜 없는
     * 기간 합의를 만들거나 고치지 않는다 — 지난 발언의 "이번 주"는 그때의 주다(사용자가 다시 말해야 한다).
     *
     * @param scope 결과 항목의 적용 범위(UPDATE에서 범위를 말하지 않았으면 기존 항목의 범위)
     */
    static UserBasis admissibleBasis(PlanBriefOp op, String scope, Long userMessageId, List<UserUtterance> utterances) {
        UserBasis basis = verifyQuote(op.quote(), utterances);
        if (basis != null && PlanBriefItem.SCOPE_PERIOD.equals(scope) && !datesGiven(op)
                && !Objects.equals(basis.messageId(), userMessageId)) {
            return null;
        }
        return basis;
    }

    /**
     * 정리 문장이 근거 문장을 그대로 옮겼는가. 공백과 의미 없는 문장부호만 무시한다 — 범위·소수·부호·시각 기호(~ . - + / : %)는
     * 남긴다("1~2시간"과 "12시간", "1.5"와 "15"는 다르다).
     */
    public static boolean verbatim(String text, String sentence) {
        if (text == null || sentence == null) {
            return false;
        }
        String t = text.replaceAll(VERBATIM_IGNORED, "");
        String s = sentence.replaceAll(VERBATIM_IGNORED, "");
        if (t.isEmpty()) {
            return false;
        }
        // 수의 안쪽에서 시작하거나 끝나는 일치는 같은 말이 아니다("15시간" 속의 "5시간", "1.5" 속의 "5", "열두" 속의 "두").
        for (int at = s.indexOf(t); at >= 0; at = s.indexOf(t, at + 1)) {
            boolean cutBefore = SaidTime.insideNumber(s, at);
            int after = at + t.length();
            boolean cutAfter = after < s.length() && Character.isDigit(t.charAt(t.length() - 1)) && isNumberChar(s.charAt(after));
            if (!cutBefore && !cutAfter) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNumberChar(char c) {
        return Character.isDigit(c) || c == '.' || c == '/' || c == '~' || c == '-';
    }

    private static final String VERBATIM_IGNORED = "[\\s,!?;'\"“”‘’()\\[\\]{}<>·…。、]+";

    private static boolean datesGiven(PlanBriefOp op) {
        LocalDate start = parseDate(op.periodStart());
        LocalDate end = parseDate(op.periodEnd());
        return start != null && end != null && !end.isBefore(start);
    }

    /** 구절이 든 문장. 문장 끝(. ! ? 。 줄바꿈)으로 자른다. 상한보다 길면 null(근거로 쓰지 않는다). */
    static String sentenceAround(String text, int at, int length) {
        String stops = ".!?。\n";
        int start = at;
        while (start > 0 && stops.indexOf(text.charAt(start - 1)) < 0) {
            start--;
        }
        int end = at + length;
        while (end < text.length() && stops.indexOf(text.charAt(end)) < 0) {
            end++;
        }
        if (end < text.length() && text.charAt(end) != '\n') {
            end++;
        }
        String sentence = text.substring(start, end).trim();
        return sentence.length() <= MAX_SENTENCE_CHARS ? sentence : null;
    }

    /** 삭제되지 않은 항목 중 문장·적용 범위·과목이 같은 것. 없으면 -1. */
    private static int sameItem(List<PlanBriefItem> items, String text, String scope, LocalDate[] period, Long courseId) {
        for (int i = 0; i < items.size(); i++) {
            PlanBriefItem it = items.get(i);
            if (!it.removed() && it.text().equals(text) && it.scope().equals(scope)
                    && Objects.equals(it.courseId(), courseId)
                    && Objects.equals(it.periodStart(), period[0]) && Objects.equals(it.periodEnd(), period[1])) {
                return i;
            }
        }
        return -1;
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

    // ===== 쓸 수 있는 시간 =====

    /**
     * 꼬리표: "(쓸 수 있는 시간 60분 · 하루)", "(쓸 수 있는 시간 하루 · ~24:00)", "(쓸 수 있는 시간 240분 · 하루 · 20:00~24:00)".
     * 분과 시간대 중 하나는 있다. 시간대만 있으면 그 안에서 남는 시간이 쓸 수 있는 시간이다.
     */
    private static final java.util.regex.Pattern TIME_BUDGET_TAG = java.util.regex.Pattern.compile(
            "\\(쓸 수 있는 시간 (?:(\\d{1,4})분 · )?(계획 전체|하루)(?: · (\\d{2}:\\d{2})?~(\\d{2}:\\d{2}))?\\)$");

    /**
     * 사용자가 말한 시간. per가 DAY면 하루 기준이다.
     *
     * @param minutes 쓸 수 있는 분. 시간대만 말했으면 null(그 안에서 남는 시간을 쓴다)
     * @param from    공부를 시작할 수 있는 시각. 없으면 null
     * @param until   공부를 끝낼 시각(HH:mm, "24:00"은 자정). 없으면 null
     */
    public record TimeBudget(Integer minutes, boolean perDay, java.time.LocalTime from, String until) {
        public TimeBudget(int minutes, boolean perDay) {
            this(minutes, perDay, null, null);
        }

        /** 기간 전체로 환산한 분. 분을 말하지 않았으면 null. */
        public Integer totalFor(int days) {
            return minutes == null ? null : perDay ? minutes * Math.max(1, days) : minutes;
        }

        /** "몇 시부터·몇 시까지"를 말했는가. */
        public boolean hasWindow() {
            return from != null || until != null;
        }

        /** 그날 공부할 수 있는 구간의 시작. 시작을 말하지 않았으면 그날 0시(기본 창이 대신 자른다). */
        public java.time.LocalDateTime windowStartOn(LocalDate day) {
            return from == null ? day.atStartOfDay() : day.atTime(from);
        }

        /** 그날 공부할 수 있는 구간의 끝. 끝을 말하지 않았으면 다음 날 0시. */
        public java.time.LocalDateTime windowEndOn(LocalDate day) {
            return until == null || "24:00".equals(until) ? day.plusDays(1).atStartOfDay()
                    : day.atTime(java.time.LocalTime.parse(until));
        }

        /** "20:00~24:00"·"~24:00"처럼 사람이 읽는 시간대. 없으면 null. */
        public String windowLabel() {
            return hasWindow() ? (from == null ? "" : from.toString()) + "~" + (until == null ? "24:00" : until) : null;
        }
    }

    /** "HH:mm" 시각. until이면 24:00(자정)도 받는다. 형식이 틀리면 null. */
    public static String clockTime(String value, boolean until) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        if (until && "24:00".equals(v)) {
            return v;
        }
        try {
            java.time.LocalTime t = java.time.LocalTime.parse(v.length() == 4 ? "0" + v : v);
            if (until && t.equals(java.time.LocalTime.MIDNIGHT)) {
                return "24:00"; // "밤 12시까지"를 00:00으로 적었다
            }
            return t.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * TIME_BUDGET 합의의 분(minutes)과 시간대(from~until)를 문장 끝에 읽을 수 있는 꼬리표로 붙여 저장한다 — 합의 항목은 문장이
     * 원본이고, 같은 문장에서 서버가 값을 다시 읽는다(별도 필드를 두면 문장과 값이 어긋난 채 남을 수 있다). 사용자에게도 그대로 보인다.
     */
    static String withTimeBudget(String text, PlanBriefOp op) {
        if (text == null || !"TIME_BUDGET".equals(kindOf(op.kind())) || TIME_BUDGET_TAG.matcher(text).find()) {
            return text;
        }
        boolean minutesGiven = op.minutes() != null && op.minutes() >= 5 && op.minutes() <= 9999;
        String from = clockTime(op.from(), false);
        String until = clockTime(op.until(), true);
        if (from != null && until != null && !"24:00".equals(until) && from.compareTo(until) >= 0) {
            from = null; // 앞뒤가 뒤집힌 시간대는 끝만 믿는다
        }
        boolean windowGiven = from != null || until != null;
        if (!minutesGiven && !windowGiven) {
            return text;
        }
        // 시간대만 말했으면 "오늘 밤 12시까지"처럼 하루 기준이다.
        boolean perDay = "DAY".equalsIgnoreCase(op.per()) || !minutesGiven;
        String tagged = text + " (쓸 수 있는 시간 " + (minutesGiven ? op.minutes() + "분 · " : "")
                + (perDay ? "하루" : "계획 전체")
                + (windowGiven ? " · " + (from == null ? "" : from) + "~" + (until == null ? "24:00" : until) : "") + ")";
        return tagged.length() > MAX_TEXT_CHARS ? text : tagged;
    }

    /**
     * 생성 예산과 배치가 같이 쓰는 "쓸 수 있는 시간" 선택. 사용자가 말했거나 승인한 합의 → 범위 추출이 원문에서 확인한 시간
     * (scopeTime, 없으면 null) → 근거만 붙은 AI 정리 순.
     */
    public static TimeBudget chooseTimeBudget(List<PlanBriefItem> effective, TimeBudget scopeTime) {
        TimeBudget confirmed = confirmedTimeBudgetOf(effective);
        if (confirmed != null) {
            return confirmed;
        }
        return scopeTime != null ? scopeTime : timeBudgetOf(effective);
    }

    /** 사용자가 말했거나(작성자 USER) 승인한(accepted) "쓸 수 있는 시간"만. 여러 개면 가장 최근 것. 없으면 null. */
    public static TimeBudget confirmedTimeBudgetOf(List<PlanBriefItem> effective) {
        return timeBudgetOf(effective, true);
    }

    /** 유효한 합의 중 사용자가 말한 "쓸 수 있는 시간". 여러 개면 가장 최근 것. 없으면 null. */
    public static TimeBudget timeBudgetOf(List<PlanBriefItem> effective) {
        return timeBudgetOf(effective, false);
    }

    private static TimeBudget timeBudgetOf(List<PlanBriefItem> effective, boolean confirmedOnly) {
        TimeBudget found = null;
        for (PlanBriefItem item : effective == null ? List.<PlanBriefItem>of() : effective) {
            // 사용자가 말했거나 AI 제안을 승인했으면 사용자의 제약이다. 근거만 붙은 AI 정리는 값을 원문과 대조한 뒤에만.
            boolean userWords = PlanBriefItem.SPEAKER_USER.equals(item.speaker()) || item.accepted();
            if (!"TIME_BUDGET".equals(item.kind()) || !(userWords || (!confirmedOnly && item.grounded()))) {
                continue; // AI가 제안만 한 시간은 사용자의 제약이 아니다.
            }
            java.util.regex.Matcher m = TIME_BUDGET_TAG.matcher(item.text());
            if (m.find()) {
                TimeBudget candidate = new TimeBudget(m.group(1) == null ? null : Integer.parseInt(m.group(1)),
                        "하루".equals(m.group(2)), m.group(3) == null ? null : java.time.LocalTime.parse(m.group(3)), m.group(4));
                // AI가 정리한 항목은 근거가 있어도 시간 값은 모델이 적은 것이다 — 원문 문장에서 같은 값이 읽힐 때만 쓴다.
                if (userWords || timeValuesMatch(candidate, item.basisSentence())) {
                    found = candidate;
                }
            }
        }
        return found;
    }

    /** 정리된 시간 값(분·단위·시작·끝)이 사용자 원문 문장에서 그대로 읽히는가. 읽지 못하면 false — {@link SaidTime}. */
    public static boolean timeValuesMatch(TimeBudget budget, String sentence) {
        return SaidTime.matches(budget, sentence);
    }

    /**
     * {@link #timeValuesMatch}보다 엄격하다: 원문에서 읽히는 분량·시간대를 빠뜨리지 않았고, 오전·오후를 말하지 않은 시각은
     * 기준 시각 뒤의 가장 이른 시각이어야 한다(기준 시각이 없으면 애매한 시각은 일치가 아니다). {@link SaidTime#matchesFully}.
     */
    public static boolean timeValuesMatchFully(TimeBudget budget, String sentence, java.time.LocalTime reference) {
        return SaidTime.matchesFully(budget, sentence, reference);
    }

    /** 글에 시간 표현(지속 시간·시각)이 있는가. */
    public static boolean mentionsTime(String sentence) {
        return SaidTime.mentionsTime(sentence);
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
            case "DEPTH" -> "깊이(점검·복습·처음부터·문제 풀이)";
            case "TIME_BUDGET" -> "쓸 수 있는 시간";
            case "PURPOSE" -> "계획 목적(복습·수업 따라잡기·예습·시험 준비·독학)";
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
        if (item.accepted()) {
            return "[AI 제안 · 사용자 수락]";
        }
        return item.grounded() ? "[사용자 요청 · AI가 정리]" : "[AI 제안 · 아직 답 없음]";
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
        sb.append(PlanBriefItem.SPEAKER_USER.equals(item.speaker()) ? " (사용자가 말함"
                : item.accepted() ? " (AI 제안을 사용자가 수락함" : " (사용자 요청을 AI가 정리함");
        if (item.basisSentence() != null) {
            // 정리 문장이 원문의 "…까지"·"…빼지 마" 같은 경계를 잃을 수 있다 — 원문을 함께 싣고 원문이 기준이라고 둔다.
            sb.append(", 원문: \"").append(item.basisSentence()).append("\" — 정리와 다르면 원문이 기준");
        }
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
