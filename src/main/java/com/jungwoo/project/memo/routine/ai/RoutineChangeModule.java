package com.jungwoo.project.memo.routine.ai;

import com.jungwoo.project.memo.ai.change.ChangeCheck;
import com.jungwoo.project.memo.ai.change.ChangeModule;
import com.jungwoo.project.memo.ai.change.ChangeOp;
import com.jungwoo.project.memo.routine.RoutineExceptionMapper;
import com.jungwoo.project.memo.routine.RoutineReader;
import com.jungwoo.project.memo.routine.RoutineService;
import com.jungwoo.project.memo.routine.domain.Routine;
import com.jungwoo.project.memo.routine.domain.RoutineException;
import com.jungwoo.project.memo.routine.domain.RoutineExceptionType;
import com.jungwoo.project.memo.routine.dto.RoutineExceptionSaveRequest;
import com.jungwoo.project.memo.routine.dto.RoutineResponse;
import com.jungwoo.project.memo.routine.dto.RoutineSaveRequest;
import com.jungwoo.project.memo.scheduling.scenario.Scenario;
import com.jungwoo.project.memo.scheduling.scenario.ScenarioProjection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 반복 일정의 상담 변경: 그날 회차 빼기·옮기기·되돌리기, 규칙 만들기·고치기. 업무 규칙은 {@link RoutineService}가 갖는다
 * (그날이 실제 회차인지, 한 날짜에 예외 하나, 수업 회차 학습 이벤트). 여기서는 감싼다.
 *
 * <p>반복 일정에는 version이 없다. 그래서 검토 당시 규칙 전체(본체·요일·예외)의 해시를 op에 담고, 적용 때 잠근 최신 상태와 비교한다 —
 * 제목만 바뀌어도 오래된 내용으로 덮지 않는다. 회차는 언제나 (routineId, 원래 날짜)로 가리킨다.
 */
@Component
@RequiredArgsConstructor
public class RoutineChangeModule implements ChangeModule {

    private final RoutineReader routineReader;
    private final RoutineExceptionMapper exceptionMapper;
    private final RoutineService routineService;
    private final com.jungwoo.project.memo.routine.RoutineOccurrenceService occurrenceService;

    /** 근무 회차와 이 시간 안에 붙어 있는 이동 회차를 "관련 있어 보이는" 항목으로 낸다(저장된 연결이 아니다). */
    static final long LINK_GAP_MINUTES = 30;

    // ===== op =====

    /** 그날 회차만 뺀다. 이후 반복은 그대로다. */
    public record SkipOccurrence(Long routineId, LocalDate date, String title, LocalTime start, LocalTime end,
                                 String stateHash) implements ChangeOp, ScenarioProjection {
        public String type() {
            return "ROUTINE_SKIP";
        }

        public String targetKey() {
            return "routine:" + routineId + "@" + date;
        }

        public String summary() {
            return date.getMonthValue() + "/" + date.getDayOfMonth() + " " + title + "(" + start + "~" + end + ") 이날만 빼기";
        }

        public List<LocalDate> affectedDates() {
            return List.of(date);
        }

        public void project(Scenario.Builder s) {
            s.routines().putException(RoutineException.builder().routineId(routineId).exceptionDate(date)
                    .type(RoutineExceptionType.SKIP).build());
        }
    }

    /** 그날 회차를 다른 날·시각으로 옮긴다(보강·대타). */
    public record MoveOccurrence(Long routineId, LocalDate date, LocalDate movedDate, LocalTime movedStart,
                                 LocalTime movedEnd, String title, String stateHash) implements ChangeOp, ScenarioProjection {
        public String type() {
            return "ROUTINE_MOVE";
        }

        public String targetKey() {
            return "routine:" + routineId + "@" + date;
        }

        public String summary() {
            return date.getMonthValue() + "/" + date.getDayOfMonth() + " " + title + " → " + movedDate.getMonthValue() + "/"
                    + movedDate.getDayOfMonth() + (movedStart == null ? "" : " " + movedStart + "~" + movedEnd) + "로 옮기기";
        }

        public List<LocalDate> affectedDates() {
            return List.of(date, movedDate);
        }

        public void project(Scenario.Builder s) {
            s.routines().putException(RoutineException.builder().routineId(routineId).exceptionDate(date)
                    .type(RoutineExceptionType.MOVED).movedDate(movedDate).movedStartTime(movedStart)
                    .movedEndTime(movedEnd).build());
        }
    }

