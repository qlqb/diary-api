package com.jungwoo.project.memo.ai.draft;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.jungwoo.project.memo.ai.domain.ScheduleSuggestionKind;
import com.jungwoo.project.memo.ai.draft.dto.DraftCreateSpec;
import com.jungwoo.project.memo.ai.draft.dto.DraftOp;
import com.jungwoo.project.memo.ai.draft.dto.DraftRouting;
import com.jungwoo.project.memo.ai.dto.AiTurnStructured;
import com.jungwoo.project.memo.ai.dto.ScheduleSuggestion;
import com.jungwoo.project.memo.ai.draft.resolver.UpcomingWorkShiftsResolver.WorkShift;
import com.jungwoo.project.memo.commitment.domain.DerivedTravelRelation;
import com.jungwoo.project.memo.plan.PeriodPlanDraftGenerator;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 한 턴의 서버 판정(B-4). 순수 자바 — DB도 Spring도 모른다. 입력은 OPEN draft·모델 출력·실제
 * 데이터이고, 출력은 "저장할 draft 상태 + 이번 턴 action + 서버 문구"다. 저장은
 * {@link DraftPromotionService}가 대화 잠금 안의 트랜잭션에서 한다.
 *
 * <pre>
 * 1. routing.create 처리(임시 id) → draftOps 적용(CANCEL 즉시, RETYPE는 잔재 삭제)
 * 2. effectiveTargets = routing.targets ∪ 이번 턴 생성 draft ∪ draftOps가 참조한 draft
 * 3. 대상 draft에 resolver 값 채움(DB/SYSTEM/DEFAULT) — USER/INFERRED는 덮지 않는다
 * 4. 서버 강제 확인 규칙 → missing_required → readiness
 * 5. action:
 *    userTriggered: missing 없는 대상 → PROPOSE(가정 표시), missing 있는 대상 → 서버 문구로 되물음
 *    그 외: READY 있으면 PROPOSE(READY만) + 남은 draft 질문 한 줄, 없으면 ASK(대상 하나만 선택)
 *    대상도 생성도 없음 → CHAT
 *    안전망: NEEDS_CONFIRM && ask_count ≥ 3 → READY 승격. NEEDS_INPUT은 승격 없음 —
 *    같은 누락으로 3번 이상 물었으면 같은 질문을 되풀이하지 않고 진전이 없다는 것을 말한다
 * 6. PROPOSE면 모델 reply 폐기, 서버 요약 문구. ASK인데 모델 reply가 질문이 아니면 서버 고정 문구
 * </pre>
 */
public final class DraftTurnResolver {

    public enum Action { ASK, PROPOSE, CHAT }

    /** 서버 고정 문구. 실패·미완료·이행률 계열 표현을 쓰지 않는다. */
    static final String PROPOSE_SUFFIX = " 검토 후 적용해 주세요.";
    static final String PERIOD_PLAN_OFFER_LINE = "기간 계획은 아래 버튼을 누르면 만들어져요.";
    static final String USER_TRIGGERED_MISSING_TEMPLATE = "%s은(는) %s만 알려주시면 이어서 만들게요.";
    public static final List<String> ANCHOR_CONFIRM_QUICK_REPLIES = List.of("첫 수업 전만", "수업 전마다");
    public static final List<String> YES_NO_QUICK_REPLIES = List.of("네", "아니요");
    public static final String CANCEL_REPLY = "취소";
    /** 같은 누락으로 막혀 있을 때 질문 앞에 붙는다. 무엇 때문에 멈췄는지 먼저 말한다. */
    static final String STALLED_PREFIX = "%s은(는) %s을(를) 아직 받지 못해서 계속 못 만들고 있어요.";
    static final String STALLED_SUFFIX = " 지금 정하기 어려우면 \"취소\"라고 하시면 이 요청은 접어 둘게요.";

    private static final DateTimeFormatter DATE_LABEL_FMT = DateTimeFormatter.ofPattern("M월 d일");
    private static final DateTimeFormatter DATE_TIME_LABEL_FMT = DateTimeFormatter.ofPattern("M월 d일 HH:mm");

    private DraftTurnResolver() {
    }

    /**
     * @param openDrafts          이 대화의 OPEN draft 전부(저장된 상태)
     * @param structured          모델 구조화 출력(null 가능)
     * @param modelReply          모델 자연어 reply
     * @param userMentionedFirst  이번 턴까지의 사용자 발화 원문 어딘가에 "첫"이 있는가
     * @param facts               LLM 호출 전 기본 기간으로 계산해 둔 실제 데이터. today의 출처이기도 하다
     * @param factsSource         요청 기간에 맞는 실제 데이터를 주는 조회기. null이면 facts 고정
     */
    public record Input(
            Long userId,
            Long conversationId,
            List<DraftState> openDrafts,
            AiTurnStructured structured,
            String modelReply,
            boolean userMentionedFirst,
            DraftFacts facts,
            DraftFactsSource factsSource,
            ObjectMapper objectMapper
    ) {
        public Input {
            if (factsSource == null) {
                factsSource = DraftFactsSource.fixed(facts);
            }
        }
    }

