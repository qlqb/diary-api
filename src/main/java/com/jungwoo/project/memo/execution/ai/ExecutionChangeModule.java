package com.jungwoo.project.memo.execution.ai;

import com.jungwoo.project.memo.ai.change.ChangeCheck;
import com.jungwoo.project.memo.ai.change.ChangeModule;
import com.jungwoo.project.memo.ai.change.ChangeOp;
import com.jungwoo.project.memo.course.CourseMapper;
import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.execution.ExecutionItemMapper;
import com.jungwoo.project.memo.execution.ExecutionItemService;
import com.jungwoo.project.memo.execution.domain.ExecutionItem;
import com.jungwoo.project.memo.execution.domain.ExecutionPriority;
import com.jungwoo.project.memo.execution.domain.ExecutionStatus;
import com.jungwoo.project.memo.execution.domain.PlacementType;
import com.jungwoo.project.memo.execution.dto.ExecutionItemCompleteRequest;
import com.jungwoo.project.memo.execution.dto.ExecutionItemCreateRequest;
import com.jungwoo.project.memo.execution.dto.ExecutionItemHoldRequest;
import com.jungwoo.project.memo.execution.dto.ExecutionItemMoveRequest;
import com.jungwoo.project.memo.execution.dto.ExecutionItemPartialRequest;
import com.jungwoo.project.memo.execution.dto.ExecutionItemReduceRequest;
import com.jungwoo.project.memo.execution.dto.ExecutionItemReopenRequest;
import com.jungwoo.project.memo.execution.dto.ExecutionItemResumeRequest;
import com.jungwoo.project.memo.execution.dto.ExecutionItemUnscheduleRequest;
import com.jungwoo.project.memo.scheduling.scenario.Scenario;
import com.jungwoo.project.memo.scheduling.scenario.ScenarioProjection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 실행 조각의 상담 변경: 완료(실제 걸린 시간)·일부 수행·완료 취소·보류·보류 복귀·배치 해제·옮기기·줄이기·만들기. 상태 전이·version·기록·
 * 학습 이벤트는 {@link ExecutionItemService}가 그대로 한다.
 *
 * <p>op에는 검토 당시 항목(before)을 담는다 — 투영(가정 상태)은 그 값에 서비스와 같은 변화를 적용하고, 적용 때는 version으로 그사이
 * 바뀌었는지 본다. 완료는 수행 기록이지 이해의 증거가 아니다(기존 의미 그대로).
 */
@Component
@RequiredArgsConstructor
public class ExecutionChangeModule implements ChangeModule {

    private final ExecutionItemMapper itemMapper;
    private final ExecutionItemService itemService;
    private final CourseMapper courseMapper;

    /** 기존 항목에 대한 op의 공통 모양. */
    public interface ItemOp extends ChangeOp, ScenarioProjection {
        ExecutionItem before();

        default Long executionItemId() {
            return before().getExecutionItemId();
        }

        default Long version() {
            return before().getVersion();
        }

        default String targetKey() {
            return "execution:" + executionItemId();
        }

        default List<LocalDate> affectedDates() {
            return before().getScheduledDate() == null ? List.of() : List.of(before().getScheduledDate());
        }

        default String title() {
            return before().getTitle();
        }
    }

    public record CompleteItem(ExecutionItem before, Integer actualMinutes, String note) implements ItemOp {
        public String type() {
            return "EXECUTION_COMPLETE";
        }

        public String summary() {
            return title() + " 완료" + (actualMinutes == null ? "" : "(실제 " + actualMinutes + "분)");
        }

        public void project(Scenario.Builder s) {
            s.execution().put(changed(before, i -> i.setStatus(ExecutionStatus.DONE)));
        }
    }