    /** 그날 빼거나 옮긴 것을 되돌린다(원래 회차가 돌아온다). */
    public record RestoreOccurrence(Long routineId, LocalDate date, Long routineExceptionId, String title,
                                    String stateHash) implements ChangeOp, ScenarioProjection {
        public String type() {
            return "ROUTINE_RESTORE";
        }

        public String targetKey() {
            return "routine:" + routineId + "@" + date;
        }

        public String summary() {
            return date.getMonthValue() + "/" + date.getDayOfMonth() + " " + title + " 원래대로 되돌리기";
        }

        public List<LocalDate> affectedDates() {
            return List.of(date);
        }

        public void project(Scenario.Builder s) {
            s.routines().removeException(routineId, date);
        }
    }

    /** 새 반복 일정. */
    public record CreateRoutine(Long courseId, String title, String location, Set<DayOfWeek> daysOfWeek, LocalTime startTime,
                                LocalTime endTime, LocalDate effectiveFrom, LocalDate effectiveUntil)
            implements ChangeOp, ScenarioProjection {
        public String type() {
            return "ROUTINE_CREATE";
        }

        public String targetKey() {
            return "routine:new:" + title + ":" + startTime + ":" + daysOfWeek;
        }

        public String summary() {
            return "반복 일정 만들기: " + title + " " + daysOfWeek + " " + startTime + "~" + endTime;
        }

        public List<LocalDate> affectedDates() {
            return List.of(effectiveFrom);
        }

        public void project(Scenario.Builder s) {
            s.routines().putRoutine(Routine.builder().routineId(-(long) Math.abs(targetKey().hashCode()) - 1).title(title)
                    .courseId(courseId).location(location).startTime(startTime).endTime(endTime)
                    .effectiveFrom(effectiveFrom).effectiveUntil(effectiveUntil)
                    .daysOfWeek(new LinkedHashSet<>(daysOfWeek)).build());
        }
    }

    /** 반복 일정 규칙을 바꾼다(전체 교체 — 종료일 설정 포함). 기존 예외와 맞지 않으면 서비스가 거절한다. */
    public record UpdateRoutine(Long routineId, Long courseId, String title, String location, Set<DayOfWeek> daysOfWeek,
                                LocalTime startTime, LocalTime endTime, LocalDate effectiveFrom, LocalDate effectiveUntil,
                                String stateHash) implements ChangeOp, ScenarioProjection {
        public String type() {
            return "ROUTINE_UPDATE";
        }

        public String targetKey() {
            return "routine:" + routineId;
        }

        public String summary() {
            return "반복 일정 고치기: " + title + " " + daysOfWeek + " " + startTime + "~" + endTime
                    + (effectiveUntil == null ? "" : " (" + effectiveUntil + "까지)");
        }

        public List<LocalDate> affectedDates() {
            return effectiveUntil == null ? List.of(effectiveFrom) : List.of(effectiveFrom, effectiveUntil);
        }

        public void project(Scenario.Builder s) {
            s.routines().putRoutine(Routine.builder().routineId(routineId).title(title).courseId(courseId).location(location)
                    .startTime(startTime).endTime(endTime).effectiveFrom(effectiveFrom).effectiveUntil(effectiveUntil)
                    .daysOfWeek(new LinkedHashSet<>(daysOfWeek)).build());
        }
    }

