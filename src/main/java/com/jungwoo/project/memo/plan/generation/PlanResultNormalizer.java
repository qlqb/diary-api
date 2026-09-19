package com.jungwoo.project.memo.plan.generation;

import com.jungwoo.project.memo.ai.dto.ProposalAdjustment;
import com.jungwoo.project.memo.ai.dto.ProposalItem;
import com.jungwoo.project.memo.ai.domain.ProposalOperation;
import com.jungwoo.project.memo.course.domain.Course;
import com.jungwoo.project.memo.execution.domain.ExecutionItem;
import com.jungwoo.project.memo.execution.domain.ExecutionStatus;
import com.jungwoo.project.memo.execution.domain.PlacementType;
import com.jungwoo.project.memo.plan.domain.ActionType;
import com.jungwoo.project.memo.plan.domain.DoneCriteriaSource;
import com.jungwoo.project.memo.plan.domain.PlanStrategy;
import com.jungwoo.project.memo.plan.domain.StrategySource;
import com.jungwoo.project.memo.plan.domain.Treatment;
import com.jungwoo.project.memo.plan.dto.PlanDraftAiResult;
import com.jungwoo.project.memo.plan.provenance.PlanItemEvidence;
import com.jungwoo.project.memo.plan.provenance.PlanProvenance;
import com.jungwoo.project.memo.plan.provenance.ProvenanceSourceType;
import com.jungwoo.project.memo.plan.provenance.ProvidedSource;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 최종 계획 호출의 출력을 서버가 아는 사실과 대조해 제안 항목·전략·조정 항목으로 옮긴다.
 *
 * <p>서버가 검사하는 것: 인용 번호가 이번 회차에 실제로 준 것인가, courseId가 대상 프로젝트인가, 마감 참조가 수업·과제
 * 사실을 가리키는가, 기존 항목 참조가 이 기간의 계획 항목인가, 시각이 지금 이후인가. 어떤 과목을 먼저 할지·개념과 문제 중
 * 무엇을 고를지·분량은 모델의 제안 영역이라 손대지 않는다.
 */
@Slf4j
public final class PlanResultNormalizer {

    public static final String DEADLINE_CLASS = "CLASS";
    public static final String DEADLINE_ASSIGNMENT = "ASSIGNMENT";
    public static final String DEADLINE_AI_PROPOSED = "AI_PROPOSED";

    static final int MAX_LIST = 12;
    static final int MAX_TEXT = 300;

    private static final DateTimeFormatter LOCAL_MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private PlanResultNormalizer() {
    }

    /** 마감 근거 한 줄. 인용 번호 → 사실. */
    public record DeadlineFact(String source, LocalDateTime at, LocalDate date) {
    }

    /** 정규화 결과. items와 evidence는 같은 순서·같은 길이다. */
    public record Normalized(List<ProposalItem> items, List<PlanItemEvidence> evidence, PlanStrategy strategy,
                             List<ProposalAdjustment> adjustments, List<PlanStrategy.ExistingDecision> existingDecisions,
                             int unknownRefs) {
    }