    public record PartialItem(ExecutionItem before, Integer completionPercent, Integer actualMinutes, String note)
            implements ItemOp {
        public String type() {
            return "EXECUTION_PARTIAL";
        }

        public String summary() {
            return title() + " 일부 수행(" + (completionPercent == null ? 50 : completionPercent) + "%"
                    + (actualMinutes == null ? "" : ", 실제 " + actualMinutes + "분") + ") — 남은 분량은 새 항목";
        }

        public void project(Scenario.Builder s) {
            int percent = completionPercent == null ? 50 : completionPercent;
            s.execution().put(changed(before, i -> i.setStatus(ExecutionStatus.DONE)));
            // ExecutionItemService.createRemainderItem과 같은 규칙: 같은 날·마감·과목, 날짜가 없으면 같은 계획 기간의 날짜 미정.
            Integer minutes = before.getExpectedMinutes() == null ? null
                    : Math.max(5, (int) Math.round(before.getExpectedMinutes() * (100 - percent) / 100.0));
            s.execution().put(ExecutionItem.builder().executionItemId(-before.getExecutionItemId())
                    .userId(before.getUserId()).topicId(before.getTopicId()).courseId(before.getCourseId())
                    .sourceExecutionItemId(before.getExecutionItemId()).title(before.getTitle() + " (남은 분량)")
                    .placementType(before.getScheduledDate() == null ? PlacementType.UNSCHEDULED : PlacementType.DATE_ONLY)
                    .scheduledDate(before.getScheduledDate())
                    .planningStartDate(before.getScheduledDate() == null ? before.getPlanningStartDate() : null)
                    .planningEndDate(before.getScheduledDate() == null ? before.getPlanningEndDate() : null)
                    .deadlineAt(before.getDeadlineAt()).expectedMinutes(minutes).status(ExecutionStatus.PLANNED)
                    .priority(before.getPriority()).version(0L).isDeleted(false).build());
        }
    }

    public record ReopenItem(ExecutionItem before) implements ItemOp {
        public String type() {
            return "EXECUTION_REOPEN";
        }

        public String summary() {
            return title() + " 완료 취소(다시 할 일로)";
        }

        public void project(Scenario.Builder s) {
            s.execution().put(changed(before, i -> i.setStatus(ExecutionStatus.PLANNED)));
        }
    }

    public record ResumeItem(ExecutionItem before) implements ItemOp {
        public String type() {
            return "EXECUTION_RESUME";
        }

        public String summary() {
            return title() + " 보류 풀기";
        }

        public void project(Scenario.Builder s) {
            s.execution().put(changed(before, i -> i.setStatus(ExecutionStatus.PLANNED)));
        }
    }

    public record HoldItem(ExecutionItem before) implements ItemOp {
        public String type() {
            return "EXECUTION_HOLD";
        }

        public String summary() {
            return title() + " 보류";
        }

        public void project(Scenario.Builder s) {
            s.execution().put(changed(before, i -> i.setStatus(ExecutionStatus.HOLD)));
        }
    }

    /** 할 일로는 남기고 날짜·시각만 뗀다(보류와 다르다). */
    public record UnscheduleItem(ExecutionItem before, LocalDate planningStartDate, LocalDate planningEndDate) implements ItemOp {
        public String type() {
            return "EXECUTION_UNSCHEDULE";
        }

        public String summary() {
            return title() + " 시간 배치만 빼기(할 일로는 남김)";
        }

        public void project(Scenario.Builder s) {
            s.execution().put(changed(before, i -> {
                i.setPlacementType(PlacementType.UNSCHEDULED);
                i.setScheduledDate(null);
                i.setScheduledStartAt(null);
                i.setScheduledEndAt(null);
                i.setPlanningStartDate(planningStartDate);
                i.setPlanningEndDate(planningEndDate);
            }));
        }
    }

    public record MoveItem(ExecutionItem before, LocalDate toDate, LocalTime startTime, LocalTime endTime) implements ItemOp {
        public String type() {
            return "EXECUTION_MOVE";
        }

        public String summary() {
            return title() + " → " + toDate.getMonthValue() + "/" + toDate.getDayOfMonth()
                    + (startTime == null ? "" : " " + startTime + "~" + endTime) + "로 옮기기";
        }

        public List<LocalDate> affectedDates() {
            List<LocalDate> out = new ArrayList<>(ItemOp.super.affectedDates());
            out.add(toDate);
            return out;
        }