    /**
     * @param drafts          이번 턴에 저장해야 하는 draft(새 것·바뀐 것·취소된 것·승격 대상 전부)
     * @param proposeDrafts   이번 턴 PROPOSE 대상(ROUTINE/SCHEDULE → 후보 생성, PERIOD_PLAN → OFFER)
     * @param askDraft        질문 대상으로 선택된 draft(ask_count는 이미 +1 되어 있다). 없으면 null
     * @param serverReply     서버가 정한 reply. null이면 모델 reply를 그대로 쓴다(ASK에서 모델 질문이 적절할 때)
     * @param quickReplies    질문에 붙일 선택지
     * @param factsByDraft    draft마다 그 요청 기간으로 판정에 쓴 실제 데이터. 승격도 같은 값을 써야
     *                        한다 — 판정은 "이번 주"로 하고 생성은 기본 2주로 하면 말하지 않은
     *                        근무에도 블록이 붙는다. 키는 DraftState 동일성이다
     * @param notes           findings용 카운트(버린 op 등)
     */
    public record Outcome(
            List<DraftState> drafts,
            Set<String> effectiveTargets,
            Action action,
            boolean userTriggered,
            List<DraftState> proposeDrafts,
            DraftState askDraft,
            String serverReply,
            List<String> quickReplies,
            Map<DraftState, DraftFacts> factsByDraft,
            List<String> notes
    ) {
        public static Outcome chat(List<DraftState> drafts, List<String> notes) {
            return new Outcome(drafts, Set.of(), Action.CHAT, false, List.of(), null, null, List.of(),
                    Map.of(), notes);
        }

        /** 이 draft의 판정에 쓴 사실. 없으면 {@code fallback}(기간을 따지지 않는 경로). */
        public DraftFacts factsFor(DraftState draft, DraftFacts fallback) {
            DraftFacts resolved = factsByDraft.get(draft);
            return resolved != null ? resolved : fallback;
        }

        public boolean touchesDrafts() {
            return !drafts.isEmpty();
        }

        public DraftState periodPlanProposal() {
            for (DraftState draft : proposeDrafts) {
                if (draft.getType() == DraftType.CREATE_PERIOD_PLAN) {
                    return draft;
                }
            }
            return null;
        }
    }

