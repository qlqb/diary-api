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
 *   anchor | (date + startTime)   둘 중 하나 필수.
 *                    anchor ∈ {BEFORE_EACH_WORK_SHIFT, AFTER_EACH_WORK_SHIFT}
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
    public static final String ANCHOR_AFTER_EACH_WORK_SHIFT = "AFTER_EACH_WORK_SHIFT";

    // ---- reason 상수 (payload fieldNotes/assumedFields의 reason이 된다) ----
    public static final String REASON_CURRENT_DATE = "CURRENT_DATE";
    public static final String REASON_ACTIVE_SEMESTER_END = "ACTIVE_SEMESTER_END";
    public static final String REASON_DEFAULT_RANGE = "DEFAULT_RANGE_14_DAYS";
    public static final String REASON_DRAFT_LABEL = "DRAFT_LABEL";
    public static final String REASON_ANCHOR_IMPLIES = "ANCHOR_IMPLIES";
    public static final String REASON_UNCONFIRMED = "UNCONFIRMED";

    /**
     * anchor는 있는데 실제 데이터로 펼칠 수 없을 때 missing_required에 넣는 가상 키.
     * 값이 없다는 뜻이지 모델이 채울 필드가 아니다.
     *
     * <p>네 가지를 구분한다. 합치면 사용자가 무엇을 해야 하는지 알 수 없다 —
     * "근무를 등록하세요"와 "다시 시도해 주세요"는 다른 행동이다.
     * <ul>
     *   <li>{@link #MISSING_FIRST_CLASS_TIMES}: 시간표에 수업 시작 시각이 없다
     *   <li>{@link #MISSING_WORK_SHIFTS}: 조회는 됐고, 그 기간에 근무가 없다
     *   <li>{@link #MISSING_WORK_SHIFT_END}: 근무는 있는데 종료 시각을 쓸 수 없다(뒤쪽 이동만 해당)
     *   <li>{@link #MISSING_WORK_LOOKUP}: 근무 조회 자체가 실패했다. 근무가 없다는 뜻이 아니다
     * </ul>
     */
    public static final String MISSING_FIRST_CLASS_TIMES = "firstClassTimes";
    public static final String MISSING_WORK_SHIFTS = "workShifts";
    public static final String MISSING_WORK_SHIFT_END = "workShiftEnd";
    public static final String MISSING_WORK_LOOKUP = "workLookup";

    /** 사용자가 답할 수 없는 누락. 이것들은 "정보를 더 달라"가 아니라 상태 안내로 말해야 한다. */
    private static final Set<String> NOT_USER_ANSWERABLE = Set.of(MISSING_WORK_LOOKUP);

    /** 사용자가 답해서 풀 수 있는 누락인가. */
    public static boolean userCanAnswer(String field) {
        return !NOT_USER_ANSWERABLE.contains(field);
    }

    /** 1회성 이동 블록의 기본 조회 기간(오늘 포함). 제품 정책값이다. */
    public static final int SCHEDULE_DEFAULT_RANGE_DAYS = 14;

    /** anchor=FIRST_CLASS_OF_DAY를 확인 없이 믿으려면 사용자 발화 원문에 이 글자가 있어야 한다. */
    public static final String USER_FIRST_MARKER = "첫";

    /** NEEDS_CONFIRM draft가 이 횟수 이상 질문받으면 가정 표시를 달고 READY로 승격한다. */
    public static final int CONFIRM_ASK_LIMIT = 3;

    /**
     * 같은 필수 누락으로 이 횟수 이상 물었으면 같은 질문을 반복하지 않고 진전이 없다는 것을
     * 말한다. <b>승격은 하지 않는다</b> — NEEDS_INPUT을 횟수로 밀어 올리면 서버가 날짜·시각을
     * 지어내 일정을 만들게 된다. draft는 OPEN으로 남아, 사용자가 나중에 값을 주면 이어진다.
     */
    public static final int STALLED_ASK_LIMIT = 3;

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
            case CREATE_SCHEDULE -> ANCHOR_BEFORE_EACH_WORK_SHIFT.equals(anchor)
                    || ANCHOR_AFTER_EACH_WORK_SHIFT.equals(anchor);
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
                    /*
                     * 셋은 배타적이다. 조회를 못 했으면 근무 유무를 알 수 없고, 근무가 없으면
                     * 종료 시각을 따질 것도 없다. 조회 실패를 "근무 없음"과 같은 문구로 말하지
                     * 않는다 - 사용자가 할 수 있는 일이 다르다(재시도 vs 근무 등록).
                     */
                    if (facts.workLookupFailed()) {
                        missing.add(MISSING_WORK_LOOKUP);
                    } else if (!facts.workShiftsKnown()) {
                        missing.add(MISSING_WORK_SHIFTS);
                    } else if (facts.hasUnusableShiftEnd()) {
                        /*
                         * 근무 뒤 이동은 종료 시각이 있어야 계산된다. 일부만 정상이라고 그것만
                         * 만들고 끝내면 "모든 근무 후에"라는 요청이 조용히 일부만 처리된 채
                         * 완료로 표시된다. 그 근무를 지정해 묻고, 답이 올 때까지 이 draft는 보류다.
                         */
                        missing.add(MISSING_WORK_SHIFT_END);
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

    /**
     * anchor를 실제 데이터로 펼칠 수 있는가. resolver 결과에서 온다.
     *
     * <p>근무 관련 세 값은 <b>요청 기간</b> 기준이다. 무관한 기간에 근무가 있다는 이유로
     * 요청 기간의 workShiftsKnown을 true로 만들면, 그 기간에 후보가 0건인데도 READY가 된다.
     *
     * @param firstClassTimesKnown  시간표에서 요일별 첫 수업 시각을 하나라도 찾았는가
     * @param workShiftsKnown       요청 기간에 쓸 수 있는 근무가 하나라도 있는가
     * @param workLookupFailed      근무 조회 자체가 실패했는가. true면 위 값은 의미가 없다
     * @param hasUnusableShiftEnd   요청 기간에 종료 시각을 쓸 수 없는 근무가 있는가(뒤쪽 이동만 본다)
     */
    public record AnchorFacts(boolean firstClassTimesKnown, boolean workShiftsKnown,
                              boolean workLookupFailed, boolean hasUnusableShiftEnd) {
        public static final AnchorFacts NONE = new AnchorFacts(false, false, false, false);
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
