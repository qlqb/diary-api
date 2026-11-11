package com.jungwoo.project.memo.ai.draft;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jungwoo.project.memo.ai.domain.ScheduleSuggestionKind;
import com.jungwoo.project.memo.ai.draft.resolver.UpcomingWorkShiftsResolver.WorkShift;
import com.jungwoo.project.memo.ai.dto.ScheduleSuggestion;
import com.jungwoo.project.memo.commitment.domain.DerivedTravelRelation;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * READY draft → proposal payload. 순수 자바(ObjectMapper만 받는다).
 *
 * <ul>
 *   <li>anchor=FIRST_CLASS_OF_DAY: 요일마다 ROUTINE 1건. 시작 = 첫 수업 − durationMinutes, 종료 = 첫 수업.
 *       요일 4개면 4건이다 — 하나로 합치지 않는다. payload는 {@code RoutineSaveRequest} 필드명 그대로.
 *   <li>anchor=BEFORE_EACH_WORK_SHIFT: 근무 1건당 COMMITMENT 1건. 시작 = 근무 시작 − durationMinutes,
 *       종료 = 근무 시작. payload는 {@code CommitmentCreateRequest}.
 *   <li>anchor=AFTER_EACH_WORK_SHIFT: 근무 1건당 COMMITMENT 1건. 시작 = 근무 <b>종료</b>,
 *       종료 = 근무 종료 + durationMinutes. 날짜가 있는 LocalDateTime으로 더하므로 23:00 + 60분은
 *       다음 날 00:00이다 — 같은 날 00:00으로 접거나 길이를 줄이지 않는다.
 *   <li>anchor 없이 요일+시각(ROUTINE) / 날짜+시각(SCHEDULE)이면 1건.
 *   <li>CREATE_PERIOD_PLAN은 후보를 만들지 않는다(기존 기간 계획 OFFER로 간다).
 * </ul>
 *
 * <p>payload에 두 키를 더 넣는다. 뜻이 다르다.
 * <ul>
 *   <li>{@code assumedFields}: 확인되지 않은 추측. confirmationRequired=true인 채로 PROPOSE된 필드만.
 *   <li>{@code fieldNotes}: 서버가 규칙대로 채운 값(DB/SYSTEM/DEFAULT). 사용자가 알아야 하지만 추측이 아니다.
 * </ul>
 * 두 키는 저장 요청 DTO로 매핑되지 않는다 — {@code ScheduleSuggestionService}가 읽기 전에 떼어낸다.
 */
public final class DraftProposalBuilder {

    public static final String ASSUMED_FIELDS_KEY = "assumedFields";
    public static final String FIELD_NOTES_KEY = "fieldNotes";

    /**
     * 이 후보가 어떤 근무에서 파생됐는가. {@code {"commitmentId":528,"relation":"AFTER_WORK"}}.
     *
     * <p>화면 장식이 아니다. 적용 시점에 {@code ScheduleSuggestionService}가 <b>저장된</b>
     * payload에서 이 값을 읽어 one_off_commitments의 파생 컬럼으로 넘긴다. 사용자가 카드에서
     * 고친 payload에서는 읽지 않는다 — 원본 참조는 서버가 정한 사실이라 사용자가 바꿀 값이 아니다.
     */
    public static final String DERIVED_FROM_KEY = "derivedFrom";
    public static final String DERIVED_COMMITMENT_ID = "commitmentId";
    public static final String DERIVED_RELATION = "relation";
    /** 후보를 계산할 때 기준으로 삼은 원본의 시각. 적용 시점에 원본이 바뀌었는지 이것으로 안다. */
    public static final String DERIVED_ANCHOR_AT = "anchorAt";

    private DraftProposalBuilder() {
    }

    /** 이 draft로 만들 후보 수. 0이면 PROPOSE할 수 없다(근무 없음 등). */
    public static int expectedCount(DraftState draft, DraftFacts facts) {
        return build(draft, facts, new ObjectMapper()).size();
    }

