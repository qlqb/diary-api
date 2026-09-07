package com.jungwoo.project.memo.ai.draft;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 서버가 소유하는 필수 슬롯 맵 + 출처·확인 규칙. 순수 자바(Spring 의존 없음).
 *
 * <pre>
 * CREATE_ROUTINE:
 *   title            필수. 없으면 서버가 draft 라벨로 채움 (DEFAULT / DRAFT_LABEL, 확인 불필요)
 *   anchor | (daysOfWeek + startTime)   둘 중 하나 필수. anchor ∈ {FIRST_CLASS_OF_DAY}
 *   durationMinutes  필수
 *   startDate        필수. 없으면 오늘 (SYSTEM / CURRENT_DATE)
 *   endDate          필수. resolver: 수업 루틴의 종강일 (DB / ACTIVE_SEMESTER_END). 없으면 missing
 *   classDaysOnly    anchor가 있으면 true (SYSTEM / ANCHOR_IMPLIES)
 * CREATE_SCHEDULE:
 *   title            필수 (위와 같음)
 *   anchor | (date + startTime)   둘 중 하나 필수. anchor ∈ {BEFORE_EACH_WORK_SHIFT}
 *   durationMinutes  필수
 *   dateRange        anchor일 때만. 없으면 오늘~+14일 (DEFAULT / DEFAULT_RANGE_14_DAYS, 확인 불필요 —
 *                    1회성 블록은 승인 시 개별 검토되므로)
 * CREATE_PERIOD_PLAN:
 *   startDate, endDate, intensity   전부 필수(기존 기간 계획 로직이 검증한다. 여기서는 슬롯만 선언)
 * </pre>
 *
 * <p>DEFAULT 출처를 가질 수 있는 값은 이 클래스에 적힌 것뿐이다(title 라벨, dateRange 14일).
 * 모델의 추측은 DEFAULT가 아니라 INFERRED다.
 */
public final class DraftSlotRegistry {

    // ---- 필드 이름 ----
    public static final String TITLE = "title";
    public static final String ANCHOR = "anchor";
    public static final String DAYS_OF_WEEK = "daysOfWeek";
    public static final String START_TIME = "startTime";
    public static final String DURATION_MINUTES = "durationMinutes";
    public static final String START_DATE = "startDate";
    public static final String END_DATE = "endDate";
    public static final String CLASS_DAYS_ONLY = "classDaysOnly";
    public static final String DATE = "date";
    public static final String DATE_RANGE = "dateRange";
    public static final String INTENSITY = "intensity";

    // ---- anchor 값 ----
    public static final String ANCHOR_FIRST_CLASS_OF_DAY = "FIRST_CLASS_OF_DAY";
    public static final String ANCHOR_BEFORE_EACH_WORK_SHIFT = "BEFORE_EACH_WORK_SHIFT";

    // ---- reason 상수 (payload fieldNotes/assumedFields의 reason이 된다) ----
    public static final String REASON_CURRENT_DATE = "CURRENT_DATE";
    public static final String REASON_ACTIVE_SEMESTER_END = "ACTIVE_SEMESTER_END";
    public static final String REASON_DEFAULT_RANGE = "DEFAULT_RANGE_14_DAYS";
    public static final String REASON_DRAFT_LABEL = "DRAFT_LABEL";
    public static final String REASON_ANCHOR_IMPLIES = "ANCHOR_IMPLIES";
    public static final String REASON_UNCONFIRMED = "UNCONFIRMED";

    /**
     * anchor는 있는데 실제 데이터로 펼칠 수 없을 때 missing_required에 넣는 가상 키.
     * 시간표에 시작 시각이 없으면 고정 시각을 물어야 하고(§7), 앞으로 2주 안에 근무가 없으면
     * 근무 날짜·시각을 물어야 한다. 값이 없다는 뜻이지 모델이 채울 필드가 아니다.
     */
    public static final String MISSING_FIRST_CLASS_TIMES = "firstClassTimes";
    public static final String MISSING_WORK_SHIFTS = "workShifts";

    /** 1회성 이동 블록의 기본 조회 기간(오늘 포함). 제품 정책값이다. */
    public static final int SCHEDULE_DEFAULT_RANGE_DAYS = 14;

    /** anchor=FIRST_CLASS_OF_DAY를 확인 없이 믿으려면 사용자 발화 원문에 이 글자가 있어야 한다. */
    public static final String USER_FIRST_MARKER = "첫";

    /** NEEDS_CONFIRM draft가 이 횟수 이상 질문받으면 가정 표시를 달고 READY로 승격한다. */
    public static final int CONFIRM_ASK_LIMIT = 3;

    private static final Set<String> ROUTINE_FIELDS = Set.of(
            TITLE, ANCHOR, DAYS_OF_WEEK, START_TIME, DURATION_MINUTES, START_DATE, END_DATE, CLASS_DAYS_ONLY);
    private static final Set<String> SCHEDULE_FIELDS = Set.of(
            TITLE, ANCHOR, DATE, START_TIME, DURATION_MINUTES, DATE_RANGE);
    private static final Set<String> PERIOD_PLAN_FIELDS = Set.of(START_DATE, END_DATE, INTENSITY);

    private DraftSlotRegistry() {
    }

    /** 이 타입에 존재하는 필드. RETYPE 뒤 여기 없는 필드는 서버가 전부 지운다. */
    public static Set<String> allowedFields(DraftType type) {
        return switch (type) {
            case CREATE_ROUTINE -> ROUTINE_FIELDS;
            case CREATE_SCHEDULE -> SCHEDULE_FIELDS;
            case CREATE_PERIOD_PLAN -> PERIOD_PLAN_FIELDS;
        };
    }

