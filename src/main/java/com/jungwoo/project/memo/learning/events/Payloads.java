package com.jungwoo.project.memo.learning.events;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * verb별 payload(설계 20번 §6.2, 계획 §3). 모두 {@code v}(판)를 가진다. 원문 텍스트 필드는 없다 —
 * 막힌 단계 글은 {@code hasStuckStep} 표시만, 시험 이름은 키({@code examKey}, 40자 이하)로만 남긴다.
 */
public final class Payloads {

    public static final int V = 1;

    private Payloads() {
    }

    /** 실행 기록의 시도. outcome: DONE_UNGRADED · PARTIAL · CORRECT · WRONG. supportLevel: SOLO · GUIDED · UNKNOWN. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Attempted(int v, String outcome, String supportLevel, String blockerKind, Boolean hasStuckStep,
                            Integer completionPercent) implements EventPayload {
        public Attempted(String outcome, String supportLevel, String blockerKind, Boolean hasStuckStep,
                         Integer completionPercent) {
            this(V, outcome, supportLevel, blockerKind, hasStuckStep, completionPercent);
        }
    }

    public record Stuck(int v) implements EventPayload {
        public Stuck() {
            this(V);
        }
    }

    /** resolves = 닫는 STUCK 이벤트. helped = 도움을 받아 해결. */
    public record Resolved(int v, long resolves, boolean helped) implements EventPayload {
        public Resolved(long resolves, boolean helped) {
            this(V, resolves, helped);
        }

        @Override
        public List<Long> referencedEvents() {
            return List.of(resolves);
        }

        @Override
        public List<Long> liveStuckRefs() {
            return List.of(resolves);
        }
    }

    /** level: KNOW · UNSURE · NEW. via: STATUS(토픽 진도) · MARK(표식) · SELF_CHECK(점검). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SelfAssessed(int v, String level, String via) implements EventPayload {
        public SelfAssessed(String level, String via) {
            this(V, level, via);
        }
    }

    /**
     * 사용자 진술. claim: PROGRESS(진도를 말함) · COVERED_IN_WEEK(정리안 CLASS) · TEXTBOOK_NOT_USED …
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Stated(int v, String claim, Integer week, Integer classSeq, List<SourceRef> sources)
            implements EventPayload {
        public Stated(String claim, Integer week, Integer classSeq, List<SourceRef> sources) {
            this(V, claim, week, classSeq, sources == null || sources.isEmpty() ? null : List.copyOf(sources));
        }

        @Override
        public List<SourceRef> referencedSources() {
            return sources == null ? List.of() : sources;
        }
    }

    /** 수업 회차에서 다룬 원천 집합. unknownContent = "다뤘는데 자료가 아직 없음". */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Covered(int v, List<SourceRef> sources, Boolean unknownContent) implements EventPayload {
        public Covered(List<SourceRef> sources, Boolean unknownContent) {
            this(V, sources == null ? List.of() : List.copyOf(sources), unknownContent);
        }

        @Override
        public List<SourceRef> referencedSources() {
            return sources;
        }
    }

    /** 시험 범위 공지. coordinate: TOC · WEEK_PLANNED · WEEK_ACTUAL · MATERIAL · UNKNOWN. state: RESOLVED · AMBIGUOUS. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ScopeAnnounced(int v, String examKey, String coordinate, List<SourceRef> anchor, String state)
            implements EventPayload {
        public ScopeAnnounced(String examKey, String coordinate, List<SourceRef> anchor, String state) {
            this(V, examKey, coordinate, anchor == null ? List.of() : List.copyOf(anchor), state);
        }

        @Override
        public List<SourceRef> referencedSources() {
            return anchor;
        }
    }

    /** 시험·계획 범위 제외(course_scope_exclusions). */
    public record ScopeExcluded(int v, String examKey, boolean restore) implements EventPayload {
        public ScopeExcluded(String examKey, boolean restore) {
            this(V, examKey, restore);
        }
    }

    /** activity: READ · PLAN_ITEM · PHOTO_TURN. */
    public record Studied(int v, String activity) implements EventPayload {
        public Studied(String activity) {
            this(V, activity);
        }
    }

    /** 자료 공개(업로드·과목 연결). uploadedAt = 연결 시각(교수 공개 시각이 아니다). */
    public record Released(int v, String uploadedAt, String materialType) implements EventPayload {
        public Released(String uploadedAt, String materialType) {
            this(V, uploadedAt, materialType);
        }
    }

    /** 휴강·결석(수업 확인, 일정 예외). */
    public record Empty(int v) implements EventPayload {
        public Empty() {
            this(V);
        }
    }

    /** 다른 이벤트를 철회한다(대상은 뷰에서 빠진다). */
    public record Retracted(int v, long target) implements EventPayload {
        public Retracted(long target) {
            this(V, target);
        }

        @Override
        public List<Long> referencedEvents() {
            return List.of(target);
        }
    }

    /** target을 by로 대체한다(다른 원본 사이의 대체 — 1단계에는 만드는 경로가 없다). */
    public record Supersedes(int v, long target, long by) implements EventPayload {
        public Supersedes(long target, long by) {
            this(V, target, by);
        }

        @Override
        public List<Long> referencedEvents() {
            return List.of(target, by);
        }
    }

    /** 회차 이동(보강). movedTo = 옮긴 날짜(yyyy-MM-dd). */
    public record SessionMoved(int v, String movedTo) implements EventPayload {
        public SessionMoved(String movedTo) {
            this(V, movedTo);
        }
    }
}