    public static Outcome resolve(Input in) {
        List<String> notes = new ArrayList<>();
        List<DraftState> working = new ArrayList<>(in.openDrafts() != null ? in.openDrafts() : List.of());
        AiTurnStructured structured = in.structured();
        DraftRouting routing = structured != null ? structured.routing() : null;
        List<DraftOp> ops = structured != null && structured.draftOps() != null ? structured.draftOps() : List.of();
        boolean userTriggered = structured != null && Boolean.TRUE.equals(structured.userTriggered());

        // 1. create
        Set<String> touched = new LinkedHashSet<>();
        List<DraftState> created = new ArrayList<>();
        if (routing != null && routing.create() != null) {
            int index = 0;
            for (DraftCreateSpec spec : routing.create()) {
                if (spec == null || spec.draftType() == null) {
                    notes.add("create 무시: draftType 없음");
                    continue;
                }
                String tempId = "new-" + index++;
                String label = spec.label() != null && !spec.label().isBlank()
                        ? spec.label().strip() : defaultLabel(spec.draftType());
                DraftState draft = DraftState.newDraft(tempId, in.userId(), in.conversationId(), spec.draftType(),
                        label.length() > 100 ? label.substring(0, 100) : label);
                draft.setSameGroupAs(spec.sameGroupAs());
                working.add(draft);
                created.add(draft);
                touched.add(tempId);
            }
        }

        // 2. draftOps
        for (DraftOp op : ops) {
            if (op == null || op.op() == null) {
                continue;
            }
            DraftState draft = find(working, op.draftId());
            if (draft == null) {
                notes.add("존재하지 않는 draftId 참조: " + op.draftId());
                continue;
            }
            switch (op.op().toUpperCase()) {
                case DraftOp.CANCEL -> {
                    draft.setStatus(DraftStatus.CANCELLED);
                    touched.add(draft.refId());
                }
                case DraftOp.RETYPE -> {
                    if (op.draftType() == null) {
                        notes.add("RETYPE 무시: draftType 없음");
                        continue;
                    }
                    draft.retype(op.draftType());
                    touched.add(draft.refId());
                }
                case DraftOp.CLEAR -> {
                    if (op.field() != null) {
                        draft.getFields().remove(op.field());
                        touched.add(draft.refId());
                    }
                }
                case DraftOp.SET -> {
                    if (applySet(draft, op, notes)) {
                        touched.add(draft.refId());
                    }
                }
                default -> notes.add("알 수 없는 op: " + op.op());
            }
        }

        // effectiveTargets
        Set<String> targets = new LinkedHashSet<>();
        if (routing != null && routing.targets() != null) {
            for (Long id : routing.targets()) {
                DraftState draft = id != null ? find(working, String.valueOf(id)) : null;
                if (draft != null) {
                    targets.add(draft.refId());
                } else {
                    notes.add("존재하지 않는 targets 참조: " + id);
                }
            }
        }
        if (targets.isEmpty() && !touched.isEmpty() && (routing == null || routing.targets() == null
                || routing.targets().isEmpty())) {
            notes.add("targets 비어 있음 → draftOps/create 참조를 targets로 간주");
        }
        targets.addAll(touched);

        // 저장 대상: 이번 턴에 무엇이든 바뀐 draft(생성·op·resolver 갱신)
        List<DraftState> changed = new ArrayList<>();
        for (DraftState draft : working) {
            if (targets.contains(draft.refId())) {
                changed.add(draft);
            }
        }

        List<DraftState> openTargets = new ArrayList<>();
        for (DraftState draft : changed) {
            if (draft.isOpen()) {
                openTargets.add(draft);
            }
        }
        if (openTargets.isEmpty()) {
            return Outcome.chat(changed, notes);
        }

        /*
         * 이번 턴 전의 필수 누락. "같은 것을 또 묻고 있는가"를 판단하는 유일한 근거다 —
         * ask_count만 보면 사용자가 다른 값을 채워 넣어 진전이 있었던 턴까지 정체로 센다.
         */
        Map<DraftState, List<String>> previousMissing = new LinkedHashMap<>();
        for (DraftState draft : openTargets) {
            previousMissing.put(draft, List.copyOf(draft.getMissingRequired()));
        }

        // 3~4. resolver 채움 + 강제 규칙 + missing + readiness
        Map<DraftState, DraftFacts> factsByDraft = new LinkedHashMap<>();
        for (DraftState draft : openTargets) {
            fillServerFields(draft, in.facts(), in.objectMapper());
            applyForcedRules(draft, in.userMentionedFirst());
            /*
             * 이 draft가 말한 기간으로 사실을 다시 얻는다. dateRange가 기본 조회 기간 밖이면
             * 여기서 실제 조회가 한 번 더 일어나고, 같은 기간이면 재사용된다. 조회·필수 판정·
             * 후보 생성이 같은 값을 봐야 "이번 주"가 어디서는 2주로 새지 않는다.
             */
            DraftFacts facts = factsFor(draft, in);
            factsByDraft.put(draft, facts);
            draft.setMissingRequired(DraftSlotRegistry.missingRequired(draft.getType(), draft.getFields(),
                    anchorFacts(draft, facts)));
            validatePeriodPlan(draft, in.facts().today());
        }

        // 5. action
        List<DraftState> ready = new ArrayList<>();
        List<DraftState> pending = new ArrayList<>();
        for (DraftState draft : openTargets) {
            DraftReadiness readiness = draft.readiness();
            /*
             * 승격 안전망은 NEEDS_CONFIRM 전용이다. NEEDS_INPUT은 "확인만 받으면 되는 값"이
             * 아니라 "아직 없는 값"이라, 횟수로 밀어 올리면 서버가 날짜·시각을 지어내 일정을
             * 만들게 된다. 정체는 승격이 아니라 안내로 푼다(questionFor의 stalled).
             */
            boolean promoteByAskLimit = readiness == DraftReadiness.NEEDS_CONFIRM
                    && draft.getAskCount() >= DraftSlotRegistry.CONFIRM_ASK_LIMIT;
            if (userTriggered ? draft.getMissingRequired().isEmpty() : (readiness == DraftReadiness.READY || promoteByAskLimit)) {
                if (promoteByAskLimit) {
                    notes.add("ask_count 상한으로 READY 승격: #" + draft.refId());
                }
                ready.add(draft);
            } else {
                pending.add(draft);
            }
        }
        // 후보를 하나도 만들 수 없는 READY(anchor는 있는데 펼칠 데이터가 없는 경우)는 missing으로 되돌린다.
        List<DraftState> proposable = new ArrayList<>();
        for (DraftState draft : ready) {
            if (draft.getType() != DraftType.CREATE_PERIOD_PLAN
                    && DraftProposalBuilder.build(draft, factsByDraft.get(draft), in.objectMapper()).isEmpty()) {
                notes.add("READY인데 만들 후보가 0건: #" + draft.refId());
                pending.add(draft);
            } else {
                proposable.add(draft);
            }
        }

        if (!proposable.isEmpty()) {
            StringBuilder reply = new StringBuilder(proposeSummary(proposable, factsByDraft, in.objectMapper()));
            DraftState next = pickAskTarget(pending);
            List<String> quick = List.of();
            if (next != null) {
                next.setAskCount(next.getAskCount() + 1);
                Question question = questionFor(next, userTriggered, factsByDraft.get(next),
                        stalled(next, previousMissing));
                reply.append(' ').append(question.text());
                quick = question.quickReplies();
            }
            return new Outcome(changed, targets, Action.PROPOSE, userTriggered, proposable, next,
                    reply.toString(), quick, factsByDraft, notes);
        }

        DraftState target = pickAskTarget(pending);
        if (target == null) {
            return Outcome.chat(changed, notes);
        }
        boolean noProgress = stalled(target, previousMissing);
        if (noProgress) {
            notes.add("같은 필수 누락으로 진전 없음: #" + target.refId() + " " + target.getMissingRequired());
        }
        target.setAskCount(target.getAskCount() + 1);
        Question question = questionFor(target, userTriggered, factsByDraft.get(target), noProgress);
        /*
         * 모델 질문을 그대로 두는 조건이 "물음표가 있는가" 하나였다. 그러면 서버가 판정한
         * 누락과 무관한 되물음("종료 직후가 맞나요?")이 그대로 나가고, 사용자가 이미 답한 것을
         * 또 묻는다. 서버가 필수 누락을 들고 있는 동안에는 문구도 서버 것을 쓴다 — 지금 구조에서
         * 모델 질문이 그 누락을 정말 묻고 있는지 확인할 방법이 없고, 확인하려고 LLM을 한 번 더
         * 부르지는 않는다.
         */
        boolean keepModelReply = !userTriggered && isQuestion(in.modelReply())
                && target.getMissingRequired().isEmpty() && !noProgress;
        String reply = keepModelReply ? null : question.text();
        if (!keepModelReply && !userTriggered) {
            notes.add("모델 reply 대신 서버 문구 사용: #" + target.refId()
                    + " (누락=" + target.getMissingRequired() + ")");
        }
        return new Outcome(changed, targets, Action.ASK, userTriggered, List.of(), target, reply,
                question.quickReplies(), factsByDraft, notes);
    }

