package com.jungwoo.project.memo.plan.generation;

import com.jungwoo.project.memo.plan.PlanMaterialContextService.AssignmentLine;
import com.jungwoo.project.memo.plan.PlanMaterialContextService.CourseCatalog;
import com.jungwoo.project.memo.plan.PlanMaterialContextService.SectionLine;
import com.jungwoo.project.memo.plan.PlanMaterialContextService.TopicLine;
import com.jungwoo.project.memo.plan.selection.PlanRequestContext;
import com.jungwoo.project.memo.scheduling.domain.BusyWindow;

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
     * @param busy 현재 시각으로 잘라내기 <b>전의</b> 일정 — 시각이 박힌 실행 항목·수업 발생분·약속(가용시간 추정의 입력).
     *             남는 시간 구간은 "지금부터"로 잘려 1분만 지나도 달라지므로 지문에 넣지 않는다. 대신 그 구간을 만든 일정을
     *             본다: 구간이 13~23시에서 14~23시로 줄었다면 13~14시에 일정이 생긴 것이고, 그것은 여기서 잡힌다. 종료 시각
     *             변경·분할·삭제·수업 시각 변경도 마찬가지다. 시간이 흐른 것만으로는 바뀌지 않는다.
     * @param now  지문을 찍는 시각. 날짜가 바뀌면(자정 경과) 지문이 달라져 지난 날짜·마감을 다시 확인한다. 최종 입력의 남는
     *             시간은 언제나 지금 이후의 실제 구간이다 — 지문은 재사용 판단과 변경 감지에만 쓴다.
     */
    public static EvidenceFingerprint of(List<CourseCatalog> catalogs, List<BusyWindow> busy, LocalDateTime now,
                                         LocalDate start, LocalDate end, Collection<Long> courseIds, String instruction,
                                         Collection<Long> excluded, Collection<Long> requestedMaterials) {
        TreeSet<String> availability = new TreeSet<>();
        LocalDate today = now == null ? null : now.toLocalDate();
        for (BusyWindow b : busy == null ? List.<BusyWindow>of() : busy) {
            availability.add((b.source() == null ? "?" : b.source().name()) + ":" + b.sourceId() + ":" + b.startAt() + "~"
                    + b.endAt());
        }
        TreeSet<String> materials = new TreeSet<>();
        TreeSet<String> assignments = new TreeSet<>();
        TreeSet<String> progress = new TreeSet<>();
        for (CourseCatalog c : catalogs == null ? List.<CourseCatalog>of() : catalogs) {
            for (SectionLine s : c.sections()) {
                materials.add("s" + s.section().getSectionId() + ":" + s.section().getFileHash());
            }
            for (AssignmentLine a : concat(c.open(), c.completed(), c.unconfirmed())) {
                LocalDate due = a.assignment().dueDay();
                boolean overdue = due != null && today != null && due.isBefore(today);
                assignments.add("a" + a.assignment().getAssignmentId() + ":" + a.assignment().getVersion() + ":"
                        + a.assignment().isCompleted() + ":" + a.assignment().getConfirmStatus() + ":" + due
                        + (overdue ? ":overdue" : ""));
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
        all.add("day:" + today);
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
        return changesFrom(previous, previousRequest, start, end, courseIds, instruction, excluded, null);
    }

    /** @param today 지문을 찍은 날짜. 이전 스냅샷의 날짜와 다르면 그 사실을 적는다 */
    public List<String> changesFrom(PlanRequestContext.EvidenceSnapshot previous, PlanRequestContext previousRequest,
                                    LocalDate start, LocalDate end, Collection<Long> courseIds, String instruction,
                                    Collection<Long> excluded, LocalDate today) {
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
            out.add("일정(수업·약속·시각이 정해진 항목)이 달라져 남는 시간이 바뀌었다");
        }
        LocalDate previousDay = previous.capturedAt() == null ? null : previous.capturedAt().toLocalDate();
        if (previousDay != null && today != null && !previousDay.equals(today)) {
            out.add("날짜가 " + previousDay + "에서 " + today + "로 바뀌어 오늘 이후의 일정·마감을 다시 확인했다");
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