        public void project(Scenario.Builder s) {
            s.execution().put(changed(before, i -> {
                i.setScheduledDate(toDate);
                if (before.getPlacementType() == PlacementType.TIME_FIXED) {
                    if (startTime != null) {
                        i.setScheduledStartAt(toDate.atTime(startTime));
                        i.setScheduledEndAt(endTime.equals(LocalTime.MIDNIGHT) ? toDate.plusDays(1).atStartOfDay()
                                : toDate.atTime(endTime));
                        // 서비스와 같은 규칙: 시각이 정해진 항목의 분량은 그 시각 길이다
                        i.setExpectedMinutes(com.jungwoo.project.memo.execution.domain.PlacementDuration.resolve(
                                PlacementType.TIME_FIXED, i.getScheduledStartAt(), i.getScheduledEndAt(),
                                before.getExpectedMinutes()));
                    } else {
                        long days = java.time.temporal.ChronoUnit.DAYS.between(before.getScheduledDate(), toDate);
                        i.setScheduledStartAt(before.getScheduledStartAt().plusDays(days));
                        i.setScheduledEndAt(before.getScheduledEndAt().plusDays(days));
                    }
                }
            }));
        }
    }

    public record ReduceItem(ExecutionItem before, Integer expectedMinutes, String reducedTitle) implements ItemOp {
        public String type() {
            return "EXECUTION_REDUCE";
        }

        public String summary() {
            return title() + " 줄이기" + (expectedMinutes == null ? "" : "(" + expectedMinutes + "분)");
        }

        public void project(Scenario.Builder s) {
            s.execution().put(changed(before, i -> {
                if (expectedMinutes != null) {
                    i.setExpectedMinutes(expectedMinutes);
                    if (before.getPlacementType() == PlacementType.TIME_FIXED) {
                        i.setScheduledEndAt(before.getScheduledStartAt().plusMinutes(expectedMinutes));
                    }
                }
                if (reducedTitle != null) {
                    i.setTitle(reducedTitle);
                }
            }));
        }
    }

    public record CreateItem(String title, Long courseId, LocalDate scheduledDate, LocalDateTime scheduledStartAt,
                             LocalDateTime scheduledEndAt, Integer expectedMinutes, ExecutionPriority priority)
            implements ChangeOp, ScenarioProjection {
        public String type() {
            return "EXECUTION_CREATE";
        }

        public String targetKey() {
            return "execution:new:" + title + ":" + scheduledDate + ":" + scheduledStartAt;
        }

        public String summary() {
            return "할 일 만들기: " + title + " " + scheduledDate
                    + (scheduledStartAt == null ? "" : " " + scheduledStartAt.toLocalTime() + "~" + scheduledEndAt.toLocalTime());
        }

        public List<LocalDate> affectedDates() {
            return scheduledDate == null ? List.of() : List.of(scheduledDate);
        }

        public void project(Scenario.Builder s) {
            PlacementType placement = scheduledStartAt == null ? PlacementType.DATE_ONLY : PlacementType.TIME_FIXED;
            // ExecutionItemService.create와 같은 규칙: 시각이 있으면 분량은 시각 길이, 우선순위 기본은 SHOULD
            s.execution().put(ExecutionItem.builder().executionItemId(s.nextTempId())
                    .title(title).courseId(courseId).scheduledDate(scheduledDate).scheduledStartAt(scheduledStartAt)
                    .scheduledEndAt(scheduledEndAt).placementType(placement)
                    .expectedMinutes(com.jungwoo.project.memo.execution.domain.PlacementDuration.resolve(placement,
                            scheduledStartAt, scheduledEndAt, expectedMinutes))
                    .priority(priority != null ? priority : ExecutionPriority.SHOULD).status(ExecutionStatus.PLANNED)
                    .version(0L).isDeleted(false).build());
        }
    }

    @Override
    public Map<String, Class<? extends ChangeOp>> opTypes() {
        return Map.of("EXECUTION_COMPLETE", CompleteItem.class, "EXECUTION_PARTIAL", PartialItem.class,
                "EXECUTION_REOPEN", ReopenItem.class, "EXECUTION_RESUME", ResumeItem.class, "EXECUTION_HOLD", HoldItem.class,
                "EXECUTION_UNSCHEDULE", UnscheduleItem.class, "EXECUTION_MOVE", MoveItem.class,
                "EXECUTION_REDUCE", ReduceItem.class, "EXECUTION_CREATE", CreateItem.class);
    }

