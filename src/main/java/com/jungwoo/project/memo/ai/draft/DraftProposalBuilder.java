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
                    LocalDate[] range = range(draft, facts);
                    for (WorkShift shift : facts.workShiftsIn(range[0], range[1])) {
                        if (facts.alreadyCovered(shift.commitmentId(), relation)) {
                            // 같은 근무의 같은 방향 이동이 이미 있다. 같은 시간 블록을 조용히 하나 더 만들지 않는다.
                            continue;
                        }
                        LocalDateTime startAt = relation == DerivedTravelRelation.AFTER_WORK
                                ? shift.endAt() : shift.startAt().minusMinutes(duration);
                        LocalDateTime endAt = relation == DerivedTravelRelation.AFTER_WORK
                                ? shift.endAt().plusMinutes(duration) : shift.startAt();
                        LocalDateTime anchorAt = relation == DerivedTravelRelation.AFTER_WORK
                                ? shift.endAt() : shift.startAt();
                        result.add(commitment(om, title, startAt, endAt, assumed, notes,
                                shift.commitmentId(), relation, anchorAt));
                    }
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
     * 이 draft의 기간에서 "이미 같은 이동이 있어서" 후보를 만들지 않은 근무 수.
     *
     * <p>PROPOSE 문구가 쓴다. "모든 근무 후에"라고 했는데 후보가 2건이면 사용자는 나머지가
     * 어디 갔는지 알아야 한다 — 조용히 빠지면 앱이 요청을 흘린 것처럼 보인다.
     */
    public static int skippedAsCovered(DraftState draft, DraftFacts facts) {
        if (draft == null || facts == null || draft.getType() != DraftType.CREATE_SCHEDULE) {
            return 0;
        }
        DerivedTravelRelation relation = relationOf(text(draft.getFields(), DraftSlotRegistry.ANCHOR));
        if (relation == null) {
            return 0;
        }
        LocalDate[] range = range(draft, facts);
        int skipped = 0;
        for (WorkShift shift : facts.workShiftsIn(range[0], range[1])) {
            if (facts.alreadyCovered(shift.commitmentId(), relation)) {
                skipped++;
            }
        }
        return skipped;
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