    @Override
    public Map<String, Class<? extends ChangeOp>> opTypes() {
        return Map.of("ROUTINE_SKIP", SkipOccurrence.class, "ROUTINE_MOVE", MoveOccurrence.class,
                "ROUTINE_RESTORE", RestoreOccurrence.class, "ROUTINE_CREATE", CreateRoutine.class,
                "ROUTINE_UPDATE", UpdateRoutine.class);
    }

    @Override
    public int lockOrder() {
        return 30;
    }

    // ===== 확인 =====

    @Override
    public ChangeCheck check(Long userId, ChangeOp op) {
        return switch (op) {
            case SkipOccurrence skip -> {
                Routine r = owned(userId, skip.routineId());
                if (r == null) {
                    yield ChangeCheck.fail(ChangeCheck.NOT_FOUND, "그 반복 일정을 찾지 못했어요", op);
                }
                if (!occursOn(r, skip.date())) {
                    yield ChangeCheck.fail(ChangeCheck.INVALID, skip.date() + "은 " + r.getTitle() + "이 없는 날이에요", op);
                }
                RoutineException existing = exceptionMapper.findByRoutineIdAndDate(r.getRoutineId(), skip.date());
                if (existing != null) {
                    yield existing.getType() == RoutineExceptionType.SKIP
                            ? ChangeCheck.fail(ChangeCheck.ALREADY_DONE, "그날은 이미 빠져 있어요", op)
                            : ChangeCheck.fail(ChangeCheck.CONFLICT, "그날은 이미 다른 날로 옮겨져 있어요", op);
                }
                yield ChangeCheck.ok(new SkipOccurrence(r.getRoutineId(), skip.date(), r.getTitle(), r.getStartTime(),
                        r.getEndTime(), stateHash(r)));
            }
            case MoveOccurrence move -> {
                Routine r = owned(userId, move.routineId());
                if (r == null) {
                    yield ChangeCheck.fail(ChangeCheck.NOT_FOUND, "그 반복 일정을 찾지 못했어요", op);
                }
                if (!occursOn(r, move.date()) || move.movedDate() == null
                        || (move.movedStart() == null) != (move.movedEnd() == null)) {
                    yield ChangeCheck.fail(ChangeCheck.INVALID, "옮길 회차나 옮길 날짜가 맞지 않아요", op);
                }
                if (exceptionMapper.findByRoutineIdAndDate(r.getRoutineId(), move.date()) != null) {
                    yield ChangeCheck.fail(ChangeCheck.CONFLICT, "그날은 이미 빠지거나 옮겨져 있어요", op);
                }
                yield ChangeCheck.ok(new MoveOccurrence(r.getRoutineId(), move.date(), move.movedDate(), move.movedStart(),
                        move.movedEnd(), r.getTitle(), stateHash(r)));
            }
            case RestoreOccurrence restore -> {
                Routine r = owned(userId, restore.routineId());
                if (r == null) {
                    yield ChangeCheck.fail(ChangeCheck.NOT_FOUND, "그 반복 일정을 찾지 못했어요", op);
                }
                RoutineException existing = exceptionMapper.findByRoutineIdAndDate(r.getRoutineId(), restore.date());
                if (existing == null) {
                    yield ChangeCheck.fail(ChangeCheck.ALREADY_DONE, "그날은 원래대로예요", op);
                }
                yield ChangeCheck.ok(new RestoreOccurrence(r.getRoutineId(), restore.date(),
                        existing.getRoutineExceptionId(), r.getTitle(), stateHash(r)));
            }
            case CreateRoutine create -> create.title() == null || create.title().isBlank() || create.daysOfWeek() == null
                    || create.daysOfWeek().isEmpty() || create.startTime() == null || create.endTime() == null
                    || create.startTime().equals(create.endTime()) || create.effectiveFrom() == null
                    ? ChangeCheck.fail(ChangeCheck.INVALID, "제목·요일·시각·시작일이 필요해요", op)
                    : ChangeCheck.ok(op);
            case UpdateRoutine update -> {
                Routine r = owned(userId, update.routineId());
                if (r == null) {
                    yield ChangeCheck.fail(ChangeCheck.NOT_FOUND, "그 반복 일정을 찾지 못했어요", op);
                }
                yield ChangeCheck.ok(new UpdateRoutine(r.getRoutineId(), update.courseId(), update.title(), update.location(),
                        update.daysOfWeek(), update.startTime(), update.endTime(), update.effectiveFrom(),
                        update.effectiveUntil(), stateHash(r)));
            }
            default -> throw new IllegalArgumentException(op.type());
        };
    }