    /**
     * @param deadlineFacts 인용 번호 → 수업·과제 마감 사실(프롬프트를 만들면서 모은 것)
     * @param existing      [이 기간에 이미 있는 일정]에 실린 항목(인용 번호 → 항목)
     * @param serverUnread  서버가 아는 "읽지 못한 범위" 문장(상한·예산 때문에 싣지 못한 것)
     */
    public static Normalized normalize(PlanDraftAiResult ai, LocalDate start, LocalDate end, LocalDateTime now,
                                       List<Course> targetCourses, PlanProvenance provenance,
                                       Map<String, DeadlineFact> deadlineFacts, Map<String, ExecutionItem> existing,
                                       List<String> serverUnread, List<String> changesFromPrevious) {
        Set<Long> allowedCourseIds = targetCourses.stream().map(Course::getCourseId).collect(Collectors.toSet());
        Long soleCourseId = targetCourses.size() == 1 ? targetCourses.get(0).getCourseId() : null;
        Map<String, ProvidedSource> byRef = new HashMap<>();
        for (ProvidedSource source : provenance.providedSources()) {
            byRef.put(source.refId(), source);
        }

        List<ProposalItem> items = new ArrayList<>();
        List<PlanItemEvidence> evidence = new ArrayList<>();
        List<PlanStrategy.Deferred> operationalDeferred = new ArrayList<>();
        int totalUnknown = 0;
        for (PlanDraftAiResult.PlanDraftAiItem raw : ai.items() == null ? List.<PlanDraftAiResult.PlanDraftAiItem>of() : ai.items()) {
            if (raw == null || raw.title() == null || raw.title().isBlank()) {
                continue;
            }
            List<String> refs = new ArrayList<>();
            int unknown = 0;
            for (String ref : raw.refIds() == null ? List.<String>of() : raw.refIds()) {
                String trimmed = ref == null ? null : ref.trim();
                if (trimmed == null || trimmed.isEmpty()) {
                    continue;
                }
                if (byRef.containsKey(trimmed)) {
                    if (!refs.contains(trimmed)) {
                        refs.add(trimmed);
                    }
                } else {
                    unknown++;
                }
            }
            totalUnknown += unknown;

            String origin = normalizeOrigin(raw.origin(), refs, byRef);
            if (citesOnlyOperationalInfo(refs, byRef) && !ORIGIN_USER_REQUEST.equals(origin)) {
                /*
                 * 운영 안내(평가 비율·연락처·수업 규칙)만 근거로 든 항목은 요청 없이 학습 항목이 되지 않는다. 판단 기준은 제목의
                 * 단어가 아니라 분석이 붙인 구간 역할이다 — 설명과 운영 안내가 섞인 구간(역할에 CONCEPT 등이 함께 있다)은
                 * 걸리지 않는다. 버리지 않고 "미룬 범위"에 이유와 함께 남긴다.
                 */
                log.info("계획 초안: 운영 안내만 근거로 든 항목을 학습 항목으로 만들지 않는다. title={}", raw.title());
                operationalDeferred.add(new PlanStrategy.Deferred(cut(raw.title(), MAX_TEXT),
                        "운영 안내(평가·연락처·수업 규칙 등)는 계획을 판단하는 배경으로만 썼어요. 직접 정리하고 싶으면 상담에서 "
                                + "요청하면 준비 작업으로 넣어요.", null, firstSectionId(refs, byRef)));
                continue;
            }

            LocalDate scheduled = parseDateInRange(raw.scheduledDate(), start, end);
            /*
             * description에는 "행동 · 완료: 기준"을 그대로 둔다 — 확정 뒤 execution_items·스냅샷에 남는 것은 description
             * 뿐이라 완료 기준이 거기서 사라지면 안 된다. doneCriteria는 화면이 따로 보여 주는 복사본이다.
             */
            String[] split = splitDoneCriteria(raw.description(), raw.doneCriteria());
            String doneCriteria = split[1];
            String description = mergedDescription(raw.description(), doneCriteria);
            if (description == null) {
                description = blankToNull(raw.reason());
            }
            Long topicId = topicIdOf(refs, byRef);
            Deadline deadline = resolveDeadline(raw, deadlineFacts, byRef, now, end, unknown);
            if (deadline.unknownRef) {
                totalUnknown++;
            }

            items.add(new ProposalItem(
                    raw.title(), description, raw.expectedMinutes(), normalizePriority(raw.priority()),
                    scheduled != null ? PlacementType.DATE_ONLY : PlacementType.UNSCHEDULED,
                    null, null,
                    scheduled != null ? scheduled : start,
                    deadline.date != null ? deadline.date : (scheduled != null ? scheduled : end),
                    null, null,
                    resolveItemCourseId(raw.courseId(), allowedCourseIds, soleCourseId),
                    deadline.at, topicId, parseAction(raw.actionType()), doneCriteria,
                    doneCriteria == null ? null : DoneCriteriaSource.MODEL, null,
                    deadline.at == null && deadline.date == null ? null : deadline.source));

            List<String> estimates = aiEstimates(raw, scheduled, deadline);
            evidence.add(PlanItemEvidence.of(provenance.generationId(), refs, blankToNull(raw.reason()), estimates,
                    List.of(), unknown + (deadline.unknownRef ? 1 : 0)).withOrigin(origin));
        }
        if (totalUnknown > 0) {
            log.warn("계획 초안: 모델이 이번 회차에 없는 인용 {}건을 냈다. generationId={}", totalUnknown,
                    provenance.generationId());
        }

        List<PlanStrategy.ExistingDecision> existingDecisions = new ArrayList<>();
        List<ProposalAdjustment> adjustments = existingDecisions(ai.existingItems(), existing, byRef, start, end,
                existingDecisions);
        PlanStrategy strategy = strategy(ai, allowedCourseIds, byRef, serverUnread, changesFromPrevious, existingDecisions);
        if (!operationalDeferred.isEmpty()) {
            List<PlanStrategy.Deferred> merged = new ArrayList<>(strategy.deferred() == null ? List.of() : strategy.deferred());
            merged.addAll(operationalDeferred);
            strategy = strategy.withDeferred(merged);
        }
        return new Normalized(items, evidence, strategy, adjustments, existingDecisions, totalUnknown);
    }