    // ---- SET 적용 ----

    private static boolean applySet(DraftState draft, DraftOp op, List<String> notes) {
        if (op.field() == null || op.field().isBlank()) {
            notes.add("SET 무시: field 없음");
            return false;
        }
        String field = op.field().strip();
        if (!DraftSlotRegistry.allowedFields(draft.getType()).contains(field)) {
            notes.add("SET 무시: " + draft.getType() + "에 없는 필드 " + field);
            return false;
        }
        FieldSource source = op.source() != null ? op.source() : FieldSource.INFERRED;
        if (!source.modelMayClaim()) {
            notes.add("SET 무시: 모델이 낼 수 없는 출처 " + source + " (" + field + ")");
            return false;
        }
        JsonNode value = op.value();
        if (value == null || value.isNull()) {
            draft.getFields().remove(field);
            return true;
        }
        if (DraftSlotRegistry.ANCHOR.equals(field) && !DraftSlotRegistry.isValidAnchor(draft.getType(), value.asText())) {
            notes.add("SET 무시: 유효하지 않은 anchor " + value.asText());
            return false;
        }
        boolean confirmationRequired = source == FieldSource.INFERRED && Boolean.TRUE.equals(op.confirmationRequired());
        draft.getFields().put(field, new DraftField(value, source, null, confirmationRequired));
        return true;
    }

    // ---- resolver 채움 ----

    static void fillServerFields(DraftState draft, DraftFacts facts, ObjectMapper om) {
        Map<String, DraftField> f = draft.getFields();
        if (absent(f, DraftSlotRegistry.TITLE) && draft.getType() != DraftType.CREATE_PERIOD_PLAN) {
            f.put(DraftSlotRegistry.TITLE, new DraftField(TextNode.valueOf(draft.getLabel()), FieldSource.DEFAULT,
                    DraftSlotRegistry.REASON_DRAFT_LABEL, false));
        }
        switch (draft.getType()) {
            case CREATE_ROUTINE -> {
                if (absent(f, DraftSlotRegistry.START_DATE)) {
                    f.put(DraftSlotRegistry.START_DATE, new DraftField(TextNode.valueOf(facts.today().toString()),
                            FieldSource.SYSTEM, DraftSlotRegistry.REASON_CURRENT_DATE, false));
                }
                if (absent(f, DraftSlotRegistry.END_DATE) && facts.semesterEnd().isPresent()) {
                    f.put(DraftSlotRegistry.END_DATE, new DraftField(TextNode.valueOf(facts.semesterEnd().get().toString()),
                            FieldSource.DB, DraftSlotRegistry.REASON_ACTIVE_SEMESTER_END, false));
                }
                if (!absent(f, DraftSlotRegistry.ANCHOR) && absent(f, DraftSlotRegistry.CLASS_DAYS_ONLY)) {
                    f.put(DraftSlotRegistry.CLASS_DAYS_ONLY, new DraftField(BooleanNode.TRUE, FieldSource.SYSTEM,
                            DraftSlotRegistry.REASON_ANCHOR_IMPLIES, false));
                }
            }
            case CREATE_SCHEDULE -> {
                if (!absent(f, DraftSlotRegistry.ANCHOR) && absent(f, DraftSlotRegistry.DATE_RANGE)) {
                    ObjectNode range = om.createObjectNode();
                    range.put("from", facts.today().toString());
                    range.put("to", facts.today().plusDays(DraftSlotRegistry.SCHEDULE_DEFAULT_RANGE_DAYS).toString());
                    f.put(DraftSlotRegistry.DATE_RANGE, new DraftField(range, FieldSource.DEFAULT,
                            DraftSlotRegistry.REASON_DEFAULT_RANGE, false));
                }
            }
            case CREATE_PERIOD_PLAN -> {
                // 기존 기간 계획 로직이 검증한다. 기본값을 채우지 않는다(강도는 묻는다).
            }
        }
    }

