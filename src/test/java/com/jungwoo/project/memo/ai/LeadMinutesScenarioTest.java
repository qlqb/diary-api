package com.jungwoo.project.memo.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.jungwoo.project.memo.ai.domain.AiScheduleSuggestion;
import com.jungwoo.project.memo.ai.domain.ScheduleSuggestionKind;
import com.jungwoo.project.memo.ai.domain.ScheduleSuggestionStatus;
import com.jungwoo.project.memo.ai.dto.ScheduleSuggestion;
import com.jungwoo.project.memo.ai.dto.ScheduleSuggestionResponse;
import com.jungwoo.project.memo.commitment.CommitmentService;
import com.jungwoo.project.memo.common.config.JacksonConfig;
import com.jungwoo.project.memo.course.CourseService;
import com.jungwoo.project.memo.execution.ExecutionItemMapper;
import com.jungwoo.project.memo.routine.RoutineExceptionMapper;
import com.jungwoo.project.memo.routine.RoutineMapper;
import com.jungwoo.project.memo.routine.RoutineOccurrenceService;
import com.jungwoo.project.memo.routine.RoutineReader;
import com.jungwoo.project.memo.routine.RoutineService;
import com.jungwoo.project.memo.routine.domain.Routine;
import com.jungwoo.project.memo.routine.domain.RoutineOccurrence;
import com.jungwoo.project.memo.routine.dto.RoutineWeekdayRow;
import com.jungwoo.project.memo.scheduling.domain.AvailabilityWindow;
import com.jungwoo.project.memo.scheduling.domain.BusyWindow;
import com.jungwoo.project.memo.scheduling.service.AvailabilityEstimateResult;
import com.jungwoo.project.memo.scheduling.service.AvailabilityEstimateService;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * §1 시나리오를 모델 호출 없이 끝까지 재현한다.
 *
 * <pre>
 * "수업 전엔 이동시간 1시간 채워줄 수 있어?"
 *   → 구조화 출력 픽스처 {ROUTINE_LEAD, targetHint:"수업", leadMinutes:60}
 *   → 후보 1건, 대상 = 계획 기간에 도는 활성 수업 루틴 N개(courseId != null)
 *   → 승인(apply) → N개 모두 lead_minutes=60
 *   → 가용시간 재계산: 요일별 첫 수업 앞 lead, 감소량은 lead ∩ 기본 창
 *   → 확인 문장은 서버 템플릿
 * </pre>
 *
 * <p>DB 대신 매퍼를 메모리 맵으로 세운다. 서비스는 전부 실물이다 — 대상 해석(RoutineService),
 * 후보 저장·승인(ScheduleSuggestionService), 이동시간 저장(RoutineService.updateLeadMinutes),
 * 전개(RoutineOccurrenceService), 가용시간(AvailabilityEstimateService), 확인 문장(SystemNotes).
 * 숫자를 박지 않는다 — 기대값은 픽스처에서 계산한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LeadMinutesScenarioTest {

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final Long CONVERSATION_ID = 10L;
    private static final Long MESSAGE_ID = 100L;
    // 2026-09-07 00:00 Asia/Seoul
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-09-06T15:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate PLAN_FROM = LocalDate.of(2026, 9, 7);
    private static final LocalDate PLAN_TO = LocalDate.of(2026, 9, 13);
    private static final LocalDate SEMESTER_START = LocalDate.of(2026, 8, 25);
    private static final int LEAD = 60;

    @Mock private RoutineMapper routineMapper;
    @Mock private RoutineExceptionMapper routineExceptionMapper;
    @Mock private AiScheduleSuggestionMapper suggestionMapper;
    @Mock private CommitmentService commitmentService;
    @Mock private CourseService courseService;
    @Mock private ExecutionItemMapper executionItemMapper;

    /** routines 테이블 대용. routineId → 행. */
    private final Map<Long, Routine> routines = new LinkedHashMap<>();
    /** ai_schedule_suggestions 대용. */
    private final Map<Long, AiScheduleSuggestion> suggestions = new LinkedHashMap<>();
    private final AtomicLong suggestionSeq = new AtomicLong(700);

    private RoutineService routineService;
    private ScheduleSuggestionService suggestionService;
    private RoutineOccurrenceService occurrenceService;
    private AvailabilityEstimateService availabilityService;

    @BeforeEach
    void wire() {
        RoutineReader reader = new RoutineReader(routineMapper);
        occurrenceService = new RoutineOccurrenceService(reader, routineExceptionMapper);
        routineService = new RoutineService(routineMapper, routineExceptionMapper, reader, courseService,
                FIXED_CLOCK, occurrenceService);
        suggestionService = new ScheduleSuggestionService(suggestionMapper, commitmentService, routineService,
                Validation.buildDefaultValidatorFactory().getValidator(), new JacksonConfig().objectMapper());
        availabilityService = new AvailabilityEstimateService(executionItemMapper, occurrenceService,
                commitmentService, FIXED_CLOCK);

        // ---- routines 매퍼: 메모리 맵 위에서 실제 SQL과 같은 조건으로 동작한다.
        when(routineMapper.findAllByUserId(anyLong())).thenAnswer(inv -> {
            Long userId = inv.getArgument(0);
            List<Routine> rows = new ArrayList<>();
            for (Routine routine : routines.values()) {
                if (routine.getUserId().equals(userId) && !routine.isDeleted()) {
                    rows.add(copy(routine));
                }
            }
            return rows;
        });
        when(routineMapper.findWeekdaysByUserId(anyLong())).thenAnswer(inv -> {
            Long userId = inv.getArgument(0);
            List<RoutineWeekdayRow> rows = new ArrayList<>();
            for (Routine routine : routines.values()) {
                if (!routine.getUserId().equals(userId) || routine.isDeleted()) {
                    continue;
                }
                for (DayOfWeek day : routine.getDaysOfWeek()) {
                    RoutineWeekdayRow row = new RoutineWeekdayRow();
                    row.setRoutineId(routine.getRoutineId());
                    row.setDayOfWeek(day.name());
                    rows.add(row);
                }
            }
            return rows;
        });
        when(routineMapper.findByIdAndUserIdForUpdate(anyLong(), anyLong())).thenAnswer(inv -> {
            Routine routine = routines.get((Long) inv.getArgument(0));
            return routine != null && routine.getUserId().equals(inv.getArgument(1)) && !routine.isDeleted()
                    ? copy(routine) : null;
        });
        when(routineMapper.updateLeadMinutes(anyLong(), anyLong(), any())).thenAnswer(inv -> {
            Routine routine = routines.get((Long) inv.getArgument(0));
            if (routine == null || !routine.getUserId().equals(inv.getArgument(1))) {
                return 0;
            }
            routine.setLeadMinutes(inv.getArgument(2));
            return 1;
        });
        when(routineMapper.findWeekdaysByRoutineId(anyLong())).thenAnswer(inv -> {
            Routine routine = routines.get((Long) inv.getArgument(0));
            List<String> names = new ArrayList<>();
            if (routine != null) {
                for (DayOfWeek day : routine.getDaysOfWeek()) {
                    names.add(day.name());
                }
            }
            return names;
        });
        when(routineExceptionMapper.findByUserIdAndExceptionDateRange(anyLong(), any(), any())).thenReturn(List.of());
        when(routineExceptionMapper.findByUserIdAndMovedDateRange(anyLong(), any(), any())).thenReturn(List.of());
        when(routineExceptionMapper.findByRoutineId(anyLong())).thenReturn(List.of());
        when(commitmentService.findOverlapping(anyLong(), any(), any())).thenReturn(List.of());
        when(executionItemMapper.findTimeFixedByUserIdAndDateRange(anyLong(), any(), any())).thenReturn(List.of());

        // ---- 후보 매퍼.
        when(suggestionMapper.findByIdAndUserIdForUpdate(anyLong(), anyLong())).thenAnswer(inv -> {
            AiScheduleSuggestion s = suggestions.get((Long) inv.getArgument(0));
            return s != null && s.getUserId().equals(inv.getArgument(1)) ? s : null;
        });
        when(suggestionMapper.resolveIfProposed(anyLong(), anyLong(), any(), any())).thenAnswer(inv -> {
            AiScheduleSuggestion s = suggestions.get((Long) inv.getArgument(0));
            if (s == null || s.getStatus() != ScheduleSuggestionStatus.PROPOSED) {
                return 0;
            }
            s.setStatus(ScheduleSuggestionStatus.valueOf(inv.getArgument(2)));
            return 1;
        });
        org.mockito.Mockito.doAnswer(inv -> {
            AiScheduleSuggestion s = inv.getArgument(0);
            s.setSuggestionId(suggestionSeq.incrementAndGet());
            suggestions.put(s.getSuggestionId(), s);
            return null;
        }).when(suggestionMapper).insert(any());
    }

    /** 이번 주 시간표. 수업(courseId != null)과 알바(courseId == null)가 섞여 있다. */
    private void seedTimetable() {
        add(routine(1L, USER_ID, 101L, "자료구조", LocalTime.of(14, 0), LocalTime.of(17, 0), DayOfWeek.TUESDAY));
        add(routine(2L, USER_ID, 102L, "웹서버", LocalTime.of(9, 0), LocalTime.of(12, 0), DayOfWeek.WEDNESDAY));
        add(routine(3L, USER_ID, 103L, "센서", LocalTime.of(13, 0), LocalTime.of(16, 0), DayOfWeek.WEDNESDAY));
        add(routine(4L, USER_ID, 104L, "빅데이터", LocalTime.of(10, 0), LocalTime.of(11, 0), DayOfWeek.THURSDAY));
        add(routine(5L, USER_ID, 105L, "영어회화", LocalTime.of(12, 0), LocalTime.of(14, 0), DayOfWeek.THURSDAY));
        add(routine(6L, USER_ID, 106L, "스마트앱", LocalTime.of(20, 0), LocalTime.of(22, 0), DayOfWeek.FRIDAY));
        add(routine(7L, USER_ID, 107L, "네트워크", LocalTime.of(22, 0), LocalTime.of(23, 0), DayOfWeek.FRIDAY));
        // 수업이 아닌 것과 남의 것. 어느 쪽도 "수업"에 걸리면 안 된다.
        add(routine(8L, USER_ID, null, "쿠팡 알바", LocalTime.of(18, 0), LocalTime.of(22, 0), DayOfWeek.MONDAY));
        add(routine(9L, OTHER_USER_ID, 201L, "남의 수업", LocalTime.of(9, 0), LocalTime.of(10, 0), DayOfWeek.TUESDAY));
    }

    private ScheduleSuggestion leadIntent(Integer leadMinutes) {
        String json = "{\"targetRoutineIds\":null,\"targetHint\":\"수업\",\"leadMinutes\":"
                + (leadMinutes == null ? "null" : leadMinutes) + "}";
        return new ScheduleSuggestion(ScheduleSuggestionKind.ROUTINE_LEAD, readTree(json));
    }

    @Test
    void 수업_전_이동시간_1시간_요청이_후보_승인_가용시간_확인문장까지_이어진다() {
        seedTimetable();
        List<Long> classIds = activeClassIdsInPlan();
        assertThat(classIds).isNotEmpty();
        Map<LocalDate, Long> before = minutesByDate(estimate().windows());
        Map<LocalDate, List<AvailabilityWindow>> baseWindows = baseWindows();

        // 3. 후보 1건, payload routineIds = 기간에 도는 활성 수업 전부.
        ScheduleSuggestionService.Created created = suggestionService.createFromModelSuggestions(
                USER_ID, CONVERSATION_ID, MESSAGE_ID, List.of(leadIntent(LEAD)));
        assertThat(created.unresolvedLeadTargets()).isZero();
        assertThat(created.saved()).hasSize(1);
        ScheduleSuggestionResponse candidate = created.saved().get(0);
        assertThat(candidate.getKind()).isEqualTo(ScheduleSuggestionKind.ROUTINE_LEAD);
        assertThat(toLongs(candidate.getPayload().get("routineIds"))).containsExactlyInAnyOrderElementsOf(classIds);
        // 후보 단계에서는 아무 루틴도 바뀌지 않았다.
        assertThat(routines.values()).allMatch(r -> r.getLeadMinutes() == null);

        // 6. 턴의 확인 문장은 ROUTINE_LEAD 행이다.
        String turnNote = SystemNotes.forTurn(List.of(), created.saved(), created.unresolvedLeadTargets());
        assertThat(turnNote).startsWith("이동시간 후보 1건을 만들었어요. 승인하면 ")
                .endsWith(" 앞 " + LEAD + "분이 비워져요.");

        // 4. 승인 → 수업 전부 lead=60. 알바·남의 것은 그대로.
        List<ScheduleSuggestionResponse> applied = suggestionService.applyBatch(USER_ID, List.of(candidate.getSuggestionId()));
        assertThat(applied).hasSize(1);
        assertThat(applied.get(0).getStatus()).isEqualTo(ScheduleSuggestionStatus.APPLIED);
        assertThat(applied.get(0).getSystemNote()).isEqualTo("수업 일정의 이동시간을 " + LEAD + "분으로 저장했어요.");
        for (Long id : classIds) {
            assertThat(routines.get(id).getLeadMinutes()).as("routine %d", id).isEqualTo(LEAD);
        }
        assertThat(routines.get(8L).getLeadMinutes()).isNull();
        assertThat(routines.get(9L).getLeadMinutes()).isNull();

        // 5. 가용시간 재계산: 요일별 첫 수업 앞에만 lead, 감소량은 lead ∩ 기본 창.
        AvailabilityEstimateResult after = estimate();
        Map<LocalDate, RoutineOccurrence> firstClassByDate = firstClassByDate();
        List<BusyWindow> leads = after.busyWindows().stream().filter(w -> w.label().endsWith(" 이동")).toList();
        assertThat(leads).hasSize(firstClassByDate.size());
        for (RoutineOccurrence first : firstClassByDate.values()) {
            assertThat(leads).anyMatch(w -> w.label().equals(first.title() + " 이동")
                    && w.startAt().equals(first.startAt().minusMinutes(LEAD)) && w.endAt().equals(first.startAt()));
        }
        Map<LocalDate, Long> afterMinutes = minutesByDate(after.windows());
        for (LocalDate date = PLAN_FROM; !date.isAfter(PLAN_TO); date = date.plusDays(1)) {
            RoutineOccurrence first = firstClassByDate.get(date);
            long expectedDrop = first == null ? 0 : overlap(first.startAt().minusMinutes(LEAD), first.startAt(),
                    baseWindows.getOrDefault(date, List.of()));
            assertThat(before.getOrDefault(date, 0L) - afterMinutes.getOrDefault(date, 0L))
                    .as("%s(%s)", date, date.getDayOfWeek()).isEqualTo(expectedDrop);
        }
        // 수 09:00 수업의 lead(08:00~09:00)는 발생분으로는 있고, 둘째 수업(13:00) 앞에는 없다.
        assertThat(leads).anyMatch(w -> w.startAt().equals(LocalDateTime.of(2026, 9, 9, 8, 0)));
        assertThat(leads).noneMatch(w -> w.startAt().equals(LocalDateTime.of(2026, 9, 9, 12, 0)));

        // 다시 물을 것이 없다.
        assertThat(routineService.pendingLeadMinutes(USER_ID, PLAN_FROM, PLAN_TO)).isEmpty();
    }

    @Test
    void 시간을_말하지_않으면_후보는_생기되_값은_카드에서_고른_뒤에_저장된다() {
        seedTimetable();
        List<Long> classIds = activeClassIdsInPlan();

        ScheduleSuggestionService.Created created = suggestionService.createFromModelSuggestions(
                USER_ID, CONVERSATION_ID, MESSAGE_ID, List.of(leadIntent(null)));
        assertThat(created.saved()).hasSize(1);
        ScheduleSuggestionResponse candidate = created.saved().get(0);
        assertThat(candidate.getPayload().get("leadMinutes")).isNull();
        assertThat(SystemNotes.forTurn(List.of(), created.saved(), 0)).contains("승인할 때 고른 만큼");

        // 값 없이 승인하면 거부되고 아무것도 바뀌지 않는다.
        assertThatThrownBy(() -> suggestionService.applyBatch(USER_ID, List.of(candidate.getSuggestionId())))
                .isInstanceOf(com.jungwoo.project.memo.common.exception.BadRequestException.class);
        assertThat(routines.values()).allMatch(r -> r.getLeadMinutes() == null);
        assertThat(suggestions.get(candidate.getSuggestionId()).getStatus()).isEqualTo(ScheduleSuggestionStatus.PROPOSED);

        // 카드에서 30분을 고르면 그 값으로 저장된다.
        Map<String, Object> edited = new HashMap<>(candidate.getPayload());
        edited.put("leadMinutes", 30);
        ScheduleSuggestionResponse applied = suggestionService.apply(candidate.getSuggestionId(), USER_ID, edited);
        assertThat(applied.getSystemNote()).isEqualTo("수업 일정의 이동시간을 30분으로 저장했어요.");
        for (Long id : classIds) {
            assertThat(routines.get(id).getLeadMinutes()).isEqualTo(30);
        }
    }

    @Test
    void 대상을_못_찾으면_후보가_없고_확인_문장이_그렇게_말한다() {
        seedTimetable();

        ScheduleSuggestionService.Created created = suggestionService.createFromModelSuggestions(
                USER_ID, CONVERSATION_ID, MESSAGE_ID, List.of(new ScheduleSuggestion(
                        ScheduleSuggestionKind.ROUTINE_LEAD,
                        readTree("{\"targetRoutineIds\":null,\"targetHint\":\"xyz\",\"leadMinutes\":60}"))));

        assertThat(created.saved()).isEmpty();
        assertThat(created.unresolvedLeadTargets()).isEqualTo(1);
        assertThat(suggestions).isEmpty();
        assertThat(SystemNotes.forTurn(List.of(), created.saved(), created.unresolvedLeadTargets()))
                .isEqualTo("어느 일정 앞인지 찾지 못했어요. 일정 이름을 알려주시면 다시 만들게요.");
    }

    // ===== 계산 =====

    /** 계획 기간에 발생분이 있는, 본인의 활성 수업 루틴. 숫자를 박지 않는다. */
    private List<Long> activeClassIdsInPlan() {
        List<Long> ids = new ArrayList<>();
        for (RoutineOccurrence occurrence : occurrenceService.expand(USER_ID, PLAN_FROM, PLAN_TO)) {
            if (!occurrence.lead() && occurrence.courseId() != null && !ids.contains(occurrence.routineId())) {
                ids.add(occurrence.routineId());
            }
        }
        return ids;
    }

    private Map<LocalDate, RoutineOccurrence> firstClassByDate() {
        Map<LocalDate, RoutineOccurrence> first = new TreeMap<>();
        for (RoutineOccurrence occurrence : occurrenceService.expand(USER_ID, PLAN_FROM, PLAN_TO)) {
            if (occurrence.lead() || occurrence.courseId() == null) {
                continue;
            }
            first.merge(occurrence.startAt().toLocalDate(), occurrence, (a, b) -> a.startAt().isBefore(b.startAt()) ? a : b);
        }
        return first;
    }

    private AvailabilityEstimateResult estimate() {
        return availabilityService.estimate(USER_ID, PLAN_FROM, PLAN_TO, List.of(), List.of());
    }

    /** 아무 일정도 없는 사용자의 창 = 기본 후보 창. */
    private Map<LocalDate, List<AvailabilityWindow>> baseWindows() {
        Map<LocalDate, List<AvailabilityWindow>> result = new HashMap<>();
        for (AvailabilityWindow window : availabilityService.estimate(999L, PLAN_FROM, PLAN_TO, List.of(), List.of()).windows()) {
            result.computeIfAbsent(window.startAt().toLocalDate(), k -> new ArrayList<>()).add(window);
        }
        return result;
    }

    private static Map<LocalDate, Long> minutesByDate(List<AvailabilityWindow> windows) {
        Map<LocalDate, Long> minutes = new HashMap<>();
        for (AvailabilityWindow window : windows) {
            minutes.merge(window.startAt().toLocalDate(), Duration.between(window.startAt(), window.endAt()).toMinutes(), Long::sum);
        }
        return minutes;
    }

    private static long overlap(LocalDateTime start, LocalDateTime end, List<AvailabilityWindow> windows) {
        long total = 0;
        for (AvailabilityWindow window : windows) {
            LocalDateTime s = start.isAfter(window.startAt()) ? start : window.startAt();
            LocalDateTime e = end.isBefore(window.endAt()) ? end : window.endAt();
            if (s.isBefore(e)) {
                total += Duration.between(s, e).toMinutes();
            }
        }
        return total;
    }

    // ===== 고정자 =====

    private void add(Routine routine) {
        routines.put(routine.getRoutineId(), routine);
    }

    private static Routine routine(Long routineId, Long userId, Long courseId, String title,
                                   LocalTime start, LocalTime end, DayOfWeek... days) {
        return Routine.builder()
                .routineId(routineId).userId(userId).courseId(courseId).title(title)
                .startTime(start).endTime(end).effectiveFrom(SEMESTER_START)
                .daysOfWeek(new LinkedHashSet<>(List.of(days)))
                .build();
    }

    /** 매퍼가 돌려주는 행은 맵의 객체와 다른 인스턴스여야 한다 — 서비스가 응답용으로 set을 한다. */
    private static Routine copy(Routine r) {
        return Routine.builder()
                .routineId(r.getRoutineId()).userId(r.getUserId()).courseId(r.getCourseId()).title(r.getTitle())
                .location(r.getLocation()).startTime(r.getStartTime()).endTime(r.getEndTime())
                .leadMinutes(r.getLeadMinutes()).effectiveFrom(r.getEffectiveFrom()).effectiveUntil(r.getEffectiveUntil())
                .daysOfWeek(new LinkedHashSet<>(r.getDaysOfWeek())).deleted(r.isDeleted())
                .build();
    }

    private static List<Long> toLongs(Object value) {
        List<Long> ids = new ArrayList<>();
        for (Object o : (List<?>) value) {
            ids.add(((Number) o).longValue());
        }
        return ids;
    }

    private static JsonNode readTree(String json) {
        try {
            return new JacksonConfig().objectMapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