    @Override
    public void lock(Long userId, List<ChangeOp> ops) {
        // 사용자의 반복 일정 전부를 잠금 조회한다 — 전개(expandForUpdate)·예외 변경이 잡는 부모 행과 같은 행이다.
        routineReader.findAllWithWeekdaysForUpdate(userId);
    }

    @Override
    public ChangeCheck recheck(Long userId, ChangeOp op) {
        String hash = switch (op) {
            case SkipOccurrence s -> s.stateHash();
            case MoveOccurrence m -> m.stateHash();
            case RestoreOccurrence r -> r.stateHash();
            case UpdateRoutine u -> u.stateHash();
            default -> null;
        };
        if (hash == null) {
            return ChangeCheck.ok(op);
        }
        Long routineId = switch (op) {
            case SkipOccurrence s -> s.routineId();
            case MoveOccurrence m -> m.routineId();
            case RestoreOccurrence r -> r.routineId();
            case UpdateRoutine u -> u.routineId();
            default -> null;
        };
        Routine current = owned(userId, routineId);
        if (current == null) {
            return ChangeCheck.fail(ChangeCheck.STALE, "그 반복 일정이 지워졌어요", op);
        }
        return hash.equals(stateHash(current)) ? ChangeCheck.ok(op)
                : ChangeCheck.fail(ChangeCheck.STALE, current.getTitle() + "이 검토한 뒤 바뀌었어요", op);
    }

    @Override
    public Map<String, Object> apply(Long userId, ChangeOp op) {
        return switch (op) {
            case SkipOccurrence s -> Map.of("routineExceptionId", routineService.addException(userId, s.routineId(),
                    new RoutineExceptionSaveRequest(s.date(), RoutineExceptionType.SKIP, null, null, null, null,
                            "상담에서 이날만 빼기")).routineExceptionId());
            case MoveOccurrence m -> Map.of("routineExceptionId", routineService.addException(userId, m.routineId(),
                    new RoutineExceptionSaveRequest(m.date(), RoutineExceptionType.MOVED, m.movedDate(), m.movedStart(),
                            m.movedEnd(), null, "상담에서 옮기기")).routineExceptionId());
            case RestoreOccurrence r -> {
                routineService.deleteException(userId, r.routineId(), r.routineExceptionId());
                yield Map.of("restoredExceptionId", r.routineExceptionId());
            }
            case CreateRoutine c -> {
                RoutineResponse created = routineService.create(userId, new RoutineSaveRequest(c.courseId(), c.title(),
                        c.location(), c.daysOfWeek(), c.startTime(), c.endTime(), c.effectiveFrom(), c.effectiveUntil()));
                yield Map.of("routineId", created.routineId());
            }
            case UpdateRoutine u -> {
                routineService.update(userId, u.routineId(), new RoutineSaveRequest(u.courseId(), u.title(), u.location(),
                        u.daysOfWeek(), u.startTime(), u.endTime(), u.effectiveFrom(), u.effectiveUntil()));
                yield Map.of("routineId", u.routineId());
            }
            default -> throw new IllegalArgumentException(op.type());
        };
    }

