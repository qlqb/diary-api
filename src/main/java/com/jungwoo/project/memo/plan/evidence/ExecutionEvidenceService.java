package com.jungwoo.project.memo.plan.evidence;

import com.jungwoo.project.memo.execution.ExecutionItemEventMapper;
import com.jungwoo.project.memo.execution.ExecutionItemMapper;
import com.jungwoo.project.memo.execution.ExecutionRecordMapper;
import com.jungwoo.project.memo.execution.domain.ExecutionEventType;
import com.jungwoo.project.memo.execution.domain.ExecutionItem;
import com.jungwoo.project.memo.execution.domain.ExecutionItemEvent;
import com.jungwoo.project.memo.execution.domain.ExecutionRecord;
import com.jungwoo.project.memo.execution.domain.ExecutionRecordOutcome;
import com.jungwoo.project.memo.execution.domain.ExecutionStatus;
import com.jungwoo.project.memo.execution.domain.PlacementType;
import com.jungwoo.project.memo.plan.PlanSnapshotCodec;
import com.jungwoo.project.memo.plan.PlanVersionMapper;
import com.jungwoo.project.memo.plan.domain.PlanSnapshotItem;
import com.jungwoo.project.memo.plan.domain.PlanVersion;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 계획 생성과 상담이 <b>같은 방식으로</b> 읽는 실행 기록.
 *
 * <p>회고(PlanReviewService)는 계획 한 판을 스냅샷과 대조하고, 여기서는 기간·프로젝트 기준으로 "이 항목이 실제로
 * 어떻게 됐는가"를 항목 단위로 모은다. 총계 한 줄만으로는 "어디서 막혔는지"를 말할 수 없다 — 질문 대상 과목·항목의
 * 경과(옮김·축소·일부 수행·메모)를 그대로 보여 주고, 원인은 붙이지 않는다.
 *
 * <p>기존 Mapper 경로만 쓴다. 소유권은 모든 조회가 user_id로 건다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionEvidenceService {

    /** 계획 생성·상담이 기본으로 되돌아보는 일수. */
    public static final int DEFAULT_LOOKBACK_DAYS = 14;

    private final ExecutionItemMapper executionItemMapper;
    private final ExecutionRecordMapper executionRecordMapper;
    private final ExecutionItemEventMapper executionItemEventMapper;
    private final PlanVersionMapper planVersionMapper;
    private final PlanSnapshotCodec snapshotCodec;

    /**
     * @param courseIds 비어 있으면 프로젝트를 가리지 않는다
     */
    @Transactional(readOnly = true)
    public ExecutionEvidence collect(Long userId, LocalDate from, LocalDate to, Collection<Long> courseIds) {
        if (from == null || to == null || to.isBefore(from)) {
            return ExecutionEvidence.empty(from, to);
        }
        List<ExecutionItem> raw = executionItemMapper.findInPeriodForReview(userId, from, to);
        Set<Long> scope = courseIds == null ? Set.of() : new HashSet<>(courseIds);
        List<ExecutionItem> items = raw.stream()
                .filter(i -> scope.isEmpty() || (i.getCourseId() != null && scope.contains(i.getCourseId())))
                .toList();
        if (items.isEmpty()) {
            return ExecutionEvidence.empty(from, to);
        }
        List<Long> ids = items.stream().map(ExecutionItem::getExecutionItemId).toList();
        Map<Long, List<ExecutionRecord>> records = new HashMap<>();
        for (ExecutionRecord record : executionRecordMapper.findByExecutionItemIds(userId, ids)) {
            records.computeIfAbsent(record.getExecutionItemId(), k -> new ArrayList<>()).add(record);
        }
        Map<Long, List<ExecutionItemEvent>> events = new HashMap<>();
        for (ExecutionItemEvent event : executionItemEventMapper.findByExecutionItemIds(userId, ids)) {
            events.computeIfAbsent(event.getExecutionItemId(), k -> new ArrayList<>()).add(event);
        }
        Map<Long, LocalDate> plannedDates = plannedDatesOf(userId, items);

        List<ExecutionEvidence.ItemHistory> histories = new ArrayList<>();
        for (ExecutionItem item : items) {
            histories.add(history(item, records.getOrDefault(item.getExecutionItemId(), List.of()),
                    events.getOrDefault(item.getExecutionItemId(), List.of()),
                    plannedDates.get(item.getExecutionItemId())));
        }
        return new ExecutionEvidence(from, to, histories, summarize(histories));
    }

    /** 확정 당시 날짜. 같은 계획 판에서 왔으면 그 스냅샷을 한 번만 읽는다. */
    private Map<Long, LocalDate> plannedDatesOf(Long userId, List<ExecutionItem> items) {
        Map<Long, LocalDate> out = new HashMap<>();
        Set<Long> planIds = new HashSet<>();
        for (ExecutionItem item : items) {
            if (item.getPlanVersionId() != null) {
                planIds.add(item.getPlanVersionId());
            }
        }
        for (Long planId : planIds) {
            try {
                PlanVersion plan = planVersionMapper.findByIdAndUserId(planId, userId);
                if (plan == null) {
                    continue;
                }
                for (PlanSnapshotItem snapshot : snapshotCodec.fromJson(plan.getItemsSnapshot())) {
                    out.put(snapshot.executionItemId(), snapshot.scheduledDate());
                }
            } catch (Exception e) {
                log.debug("실행 기록 근거: 스냅샷을 읽지 못했다. planVersionId={}", planId);
            }
        }
        return out;
    }

    private ExecutionEvidence.ItemHistory history(ExecutionItem item, List<ExecutionRecord> records,
                                                  List<ExecutionItemEvent> events, LocalDate plannedDate) {
        ExecutionRecord latest = records.stream()
                .max(Comparator.comparing(ExecutionRecord::getRecordedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
        int moved = 0;
        int reduced = 0;
        boolean held = false;
        boolean reopened = false;
        for (ExecutionItemEvent event : events) {
            if (event.getEventType() == ExecutionEventType.MOVED) {
                moved++;
            } else if (event.getEventType() == ExecutionEventType.REDUCED) {
                reduced++;
            } else if (event.getEventType() == ExecutionEventType.HOLD) {
                held = true;
            } else if (event.getEventType() == ExecutionEventType.REOPENED) {
                reopened = true;
            }
        }
        String note = null;
        for (ExecutionRecord record : records) {
            /*
             * 막힌 이유(선택 답)는 메모 앞에 붙여 같은 자리로 전달한다 — "시간이 없었다"와 "개념에서 막혔다"는 다음 계획을
             * 다르게 바꾸는 사용자의 말이다. 서버가 원인을 추정해 붙이지 않는다(값이 있을 때만).
             */
            String blocker = blockerLabel(record.getBlockerKind());
            if (blocker != null && (record.getNote() == null || record.getNote().isBlank())) {
                note = "(이유: " + blocker + ")";
            } else if (record.getNote() != null && !record.getNote().isBlank()) {
                note = (blocker == null ? "" : "(이유: " + blocker + ") ") + record.getNote().strip();
            }
        }
        return new ExecutionEvidence.ItemHistory(
                item.getExecutionItemId(), item.getCourseId(), item.getTopicId(), item.getPlanVersionId(),
                item.getTitle(), item.getStatus(), item.getPlacementType(), plannedDate, item.getScheduledDate(),
                item.getDeadlineAt(), item.getExpectedMinutes(), Boolean.TRUE.equals(item.getIsDeleted()),
                moved, reduced, held, reopened, latest, records.size(),
                latest == null ? null : latest.getActualMinutes(), note, item.getSourceExecutionItemId(),
                item.getVersion(), item.getUpdatedAt(), events);
    }

    private Map<Long, ExecutionEvidence.CourseSummary> summarize(List<ExecutionEvidence.ItemHistory> histories) {
        Map<Long, int[]> counts = new LinkedHashMap<>();
        for (ExecutionEvidence.ItemHistory h : histories) {
            int[] c = counts.computeIfAbsent(h.courseId(), k -> new int[10]);
            c[0]++;
            if (h.deleted() || h.status() == ExecutionStatus.CANCELLED) {
                c[6]++;
            } else if (h.status() == ExecutionStatus.HOLD) {
                c[5]++;
            } else if (h.status() == ExecutionStatus.DONE) {
                if (h.latestOutcome() == ExecutionRecordOutcome.PARTIAL) {
                    c[2]++;
                } else {
                    c[1]++;
                }
                if (h.measuredMinutes() != null) {
                    c[8] += h.measuredMinutes();
                } else {
                    c[9]++;
                }
            } else if (h.placementType() == PlacementType.UNSCHEDULED) {
                c[4]++;
            } else {
                c[3]++;
            }
            if (h.movedCount() > 0) {
                c[7]++;
            }
        }
        Map<Long, ExecutionEvidence.CourseSummary> out = new LinkedHashMap<>();
        counts.forEach((courseId, c) -> out.put(courseId, new ExecutionEvidence.CourseSummary(courseId,
                c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8], c[9])));
        return out;
    }

    // ===== 문장 =====

    /** 프롬프트·화면이 같이 쓰는 한 줄. 관찰 사실만 적고 원인을 붙이지 않는다. */
    public static String describe(ExecutionEvidence.ItemHistory h) {
        StringBuilder sb = new StringBuilder();
        sb.append('#').append(h.executionItemId()).append(' ').append(h.title());
        if (h.plannedDate() != null || h.currentDate() != null) {
            sb.append(" · ");
            if (h.plannedDate() != null && h.currentDate() != null && !h.plannedDate().equals(h.currentDate())) {
                sb.append("계획 ").append(shortDate(h.plannedDate())).append(" → 지금 ").append(shortDate(h.currentDate()));
            } else if (h.currentDate() != null) {
                sb.append(shortDate(h.currentDate()));
            } else {
                sb.append("계획 ").append(shortDate(h.plannedDate())).append(" → 지금 날짜 없음");
            }
        } else if (h.placementType() == PlacementType.UNSCHEDULED) {
            sb.append(" · 날짜 미정");
        }
        if (h.expectedMinutes() != null) {
            sb.append(" · 예정 ").append(h.expectedMinutes()).append("분");
        }
        sb.append(" · ").append(statusText(h));
        if (h.movedCount() > 0) {
            sb.append(" · 옮김 ").append(h.movedCount()).append("회");
        }
        if (h.reducedCount() > 0) {
            sb.append(" · 줄임 ").append(h.reducedCount()).append("회");
        }
        if (h.held()) {
            sb.append(" · 보류한 적 있음");
        }
        if (h.reopened()) {
            sb.append(" · 완료를 되돌린 적 있음");
        }
        if (h.leftoverOfId() != null) {
            sb.append(" · #").append(h.leftoverOfId()).append("의 남은 분량");
        }
        if (h.userNote() != null) {
            sb.append(" · 사용자 메모: \"").append(cut(h.userNote(), 120)).append('"');
        }
        return sb.toString();
    }

    static String blockerLabel(String kind) {
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case "TIME" -> "시간이 없었다";
            case "CONCEPT" -> "개념에서 막혔다";
            case "ENERGY" -> "컨디션이 좋지 않았다";
            case "OTHER" -> "다른 이유";
            default -> null;
        };
    }

    static String statusText(ExecutionEvidence.ItemHistory h) {
        if (h.deleted() || h.status() == ExecutionStatus.CANCELLED) {
            return "계획에서 뺌";
        }
        if (h.status() == ExecutionStatus.HOLD) {
            return "보류 중";
        }
        if (h.status() == ExecutionStatus.DONE) {
            String base = h.latestOutcome() == ExecutionRecordOutcome.PARTIAL ? "일부 수행" : "완료";
            if (h.measuredMinutes() != null) {
                return base + "(실제 " + h.measuredMinutes() + "분, 사용자가 적은 시간)";
            }
            return base + "(실제 시간 미기록)";
        }
        if (h.status() == ExecutionStatus.PARTIAL) {
            return h.measuredMinutes() != null ? "일부 진행(실제 " + h.measuredMinutes() + "분, 사용자가 적은 시간)"
                    : "일부 진행(실제 시간 미기록)";
        }
        if (h.recordCount() > 0 && h.latestOutcome() == ExecutionRecordOutcome.NOT_DONE) {
            return "시작했지만 진행 없음으로 기록";
        }
        return h.placementType() == PlacementType.UNSCHEDULED ? "아직 시작 안 함(날짜 미정)" : "아직 시작 안 함";
    }

    public static String summaryLine(ExecutionEvidence.CourseSummary s) {
        StringBuilder sb = new StringBuilder();
        sb.append("항목 ").append(s.total()).append("개 — 완료 ").append(s.done());
        if (s.partial() > 0) {
            sb.append(", 일부 수행 ").append(s.partial());
        }
        sb.append(", 남음 ").append(s.remaining());
        if (s.unplaced() > 0) {
            sb.append(", 날짜 미정 ").append(s.unplaced());
        }
        if (s.held() > 0) {
            sb.append(", 보류 ").append(s.held());
        }
        if (s.excluded() > 0) {
            sb.append(", 뺌 ").append(s.excluded());
        }
        if (s.moved() > 0) {
            sb.append(" · 옮긴 항목 ").append(s.moved()).append("개");
        }
        sb.append(" · 사용자가 적은 실제 시간 ").append(s.measuredMinutes()).append("분");
        if (s.unmeasuredDone() > 0) {
            sb.append(" · 시간 미기록 ").append(s.unmeasuredDone()).append("건");
        }
        return sb.toString();
    }

    private static String shortDate(LocalDate d) {
        return d.getMonthValue() + "/" + d.getDayOfMonth() + " "
                + d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN);
    }

    private static String cut(String s, int max) {
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    /** 두 기록이 같은 항목을 가리키는가(리스트 합칠 때). */
    static boolean same(ExecutionEvidence.ItemHistory a, ExecutionEvidence.ItemHistory b) {
        return Objects.equals(a.executionItemId(), b.executionItemId());
    }
}