    @Override
    public Map<String, String> toolArgs() {
        return Map.of(
                "EXECUTION_COMPLETE", "{executionItemId, actualMinutes?, note?} - 다 했다(실제 걸린 분). 이해했다는 뜻은 아니다",
                "EXECUTION_PARTIAL", "{executionItemId, completionPercent?(1-99), actualMinutes?, note?} - 일부만 했다. 남은 분량은 새 할 일",
                "EXECUTION_REOPEN", "{executionItemId} - 완료를 취소하고 다시 할 일로",
                "EXECUTION_RESUME", "{executionItemId} - 보류를 풀기",
                "EXECUTION_HOLD", "{executionItemId} - 보류(삭제 아님)",
                "EXECUTION_UNSCHEDULE", "{executionItemId, planningStartDate?, planningEndDate?} - 할 일로는 두고 날짜/시각만 떼기",
                "EXECUTION_MOVE", "{executionItemId, toDate, startTime?, endTime?} - 다른 날/시각으로 옮기기",
                "EXECUTION_REDUCE", "{executionItemId, expectedMinutes?, reducedTitle?} - 분량 줄이기",
                "EXECUTION_CREATE", "{title, scheduledDate, scheduledStartAt?, scheduledEndAt?, expectedMinutes?, courseId?} - 할 일 하나 만들기");
    }

    @Override
    public ChangeOp fromToolArgs(String type, com.fasterxml.jackson.databind.JsonNode a) {
        if ("EXECUTION_CREATE".equals(type)) {
            return new CreateItem(com.jungwoo.project.memo.ai.change.ToolArgs.text(a, "title"), com.jungwoo.project.memo.ai.change.ToolArgs.optionalId(a, "courseId"), com.jungwoo.project.memo.ai.change.ToolArgs.date(a, "scheduledDate"),
                    com.jungwoo.project.memo.ai.change.ToolArgs.optionalDateTime(a, "scheduledStartAt"), com.jungwoo.project.memo.ai.change.ToolArgs.optionalDateTime(a, "scheduledEndAt"),
                    com.jungwoo.project.memo.ai.change.ToolArgs.optionalInt(a, "expectedMinutes"), null);
        }
        // 대상 항목은 id만 담는다 - check가 지금 행으로 채운다.
        ExecutionItem target = ExecutionItem.builder().executionItemId(com.jungwoo.project.memo.ai.change.ToolArgs.id(a, "executionItemId")).build();
        return switch (type) {
            case "EXECUTION_COMPLETE" -> new CompleteItem(target, com.jungwoo.project.memo.ai.change.ToolArgs.optionalInt(a, "actualMinutes"), com.jungwoo.project.memo.ai.change.ToolArgs.text(a, "note"));
            case "EXECUTION_PARTIAL" -> new PartialItem(target, com.jungwoo.project.memo.ai.change.ToolArgs.optionalInt(a, "completionPercent"),
                    com.jungwoo.project.memo.ai.change.ToolArgs.optionalInt(a, "actualMinutes"), com.jungwoo.project.memo.ai.change.ToolArgs.text(a, "note"));
            case "EXECUTION_REOPEN" -> new ReopenItem(target);
            case "EXECUTION_RESUME" -> new ResumeItem(target);
            case "EXECUTION_HOLD" -> new HoldItem(target);
            case "EXECUTION_UNSCHEDULE" -> new UnscheduleItem(target, com.jungwoo.project.memo.ai.change.ToolArgs.optionalDate(a, "planningStartDate"),
                    com.jungwoo.project.memo.ai.change.ToolArgs.optionalDate(a, "planningEndDate"));
            case "EXECUTION_MOVE" -> new MoveItem(target, com.jungwoo.project.memo.ai.change.ToolArgs.date(a, "toDate"), com.jungwoo.project.memo.ai.change.ToolArgs.optionalTime(a, "startTime"),
                    com.jungwoo.project.memo.ai.change.ToolArgs.optionalTime(a, "endTime"));
            case "EXECUTION_REDUCE" -> new ReduceItem(target, com.jungwoo.project.memo.ai.change.ToolArgs.optionalInt(a, "expectedMinutes"), com.jungwoo.project.memo.ai.change.ToolArgs.text(a, "reducedTitle"));
            default -> throw new IllegalArgumentException("모르는 변경: " + type);
        };
    }