    static void applyForcedRules(DraftState draft, boolean userMentionedFirst) {
        for (Map.Entry<String, DraftField> entry : draft.getFields().entrySet()) {
            DraftField field = entry.getValue();
            boolean required = field.confirmationRequired();
            if (field.source() == FieldSource.USER || !field.source().modelMayClaim()) {
                required = false;
            }
            if (DraftSlotRegistry.forcedConfirmation(entry.getKey(), field, userMentionedFirst)) {
                required = true;
            }
            entry.setValue(field.withConfirmationRequired(required));
        }
    }

    /**
     * 이 draft가 대상으로 하는 기간의 사실. dateRange가 이미 조회한 기간 밖이면 다시 조회한다.
     *
     * <p>기간이 없는 draft(anchor 없는 1회성, 루틴, 기간 계획)는 기본 사실을 그대로 쓴다 —
     * 그것들은 근무 목록을 보지 않는다.
     */
    static DraftFacts factsFor(DraftState draft, Input in) {
        if (draft.getType() != DraftType.CREATE_SCHEDULE) {
            return in.facts();
        }
        String anchor = textField(draft, DraftSlotRegistry.ANCHOR);
        if (DraftProposalBuilder.relationOf(anchor) == null) {
            return in.facts();
        }
        DraftField field = draft.getFields().get(DraftSlotRegistry.DATE_RANGE);
        LocalDate[] range = field != null ? field.asDateRange() : null;
        if (range == null || range[1].isBefore(range[0])) {
            return in.facts();
        }
        return in.factsSource().forRange(range[0], range[1]);
    }

    /**
     * 요청 기간 기준의 anchor 사실.
     *
     * <p>기간으로 걸러서 센다. 무관한 기간에 근무가 있다는 이유로 요청 기간의 workShiftsKnown을
     * true로 만들면, 그 기간의 후보가 0건인데도 READY가 되어 "만들었어요"라고 말하게 된다.
     *
     * <p>종료 시각 누락은 <b>뒤쪽 이동일 때만</b> 본다. 근무 앞 이동은 근무 시작 시각만 쓰므로
     * 종료가 비어 있어도 계산에 지장이 없다.
     */
    static DraftSlotRegistry.AnchorFacts anchorFacts(DraftState draft, DraftFacts facts) {
        boolean firstClass = !facts.firstClassByDay().isEmpty();
        if (draft.getType() != DraftType.CREATE_SCHEDULE) {
            return new DraftSlotRegistry.AnchorFacts(firstClass, !facts.workShifts().isEmpty(),
                    facts.workLookupFailed(), false);
        }
        DerivedTravelRelation relation = DraftProposalBuilder.relationOf(
                textField(draft, DraftSlotRegistry.ANCHOR));
        LocalDate[] range = DraftProposalBuilder.range(draft, facts);
        List<WorkShift> usable = facts.workShiftsIn(range[0], range[1]);
        List<WorkShift> incomplete = facts.incompleteShiftsIn(range[0], range[1]);
        boolean endMatters = relation == DerivedTravelRelation.AFTER_WORK;
        return new DraftSlotRegistry.AnchorFacts(firstClass,
                !usable.isEmpty() || (!endMatters && !incomplete.isEmpty()),
                facts.workLookupFailed(),
                endMatters && !incomplete.isEmpty());
    }

    /**
     * 이번 턴에도 지난 턴과 <b>같은</b> 필수 누락으로 막혀 있고, 그 상태로 이미 상한만큼 물었는가.
     *
     * <p>ask_count만으로 판단하지 않는다. 사용자가 다른 값을 채워 넣어 누락 목록이 바뀌었으면
     * 진전이 있었던 것이고, 그때는 평소대로 남은 것을 물어야 한다.
     */
    static boolean stalled(DraftState draft, Map<DraftState, List<String>> previousMissing) {
        List<String> now = draft.getMissingRequired();
        if (now.isEmpty()) {
            return false;
        }
        List<String> before = previousMissing.get(draft);
        return before != null && before.equals(now)
                && draft.getAskCount() >= DraftSlotRegistry.STALLED_ASK_LIMIT;
    }

    private static String textField(DraftState draft, String name) {
        DraftField field = draft.getFields().get(name);
        return field != null ? field.asText() : null;
    }

