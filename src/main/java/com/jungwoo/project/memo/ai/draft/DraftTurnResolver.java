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
import com.jungwoo.project.memo.plan.PeriodPlanDraftGenerator;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
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
 *    안전망: NEEDS_CONFIRM && ask_count ≥ 3 → READY 승격. NEEDS_INPUT은 승격 없음
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

    private DraftTurnResolver() {
    }

    /**
     * @param openDrafts          이 대화의 OPEN draft 전부(저장된 상태)
     * @param structured          모델 구조화 출력(null 가능)
     * @param modelReply          모델 자연어 reply
     * @param userMentionedFirst  이번 턴까지의 사용자 발화 원문 어딘가에 "첫"이 있는가
     * @param facts               resolver가 계산한 실제 데이터
     */
    public record Input(
            Long userId,
            Long conversationId,
            List<DraftState> openDrafts,
            AiTurnStructured structured,
            String modelReply,
            boolean userMentionedFirst,
            DraftFacts facts,
            ObjectMapper objectMapper
    ) {
    }

    /**
     * @param drafts          이번 턴에 저장해야 하는 draft(새 것·바뀐 것·취소된 것·승격 대상 전부)
     * @param proposeDrafts   이번 턴 PROPOSE 대상(ROUTINE/SCHEDULE → 후보 생성, PERIOD_PLAN → OFFER)
     * @param askDraft        질문 대상으로 선택된 draft(ask_count는 이미 +1 되어 있다). 없으면 null
     * @param serverReply     서버가 정한 reply. null이면 모델 reply를 그대로 쓴다(ASK에서 모델 질문이 적절할 때)
     * @param quickReplies    질문에 붙일 선택지
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
            List<String> notes
    ) {
        public static Outcome chat(List<DraftState> drafts, List<String> notes) {
            return new Outcome(drafts, Set.of(), Action.CHAT, false, List.of(), null, null, List.of(), notes);
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

        // 3~4. resolver 채움 + 강제 규칙 + missing + readiness
        for (DraftState draft : openTargets) {
            fillServerFields(draft, in.facts(), in.objectMapper());
            applyForcedRules(draft, in.userMentionedFirst());
            draft.setMissingRequired(DraftSlotRegistry.missingRequired(draft.getType(), draft.getFields(),
                    in.facts().anchorFacts()));
            validatePeriodPlan(draft, in.facts().today());
        }

        // 5. action
        List<DraftState> ready = new ArrayList<>();
        List<DraftState> pending = new ArrayList<>();
        for (DraftState draft : openTargets) {
            DraftReadiness readiness = draft.readiness();
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
                    && DraftProposalBuilder.build(draft, in.facts(), in.objectMapper()).isEmpty()) {
                notes.add("READY인데 만들 후보가 0건: #" + draft.refId());
                pending.add(draft);
            } else {
                proposable.add(draft);
            }
        }

        if (!proposable.isEmpty()) {
            StringBuilder reply = new StringBuilder(proposeSummary(proposable, in.facts(), in.objectMapper()));
            DraftState next = pickAskTarget(pending);
            List<String> quick = List.of();
            if (next != null) {
                next.setAskCount(next.getAskCount() + 1);
                Question question = questionFor(next, userTriggered);
                reply.append(' ').append(question.text());
                quick = question.quickReplies();
            }
            return new Outcome(changed, targets, Action.PROPOSE, userTriggered, proposable, next,
                    reply.toString(), quick, notes);
        }

        DraftState target = pickAskTarget(pending);
        if (target == null) {
            return Outcome.chat(changed, notes);
        }
        target.setAskCount(target.getAskCount() + 1);
        Question question = questionFor(target, userTriggered);
        String reply = !userTriggered && isQuestion(in.modelReply()) ? null : question.text();
        if (reply != null && !userTriggered) {
            notes.add("모델 reply가 질문이 아니라 서버 문구로 대체: #" + target.refId());
        }
        return new Outcome(changed, targets, Action.ASK, userTriggered, List.of(), target, reply,
                question.quickReplies(), notes);
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

    static Question questionFor(DraftState draft, boolean userTriggered) {
        List<String> missing = draft.getMissingRequired();
        if (!missing.isEmpty()) {
            String first = missing.get(0);
            String text = missingQuestion(draft, first);
            if (userTriggered) {
                text = USER_TRIGGERED_MISSING_TEMPLATE.formatted(draft.getLabel(), missingNoun(first)) + " " + text;
            }
            return new Question(text, quickRepliesForMissing(first));
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

    private static String missingQuestion(DraftState draft, String field) {
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
            case DraftSlotRegistry.DATE_RANGE -> draft.getLabel() + "은(는) 어느 기간의 근무 앞에 넣을까요?";
            case DraftSlotRegistry.MISSING_FIRST_CLASS_TIMES ->
                    "시간표에서 수업 시작 시각을 찾지 못했어요. 고정 시각으로 할까요? 몇 시부터가 좋을까요?";
            case DraftSlotRegistry.MISSING_WORK_SHIFTS ->
                    "앞으로 2주 안에 등록된 근무가 없어요. 근무 날짜와 시각을 알려주시면 그 앞에 넣을게요. 언제인가요?";
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
        return List.of();
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
            default -> field;
        };
    }

    // ---- PROPOSE 문구 ----

    static String proposeSummary(List<DraftState> proposable, DraftFacts facts, ObjectMapper om) {
        List<String> parts = new ArrayList<>();
        List<String> assumedNouns = new ArrayList<>();
        boolean periodPlan = false;
        for (DraftState draft : proposable) {
            if (draft.getType() == DraftType.CREATE_PERIOD_PLAN) {
                periodPlan = true;
            } else {
                List<ScheduleSuggestion> built = DraftProposalBuilder.build(draft, facts, om);
                Map<ScheduleSuggestionKind, Integer> counts = DraftProposalBuilder.countByKind(built);
                int total = counts.values().stream().mapToInt(Integer::intValue).sum();
                parts.add(draft.getLabel() + " 후보 " + total + "건");
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