    /** op 안의 검토 당시 엔티티 스냅샷은 파생 getter가 직렬화돼 되읽을 수 없다 — 그 필드는 읽을 때 무시한다. */
    @Override
    public void customizeJson(com.fasterxml.jackson.databind.ObjectMapper mapper) {
        mapper.addMixIn(com.jungwoo.project.memo.execution.domain.ExecutionItem.class, LenientSnapshot.class);
    }

    @Override
    public int lockOrder() {
        return 20;
    }

    @Override
    public ChangeCheck check(Long userId, ChangeOp op) {
        if (op instanceof CreateItem c) {
            if (c.title() == null || c.title().isBlank() || c.scheduledDate() == null) {
                return ChangeCheck.fail(ChangeCheck.INVALID, "제목과 날짜가 필요해요", op);
            }
            // 서비스와 같은 규칙: 시각은 시작·끝을 함께, 끝이 뒤, 시작은 그 날짜(끝은 다음 날 00:00까지 허용)
            if ((c.scheduledStartAt() == null) != (c.scheduledEndAt() == null)) {
                return ChangeCheck.fail(ChangeCheck.INVALID, "시작과 끝 시각을 함께 정해야 해요", op);
            }
            if (c.scheduledStartAt() != null && (!c.scheduledEndAt().isAfter(c.scheduledStartAt())
                    || !c.scheduledStartAt().toLocalDate().equals(c.scheduledDate())
                    || (!c.scheduledEndAt().toLocalDate().equals(c.scheduledDate())
                    && !c.scheduledEndAt().equals(c.scheduledDate().plusDays(1).atStartOfDay())))) {
                return ChangeCheck.fail(ChangeCheck.INVALID, "시각이 맞지 않아요(그날 안, 끝이 뒤)", op);
            }
            if (c.courseId() != null) {
                Course course = courseMapper.findByIdAndUserId(c.courseId(), userId);
                if (course == null) {
                    return ChangeCheck.fail(ChangeCheck.NOT_FOUND, "그 프로젝트를 찾지 못했어요", op);
                }
            }
            return ChangeCheck.ok(op);
        }
        ItemOp itemOp = (ItemOp) op;
        Long id = itemOp.before() == null ? null : itemOp.before().getExecutionItemId();
        ExecutionItem current = id == null ? null : itemMapper.findByIdAndUserId(id, userId);
        if (current == null || Boolean.TRUE.equals(current.getIsDeleted())) {
            return ChangeCheck.fail(ChangeCheck.NOT_FOUND, "그 할 일을 찾지 못했어요", op);
        }
        ExecutionStatus st = current.getStatus();
        String problem = switch (op) {
            case CompleteItem c -> st == ExecutionStatus.DONE ? "ALREADY:이미 완료한 항목이에요"
                    : st == ExecutionStatus.HOLD ? "INVALID:보류 중이라 먼저 보류를 풀어야 해요"
                    : st != ExecutionStatus.PLANNED ? "INVALID:지금 상태에서는 완료할 수 없어요" : null;
            case PartialItem p -> st != ExecutionStatus.PLANNED ? "INVALID:할 일 상태에서만 일부 수행을 남길 수 있어요"
                    : p.completionPercent() != null && (p.completionPercent() < 1 || p.completionPercent() > 99)
                    ? "INVALID:일부 수행은 1~99%예요" : null;
            case ReopenItem r -> st == ExecutionStatus.PLANNED ? "ALREADY:이미 할 일 상태예요"
                    : st != ExecutionStatus.DONE ? "INVALID:완료한 항목만 되돌릴 수 있어요" : null;
            case ResumeItem r -> st == ExecutionStatus.PLANNED ? "ALREADY:이미 보류가 아니에요"
                    : st != ExecutionStatus.HOLD ? "INVALID:보류한 항목만 풀 수 있어요" : null;
            case HoldItem h -> st == ExecutionStatus.HOLD ? "ALREADY:이미 보류한 항목이에요"
                    : st != ExecutionStatus.PLANNED ? "INVALID:할 일 상태에서만 보류할 수 있어요" : null;
            case UnscheduleItem u -> current.getPlacementType() == PlacementType.UNSCHEDULED ? "ALREADY:이미 날짜가 없어요"
                    : st != ExecutionStatus.PLANNED && st != ExecutionStatus.HOLD ? "INVALID:지금 상태에서는 뗄 수 없어요"
                    : (u.planningStartDate() == null) != (u.planningEndDate() == null) ? "INVALID:기간은 시작·끝을 함께 정해요" : null;
            case MoveItem m -> st != ExecutionStatus.PLANNED || current.getPlacementType() == PlacementType.UNSCHEDULED
                    ? "INVALID:날짜가 있는 할 일만 옮길 수 있어요"
                    : m.toDate() == null ? "INVALID:옮길 날짜가 필요해요"
                    : (m.startTime() != null && current.getPlacementType() != PlacementType.TIME_FIXED)
                    ? "INVALID:시각이 없는 할 일에는 시각을 붙여 옮길 수 없어요"
                    : (m.startTime() == null) != (m.endTime() == null) ? "INVALID:시작과 끝 시각을 함께 정해야 해요"
                    : m.startTime() != null && !m.endTime().isAfter(m.startTime()) && !m.endTime().equals(LocalTime.MIDNIGHT)
                    ? "INVALID:끝 시각이 시작보다 뒤여야 해요" : null;
            case ReduceItem r -> st != ExecutionStatus.PLANNED ? "INVALID:할 일 상태에서만 줄일 수 있어요"
                    : r.expectedMinutes() != null && current.getExpectedMinutes() != null
                    && r.expectedMinutes() >= current.getExpectedMinutes() ? "INVALID:줄이는 분량은 지금보다 작아야 해요" : null;
            default -> throw new IllegalArgumentException(op.type());
        };
        if (problem != null) {
            String[] parts = problem.split(":", 2);
            return ChangeCheck.fail("ALREADY".equals(parts[0]) ? ChangeCheck.ALREADY_DONE : ChangeCheck.INVALID, parts[1], op);
        }
        return ChangeCheck.ok(withBefore(op, current));
    }