    public static List<ScheduleSuggestion> build(DraftState draft, DraftFacts facts, ObjectMapper om) {
        List<ScheduleSuggestion> result = new ArrayList<>();
        Map<String, DraftField> f = draft.getFields();
        String title = text(f, DraftSlotRegistry.TITLE);
        Integer duration = intValue(f, DraftSlotRegistry.DURATION_MINUTES);
        if (title == null || duration == null || duration <= 0) {
            return result;
        }
        ArrayNode assumed = annotations(draft, om, true);
        ArrayNode notes = annotations(draft, om, false);

        switch (draft.getType()) {
            case CREATE_ROUTINE -> {
                LocalDate from = date(f, DraftSlotRegistry.START_DATE);
                LocalDate until = date(f, DraftSlotRegistry.END_DATE);
                if (from == null) {
                    return result;
                }
                String anchor = text(f, DraftSlotRegistry.ANCHOR);
                if (DraftSlotRegistry.ANCHOR_FIRST_CLASS_OF_DAY.equals(anchor)) {
                    for (DayOfWeek day : DayOfWeek.values()) {
                        LocalTime classStart = facts.firstClassByDay().get(day);
                        if (classStart == null) {
                            continue;
                        }
                        result.add(routine(om, title, List.of(day), classStart.minusMinutes(duration), classStart,
                                from, until, assumed, notes));
                    }
                } else {
                    List<DayOfWeek> days = f.containsKey(DraftSlotRegistry.DAYS_OF_WEEK)
                            ? f.get(DraftSlotRegistry.DAYS_OF_WEEK).asDaysOfWeek() : List.of();
                    LocalTime start = time(f, DraftSlotRegistry.START_TIME);
                    if (days.isEmpty() || start == null) {
                        return result;
                    }
                    result.add(routine(om, title, days, start, start.plusMinutes(duration), from, until, assumed, notes));
                }
            }
            case CREATE_SCHEDULE -> {
                String anchor = text(f, DraftSlotRegistry.ANCHOR);
                DerivedTravelRelation relation = relationOf(anchor);
                if (relation != null) {
                    result.addAll(plan(draft, facts, om).create());
                } else {
                    LocalDate day = date(f, DraftSlotRegistry.DATE);
                    LocalTime start = time(f, DraftSlotRegistry.START_TIME);
                    if (day == null || start == null) {
                        return result;
                    }
                    LocalDateTime startAt = LocalDateTime.of(day, start);
                    result.add(commitment(om, title, startAt, startAt.plusMinutes(duration), assumed, notes,
                            null, null, null));
                }
            }
            case CREATE_PERIOD_PLAN -> {
                // 기존 기간 계획 OFFER 경로. 여기서 후보를 만들지 않는다.
            }
        }
        return result;
    }

    /** confirmationRequired=true(assumed) 또는 DB/SYSTEM/DEFAULT 출처(notes)인 필드 목록. */
    static ArrayNode annotations(DraftState draft, ObjectMapper om, boolean assumed) {
        ArrayNode array = om.createArrayNode();
        for (Map.Entry<String, DraftField> entry : draft.getFields().entrySet()) {
            DraftField field = entry.getValue();
            if (assumed) {
                if (field.confirmationRequired()) {
                    array.addObject().put("field", entry.getKey()).put("reason", DraftSlotRegistry.REASON_UNCONFIRMED);
                }
            } else if (!field.source().modelMayClaim()) {
                array.addObject().put("field", entry.getKey())
                        .put("reason", field.reason() != null ? field.reason() : field.source().name());
            }
        }
        return array;
    }

    private static ScheduleSuggestion routine(ObjectMapper om, String title, List<DayOfWeek> days,
                                              LocalTime start, LocalTime end, LocalDate from, LocalDate until,
                                              ArrayNode assumed, ArrayNode notes) {
        ObjectNode payload = om.createObjectNode();
        payload.putNull("courseId");
        payload.put("title", title);
        payload.putNull("location");
        ArrayNode dayArray = payload.putArray("daysOfWeek");
        days.forEach(d -> dayArray.add(d.name()));
        payload.put("startTime", start.toString());
        payload.put("endTime", end.toString());
        payload.put("effectiveFrom", from.toString());
        if (until != null) {
            payload.put("effectiveUntil", until.toString());
        } else {
            payload.putNull("effectiveUntil");
        }
        payload.set(ASSUMED_FIELDS_KEY, assumed.deepCopy());
        payload.set(FIELD_NOTES_KEY, notes.deepCopy());
        return new ScheduleSuggestion(ScheduleSuggestionKind.ROUTINE, payload);
    }