    // ===== 출처 유형·운영 안내 =====

    public static final String ORIGIN_SOURCE_TASK = "SOURCE_TASK";
    public static final String ORIGIN_AI_PRACTICE = "AI_PRACTICE";
    public static final String ORIGIN_USER_REQUEST = "USER_REQUEST";

    /**
     * 모델이 낸 출처 유형을 검증한다. SOURCE_TASK("자료 원문에 있는 과제·실습")는 이번 회차에 원문이 전달된 구간을
     * 인용했을 때만 인정하고, 아니면 AI_PRACTICE로 낮춘다 — 모델이 만든 연습이 원문 문제처럼 보이면 안 된다.
     * 값이 없거나 모르는 값이면 null(표시하지 않음)이다.
     */
    static String normalizeOrigin(String raw, List<String> refs, Map<String, ProvidedSource> byRef) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String origin = raw.trim().toUpperCase(Locale.ROOT);
        if (ORIGIN_SOURCE_TASK.equals(origin)) {
            boolean citesText = refs.stream().map(byRef::get)
                    .anyMatch(s -> s != null && s.sourceType() == ProvenanceSourceType.MATERIAL_SECTION);
            return citesText ? ORIGIN_SOURCE_TASK : ORIGIN_AI_PRACTICE;
        }
        if (ORIGIN_AI_PRACTICE.equals(origin) || ORIGIN_USER_REQUEST.equals(origin)) {
            return origin;
        }
        return null;
    }

    /** 인용한 근거가 구간뿐이고, 그 구간의 역할이 전부 운영 안내(ADMIN, 또는 ADMIN과 SCHEDULE)뿐인가. */
    static boolean citesOnlyOperationalInfo(List<String> refs, Map<String, ProvidedSource> byRef) {
        boolean anySection = false;
        for (String ref : refs) {
            ProvidedSource s = byRef.get(ref);
            if (s == null) {
                continue;
            }
            if (s.sourceType() != ProvenanceSourceType.MATERIAL_SECTION) {
                if (s.sourceType() == ProvenanceSourceType.TOPIC) {
                    return false; // 학습 항목을 함께 근거로 들었다.
                }
                continue;
            }
            anySection = true;
            Object roles = s.providedValue() == null ? null : s.providedValue().get("roles");
            if (!(roles instanceof java.util.Collection<?> list) || list.isEmpty()) {
                return false; // 역할을 모르면 막지 않는다.
            }
            boolean hasAdmin = false;
            for (Object role : list) {
                String r = String.valueOf(role).toUpperCase(Locale.ROOT);
                if ("ADMIN".equals(r)) {
                    hasAdmin = true;
                } else if (!"SCHEDULE".equals(r)) {
                    return false; // 설명·예제·연습이 섞인 구간이다.
                }
            }
            if (!hasAdmin) {
                return false;
            }
        }
        return anySection;
    }

    private static Long firstSectionId(List<String> refs, Map<String, ProvidedSource> byRef) {
        for (String ref : refs) {
            ProvidedSource s = byRef.get(ref);
            if (s != null && s.sourceType() == ProvenanceSourceType.MATERIAL_SECTION) {
                return s.sourceId();
            }
        }
        return null;
    }

    // ===== 마감 =====

    private record Deadline(LocalDateTime at, LocalDate date, String source, boolean unknownRef) {
        static final Deadline NONE = new Deadline(null, null, null, false);
    }

    private static Deadline resolveDeadline(PlanDraftAiResult.PlanDraftAiItem raw, Map<String, DeadlineFact> facts,
                                            Map<String, ProvidedSource> byRef, LocalDateTime now, LocalDate end,
                                            int unknownSoFar) {
        String ref = raw.deadlineRefId() == null ? null : raw.deadlineRefId().trim();
        if (ref != null && !ref.isEmpty()) {
            DeadlineFact fact = facts.get(ref);
            if (fact == null) {
                log.info("계획 초안: 마감 참조 {}가 수업·과제 사실이 아니라 버린다", ref);
                return new Deadline(null, null, null, !byRef.containsKey(ref));
            }
            if (fact.at() != null) {
                if (!fact.at().isAfter(now)) {
                    log.info("계획 초안: 마감 참조 {}의 시각 {}이 이미 지나 버린다", ref, fact.at());
                    return Deadline.NONE;
                }
                return new Deadline(fact.at(), null, fact.source(), false);
            }
            if (fact.date() != null && !fact.date().isBefore(now.toLocalDate())) {
                return new Deadline(null, fact.date(), fact.source(), false);
            }
            return Deadline.NONE;
        }
        String target = raw.targetCompleteAt() == null ? null : raw.targetCompleteAt().trim();
        if (target != null && !target.isEmpty()) {
            try {
                LocalDateTime at = LocalDateTime.parse(target.length() > 16 ? target.substring(0, 16) : target, LOCAL_MINUTE);
                if (at.isAfter(now) && !at.toLocalDate().isAfter(end.plusDays(14))) {
                    return new Deadline(at, null, DEADLINE_AI_PROPOSED, false);
                }
                log.info("계획 초안: 제안 완료 목표 {}가 지금 이전이거나 기간 밖이라 버린다", at);
            } catch (Exception e) {
                log.info("계획 초안: 제안 완료 목표를 읽지 못했다: {}", target);
            }
        }
        return Deadline.NONE;
    }

    /** 인용한 학습 항목, 없으면 인용한 구간이 실린 학습 항목. 제목으로 짝짓지 않는다. */
    static Long topicIdOf(List<String> refs, Map<String, ProvidedSource> byRef) {
        for (String ref : refs) {
            ProvidedSource s = byRef.get(ref);
            if (s != null && s.sourceType() == ProvenanceSourceType.TOPIC && s.sourceId() != null) {
                return s.sourceId();
            }
        }
        for (String ref : refs) {
            ProvidedSource s = byRef.get(ref);
            if (s != null && s.sourceType() == ProvenanceSourceType.MATERIAL_SECTION && s.parentSourceId() != null) {
                return s.parentSourceId();
            }
        }
        return null;
    }

    // ===== 기존 항목 =====

    private static List<ProposalAdjustment> existingDecisions(List<PlanDraftAiResult.ExistingItemOut> outs,
                                                              Map<String, ExecutionItem> existing,
                                                              Map<String, ProvidedSource> byRef, LocalDate start,
                                                              LocalDate end,
                                                              List<PlanStrategy.ExistingDecision> decisions) {
        List<ProposalAdjustment> adjustments = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (PlanDraftAiResult.ExistingItemOut out : outs == null ? List.<PlanDraftAiResult.ExistingItemOut>of() : outs) {
            if (out == null || out.refId() == null) {
                continue;
            }
            ExecutionItem item = existing.get(out.refId().trim());
            if (item == null || !seen.add(item.getExecutionItemId())) {
                continue;
            }
            String action = out.action() == null ? "KEEP" : out.action().trim().toUpperCase(Locale.ROOT);
            if (item.getStatus() != ExecutionStatus.PLANNED && !"KEEP".equals(action)) {
                log.info("계획 초안: 기존 항목 #{}는 {} 상태라 조정하지 않는다", item.getExecutionItemId(), item.getStatus());
                continue;
            }
            String reason = cut(out.reason(), MAX_TEXT);
            switch (action) {
                case "REDUCE" -> {
                    if (out.expectedMinutes() == null || item.getExpectedMinutes() == null
                            || out.expectedMinutes() <= 0 || out.expectedMinutes() >= item.getExpectedMinutes()) {
                        continue;
                    }
                    adjustments.add(new ProposalAdjustment(item.getExecutionItemId(), ProposalOperation.REDUCE,
                            out.expectedMinutes(), null, null, null, null, reason));
                    decisions.add(new PlanStrategy.ExistingDecision(item.getExecutionItemId(), item.getTitle(), "REDUCE",
                            reason, out.expectedMinutes(), null));
                }
                case "MOVE" -> {
                    LocalDate to = parseDateInRange(out.toDate(), start, end);
                    if (to == null || item.getPlacementType() == PlacementType.UNSCHEDULED
                            || to.equals(item.getScheduledDate()) || to.isBefore(LocalDate.from(start))) {
                        continue;
                    }
                    adjustments.add(new ProposalAdjustment(item.getExecutionItemId(), ProposalOperation.MOVE, null,
                            null, to, null, null, reason));
                    decisions.add(new PlanStrategy.ExistingDecision(item.getExecutionItemId(), item.getTitle(), "MOVE",
                            reason, null, to));
                }
                case "DROP" -> {
                    adjustments.add(new ProposalAdjustment(item.getExecutionItemId(), ProposalOperation.DROP, null,
                            null, null, null, null, reason));
                    decisions.add(new PlanStrategy.ExistingDecision(item.getExecutionItemId(), item.getTitle(), "DROP",
                            reason, null, null));
                }
                default -> decisions.add(new PlanStrategy.ExistingDecision(item.getExecutionItemId(), item.getTitle(),
                        "KEEP", reason, null, null));
            }
        }
        return adjustments;
    }

    // ===== 전략 =====

    private static PlanStrategy strategy(PlanDraftAiResult ai, Set<Long> allowedCourseIds,
                                         Map<String, ProvidedSource> byRef, List<String> serverUnread,
                                         List<String> changesFromPrevious,
                                         List<PlanStrategy.ExistingDecision> existingDecisions) {
        PlanDraftAiResult.StrategyOut out = ai.strategy();
        String goal = out != null && blankToNull(out.goal()) != null ? cut(out.goal(), MAX_TEXT) : blankToNull(ai.goalSummary());
        List<PlanStrategy.CourseStrategy> courses = new ArrayList<>();
        if (out != null && out.courses() != null) {
            int rank = 1;
            for (PlanDraftAiResult.CourseOut c : out.courses()) {
                if (c == null || c.courseId() == null || !allowedCourseIds.contains(c.courseId())) {
                    continue;
                }
                courses.add(new PlanStrategy.CourseStrategy(c.courseId(), c.rank() != null ? c.rank() : rank,
                        cut(c.focus(), MAX_TEXT), cut(c.reason(), MAX_TEXT)));
                rank++;
            }
        }
        List<PlanStrategy.Deferred> deferred = new ArrayList<>();
        List<PlanStrategy.TopicTreatment> topics = new ArrayList<>();
        Set<Long> skipped = new HashSet<>();
        if (out != null && out.deferred() != null) {
            for (PlanDraftAiResult.DeferredOut d : out.deferred()) {
                if (d == null || blankToNull(d.title()) == null) {
                    continue;
                }
                Long topicId = null;
                Long sectionId = null;
                String topicTitle = null;
                for (String ref : d.refIds() == null ? List.<String>of() : d.refIds()) {
                    ProvidedSource s = byRef.get(ref == null ? "" : ref.trim());
                    if (s == null) {
                        continue;
                    }
                    if (s.sourceType() == ProvenanceSourceType.TOPIC && topicId == null) {
                        topicId = s.sourceId();
                        topicTitle = String.valueOf(s.providedValue().getOrDefault("title", d.title()));
                    } else if (s.sourceType() == ProvenanceSourceType.MATERIAL_SECTION && sectionId == null) {
                        sectionId = s.sourceId();
                        if (topicId == null) {
                            topicId = s.parentSourceId();
                        }
                    }
                }
                deferred.add(new PlanStrategy.Deferred(cut(d.title(), MAX_TEXT), cut(d.reason(), MAX_TEXT), topicId, sectionId));
                if (topicId != null && skipped.add(topicId)) {
                    topics.add(new PlanStrategy.TopicTreatment(topicId, Treatment.SKIP, 0, cut(d.reason(), MAX_TEXT),
                            List.of(), topicTitle != null ? topicTitle : d.title(), null));
                }
                if (deferred.size() >= MAX_LIST) {
                    break;
                }
            }
        }
        List<String> unread = new ArrayList<>(strings(out == null ? null : out.unread()));
        for (String note : serverUnread == null ? List.<String>of() : serverUnread) {
            if (note != null && !unread.contains(note)) {
                unread.add(note);
            }
        }
        List<PlanStrategy.Change> changes = new ArrayList<>();
        if (out != null && out.changes() != null) {
            for (PlanDraftAiResult.ChangeOut c : out.changes()) {
                if (c != null && blankToNull(c.what()) != null && changes.size() < MAX_LIST) {
                    changes.add(new PlanStrategy.Change(cut(c.what(), MAX_TEXT), cut(c.why(), MAX_TEXT)));
                }
            }
        }
        for (String change : changesFromPrevious == null ? List.<String>of() : changesFromPrevious) {
            boolean dup = changes.stream().anyMatch(c -> c.what().equals(change));
            if (!dup && changes.size() < MAX_LIST) {
                changes.add(new PlanStrategy.Change(change, "서버가 이전 초안의 근거 스냅샷과 비교해 확인한 변경"));
            }
        }
        List<String> questions = strings(out == null ? null : out.questions());
        if (questions.size() > 1) {
            questions = questions.subList(0, 1);
        }
        return new PlanStrategy(goal, out == null ? null : cut(out.summary(), MAX_TEXT), StrategySource.NEW, null,
                List.of(), courses, topics, List.of(),
                out == null ? null : cut(out.reach(), MAX_TEXT), strings(out == null ? null : out.keptDecisions()),
                deferred, strings(out == null ? null : out.assumptions()), questions,
                unread.size() > MAX_LIST ? new ArrayList<>(unread.subList(0, MAX_LIST)) : unread, changes,
                new ArrayList<>(existingDecisions));
    }

    // ===== 작은 도우미 =====

    /** 저장용 description. "완료:"가 없는데 doneCriteria가 있으면 붙인다(V1의 toDescription과 같은 모양). */
    static String mergedDescription(String description, String doneCriteria) {
        String desc = blankToNull(description);
        if (desc == null) {
            return doneCriteria == null ? null : "완료: " + doneCriteria;
        }
        if (doneCriteria != null && !desc.contains("완료:")) {
            return desc.trim() + " · 완료: " + doneCriteria;
        }
        return desc;
    }

    /** description의 "완료:" 뒤를 완료 기준으로 나눈다. doneCriteria가 따로 오면 그것이 우선이다. */
    static String[] splitDoneCriteria(String description, String doneCriteria) {
        String done = blankToNull(doneCriteria);
        String desc = blankToNull(description);
        if (done == null && desc != null) {
            int idx = desc.indexOf("완료:");
            if (idx > 0) {
                done = blankToNull(desc.substring(idx + 3));
                String head = desc.substring(0, idx).replaceAll("[\\s·]+$", "");
                desc = blankToNull(head);
            }
        }
        return new String[]{desc, done == null ? null : cut(done, MAX_TEXT)};
    }

    static ActionType parseAction(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return ActionType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static List<String> aiEstimates(PlanDraftAiResult.PlanDraftAiItem raw, LocalDate scheduled, Deadline deadline) {
        List<String> estimates = new ArrayList<>();
        if (raw.expectedMinutes() != null) {
            estimates.add("예상 소요 시간 " + raw.expectedMinutes() + "분");
        }
        if (blankToNull(raw.priority()) != null) {
            estimates.add(switch (normalizePriority(raw.priority())) {
                case "MUST" -> "시간이 모자라도 꼭 해야 한다고 봄";
                case "OPTIONAL" -> "여유가 되면 하는 쪽으로 봄";
                default -> "보통 순위로 봄";
            });
        }
        if (scheduled != null) {
            estimates.add("이 날짜에 해야 한다고 봄: " + scheduled);
        }
        if (DEADLINE_AI_PROPOSED.equals(deadline.source) && deadline.at != null) {
            estimates.add("완료 목표 시각을 제안함: " + deadline.at + " (사용자가 고칠 수 있음)");
        } else if (deadline.source != null) {
            estimates.add((DEADLINE_CLASS.equals(deadline.source) ? "다음 수업 전에 끝내야 한다고 봄" : "과제 마감 전에 끝내야 한다고 봄"));
        }
        if (blankToNull(raw.reflects()) != null) {
            estimates.add("이전 실행 결과에서 반영: " + cut(raw.reflects(), MAX_TEXT));
        }
        return estimates;
    }

    static List<String> strings(List<String> raw) {
        List<String> out = new ArrayList<>();
        for (String s : raw == null ? List.<String>of() : raw) {
            String v = blankToNull(s);
            if (v != null && out.size() < MAX_LIST) {
                out.add(cut(v, MAX_TEXT));
            }
        }
        return out;
    }

    static Long resolveItemCourseId(Long raw, Set<Long> allowed, Long soleCourseId) {
        if (raw != null && allowed.contains(raw)) {
            return raw;
        }
        return soleCourseId;
    }

    static LocalDate parseDateInRange(String raw, LocalDate start, LocalDate end) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            LocalDate parsed = LocalDate.parse(raw.trim());
            return parsed.isBefore(start) || parsed.isAfter(end) ? null : parsed;
        } catch (Exception e) {
            return null;
        }
    }

    static String normalizePriority(String raw) {
        if (raw == null) {
            return "SHOULD";
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "MUST" -> "MUST";
            case "OPTIONAL" -> "OPTIONAL";
            default -> "SHOULD";
        };
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    static String cut(String s, int max) {
        if (s == null) {
            return null;
        }
        String flat = s.replaceAll("\\s+", " ").trim();
        if (flat.isEmpty()) {
            return null;
        }
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    /** 인용 번호 → 원본 줄. 정규화 밖(요약 등)에서도 쓴다. */
    public static Map<String, ProvidedSource> byRef(PlanProvenance provenance) {
        Map<String, ProvidedSource> map = new LinkedHashMap<>();
        for (ProvidedSource s : provenance.providedSources()) {
            map.put(s.refId(), s);
        }
        return map;
    }
}