    @Override
    public void lock(Long userId, List<ChangeOp> ops) {
        // 실행 항목은 version 조건 갱신이 경합을 막는다(서비스와 같은 방식). 행 잠금을 따로 잡지 않는다.
    }

    @Override
    public ChangeCheck recheck(Long userId, ChangeOp op) {
        if (!(op instanceof ItemOp itemOp)) {
            return ChangeCheck.ok(op);
        }
        ExecutionItem current = itemMapper.findByIdAndUserId(itemOp.executionItemId(), userId);
        if (current == null) {
            return ChangeCheck.fail(ChangeCheck.STALE, "그 할 일이 지워졌어요", op);
        }
        return Objects.equals(current.getVersion(), itemOp.version()) ? ChangeCheck.ok(op)
                : ChangeCheck.fail(ChangeCheck.STALE, current.getTitle() + "이 검토한 뒤 바뀌었어요", op);
    }

    @Override
    public Map<String, Object> apply(Long userId, ChangeOp op) {
        return switch (op) {
            case CompleteItem c -> {
                itemService.complete(c.executionItemId(), userId, ExecutionItemCompleteRequest.builder().version(c.version())
                        .actualMinutes(c.actualMinutes()).note(c.note()).build());
                yield Map.of("executionItemId", c.executionItemId(), "status", "DONE");
            }
            case PartialItem p -> {
                var res = itemService.recordPartial(p.executionItemId(), userId, ExecutionItemPartialRequest.builder()
                        .version(p.version()).completionPercent(p.completionPercent()).actualMinutes(p.actualMinutes())
                        .note(p.note()).build());
                yield Map.of("executionItemId", p.executionItemId(), "status", String.valueOf(res.getStatus()));
            }
            case ReopenItem r -> {
                itemService.reopen(r.executionItemId(), userId, ExecutionItemReopenRequest.builder().version(r.version())
                        .reason("상담에서 완료 취소").build());
                yield Map.of("executionItemId", r.executionItemId());
            }
            case ResumeItem r -> {
                itemService.resume(r.executionItemId(), userId, ExecutionItemResumeRequest.builder().version(r.version())
                        .reason("상담에서 보류 풀기").build());
                yield Map.of("executionItemId", r.executionItemId());
            }
            case HoldItem h -> {
                itemService.hold(h.executionItemId(), userId, ExecutionItemHoldRequest.builder().version(h.version())
                        .reason("상담에서 보류").build());
                yield Map.of("executionItemId", h.executionItemId());
            }
            case UnscheduleItem u -> {
                itemService.unschedule(u.executionItemId(), userId, ExecutionItemUnscheduleRequest.builder()
                        .version(u.version()).planningStartDate(u.planningStartDate()).planningEndDate(u.planningEndDate())
                        .reason("상담에서 배치 해제").build());
                yield Map.of("executionItemId", u.executionItemId());
            }
            case MoveItem m -> {
                itemService.move(m.executionItemId(), userId, ExecutionItemMoveRequest.builder().version(m.version())
                        .toDate(m.toDate()).startTime(m.startTime()).endTime(m.endTime()).reason("상담에서 옮기기").build());
                yield Map.of("executionItemId", m.executionItemId());
            }
            case ReduceItem r -> {
                itemService.reduce(r.executionItemId(), userId, ExecutionItemReduceRequest.builder().version(r.version())
                        .expectedMinutes(r.expectedMinutes()).reducedTitle(r.reducedTitle()).reason("상담에서 줄이기").build());
                yield Map.of("executionItemId", r.executionItemId());
            }
            case CreateItem c -> Map.of("executionItemId", itemService.create(userId, ExecutionItemCreateRequest.builder()
                    .title(c.title()).courseId(c.courseId()).scheduledDate(c.scheduledDate())
                    .scheduledStartAt(c.scheduledStartAt()).scheduledEndAt(c.scheduledEndAt())
                    .expectedMinutes(c.expectedMinutes()).priority(c.priority()).build()).getExecutionItemId());
            default -> throw new IllegalArgumentException(op.type());
        };
    }