    private static ScheduleSuggestion commitment(ObjectMapper om, String title, LocalDateTime startAt,
                                                 LocalDateTime endAt, ArrayNode assumed, ArrayNode notes,
                                                 Long originCommitmentId, DerivedTravelRelation relation,
                                                 LocalDateTime anchorAt) {
        ObjectNode payload = om.createObjectNode();
        payload.put("title", title);
        payload.put("startAt", startAt.toString());
        payload.put("endAt", endAt.toString());
        payload.putNull("locationText");
        payload.set(ASSUMED_FIELDS_KEY, assumed.deepCopy());
        payload.set(FIELD_NOTES_KEY, notes.deepCopy());
        if (originCommitmentId != null && relation != null) {
            ObjectNode derived = payload.putObject(DERIVED_FROM_KEY);
            derived.put(DERIVED_COMMITMENT_ID, originCommitmentId);
            derived.put(DERIVED_RELATION, relation.name());
            if (anchorAt != null) {
                derived.put(DERIVED_ANCHOR_AT, anchorAt.toString());
            }
        }
        return new ScheduleSuggestion(ScheduleSuggestionKind.COMMITMENT, payload);
    }

    /**
     * 근무 기준 이동 요청 하나를 근무별로 분류한 결과.
     *
     * <p>"만들 것"만 돌려주면 나머지가 왜 빠졌는지 말할 수 없다. 사용자가 "모든 근무 후에"라고
     * 했는데 후보가 2건이면, 남은 근무가 이미 같은 이동을 갖고 있어서인지 길이가 달라서인지는
     * 전혀 다른 이야기다. 전자는 할 일이 없고 후자는 사용자가 고쳐야 한다.
     *
     * @param create    새로 만들 후보
     * @param unchanged 요청과 구간이 같은 이동이 이미 있는 근무. 다시 만들지 않는다
     * @param conflicts 같은 근무·방향에 이동이 있지만 구간이 다른 것. 수정이 필요하다
     */
    public record TravelPlan(List<ScheduleSuggestion> create,
                             List<ExistingTravel> unchanged,
                             List<TravelConflict> conflicts) {
        public static final TravelPlan EMPTY = new TravelPlan(List.of(), List.of(), List.of());

        public boolean hasExisting() {
            return !unchanged.isEmpty() || !conflicts.isEmpty();
        }
    }

    /**
     * 기존 이동이 있는데 요청과 구간이 다른 근무.
     *
     * @param shift       원본 근무
     * @param existing    이미 있는 이동
     * @param wantedStart 이번 요청이 만들려던 시작
     * @param wantedEnd   이번 요청이 만들려던 종료
     */
    public record TravelConflict(WorkShift shift, ExistingTravel existing,
                                 LocalDateTime wantedStart, LocalDateTime wantedEnd) {
        public long wantedMinutes() {
            return java.time.Duration.between(wantedStart, wantedEnd).toMinutes();
        }
    }

    /**
     * 근무 기준 이동 요청을 근무별로 분류한다.
     *
     * <p>대상 근무 집합이 방향에 따라 다르다. 근무 <b>앞</b> 이동은 근무 시작 시각만 쓰므로
     * 종료 시각이 이상한 근무에도 만들 수 있다 — 준비 여부 판정이 그렇게 허용하는데 여기서만
     * 정상 목록을 돌면, READY인데 후보가 0건이라 사용자는 이유 없는 되물음을 받는다. 근무
     * <b>뒤</b> 이동은 종료 시각이 필요하므로 정상 근무만 대상이다(누락이 있으면 판정 단계에서
     * 이미 보류된다).
     */
    public static TravelPlan plan(DraftState draft, DraftFacts facts, ObjectMapper om) {
        if (draft == null || facts == null || draft.getType() != DraftType.CREATE_SCHEDULE) {
            return TravelPlan.EMPTY;
        }
        Map<String, DraftField> f = draft.getFields();
        DerivedTravelRelation relation = relationOf(text(f, DraftSlotRegistry.ANCHOR));
        String title = text(f, DraftSlotRegistry.TITLE);
        Integer duration = intValue(f, DraftSlotRegistry.DURATION_MINUTES);
        if (relation == null || title == null || duration == null || duration <= 0) {
            return TravelPlan.EMPTY;
        }
        ArrayNode assumed = annotations(draft, om, true);
        ArrayNode notes = annotations(draft, om, false);
        LocalDate[] range = range(draft, facts);

        List<ScheduleSuggestion> create = new ArrayList<>();
        List<ExistingTravel> unchanged = new ArrayList<>();
        List<TravelConflict> conflicts = new ArrayList<>();

        for (WorkShift shift : targetShifts(facts, relation, range)) {
            LocalDateTime anchorAt = relation == DerivedTravelRelation.AFTER_WORK
                    ? shift.endAt() : shift.startAt();
            LocalDateTime startAt = relation == DerivedTravelRelation.AFTER_WORK
                    ? anchorAt : anchorAt.minusMinutes(duration);
            LocalDateTime endAt = relation == DerivedTravelRelation.AFTER_WORK
                    ? anchorAt.plusMinutes(duration) : anchorAt;

            ExistingTravel existing = facts.existingTravelFor(shift.commitmentId(), relation);
            if (existing == null) {
                create.add(commitment(om, title, startAt, endAt, assumed, notes,
                        shift.commitmentId(), relation, anchorAt));
            } else if (existing.matches(startAt, endAt)) {
                // 요청한 것과 똑같은 이동이 이미 있다. 하나 더 만들 이유가 없다.
                unchanged.add(existing);
            } else {
                // 길이나 시각이 다르다. 이건 "이미 있음"이 아니라 "고쳐야 함"이다.
                conflicts.add(new TravelConflict(shift, existing, startAt, endAt));
            }
        }
        return new TravelPlan(create, unchanged, conflicts);
    }