    /** 기간 계획은 기존 규칙(시작≤종료, 최대 31일, 전부 지나지 않음)을 어기면 날짜를 다시 묻는다. */
    private static void validatePeriodPlan(DraftState draft, LocalDate today) {
        if (draft.getType() != DraftType.CREATE_PERIOD_PLAN) {
            return;
        }
        DraftField startField = draft.getFields().get(DraftSlotRegistry.START_DATE);
        DraftField endField = draft.getFields().get(DraftSlotRegistry.END_DATE);
        LocalDate start = startField != null ? startField.asLocalDate() : null;
        LocalDate end = endField != null ? endField.asLocalDate() : null;
        if (start == null || end == null) {
            return;
        }
        boolean valid = !end.isBefore(start) && !end.isBefore(today)
                && ChronoUnit.DAYS.between(start, end) + 1 <= PeriodPlanDraftGenerator.MAX_PLAN_DAYS;
        if (!valid && !draft.getMissingRequired().contains(DraftSlotRegistry.END_DATE)) {
            draft.getMissingRequired().add(DraftSlotRegistry.END_DATE);
        }
    }

    // ---- 질문 선택 ----

    /**
     * 질문 대상 하나: NEEDS_INPUT → NEEDS_CONFIRM 순, 같으면 created_at이 빠른 것(새 draft는 뒤).
     * ask_count는 여기서 고른 draft에만 +1한다.
     */
    static DraftState pickAskTarget(List<DraftState> pending) {
        return pending.stream()
                .sorted(Comparator
                        .comparing((DraftState d) -> d.readiness() == DraftReadiness.NEEDS_INPUT ? 0 : 1)
                        .thenComparing(d -> d.getCreatedAt() == null ? 1 : 0)
                        .thenComparing(d -> d.getCreatedAt() != null ? d.getCreatedAt() : java.time.LocalDateTime.MAX)
                        .thenComparing(d -> d.getDraftId() != null ? d.getDraftId() : Long.MAX_VALUE))
                .findFirst()
                .orElse(null);
    }

    record Question(String text, List<String> quickReplies) {
    }

    static Question questionFor(DraftState draft, boolean userTriggered, DraftFacts facts, boolean stalled) {
        List<String> missing = draft.getMissingRequired();
        if (!missing.isEmpty()) {
            String first = missing.get(0);
            String text = missingQuestion(draft, first, facts);
            List<String> quick = quickRepliesForMissing(first);
            if (stalled) {
                /*
                 * 같은 것을 또 묻고 있다. 말만 바꿔 되풀이하는 것은 해결이 아니므로, 무엇 때문에
                 * 멈춰 있는지 먼저 말하고 빠져나갈 길을 준다. draft는 OPEN으로 남는다 — 나중에
                 * 값을 주면 이어서 만들 수 있어야 한다.
                 */
                text = STALLED_PREFIX.formatted(draft.getLabel(), missingNoun(first)) + " " + text
                        + STALLED_SUFFIX;
                quick = withCancel(quick);
            } else if (userTriggered && DraftSlotRegistry.userCanAnswer(first)) {
                text = USER_TRIGGERED_MISSING_TEMPLATE.formatted(draft.getLabel(), missingNoun(first)) + " " + text;
            }
            return new Question(text, quick);
        }
        Question allCovered = allCoveredQuestion(draft, facts);
        if (allCovered != null) {
            return allCovered;
        }
        List<String> unconfirmed = draft.unconfirmedFields();
        if (unconfirmed.isEmpty()) {
            return new Question(draft.getLabel() + "을(를) 이대로 만들까요?", YES_NO_QUICK_REPLIES);
        }
        String field = unconfirmed.get(0);
        DraftField value = draft.getFields().get(field);
        if (DraftSlotRegistry.ANCHOR.equals(field)
                && DraftSlotRegistry.ANCHOR_FIRST_CLASS_OF_DAY.equals(value.asText())) {
            return new Question("수업 전마다요, 아니면 그날 첫 수업 전만요?", ANCHOR_CONFIRM_QUICK_REPLIES);
        }
        if (DraftSlotRegistry.END_DATE.equals(field)) {
            return new Question(value.asText() + "까지로 하면 될까요?", YES_NO_QUICK_REPLIES);
        }
        return new Question(draft.getLabel() + "의 " + fieldNoun(field) + "을(를) " + value.asText()
                + "(으)로 하면 될까요?", YES_NO_QUICK_REPLIES);
    }