    /**
     * 그날 회차를 빼면, 제목에 "이동"이 있고 그 회차의 시작·끝에 30분 안으로 붙어 있는 다른 반복 회차를 함께 뺄지 고를 항목으로 낸다.
     * 반복 일정 사이에는 저장된 연결이 없다 — 제목·시각으로 추정한 것이라 기본 미선택이다. 자정을 넘는 근무의 새벽 이동(다음 날
     * 회차)도 찾는다.
     */
    @Override
    public List<LinkedChange> linked(Long userId, ChangeOp op) {
        if (!(op instanceof SkipOccurrence skip)) {
            return List.of();
        }
        List<com.jungwoo.project.memo.routine.domain.RoutineOccurrence> around =
                occurrenceService.expand(userId, skip.date(), skip.date().plusDays(1));
        var shift = around.stream().filter(o -> o.routineId().equals(skip.routineId()) && o.sourceDate().equals(skip.date())
                && !o.moved()).findFirst().orElse(null);
        if (shift == null) {
            return List.of();
        }
        List<LinkedChange> out = new ArrayList<>();
        for (var o : around) {
            if (o.routineId().equals(skip.routineId()) || o.moved() || o.title() == null || !o.title().contains("이동")) {
                continue;
            }
            long beforeGap = java.time.Duration.between(o.endAt(), shift.startAt()).toMinutes();
            long afterGap = java.time.Duration.between(shift.endAt(), o.startAt()).toMinutes();
            boolean adjacent = (beforeGap >= 0 && beforeGap <= LINK_GAP_MINUTES) || (afterGap >= 0 && afterGap <= LINK_GAP_MINUTES);
            if (!adjacent) {
                continue;
            }
            ChangeCheck check = check(userId, new SkipOccurrence(o.routineId(), o.sourceDate(), null, null, null, null));
            if (check.isOk()) {
                out.add(new LinkedChange(check.op(), false, "근무 " + (beforeGap >= 0 && beforeGap <= LINK_GAP_MINUTES ? "앞" : "뒤")
                        + "에 붙은 이동으로 보여요(제목·시각으로 추정 — 저장된 연결은 아니에요)"));
            }
        }
        return out;
    }

    // ===== 내부 =====

    private Routine owned(Long userId, Long routineId) {
        if (routineId == null) {
            return null;
        }
        Routine r = routineReader.findOneWithWeekdays(userId, routineId);
        return r == null || r.isDeleted() ? null : r;
    }

    static boolean occursOn(Routine r, LocalDate date) {
        return date != null && !date.isBefore(r.getEffectiveFrom())
                && (r.getEffectiveUntil() == null || !date.isAfter(r.getEffectiveUntil()))
                && r.getDaysOfWeek() != null && r.getDaysOfWeek().contains(date.getDayOfWeek());
    }

    /** 규칙 전체(본체·요일·예외)의 해시. 제목만 바뀌어도 달라진다. */
    String stateHash(Routine r) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.getRoutineId()).append('|').append(r.getTitle()).append('|').append(r.getLocation()).append('|')
                .append(r.getCourseId()).append('|').append(r.getStartTime()).append('|').append(r.getEndTime()).append('|')
                .append(r.getEffectiveFrom()).append('|').append(r.getEffectiveUntil()).append('|');
        List<DayOfWeek> days = new ArrayList<>(r.getDaysOfWeek() == null ? Set.of() : r.getDaysOfWeek());
        days.sort(Comparator.naturalOrder());
        sb.append(days).append('|');
        List<RoutineException> exceptions = new ArrayList<>(exceptionMapper.findByRoutineId(r.getRoutineId()));
        exceptions.sort(Comparator.comparing(RoutineException::getExceptionDate));
        for (RoutineException e : exceptions) {
            sb.append(e.getExceptionDate()).append(e.getType()).append(e.getMovedDate()).append(e.getMovedStartTime())
                    .append(e.getMovedEndTime()).append(Objects.toString(e.getMovedLocation(), "")).append(';');
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