    /** 이 방향의 이동을 만들 수 있는 근무. 앞이면 시작만, 뒤면 시작·종료가 모두 필요하다. */
    private static List<WorkShift> targetShifts(DraftFacts facts, DerivedTravelRelation relation,
                                                LocalDate[] range) {
        List<WorkShift> targets = new ArrayList<>(facts.workShiftsIn(range[0], range[1]));
        if (relation == DerivedTravelRelation.BEFORE_WORK) {
            targets.addAll(facts.incompleteShiftsIn(range[0], range[1]));
            targets.sort(java.util.Comparator.comparing(WorkShift::startAt));
        }
        return targets;
    }

    /** anchor가 뜻하는 파생 관계. 근무 기준 anchor가 아니면 null. */
    public static DerivedTravelRelation relationOf(String anchor) {
        if (DraftSlotRegistry.ANCHOR_BEFORE_EACH_WORK_SHIFT.equals(anchor)) {
            return DerivedTravelRelation.BEFORE_WORK;
        }
        if (DraftSlotRegistry.ANCHOR_AFTER_EACH_WORK_SHIFT.equals(anchor)) {
            return DerivedTravelRelation.AFTER_WORK;
        }
        return null;
    }

    /**
     * 이 draft가 대상으로 하는 기간. dateRange가 있으면 그것이고, 없으면 조회한 기간이다.
     *
     * <p>조회·필수 판정·후보 생성이 <b>같은</b> 기간을 봐야 한다. 사용자가 "이번 주"라고 했는데
     * 생성만 기본 2주를 쓰면 말하지 않은 다음 주 근무에도 블록이 붙는다.
     */
    public static LocalDate[] range(DraftState draft, DraftFacts facts) {
        DraftField field = draft.getFields().get(DraftSlotRegistry.DATE_RANGE);
        LocalDate[] declared = field != null ? field.asDateRange() : null;
        if (declared != null && !declared[1].isBefore(declared[0])) {
            return declared;
        }
        return new LocalDate[]{facts.workRangeFrom(), facts.workRangeTo()};
    }

    /** 사람이 읽는 한 줄 요약에 쓰는 종류별 건수. */
    public static Map<ScheduleSuggestionKind, Integer> countByKind(List<ScheduleSuggestion> suggestions) {
        Map<ScheduleSuggestionKind, Integer> counts = new LinkedHashMap<>();
        for (ScheduleSuggestion suggestion : suggestions) {
            counts.merge(suggestion.kind(), 1, Integer::sum);
        }
        return counts;
    }

    private static String text(Map<String, DraftField> f, String name) {
        DraftField field = f.get(name);
        String value = field != null ? field.asText() : null;
        return value != null && !value.isBlank() ? value : null;
    }

    private static Integer intValue(Map<String, DraftField> f, String name) {
        DraftField field = f.get(name);
        return field != null ? field.asInt() : null;
    }

    private static LocalDate date(Map<String, DraftField> f, String name) {
        DraftField field = f.get(name);
        return field != null ? field.asLocalDate() : null;
    }

    private static LocalTime time(Map<String, DraftField> f, String name) {
        DraftField field = f.get(name);
        return field != null ? field.asLocalTime() : null;
    }
}