    private static String missingQuestion(DraftState draft, String field, DraftFacts facts) {
        return switch (field) {
            case DraftSlotRegistry.DURATION_MINUTES -> draft.getLabel() + "은(는) 몇 분짜리로 할까요?";
            case DraftSlotRegistry.END_DATE -> draft.getType() == DraftType.CREATE_PERIOD_PLAN
                    ? "어느 기간의 계획을 만들까요? 실제 날짜로 말해주세요."
                    : draft.getLabel() + "은(는) 언제까지 반복할까요? 날짜로 알려주세요.";
            case DraftSlotRegistry.START_DATE -> draft.getType() == DraftType.CREATE_PERIOD_PLAN
                    ? "어느 기간의 계획을 만들까요? 실제 날짜로 말해주세요."
                    : draft.getLabel() + "은(는) 언제부터 시작할까요?";
            case DraftSlotRegistry.DAYS_OF_WEEK, DraftSlotRegistry.START_TIME ->
                    draft.getType() == DraftType.CREATE_ROUTINE
                            ? draft.getLabel() + "은(는) 어느 요일 몇 시에 넣을까요? 아니면 그날 첫 수업 전으로 할까요?"
                            : draft.getLabel() + "은(는) 어느 날 몇 시에 넣을까요?";
            case DraftSlotRegistry.DATE -> draft.getLabel() + "은(는) 어느 날 몇 시에 넣을까요?";
            case DraftSlotRegistry.DATE_RANGE ->
                    draft.getLabel() + "은(는) 어느 기간의 근무에 넣을까요?";
            case DraftSlotRegistry.MISSING_FIRST_CLASS_TIMES ->
                    "시간표에서 수업 시작 시각을 찾지 못했어요. 고정 시각으로 할까요? 몇 시부터가 좋을까요?";
            // 기간을 문구에 박아 넣는다. "2주 안에 없다"고만 하면 사용자가 말한 기간을 봤는지 알 수 없다.
            case DraftSlotRegistry.MISSING_WORK_SHIFTS -> rangeText(draft, facts)
                    + "에 등록된 근무가 없어요. 근무 날짜와 시각을 알려주시면 그때 " + draft.getLabel()
                    + "을(를) 넣을게요. 언제인가요?";
            /*
             * 조회 실패는 사용자 정보 부족이 아니다. "언제인가요?"로 물으면 사용자는 이미 등록해
             * 둔 근무를 앱이 못 본다는 사실을 모른 채 같은 말을 반복하게 된다.
             */
            case DraftSlotRegistry.MISSING_WORK_LOOKUP ->
                    "근무 일정을 불러오지 못해서 " + draft.getLabel()
                            + "을(를) 아직 만들지 못했어요. 등록된 근무가 없다는 뜻은 아니에요. 다시 시도할까요?";
            // 어느 근무인지 지정한다. "근무가 몇 시에 끝나나요"는 이미 실패한 질문이다.
            case DraftSlotRegistry.MISSING_WORK_SHIFT_END ->
                    incompleteShiftText(draft, facts) + " 종료 시각이 없어서 그 뒤에 "
                            + draft.getLabel() + "을(를) 붙일 수 없어요. 그 근무는 몇 시에 끝나나요?";
            case DraftSlotRegistry.TITLE -> "이 일정을 뭐라고 부를까요?";
            case DraftSlotRegistry.INTENSITY ->
                    "이번 기간의 남는 시간 중 어느 정도를 공부로 채울까요? 가볍게 / 보통 / 집중 중에 골라주세요.";
            default -> draft.getLabel() + "의 " + fieldNoun(field) + "을(를) 알려주시겠어요?";
        };
    }

    private static List<String> quickRepliesForMissing(String field) {
        if (DraftSlotRegistry.INTENSITY.equals(field)) {
            return List.of("가볍게", "보통", "집중");
        }
        if (DraftSlotRegistry.MISSING_WORK_LOOKUP.equals(field)) {
            return List.of("다시 시도", "취소");
        }
        return List.of();
    }

    /** 정체 안내에는 빠져나갈 길을 함께 준다. draft는 취소하기 전까지 OPEN으로 남는다. */
    private static List<String> withCancel(List<String> quick) {
        if (quick.contains(CANCEL_REPLY)) {
            return quick;
        }
        List<String> merged = new ArrayList<>(quick);
        merged.add(CANCEL_REPLY);
        return List.copyOf(merged);
    }

    /** 이 draft가 대상으로 하는 기간을 사람이 읽는 문구로. */
    private static String rangeText(DraftState draft, DraftFacts facts) {
        if (facts == null) {
            return "요청한 기간";
        }
        LocalDate[] range = DraftProposalBuilder.range(draft, facts);
        return range[0].format(DATE_LABEL_FMT) + "~" + range[1].format(DATE_LABEL_FMT);
    }

    /** 종료 시각을 못 쓰는 근무를 지정하는 문구. 최대 세 건까지 적는다. */
    private static String incompleteShiftText(DraftState draft, DraftFacts facts) {
        if (facts == null) {
            return "등록된 근무 중 일부는";
        }
        LocalDate[] range = DraftProposalBuilder.range(draft, facts);
        List<WorkShift> incomplete = facts.incompleteShiftsIn(range[0], range[1]);
        if (incomplete.isEmpty()) {
            return "등록된 근무 중 일부는";
        }
        List<String> parts = new ArrayList<>();
        for (WorkShift shift : incomplete) {
            if (parts.size() == 3) {
                parts.add("외 " + (incomplete.size() - 3) + "건");
                break;
            }
            parts.add(shift.startAt().format(DATE_TIME_LABEL_FMT) + " 근무");
        }
        return String.join(", ", parts) + "는";
    }

