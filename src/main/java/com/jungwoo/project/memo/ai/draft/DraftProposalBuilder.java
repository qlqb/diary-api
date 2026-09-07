package com.jungwoo.project.memo.ai.draft;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jungwoo.project.memo.ai.domain.ScheduleSuggestionKind;
import com.jungwoo.project.memo.ai.draft.resolver.UpcomingWorkShiftsResolver.WorkShift;
import com.jungwoo.project.memo.ai.dto.ScheduleSuggestion;

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
 *   <li>anchor=BEFORE_EACH_WORK_SHIFT: 근무 1건당 COMMITMENT 1건. payload는 {@code CommitmentCreateRequest}.
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
                if (DraftSlotRegistry.ANCHOR_BEFORE_EACH_WORK_SHIFT.equals(anchor)) {
                    LocalDate[] range = f.containsKey(DraftSlotRegistry.DATE_RANGE)
                            ? f.get(DraftSlotRegistry.DATE_RANGE).asDateRange() : null;
                    for (WorkShift shift : facts.workShifts()) {
                        LocalDate day = shift.startAt().toLocalDate();
                        if (range != null && (day.isBefore(range[0]) || day.isAfter(range[1]))) {
                            continue;
                        }
                        result.add(commitment(om, title, shift.startAt().minusMinutes(duration), shift.startAt(),
                                assumed, notes));
                    }
                } else {
                    LocalDate day = date(f, DraftSlotRegistry.DATE);
                    LocalTime start = time(f, DraftSlotRegistry.START_TIME);
                    if (day == null || start == null) {
                        return result;
                    }
                    LocalDateTime startAt = LocalDateTime.of(day, start);
                    result.add(commitment(om, title, startAt, startAt.plusMinutes(duration), assumed, notes));
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
                                                 LocalDateTime endAt, ArrayNode assumed, ArrayNode notes) {
        ObjectNode payload = om.createObjectNode();
        payload.put("title", title);
        payload.put("startAt", startAt.toString());
        payload.put("endAt", endAt.toString());
        payload.putNull("locationText");
        payload.set(ASSUMED_FIELDS_KEY, assumed.deepCopy());
        payload.set(FIELD_NOTES_KEY, notes.deepCopy());
        return new ScheduleSuggestion(ScheduleSuggestionKind.COMMITMENT, payload);
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
