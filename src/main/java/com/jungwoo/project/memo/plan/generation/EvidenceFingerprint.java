package com.jungwoo.project.memo.plan.generation;

import com.jungwoo.project.memo.plan.PlanMaterialContextService.AssignmentLine;
import com.jungwoo.project.memo.plan.PlanMaterialContextService.CourseCatalog;
import com.jungwoo.project.memo.plan.PlanMaterialContextService.SectionLine;
import com.jungwoo.project.memo.plan.PlanMaterialContextService.TopicLine;
import com.jungwoo.project.memo.plan.selection.PlanRequestContext;
import com.jungwoo.project.memo.scheduling.domain.AvailabilityWindow;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * "그때 모델에 준 근거"의 지문. 다시 만들 때 같은지 다른지를 사후 DB 조회가 아니라 이 값으로 가른다.
 *
 * <p>네 갈래(가용시간·자료/구간·과제·학습 항목 진행)를 따로 해시해 무엇이 달라졌는지 말할 수 있게 한다. 전체 지문은
 * 넷과 요청 조건(기간·과목·지시·제외·지정 자료)을 합친 값이다.
 */
public record EvidenceFingerprint(String fingerprint, String availabilityHash, String materialsHash,
                                  String assignmentsHash, String progressHash) {

    /**
     * @param now 지문을 찍는 시각. 오늘의 가용 구간은 "지금부터"로 시작하므로 시작 시각을 지문에 넣으면 1분만 지나도 다른
     *            근거가 된다(실호출에서 같은 조건의 다시 만들기가 매번 선택을 다시 했다). 오늘 구간은 끝 시각만 본다 —
     *            새 일정이 오늘 구간을 자르거나 줄이면 끝 시각이나 구간 수가 달라져 여전히 잡힌다.
     */
    public static EvidenceFingerprint of(List<CourseCatalog> catalogs, List<AvailabilityWindow> windows, LocalDateTime now,
                                         LocalDate start, LocalDate end, Collection<Long> courseIds, String instruction,
                                         Collection<Long> excluded, Collection<Long> requestedMaterials) {
        TreeSet<String> availability = new TreeSet<>();
        LocalDate today = now == null ? null : now.toLocalDate();
        for (AvailabilityWindow w : windows == null ? List.<AvailabilityWindow>of() : windows) {
            boolean startsToday = today != null && w.startAt() != null && w.startAt().toLocalDate().equals(today);
            availability.add((startsToday ? "today" : String.valueOf(w.startAt())) + "~" + w.endAt());
        }
        TreeSet<String> materials = new TreeSet<>();
        TreeSet<String> assignments = new TreeSet<>();
        TreeSet<String> progress = new TreeSet<>();
        for (CourseCatalog c : catalogs == null ? List.<CourseCatalog>of() : catalogs) {
            for (SectionLine s : c.sections()) {
                materials.add("s" + s.section().getSectionId() + ":" + s.section().getFileHash());
            }
            for (AssignmentLine a : concat(c.open(), c.completed(), c.unconfirmed())) {
                assignments.add("a" + a.assignment().getAssignmentId() + ":" + a.assignment().getVersion() + ":"
                        + a.assignment().isCompleted() + ":" + a.assignment().getConfirmStatus() + ":"
                        + a.assignment().dueDay());
            }
            for (TopicLine t : c.topics()) {
                progress.add("t" + t.topicId() + ":" + t.progress() + ":" + t.mark());
            }
            c.excluded().forEach(e -> progress.add("x" + e.topicId() + ":" + e.reason()));
        }
        String availabilityHash = hash(availability);
        String materialsHash = hash(materials);
        String assignmentsHash = hash(assignments);
        String progressHash = hash(progress);
        TreeSet<String> all = new TreeSet<>();
        all.add("period:" + start + "~" + end);
        all.add("courses:" + sorted(courseIds));
        all.add("instruction:" + (instruction == null ? "" : instruction.strip()));
        all.add("excluded:" + sorted(excluded));
        all.add("requested:" + sorted(requestedMaterials));
        all.add("av:" + availabilityHash);
        all.add("mat:" + materialsHash);
        all.add("asg:" + assignmentsHash);
        all.add("prog:" + progressHash);
        return new EvidenceFingerprint(hash(all), availabilityHash, materialsHash, assignmentsHash, progressHash);
    }

    /** 이전 스냅샷과 비교해 사람이 읽는 변경 목록. 같으면 빈 목록. */
    public List<String> changesFrom(PlanRequestContext.EvidenceSnapshot previous, PlanRequestContext previousRequest,
                                    LocalDate start, LocalDate end, Collection<Long> courseIds, String instruction,
                                    Collection<Long> excluded) {
        List<String> out = new ArrayList<>();
        if (previous == null) {
            return out;
        }
        if (previousRequest != null) {
            if (!Objects.equals(previousRequest.startDate(), start) || !Objects.equals(previousRequest.endDate(), end)) {
                out.add("기간이 " + previousRequest.startDate() + "~" + previousRequest.endDate() + "에서 " + start + "~" + end
                        + "으로 바뀌었다");
            }
            if (!sorted(previousRequest.courseIds()).equals(sorted(courseIds))) {
                out.add("대상 프로젝트가 달라졌다");
            }
            if (!Objects.equals(nz(previousRequest.instruction()), nz(instruction))) {
                out.add("사용자 지시가 달라졌다");
            }
            if (!sorted(previousRequest.excludeTopicIds()).equals(sorted(excluded))) {
                out.add("이번만 제외한 항목이 달라졌다");
            }
        }
        if (!Objects.equals(previous.availabilityHash(), availabilityHash)) {
            out.add("남는 시간(일정·가용 구간)이 달라졌다");
        }
        if (!Objects.equals(previous.materialsHash(), materialsHash)) {
            out.add("자료·구간이 달라졌다(추가·삭제·원본 변경)");
        }
        if (!Objects.equals(previous.assignmentsHash(), assignmentsHash)) {
            out.add("과제 상태(마감·완료·확인)가 달라졌다");
        }
        if (!Objects.equals(previous.progressHash(), progressHash)) {
            out.add("학습 항목의 진행 상태나 표식이 달라졌다");
        }
        return out;
    }

    public boolean sameAs(PlanRequestContext.EvidenceSnapshot previous) {
        return previous != null && Objects.equals(previous.fingerprint(), fingerprint);
    }

    @SafeVarargs
    private static List<AssignmentLine> concat(List<AssignmentLine>... lists) {
        List<AssignmentLine> out = new ArrayList<>();
        for (List<AssignmentLine> l : lists) {
            if (l != null) {
                out.addAll(l);
            }
        }
        return out;
    }

    private static String nz(String s) {
        return s == null ? "" : s.strip();
    }

    private static List<Long> sorted(Collection<Long> ids) {
        return ids == null ? List.of() : new TreeSet<>(ids).stream().toList();
    }

    static String hash(Collection<String> parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                digest.update(part.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest()).substring(0, 20);
        } catch (Exception e) {
            return String.valueOf(parts.hashCode());
        }
    }
}