    /** 검토 당시 항목(before)을 지금 행으로 채운 op. */
    private static ChangeOp withBefore(ChangeOp op, ExecutionItem current) {
        return switch (op) {
            case CompleteItem c -> new CompleteItem(current, c.actualMinutes(), c.note());
            case PartialItem p -> new PartialItem(current, p.completionPercent(), p.actualMinutes(), p.note());
            case ReopenItem r -> new ReopenItem(current);
            case ResumeItem r -> new ResumeItem(current);
            case HoldItem h -> new HoldItem(current);
            case UnscheduleItem u -> new UnscheduleItem(current, u.planningStartDate(), u.planningEndDate());
            case MoveItem m -> new MoveItem(current, m.toDate(), m.startTime(), m.endTime());
            case ReduceItem r -> new ReduceItem(current, r.expectedMinutes(), r.reducedTitle());
            default -> op;
        };
    }

    /** 검토 당시 항목의 복사본에 변화를 적용한다(원본 객체는 그대로). */
    static ExecutionItem changed(ExecutionItem before, Consumer<ExecutionItem> change) {
        ExecutionItem copy = ExecutionItem.builder().executionItemId(before.getExecutionItemId()).userId(before.getUserId())
                .topicId(before.getTopicId()).courseId(before.getCourseId()).planVersionId(before.getPlanVersionId())
                .sourceExecutionItemId(before.getSourceExecutionItemId()).title(before.getTitle())
                .description(before.getDescription()).placementType(before.getPlacementType())
                .scheduledDate(before.getScheduledDate()).scheduledStartAt(before.getScheduledStartAt())
                .scheduledEndAt(before.getScheduledEndAt()).deadlineAt(before.getDeadlineAt())
                .planningStartDate(before.getPlanningStartDate()).planningEndDate(before.getPlanningEndDate())
                .expectedMinutes(before.getExpectedMinutes()).status(before.getStatus()).priority(before.getPriority())
                .orderIndex(before.getOrderIndex()).routineId(before.getRoutineId()).originType(before.getOriginType())
                .modifiedAfterCreation(before.getModifiedAfterCreation()).version(before.getVersion())
                .isDeleted(before.getIsDeleted()).build();
        change.accept(copy);
        return copy;
    }
}