    /** 이 타입에서 유효한 anchor 값인가. 아니면 그 SET은 버린다. */
    public static boolean isValidAnchor(DraftType type, String anchor) {
        if (anchor == null) {
            return false;
        }
        return switch (type) {
            case CREATE_ROUTINE -> ANCHOR_FIRST_CLASS_OF_DAY.equals(anchor);
            case CREATE_SCHEDULE -> ANCHOR_BEFORE_EACH_WORK_SHIFT.equals(anchor);
            case CREATE_PERIOD_PLAN -> false;
        };
    }

    /**
     * 서버가 계산한 필수 누락. 모델이 쓰지 않는다. anchor 자체는 있어도 실제 데이터로 펼칠 수
     * 없으면 가상 키({@link #MISSING_FIRST_CLASS_TIMES}/{@link #MISSING_WORK_SHIFTS})가 들어간다.
     */
    public static List<String> missingRequired(DraftType type, Map<String, DraftField> fields, AnchorFacts facts) {
        List<String> missing = new ArrayList<>();
        switch (type) {
            case CREATE_ROUTINE -> {
                requireText(fields, TITLE, missing);
                String anchor = text(fields, ANCHOR);
                if (anchor != null) {
                    if (!facts.firstClassTimesKnown()) {
                        missing.add(MISSING_FIRST_CLASS_TIMES);
                    }
                } else {
                    if (days(fields).isEmpty()) {
                        missing.add(DAYS_OF_WEEK);
                    }
                    if (time(fields, START_TIME) == null) {
                        missing.add(START_TIME);
                    }
                }
                requirePositiveInt(fields, DURATION_MINUTES, missing);
                if (date(fields, START_DATE) == null) {
                    missing.add(START_DATE);
                }
                if (date(fields, END_DATE) == null) {
                    missing.add(END_DATE);
                }
            }
            case CREATE_SCHEDULE -> {
                requireText(fields, TITLE, missing);
                String anchor = text(fields, ANCHOR);
                if (anchor != null) {
                    if (!facts.workShiftsKnown()) {
                        missing.add(MISSING_WORK_SHIFTS);
                    }
                    if (fields.get(DATE_RANGE) == null || fields.get(DATE_RANGE).asDateRange() == null) {
                        missing.add(DATE_RANGE);
                    }
                } else {
                    if (date(fields, DATE) == null) {
                        missing.add(DATE);
                    }
                    if (time(fields, START_TIME) == null) {
                        missing.add(START_TIME);
                    }
                }
                requirePositiveInt(fields, DURATION_MINUTES, missing);
            }
            case CREATE_PERIOD_PLAN -> {
                if (date(fields, START_DATE) == null) {
                    missing.add(START_DATE);
                }
                if (date(fields, END_DATE) == null) {
                    missing.add(END_DATE);
                }
                if (text(fields, INTENSITY) == null) {
                    missing.add(INTENSITY);
                }
            }
        }
        return missing;
    }

    /**
     * 서버 강제 확인 규칙. 모델 신고와 무관하게 적용한다. true를 돌려주면 그 필드의
     * confirmationRequired를 true로 덮어쓴다(false로 되돌리지는 않는다).
     *
     * <ul>
     *   <li>anchor=FIRST_CLASS_OF_DAY인데 이번 턴까지의 사용자 발화 원문에 "첫"이 없으면 확인 필요
     *   <li>endDate가 INFERRED이면 항상 확인 필요 (날짜는 해석하지 않는다)
     * </ul>
     */
    public static boolean forcedConfirmation(String field, DraftField value, boolean userMentionedFirst) {
        if (value == null || value.isNull()) {
            return false;
        }
        if (ANCHOR.equals(field) && ANCHOR_FIRST_CLASS_OF_DAY.equals(value.asText()) && !userMentionedFirst) {
            return true;
        }
        return END_DATE.equals(field) && value.source() == FieldSource.INFERRED;
    }

    public static DraftReadiness readiness(List<String> missing, Map<String, DraftField> fields) {
        if (!missing.isEmpty()) {
            return DraftReadiness.NEEDS_INPUT;
        }
        for (DraftField field : fields.values()) {
            if (field.confirmationRequired()) {
                return DraftReadiness.NEEDS_CONFIRM;
            }
        }
        return DraftReadiness.READY;
    }

    /** anchor를 실제 데이터로 펼칠 수 있는가. resolver 결과에서 온다. */
    public record AnchorFacts(boolean firstClassTimesKnown, boolean workShiftsKnown) {
        public static final AnchorFacts NONE = new AnchorFacts(false, false);
    }

    // ---- 내부 ----

    private static void requireText(Map<String, DraftField> fields, String name, List<String> missing) {
        if (text(fields, name) == null) {
            missing.add(name);
        }
    }

    private static void requirePositiveInt(Map<String, DraftField> fields, String name, List<String> missing) {
        DraftField field = fields.get(name);
        Integer value = field != null ? field.asInt() : null;
        if (value == null || value <= 0) {
            missing.add(name);
        }
    }

    private static String text(Map<String, DraftField> fields, String name) {
        DraftField field = fields.get(name);
        String value = field != null ? field.asText() : null;
        return value != null && !value.isBlank() ? value : null;
    }

    private static java.time.LocalDate date(Map<String, DraftField> fields, String name) {
        DraftField field = fields.get(name);
        return field != null ? field.asLocalDate() : null;
    }

    private static java.time.LocalTime time(Map<String, DraftField> fields, String name) {
        DraftField field = fields.get(name);
        return field != null ? field.asLocalTime() : null;
    }

    private static List<java.time.DayOfWeek> days(Map<String, DraftField> fields) {
        DraftField field = fields.get(DAYS_OF_WEEK);
        return field != null ? field.asDaysOfWeek() : List.of();
    }
}