    /**
     * 필수 누락도 없고 확인할 것도 없는데 만들 후보가 0건인 경우. 그 기간 근무에 이미 같은
     * 이동이 다 붙어 있다는 뜻이다. "이대로 만들까요?"라고 물으면 눌러도 아무것도 안 생긴다.
     */
    private static Question allCoveredQuestion(DraftState draft, DraftFacts facts) {
        if (draft.getType() != DraftType.CREATE_SCHEDULE || facts == null) {
            return null;
        }
        DerivedTravelRelation relation = DraftProposalBuilder.relationOf(
                textField(draft, DraftSlotRegistry.ANCHOR));
        if (relation == null) {
            return null;
        }
        LocalDate[] range = DraftProposalBuilder.range(draft, facts);
        List<WorkShift> shifts = facts.workShiftsIn(range[0], range[1]);
        if (shifts.isEmpty()) {
            return null;
        }
        for (WorkShift shift : shifts) {
            if (!facts.alreadyCovered(shift.commitmentId(), relation)) {
                return null;
            }
        }
        /*
         * 기존 항목을 조용히 바꾸지 않는다. 길이가 달라졌으면 그것은 "수정"이지 "추가"가
         * 아니고, 어느 쪽인지는 사용자만 안다.
         */
        return new Question(rangeText(draft, facts) + " 근무에는 " + draft.getLabel()
                + "이(가) 이미 다 들어가 있어요. 기존 것을 그대로 둘까요, 길이를 바꿔 다시 만들까요?",
                List.of("그대로 둘게요", "길이 바꿀게요"));
    }

    static String missingNoun(String field) {
        return fieldNoun(field);
    }

    static String fieldNoun(String field) {
        return switch (field) {
            case DraftSlotRegistry.TITLE -> "이름";
            case DraftSlotRegistry.ANCHOR -> "기준";
            case DraftSlotRegistry.DAYS_OF_WEEK -> "요일";
            case DraftSlotRegistry.START_TIME -> "시작 시각";
            case DraftSlotRegistry.DURATION_MINUTES -> "길이";
            case DraftSlotRegistry.START_DATE -> "시작일";
            case DraftSlotRegistry.END_DATE -> "종료일";
            case DraftSlotRegistry.DATE -> "날짜";
            case DraftSlotRegistry.DATE_RANGE -> "기간";
            case DraftSlotRegistry.INTENSITY -> "강도";
            case DraftSlotRegistry.MISSING_FIRST_CLASS_TIMES -> "수업 시작 시각";
            case DraftSlotRegistry.MISSING_WORK_SHIFTS -> "근무 일정";
            case DraftSlotRegistry.MISSING_WORK_SHIFT_END -> "근무 종료 시각";
            case DraftSlotRegistry.MISSING_WORK_LOOKUP -> "근무 조회";
            default -> field;
        };
    }

    // ---- PROPOSE 문구 ----

    static String proposeSummary(List<DraftState> proposable, Map<DraftState, DraftFacts> factsByDraft,
                                ObjectMapper om) {
        List<String> parts = new ArrayList<>();
        List<String> assumedNouns = new ArrayList<>();
        int skipped = 0;
        boolean periodPlan = false;
        for (DraftState draft : proposable) {
            DraftFacts facts = factsByDraft.get(draft);
            if (draft.getType() == DraftType.CREATE_PERIOD_PLAN) {
                periodPlan = true;
            } else {
                List<ScheduleSuggestion> built = DraftProposalBuilder.build(draft, facts, om);
                Map<ScheduleSuggestionKind, Integer> counts = DraftProposalBuilder.countByKind(built);
                int total = counts.values().stream().mapToInt(Integer::intValue).sum();
                parts.add(draft.getLabel() + " 후보 " + total + "건");
                skipped += DraftProposalBuilder.skippedAsCovered(draft, facts);
            }
            for (String field : draft.unconfirmedFields()) {
                String noun = draft.getLabel() + "의 " + fieldNoun(field);
                if (!assumedNouns.contains(noun)) {
                    assumedNouns.add(noun);
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        if (!parts.isEmpty()) {
            sb.append(String.join("과 ", parts)).append("을(를) 만들었어요.").append(PROPOSE_SUFFIX);
        }
        if (periodPlan) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(PERIOD_PLAN_OFFER_LINE);
        }
        if (skipped > 0) {
            // 조용히 건너뛰지 않는다. 사용자가 "모든 근무"라고 했는데 건수가 적으면 이유를 알아야 한다.
            sb.append(" 이미 같은 이동이 있는 근무 ").append(skipped).append("건은 빼고 만들었어요.");
        }
        if (!assumedNouns.isEmpty()) {
            sb.append(" 확인 없이 넣은 값이 있어요: ").append(String.join(", ", assumedNouns)).append('.');
        }
        return sb.toString();
    }

    // ---- 유틸 ----

    static boolean isQuestion(String reply) {
        return reply != null && reply.contains("?");
    }

    private static boolean absent(Map<String, DraftField> fields, String name) {
        DraftField field = fields.get(name);
        return field == null || field.isNull();
    }

    private static DraftState find(List<DraftState> drafts, String ref) {
        if (ref == null) {
            return null;
        }
        for (DraftState draft : drafts) {
            if (draft.matches(ref)) {
                return draft;
            }
        }
        return null;
    }

    static String defaultLabel(DraftType type) {
        return switch (type) {
            case CREATE_ROUTINE -> "반복 일정";
            case CREATE_SCHEDULE -> "일회성 일정";
            case CREATE_PERIOD_PLAN -> "기간 계획";
        };
    }
}
